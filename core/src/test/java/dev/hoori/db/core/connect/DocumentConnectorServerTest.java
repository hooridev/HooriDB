package dev.hoori.db.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Connecting to a real MongoDB through its driver and core's bridge to it. */
class DocumentConnectorServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Test
  void aTestReadsWhatTheServerIs() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var found =
        DocumentConnector.test(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    assertEquals("MongoDB", found.product());
    assertEquals(8, found.parsed().major(), found.version());
  }

  @Test
  void anOpenClientRunsCommandsUntilClosed() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var opened =
        DocumentConnector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    assertEquals(TestServer.DATABASE, opened.database());
    var client = opened.client();
    try {
      var reply = client.command(
          "hoori", Document.of("count", "orders", "query", Document.of("paid", true)));
      assertEquals(125, ((Number) reply.get("n")).intValue());
      var found = client.command(
          "hoori", Document.of("find", "customers", "filter", Document.of("_id", 1)));
      var batch = (List<?>) ((Document) found.get("cursor")).get("firstBatch");
      var ada = (Document) batch.getFirst();
      assertEquals("Ada", ada.get("name"));
      assertEquals(List.of("math", "poetry"), ada.get("tags"));
      assertEquals(Document.of("city", "London", "zip", "W1"), ada.get("address"));
      var failure = assertThrows(
          DocumentException.class, () -> client.command("hoori", Document.of("frobnicate", 1)));
      assertEquals(DocumentException.Kind.SERVER, failure.kind());
      assertEquals(DocumentException.COMMAND_NOT_FOUND, failure.code());
    } finally {
      client.close();
    }
    var closed = assertThrows(
        DocumentException.class, () -> client.command("admin", Document.of("ping", 1)));
    assertEquals(DocumentException.Kind.CANCELLED, closed.kind());
  }

  @Test
  void aWrongPasswordIsRefusedWithoutBeingQuoted() {
    var server = TestServer.of(Provider.MONGODB);
    var wrong = "Wrong-Pw-9zZ".toCharArray();
    var e = assertThrows(
        ConnectionException.class,
        () -> DocumentConnector.test(server.profile(), wrong, server.driver(), TIMEOUT));
    assertEquals(Failure.LOGIN_REFUSED, e.failure(), e.getMessage());
    assertFalse(e.getMessage().contains("Wrong-Pw-9zZ"));
  }

  /** A user is looked for in the database the profile names, and in admin where it names none. */
  @Test
  void aUserDefinedInAnotherDatabaseLogsInThere() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var reader = server.profile().withUser("reader");
    var found = DocumentConnector.test(
        reader.withAuthDatabase(TestServer.DATABASE),
        TestServer.password(),
        server.driver(),
        TIMEOUT);
    assertEquals("MongoDB", found.product());
    for (var wrong : List.of(reader, reader.withAuthDatabase("shop"))) {
      var e = assertThrows(
          ConnectionException.class,
          () -> DocumentConnector.test(wrong, TestServer.password(), server.driver(), TIMEOUT));
      assertEquals(Failure.LOGIN_REFUSED, e.failure(), e.getMessage());
    }
  }

  @Test
  void aPortWhereNothingListensIsUnreachable() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    // Bound and not listening, so that no other process can listen there meanwhile.
    try (var free = new Socket()) {
      free.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      var profile = server.profile().withHost("127.0.0.1").withPort(free.getLocalPort());
      var e = assertThrows(
          ConnectionException.class,
          () -> DocumentConnector.test(
              profile, TestServer.password(), server.driver(), Duration.ofSeconds(2)));
      assertTrue(
          e.failure() == Failure.UNREACHABLE || e.failure() == Failure.TIMED_OUT, e.getMessage());
    }
  }

  @Test
  // That the app's class path has no driver is the app's DriversTest's: core compiles against it.
  void theBridgeSeesTheDriverAndCore() throws Exception {
    var driver = TestServer.driver(Provider.MONGODB);
    assertTrue(driver.documents());
    assertThrows(IllegalStateException.class, driver::driver);
    var server = TestServer.of(Provider.MONGODB);
    var opened =
        DocumentConnector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    try {
      var client = opened.client();
      var loader = client.getClass().getClassLoader();
      assertSame(Document.class, Class.forName(Document.class.getName(), false, loader));
      assertEquals(
          loader,
          Class.forName("com.mongodb.client.MongoClients", false, loader).getClassLoader());
    } finally {
      opened.client().close();
    }
  }
}
