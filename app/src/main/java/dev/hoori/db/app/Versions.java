package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.ComboBox;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverException;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.Maven;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The versions of a kind's driver that can be chosen, as the repository lists them, and those
 * downloaded with their sizes; read in the background. Only the app's thread uses it.
 */
final class Versions {

  /**
   * What was read.
   *
   * @param sizes the downloaded versions, newest first, with their sizes in bytes
   * @param choices what the repository offers, or null when it could not be read
   * @param error why the repository could not be read, or null
   */
  record Read(Map<String, Long> sizes, DriverStore.Choices choices, DriverException error) {}

  final Provider provider;
  Read read; // the last that was read, null before the first read
  Exception failed; // the drivers folder could not be read, or null
  private Work work; // the read under way, or null
  private Clock clock = Clock.systemUTC(); // what ages count from: the drivers' clock
  // When versions were published, as looked up one by one; the list has those newer than the
  // recommended one.
  private final Map<String, Instant> dates = new HashMap<>();
  String dating; // the version whose date is being looked up, or null
  private Work datingWork;

  Versions(Provider provider) {
    this.provider = provider;
  }

  /** Reads the versions again; what was read stays until the new read is in. */
  void read(State s) {
    read(s, null, () -> {});
  }

  /**
   * As {@link #read(State)}, and then runs once the read is in or failed; not when it was stopped.
   *
   * @param known what the repository offers, read already, or null to read it
   */
  void read(State s, DriverStore.Choices known, Runnable then) {
    stop();
    failed = null;
    if (s.drivers == null) {
      return;
    }
    var store = s.drivers.store;
    var driver = Driver.of(provider);
    var clock = this.clock = s.drivers.clock;
    // shortcut: what is downloaded shows only once the repository answered or failed, which takes
    // as long as its timeout without a network; read the folder first if that is felt.
    work = s.app.background(
        "read the versions of a driver",
        () -> {
          var sizes = new LinkedHashMap<String, Long>();
          for (var version : store.downloaded(driver)) {
            sizes.put(version, store.sizeOnDisk(driver, version));
          }
          try {
            return new Read(
                sizes, known != null ? known : store.choices(driver, clock.instant()), null);
          } catch (DriverException e) {
            if (e.failure() == DriverException.Failure.CANCELLED) {
              throw e;
            }
            return new Read(sizes, null, e);
          }
        },
        done -> {
          work = null;
          read = done;
          then.run();
        },
        error -> {
          work = null;
          failed = error;
          then.run();
        });
  }

  /**
   * Looks up when the version was published, unless that is known: its age decides whether it is
   * too new. A version's age comes from its own date, as a lower version can be the newest release.
   */
  void date(State s, String version) {
    if (version == null || s.drivers == null || published(version) != null) {
      return;
    }
    if (datingWork != null) {
      datingWork.cancel();
    }
    var store = s.drivers.store;
    var driver = Driver.of(provider);
    clock = s.drivers.clock;
    dating = version;
    datingWork = s.app.background(
        "look up when a driver was published",
        () -> store.published(driver, version),
        date -> {
          datingWork = null;
          dating = null;
          date.ifPresent(d -> dates.put(version, d));
        },
        // Without a date the version is not said to be too new, which the list shows as unknown.
        error -> {
          datingWork = null;
          dating = null;
        });
  }

  boolean reading() {
    return work != null;
  }

  /** Stops reading the versions; a date being looked up still comes. */
  void stop() {
    if (work != null) {
      work.cancel();
      work = null;
    }
  }

  /** Nothing it began goes on. */
  void close() {
    stop();
    if (datingWork != null) {
      datingWork.cancel();
      datingWork = null;
      dating = null;
    }
  }

  /** When the version was published, or null while that is not known. */
  private Instant published(String version) {
    var listed = read == null || read.choices() == null
        ? null
        : read.choices().published().get(version);
    return listed != null ? listed : dates.get(version);
  }

  /** What the repository offers, or null while that is not known. */
  DriverStore.Choices choices() {
    return read == null ? null : read.choices();
  }

  /** The recommended version, or null while it is not known. */
  String recommended() {
    return read == null || read.choices() == null ? null : read.choices().recommended();
  }

  /**
   * What can be chosen: null for the recommended version first, then the repository's versions and
   * the downloaded ones, newest first, and the one chosen, which may be neither.
   */
  List<String> values(String chosen) {
    var versions = new TreeSet<String>(Maven.NEWEST_FIRST);
    if (read != null) {
      versions.addAll(read.sizes().keySet());
      if (read.choices() != null) {
        versions.addAll(read.choices().versions());
      }
    }
    if (chosen != null) {
      versions.add(chosen);
    }
    var values = new ArrayList<String>();
    values.add(null);
    values.addAll(versions);
    return values;
  }

  /** A value of {@link #values} in the list: its version, and what is known of it. */
  ComboBox.Item item(String version) {
    if (version == null) {
      var recommended = recommended();
      return ComboBox.item("Recommended").detail(recommended == null ? null : recommended);
    }
    var notes = new ArrayList<String>();
    if (version.equals(recommended())) {
      notes.add("recommended");
    }
    var age = age(version);
    if (age != null) {
      notes.add(age);
    } else if (young(version)) {
      notes.add("too new to be recommended");
    }
    if (read != null && read.sizes().containsKey(version)) {
      notes.add("downloaded");
    }
    return ComboBox.item(version).detail(notes.isEmpty() ? null : String.join(", ", notes));
  }

  /**
   * Whether the version is too new to be recommended: published less than the waiting period ago,
   * or, without a date, newer than the recommended one.
   */
  boolean young(String version) {
    if (version == null) {
      return false;
    }
    var published = published(version);
    if (published != null) {
      return published.isAfter(clock.instant().minus(DriverStore.WAITING_PERIOD));
    }
    var recommended = recommended();
    return recommended != null && Maven.NEWEST_FIRST.compare(version, recommended) < 0;
  }

  /** How long ago a version too new to be recommended was published, or null when not known. */
  String age(String version) {
    var published = published(version);
    if (published == null || !young(version)) {
      return null;
    }
    long days = Math.max(0, Duration.between(published, clock.instant()).toDays());
    return days == 0 ? "out since today" : days == 1 ? "1 day old" : days + " days old";
  }

  /** What keeps the repository's list from showing, in words, or null. */
  String problem(Drivers drivers) {
    if (failed != null) {
      return "The drivers folder could not be read"
          + (failed instanceof DriverException e ? Fetch.why(e) : "")
          + ". Check that HooriDB may read it, then try again.";
    }
    if (read != null && read.error() != null) {
      return "Its versions could not be read. " + Fetch.text(read.error(), drivers);
    }
    return null;
  }
}
