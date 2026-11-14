package dev.hoori.db.core.connect;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A TCP server on a free local port that accepts connections and holds them without a word, as a
 * server does that hangs; once {@link #open}ed, it passes them on to the real server behind it.
 */
final class Gate implements AutoCloseable {

  private final ServerSocket server;
  private final String host;
  private final int port;
  private final CountDownLatch opened = new CountDownLatch(1);
  private final CountDownLatch accepted = new CountDownLatch(1);
  private final CountDownLatch passed = new CountDownLatch(1);
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();

  /** A gate before the server at the host and port. */
  Gate(String host, int port) throws IOException {
    this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.host = host;
    this.port = port;
    Thread.ofVirtual().start(this::accept);
  }

  int port() {
    return server.getLocalPort();
  }

  /** Waits until a client has connected. */
  void awaitClient() throws InterruptedException {
    accepted.await();
  }

  /** Waits until a client that was let through has gone away, and reports whether it did. */
  boolean awaitPassed(Duration timeout) throws InterruptedException {
    return passed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
    } catch (IOException closed) {
      // The gate is closed.
    }
  }

  private void pass(Socket client) {
    try {
      opened.await();
      var upstream = new Socket(host, port);
      sockets.add(upstream);
      Thread.ofVirtual().start(() -> copy(upstream, client));
      copy(client, upstream);
      passed.countDown();
    } catch (IOException | InterruptedException e) {
      close(client);
    }
  }

  private static void copy(Socket from, Socket to) {
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      in.transferTo(out);
    } catch (IOException e) {
      // One side went away.
    } finally {
      close(from);
      close(to);
    }
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
    server.close();
    sockets.forEach(Gate::close);
  }
}
