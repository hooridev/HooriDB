package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.updater.Asset;
import com.rethinkj.updater.Download;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Release;
import com.rethinkj.updater.Version;
import dev.hoori.db.core.connection.StoredUpdates;
import dev.hoori.db.core.connection.UpdateStore;
import dev.hoori.db.core.update.Channel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * HooriDB's updates, a part of the state: what they keep, the check at the start and after a
 * change of channel, the download, the notice above the tree, and the restart into an update. None
 * without an updater, as in a script run or a build of its own, and none checked for where the
 * version that runs is not a SemVer version. Only the app's thread uses it.
 */
final class Updates {

  final Updater updater; // null for none
  private final Version installed; // the version that runs, parsed; or null
  private final UpdateStore store;
  final Kept kept;
  StoredUpdates stored = StoredUpdates.NONE; // as loaded, and changed since
  private Channel chosen; // a channel chosen before the settings were loaded, or null
  Notice notice; // the one notice of updates, or null
  Ready ready; // the update downloaded, to restart with, or null
  Release downloading; // the release whose download runs, or null
  private Work work; // the check or the download, or null
  Ready restartWith; // what the exit that Restart now began restarts with, or null
  private Work restarting; // the save of the attempt and the start of the helper, or null
  private boolean confirmed; // whether shown() ran
  private Work beginning; // the start of updates, which may hold the helper's report, or null
  private boolean startLooks; // the start looks once the settings are read, where they say so
  private boolean asked; // a check asked for before updates had started

  /** An update downloaded and verified: its version and its file. */
  record Ready(Version version, Download download) {}

  /**
   * The notice of updates, above the tree, apart from those of the consoles' sessions: of a kind,
   * with a title and a text, an act where it has one, and the label of what dismisses it.
   *
   * @param act the label of its act, or null for none
   * @param primary whether the act is the notice's main one
   * @param offer whether it offers an update, which a switch of the channel drops
   */
  record Notice(
      Callout.Kind kind,
      String title,
      String text,
      String act,
      Runnable action,
      boolean primary,
      String dismiss,
      boolean offer) {}

  /**
   * What the start found of updates: what they keep, what the helper reported of the last one, or
   * null, and whether the attempt's file is still there after it was locked.
   */
  private record Begun(StoredUpdates stored, Installation.Result result, boolean kept) {}

  /** An update found: its release, its archive, and whether HooriDB can replace itself. */
  private record Offer(Release release, Asset asset, boolean writable) {}

  private static final String DISMISS = "Dismiss";

  /**
   * @param folder where what updates keep is kept
   * @param updater updates HooriDB, off the app's thread; null for none
   * @param version the version that runs
   */
  Updates(Path folder, Updater updater, String version) {
    this.store = new UpdateStore(folder);
    this.kept = new Kept("update settings", () -> Kept.writing(stored, store::save));
    this.updater = updater;
    Version parsed;
    try {
      parsed = Version.parse(version);
    } catch (IllegalArgumentException _) { // not a SemVer version: no updates
      parsed = null;
    }
    this.installed = parsed;
  }

  /** Whether an Exit waits for updates: the helper's report to be taken, or the helper to start. */
  boolean busy() {
    return restarting != null || beginning != null;
  }

  /**
   * Loads what updates keep and reads what the helper reported, which says whether the downloads
   * are done with. A file that cannot be read counts as none, as updates can do without it.
   */
  void start(State s) {
    var started = s.app.background(
        "load update settings",
        begin(store, updater),
        begun -> begun(s, begun),
        error -> begun(s, new Begun(StoredUpdates.NONE, null, false)));
    beginning = updater == null ? null : started; // only a helper's report, read once, is worth it
  }

  /**
   * The start of updates, off the app's thread: what they keep, and what the helper reported, which
   * is cleared once read.
   */
  private static Callable<Begun> begin(UpdateStore store, Updater updater) {
    return () -> {
      var stored = stored(store);
      if (updater == null) {
        return new Begun(stored, null, false);
      }
      var result = reported(updater);
      var attempt = stored.attempt();
      if (result != null
          && result.status() == Installation.Result.Status.ROLLED_BACK
          && attempt != null) {
        // Now, as the report is gone: an Exit before it is shown must not lose the skip.
        stored = stored.withSkipped(attempt.version());
        try {
          store.save(stored);
        } catch (IOException _) {
          // Saved again once the start has taken it.
        }
      }
      boolean locked = result != null && locked(result) && attempt != null;
      if (result != null) {
        updater.clear(locked ? attempt.file() : null); // the helper is done with them
      }
      return new Begun(stored, result, locked && Files.isRegularFile(attempt.file()));
    };
  }

  /** What updates keep; none where the file cannot be read, as updates can do without it. */
  private static StoredUpdates stored(UpdateStore store) {
    try {
      return store.load();
    } catch (IOException _) {
      return StoredUpdates.NONE;
    }
  }

  /** What the helper reported of the last update, or null for nothing or a report not read. */
  private static Installation.Result reported(Updater updater) {
    try {
      return updater.result().orElse(null);
    } catch (IOException _) {
      return null;
    }
  }

  private static boolean locked(Installation.Result result) {
    return result.status() == Installation.Result.Status.REJECTED
        && result.reason().equals("locked");
  }

  private void begun(State s, Begun begun) {
    beginning = null;
    var loaded = begun.stored();
    stored = chosen == null ? loaded : loaded.withChannel(chosen);
    if (installed != null && updater != null) {
      // Only the app image chooses by itself: a JAR run elsewhere would choose for it.
      stored = stored.withChannelFor(installed);
    }
    var result = begun.result();
    if (result != null) {
      report(s, result, begun.kept());
    }
    kept.canSave = true;
    if (!stored.equals(loaded) || kept.unsaved) {
      s.keeping.persist(kept);
    }
    // Not again at once after an update that failed: the same update would fail the same way.
    startLooks = result == null || result.status() == Installation.Result.Status.APPLIED;
    settingsReady(s);
    s.settled(); // an Exit waited for the report to be taken
  }

  /**
   * The settings were read, or kept their defaults, or updates have started: once both are done,
   * runs the start's check where the settings or a request meanwhile ask for it.
   */
  void settingsReady(State s) {
    if (!kept.canSave || s.keeping.settingsLoading) {
      return;
    }
    // Not even one asked for after an update that failed: its notice may still offer the download.
    boolean look = startLooks && (asked || s.settings.checkForUpdates());
    asked = false;
    startLooks = false;
    if (look) {
      check(s);
    }
  }

  /**
   * Checking for updates was turned on: looks once, or leaves it to the start's check, which reads
   * the setting then, where updates have not started.
   */
  void turnedOn(State s) {
    if (kept.canSave) {
      check(s);
    }
  }

  /** Tells what the helper reported of the last update, and keeps what it means for the next. */
  private void report(State s, Installation.Result result, boolean kept) {
    var attempt = stored.attempt();
    switch (result.status()) {
      case APPLIED -> {
        stored = stored.withAttempt(null);
        s.tell("HooriDB was updated to " + s.version + ".");
      }
      case ROLLED_BACK -> rolledBack(s, result, attempt);
      case REJECTED -> rejected(s, result, attempt, kept);
    }
  }

  private void rolledBack(State s, Installation.Result result, StoredUpdates.Attempt attempt) {
    String to = attempt == null ? "" : " to HooriDB " + attempt.version();
    if (attempt != null) {
      stored = stored.withSkipped(attempt.version()); // until a higher one comes out
    }
    stored = stored.withAttempt(null);
    notice = warning(
        "The update" + to + " was undone",
        result.reason().equals("not-confirmed")
            ? "It did not start in time, so HooriDB " + s.version + " runs again."
            : result.message());
  }

  /**
   * The helper did not apply the update: where files were in use, the notice offers it again, in
   * the channel it belongs to.
   */
  private void rejected(
      State s, Installation.Result result, StoredUpdates.Attempt attempt, boolean kept) {
    if (locked(result)
        && attempt != null
        && (Channel.of(attempt.version()) == Channel.STABLE || channel() == Channel.NIGHTLY)) {
      notice = new Notice(
          Callout.Kind.WARNING,
          "The update was not applied",
          "Files of HooriDB were in use, as by a virus scanner.",
          "Try again",
          () -> tryAgain(s, attempt, kept),
          false,
          DISMISS,
          true);
    } else {
      stored = stored.withAttempt(null);
      notice = warning("The update was not applied", result.message());
    }
  }

  private static Notice warning(String title, String text) {
    return new Notice(Callout.Kind.WARNING, title, text, null, null, false, DISMISS, false);
  }

  /** "HooriDB" and the version, as notices of updates name a version. */
  private static String named(Version version) {
    return "HooriDB " + version;
  }

  /** Restarts with the download the helper could not apply, or checks anew where it is gone. */
  private void tryAgain(State s, StoredUpdates.Attempt attempt, boolean kept) {
    notice = null;
    if (kept) {
      ready = new Ready(attempt.version(), new Download(attempt.file(), attempt.sha256()));
      notice = readyNotice(s, ready);
      s.restartToUpdate();
    } else {
      check(s);
    }
  }

  private static Notice readyNotice(State s, Ready ready) {
    return new Notice(
        Callout.Kind.INFO,
        named(ready.version()) + " is ready",
        "Restart HooriDB to update from " + s.version + ".",
        "Restart now",
        s::restartToUpdate,
        true,
        "Later",
        true);
  }

  /**
   * Looks for an update in the channel, and downloads it where HooriDB can replace itself; a
   * check that fails, as without a network, shows nothing. Not while one is ready or HooriDB
   * closes; one asked for before updates have started runs once they have.
   */
  void check(State s) {
    if (updater == null
        || installed == null
        || work != null
        || ready != null
        || restarting != null
        || s.guard.exiting()) {
      return;
    }
    if (!kept.canSave) {
      asked = true;
      return;
    }
    work = s.app.background(
        "check for updates",
        look(updater, installed, channel(), stored.skipped()),
        offer -> {
          work = null;
          offer.ifPresent(o -> offered(s, o));
        },
        error -> work = null);
  }

  /** The check for an update, off the app's thread: the latest release offered, with its archive. */
  private static Callable<Optional<Offer>> look(
      Updater updater, Version installed, Channel channel, Version skipped) {
    return () -> {
      var found = channel.offer(installed, updater.releases(), skipped);
      if (found.isEmpty()) {
        return Optional.<Offer>empty();
      }
      var release = found.get().latest();
      var asset = release.asset(Updater.ASSET);
      return asset.map(a -> new Offer(release, a, updater.writable()));
    };
  }

  private void offered(State s, Offer offer) {
    var release = offer.release();
    if (!offer.writable()) {
      notice = new Notice(
          Callout.Kind.INFO,
          named(release.version()) + " is available",
          "HooriDB cannot replace itself in " + updater.app()
              + ". Download it from its release page.",
          "Open release page",
          () -> s.app.openUri(release.page(), s::tell),
          false,
          DISMISS,
          true);
      return;
    }
    var asset = offer.asset();
    downloading = release;
    work = s.app.background(
        "download HooriDB " + release.version(),
        download(updater, release, asset),
        download -> {
          work = null;
          downloading = null;
          ready = new Ready(release.version(), download);
          notice = readyNotice(s, ready);
        },
        error -> {
          work = null;
          downloading = null;
          notice = warning(
              named(release.version()) + " could not be downloaded", State.messageOf(error));
        });
  }

  /** The download of the release's archive, off the app's thread. */
  private static Callable<Download> download(Updater updater, Release release, Asset asset) {
    return () -> updater.download(release, asset);
  }

  /** The channel updates come from: the one stored, else the one of the version that runs. */
  Channel channel() {
    return stored.channel() != null ? stored.channel() : defaultChannel();
  }

  /** The channel the running version belongs to, or Stable where it is not a SemVer version. */
  Channel defaultChannel() {
    return installed == null ? Channel.STABLE : Channel.of(installed);
  }

  /**
   * Help, Stable or Nightly updates: saved, and an offer not acted on goes, for one of the new
   * channel. Not while HooriDB closes.
   */
  void channel(State s, Channel channel) {
    if (s.guard.exiting()) {
      return;
    }
    var was = channel();
    chosen = channel;
    stored = stored.withChannel(channel);
    s.keeping.persist(kept);
    if (was == Channel.NIGHTLY
        && channel == Channel.STABLE
        && installed != null
        && Channel.of(installed) == Channel.NIGHTLY) {
      s.tell("HooriDB stays on " + s.version + " until a higher release comes out.");
    } else {
      s.tell((channel == Channel.NIGHTLY ? "Nightly" : "Stable") + " updates on");
    }
    if (channel == was) {
      return;
    }
    if (work != null) {
      work.cancel();
      work = null;
      downloading = null;
    }
    ready = null;
    if (notice != null && notice.offer()) {
      notice = null;
    }
    check(s);
  }

  /** Whether the helper is being started, before which Exit and Restart now do nothing. */
  boolean restarting() {
    return restarting != null;
  }

  /**
   * Where Exit would close the windows after Restart now: saves the attempt, then starts the
   * helper, which takes over once HooriDB has closed; if it cannot, HooriDB stays open.
   */
  void restartNow(State s) {
    var with = restartWith;
    var download = with.download();
    var before = stored.attempt(); // one a helper may still be at work on
    stored = stored.withAttempt(
        new StoredUpdates.Attempt(with.version(), download.file(), download.sha256()));
    restarting = s.app.background(
        "restart to update",
        restart(updater, download, store, stored),
        started -> {
          restarting = null;
          // Started: what came meanwhile is saved, then the windows close; no second restart.
          restartWith = null;
          ready = null;
          if (notice != null && notice.offer()) {
            notice = null;
          }
          s.settled();
        },
        error -> {
          restarting = null;
          restartWith = null;
          s.stay("HooriDB stayed open, as it could not restart to update.");
          stored = stored.withAttempt(before); // a refused restart wrote nothing
          notice = warning("HooriDB could not restart", State.messageOf(error));
        });
  }

  /** The restart, off the app's thread: starts the helper, then saves the attempt it makes. */
  private static Callable<Download> restart(
      Updater updater, Download download, UpdateStore store, StoredUpdates now) {
    return () -> {
      updater.restart(download); // refused while a helper is at work: nothing written then
      try {
        store.save(now);
      } catch (IOException _) {
        // The helper waits for HooriDB to close: it closes, and a rollback is not skipped.
      }
      return download;
    };
  }

  /**
   * HooriDB has shown its first frame: a version the helper started tells it that it runs, once.
   * A failure says so in the status line.
   */
  void shown(State s) {
    if (updater == null || confirmed) {
      return;
    }
    confirmed = true;
    s.app.background(
        "confirm the update",
        confirm(updater),
        done -> {},
        error -> s.tell("HooriDB could not confirm the update: " + State.messageOf(error)));
  }

  /** The confirmation that the version the helper started runs, off the app's thread. */
  private static Callable<Boolean> confirm(Updater updater) {
    return () -> {
      updater.confirm();
      return true;
    };
  }

  void dismiss() {
    notice = null;
  }
}
