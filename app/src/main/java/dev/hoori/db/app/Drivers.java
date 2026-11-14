package dev.hoori.db.app;

import com.rethinkj.ui.App;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.driver.Repository;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where drivers are kept and where they come from, and the drivers loaded in this session, which
 * stay loaded until HooriDB closes: loading checks the file again and is not free. Only the app's
 * thread uses it.
 */
final class Drivers {

  final DriverStore store;
  final String source; // the repository as the user knows it, such as "Maven Central"
  final URI address; // where its files are

  private record Version(Driver driver, String version) {}

  private record Waiter(Consumer<LoadedDriver> then, Consumer<Exception> failed) {}

  private final Map<Version, LoadedDriver> loaded = new HashMap<>();
  // The loads under way, with whoever waits for each; one load per driver and version at a time.
  private final Map<Version, List<Waiter>> loading = new HashMap<>();

  /**
   * @param folder where downloaded drivers are kept
   * @param repository where they are downloaded from
   * @param source the repository's name for the user
   * @param address the repository's address, for the user
   */
  Drivers(Path folder, Repository repository, String source, URI address) {
    this.store = new DriverStore(folder, repository);
    this.source = source;
    this.address = address;
  }

  /** Gives the version of the driver to one of the two, loaded now or once its load is done. */
  void load(
      App<?> app,
      Driver driver,
      String version,
      Consumer<LoadedDriver> then,
      Consumer<Exception> failed) {
    var key = new Version(driver, version);
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
    var store = this.store;
    app.background(
        "load a driver",
        () -> store.load(driver, version),
        result -> {
          loaded.put(key, result);
          loading.remove(key).forEach(w -> w.then().accept(result));
        },
        error -> loading.remove(key).forEach(w -> w.failed().accept(error)));
  }

  /** A version of the driver loaded in this session, or null while there is none. */
  LoadedDriver loaded(Driver driver) {
    for (var entry : loaded.entrySet()) {
      if (entry.getKey().driver().equals(driver)) {
        return entry.getValue();
      }
    }
    return null;
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
    return address.resolve(store.address(driver, version));
  }
}
