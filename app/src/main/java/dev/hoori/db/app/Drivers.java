package dev.hoori.db.app;

import com.rethinkj.ui.App;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverException;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.driver.Repository;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Where drivers are kept and where they come from, and the drivers loaded in this session, which
 * stay loaded until HooriDB closes or they are deleted: loading checks the file again and is not
 * free. Only the app's thread uses it.
 */
final class Drivers {

  final DriverStore store;
  final String source; // the repository as the user knows it, such as "Maven Central"
  private final URI files; // where its files are
  final Clock clock; // what the age of a release counts from

  private record Version(Driver driver, String version) {}

  private record Waiter(Consumer<LoadedDriver> then, Consumer<Exception> failed) {}

  private final Map<Version, LoadedDriver> loaded = new HashMap<>();
  // The version last looked up as the recommended one of each driver.
  private final Map<Driver, String> recommended = new HashMap<>();
  // The loads under way, with whoever waits for each; one load per driver and version at a time.
  private final Map<Version, List<Waiter>> loading = new HashMap<>();
  // The versions being deleted, which nothing loads meanwhile.
  private final Set<Version> deleting = new HashSet<>();

  /**
   * @param folder where downloaded drivers are kept
   * @param repository where they are downloaded from
   * @param source the repository's name for the user
   * @param address the repository's address, for the user
   */
  Drivers(Path folder, Repository repository, String source, URI address) {
    this(folder, repository, source, address, Clock.systemUTC());
  }

  Drivers(Path folder, Repository repository, String source, URI address, Clock clock) {
    this.store = new DriverStore(folder, repository);
    this.source = source;
    this.files = address;
    this.clock = clock;
  }

  /** Gives the version of the driver to one of the two, loaded now or once its load is done. */
  void load(
      App<?> app,
      Driver driver,
      String version,
      Consumer<LoadedDriver> then,
      Consumer<Exception> failed) {
    var key = new Version(driver, version);
    if (deleting.contains(key)) {
      failed.accept(new DriverException(DriverException.Failure.NOT_DOWNLOADED, "being deleted"));
      return;
    }
    var done = loaded.get(key);
    if (done != null) {
      then.accept(done);
      return;
    }
    var waiting = loading.get(key);
    if (waiting != null) {
      waiting.add(new Waiter(then, failed));
      return;
    }
    loading.put(key, new ArrayList<>(List.of(new Waiter(then, failed))));
    var from = store; // the value alone, as background work gets no state
    app.background(
        "load a driver",
        () -> from.load(driver, version),
        result -> {
          loaded.put(key, result);
          loading.remove(key).forEach(w -> w.then().accept(result));
        },
        error -> loading.remove(key).forEach(w -> w.failed().accept(error)));
  }

  /** The version is the driver's recommended one now. */
  void recommend(Driver driver, String version) {
    recommended.put(driver, version);
  }

  /**
   * The version of the driver loaded in this session, or null while it is not; for a null version
   * the one last looked up as recommended.
   */
  LoadedDriver loaded(Driver driver, String version) {
    var v = version != null ? version : recommended.get(driver);
    var key = new Version(driver, v);
    return v == null || deleting.contains(key) ? null : loaded.get(key);
  }

  /** The version the driver loaded in this session is, or null for one deleted since. */
  String version(LoadedDriver driver) {
    for (var entry : loaded.entrySet()) {
      if (entry.getValue() == driver) {
        return entry.getKey().version();
      }
    }
    return null;
  }

  /** Whether the version is being loaded: an attempt to connect decided on it. */
  boolean loading(Driver driver, String version) {
    return loading.containsKey(new Version(driver, version));
  }

  /** A delete of the version begins, or ends: until it ends, nothing loads the version. */
  void deleting(Driver driver, String version, boolean begins) {
    var key = new Version(driver, version);
    if (begins) {
      deleting.add(key);
    } else {
      deleting.remove(key);
    }
  }

  /**
   * Takes the version out as its delete begins, to be closed before its file goes; null where it
   * is not loaded. Whoever needs it next loads it again, from the file that is left or a new
   * download.
   */
  LoadedDriver unload(Driver driver, String version) {
    return loaded.remove(new Version(driver, version));
  }

  /** The version was deleted. */
  void forget(Driver driver, String version) {
    recommended.remove(driver, version);
  }

  /**
   * Closes the drivers loaded so far. The app's end closes them by itself; a test closes them
   * before its folder goes, as Windows deletes no JAR that is open.
   */
  void close() throws IOException {
    for (var driver : loaded.values()) {
      driver.close();
    }
    loaded.clear();
  }

  /** Where the version's file is downloaded from. */
  URI address(Driver driver, String version) {
    return files.resolve(store.address(driver, version));
  }
}
