package dev.hoori.db.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * The desktop entry that lists HooriDB in the application menu of a Linux desktop and gives its
 * windows their icon in the dock, for the app image that the user unpacked into a folder of their
 * own, which no package installs: {@code applications/dev.hoori.db.desktop} in the XDG data
 * folder. It lies outside the image, so that an update, which replaces the image in its place,
 * leaves it.
 */
public final class DesktopEntry {

  /** The identity of HooriDB's windows, which the desktop finds the entry of a window by. */
  public static final String ID = "dev.hoori.db";

  private DesktopEntry() {}

  /**
   * Writes the entry for the app image that runs, unless it holds that already; so after the image
   * was moved, it points to the new place. It writes nothing but on Linux and from an app image,
   * not from a build of its own, as under Maven. Blocks on the disk.
   *
   * @param osName the operating system as Java names it
   * @param launcher where the image's launcher is, {@code bin/HooriDB} in it, as jpackage gives it
   *     in {@code jpackage.app-path}; null for a run from no app image
   * @param folders the user's folders, beside whose data folder the entry goes
   * @return whether it wrote the entry
   */
  public static boolean write(String osName, String launcher, AppFolders folders)
      throws IOException {
    // shortcut: no entry for a path with "=" or a control character, by which Exec cannot name a
    // program, or "%", which GLib looks for unexpanded; upgrade when someone unpacks HooriDB there.
    if (!"Linux".equals(osName)
        || launcher == null
        || launcher.chars().anyMatch(c -> c == '=' || c == '%' || Character.isISOControl(c))) {
      return false;
    }
    var bin = Path.of(launcher).toAbsolutePath();
    var icon = bin.getParent().resolveSibling("lib").resolve("HooriDB.png");
    var text = String.join(
        "\n",
        "[Desktop Entry]",
        "Type=Application",
        "Name=HooriDB",
        "Comment=Database manager",
        "Exec=" + string(argument(bin.toString())),
        "Icon=" + string(icon.toString()),
        "Terminal=false",
        "Categories=Development;Database;",
        "StartupWMClass=" + ID,
        "");
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    // On Linux the data folder is hooridb in the XDG data folder.
    var folder = folders.data().resolveSibling("applications");
    var file = folder.resolve(ID + ".desktop");
    if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), bytes)) {
      return false;
    }
    Files.createDirectories(folder);
    // Replaced whole, so that the menu never reads half an entry.
    var temporary = Files.createTempFile(folder, ID + ".", ".tmp");
    try {
      Files.write(temporary, bytes);
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return true;
  }

  // An argument of Exec, quoted whole, with the characters escaped that keep their meaning there.
  private static String argument(String value) {
    var escaped =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$");
    return "\"" + escaped + "\"";
  }

  // A value of type string, whose backslashes the desktop reads before it reads any quoting.
  private static String string(String value) {
    return value.replace("\\", "\\\\");
  }
}
