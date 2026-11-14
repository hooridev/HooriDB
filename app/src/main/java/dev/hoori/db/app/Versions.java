package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.ComboBox;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverException;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.Maven;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
   * @param now when it was read, which the ages count from
   */
  record Read(
      Map<String, Long> sizes, DriverStore.Choices choices, DriverException error, Instant now) {}

  final Provider provider;
  Read read; // the last that was read, null before the first read
  Exception failed; // the drivers folder could not be read, or null
  private Work work; // the read under way, or null

  Versions(Provider provider) {
    this.provider = provider;
  }

  /** Reads the versions again; what was read stays until the new read is in. */
  void read(State s) {
    close();
    failed = null;
    if (s.drivers == null) {
      return;
    }
    var store = s.drivers.store;
    var driver = Driver.of(provider);
    // shortcut: what is downloaded shows only once the repository answered or failed, which takes
    // as long as its timeout without a network; read the folder first if that is felt.
    work = s.app.background(
        "read the versions of a driver",
        () -> {
          var sizes = new LinkedHashMap<String, Long>();
          for (var version : store.downloaded(driver)) {
            sizes.put(version, store.sizeOnDisk(driver, version));
          }
          var now = Instant.now();
          try {
            return new Read(sizes, store.choices(driver, now), null, now);
          } catch (DriverException e) {
            if (e.failure() == DriverException.Failure.CANCELLED) {
              throw e;
            }
            return new Read(sizes, null, e, now);
          }
        },
        done -> {
          work = null;
          read = done;
        },
        error -> {
          work = null;
          failed = error;
        });
  }

  boolean reading() {
    return work != null;
  }

  /** Nothing it began goes on. */
  void close() {
    if (work != null) {
      work.cancel();
      work = null;
    }
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

  /** Whether the version is newer than the recommended one, or than all where none is. */
  boolean young(String version) {
    if (read == null || read.choices() == null || version == null) {
      return false;
    }
    var recommended = read.choices().recommended();
    return recommended == null
        ? read.choices().published().containsKey(version)
        : Maven.NEWEST_FIRST.compare(version, recommended) < 0;
  }

  /** How long ago a version too new to be recommended was published, or null when not known. */
  String age(String version) {
    if (read == null || read.choices() == null) {
      return null;
    }
    var published = read.choices().published().get(version);
    if (published == null) {
      return null;
    }
    long days = Math.max(0, Duration.between(published, read.now()).toDays());
    return days == 0 ? "out since today" : days == 1 ? "1 day old" : days + " days old";
  }

  /** What keeps the repository's list from showing, in words, or null. */
  String problem(Drivers drivers) {
    if (failed != null) {
      return "The drivers folder could not be read: " + State.message(failed);
    }
    if (read != null && read.error() != null) {
      return "Its versions could not be read. " + Fetch.text(read.error(), drivers);
    }
    return null;
  }
}
