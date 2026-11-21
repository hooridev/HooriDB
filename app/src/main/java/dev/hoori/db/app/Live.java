package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.TabGroups;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.driver.LoadedDriver;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A connection the user opened in the tree: where it is on its way to being connected, its server
 * connections and what was read of them. The tree ({@link CatalogTree}) moves it on; only the app's
 * thread uses it, but for the password's {@link Handover}.
 */
final class Live {
  final ConnectionProfile profile; // as it was when opened
  Phase phase;
  Work work; // reading the password, or null
  Lane.Asked connecting; // the wait for the first connection, or null
  Handover handover; // the password on its way from the keyring, or null
  LoadedDriver driver;
  boolean typing; // the user asked to type the password, which nothing kept is tried before
  // The tabs whose window asks for the password: a console's there; null for the main window.
  TabGroups.Group<Tab> window;
  // While connected, for the connections to further databases; wiped when it ends.
  char[] password;
  Lane lane; // the first connection
  final Map<String, Lane> lanes = new HashMap<>(); // further connections, by database
  // The connections of the consoles' estimates, by database on PostgreSQL, else one under "".
  final Map<String, Lane> planners = new HashMap<>();
  final Map<Node, CatalogTree.Level> levels = new HashMap<>();
  // The background reads of go to table and of completions: a database's tables, a table's
  // columns.
  final Map<Node, CatalogTree.Scan> scans = new HashMap<>();
  // The password dialog; the kit's field holds a String, which goes with the dialog.
  String typed = "";
  boolean revealed;
  boolean keep;

  Live(ConnectionProfile profile) {
    this.profile = profile;
  }

  UUID id() {
    return profile.id();
  }

  /** Where a connection the user opened is. */
  sealed interface Phase {}

  /** For its driver, which the {@link Fetch} of its kind gets. */
  record ForDriver() implements Phase {}

  /** For the system keyring to be found. */
  record ForKeyring() implements Phase {}

  /** For the keyring to give the password. */
  record ReadingPassword() implements Phase {}

  /** For the user to give the password; silent, as the keyring did not answer in time. */
  record ForPassword(boolean silent) implements Phase {}

  record Connecting() implements Phase {}

  /**
   * @param database the one database the first connection reads, or null where it reads them all
   * @param schemas whether the server has a level of schemas
   */
  record Connected(ServerVersion version, String database, boolean schemas) implements Phase {}

  /** @param withPassword whether a password was sent */
  record Failed(ConnectionException.Failure failure, String detail, boolean withPassword)
      implements Phase {}

  /**
   * The connection broke while it was open.
   *
   * @param discarded whether core closed it, as the server did not stop a cancelled call
   */
  record Lost(String detail, boolean discarded) implements Phase {}

  /** A script run has no drivers. */
  record WithoutDrivers() implements Phase {}

  /**
   * A password on its way from the keyring's thread to the app's. Whoever comes last of the two
   * wipes it, should the app's side be cancelled before it takes it.
   */
  static final class Handover {
    private char[] password;
    private boolean closed;

    synchronized void put(char[] given) {
      if (closed) {
        Arrays.fill(given, '\0');
      } else {
        password = given;
      }
    }

    /** The password, which the caller owns now; null if there was none. */
    synchronized char[] take() {
      closed = true;
      var given = password;
      password = null;
      return given;
    }

    synchronized void drop() {
      var given = take();
      if (given != null) {
        Arrays.fill(given, '\0');
      }
    }
  }
}
