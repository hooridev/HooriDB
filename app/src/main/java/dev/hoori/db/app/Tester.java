package dev.hoori.db.app;

import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connect.Server;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.driver.LoadedDriver;
import java.time.Duration;

/** Tests a connection: logs in to its server and out again. Blocks, off the app's thread. */
@FunctionalInterface
interface Tester {

  /** How long reaching the server and logging in may take. */
  Duration TIMEOUT = Duration.ofSeconds(10);

  /** Through the driver to the real server. */
  Tester SERVER = (profile, password, driver) -> {
    try {
      return new Reached(Connector.test(profile, password, driver, TIMEOUT));
    } catch (ConnectionException e) {
      return new Refused(e.failure(), e.detail());
    }
  };

  Result test(ConnectionProfile profile, char[] password, LoadedDriver driver);

  /** What a test found. */
  sealed interface Result {}

  /** The server let the user in. */
  record Reached(Server server) implements Result {}

  /** Why the server could not be reached or refused, with the driver's own words. */
  record Refused(ConnectionException.Failure failure, String detail) implements Result {}
}
