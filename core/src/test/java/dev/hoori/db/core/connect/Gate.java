package dev.hoori.db.core.connect;

import dev.hoori.db.core.connection.Provider;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A TCP server on a free port of {@link #HOST} that accepts connections and holds them without a
 * word, as a server does that hangs; once {@link #open}ed, it passes them on to the real server
 * behind it, and the server's answers back as its {@link Relay} lets them.
 */
final class Gate implements AutoCloseable {

  /** The address the gate listens on, which its clients connect to. */
  static final String HOST = "127.0.0.1";

  private static final Duration WAIT = Duration.ofSeconds(10);

  /** What of the server's answers reaches the client. */
  interface Relay {
    void copy(InputStream server, OutputStream client) throws IOException, InterruptedException;
  }

  /** All of it. */
  static final Relay ALL = (server, client) -> server.transferTo(client);

  /** None of it: the client waits for its first answer. */
  static final Relay NONE = (server, client) -> server.transferTo(OutputStream.nullOutputStream());

  /** The first bytes, and nothing after them. */
  static Relay bytes(int count) {
    return (server, client) -> {
      client.write(server.readNBytes(count));
      server.transferTo(OutputStream.nullOutputStream());
    };
  }

  /** The first messages of a connection without TLS, and nothing after them. */
  static Relay messages(Provider provider, int count) {
    return (server, client) -> {
      var in = new DataInputStream(server);
      for (int i = 0; i < count; i++) {
        byte[] message;
        if (provider == Provider.POSTGRESQL) {
          // A type, and a length that counts itself.
          var head = in.readNBytes(5);
          var length = ((head[1] & 0xff) << 24)
              | ((head[2] & 0xff) << 16)
              | ((head[3] & 0xff) << 8)
              | (head[4] & 0xff);
          message = concat(head, in.readNBytes(length - 4));
        } else {
          // A length of three bytes, little-endian, and a sequence number.
          var head = in.readNBytes(4);
          var length = (head[0] & 0xff) | ((head[1] & 0xff) << 8) | ((head[2] & 0xff) << 16);
          message = concat(head, in.readNBytes(length));
        }
        client.write(message);
      }
      server.transferTo(OutputStream.nullOutputStream());
    };
  }

  /** All of it, a byte at a time with a pause before each. */
  static Relay trickle(Duration pause) {
    return (server, client) -> {
      for (int b; (b = server.read()) >= 0; ) {
        Thread.sleep(pause);
        client.write(b);
      }
    };
  }

  private final ServerSocket server;
  private final String host;
  private final int port;
  private final Relay relay;
  private final CountDownLatch opened = new CountDownLatch(1);
  private final CountDownLatch accepted = new CountDownLatch(1);
  private final CountDownLatch ended = new CountDownLatch(1);
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
  private boolean closed;

  /** A gate before the server at the host and port, which passes all of its answers. */
  Gate(String host, int port) throws IOException {
    this(host, port, ALL);
  }

  Gate(String host, int port, Relay relay) throws IOException {
    this.server = new ServerSocket(0, 50, address());
    this.host = host;
    this.port = port;
    this.relay = relay;
    Thread.ofVirtual().start(this::accept);
  }

  private static InetAddress address() throws UnknownHostException {
    return InetAddress.getByName(HOST);
  }

  int port() {
    return server.getLocalPort();
  }

  /** Waits until a client has connected, and reports whether one did in time. */
  boolean awaitClient() throws InterruptedException {
    return accepted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Waits until a client that was let through has gone away, and reports whether it did within the
   * time.
   */
  boolean awaitEnd(Duration timeout) throws InterruptedException {
    return ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** What the clients sent to the server. */
  byte[] sent() {
    synchronized (sent) {
      return sent.toByteArray();
    }
  }

  /** Lets what waits through, and all that comes later. */
  void open() {
    opened.countDown();
  }

  private void accept() {
    try {
      while (true) {
        var client = server.accept();
        sockets.add(client);
        accepted.countDown();
        Thread.ofVirtual().start(() -> pass(client));
      }
    } catch (IOException e) {
      // The gate is closed.
    }
  }

  private void pass(Socket client) {
    try {
      opened.await();
      Socket upstream;
      // Under the lock that close takes, so that a gate closed meanwhile opens nothing.
      synchronized (this) {
        if (closed) {
          close(client);
          return;
        }
        upstream = new Socket(host, port);
        sockets.add(upstream);
      }
      Thread.ofVirtual().start(() -> {
        try {
          relay.copy(upstream.getInputStream(), client.getOutputStream());
        } catch (IOException | InterruptedException e) {
          // One side went away.
        } finally {
          close(client);
          close(upstream);
        }
      });
      try (var in = client.getInputStream();
          var out = upstream.getOutputStream()) {
        var buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) >= 0; ) {
          synchronized (sent) {
            sent.write(buffer, 0, n);
          }
          out.write(buffer, 0, n);
        }
      } catch (IOException e) {
        // One side went away.
      } finally {
        ended.countDown();
        close(client);
        close(upstream);
      }
    } catch (IOException | InterruptedException e) {
      close(client);
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    var both = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, both, a.length, b.length);
    return both;
  }

  private static void close(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // Closed already.
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      closed = true;
    }
    opened.countDown();
    server.close();
    sockets.forEach(Gate::close);
  }
}
