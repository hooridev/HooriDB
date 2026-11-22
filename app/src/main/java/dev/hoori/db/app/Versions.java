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
import java.util.function.Supplier;

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

  private record Listed(DriverStore.Choices choices, DriverException error) {}

  final Provider provider;
  Read last; // the last that was read, null before the first folder read
  Exception failed; // the drivers folder could not be read, or null
  private Work folderWork; // reading the drivers folder, or null
  private Work choicesWork; // reading the repository's list, or null
  private Listed listed; // what the repository's read gave, null before it ended
  private Clock clock = Clock.systemUTC(); // what ages count from: the drivers' clock
  // When versions were published, as looked up one by one; the list has those newer than the
  // recommended one.
  private final Map<String, Instant> dates = new HashMap<>();
  String dating; // the version whose date is being looked up, or null
  private Work datingWork;
  // What the connection's lookup of the driver read, which wins over what this reads; null for
  // none.
  Supplier<DriverStore.Choices> lookedUp = () -> null;
  // The recommended version, or null while it is not known: what this read names, unless the one
  // who asks decides it otherwise, as the connection's lookup does.
  Supplier<String> recommendedBy = () -> {
    var choices = choices();
    return choices == null ? null : choices.recommended();
  };

  Versions(Provider provider) {
    this.provider = provider;
  }

  /**
   * Reads the versions again; what was read stays until the new read is in. The drivers folder and
   * the repository are read separately so that downloaded versions show at once even without a
   * network. The repository is not asked where the lookup's choices are there.
   */
  void read(State s) {
    stop();
    failed = null;
    listed = null;
    if (s.drivers == null) {
      return;
    }
    var store = s.drivers.store;
    var driver = Driver.of(provider);
    var driverClock = this.clock = s.drivers.clock;
    var known = lookedUp.get();
    folderWork = s.app.background(
        "read the downloaded versions of a driver",
        () -> {
          var sizes = new LinkedHashMap<String, Long>();
          for (var version : store.downloaded(driver)) {
            sizes.put(version, store.sizeOnDisk(driver, version));
          }
          return sizes;
        },
        sizes -> {
          folderWork = null;
          if (known != null) {
            last = new Read(sizes, known, null);
          } else if (listed != null) {
            last = new Read(sizes, listed.choices(), listed.error());
          } else {
            last = new Read(sizes, last == null ? null : last.choices(), null);
          }
        },
        error -> {
          folderWork = null;
          failed = error;
        });
    if (known == null) {
      choicesWork = s.app.background(
          "read the versions of a driver",
          () -> {
            try {
              return new Listed(store.choices(driver, driverClock.instant()), null);
            } catch (DriverException e) {
              if (e.failure() == DriverException.Failure.CANCELLED) {
                throw e;
              }
              return new Listed(null, e);
            }
          },
          done -> {
            choicesWork = null;
            listed = done;
            if (last != null) {
              last = new Read(last.sizes(), done.choices(), done.error());
            }
          },
          error -> {
            choicesWork = null;
            if (failed == null) {
              failed = error;
            }
          });
    }
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
        _ -> {
          datingWork = null;
          dating = null;
        });
  }

  boolean reading() {
    return folderWork != null || choicesWork != null;
  }

  boolean readingChoices() {
    return choicesWork != null;
  }

  /** Stops reading the versions; a date being looked up still comes. */
  void stop() {
    if (folderWork != null) {
      folderWork.cancel();
      folderWork = null;
    }
    if (choicesWork != null) {
      choicesWork.cancel();
      choicesWork = null;
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
    var choices = choices();
    var listed = choices == null ? null : choices.published().get(version);
    return listed != null ? listed : dates.get(version);
  }

  /** What the repository offers, the lookup's first, or null while that is not known. */
  DriverStore.Choices choices() {
    var known = lookedUp.get();
    if (known != null) {
      return known;
    }
    return last == null ? null : last.choices();
  }

  /** The recommended version, or null while it is not known. */
  String recommended() {
    return recommendedBy.get();
  }

  /**
   * What can be chosen: null for the recommended version first, then the repository's versions and
   * the downloaded ones, newest first, and the one chosen, which may be neither.
   */
  List<String> values(String chosen) {
    var versions = new TreeSet<String>(Maven.NEWEST_FIRST);
    if (last != null) {
      versions.addAll(last.sizes().keySet());
    }
    var choices = choices();
    if (choices != null) {
      versions.addAll(choices.versions());
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
    }
    if (last != null && last.sizes().containsKey(version)) {
      notes.add("downloaded");
    }
    return ComboBox.item(version).detail(notes.isEmpty() ? null : String.join(", ", notes));
  }

  /**
   * Whether the version is too new to be recommended: published less than the waiting period ago.
   * Without a date it is not said to be, whatever it sorts above.
   */
  boolean young(String version) {
    var published = version == null ? null : published(version);
    return published != null
        && published.isAfter(clock.instant().minus(DriverStore.WAITING_PERIOD));
  }

  /** How long ago a version too new to be recommended was published, or null when not known. */
  String age(String version) {
    var published = published(version);
    if (published == null || !young(version)) {
      return null;
    }
    long days = Math.max(0, Duration.between(published, clock.instant()).toDays());
    if (days == 0) {
      return "out since today";
    }
    return days == 1 ? "1 day old" : days + " days old";
  }

  /** What keeps the repository's list from showing, in words, or null. */
  String problem(Drivers drivers) {
    if (failed != null) {
      return "The drivers folder could not be read"
          + (failed instanceof DriverException e ? Fetch.why(e) : "")
          + ". Check that HooriDB may read it, then try again.";
    }
    if (lookedUp.get() == null && last != null && last.error() != null) {
      return "Its versions could not be read. " + Fetch.text(last.error(), drivers);
    }
    return null;
  }
}
