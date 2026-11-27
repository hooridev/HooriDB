package dev.hoori.db.core.file;

import dev.hoori.db.core.data.Inserts;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A file of delimited text read for an import: its first rows as a preview, as the format reads
 * them, and all its rows as the source of {@link Inserts#write}, with the values of the file's
 * columns that go into the table's.
 */
public final class Import {

  private Import() {}

  /**
   * The first rows of a file, as the format reads them.
   *
   * @param names the names of the file's columns: the header's where the format has one, else
   *     {@code Column 1} and on, as many as the rows have values at most
   * @param rows the rows after the header, each with its values, null for NULL
   */
  public record Preview(List<String> names, List<List<String>> rows) {

    public Preview {
      names = List.copyOf(names);
      rows = rows.stream()
          .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
          .toList();
    }
  }

  /**
   * The header and up to {@code rows} rows after it, read from the file's start.
   *
   * @throws IOException where the file cannot be read, or not in the format, as a quoted value
   *     that does not end or bytes that are no text of the encoding
   */
  public static Preview preview(Path file, Format.Delimited format, int rows) throws IOException {
    try (var reader = reader(file, format)) {
      List<String> header = null;
      if (format.header()) {
        var first = reader.next();
        header = first == null ? List.of() : named(first.values());
      }
      // Not a row more, which could fail the preview of those before it.
      var read = new ArrayList<List<String>>();
      while (read.size() < rows) {
        var row = reader.next();
        if (row == null) {
          break;
        }
        read.add(row.values());
      }
      var widest = read.stream().mapToInt(List::size).max().orElse(0);
      return new Preview(
          header != null
              ? header
              : IntStream.rangeClosed(1, widest).mapToObj(i -> "Column " + i).toList(),
          read);
    }
  }

  // The header's names, an unquoted one equal to NULL's text as it was written.
  private static List<String> named(List<String> values) {
    var names = new ArrayList<String>();
    for (var i = 0; i < values.size(); i++) {
      names.add(values.get(i) == null ? "" : values.get(i));
    }
    return names;
  }

  /**
   * The rows of the file after its header, each as the values of the file's columns given, in
   * their order, as a source for {@link Inserts#write}, open until it is closed. A row that has
   * another number of values than the header, or than the first row without one, is {@link
   * Inserts.Row.Unreadable}.
   *
   * @param columns the indexes of the file's columns whose values go in, from 0
   * @throws IOException where the file cannot be opened
   */
  public static Opened open(Path file, Format.Delimited format, List<Integer> columns)
      throws IOException {
    return new Opened(reader(file, format), format.header(), List.copyOf(columns));
  }

  /** The rows of a file, as {@link #open} gives them. */
  public static final class Opened implements Inserts.Source, Closeable {

    private final DelimitedReader reader;
    private final List<Integer> columns;
    private boolean header;
    private int width = -1; // the number of values each row has, once the first told it

    private Opened(DelimitedReader reader, boolean header, List<Integer> columns) {
      this.reader = reader;
      this.header = header;
      this.columns = columns;
    }

    @Override
    public Inserts.Row next() throws IOException {
      var row = reader.next();
      if (row != null && header) {
        header = false;
        width = row.values().size();
        row = reader.next();
      }
      if (row == null) {
        return null;
      }
      var values = row.values();
      if (width < 0) {
        width = values.size();
      }
      if (values.size() != width) {
        return new Inserts.Row.Unreadable(
            row.line(),
            "It has " + values(values.size()) + " where " + values(width) + " come before");
      }
      var given = new ArrayList<String>(columns.size());
      for (var column : columns) {
        if (column >= values.size()) {
          return new Inserts.Row.Unreadable(
              row.line(), "It has no column " + (column + 1) + ", only " + values.size());
        }
        given.add(values.get(column));
      }
      return new Inserts.Row.Values(row.line(), given);
    }

    private static String values(int n) {
      return n + (n == 1 ? " value" : " values");
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }
  }

  private static DelimitedReader reader(Path file, Format.Delimited format) throws IOException {
    var in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024);
    try {
      return new DelimitedReader(format, in);
    } catch (RuntimeException e) {
      in.close();
      throw e;
    }
  }
}
