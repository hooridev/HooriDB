package dev.hoori.db.core.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.query.QueryException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The monitor of a real MongoDB, with a second client whose find sleeps in the server. */
class DocumentMonitorServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Test
  void theSessionsHoldTheMonitorsOwnAndAnotherWithWhatItRuns() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var mark = "m" + UUID.randomUUID();
    try (var monitor = session(server.profile(), false);
        var other = session(server.profile(), false)) {
      var sleeping = sleep(other, mark, 8);
      try {
        var sessions = new DocumentMonitor(monitor).sessions();
        var own = sessions.stream().filter(ServerSession::own).toList();
        assertEquals(1, own.size(), sessions.toString());
        assertFalse(own.getFirst().cancellable());
        var found = MonitorServerTest.waitFor(new DocumentMonitor(monitor), mark);
        assertEquals(TestServer.USER, found.user());
        assertEquals(TestServer.DATABASE, found.database());
        assertTrue(found.client().startsWith("HooriDB ("), found.client());
        assertEquals("active", found.state());
        assertTrue(found.cancellable());
        // The server cannot end another client's connection.
        assertFalse(found.ends());
        assertNotNull(found.elapsed());
        assertFalse(found.statement().contains("Pw-secret"), found.statement());
        assertTrue(found.statement().contains(Credentials.MASK), found.statement());
        assertFalse(found.statement().contains("lsid"), found.statement());
        // The idle connections of clients show too.
        assertTrue(sessions.stream().anyMatch(s -> s.state().equals("idle")), sessions.toString());
      } finally {
        sleeping.join();
      }
    }
  }

  @Test
  void aUserWithoutThePrivilegeSeesTheirOwn() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var reader = server.profile().withUser("reader").withAuthDatabase(TestServer.DATABASE);
    try (var monitor = session(reader, true)) {
      var sessions = new DocumentMonitor(monitor).sessions();
      assertTrue(sessions.stream().anyMatch(ServerSession::own), sessions.toString());
      assertTrue(sessions.stream().allMatch(s -> s.user().equals("reader")), sessions.toString());
    }
  }

  @Test
  void aCancelKillsTheOperationAndEndingIsNotThere() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var mark = "m" + UUID.randomUUID();
    try (var monitor = session(server.profile(), false);
        var other = session(server.profile(), false)) {
      var sleeping = sleep(other, mark, 30);
      var reader = new DocumentMonitor(monitor);
      var found = MonitorServerTest.waitFor(reader, mark);
      assertTrue(reader.cancel(found));
      // Killed long before its time limit.
      sleeping.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertThrows(IllegalArgumentException.class, () -> reader.end(found));
    }
  }

  @Test
  void aReadOnlyConnectionReadsTheSessionsButSendsNoKill() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var mark = "m" + UUID.randomUUID();
    try (var monitor = session(server.profile(), true);
        var other = session(server.profile(), false)) {
      var sleeping = sleep(other, mark, 3);
      try {
        var reader = new DocumentMonitor(monitor);
        var found = MonitorServerTest.waitFor(reader, mark);
        var refused = assertThrows(QueryException.class, () -> reader.cancel(found));
        assertTrue(refused.detail().contains("read-only"), refused.detail());
      } finally {
        sleeping.join();
      }
    }
  }

  /** A find whose filter sleeps on the server for the seconds, then stops at its time limit. */
  static CompletableFuture<Void> sleep(DocumentSession session, String mark, int seconds) {
    return CompletableFuture.runAsync(() -> {
      try {
        session.command(
            TestServer.DATABASE,
            Document.of(
                "find",
                "orders",
                "filter",
                Document.of("$where", "sleep(" + seconds * 1000 + ") || true"),
                "limit",
                1,
                "maxTimeMS",
                seconds * 1000,
                "comment",
                Document.of("mark", mark, "pwd", "Pw-secret")));
      } catch (DocumentException e) {
        // Stopped at its time limit, or killed.
      }
    });
  }

  static DocumentSession session(ConnectionProfile profile, boolean readOnly) throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var opened = DocumentConnector.open(profile, TestServer.password(), server.driver(), TIMEOUT);
    return new DocumentSession(opened.client(), Provider.MONGODB.documents(), readOnly);
  }
}
