package dev.hoori.db.core.connection;

import com.rethinkj.updater.Version;
import dev.hoori.db.core.update.Channel;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What updates keep between sessions: the channel chosen, or derived at the first start; the
 * version skipped since it was rolled back; and the update whose restart was the last attempt.
 * Each is null for none.
 */
public record StoredUpdates(Channel channel, Version skipped, Attempt attempt) {

  public static final StoredUpdates NONE = new StoredUpdates(null, null, null);

  /** The update handed to the helper: the version, the downloaded file and its checksum. */
  public record Attempt(Version version, Path file, String sha256) {
    public Attempt {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(sha256, "sha256");
    }
  }

  public StoredUpdates withChannel(Channel channel) {
    return new StoredUpdates(channel, skipped, attempt);
  }

  public StoredUpdates withSkipped(Version skipped) {
    return new StoredUpdates(channel, skipped, attempt);
  }

  public StoredUpdates withAttempt(Attempt attempt) {
    return new StoredUpdates(channel, skipped, attempt);
  }

  /** These, with the channel of the installed version where none is stored yet. */
  public StoredUpdates withChannelFor(Version installed) {
    return channel != null ? this : withChannel(Channel.of(installed));
  }
}
