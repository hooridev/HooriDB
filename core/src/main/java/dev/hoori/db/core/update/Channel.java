package dev.hoori.db.core.update;

import com.rethinkj.updater.Release;
import com.rethinkj.updater.Update;
import com.rethinkj.updater.Version;
import java.util.Collection;
import java.util.Optional;

/** Which releases of HooriDB are offered as updates: the releases alone, or the nightlies too. */
public enum Channel {
  STABLE(null),
  NIGHTLY("nightly");

  private final String id;

  Channel(String id) {
    this.id = id;
  }

  /** The updater's id of the channel: null for the stable one, else the first pre-release part. */
  public String id() {
    return id;
  }

  /** The channel a version is on, which a start without a stored channel takes. */
  public static Channel of(Version version) {
    return version.isPreRelease() && version.preRelease().getFirst().equals(NIGHTLY.id)
        ? NIGHTLY
        : STABLE;
  }

  /**
   * The update from the installed version in this channel, leaving out the versions up to the one
   * skipped, as one that was rolled back; empty if none is newer.
   *
   * @param skipped the version skipped, or null for none
   */
  public Optional<Update> offer(Version installed, Collection<Release> releases, Version skipped) {
    var left = releases.stream()
        .filter(r -> skipped == null || r.version().compareTo(skipped) > 0)
        .toList();
    return Update.find(installed, left, id);
  }
}
