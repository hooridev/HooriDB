package dev.hoori.db.core.file;

import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rows written to a file in a format, as they are read: into a file of its own beside the one
 * asked for, which takes the place of that one only once it is whole, so that a failure or a cancel
 * leaves the folder as it was. The file is made as any the user makes there, with the permissions
 * the system gives a new file, not those of a temporary one.
 */
public final class Export {

  // What file systems refuse in a name, Windows' among them, and the names of its devices.
  private static final Pattern REFUSED = Pattern.compile("[\\x00-\\x1F<>:\"/\\\\|?*]");
  private static final Set<String> DEVICES = Set.of(
      "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
      "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

  private Export() {}

  /** What reads the rows, as {@link Rows#each} with its columns: the number of rows. */
  @FunctionalInterface
  public interface Reading {
    long read(Rows.Each each) throws QueryException, IOException;
  }

  /**
   * Writes what the reading gives to the file in the format, replacing a file of that name once
   * it is whole: the number of rows.
   *
   * <p>The call blocks; an interrupt of the calling thread cancels the reading, as {@link
   * Rows#each} says, and the file asked for stays as it was.
   *
   * @throws IOException where the file could not be written or put in its place, or a value
   *     cannot be written in the encoding
   * @throws QueryException as the reading failed
   */
  public static long write(Path file, Format format, Reading reading)
      throws IOException, QueryException {
    var folder = file.toAbsolutePath().getParent();
    var part = folder.resolve("." + file.getFileName() + "." + UUID.randomUUID() + ".part");
    // Made new, without the owner-only permissions of Files.createTempFile.
    var opened = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW);
    try {
      long rows;
      try (var out = new BufferedOutputStream(opened, 64 * 1024)) {
        var writer = RowWriter.of(format, out);
        rows = reading.read(writer);
        writer.finish();
      }
      try {
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException _) {
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
      }
      return rows;
    } catch (IOException | QueryException | RuntimeException e) {
      try {
        Files.deleteIfExists(part);
      } catch (IOException left) {
        e.addSuppressed(left);
      }
      throw e;
    }
  }

  /**
   * The names of the tables' files in one folder, in their order: each table's name with the
   * format's extension, what a file system refuses in a name replaced by {@code _}, and a number
   * after a name that an earlier one has already, in any case, as two tables whose names differ in
   * case alone would on Windows.
   */
  public static List<String> fileNames(List<Table> tables, Format format) {
    var taken = new HashSet<String>();
    var names = new ArrayList<String>();
    for (var table : tables) {
      var base = base(table.name());
      var name = base;
      for (var n = 2; !taken.add(name.toLowerCase(Locale.ROOT)); n++) {
        name = base + "-" + n;
      }
      names.add(name + "." + format.extension());
    }
    return names;
  }

  // Windows drops the dots and spaces a name ends with, and refuses some names that it ends with.
  private static String base(String name) {
    var base = REFUSED.matcher(name).replaceAll("_");
    var end = base.length();
    while (end > 0
        && (base.charAt(end - 1) == '.' || Character.isWhitespace(base.charAt(end - 1)))) {
      end--;
    }
    base = base.substring(0, end).strip();
    if (base.isEmpty()) {
      return "_";
    }
    return DEVICES.contains(base.toLowerCase(Locale.ROOT)) ? base + "_" : base;
  }
}
