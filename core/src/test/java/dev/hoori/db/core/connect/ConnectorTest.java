package dev.hoori.db.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ConnectionProfile.Problem;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** What needs no database server: the drivers are real, and nothing answers them. */
class ConnectorTest {

  static final String WRONG_PASSWORD = "Wrong-pw-9zK!4q";

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anUnfitProfileIsRefusedBeforeTheDriverSeesIt(Provider provider) {
    var profile = ConnectionProfile.of(provider).withPort(0);
    var e = assertFailure(
        Failure.UNFIT,
        () -> Connector.test(
            profile, TestServer.password(), TestServer.driver(provider), Duration.ofSeconds(5)));
    assertEquals(Set.of(Problem.HOST_MISSING, Problem.PORT_OUT_OF_RANGE), e.problems());
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anUnknownHostIsUnreachable(Provider provider) {
    // .invalid is reserved never to resolve.
    var profile = local(provider).withHost("no-such-host.invalid");
    assertFailure(
        Failure.UNREACHABLE,
        () -> Connector.test(
            profile, TestServer.password(), TestServer.driver(provider), Duration.ofSeconds(5)));
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
                    Duration.ofSeconds(1))
                .connection());
        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertTrue(elapsed.compareTo(Duration.ofMillis(900)) > 0, elapsed::toString);
        assertTrue(elapsed.compareTo(Duration.ofMillis(2500)) < 0, elapsed::toString);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anInterruptedCallerIsReleasedAtOnceAndTheDriverStops(Provider provider) throws Exception {
    try (var gate = new Gate(null, 0)) {
      var attempt = Attempt.start(local(provider).withPort(gate.port()), Duration.ofSeconds(30));
      assertTrue(gate.awaitClient());
      assertTrue(attempt.cancel().toMillis() < 500);
      assertEquals(Failure.CANCELLED, attempt.failure().failure());
      assertTrue(attempt.interrupted());
      assertNotNull(Attempt.workersEnd(provider, Duration.ofSeconds(2)));
    }
  }

  // Windows refuses a connect beyond a full queue at once, so no connect can be held open this way.
  @EnabledOnOs(OS.LINUX)
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCancelEndsTheDriverWhileItStillOpensTheSocket(Provider provider) throws Exception {
    var queued = new ArrayList<Socket>();
    try (var listener = new ServerSocket(0, 1, InetAddress.getByName(Gate.HOST))) {
      // Linux drops what comes beyond a full queue, and the next connect waits for an answer.
      var address = new InetSocketAddress(Gate.HOST, listener.getLocalPort());
      // Then the next connect waits.
      assertThrows(
          SocketTimeoutException.class, () -> fill(queued, address), "The queue does not fill");
      var attempt =
          Attempt.start(local(provider).withPort(listener.getLocalPort()), Duration.ofSeconds(30));
      Thread.sleep(300);
      assertTrue(attempt.cancel().toMillis() < 500);
      assertEquals(Failure.CANCELLED, attempt.failure().failure());
      assertNotNull(Attempt.workersEnd(provider, Duration.ofSeconds(2)));
    } finally {
      for (var socket : queued) {
        socket.close();
      }
    }
  }

  // Connects one socket after another, each kept in the list, until one waits or 64 are queued.
  private static void fill(List<Socket> queued, InetSocketAddress address) throws IOException {
    while (queued.size() < 64) {
      var socket = new Socket();
      queued.add(socket);
      socket.connect(address, 200);
    }
  }

  @Test
  void anInterruptWinsOverAConnectionThatIsThereAlready() {
    var closes = new AtomicInteger();
    var connection = (Connection) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
          if (method.getName().equals("close")) {
            closes.incrementAndGet();
          }
          return null;
        });
    Thread.currentThread().interrupt();
    try {
      var e = assertThrows(
          ConnectionException.class,
          () -> Connector.await(
              CompletableFuture.completedFuture(new Opened(connection, null, null)),
              Thread.ofVirtual().unstarted(() -> {}),
              Duration.ofSeconds(1),
              thrown -> {
                throw new AssertionError(thrown);
              }));
      assertEquals(Failure.CANCELLED, e.failure());
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, closes.get());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void anInterruptWinsOverAFailureThatIsThereAlready() {
    Thread.currentThread().interrupt();
    try {
      var e = assertThrows(
          ConnectionException.class,
          () -> Connector.await(
              CompletableFuture.failedFuture(new SQLException("refused", "28000")),
              Thread.ofVirtual().unstarted(() -> {}),
              Duration.ofSeconds(1),
              thrown -> {
                throw new AssertionError(thrown);
              }));
      assertEquals(Failure.CANCELLED, e.failure());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aServerThatRepeatsThePasswordHasItsMessageWithheld() throws Exception {
    try (var server = new FakePostgres(null, (in, out) -> {
      FakePostgres.askPassword(out);
      FakePostgres.refuse(out, "password \"" + FakePostgres.readPassword(in) + "\" is wrong");
    })) {
      var e = assertFailure(
          Failure.LOGIN_REFUSED,
          () -> Connector.test(
              postgres(server.port(), Tls.OFF),
              TestServer.password(),
              TestServer.driver(Provider.POSTGRESQL),
              Duration.ofSeconds(5)));
      assertEquals(ConnectionException.WITHHELD, e.detail());
      assertNull(e.getCause());
    }
  }

  @Test
  void aShortPasswordWithholdsAMessageThatMerelyContainsIt() throws Exception {
    try (var server = new FakePostgres(null, (in, out) -> {
      FakePostgres.askPassword(out);
      FakePostgres.readPassword(in);
      FakePostgres.refuse(out, "no entry");
    })) {
      var e = assertThrows(
          ConnectionException.class,
          () -> Connector.test(
              postgres(server.port(), Tls.OFF),
              "e".toCharArray(),
              TestServer.driver(Provider.POSTGRESQL),
              Duration.ofSeconds(5)));
      assertEquals(Failure.LOGIN_REFUSED, e.failure());
      assertEquals(ConnectionException.WITHHELD, e.detail());
    }
  }

  /**
   * Opening reads the database the connection is on, which pgjdbc asks the server for with a
   * query: a server that stops answering after the login fails the opening in time.
   */
  @Test
  void aServerThatStopsAnsweringAfterTheLoginTimesOut() throws Exception {
    try (var server = new FakePostgres(null, (in, out) -> {
      FakePostgres.askPassword(out);
      FakePostgres.readPassword(in);
      FakePostgres.loggedIn(out);
      // The driver's own SET after the login is answered; what it asks then goes unanswered.
      while (in.read() == 'Q') {
        var query = new String(in.readNBytes(in.readInt() - 4), StandardCharsets.UTF_8);
        if (!query.startsWith("SET ")) {
          break;
        }
        FakePostgres.done(out, "SET");
      }
      in.readAllBytes();
    })) {
      long start = System.nanoTime();
      assertFailure(
          Failure.TIMED_OUT,
          () -> Connector.open(
              postgres(server.port(), Tls.OFF),
              TestServer.password(),
              TestServer.driver(Provider.POSTGRESQL),
              Duration.ofSeconds(2)));
      assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
    }
  }

  @Test
  void noTlsIsToldApartFromAMissingPassword() throws Exception {
    // Without a password, a server that refuses TLS is still one that refuses TLS.
    try (var server = new FakePostgres(null, (in, out) -> {})) {
      assertFailure(
          Failure.TLS_UNAVAILABLE,
          () -> Connector.test(
              postgres(server.port(), Tls.ENCRYPTED),
              new char[0],
              TestServer.driver(Provider.POSTGRESQL),
              Duration.ofSeconds(5)));
    }
    // The driver refuses by itself when the server asks for a password that it has not got.
    try (var server = new FakePostgres(null, (in, out) -> FakePostgres.askPassword(out))) {
      assertFailure(
          Failure.LOGIN_REFUSED,
          () -> Connector.test(
              postgres(server.port(), Tls.OFF),
              new char[0],
              TestServer.driver(Provider.POSTGRESQL),
              Duration.ofSeconds(5)));
    }
  }

  @Test
  void anAuthenticationMethodThatTheDriverDoesNotKnowIsNeitherOverTls(@TempDir Path temp)
      throws Exception {
    var tls = FakePostgres.selfSigned(temp);
    try (var server =
        new FakePostgres(tls, (in, out) -> FakePostgres.authenticationRequest(out, 99))) {
      assertFailure(
          Failure.OTHER,
          () -> Connector.test(
              postgres(server.port(), Tls.ENCRYPTED),
              TestServer.password(),
              TestServer.driver(Provider.POSTGRESQL),
              Duration.ofSeconds(5)));
    }
  }

  private static ConnectionProfile postgres(int port, Tls tls) {
    return local(Provider.POSTGRESQL).withPort(port).withTls(tls);
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
                    Duration.ofSeconds(5))
                .connection());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  private static ConnectionProfile local(Provider provider) {
    return ConnectionProfile.of(provider)
        .withHost(Gate.HOST)
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
