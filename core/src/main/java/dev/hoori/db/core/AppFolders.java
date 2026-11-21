package dev.hoori.db.core;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Where HooriDB keeps its files: the configuration, such as the connections, and data that can be
 * fetched again, such as downloaded drivers. It only names the folders and creates none.
 */
public record AppFolders(Path config, Path data) {

  /** The folders of the platform this runs on, for the current user. */
  public static AppFolders find() {
    return find(
        System.getProperty("os.name"), System::getenv, Path.of(System.getProperty("user.home")));
  }

  /**
   * The folders for the operating system named as Java names it, with the environment variables
   * and the home folder given. Every system but Windows follows the XDG Base Directory
   * specification.
   */
  public static AppFolders find(String osName, UnaryOperator<String> env, Path home) {
    if (osName.startsWith("Windows")) {
      var appData = home.resolve("AppData");
      return new AppFolders(
          base(env, "APPDATA", appData.resolve("Roaming")).resolve("HooriDB"),
          base(env, "LOCALAPPDATA", appData.resolve("Local")).resolve("HooriDB"));
    }
    return new AppFolders(
        base(env, "XDG_CONFIG_HOME", home.resolve(".config")).resolve("hooridb"),
        base(env, "XDG_DATA_HOME", home.resolve(".local").resolve("share")).resolve("hooridb"));
  }

  // The XDG specification has a relative path ignored. On Windows too, a relative one would follow
  // the working folder, which is no place for settings.
  private static Path base(UnaryOperator<String> env, String variable, Path fallback) {
    var value = env.apply(variable);
    if (value == null || value.isEmpty()) {
      return fallback;
    }
    try {
      var path = Path.of(value);
      return path.isAbsolute() ? path : fallback;
    } catch (InvalidPathException _) {
      return fallback;
    }
  }
}
