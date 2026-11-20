package dev.hoori.db.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.updater.Release;
import com.rethinkj.updater.Source;
import com.rethinkj.updater.Update;
import com.rethinkj.updater.Version;
import dev.hoori.db.core.connection.StoredUpdates;
import dev.hoori.db.core.connection.UpdateStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelTest {

  @TempDir
  Path folder;

  // The releases a source publishes; a test adds to them as a repository would.
  final List<Release> published = new ArrayList<>();
  final Source source = () -> List.copyOf(published);

  static Release release(String version) {
    return new Release(
        Version.parse(version),
        "v" + version,
        "HooriDB " + version,
        Instant.parse("2026-11-01T00:00:00Z"),
        "",
        false,
        URI.create("https://github.com/hooridev/HooriDB/releases/tag/v" + version),
        List.of());
  }

  void publish(String... versions) {
    for (var v : versions) {
      published.add(release(v));
    }
  }

  /** What the start of the installed version offers, with what is stored, as the app does it. */
  Optional<String> offered(String installed) throws IOException {
    var version = Version.parse(installed);
    var store = new UpdateStore(folder);
    var stored = store.load();
    var updates = stored.withChannelFor(version);
    if (!updates.equals(stored)) {
      store.save(updates);
    }
    return updates
        .channel()
        .offer(version, source.releases(), updates.skipped())
        .map(Update::latest)
        .map(r -> r.version().toString());
  }

  void choose(Channel channel) throws IOException {
    var store = new UpdateStore(folder);
    store.save(store.load().withChannel(channel));
  }

  @Test
  void stableOffersNoNightly() throws IOException {
    publish("0.1.0", "0.2.0-nightly.202611200300");
    assertEquals(Optional.empty(), offered("0.1.0"));
    publish("0.2.0");
    assertEquals(Optional.of("0.2.0"), offered("0.1.0"));
  }

  @Test
  void nightlyOffersAReleaseHigherThanTheInstalledNightly() throws IOException {
    publish("0.2.0-nightly.202611190300", "0.2.0-nightly.202611200300", "0.1.0");
    assertEquals(Optional.of("0.2.0-nightly.202611200300"), offered("0.2.0-nightly.202611190300"));
  }

  @Test
  void afterSwitchingFromNightlyToStableNothingIsOfferedUntilAHigherRelease() throws IOException {
    choose(Channel.STABLE);
    publish("0.2.0-nightly.202611200300");
    assertEquals(Optional.empty(), offered("0.2.0-nightly.202611190300"));
    publish("0.3.0");
    assertEquals(Optional.of("0.3.0"), offered("0.2.0-nightly.202611190300"));
    // A release of the nightly's own version is higher than it, as SemVer orders them.
    published.removeLast();
    publish("0.2.0");
    assertEquals(Optional.of("0.2.0"), offered("0.2.0-nightly.202611190300"));
  }

  @Test
  void withoutAStoredChannelANightlyTakesNightlyAndAReleaseStable() throws IOException {
    offered("0.2.0-nightly.202611190300");
    assertEquals(Channel.NIGHTLY, new UpdateStore(folder).load().channel());
    var other = folder.resolve("other");
    var store = new UpdateStore(other);
    assertEquals(null, store.load().channel(), "nothing stored yet");
    store.save(store.load().withChannelFor(Version.parse("0.2.0")));
    assertEquals(Channel.STABLE, new UpdateStore(other).load().channel());
  }

  @Test
  void aNightlyWithoutItsOwnChoiceUpdatesToAReleaseAndStaysOnNightly() throws IOException {
    publish("0.2.0");
    assertEquals(Optional.of("0.2.0"), offered("0.2.0-nightly.202611190300"));
    // The release runs now; its channel is the one stored, not the one its version implies.
    publish("0.3.0-nightly.202612010300");
    assertEquals(Optional.of("0.3.0-nightly.202612010300"), offered("0.2.0"));
    assertEquals(Channel.NIGHTLY, new UpdateStore(folder).load().channel());
  }

  @Test
  void aSkippedVersionIsNotOfferedAgainButAHigherOneIs() throws IOException {
    publish("0.3.0");
    var store = new UpdateStore(folder);
    store.save(StoredUpdates.NONE.withSkipped(Version.parse("0.3.0")));
    assertEquals(Optional.empty(), offered("0.2.0"));
    assertEquals(Optional.empty(), offered("0.2.0"), "at later starts too");
    publish("0.3.1");
    assertEquals(Optional.of("0.3.1"), offered("0.2.0"));
    assertTrue(Channel.STABLE.offer(Version.parse("0.2.0"), published, null).isPresent());
  }
}
