package dev.hoori.db.core;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.FolderRepository;
import dev.hoori.db.core.driver.LoadedDriver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/**
 * A real database server for tests, in Docker through Testcontainers. {@link #of} gives the server
 * of a provider, started the first time any test asks for one, the three of SQL at once, and
 * shared by every test class that runs in the same JVM; Testcontainers removes them when the JVM ends. Without Docker, the
 * test that asks is skipped. Each server holds the sample schema of {@code
 * src/test/resources/sample-schema/<provider>.sql} in the database {@link #DATABASE}, and the empty
 * databases {@link #SPACED_DATABASE} and {@link #NON_ASCII_DATABASE}, all open to {@link #USER}.
 * Tests that change data clean up after themselves, as the servers are shared.
 *
 * <p>{@link #profile} connects to it as that user, and {@link #driver} is the provider's JDBC
 * driver, copied by Maven into {@code target/jdbc-drivers} and loaded through a {@link DriverStore}
 * the way the app loads it, never from the class path. Each JVM that tests run in has servers and
 * a store of its own.
 *
 * <p>The PostgreSQL server offers no TLS, while MariaDB and MySQL offer it with the self-signed
 * certificate they make for themselves; {@link #otherTls} is a second server of the provider that
 * does the opposite, started only when a test asks for it.
 */
public final class TestServer {

  public static final String USER = "hoori";
  // Made up, and unlike anything a driver would write, so that a test can look for it.
  public static final String PASSWORD = "Pw-hoori-7qX!3z";
  public static final String DATABASE = "hoori";
  public static final String SPACED_DATABASE = "with space";
  public static final String NON_ASCII_DATABASE = "Grüße";

  private static final Map<Provider, TestServer> MAIN = new EnumMap<>(Provider.class);
  private static final Map<Provider, TestServer> OTHER = new EnumMap<>(Provider.class);
  private static final Map<Provider, LoadedDriver> DRIVERS = new EnumMap<>(Provider.class);

  private final Provider provider;
  private final boolean tls;
  private final GenericContainer<?> container;

  private TestServer(Provider provider, boolean tls) {
    this.provider = provider;
    this.tls = tls;
    this.container = container(provider, tls);
  }

  /** The providers of SQL, for tests that run on each of their servers. */
  public static List<Provider> sql() {
    return Provider.SQL;
  }

  /** The shared server of the provider; skips the calling test without Docker. */
  public static TestServer of(Provider provider) {
    return started(MAIN, provider, false);
  }

  /** A second server of the provider, with TLS where {@link #of} has none, and the reverse. */
  public static TestServer otherTls(Provider provider) {
    return started(OTHER, provider, true);
  }

  private static synchronized TestServer started(
      Map<Provider, TestServer> servers, Provider provider, boolean other) {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    if (!servers.containsKey(provider)) {
      var starting = new EnumMap<Provider, TestServer>(Provider.class);
      // The servers of SQL together, as most tests run on each of them; MongoDB on its own.
      for (var p : provider.keepsDocuments() ? List.of(provider) : Provider.SQL) {
        var tls = p != Provider.POSTGRESQL && p != Provider.MONGODB;
        starting.put(p, new TestServer(p, other ? !tls : tls));
      }
      Startables.deepStart(starting.values().stream().map(s -> s.container)).join();
      servers.putAll(starting);
    }
    return servers.get(provider);
  }

  public Provider provider() {
    return provider;
  }

  /** Whether the server offers TLS, with a self-signed certificate. */
  public boolean tls() {
    return tls;
  }

  public String host() {
    return container.getHost();
  }

  public int port() {
    return container.getMappedPort(provider.defaultPort());
  }

  /** A profile for {@link #USER} on {@link #DATABASE}, encrypted where the server offers TLS. */
  public ConnectionProfile profile() {
    return ConnectionProfile.of(provider)
        .withName(provider.displayName() + " test server")
        .withHost(host())
        .withPort(port())
        .withDatabase(DATABASE)
        .withUser(USER)
        .withTls(tls ? Tls.ENCRYPTED : Tls.OFF);
  }

  /** A new copy of {@link #PASSWORD}, which the code under test may wipe. */
  public static char[] password() {
    return PASSWORD.toCharArray();
  }

  /** The provider's driver, loaded once for the run from {@code target/jdbc-drivers}. */
  public static synchronized LoadedDriver driver(Provider provider) {
    var loaded = DRIVERS.get(provider);
    if (loaded == null) {
      try {
        loaded = load(provider, Driver.of(provider));
      } catch (Exception e) {
        throw new IllegalStateException("Cannot load the driver of " + provider, e);
      }
      DRIVERS.put(provider, loaded);
    }
    return loaded;
  }

  public LoadedDriver driver() {
    return driver(provider);
  }

  /**
   * Runs the server's own client in its container on the file, as root on MariaDB and MySQL and as
   * {@link #USER} on PostgreSQL, on the database, stopping at the first error: what it wrote and
   * whether it ended well.
   */
  public Container.ExecResult client(Path file, String database) throws Exception {
    var in = "/tmp/" + file.getFileName();
    container.copyFileToContainer(MountableFile.forHostPath(file), in);
    var command = switch (provider) {
      case POSTGRESQL ->
        new String[] {"psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", USER, "-d", database, "-f", in};
      case MARIADB, MYSQL ->
        new String[] {
          "sh",
          "-c",
          (provider == Provider.MARIADB ? "mariadb" : "mysql") + " -uroot -p'root-" + PASSWORD
              + "' --default-character-set=utf8mb4 \"$1\" < \"$2\"",
          "client",
          database,
          in
        };
      case MONGODB -> throw new IllegalArgumentException("MongoDB has no client of SQL");
    };
    return container.execInContainer(command);
  }

  // Exactly the version the build configures: earlier copies stay in the folder.
  private static LoadedDriver load(Provider provider, Driver driver) throws Exception {
    var copied = Path.of(System.getProperty("hooridb.test.drivers", "target/jdbc-drivers"));
    var key = "hooridb.test." + provider.name().toLowerCase(Locale.ROOT) + ".version";
    var version = System.getProperty(key);
    if (version == null) {
      throw new IllegalStateException("No " + key + "; run the tests through Maven");
    }
    // A folder of its own for each JVM that tests run in, as they run at the same time.
    var work = Path.of(System.getProperty(
        "hooridb.test.store", copied.resolveSibling("test-driver-store").toString()));
    var repository = new FolderRepository(work.resolve("repository"));
    var artifacts = new ArrayList<String>();
    artifacts.add(driver.artifact());
    driver.parts().forEach(part -> artifacts.add(part.artifact()));
    for (var artifact : artifacts) {
      var jar = copied.resolve(artifact + "-" + version + ".jar");
      if (!Files.isRegularFile(jar)) {
        throw new IllegalStateException("No " + jar + "; run the tests through Maven");
      }
      repository.publish(
          new Driver(driver.group(), artifact, driver.driverClass()),
          version,
          Files.readAllBytes(jar),
          FolderRepository.Sum.SHA256,
          Instant.EPOCH);
    }
    var store = new DriverStore(work.resolve("drivers"), repository);
    store.download(driver, version, DriverStore.Progress.NONE);
    return store.load(driver, version);
  }

  private static GenericContainer<?> container(Provider provider, boolean tls) {
    return switch (provider) {
      case POSTGRESQL -> {
        var container = new GenericContainer<>("postgres:18")
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withEnv("POSTGRES_DB", DATABASE)
            .withTmpFs(Map.of("/var/lib/postgresql", "rw"))
            // Once by the server that runs the scripts, and once by the one that stays.
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                .withStartupTimeout(Duration.ofMinutes(2)));
        if (tls) {
          // The image's own self-signed certificate.
          container.withCommand(
              "postgres",
              "-c",
              "ssl=on",
              "-c",
              "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem",
              "-c",
              "ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key");
        }
        yield withScript(container, provider);
      }
      case MARIADB -> {
        var container = new GenericContainer<>("mariadb:12")
            .withEnv("MARIADB_USER", USER)
            .withEnv("MARIADB_PASSWORD", PASSWORD)
            .withEnv("MARIADB_DATABASE", DATABASE)
            .withEnv("MARIADB_ROOT_PASSWORD", "root-" + PASSWORD)
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .waitingFor(mysqlReady());
        if (!tls) {
          container.withCommand("--skip-ssl");
        }
        yield withScript(container, provider);
      }
      case MYSQL -> {
        var container = new GenericContainer<>("mysql:9")
            .withEnv("MYSQL_USER", USER)
            .withEnv("MYSQL_PASSWORD", PASSWORD)
            .withEnv("MYSQL_DATABASE", DATABASE)
            .withEnv("MYSQL_ROOT_PASSWORD", "root-" + PASSWORD)
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .waitingFor(mysqlReady());
        if (!tls) {
          container.withCommand("--tls-version=");
        }
        yield withScript(container, provider);
      }
      case MONGODB -> {
        var container = new GenericContainer<>("mongo:8")
            .withEnv("MONGO_INITDB_ROOT_USERNAME", USER)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", PASSWORD)
            .withTmpFs(Map.of("/data/db", "rw"))
            // Once by the server that runs the scripts, and once by the one that stays.
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 2)
                .withStartupTimeout(Duration.ofMinutes(2)));
        yield withScript(container, provider);
      }
    };
  }

  // The server that runs the scripts says that it listens on port 0, and the one that stays on
  // 3306.
  private static WaitStrategy mysqlReady() {
    return Wait.forLogMessage(".*port: 3306\\b.*", 1).withStartupTimeout(Duration.ofMinutes(2));
  }

  private static GenericContainer<?> withScript(GenericContainer<?> container, Provider provider) {
    if (provider.keepsDocuments()) {
      var script = "sample-schema/" + provider.name().toLowerCase(Locale.ROOT) + ".js";
      return container
          .withExposedPorts(provider.defaultPort())
          .withCopyFileToContainer(
              MountableFile.forClasspathResource(script), "/docker-entrypoint-initdb.d/sample.js");
    }
    var script = "sample-schema/" + provider.name().toLowerCase(Locale.ROOT) + ".sql";
    return container
        .withExposedPorts(provider.defaultPort())
        .withCopyFileToContainer(
            MountableFile.forClasspathResource(script), "/docker-entrypoint-initdb.d/schema.sql");
  }
}
