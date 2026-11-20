package dev.hoori.db.core.document.mongo;

import dev.hoori.db.core.document.DocumentClient;
import dev.hoori.db.core.document.DocumentDriver;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.Target;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The MongoDB Java driver as a {@link DocumentDriver}. This class and the others of its package are
 * defined in the class loader of the downloaded driver, never in the app's, which has no driver:
 * nothing of HooriDB names them but by their name, as {@link
 * dev.hoori.db.core.driver.Driver#driverClass}. It names none of the driver's classes itself, so
 * that it loads beside any driver; {@link Mongo} uses them once it connects.
 */
public final class MongoBridge implements DocumentDriver {

  // Without SLF4J the driver says so through java.util.logging once, on the console; it has
  // nothing else to say that a user of HooriDB could act on. Held, as a logger without a
  // reference may be collected with its level.
  private static final Logger QUIET = Logger.getLogger("org.mongodb.driver");

  static {
    QUIET.setLevel(Level.OFF);
  }

  public MongoBridge() {}

  @Override
  public DocumentClient connect(Target target, char[] password) throws DocumentException {
    return Mongo.connect(target, password);
  }
}
