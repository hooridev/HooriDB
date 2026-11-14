package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverException;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.LoadedDriver;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

/**
 * Tries the connection of the dialog: finds the driver it needs, downloads it once the user agrees,
 * loads it and tests the connection with it. It saves nothing.
 */
final class Probe {

  /** What is known of the driver that the connection would use. */
  sealed interface Lookup {
    Provider provider();
  }

  record Looking(Provider provider) implements Lookup {}

  /**
   * @param offline chosen from what is downloaded, as the repository could not be reached
   * @param size the file's size in bytes, or -1 when it is not known
   */
  record Found(Provider provider, String version, boolean offline, boolean downloaded, long size)
      implements Lookup {
    Found asDownloaded() {
      return new Found(provider, version, offline, true, size);
    }
  }

  record Missing(Provider provider, Exception error) implements Lookup {}

  /** Where a test is, from the press on Test connection to its outcome. */
  sealed interface Step {}

  /** For the driver to be looked up. */
  record Waiting() implements Step {}

  /** Whether the driver may be downloaded. */
  record Asking(Found found) implements Step {}

  record Downloading(Found found, long done, long total) implements Step {}

  record Loading(Found found) implements Step {}

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

  /** @param tried what was tested, for a failure of the test itself; null for the driver's */
  record Failed(Stage stage, Exception error, Tried tried) implements Step {}

  /** @param withPassword whether the test sent a password */
  record Done(Tried tried, Tester.Result result, boolean withPassword) implements Step {
    ConnectionProfile profile() {
      return tried.profile();
    }
  }

  enum Stage {
    LOOK_UP,
    DOWNLOAD,
    LOAD,
    TEST
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
  Lookup lookup; // null without drivers, as in a script run
  private Work looking; // the lookup under way, or null
  Step step; // null while no test runs or is shown
  private Work work; // the download or the test under way, or null
  // Counts the tests stopped, so that what a stopped one delivers late is dropped.
  private int stops;
  private boolean agreed; // the user agreed to the download in this test

  Probe(ConnectionDialog dialog) {
    this.dialog = dialog;
  }

  /** Looks up the version of the provider's driver to use, and whether it is downloaded. */
  void lookUp(State s, Provider provider) {
    if (looking != null) {
      looking.cancel();
      looking = null;
    }
    var drivers = s.drivers;
    if (drivers == null) {
      return;
    }
    var driver = Driver.of(provider);
    var store = drivers.store;
    lookup = new Looking(provider);
    looking = s.app.background(
        "look up a driver",
        () -> {
          var recommended = store.recommended(driver, Instant.now());
          var version = recommended.version();
          boolean there = store.downloaded(driver).contains(version);
          long size = there || recommended.offline() ? -1 : size(store, driver, version);
          return new Found(provider, version, recommended.offline(), there, size);
        },
        found -> {
          looking = null;
          lookup = found;
          if (step instanceof Waiting) {
            next(s);
          }
        },
        error -> {
          looking = null;
          lookup = new Missing(provider, error);
          if (step instanceof Waiting) {
            step = new Failed(Stage.LOOK_UP, error, null);
          }
        });
  }

  /** Whether Test connection can go: there are drivers, and the form holds a profile to test. */
  boolean canTest(State s) {
    return s.drivers != null && dialog.formValid() && !dialog.changing;
  }

  /** Whether a test runs: Test connection is busy. */
  boolean running() {
    return step instanceof Waiting
        || step instanceof Downloading
        || step instanceof Loading
        || step instanceof ForKeyring
        || step instanceof Testing;
  }

  /** Test connection: from the driver on, as far as is needed. */
  void test(State s) {
    if (!canTest(s) || running()) {
      return;
    }
    stop();
    next(s);
  }

  private void next(State s) {
    switch (lookup) {
      case Looking looking -> step = new Waiting();
      case Missing missing -> {
        step = new Waiting();
        lookUp(s, missing.provider());
      }
      case Found found when !found.downloaded() -> step = new Asking(found);
      case Found found -> load(s, found);
      case null -> step = null;
    }
  }

  /** Download in the question: the user agrees, and the test goes on. */
  void agree(State s) {
    agreed = true;
    download(s);
  }

  /**
   * Downloads the driver, then goes on with the test; asks first unless the user agreed in this
   * test.
   */
  void download(State s) {
    if (!(lookup instanceof Found found)) {
      return;
    }
    if (!agreed) {
      step = new Asking(found);
      return;
    }
    var store = s.drivers.store;
    var driver = Driver.of(found.provider());
    var version = found.version();
    var app = s.app;
    int stop = stops;
    step = new Downloading(found, 0, found.size());
    DriverStore.Progress progress = new DriverStore.Progress() {
      private long shown;

      // About a hundred updates in all, whatever the size.
      @Override
      public void update(long done, long total) {
        if (done == total || done - shown >= Math.max(total / 100, 64 << 10)) {
          shown = done;
          app.post(() -> {
            if (stop == stops && step instanceof Downloading d) {
              step = new Downloading(d.found(), done, total);
            }
          });
        }
      }
    };
    work = app.background(
        "download a driver",
        () -> {
          store.download(driver, version, progress);
          return found.asDownloaded();
        },
        downloaded -> {
          work = null;
          if (lookup instanceof Found f && f.provider() == downloaded.provider()) {
            lookup = downloaded;
          }
          load(s, downloaded);
        },
        error -> {
          work = null;
          step = new Failed(Stage.DOWNLOAD, error, null);
        });
  }

  private void load(State s, Found found) {
    int stop = stops;
    step = new Loading(found);
    s.drivers.load(
        s.app,
        Driver.of(found.provider()),
        found.version(),
        driver -> {
          if (stop == stops) {
            connect(s, driver);
          }
        },
        error -> {
          if (stop == stops) {
            step = new Failed(Stage.LOAD, error, null);
          }
        });
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
          step = new Failed(Stage.TEST, error, tried);
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
    agreed = false;
    if (work != null) {
      work.cancel();
      work = null;
    }
    step = null;
  }

  /** The dialog closes: nothing it began goes on. */
  void close() {
    stop();
    if (looking != null) {
      looking.cancel();
      looking = null;
    }
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
      return note(r.failure(), done);
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

  /** What the user can do about the failure, in words for its field. */
  private static String note(ConnectionException.Failure failure, Done done) {
    var p = done.profile();
    return switch (failure) {
      case LOGIN_REFUSED ->
        done.withPassword()
            ? "The server refused this user name and password."
            : "The server refused the login without a password. Enter the password.";
      case NO_SUCH_DATABASE ->
        p.database().isBlank()
            ? "The server has no database named after the user, which it opens when none is"
                + " given. Enter the database to open."
            : "The server has no database named " + p.database()
                + ", or this user may not open it.";
      case UNREACHABLE ->
        "Nothing answered at " + p.host() + ", port " + p.port()
            + ". Check the host and the port, and that the server is running.";
      case TIMED_OUT ->
        p.host() + " did not answer on port " + p.port() + " within "
            + Tester.TIMEOUT.toSeconds()
            + " seconds. Check the host and the port; a firewall may be in the way.";
      case TLS_UNAVAILABLE ->
        "The server offers no TLS. Off connects without it, and password and data go over the"
            + " network as they are.";
      case CERTIFICATE_REJECTED ->
        "The server's certificate is not trusted, or not for " + p.host()
            + ". Encrypted accepts it, without verifying the server.";
      case UNFIT, CANCELLED, OTHER -> null;
    };
  }

  private static String title(ConnectionException.Failure failure) {
    return switch (failure) {
      case LOGIN_REFUSED -> "Login refused";
      case NO_SUCH_DATABASE -> "No such database";
      case UNREACHABLE -> "The server cannot be reached";
      case TIMED_OUT -> "No answer in time";
      case TLS_UNAVAILABLE -> "The server offers no TLS";
      case CERTIFICATE_REJECTED -> "The certificate was not accepted";
      case UNFIT, CANCELLED, OTHER -> "The connection failed";
    };
  }

  private static String title(Stage stage) {
    return switch (stage) {
      case LOOK_UP -> "The driver could not be found";
      case DOWNLOAD -> "The driver could not be downloaded";
      case LOAD -> "The driver could not be loaded";
      case TEST -> "The connection could not be tested";
    };
  }

  /** What went wrong with a driver, in words. */
  private static String text(Exception error, Drivers drivers) {
    if (!(error instanceof DriverException e)) {
      return State.message(error);
    }
    var source = drivers.source;
    return switch (e.failure()) {
      case NO_NETWORK -> source + " cannot be reached. Check the network connection.";
      case NOT_FOUND -> source + " does not have this driver.";
      case NO_RECOMMENDATION ->
        "None of its newest releases has been out for "
            + DriverStore.WAITING_PERIOD.toDays()
            + " days yet. HooriDB waits that long, as a bad release is usually withdrawn within"
            + " days.";
      case NO_CHECKSUM ->
        source + " publishes no checksum for it, so a download cannot be checked.";
      case CHECKSUM_MISMATCH ->
        "The file does not match its checksum, so it was not used. It may have been damaged on"
            + " the way.";
      case TOO_LARGE -> "The file is larger than a driver should be, so it was not kept.";
      case CLASS_PATH_IN_MANIFEST ->
        "The file asks for more files to be loaded with it, which nothing has checked, so it is"
            + " not used.";
      case INVALID_RESPONSE -> source + " answered with something HooriDB cannot read.";
      case STORAGE ->
        "The drivers folder could not be read or written: "
            + (e.getCause() == null ? e.getMessage() : State.message((Exception) e.getCause()));
      case NOT_DOWNLOADED -> "Its file is missing from the drivers folder.";
      case NOT_A_DRIVER -> "The file does not hold the driver.";
      case CANCELLED -> "It was cancelled.";
    };
  }

  /** The driver's name as its makers call it. */
  static String name(Provider provider) {
    return switch (provider) {
      case POSTGRESQL -> "PostgreSQL JDBC";
      case MARIADB -> "MariaDB Connector/J";
      case MYSQL -> "MySQL Connector/J";
    };
  }

  /** The line under the kind of database: which driver, and whether it is there. */
  String line(State s) {
    if (s.drivers == null) {
      return "A script run downloads no drivers, so connections cannot be tested.";
    }
    var source = s.drivers.source;
    return switch (lookup) {
      case Looking l -> name(l.provider()) + ", looking up the version to use…";
      case Found f
      when f.downloaded() && f.offline() ->
        name(f.provider()) + " " + f.version() + ", downloaded (" + source
            + " cannot be reached to look for a newer one)";
      case Found f when f.downloaded() -> name(f.provider()) + " " + f.version() + ", downloaded";
      case Found f ->
        name(f.provider()) + " " + f.version() + ", not downloaded yet ("
            + (f.size() >= 0 ? size(f.size()) + ", " : "") + "from " + source + ")";
      case Missing m
      when m.error() instanceof DriverException e
          && e.failure() == DriverException.Failure.NO_NETWORK ->
        name(m.provider()) + ", not downloaded yet (" + source + " cannot be reached)";
      case Missing m ->
        name(m.provider()) + " could not be looked up. " + text(m.error(), s.drivers);
      case null -> null;
    };
  }

  /** What the test shows below the form: the question, the progress or the outcome; or null. */
  El view(Theme t, State s) {
    return switch (step) {
      case null -> null;
      case Waiting w -> status(t, "Looking up the driver…");
      case ForKeyring k -> status(t, "Looking for the system keyring…");
      case Asking a -> question(t, s, a.found());
      case Downloading d -> downloading(t, d);
      case Loading l -> status(t, "Loading " + name(l.found().provider()) + "…");
      case Testing x -> status(t, "Connecting to " + x.host() + "…");
      case Failed f when f.tried() != null && !holds(f.tried()) -> null;
      case Failed f ->
        Callout.callout(
            t,
            Callout.Kind.DANGER,
            title(f.stage()),
            text(f.error(), s.drivers),
            button(t, SECONDARY, "Try again", () -> again(s, f.stage())));
      case Done d -> outcome(t);
    };
  }

  private void again(State s, Stage stage) {
    switch (stage) {
      case LOOK_UP, TEST -> test(s);
      // A file that would not load is downloaded again, which replaces one that changed.
      case DOWNLOAD, LOAD -> download(s);
    }
  }

  // shortcut: a callout puts its buttons beside its text, which squeezes a long question into a
  // narrow column, so they go below it; pass them to the callout once it can put them there.
  private El question(Theme t, State s, Found found) {
    var name = name(found.provider());
    var address = s.drivers.address(Driver.of(found.provider()), found.version());
    String size = found.size() >= 0 ? " (" + size(found.size()) + ")" : "";
    El callout = Callout.callout(
        t,
        Callout.Kind.INFO,
        "Download " + name + " " + found.version() + "?",
        "To connect, HooriDB needs this driver" + size + ", which it downloads from "
            + s.drivers.source + ":\n" + address
            + "\nA driver is program code. It will run on this computer, with your rights. It is"
            + " downloaded once, checked against its checksum and kept for later.");
    return col()
        .wFull()
        .gap(t.space().m())
        .key("question")
        .child(callout)
        .child(Form.buttons(
            t,
            button(t, SECONDARY, "Download", () -> agree(s)),
            button(t, SECONDARY, "Not now", this::stop)));
  }

  private El downloading(Theme t, Downloading d) {
    var found = d.found();
    String detail = d.total() > 0
        ? amount(d.done(), d.total()) + " of " + size(d.total())
        : size(Math.max(0, d.done()));
    String title = "Downloading " + name(found.provider());
    El bar = d.total() > 0
        ? Progress.labeled(t, title, (double) d.done() / d.total(), detail)
        : Progress.labeled(t, title, 0, detail);
    return row()
        .wFull()
        .gap(t.space().m())
        .align(Style.Align.END)
        .child(bar.grow(1))
        .child(button(t, SECONDARY, "Cancel", this::stop).label("Cancel download"));
  }

  private El status(Theme t, String text) {
    return row()
        .wFull()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .role("status")
        .label(text)
        .child(Progress.spinner(t))
        .child(label(t, text).wrapText().grow(1))
        .child(button(t, SECONDARY, "Cancel", this::stop).label("Cancel test"));
  }

  private El outcome(Theme t) {
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
            .child(Icon.icon(t, Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS))
            .child(label(t, text).wrapText());
      }
      case Tester.Refused r ->
        Callout.callout(
            t, Callout.Kind.DANGER, title(r.failure()), r.detail().isEmpty() ? null : r.detail());
    };
  }

  /** The size of the version's file, or -1: it is a courtesy, and the download says what fails. */
  private static long size(DriverStore store, Driver driver, String version) {
    try {
      return store.size(driver, version);
    } catch (DriverException e) {
      return -1;
    }
  }

  /** Bytes as megabytes with one decimal, or as kilobytes below one megabyte. */
  // shortcut: the kit's Format has no size in bytes; use it once it has one.
  static String size(long bytes) {
    // A space that does not break keeps the number with its unit.
    return amount(bytes, bytes) + (bytes < 1_000_000 ? "\u00a0kB" : "\u00a0MB");
  }

  /** The bytes in the unit that the total is shown in, without the unit. */
  private static String amount(long bytes, long total) {
    return total < 1_000_000
        ? String.valueOf(Math.round(bytes / 1e3))
        : String.format(Locale.ROOT, "%.1f", bytes / 1e6);
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
