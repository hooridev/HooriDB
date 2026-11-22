package dev.hoori.db.app;

import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connect.Server;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.driver.LoadedDriver;
import java.time.Duration;

/** Tests a connection: logs in to its server and out again. Blocks, off the app's thread. */
@FunctionalInterface
interface Tester {

  /** Through the driver to the real server, of SQL or of documents. */
  Tester SERVER = (profile, password, driver, timeout) -> {
    try {
      return new Reached(
          driver.documents()
              ? DocumentConnector.test(profile, password, driver, timeout)
              : Connector.test(profile, password, driver, timeout));
    } catch (ConnectionException e) {
      return new Refused(e.failure(), e.detail());
    }
  };

  Result test(ConnectionProfile profile, char[] password, LoadedDriver driver, Duration timeout);

  /** What a test found. */
  sealed interface Result {}

  /** The server let the user in. */
  record Reached(Server server) implements Result {}

  /** Why the server could not be reached or refused, with the driver's own words. */
  record Refused(ConnectionException.Failure failure, String detail) implements Result {}
}
