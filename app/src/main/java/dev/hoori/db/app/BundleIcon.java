package dev.hoori.db.app;

import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the app's icon for its bundle, as the build of a release needs it. */
final class BundleIcon {

  private BundleIcon() {}

  /** Writes {@code HooriDB.png} (Linux) into the folder that the one argument names. */
  public static void main(String[] args) throws IOException {
    var folder = Files.createDirectories(Path.of(args[0]));
    Files.write(folder.resolve(HooriDb.NAME + ".png"), Png.encode(HooriDb.ICON.image(256)));
  }
}
