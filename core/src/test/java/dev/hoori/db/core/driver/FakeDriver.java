package dev.hoori.db.core.driver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

/** Builds the JAR of a made-up JDBC driver, compiled by the JDK's compiler. */
final class FakeDriver {

  static final Driver DRIVER = new Driver("dev.hoori.fake", "fake-jdbc", "fake.jdbc.FakeDriver");

  private FakeDriver() {}

  /** A driver whose connections answer {@code getCatalog()} with the version. */
  static byte[] jar(String version, Path work) throws Exception {
    var source = """
        package fake.jdbc;

        import java.lang.reflect.Proxy;
        import java.sql.*;
        import java.util.Properties;
        import java.util.logging.Logger;

        public class FakeDriver implements Driver {
          public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
              return null;
            }
            return (Connection) Proxy.newProxyInstance(
                FakeDriver.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> method.getName().equals("getCatalog") ? "%s" : null);
          }

          public boolean acceptsURL(String url) {
            return url.startsWith("jdbc:fake:");
          }

          public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
          }

          public int getMajorVersion() {
            return 1;
          }

          public int getMinorVersion() {
            return 0;
          }

          public boolean jdbcCompliant() {
            return false;
          }

          public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
          }
        }
        """.formatted(version);
    var src = Files.createDirectories(work.resolve("src-" + version).resolve("fake/jdbc"));
    var classes = Files.createDirectories(work.resolve("classes-" + version));
    var file = Files.writeString(src.resolve("FakeDriver.java"), source);
    var compiler = ToolProvider.getSystemJavaCompiler();
    int status = compiler.run(null, null, null, "-d", classes.toString(), file.toString());
    if (status != 0) {
      throw new IllegalStateException("The fake driver does not compile");
    }
    var bytes = new ByteArrayOutputStream();
    try (var jar = new JarOutputStream(bytes);
        var paths = Files.walk(classes)) {
      for (var path : paths.filter(Files::isRegularFile).toList()) {
        jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
        jar.write(Files.readAllBytes(path));
        jar.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  /** A JAR with one text file, whose manifest names the class path, if there is one. */
  static byte[] plain(String classPath) {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (classPath != null) {
      manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
    }
    var bytes = new ByteArrayOutputStream();
    try (var jar = new JarOutputStream(bytes, manifest)) {
      jar.putNextEntry(new JarEntry("hello.txt"));
      jar.write("hello".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }
}
