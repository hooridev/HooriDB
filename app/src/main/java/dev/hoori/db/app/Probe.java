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
import dev.hoori.db.core.secret.SecretStore;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

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
          char[] password = password(typed, kept, secrets, id);
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
        this::tested,
        error -> {
          work = null;
          step = new Failed(error, tried);
        });
  }

  /**
   * The password to test with: the one typed, or else the one kept for this session, or else the
   * keyring's where it is asked; empty for none.
   */
  private static char[] password(char[] typed, char[] kept, SecretStore secrets, UUID id)
      throws IOException {
    if (typed != null) {
      return typed;
    }
    if (kept != null) {
      return kept;
    }
    char[] saved = secrets == null ? null : secrets.read(id).orElse(null);
    return saved == null ? new char[0] : saved;
  }

  /** The test's outcome, which shows; a cancel shows nothing, and an unfit form its fields. */
  private void tested(Done done) {
    work = null;
    step = done;
    if (done.result() instanceof Tester.Refused(var failure, var detail)) {
      switch (failure) {
        case CANCELLED -> step = null;
        case UNFIT -> dialog.triedToSave = true; // the fields say why, as for Save
        default -> {
          // The outcome says what was refused.
        }
      }
    }
  }

  /** The system keyring was found: a test that waited for it goes on. */
  void keyringFound(State s) {
    if (step instanceof ForKeyring(var driver)) {
      connect(s, driver);
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
    if (done != null
        && done.result() instanceof Tester.Refused r
        && at(r.failure(), done.profile()) == at) {
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
          case UNREACHABLE, TIMED_OUT -> at == At.HOST && !done.profile().srv();
          default -> false;
        };
  }

  private static At at(ConnectionException.Failure failure, ConnectionProfile profile) {
    return switch (failure) {
      case LOGIN_REFUSED -> At.PASSWORD;
      case NO_SUCH_DATABASE -> At.DATABASE;
      // With SRV there is no port to show it under.
      case UNREACHABLE, TIMED_OUT -> profile.srv() ? At.HOST : At.PORT;
      case TLS_UNAVAILABLE, CERTIFICATE_REJECTED -> At.TLS;
      case UNFIT, CANCELLED, OTHER -> null;
    };
  }

  /** The driver's line: which driver, and whether it is there. */
  String line(State s) {
    return fetch.line(s);
  }

  /** What the test shows below the form: the question, the progress or the outcome; or null. */
  El view(State s) {
    return switch (step) {
      case null -> null;
      case Fetching _ ->
        switch (fetch.step) {
          case Fetch.Waiting _ -> status("Looking up the driver…");
          case Fetch.Loading(var found) -> status("Loading " + Fetch.name(found.provider()) + "…");
          case null -> null;
          default -> fetch.view(s, this::stop);
        };
      case ForKeyring _ -> status("Looking for the system keyring…");
      case Testing(var host) -> status("Connecting to " + host + "…");
      case Failed f when !holds(f.tried()) -> null;
      case Failed f ->
        Callout.callout(
            Callout.Kind.DANGER,
            "The connection could not be tested",
            State.message(f.error()),
            button(SECONDARY, "Try again", () -> test(s)));
      case Done _ -> outcomeView();
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
      case Tester.Reached(var server) -> {
        String text = "Connected to " + server.product() + " " + server.version();
        yield row()
            .gap(t.space().s())
            .align(Style.Align.CENTER)
            .role("status")
            .label(text)
            .child(Icon.icon(Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS))
            .child(label(text).wrapText());
      }
      case Tester.Refused(var failure, var detail) ->
        Callout.callout(
            Callout.Kind.DANGER, Words.title(failure), detail.isEmpty() ? null : detail);
    };
  }

  /** Whether the text contains the password, compared without making a string of it. */
  static boolean contains(String text, char[] password) {
    if (password.length == 0) {
      return false;
    }
    for (int i = 0; i + password.length <= text.length(); i++) {
      if (at(text, i, password)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the password stands in the text at the index. */
  private static boolean at(String text, int at, char[] password) {
    for (int j = 0; j < password.length; j++) {
      if (text.charAt(at + j) != password[j]) {
        return false;
      }
    }
    return true;
  }
}
