package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverException;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.LoadedDriver;
import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * Gets the driver of a kind of database ready: looks up the version to use, asks before it
 * downloads it, downloads and loads it, and hands it on. Test connection and opening a connection
 * in the tree both go through it, with the same question and the same words.
 */
final class Fetch {

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

  /** Where getting the driver is, once it was asked for. */
  sealed interface Step {}

  /** For the driver to be looked up. */
  record Waiting() implements Step {}

  /** Whether the driver may be downloaded. */
  record Asking(Found found) implements Step {}

  record Downloading(Found found, long done, long total) implements Step {}

  record Loading(Found found) implements Step {}

  record Failed(Stage stage, Exception error) implements Step {}

  enum Stage {
    LOOK_UP,
    DOWNLOAD,
    LOAD
  }

  private final BiConsumer<State, LoadedDriver> then; // gets the driver once it is loaded
  private String chosen; // the version looked up, or null for the recommended one
  Lookup lookup; // null without drivers, as in a script run
  private Work looking; // the lookup under way, or null
  Step step; // null while the driver is not asked for
  private Work work; // the download under way, or null
  // Counts the stops, so that what a stopped step delivers late is dropped.
  private int stops;
  private boolean agreed; // the user agreed to the download since the last stop

  Fetch(BiConsumer<State, LoadedDriver> then) {
    this.then = then;
  }

  /**
   * Looks up the version of the provider's driver to use, and whether it is downloaded.
   *
   * @param chosen the version to use, or null for the recommended one
   */
  void lookUp(State s, Provider provider, String chosen) {
    this.chosen = chosen;
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
          var recommended = chosen == null ? store.recommended(driver, Instant.now()) : null;
          var version = chosen == null ? recommended.version() : chosen;
          boolean offline = recommended != null && recommended.offline();
          boolean there = store.downloaded(driver).contains(version);
          long size = there || offline ? -1 : size(store, driver, version);
          return new Found(provider, version, offline, there, size);
        },
        found -> {
          looking = null;
          lookup = found;
          if (chosen == null) {
            drivers.recommend(driver, found.version());
          }
          if (step instanceof Waiting) {
            start(s);
          }
        },
        error -> {
          looking = null;
          lookup = new Missing(provider, error);
          if (step instanceof Waiting) {
            step = new Failed(Stage.LOOK_UP, error);
          }
        });
  }

  /** Gets the driver, as far as is needed: asks before a download. */
  void start(State s) {
    switch (lookup) {
      case Looking looking -> step = new Waiting();
      case Missing missing -> {
        step = new Waiting();
        lookUp(s, missing.provider(), chosen);
      }
      case Found found when !found.downloaded() -> step = new Asking(found);
      case Found found -> load(s, found);
      case null -> step = null;
    }
  }

  /** Whether it works on its own: Waiting, Downloading or Loading. */
  boolean running() {
    return step instanceof Waiting || step instanceof Downloading || step instanceof Loading;
  }

  /** Whether {@link #view} shows something: the question, the download, or what failed. */
  boolean shows() {
    return step instanceof Asking || step instanceof Downloading || step instanceof Failed;
  }

  /** Download in the question: the user agrees, and it goes on. */
  void agree(State s) {
    agreed = true;
    download(s);
  }

  /** Downloads the driver, then loads it; asks first unless the user agreed since the last stop. */
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
          step = new Failed(Stage.DOWNLOAD, error);
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
            step = null;
            then.accept(s, driver);
          }
        },
        error -> {
          if (stop == stops) {
            step = new Failed(Stage.LOAD, error);
          }
        });
  }

  /** Stops the step under way, or forgets the question or the failure. */
  void stop() {
    stops++;
    agreed = false;
    if (work != null) {
      work.cancel();
      work = null;
    }
    step = null;
  }

  /** Nothing it began goes on. */
  void close() {
    stop();
    if (looking != null) {
      looking.cancel();
      looking = null;
    }
  }

  private void again(State s, Stage stage) {
    switch (stage) {
      case LOOK_UP -> start(s);
      // A file that would not load is downloaded again, which replaces one that changed.
      case DOWNLOAD, LOAD -> download(s);
    }
  }

  private static String title(Stage stage) {
    return switch (stage) {
      case LOOK_UP -> "The driver could not be found";
      case DOWNLOAD -> "The driver could not be downloaded";
      case LOAD -> "The driver could not be loaded";
    };
  }

  /** What went wrong with a driver, in words. */
  static String text(Exception error, Drivers drivers) {
    if (!(error instanceof DriverException e)) {
      return State.message(error);
    }
    var source = drivers.source;
    return switch (e.failure()) {
      case NO_NETWORK -> source + " cannot be reached. Check the network connection.";
      case UNAVAILABLE ->
        source + " answered with an error or stopped answering. Try again later; if it goes on,"
            + " check a proxy or firewall.";
      case NO_SPACE -> "The disk of the drivers folder is full. Free some space and try again.";
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

  /** What is known of the driver, in a line: which one, and whether it is there. */
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
      case Missing m
      when m.error() instanceof DriverException e
          && e.failure() == DriverException.Failure.UNAVAILABLE ->
        name(m.provider()) + ", not downloaded yet (" + source + " is not answering)";
      case Missing m ->
        name(m.provider()) + " could not be looked up. " + text(m.error(), s.drivers);
      case null -> null;
    };
  }

  /**
   * The question, the download's progress or what failed; null while it waits for the lookup or
   * loads, which the caller shows in its own way, and while nothing is asked for.
   *
   * @param stop what Not now and Cancel download do
   */
  El view(Theme t, State s, Runnable stop) {
    return switch (step) {
      case Asking a -> question(t, s, a.found(), stop);
      case Downloading d ->
        downloading(t, "Downloading " + name(d.found().provider()), d.done(), d.total(), stop);
      case Failed f ->
        Callout.callout(
            t,
            Callout.Kind.DANGER,
            title(f.stage()),
            text(f.error(), s.drivers),
            button(t, SECONDARY, "Try again", () -> again(s, f.stage())));
      case Waiting w -> null;
      case Loading l -> null;
      case null -> null;
    };
  }

  private El question(Theme t, State s, Found found, Runnable stop) {
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
            + " downloaded once, checked against its checksum and kept for later.",
        button(t, SECONDARY, "Download", () -> agree(s)),
        button(t, SECONDARY, "Not now", stop));
    return col().wFull().key("question").child(callout);
  }

  /** A download's bar, with its title, and Cancel download beside it. */
  static El downloading(Theme t, String title, long done, long total, Runnable stop) {
    String detail = total > 0 ? size(done) + " of " + size(total) : size(Math.max(0, done));
    El bar = total > 0
        ? Progress.labeled(t, title, (double) done / total, detail)
        : Progress.labeled(t, title, 0, detail);
    return row()
        .wFull()
        .gap(t.space().m())
        .align(Style.Align.END)
        .child(bar.grow(1))
        .child(button(t, SECONDARY, "Cancel", stop).label("Cancel download"));
  }

  /** The size of the version's file, or -1: it is a courtesy, and the download says what fails. */
  private static long size(DriverStore store, Driver driver, String version) {
    try {
      return store.size(driver, version);
    } catch (DriverException e) {
      return -1;
    }
  }

  static String size(long bytes) {
    return Format.BYTES.text(bytes);
  }
}
