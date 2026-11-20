package dev.hoori.db.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

// Linux paths, which may hold what Windows refuses in one.
@EnabledOnOs(OS.LINUX)
class DesktopEntryTest {

  @TempDir
  Path root;

  private AppFolders folders() {
    return AppFolders.find("Linux", Map.of("XDG_DATA_HOME", root + "/data")::get, root);
  }

  private String entry() throws Exception {
    return Files.readString(root.resolve("data/applications/dev.hoori.db.desktop"));
  }

  /** The image's launcher and icon, written as the desktop reads them whatever the folder's name. */
  @Test
  void theEntryNamesTheLauncherAndTheIconOfTheImage() throws Exception {
    var image = root + "/Programs $HOME \"x\" `y` \\ Ä/HooriDB";
    assertTrue(DesktopEntry.write("Linux", image + "/bin/HooriDB", folders()));
    assertEquals("""
        [Desktop Entry]
        Type=Application
        Name=HooriDB
        Comment=Database manager
        Exec="ROOT/Programs \\\\$HOME \\\\"x\\\\" \\\\`y\\\\` \\\\\\\\ Ä/HooriDB/bin/HooriDB"
        Icon=ROOT/Programs $HOME "x" `y` \\\\ Ä/HooriDB/lib/HooriDB.png
        Terminal=false
        Categories=Development;Database;
        StartupWMClass=dev.hoori.db
        """.replace("ROOT", root.toString()), entry());
  }

  /** An entry as it would be written stays; after the image moved, it points to the new place. */
  @Test
  void theEntryIsWrittenOnlyWhenItChanges() throws Exception {
    var first = root + "/first/HooriDB/bin/HooriDB";
    assertTrue(DesktopEntry.write("Linux", first, folders()));
    assertFalse(DesktopEntry.write("Linux", first, folders()));
    var moved = root + "/moved/HooriDB";
    assertTrue(DesktopEntry.write("Linux", moved + "/bin/HooriDB", folders()));
    var text = entry();
    assertTrue(text.contains("\nExec=\"" + moved + "/bin/HooriDB\"\n"), text);
    assertTrue(text.contains("\nIcon=" + moved + "/lib/HooriDB.png\n"), text);
    assertFalse(text.contains("first"), text);
  }

  /** Without XDG_DATA_HOME, the entry goes into the home folder's .local/share. */
  @Test
  void theEntryGoesIntoTheDataFolderOfTheHomeFolder() throws Exception {
    var folders = AppFolders.find("Linux", name -> null, root.resolve("home"));
    assertTrue(DesktopEntry.write("Linux", root + "/HooriDB/bin/HooriDB", folders));
    assertTrue(
        Files.isRegularFile(root.resolve("home/.local/share/applications/dev.hoori.db.desktop")));
  }

  /**
   * A build of its own, as under Maven, has no app image; Windows has no such entry; and a path
   * that Exec cannot name a program by, or GLib not find it by, gets none.
   */
  @Test
  void nothingIsWrittenWithoutAnImageOnLinux() throws Exception {
    var launcher = root + "/HooriDB/bin/HooriDB";
    assertFalse(DesktopEntry.write("Linux", null, folders()));
    assertFalse(DesktopEntry.write("Windows 11", launcher, folders()));
    assertFalse(DesktopEntry.write("Linux", root + "/a=b/HooriDB/bin/HooriDB", folders()));
    assertFalse(DesktopEntry.write("Linux", root + "/100%/HooriDB/bin/HooriDB", folders()));
    assertFalse(DesktopEntry.write("Linux", root + "/a\nb/HooriDB/bin/HooriDB", folders()));
    assertFalse(Files.exists(root.resolve("data")));
  }
}
