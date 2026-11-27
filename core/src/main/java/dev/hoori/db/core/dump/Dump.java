package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.definition.Definition;
import dev.hoori.db.core.definition.DefinitionDialect.Mode;
import dev.hoori.db.core.definition.Item;
import dev.hoori.db.core.definition.Listed;
import dev.hoori.db.core.definition.Sql;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Exports objects of a database to a file of SQL that creates them anew, with their rows or
 * without, on an empty database: the provider's own generator, not its server's dump tool.
 *
 * <p>Everything is read in one transaction, read only and in one snapshot, as {@link
 * dev.hoori.db.core.definition.DefinitionDialect#begin} says, and the objects and their
 * definitions as that dialect reads them, so that the rows of all tables are those of one moment, where the
 * server keeps transactions for them; where the server lets it, the tables are kept from being
 * changed by others until it ends ({@link DumpDialect#lock}). The objects go in the order {@link
 * Order} gives; the rows of each table are read in portions and written as they come, so that a
 * table of any size costs no more than a portion here, as {@code INSERT}s of many rows each, up to
 * {@link #INSERT_SIZE}, their values as literals of the provider's SQL ({@link
 * dev.hoori.db.core.data.RowsDialect#literal}).
 *
 * <p>The file is written beside the one chosen under another name and takes its place only once it
 * is whole, in one move: a dump that fails or is cancelled leaves the chosen file as it was, and
 * nothing of its own.
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

  /**
   * The size, in characters, past which an {@code INSERT} takes no more rows, however many it may
   * hold: well within what the servers take in one statement ({@code max_allowed_packet} of
   * MariaDB's 16 MiB and MySQL's 64 MiB), unless a row is larger alone.
   */
  public static final int INSERT_SIZE = 1 << 20;

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
   * @param rowsPerInsert how many rows each {@code INSERT} holds at most, at least 1: fewer where
   *     they pass {@link #INSERT_SIZE}
   * @param transaction whether the rows go in one transaction, all or none; a table whose engine
   *     keeps no transactions, as MyISAM, keeps what went into it before a failure all the same
   * @param noForeignKeyChecks whether the session that reads the file back checks no foreign keys,
   *     where {@link DumpDialect#foreignKeyChecks} says its servers let it
   * @param keysAfterData whether the tables' indexes and foreign keys are added after their rows,
   *     which goes faster, rather than right after all tables; the foreign keys of tables that
   *     refer to each other in a circle, or a table to itself, follow the rows either way, as no
   *     order of the rows suits them
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
   *     among them needs them where the file is read. Of an export of data alone only the tables,
   *     the sequences and the materialized views, which it refreshes, count.
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

    // What is defined: of data alone, what has rows, a value or a refresh.
    List<Item> defined() {
      return content.structure()
          ? items
          : items.stream()
              .filter(i -> switch (i.kind()) {
                case TABLE, SEQUENCE, VIEW -> true;
                case FUNCTION, PROCEDURE, TRIGGER -> false;
              })
              .toList();
    }
  }

  /** Told as the export goes: the object it writes now, and how many of a table's rows so far. */
  @FunctionalInterface
  public interface Progress {
    void at(Item item, long rows);

    /**
     * Asked once the file is whole, right before it takes the chosen one's place: false cancels
     * the export instead, which leaves the chosen file as it was; after true the file is replaced.
     */
    default boolean replacing() {
      return true;
    }
  }

  /** What an export wrote: the file, its size, its objects and the rows of their tables. */
  public record Written(Path file, long bytes, int objects, long rows) {}

  /**
   * The objects of the schema, or of the database, those of its schemas that are not the server's
   * own where the provider has schemas, read in one snapshot.
   */
  public static List<Listed> list(Connection connection, Provider provider, Container container)
      throws QueryException {
    var sql = new Sql(connection, provider, Purpose.EXPORT);
    requireConnectedTo(sql, container.database());
    Dump.begin(sql);
    try {
      return provider.dialect().definitions().list(sql, container);
    } finally {
      endQuietly(sql);
    }
  }

  /**
   * The definitions of the objects, read in one snapshot, in the order that creates them, which
   * {@link Order} gives.
   */
  public static List<Definition> definitions(
      Connection connection, Provider provider, List<Item> items) throws QueryException {
    var sql = new Sql(connection, provider, Purpose.EXPORT);
    for (var item : items) {
      requireConnectedTo(sql, item.container().database());
    }
    Dump.begin(sql);
    try {
      return Order.sorted(provider.dialect().definitions().define(sql, items, Mode.EXPORT));
    } finally {
      endQuietly(sql);
    }
  }

  /**
   * Writes the chosen objects to the file, which it replaces once it is whole and {@link
   * Progress#replacing} lets it.
   *
   * @throws QueryException as {@link Query} reports it; {@link QueryException.Failure#NOT_FOUND}
   *     also where an object no longer exists
   * @throws IOException where the file cannot be written, or not replaced in one move
   * @throws IllegalArgumentException if the connection is to another database
   */
  public static Written write(
      Connection connection, Provider provider, Choice choice, Path file, Progress progress)
      throws QueryException, IOException {
    var sql = new Sql(connection, provider, Purpose.EXPORT);
    requireConnectedTo(sql, choice.database());
    var target = file.toAbsolutePath();
    var folder = target.getParent();
    var part = Files.createTempFile(folder, "." + target.getFileName() + "-", ".part");
    var done = false;
    try {
      Writing.Counts written;
      try (var out = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
        written = new Writing(sql, choice, out, progress).write();
      }
      var bytes = Files.size(part);
      // The snapshot's end goes on after a cancel, which must not then find the file replaced.
      if (Thread.currentThread().isInterrupted() || !progress.replacing()) {
        throw QueryException.of(QueryException.Failure.CANCELLED, "");
      }
      // Beside the file, the move is the folder's own, which never leaves it half replaced.
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      done = true;
      return new Written(target, bytes, written.objects(), written.rows());
    } finally {
      if (!done) {
        Files.deleteIfExists(part);
      }
    }
  }

  // A failure to end a snapshot that was only read in changes nothing of what was read; a
  // connection it broke is found by its next call.
  private static void endQuietly(Sql sql) {
    try {
      sql.provider().dialect().definitions().end(sql);
    } catch (QueryException _) {
      // As said.
    }
  }

  // The snapshot begins on a connection in auto-commit, as Connector opens it, and nowhere else:
  // where it fails, its dialect undoes it by ending the transaction, which would end one that
  // the caller had open.
  private static void begin(Sql sql) throws QueryException {
    boolean autoCommits;
    try {
      autoCommits = sql.connection().getAutoCommit();
    } catch (SQLException e) {
      throw Query.failure(sql.connection(), sql.provider(), e);
    }
    if (!autoCommits) {
      throw new IllegalArgumentException("An export on a connection with a transaction open");
    }
    sql.provider().dialect().definitions().begin(sql);
  }

  static void requireConnectedTo(Sql sql, Database database) throws QueryException {
    var provider = sql.provider();
    var connection = sql.connection();
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

  /** The text as lines of comments of SQL, each ended: no line of it is read as a statement. */
  static String commented(String text) {
    var lines = new StringBuilder();
    for (var line : text.split("\\R", -1)) {
      lines.append("-- ").append(line).append('\n');
    }
    return lines.toString();
  }

  // One export's writing, from the snapshot's start to its end.
  private static final class Writing {

    private final Sql sql;
    private final Connection connection;
    private final Provider provider;
    private final DumpDialect dialect;
    private final Choice choice;
    private final Writer out;
    private final Progress progress;
    private final boolean delimiter;
    private int objects;
    private long rows;

    Writing(Sql sql, Choice choice, Writer out, Progress progress) {
      this.sql = sql;
      this.connection = sql.connection();
      this.provider = sql.provider();
      this.dialect = provider.dialect().dump();
      this.choice = choice;
      this.out = out;
      this.progress = progress;
      this.delimiter = provider.dialect().syntax().clientDelimiter();
    }

    // How many objects and rows it wrote.
    private record Counts(int objects, long rows) {}

    Counts write() throws QueryException, IOException {
      Dump.begin(sql);
      try {
        return written();
      } catch (QueryException e) {
        // What the file did not take, as its disk was full, is no failure of the server's.
        if (e.getCause() instanceof UncheckedIOException io) {
          throw io.getCause();
        }
        throw e;
      } finally {
        endQuietly(sql);
      }
    }

    private Counts written() throws QueryException, IOException {
      var content = choice.content();
      var options = choice.options();
      var defined = choice.defined();
      dialect.lock(
          sql, defined.stream().filter(i -> i.kind() == Item.Kind.TABLE).toList());
      var sorted = Order.sorted(provider.dialect().definitions().define(sql, defined, Mode.EXPORT));
      heading();
      statements(dialect.header(options));
      if (options.create()) {
        var containers = choice.items().stream().map(Item::container).distinct().toList();
        statements(dialect.create(sql, containers));
      }
      var created =
          sorted.stream().filter(d -> d.item().kind() != Item.Kind.TRIGGER).toList();
      // Keys that no order of the rows suits wait for them, whenever the rows go.
      var late =
          content.data() && !options.keysAfterData() ? Order.circular(created) : Set.<Item>of();
      if (content.structure()) {
        structure(sorted, created, late);
      }
      if (content.data()) {
        data(sorted);
      }
      if (content.structure()) {
        afterData(sorted, created, late);
      }
      out.write('\n');
      statements(dialect.footer(options));
      return new Counts(objects, rows);
    }

    private void heading() throws IOException {
      comment("Exported by HooriDB from " + provider.displayName() + ", database "
          + choice.database().name() + ", at "
          + Instant.now().truncatedTo(ChronoUnit.SECONDS) + ", in one snapshot");
      comment(
          switch (choice.content()) {
            case STRUCTURE_AND_DATA -> "Structure and data";
            case STRUCTURE -> "Structure only";
            case DATA -> "Data only";
          });
      out.write('\n');
    }

    // What exists goes first; then what creates the objects but triggers, with the indexes and
    // keys where they do not wait for the rows.
    private void structure(List<Definition> sorted, List<Definition> created, Set<Item> late)
        throws IOException {
      if (choice.options().drop()) {
        section("Drop what exists");
        statements(dialect.beforeDrops());
        for (var definition : sorted) {
          statements(definition.dropKeys());
        }
        for (var definition : sorted.reversed()) {
          statement(definition.drop());
        }
        statements(dialect.afterDrops());
      }
      for (var definition : created) {
        section(title(definition.item()));
        create(definition);
      }
      if (!choice.options().keysAfterData()) {
        indexes(created);
        foreignKeys(created.stream().filter(d -> !late.contains(d.item())).toList());
      }
    }

    // What waits for the rows: the indexes and keys, a materialized view's refresh, what would
    // refuse or hide rows, and the triggers, which would otherwise fire for each row.
    private void afterData(List<Definition> sorted, List<Definition> created, Set<Item> late)
        throws IOException {
      if (choice.options().keysAfterData()) {
        indexes(created);
        foreignKeys(created);
      } else {
        foreignKeys(created.stream().filter(d -> late.contains(d.item())).toList());
      }
      if (choice.content().data()) {
        refreshes(created);
      }
      for (var definition : created) {
        if (!definition.afterRows().isEmpty()) {
          section("What follows the rows of " + title(definition.item()));
          statements(definition.afterRows());
        }
      }
      for (var definition : sorted) {
        if (definition.item().kind() == Item.Kind.TRIGGER) {
          section(title(definition.item()));
          create(definition);
        }
      }
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

    private void indexes(List<Definition> created) throws IOException {
      for (var definition : created) {
        if (!definition.indexes().isEmpty()) {
          section("Indexes of " + title(definition.item()));
          statements(definition.indexes());
        }
      }
    }

    private void foreignKeys(List<Definition> created) throws IOException {
      for (var definition : created) {
        if (!definition.keys().isEmpty()) {
          section("Foreign keys of " + title(definition.item()));
          statements(definition.keys());
        }
      }
    }

    // A table's rows as they are read and written.
    private record TableData(Item table, Optional<DumpDialect.Data> data) {}

    // The tables' rows in the order their keys refer to each other, then the values of what
    // counts with them: sequences and identity columns. Of the data alone, what goes around them
    // so that the tables take them as they are, and the refresh of materialized views.
    private void data(List<Definition> sorted) throws QueryException, IOException {
      var alone = !choice.content().structure();
      // How each table's rows go, which may refuse the data alone, before a row is read; those
      // of engines without transactions first, which refer to no other.
      var tables = new ArrayList<TableData>();
      for (var definition : sorted) {
        var item = definition.item();
        if (item.kind() == Item.Kind.TABLE) {
          tables.add(new TableData(item, dialect.data(sql, item, alone)));
        }
      }
      tables.sort(Comparator.comparing(
          t -> t.data().map(DumpDialect.Data::transactional).orElse(true)));
      var around = new ArrayList<DumpDialect.Around>();
      if (alone) {
        var circular = Order.circular(sorted);
        around.add(dialect.circle(
            sorted.stream().filter(d -> circular.contains(d.item())).toList()));
        tables.forEach(t -> t.data().ifPresent(d -> around.add(d.around())));
      }
      var transaction = choice.options().transaction();
      if (transaction) {
        out.write('\n');
        statement(dialect.beginTransaction());
      }
      around(around, DumpDialect.Around::before, "Before the rows, so that the tables take them");
      for (var table : tables) {
        if (alone) {
          objects++;
        }
        progress.at(table.table(), 0);
        if (table.data().isPresent()) {
          rows(table.table(), table.data().get());
        }
      }
      values(sorted);
      if (alone) {
        refreshes(sorted);
      }
      around(around, DumpDialect.Around::after, "After the rows, what was there before them");
      if (transaction) {
        out.write('\n');
        statement("COMMIT");
      }
    }

    private void around(
        List<DumpDialect.Around> around,
        Function<DumpDialect.Around, List<String>> part,
        String title)
        throws IOException {
      var statements = around.stream().flatMap(a -> part.apply(a).stream()).toList();
      if (!statements.isEmpty()) {
        section(title);
        statements(statements);
      }
    }

    // The refresh of each materialized view that had rows, from its tables' rows as they are.
    private void refreshes(List<Definition> definitions) throws IOException {
      for (var definition : definitions) {
        if (definition.item().kind() == Item.Kind.VIEW && !definition.restore().isEmpty()) {
          if (!choice.content().structure()) {
            objects++;
          }
          section("Refresh of " + title(definition.item()) + ", from its tables as they are");
          statements(definition.restore());
        }
      }
    }

    private void values(List<Definition> sorted) throws IOException {
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
    }

    // The table's rows as they come, a portion at a time, straight into the file.
    private void rows(Item item, DumpDialect.Data data) throws QueryException {
      var session = provider.dialect().session();
      rows += Journal.sql(
          provider,
          Purpose.EXPORT,
          data.select(),
          n -> Outcome.rows(n),
          () -> Query.run(connection, provider, data.select(), statement -> {
            statement.setFetchSize(session.fetchSize(PORTION));
            try (var result = statement.executeQuery()) {
              return copied(item, data, result);
            }
          }));
    }

    // The rows of the result as INSERTs of so many rows each, or fewer where they pass the size;
    // how many went.
    private long copied(Item item, DumpDialect.Data data, ResultSet result) throws SQLException {
      var literals = provider.dialect().rows();
      var perInsert = choice.options().rowsPerInsert();
      var values = data.reader().values(result.getMetaData());
      var line = new StringBuilder();
      long n = 0;
      var inInsert = 0; // the rows of the INSERT so far
      long size = 0; // and its characters
      while (result.next()) {
        line.setLength(0);
        if (n == 0) {
          line.append('\n').append(commented("Rows of " + title(item)));
        }
        if (data.defaults()) {
          write(line.append(data.insert()).append(";\n"));
          n++;
          told(item, n, perInsert);
          continue;
        }
        if (inInsert > 0 && (inInsert >= perInsert || size >= INSERT_SIZE)) {
          line.append(";\n");
          inInsert = 0;
          size = 0;
        }
        line.append(inInsert == 0 ? data.insert() + "\n(" : ",\n(");
        literals(line, values, literals, result);
        write(line.append(')'));
        size += line.length();
        inInsert++;
        n++;
        told(item, n, perInsert);
      }
      if (n > 0 && !data.defaults()) {
        write(";\n");
      }
      progress.at(item, n);
      return n;
    }

    private void told(Item item, long n, int every) {
      if (n % every == 0) {
        progress.at(item, n);
      }
    }

    private static void literals(
        StringBuilder line, List<DumpDialect.Value> values, RowsDialect literals, ResultSet result)
        throws SQLException {
      for (var i = 0; i < values.size(); i++) {
        if (i > 0) {
          line.append(", ");
        }
        line.append(literals.literal(values.get(i).read(result, i + 1)));
      }
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
      out.write(commented(text));
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
