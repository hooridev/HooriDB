package dev.hoori.db.core.query;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A TCP relay to a real server, on a free port of 127.0.0.1, which can hold up what goes through
 * it: every connection but the first waits {@link #delay} before it is passed on, as a cancel's
 * connection does over a slow network, and {@link #freeze} holds everything, as a server that is
 * paused does.
 */
final class Relay implements AutoCloseable {

  static final String HOST = "127.0.0.1";

  private final ServerSocket server;
  private final String host;
  private final int port;
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private volatile Duration delay = Duration.ZERO;
  private volatile boolean frozen;
  private int accepted;

  Relay(String host, int port) throws IOException {
    this.server = new ServerSocket(0, 50, InetAddress.getByName(HOST));
    this.host = host;
    this.port = port;
    Thread.ofVirtual().start(this::accept);
  }

  int port() {
    return server.getLocalPort();
  }

  /** How long every connection after the first waits before it reaches the server. */
  void delay(Duration delay) {
    this.delay = delay;
  }

  /** From now on nothing goes through, and new connections wait. */
  void freeze() {
    frozen = true;
  }

  private void accept() {
    try {
      while (true) {
        var client = server.accept();
        sockets.add(client);
        var later = accepted++ > 0;
        Thread.ofVirtual().start(() -> pass(client, later));
      }
    } catch (IOException e) {
      // The relay is closed.
    }
  }

  private void pass(Socket client, boolean later) {
    try {
      if (later) {
        Thread.sleep(delay);
      }
      while (frozen) {
        Thread.sleep(10);
      }
      var upstream = new Socket(host, port);
      sockets.add(upstream);
      Thread.ofVirtual().start(() -> copy(upstream, client));
      copy(client, upstream);
    } catch (IOException | InterruptedException e) {
      close(client);
    }
  }

  private void copy(Socket from, Socket to) {
    try (from;
        to) {
      InputStream in = from.getInputStream();
      OutputStream out = to.getOutputStream();
      var buffer = new byte[8192];
      for (int read; (read = in.read(buffer)) >= 0; ) {
        while (frozen) {
          Thread.sleep(10);
        }
        out.write(buffer, 0, read);
        out.flush();
      }
    } catch (IOException | InterruptedException e) {
      // One side is gone; closing both ends the other copy.
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
    frozen = false;
    server.close();
    sockets.forEach(Relay::close);
  }
}
