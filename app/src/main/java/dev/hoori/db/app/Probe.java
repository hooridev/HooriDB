package dev.hoori.db.app;

import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.LoadedDriver;
import java.util.Arrays;

/**
 * Tries the connection of the dialog: gets the driver it needs, which the user agrees to download,
 * and tests the connection with it. It saves nothing.
 */
final class Probe {

  /** Where a test is, from the press on Test connection to its outcome. */
  sealed interface Step {}

  /** For the driver, which {@link #fetch} gets. */
  record Fetching() implements Step {}

  /** For the system keyring, which holds the password to test with. */
  record ForKeyring(LoadedDriver driver) implements Step {}

  record Testing(String host) implements Step {}

  /**
   * What a test was given: the profile and how often the password had been edited.
   *
   * @param profile what was tested
   * @param edits the password's edits when the test began
   */
  record Tried(ConnectionProfile profile, int edits) {}

  /** The test itself failed. */
  record Failed(Exception error, Tried tried) implements Step {}

  /** @param withPassword whether the test sent a password */
  record Done(Tried tried, Tester.Result result, boolean withPassword) implements Step {
    ConnectionProfile profile() {
      return tried.profile();
    }
  }

  /** A field that a failure belongs to. */
  enum At {
    HOST,
    PORT,
    DATABASE,
    USER,
    PASSWORD,
    TLS
  }

  // The same words as core's, which withholds such a message from a real driver already.
  static final String HIDDEN = ConnectionException.WITHHELD;

  private final ConnectionDialog dialog;
  final Fetch fetch = new Fetch(this::fetched);
  Step step; // null while no test runs or is shown
  private Work work; // the test under way, or null
  private int stops; // counts the tests stopped, so that what a stopped one delivers is dropped

  Probe(ConnectionDialog dialog) {
    this.dialog = dialog;
  }

  /**
   * Looks up the version of the provider's driver to use, and whether it is downloaded.
   *
   * @param version the version chosen, or null for the recommended one
   */
  void lookUp(State s, Provider provider, String version) {
    fetch.lookUp(s, provider, version);
  }

  /** Whether Test connection can go: there are drivers, and the form holds a profile to test. */
  boolean canTest(State s) {
    return s.drivers != null && dialog.formValid() && !dialog.changing;
  }

  /** Whether a test runs: Test connection is busy. */
  boolean running() {
    return step instanceof Fetching && fetch.running()
        || step instanceof ForKeyring
        || step instanceof Testing;
  }

  /** Test connection: from the driver on, as far as is needed. */
  void test(State s) {
    if (!canTest(s) || running()) {
      return;
    }
    stop();
    step = new Fetching();
    fetch.start(s);
    if (step instanceof Fetching && fetch.step == null) {
      step = null; // there is no driver to get, as in a script run
    }
  }

  private void fetched(State s, LoadedDriver driver) {
    if (step instanceof Fetching) {
      connect(s, driver);
    }
  }

  /**
   * Tests the profile as the form holds it, with the password typed, or else the one kept for
   * this session or saved in the keyring.
   */
  private void connect(State s, LoadedDriver driver) {
    var d = dialog;
    if (!d.formValid()) {
      step = null; // the form changed into one that cannot be tested meanwhile
      return;
    }
    var profile = d.editing.value();
    var id = profile.id();
    if (d.password.isEmpty() && !d.isNew && !s.remembers(id) && s.keyring == null) {
      step = new ForKeyring(driver);
      return;
    }
    char[] typed = d.password.isEmpty() ? null : d.password.toCharArray();
    char[] kept = typed == null && !d.isNew ? s.rememberedCopy(id) : null;
    var secrets = typed == null && kept == null && !d.isNew && !Boolean.FALSE.equals(d.stored)
        ? s.secrets()
        : null;
    var tester = s.tester;
    var tried = new Tried(profile, d.passwordEdits);
    step = new Testing(profile.host());
    work = s.app.background(
        "test a connection",
        () -> {
          char[] password = typed != null
              ? typed
              : kept != null ? kept : secrets != null ? secrets.read(id).orElse(null) : null;
          if (password == null) {
            password = new char[0];
          }
          try {
            var result = tester.test(profile, password, driver);
            if (result instanceof Tester.Refused r && contains(r.detail(), password)) {
              result = new Tester.Refused(r.failure(), HIDDEN);
            }
            return new Done(tried, result, password.length > 0);
          } finally {
            Arrays.fill(password, '\0');
          }
        },
        done -> {
          work = null;
          step = done;
          if (done.result() instanceof Tester.Refused r) {
            switch (r.failure()) {
              case CANCELLED -> step = null;
              case UNFIT -> d.triedToSave = true; // the fields say why, as for Save
              default -> {}
            }
          }
        },
        error -> {
          work = null;
          step = new Failed(error, tried);
        });
  }

  /** The system keyring was found: a test that waited for it goes on. */
  void keyringFound(State s) {
    if (step instanceof ForKeyring k) {
      connect(s, k.driver());
    }
  }

  /** Stops the test under way, or forgets its outcome. */
  void stop() {
    stops++;
    fetch.stop();
    if (work != null) {
      work.cancel();
      work = null;
    }
    step = null;
  }

  /** The dialog closes: nothing it began goes on. */
  void close() {
    stop();
    fetch.close();
  }

  /** The outcome while the form holds what was tested, its name aside; null otherwise. */
  Done outcome() {
    return step instanceof Done done && holds(done.tried()) ? done : null;
  }

  /** Whether the form is valid and holds what was tested, its name aside. */
  private boolean holds(Tried tried) {
    return dialog.formValid()
        && tried.edits() == dialog.passwordEdits
        && tried
            .profile()
            .equals(dialog.editing.value().withName(tried.profile().name()));
  }

  /** What the outcome says at the field, or null. */
  String error(At at) {
    var done = outcome();
    if (done != null && done.result() instanceof Tester.Refused r && at(r.failure()) == at) {
      return Words.note(r.failure(), done.profile(), done.withPassword());
    }
    return null;
  }

  /** Whether the outcome marks the field as the other one of two that its note is under. */
  boolean marks(At at) {
    var done = outcome();
    return done != null
        && done.result() instanceof Tester.Refused r
        && switch (r.failure()) {
          case LOGIN_REFUSED -> at == At.USER;
          case UNREACHABLE, TIMED_OUT -> at == At.HOST;
          default -> false;
        };
  }

  private static At at(ConnectionException.Failure failure) {
    return switch (failure) {
      case LOGIN_REFUSED -> At.PASSWORD;
      case NO_SUCH_DATABASE -> At.DATABASE;
      case UNREACHABLE, TIMED_OUT -> At.PORT;
      case TLS_UNAVAILABLE, CERTIFICATE_REJECTED -> At.TLS;
      case UNFIT, CANCELLED, OTHER -> null;
    };
  }

  /** The line under the kind of database: which driver, and whether it is there. */
  String line(State s) {
    return fetch.line(s);
  }

  /** What the test shows below the form: the question, the progress or the outcome; or null. */
  El view(State s) {
    return switch (step) {
      case null -> null;
      case Fetching f ->
        switch (fetch.step) {
          case Fetch.Waiting w -> status("Looking up the driver…");
          case Fetch.Loading l -> status("Loading " + Fetch.name(l.found().provider()) + "…");
          case null -> null;
          default -> fetch.view(s, this::stop);
        };
      case ForKeyring k -> status("Looking for the system keyring…");
      case Testing x -> status("Connecting to " + x.host() + "…");
      case Failed f when !holds(f.tried()) -> null;
      case Failed f ->
        Callout.callout(
            Callout.Kind.DANGER,
            "The connection could not be tested",
            State.message(f.error()),
            button(SECONDARY, "Try again", () -> test(s)));
      case Done d -> outcomeView();
    };
  }

  private El status(String text) {
    Theme t = Theme.CURRENT.get();
    return row()
        .wFull()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .role("status")
        .label(text)
        .child(Progress.spinner())
        .child(label(text).wrapText().grow(1))
        .child(button(SECONDARY, "Cancel", this::stop).label("Cancel test"));
  }

  private El outcomeView() {
    Theme t = Theme.CURRENT.get();
    var done = outcome();
    if (done == null) {
      return null;
    }
    return switch (done.result()) {
      case Tester.Reached r -> {
        String text = "Connected to " + r.server().product() + " " + r.server().version();
        yield row()
            .gap(t.space().s())
            .align(Style.Align.CENTER)
            .role("status")
            .label(text)
            .child(Icon.icon(Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS))
            .child(label(text).wrapText());
      }
      case Tester.Refused r ->
        Callout.callout(
            Callout.Kind.DANGER,
            Words.title(r.failure()),
            r.detail().isEmpty() ? null : r.detail());
    };
  }

  /** Whether the text contains the password, compared without making a string of it. */
  static boolean contains(String text, char[] password) {
    if (password.length == 0) {
      return false;
    }
    outer:
    for (int i = 0; i + password.length <= text.length(); i++) {
      for (int j = 0; j < password.length; j++) {
        if (text.charAt(i + j) != password[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }
}
