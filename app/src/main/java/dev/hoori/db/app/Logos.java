package dev.hoori.db.app;

import com.rethinkj.ui.Image;
import dev.hoori.db.core.connection.Provider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The logos of the kinds of server, from Devicon, decoded once: the renderer keeps a texture per
 * image.
 */
final class Logos {

  // 64 pixels for an icon's 16, sharp up to a scale of 4.
  private static final float SCALE = 4;

  // Read at the first use, on the app's thread: a few small files from the app's own JAR.
  private static final Map<Provider, Image> BY_PROVIDER = read();

  private Logos() {}

  static Image of(Provider provider) {
    return BY_PROVIDER.get(provider);
  }

  private static Map<Provider, Image> read() {
    var logos = new EnumMap<Provider, Image>(Provider.class);
    for (var provider : Provider.values()) {
      var name = provider.name().toLowerCase(Locale.ROOT) + ".png";
      try (var in = Logos.class.getResourceAsStream(name)) {
        if (in == null) {
          throw new IllegalStateException("No logo " + name);
        }
        logos.put(provider, Image.decode(in.readAllBytes(), SCALE));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return logos;
  }
}
