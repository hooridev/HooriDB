package dev.hoori.db.core.connect;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * A made-up PostgreSQL server for one connection, which no stock server would be: it answers a
 * request for TLS as told, reads the startup message and then plays its script.
 */
final class FakePostgres implements AutoCloseable {

  /** What the server says after the startup message. */
  interface Script {
    void play(DataInputStream in, DataOutputStream out) throws IOException;
  }

  private static final int SSL_REQUEST = 80877103;
  private static final char[] STORE_PASSWORD = "fake-store".toCharArray();

  private final ServerSocket server;

  /** A server that refuses TLS if the context is null, and offers it with the context if not. */
  FakePostgres(SSLContext tls, Script script) throws IOException {
    server = new ServerSocket(0, 1, InetAddress.getByName(Gate.HOST));
    Thread.ofVirtual().start(() -> {
      try (var socket = server.accept()) {
        serve(socket, tls, script);
      } catch (IOException _) {
        // The client went away, or the server was closed.
      }
    });
  }

  int port() {
    return server.getLocalPort();
  }

  private static void serve(Socket socket, SSLContext tls, Script script) throws IOException {
    var in = new DataInputStream(socket.getInputStream());
    var out = new DataOutputStream(socket.getOutputStream());
    var length = in.readInt();
    var code = in.readInt();
    if (code == SSL_REQUEST) {
      out.writeByte(tls == null ? 'N' : 'S');
      out.flush();
      if (tls == null) {
        return;
      }
      var secure = (SSLSocket) tls.getSocketFactory().createSocket(socket, null, true);
      secure.setUseClientMode(false);
      in = new DataInputStream(secure.getInputStream());
      out = new DataOutputStream(secure.getOutputStream());
      length = in.readInt();
      in.readInt();
    }
    in.readNBytes(length - 8);
    script.play(in, out);
    out.flush();
  }

  /** Asks for the password in plain text. */
  static void askPassword(DataOutputStream out) throws IOException {
    authenticationRequest(out, 3);
  }

  static void authenticationRequest(DataOutputStream out, int type) throws IOException {
    out.writeByte('R');
    out.writeInt(8);
    out.writeInt(type);
    out.flush();
  }

  /** Reads a password message, and gives the password in it. */
  static String readPassword(DataInputStream in) throws IOException {
    if (in.readByte() != 'p') {
      throw new IOException("Not a password message");
    }
    var bytes = in.readNBytes(in.readInt() - 4);
    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
  }

  /** Lets the user in: the login is done, and the server is ready for a query. */
  static void loggedIn(DataOutputStream out) throws IOException {
    authenticationRequest(out, 0);
    for (var parameter : new String[][] {
      {"server_version", "16.4"},
      {"server_encoding", "UTF8"},
      {"client_encoding", "UTF8"},
      {"DateStyle", "ISO, MDY"},
      {"integer_datetimes", "on"},
      {"standard_conforming_strings", "on"},
      {"TimeZone", "UTC"}
    }) {
      var name = parameter[0].getBytes(StandardCharsets.UTF_8);
      var value = parameter[1].getBytes(StandardCharsets.UTF_8);
      out.writeByte('S');
      out.writeInt(4 + name.length + 1 + value.length + 1);
      out.write(name);
      out.write(0);
      out.write(value);
      out.write(0);
    }
    out.writeByte('K');
    out.writeInt(12);
    out.writeInt(4242);
    out.writeInt(2424);
    out.writeByte('Z');
    out.writeInt(5);
    out.writeByte('I');
    out.flush();
  }

  /** Answers a statement as done, with its tag, and is ready for the next. */
  static void done(DataOutputStream out, String tag) throws IOException {
    var bytes = tag.getBytes(StandardCharsets.UTF_8);
    out.writeByte('C');
    out.writeInt(4 + bytes.length + 1);
    out.write(bytes);
    out.write(0);
    out.writeByte('Z');
    out.writeInt(5);
    out.writeByte('I');
    out.flush();
  }

  /** Refuses the login with the message. */
  static void refuse(DataOutputStream out, String message) throws IOException {
    var fields = new ByteArrayOutputStream();
    for (var field : new String[] {"SFATAL", "VFATAL", "C28P01", "M" + message}) {
      fields.writeBytes(field.getBytes(StandardCharsets.UTF_8));
      fields.write(0);
    }
    fields.write(0);
    out.writeByte('E');
    out.writeInt(4 + fields.size());
    out.write(fields.toByteArray());
    out.flush();
  }

  /** TLS with a self-signed certificate, made with the JDK's keytool in the folder. */
  static SSLContext selfSigned(Path folder) throws Exception {
    var store = folder.resolve("fake.p12");
    var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
    var process = new ProcessBuilder(
            keytool.toString(),
            "-genkeypair",
            "-alias",
            "fake",
            "-keyalg",
            "EC",
            "-dname",
            "CN=localhost",
            "-validity",
            "2",
            "-storetype",
            "PKCS12",
            "-keystore",
            store.toString(),
            "-storepass",
            new String(STORE_PASSWORD))
        .redirectErrorStream(true)
        .start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("keytool failed: " + output);
    }
    var keys = KeyStore.getInstance("PKCS12");
    try (var file = Files.newInputStream(store)) {
      keys.load(file, STORE_PASSWORD);
    }
    var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, STORE_PASSWORD);
    var context = SSLContext.getInstance("TLS");
    context.init(managers.getKeyManagers(), null, null);
    return context;
  }

  @Override
  public void close() throws IOException {
    server.close();
  }
}
