package dev.hoori.db.app;

import com.rethinkj.ui.App;
import java.util.Arrays;
import java.util.UUID;

/**
 * The session's {@link Delivery.Tell}: the server's words checked against its own copy of the
 * password, which it wipes once the session has closed and said so. A call given up may still
 * answer after that: a wiped Teller then posts no word of the server's, only that the outcome came
 * too late to be shown. The check and the wipe hold the same lock, so no text ever goes unchecked.
 */
final class Teller implements Delivery.Tell {
  private final App<State> app;
  private final State state;
  private final String connection;
  private final char[] password; // null where the session has none
  private final ConsoleTab of;
  private final UUID on; // the connection of the session it tells of
  private boolean wiped;

  Teller(App<State> app, State state, UUID on, String connection, char[] password, ConsoleTab of) {
    this.app = app;
    this.on = on;
    this.state = state;
    this.connection = connection;
    this.password = password;
    this.of = of;
  }

  /** The connection's name, as its notices say it. */
  String connection() {
    return connection;
  }

  App<State> app() {
    return app;
  }

  State state() {
    return state;
  }

  @Override
  public void tell(String sql, String what, boolean failure) {
    String said;
    synchronized (this) {
      said = wiped ? TOO_LATE : ConsoleTab.words(password, what);
    }
    var statement = ConsoleTab.brief(sql == null ? "" : sql);
    // A query tab that has moved to another connection since does not show it in its Messages.
    app.post(() -> state.notice(
        connection,
        statement,
        said,
        failure,
        of != null && of.id != null && of.id.equals(on) ? of : null));
  }

  /** The session has closed and said all it had to: the password goes. */
  synchronized void wipe() {
    wiped = true;
    if (password != null) {
      Arrays.fill(password, '\0');
    }
  }

  static final String TOO_LATE =
      "Its outcome arrived after its session had closed, too late to be shown; check what it did.";
}
