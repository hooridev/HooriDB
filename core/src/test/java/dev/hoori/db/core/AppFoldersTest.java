package dev.hoori.db.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppFoldersTest {

  // Not used as a folder; it only makes paths that are absolute on every platform.
  @TempDir
  Path root;

  private final Path home = Path.of("home", "user");

  @Test
  void windowsUsesAppDataAndLocalAppData() {
    var folders = AppFolders.find(
        "Windows 11",
        env("APPDATA", root.resolve("Roaming"), "LOCALAPPDATA", root.resolve("Local")),
        home);
    assertEquals(root.resolve("Roaming").resolve("HooriDB"), folders.config());
    assertEquals(root.resolve("Local").resolve("HooriDB"), folders.data());
  }

  @Test
  void linuxUsesTheXdgVariables() {
    var folders = AppFolders.find(
        "Linux",
        env("XDG_CONFIG_HOME", root.resolve("config"), "XDG_DATA_HOME", root.resolve("data")),
        home);
    assertEquals(root.resolve("config").resolve("hooridb"), folders.config());
    assertEquals(root.resolve("data").resolve("hooridb"), folders.data());
  }

  @Test
  void unsetEmptyOrRelativeVariablesFallBackToTheHomeFolder() {
    var windows = new AppFolders(
        home.resolve("AppData/Roaming/HooriDB"), home.resolve("AppData/Local/HooriDB"));
    var linux =
        new AppFolders(home.resolve(".config/hooridb"), home.resolve(".local/share/hooridb"));
    for (var value : new String[] {null, "", "relative/folder"}) {
      var env = new HashMap<String, String>();
      for (var name :
          new String[] {"APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "XDG_DATA_HOME"}) {
        env.put(name, value);
      }
      assertEquals(windows, AppFolders.find("Windows 11", env::get, home), "value " + value);
      assertEquals(linux, AppFolders.find("Linux", env::get, home), "value " + value);
    }
  }

  private static UnaryOperator<String> env(String a, Path aValue, String b, Path bValue) {
    return Map.of(a, aValue.toString(), b, bValue.toString())::get;
  }
}
