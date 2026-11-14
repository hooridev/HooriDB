package dev.hoori.db.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ConnectionProfile.Problem;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** What needs no database server: the drivers are real, and nothing answers them. */
class ConnectorTest {

  static final String WRONG_PASSWORD = "Wrong-pw-9zK!4q";

  @ParameterizedTest
  @EnumSource(Provider.class)
  void anUnfitProfileIsRefusedBeforeTheDriverSeesIt(Provider provider) {
    var profile = ConnectionProfile.of(provider).withPort(0);
    var e = assertFailure(
        Failure.UNFIT,
        () -> Connector.test(
            profile, TestServer.password(), TestServer.driver(provider), Duration.ofSeconds(5)));
    assertEquals(Set.of(Problem.HOST_MISSING, Problem.PORT_OUT_OF_RANGE), e.problems());
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aClosedPortIsUnreachable(Provider provider) throws Exception {
    int port;
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    var profile = local(provider).withPort(port);
    assertFailure(
        Failure.UNREACHABLE,
        () -> Connector.test(
            profile, TestServer.password(), TestServer.driver(provider), Duration.ofSeconds(5)));
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void anUnknownHostIsUnreachable(Provider provider) {
    // .invalid is reserved never to resolve.
    var profile = local(provider).withHost("no-such-host.invalid");
    assertFailure(
        Failure.UNREACHABLE,
        () -> Connector.test(
            profile, TestServer.password(), TestServer.driver(provider), Duration.ofSeconds(5)));
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aServerThatNeverAnswersTimesOutInTime(Provider provider) throws Exception {
    // VERIFIED waits in the same place as ENCRYPTED.
    for (var tls : new Tls[] {Tls.OFF, Tls.ENCRYPTED}) {
      try (var gate = new Gate(null, 0)) {
        var profile = local(provider).withPort(gate.port()).withTls(tls);
        var start = System.nanoTime();
        assertFailure(
            Failure.TIMED_OUT,
            () -> Connector.open(
                profile,
                TestServer.password(),
                TestServer.driver(provider),
                Duration.ofSeconds(1)));
        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertTrue(elapsed.compareTo(Duration.ofMillis(900)) > 0, elapsed::toString);
        assertTrue(elapsed.compareTo(Duration.ofMillis(2500)) < 0, elapsed::toString);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void anInterruptedCallerIsReleasedAtOnce(Provider provider) throws Exception {
    try (var gate = new Gate(null, 0)) {
      var profile = local(provider).withPort(gate.port());
      var driver = TestServer.driver(provider);
      var failure = new ConnectionException[1];
      var stillInterrupted = new boolean[1];
      var caller = Thread.ofPlatform().start(() -> {
        failure[0] = assertThrows(
            ConnectionException.class,
            () -> Connector.open(profile, TestServer.password(), driver, Duration.ofSeconds(30)));
        stillInterrupted[0] = Thread.currentThread().isInterrupted();
      });
      gate.awaitClient();
      var start = System.nanoTime();
      caller.interrupt();
      caller.join(Duration.ofSeconds(5));
      assertFalse(caller.isAlive());
      assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 500);
      assertEquals(Failure.CANCELLED, failure[0].failure());
      assertTrue(stillInterrupted[0]);
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aCallerInterruptedBeforehandDoesNotConnect(Provider provider) throws Exception {
    try (var gate = new Gate(null, 0)) {
      var profile = local(provider).withPort(gate.port());
      Thread.currentThread().interrupt();
      try {
        assertFailure(
            Failure.CANCELLED,
            () -> Connector.open(
                profile,
                TestServer.password(),
                TestServer.driver(provider),
                Duration.ofSeconds(5)));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  private static ConnectionProfile local(Provider provider) {
    return ConnectionProfile.of(provider)
        .withHost("127.0.0.1")
        .withDatabase(TestServer.DATABASE)
        .withUser(TestServer.USER);
  }

  interface Call {
    Object run() throws Exception;
  }

  /**
   * Asserts the failure, and that nothing it says or holds, down to the causes in its stack trace,
   * repeats a password.
   */
  static ConnectionException assertFailure(Failure failure, Call call) {
    var e = assertThrows(ConnectionException.class, call::run);
    var trace = new StringWriter();
    e.printStackTrace(new PrintWriter(trace));
    assertEquals(failure, e.failure(), trace::toString);
    for (var text : new String[] {trace.toString(), e.toString(), e.detail()}) {
      assertFalse(text.contains(TestServer.PASSWORD), text);
      assertFalse(text.contains(WRONG_PASSWORD), text);
    }
    return e;
  }
}
