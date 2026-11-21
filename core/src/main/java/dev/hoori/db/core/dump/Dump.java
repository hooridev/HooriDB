package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Exports objects of a database to a file of SQL that creates them anew, with their rows or
 * without, on an empty database: the provider's own generator, not its server's dump tool.
 *
 * <p>Everything is read in one transaction, read only and in one snapshot, as {@link
 * DumpDialect#begin} says, so that the rows of all tables are those of one moment, where the
 * server keeps transactions for them. The objects go in the order {@link Order} gives; the rows of
 * each table are read in portions and written as they come, so that a table of any size costs no
 * more than a portion here, as {@code INSERT}s of many rows each, their values as literals of the
 * provider's SQL ({@link dev.hoori.db.core.data.RowsDialect#literal}).
 *
 * <p>The file is written beside the one chosen under another name and takes its place only once it
 * is whole: a dump that fails or is cancelled leaves the chosen file as it was, and nothing of its
 * own.
 *
 * <p>Each call blocks, and runs through {@link Query}: an interrupt of the calling thread ends it
 * with {@link QueryException.Failure#CANCELLED}, or {@link QueryException.Failure#ABORTED} where
 * the connection had to be given up. A connection serves one call at a time; on PostgreSQL it
 * exports only objects of its own database, and on MariaDB and MySQL it must be on the database
 * exported, whose objects the server then names without it, so that the file can be read into
 * another.
 */
public final class Dump {

  /** How many rows an {@code INSERT} holds unless chosen otherwise. */
  public static final int ROWS_PER_INSERT = 100;

  /** How many rows are read from the server at a time. */
  static final int PORTION = 1000;

  private Dump() {}

  /** What of the objects goes into the file. */
  public enum Content {
    /** What creates them, and the rows of the tables. */
    STRUCTURE_AND_DATA,
    /** What creates them, without rows. */
    STRUCTURE,
    /** Only the rows of the tables, and the values of sequences, into objects that exist. */
    DATA;

    /** Whether what creates the objects goes. */
    public boolean structure() {
      return this != DATA;
    }

    /** Whether the rows go. */
    public boolean data() {
      return this != STRUCTURE;
    }
  }

  /**
   * How the file is written.
   *
   * @param drop whether each object is dropped, where it exists, before it is created
   * @param create whether the schemas, or the database where the provider has no schemas, are
   *     created where they do not exist, and the database gone to
   * @param rowsPerInsert how many rows each {@code INSERT} holds, at least 1
   * @param transaction whether the rows go in one transaction, all or none
   * @param noForeignKeyChecks whether the session that reads the file back checks no foreign keys,
   *     where {@link DumpDialect#foreignKeyChecks} says its servers let it
   * @param keysAfterData whether the tables' indexes and foreign keys are added after their rows,
   *     which goes faster, rather than right after all tables
   */
  public record Options(
      boolean drop,
      boolean create,
      int rowsPerInsert,
      boolean transaction,
      boolean noForeignKeyChecks,
      boolean keysAfterData) {

    /** Nothing dropped or created, rows as {@link #ROWS_PER_INSERT}, keys after the rows. */
    public static final Options DEFAULT =
        new Options(false, false, ROWS_PER_INSERT, false, false, true);

    public Options {
      if (rowsPerInsert < 1) {
        throw new IllegalArgumentException("At least one row per INSERT: " + rowsPerInsert);
      }
    }
  }

  /**
   * What an export writes: objects of one database, what of them, and how.
   *
   * @param items the objects, at least one, each once; a routine or trigger whose objects are not
   *     among them needs them where the file is read
   */
  public record Choice(Database database, List<Item> items, Content content, Options options) {

    public Choice {
      Objects.requireNonNull(database, "database");
      items = List.copyOf(new LinkedHashSet<>(items));
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(options, "options");
      if (items.isEmpty()) {
        throw new IllegalArgumentException("Nothing to export");
      }
      for (var item : items) {
        if (!item.container().database().equals(database)) {
          throw new IllegalArgumentException(item.shown() + " is not of " + database.name());
        }
      }
    }
  }

  /** Told as the export goes: the object it writes now, and how many of a table's rows so far. */
  @FunctionalInterface
  public interface Progress {
    void at(Item item, long rows);
  }

  /** What an export wrote: the file, its size, its objects and the rows of their tables. */
  public record Written(Path file, long bytes, int objects, long rows) {}

  /**
   * The objects of the schema, or of the database, those of its schemas that are not the server's
   * own where the provider has schemas, read in one snapshot.
   */
  public static List<Listed> list(Connection connection, Provider provider, Container container)
      throws QueryException {
    requireConnectedTo(connection, provider, container.database());
    var dialect = provider.dialect().dump();
    dialect.begin(connection, provider);
    try {
      return dialect.list(connection, provider, container);
    } finally {
      endQuietly(connection, provider);
    }
  }

  /**
   * The definitions of the objects, read in one snapshot, in the order that creates them, which
   * {@link Order} gives.
   */
  public static List<Definition> definitions(
      Connection connection, Provider provider, List<Item> items) throws QueryException {
    var dialect = provider.dialect().dump();
    for (var item : items) {
      requireConnectedTo(connection, provider, item.container().database());
    }
    dialect.begin(connection, provider);
    try {
      return Order.sorted(dialect.define(connection, provider, items));
    } finally {
      endQuietly(connection, provider);
    }
  }

  /**
   * Writes the chosen objects to the file, which it replaces once it is whole.
   *
   * @throws QueryException as {@link Query} reports it; {@link QueryException.Failure#NOT_FOUND}
   *     also where an object no longer exists
   * @throws IOException where the file cannot be written
   * @throws IllegalArgumentException if the connection is to another database
   */
  public static Written write(
      Connection connection, Provider provider, Choice choice, Path file, Progress progress)
      throws QueryException, IOException {
    requireConnectedTo(connection, provider, choice.database());
    var target = file.toAbsolutePath();
    var folder = target.getParent();
    var part = Files.createTempFile(folder, "." + target.getFileName() + "-", ".part");
    var done = false;
    try {
      Written written;
      try (var out = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
        written = new Writing(connection, provider, choice, out, progress).write(target);
      }
      move(part, target);
      done = true;
      return new Written(target, Files.size(target), written.objects(), written.rows());
    } finally {
      if (!done) {
        Files.deleteIfExists(part);
      }
    }
  }

  private static void move(Path part, Path target) throws IOException {
    try {
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException _) {
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // A failure to end a snapshot that was only read in changes nothing of what was read; a
  // connection it broke is found by its next call.
  private static void endQuietly(Connection connection, Provider provider) {
    try {
      provider.dialect().dump().end(connection, provider);
    } catch (QueryException _) {
      // As said.
    }
  }

  static void requireConnectedTo(Connection connection, Provider provider, Database database)
      throws QueryException {
    if (provider.keepsDocuments()) {
      throw new IllegalArgumentException(provider.displayName() + " is no server of SQL");
    }
    String current;
    try {
      current = connection.getCatalog();
    } catch (SQLException e) {
      throw Query.failure(connection, provider, e);
    }
    if (!database.name().equals(current)) {
      throw new IllegalArgumentException(
          "An export of " + database.name() + " on a connection to " + current);
    }
  }

  // One export's writing, from the snapshot's start to its end.
  private static final class Writing {

    private final Connection connection;
    private final Provider provider;
    private final DumpDialect dialect;
    private final Choice choice;
    private final Writer out;
    private final Progress progress;
    private final boolean delimiter;
    private int objects;
    private long rows;

    Writing(
        Connection connection, Provider provider, Choice choice, Writer out, Progress progress) {
      this.connection = connection;
      this.provider = provider;
      this.dialect = provider.dialect().dump();
      this.choice = choice;
      this.out = out;
      this.progress = progress;
      this.delimiter = provider.dialect().syntax().clientDelimiter();
    }

    Written write(Path target) throws QueryException, IOException {
      dialect.begin(connection, provider);
      try {
        return written(target);
      } catch (QueryException e) {
        // What the file did not take, as its disk was full, is no failure of the server's.
        if (e.getCause() instanceof UncheckedIOException io) {
          throw io.getCause();
        }
        throw e;
      } finally {
        endQuietly(connection, provider);
      }
    }

    private Written written(Path target) throws QueryException, IOException {
      var content = choice.content();
      var options = choice.options();
      var sorted = Order.sorted(dialect.define(connection, provider, choice.items()));
      comment("Exported by HooriDB from " + provider.displayName() + ", database "
          + choice.database().name() + ", at "
          + Instant.now().truncatedTo(ChronoUnit.SECONDS) + ", in one snapshot");
      comment(
          switch (content) {
            case STRUCTURE_AND_DATA -> "Structure and data";
            case STRUCTURE -> "Structure only";
            case DATA -> "Data only";
          });
      out.write('\n');
      statements(dialect.header(options));
      if (options.create()) {
        var containers = new ArrayList<Container>();
        for (var item : choice.items()) {
          if (!containers.contains(item.container())) {
            containers.add(item.container());
          }
        }
        statements(dialect.create(connection, provider, containers));
      }
      var triggers =
          sorted.stream().filter(d -> d.item().kind() == Item.Kind.TRIGGER).toList();
      var created =
          sorted.stream().filter(d -> d.item().kind() != Item.Kind.TRIGGER).toList();
      if (content.structure()) {
        if (options.drop()) {
          section("Drop what exists");
          for (var definition : sorted) {
            statements(definition.dropKeys());
          }
          for (var definition : sorted.reversed()) {
            statement(definition.drop());
          }
        }
        for (var definition : created) {
          section(title(definition.item()));
          create(definition);
        }
        if (!options.keysAfterData()) {
          keys(created);
        }
      }
      if (content.data()) {
        data(sorted);
      }
      if (content.structure()) {
        if (options.keysAfterData()) {
          keys(created);
        }
        if (content.data()) {
          for (var definition : created) {
            if (definition.item().kind() == Item.Kind.VIEW
                && !definition.restore().isEmpty()) {
              section("Rows of " + title(definition.item()));
              statements(definition.restore());
            }
          }
        }
        for (var definition : triggers) {
          section(title(definition.item()));
          create(definition);
        }
      }
      out.write('\n');
      statements(dialect.footer(options));
      return new Written(target, 0, objects, rows);
    }

    private void create(Definition definition) throws IOException {
      objects++;
      progress.at(definition.item(), 0);
      if (definition.body() && delimiter) {
        out.write("DELIMITER ;;\n");
        for (var sql : definition.create()) {
          out.write(sql);
          out.write(";;\n");
        }
        out.write("DELIMITER ;\n");
      } else {
        statements(definition.create());
      }
    }

    private void keys(List<Definition> created) throws IOException {
      for (var definition : created) {
        if (!definition.indexes().isEmpty()) {
          section("Indexes of " + title(definition.item()));
          statements(definition.indexes());
        }
      }
      for (var definition : created) {
        if (!definition.keys().isEmpty()) {
          section("Foreign keys of " + title(definition.item()));
          statements(definition.keys());
        }
      }
    }

    // The tables' rows in the order their keys refer to each other, then the values of what
    // counts with them: sequences and identity columns.
    private void data(List<Definition> sorted) throws QueryException, IOException {
      var options = choice.options();
      var transaction = options.transaction();
      if (transaction) {
        out.write('\n');
        statement(dialect.beginTransaction());
      }
      for (var definition : sorted) {
        var item = definition.item();
        if (item.kind() != Item.Kind.TABLE) {
          continue;
        }
        if (!choice.content().structure()) {
          objects++;
        }
        progress.at(item, 0);
        var data = dialect.data(connection, provider, item);
        if (data != null) {
          rows(item, data);
        }
      }
      for (var definition : sorted) {
        var kind = definition.item().kind();
        if ((kind == Item.Kind.TABLE || kind == Item.Kind.SEQUENCE)
            && !definition.restore().isEmpty()) {
          if (kind == Item.Kind.SEQUENCE && !choice.content().structure()) {
            objects++;
          }
          section("Value of " + title(definition.item()));
          statements(definition.restore());
        }
      }
      if (transaction) {
        out.write('\n');
        statement("COMMIT");
      }
    }

    // The table's rows as they come, a portion at a time, straight into the file.
    private void rows(Item item, DumpDialect.Data data) throws QueryException {
      var literals = provider.dialect().rows();
      var perInsert = choice.options().rowsPerInsert();
      var session = provider.dialect().session();
      var written = Journal.sql(
          provider,
          Purpose.EXPORT,
          data.select(),
          n -> Outcome.rows(n),
          () -> Query.run(connection, provider, data.select(), statement -> {
            statement.setFetchSize(session.fetchSize(PORTION));
            long n = 0;
            try (var result = statement.executeQuery()) {
              var values = data.reader().values(result.getMetaData());
              var line = new StringBuilder();
              while (result.next()) {
                line.setLength(0);
                line.append(
                    n % perInsert == 0 ? (n == 0 ? "" : ";\n") + data.insert() + "\n(" : ",\n(");
                for (var i = 0; i < values.size(); i++) {
                  if (i > 0) {
                    line.append(", ");
                  }
                  line.append(literals.literal(values.get(i).read(result, i + 1)));
                }
                line.append(')');
                if (n == 0) {
                  write("\n-- Rows of " + title(item) + "\n");
                }
                write(line);
                n++;
                if (n % perInsert == 0) {
                  progress.at(item, n);
                }
              }
            }
            if (n > 0) {
              write(";\n");
            }
            progress.at(item, n);
            return n;
          }));
      rows += written;
    }

    // Writes from the query's thread, whose step throws only what a driver throws.
    private void write(CharSequence text) {
      try {
        out.append(text);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private String title(Item item) {
      var kind = switch (item.kind()) {
        case TABLE -> "Table";
        case VIEW -> "View";
        case SEQUENCE -> "Sequence";
        case FUNCTION -> "Function";
        case PROCEDURE -> "Procedure";
        case TRIGGER -> "Trigger";
      };
      var schemas = provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN);
      return kind + " " + (schemas ? item.container().name() + "." : "") + item.shown();
    }

    private void section(String title) throws IOException {
      out.write('\n');
      comment(title);
    }

    private void comment(String text) throws IOException {
      for (var line : text.split("\\R", -1)) {
        out.write("-- ");
        out.write(line);
        out.write('\n');
      }
    }

    private void statements(List<String> statements) throws IOException {
      for (var sql : statements) {
        statement(sql);
      }
    }

    private void statement(String sql) throws IOException {
      if (sql.isEmpty()) {
        return;
      }
      out.write(sql);
      out.write(";\n");
    }
  }
}
