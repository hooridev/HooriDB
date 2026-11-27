package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.sql.Connection;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Rows of text, as a file holds them, written into a table in batches, as an import writes them:
 * each batch one {@code INSERT} of many rows, with its values bound as parameters, which names the
 * table and the columns given values quoted as the provider quotes them; the other columns take
 * their defaults. A text goes in as the server reads it into the column's type, but where the
 * column's kind says how a file writes its values otherwise: a moment of ISO 8601 with its offset,
 * a date and time with a {@code T}, a truth as {@code true}, {@code false}, {@code t}, {@code f},
 * {@code 1} or {@code 0}, and binary data in hex, after {@code \x} or {@code 0x} or not.
 *
 * <p>A batch that fails is taken back to the savepoint before it and written again row by row,
 * each within a savepoint of its own, which tells the row that fails: that row is skipped, or the
 * import stops there, as the options say. A row with a moment that the server would store as
 * another, as one in an hour that the session's time zone shows twice, fails so too, unsent. A
 * failure of the table rather than of a row, a lost connection, a refused access, or a table that
 * is gone, stops it at once. So a table whose storage keeps no transactions, as MyISAM's, which
 * would keep what a rollback is to take back, takes no import.
 */
public final class Inserts {

  /** How many failures a report lists; it counts them all. */
  public static final int LISTED = 1000;

  // The most characters of values a batch holds before it goes, so that a statement stays far
  // below the servers' packet sizes.
  private static final long CHARACTERS = 4L * 1024 * 1024;

  private Inserts() {}

  /** Whether the rows go in all or nothing, or a batch at a time. */
  public enum Commit {
    /** In one transaction: a stop or a cancel leaves the table as it was. */
    ONCE,
    /** Each batch in a transaction of its own: what was committed stays at a stop or a cancel. */
    EACH_BATCH
  }

  /** What a row that fails does. */
  public enum OnError {
    /** The import stops at the row, and takes back the transaction it is in. */
    STOP,
    /** The row is left out, and listed with why. */
    SKIP
  }

  /**
   * @param empty whether the table's rows are deleted first, in the first transaction
   * @param batch the most rows of one {@code INSERT}, and of a transaction of {@link
   *     Commit#EACH_BATCH}; fewer where the values are long or many
   */
  public record Options(boolean empty, int batch, Commit commit, OnError onError) {

    public static final Options DEFAULT = new Options(false, 1000, Commit.ONCE, OnError.STOP);

    public Options {
      Objects.requireNonNull(commit, "commit");
      Objects.requireNonNull(onError, "onError");
      if (batch < 1) {
        throw new IllegalArgumentException("A batch of " + batch + " rows");
      }
    }

    public Options withEmpty(boolean empty) {
      return new Options(empty, batch, commit, onError);
    }

    public Options withBatch(int batch) {
      return new Options(empty, batch, commit, onError);
    }

    public Options withCommit(Commit commit) {
      return new Options(empty, batch, commit, onError);
    }

    public Options withOnError(OnError onError) {
      return new Options(empty, batch, commit, onError);
    }
  }

  /** A row of the source, by the line of the file it starts on. */
  public sealed interface Row {

    long line();

    /** Its values, one per column given values, in their order; null for NULL. */
    record Values(long line, List<String> values) implements Row {

      public Values {
        values = Collections.unmodifiableList(new ArrayList<>(values));
      }
    }

    /** A row that cannot be written, as one with too few or too many values, and why. */
    record Unreadable(long line, String reason) implements Row {}
  }

  /** Where the rows come from, one after another. */
  @FunctionalInterface
  public interface Source {

    /** The next row, or null after the last. */
    Row next() throws IOException;
  }

  /** A row that was not written, by its line, and why: the server's words, or the source's. */
  public record Failed(long line, String message) {}

  /**
   * How far it is: the rows taken from the source, those written in the transaction so far and
   * before it, those committed, and those that failed.
   */
  public record Count(long read, long written, long committed, long failed) {

    public static final Count NONE = new Count(0, 0, 0, 0);
  }

  /** Told after each batch, on the calling thread. */
  @FunctionalInterface
  public interface Progress {

    Progress NONE = count -> {};

    void at(Count count);
  }

  /**
   * What came of it: the count, in which {@link Count#committed} rows are in the table, the first
   * {@link #LISTED} rows that failed, the one it stopped at, or null where it did not stop at a
   * row, and whether it was cancelled before its end.
   */
  public record Report(Count count, List<Failed> failed, Failed stoppedAt, boolean cancelled) {

    /**
     * @throws IllegalArgumentException if it both stopped at a row and was cancelled
     */
    public Report {
      if (stoppedAt != null && cancelled) {
        throw new IllegalArgumentException("Stopped at a row and cancelled");
      }
      failed = failed.stream().sorted(Comparator.comparingLong(Failed::line)).toList();
    }

    /** Whether it stopped at a row rather than going to the end. */
    public boolean stopped() {
      return stoppedAt != null;
    }
  }

  /**
   * Writes the source's rows into the table, as the options say. The connection must have
   * auto-commit on and no transaction open, as one of the import's own has, and is left so;
   * nothing else may run on it meanwhile.
   *
   * <p>The call blocks. An interrupt of the calling thread cancels the statement that runs, as
   * {@link Query} does, or the reading of the source, and once the transaction it was in is rolled
   * back the report says that it was cancelled, with what an earlier batch of {@link
   * Commit#EACH_BATCH} committed, which stays; the thread is left interrupted. An interrupt once
   * the source has given its last row and the rows are written, as the transaction commits, lets
   * the commit end, so that the report counts it. Where the connection is given up as the cancel
   * runs, the call ends with {@link QueryException.Failure#ABORTED}, and before the import began
   * with {@link QueryException.Failure#CANCELLED}.
   *
   * @param columns all the table's columns, in their order, as {@link Rows#read} takes them
   * @param targets the indexes of the columns given values among them, in the order of a row's
   *     values
   * @param readOnly whether the connection is read-only, which refuses the import before anything
   *     is sent
   * @throws QueryException where the import could not begin, as for a table that keeps no
   *     transactions, or stopped for the table, the connection, or the commit, after all that was
   *     not committed was rolled back
   * @throws IOException where the source failed so, after all that was not committed was rolled
   *     back
   * @throws IllegalArgumentException if no column, or one twice or out of range, is given values,
   *     a column is not the table's, or a PostgreSQL connection is to another database than the
   *     table's
   * @throws IllegalStateException if the connection has a transaction open, or auto-commit off
   */
  public static Report write(
      Connection connection,
      Provider provider,
      Table table,
      List<Column> columns,
      List<Integer> targets,
      Source source,
      Options options,
      boolean readOnly,
      Progress progress)
      throws QueryException, IOException {
    Rows.requireColumnsOf(table, columns);
    if (targets.isEmpty()
        || targets.stream().distinct().count() != targets.size()
        || targets.stream().anyMatch(i -> i < 0 || i >= columns.size())) {
      throw new IllegalArgumentException("Columns " + targets + " of " + columns.size());
    }
    if (readOnly) {
      throw QueryException.of(
          QueryException.Failure.OTHER,
          "The connection is read-only, which writes nothing into " + table.name());
    }
    Rows.requireConnectedTo(connection, provider, table);
    Changes.requireNoTransaction(connection, provider);
    var dialect = provider.dialect().rows();
    if (!dialect.transactional(connection, provider, table, Purpose.IMPORT)) {
      throw QueryException.of(
          QueryException.Failure.OTHER,
          table.name() + " keeps no transactions, as its storage engine, such as MyISAM, has"
              + " none: what failed could not be taken back, so nothing is imported into it");
    }
    var reads = dialect.describe(connection, provider, table, columns, false).reads();
    var kinds = targets.stream().map(i -> reads.get(i).field().kind()).toList();
    var names = new StringJoiner(", ", " (", ")");
    targets.forEach(i -> names.add(provider.quote(columns.get(i).name())));
    var writing = new Writing(
        connection,
        provider,
        "INSERT INTO " + Rows.qualified(provider, table) + names + " VALUES ",
        kinds,
        options,
        progress);
    Transactions.begin(connection, provider);
    try {
      if (options.empty()) {
        writing.empty("DELETE FROM " + Rows.qualified(provider, table));
      }
      var rows = Math.max(1, Math.min(options.batch(), dialect.parameters() / kinds.size()));
      var report = writing.all(source, rows);
      // A stop takes back the transaction it is in, which in one of each batch holds that batch
      // alone.
      Transactions.end(connection, provider, !report.stopped());
      return writing.report(report);
    } catch (QueryException | IOException | RuntimeException e) {
      try {
        Transactions.end(connection, provider, false);
      } catch (QueryException ended) {
        e.addSuppressed(ended);
        throw e;
      }
      if (cancelled(e)) {
        return writing.cancelled();
      }
      throw e;
    }
  }

  // A row of the source by its line, with its values as they are bound.
  private record Bound(long line, List<Object> values) {}

  // One import as it goes: its counts and failures, on the calling thread.
  private static final class Writing {

    private final Connection connection;
    private final Provider provider;
    private final String insert;
    private final List<Kind> kinds;
    private final Options options;
    private final Progress progress;
    private final List<Failed> failed = new ArrayList<>();
    private long read;
    private long written; // in the transaction, and committed before it
    private long committed;
    private long failures;

    Writing(
        Connection connection,
        Provider provider,
        String insert,
        List<Kind> kinds,
        Options options,
        Progress progress) {
      this.connection = connection;
      this.provider = provider;
      this.insert = insert;
      this.kinds = kinds;
      this.options = options;
      this.progress = progress;
    }

    void empty(String sql) throws QueryException {
      Journal.sql(
          provider,
          Purpose.IMPORT,
          sql,
          Outcome.Changed::new,
          () -> Query.run(connection, provider, sql, statement -> statement.executeLargeUpdate()));
    }

    // Writes every row of the source, a batch at a time, up to the row it stops at.
    Report all(Source source, int rows) throws QueryException, IOException {
      var batch = new ArrayList<Row.Values>();
      long characters = 0;
      for (var row = source.next(); row != null; row = source.next()) {
        read++;
        switch (row) {
          case Row.Unreadable(var line, var reason) -> {
            var failure = new Failed(line, reason);
            if (options.onError() == OnError.STOP) {
              // A row before it may fail first; what went in is taken back all the same.
              var earlier = batch.isEmpty() ? null : batch(batch, false);
              return stop(earlier != null ? earlier : failure);
            }
            fail(failure);
          }
          case Row.Values values -> {
            if (values.values().size() != kinds.size()) {
              throw new IllegalArgumentException(
                  values.values().size() + " values for " + kinds.size() + " columns");
            }
            batch.add(values);
            characters += values.values().stream()
                .mapToLong(v -> v == null ? 0 : v.length())
                .sum();
          }
        }
        if (batch.size() >= rows || characters >= CHARACTERS) {
          var stopped = batch(batch, true);
          if (stopped != null) {
            return stop(stopped);
          }
          batch.clear();
          characters = 0;
        }
      }
      // A cancel as the last rows were read, of which none went: the commit has not begun.
      if (Thread.currentThread().isInterrupted()) {
        throw QueryException.of(QueryException.Failure.CANCELLED, "");
      }
      var stopped = batch.isEmpty() ? null : batch(batch, true);
      return stopped != null ? stop(stopped) : new Report(count(), failed, null, false);
    }

    // Writes the batch, and in a transaction of each batch commits it where asked: the row it
    // stopped at, or null. Rows with a moment that the server would store as another go one by
    // one, which fails them.
    private Failed batch(List<Row.Values> batch, boolean commit) throws QueryException {
      var bound = batch.stream().map(row -> new Bound(row.line(), values(row))).toList();
      var shifted = shifted(bound);
      var stopped = shifted.isEmpty() ? whole(bound) : oneByOne(bound, shifted);
      if (stopped != null) {
        return stopped;
      }
      if (commit && options.commit() == Commit.EACH_BATCH) {
        Transactions.commit(connection, provider);
        committed = written;
      }
      progress.at(count());
      return null;
    }

    // Writes the rows in one statement, or one at a time where it fails: the row it stopped at, or
    // null.
    private Failed whole(List<Bound> rows) throws QueryException {
      var savepoint = Transactions.savepoint(connection, provider, Purpose.IMPORT);
      try {
        insert(rows);
        Transactions.release(connection, provider, Purpose.IMPORT, savepoint);
        written += rows.size();
        return null;
      } catch (QueryException e) {
        if (ofTable(e)) {
          throw e;
        }
        Transactions.rollBack(connection, provider, Purpose.IMPORT, savepoint);
        return oneByOne(rows, Set.of());
      }
    }

    // Writes the rows one at a time, each within a savepoint, but those with a shifted moment: the
    // row it stopped at, or null.
    private Failed oneByOne(List<Bound> rows, Set<OffsetDateTime> shifted) throws QueryException {
      for (var row : rows) {
        var moment = row.values().stream()
            .filter(v -> v instanceof OffsetDateTime && shifted.contains(v))
            .findFirst();
        Failed failure;
        if (moment.isPresent()) {
          failure = new Failed(
              row.line(),
              "The server would store another moment than " + moment.get()
                  + ": the session's time zone shows its time twice, or it is out of range");
        } else {
          var savepoint = Transactions.savepoint(connection, provider, Purpose.IMPORT);
          try {
            insert(List.of(row));
            Transactions.release(connection, provider, Purpose.IMPORT, savepoint);
            written++;
            continue;
          } catch (QueryException e) {
            if (ofTable(e)) {
              throw e;
            }
            Transactions.rollBack(connection, provider, Purpose.IMPORT, savepoint);
            failure = new Failed(row.line(), e.detail().isEmpty() ? e.getMessage() : e.detail());
          }
        }
        if (options.onError() == OnError.STOP) {
          return failure;
        }
        fail(failure);
      }
      return null;
    }

    // The values of the row as they are bound, as its columns' kinds read them.
    private List<Object> values(Row.Values row) {
      var texts = row.values();
      var values = new Object[texts.size()];
      for (var i = 0; i < values.length; i++) {
        values[i] = value(kinds.get(i), texts.get(i));
      }
      return Arrays.asList(values);
    }

    // The moments of the rows that the server would store as others, which it is asked about only
    // where moments go in through the session's time.
    private Set<OffsetDateTime> shifted(List<Bound> rows) throws QueryException {
      if (!kinds.contains(Kind.DATE_TIME_ZONED)) {
        return Set.of();
      }
      var moments = new LinkedHashSet<OffsetDateTime>();
      for (var row : rows) {
        for (var value : row.values()) {
          if (value instanceof OffsetDateTime moment) {
            moments.add(moment);
          }
        }
      }
      return moments.isEmpty()
          ? Set.of()
          : provider.dialect().rows().shifted(connection, provider, Purpose.IMPORT, moments);
    }

    private void insert(List<Bound> rows) throws QueryException {
      var dialect = provider.dialect().rows();
      var values = new ArrayList<Object>();
      var parameter = Changes.parameters(dialect, values);
      var sql = new StringBuilder(insert);
      for (var r = 0; r < rows.size(); r++) {
        var row = new StringJoiner(", ", r == 0 ? "(" : ", (", ")");
        rows.get(r).values().forEach(value -> row.add(parameter.apply(value)));
        sql.append(row);
      }
      var statement = sql.toString();
      Journal.sql(
          provider,
          Purpose.IMPORT,
          statement,
          Outcome.Changed::new,
          () -> Query.run(connection, provider, statement, prepared -> {
            for (var v = 0; v < values.size(); v++) {
              dialect.bind(prepared, v + 1, values.get(v));
            }
            return prepared.executeLargeUpdate();
          }));
    }

    private void fail(Failed failure) {
      failures++;
      if (failed.size() < LISTED) {
        failed.add(failure);
      }
    }

    // Stopped at the row: what the transaction wrote is rolled back, which the caller does.
    private Report stop(Failed failure) {
      fail(failure);
      written = committed;
      return new Report(count(), failed, failure, false);
    }

    // The report once the transaction ended, committed where it did not stop.
    Report report(Report report) {
      committed = written;
      return new Report(count(), report.failed(), report.stoppedAt(), false);
    }

    // Cancelled, once what the transaction wrote was rolled back.
    Report cancelled() {
      written = committed;
      return new Report(count(), failed, null, true);
    }

    private Count count() {
      return new Count(read, written, committed, failures);
    }
  }

  // Whether the failure is how a cancel ended a statement, or the reading of the source, whose
  // channel an interrupt closes.
  private static boolean cancelled(Exception e) {
    return switch (e) {
      case QueryException q -> q.failure() == QueryException.Failure.CANCELLED;
      case ClosedByInterruptException _, InterruptedIOException _ -> true;
      default -> false;
    };
  }

  // Whether the failure is of the table or the connection rather than of a row: no other row
  // would go in either.
  private static boolean ofTable(QueryException e) {
    return switch (e.failure()) {
      case CONNECTION_LOST, ABORTED, CANCELLED, ACCESS_DENIED, NOT_FOUND -> true;
      case COLUMNS_CHANGED, CLAUSE_REFUSED, OTHER -> false;
    };
  }

  private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .optionalStart()
      .appendLiteral('T')
      .optionalEnd()
      .optionalStart()
      .appendLiteral(' ')
      .optionalEnd()
      .append(DateTimeFormatter.ISO_LOCAL_TIME)
      .toFormatter(Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter MOMENT = new DateTimeFormatterBuilder()
      .append(LOCAL)
      .parseLenient()
      .appendOffset("+HH:MM:ss", "Z")
      .toFormatter(Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT);

  /**
   * The text as the value it is bound as for a column of the kind: the text itself, but where the
   * kind reads a file's text otherwise, as the class says; null stays NULL, and a text that is no
   * such value stays text, which the server reads or refuses.
   */
  static Object value(Kind kind, String text) {
    if (text == null) {
      return null;
    }
    try {
      return switch (kind) {
        case DATE_TIME_ZONED ->
          OffsetDateTime.parse(text.strip(), MOMENT).withOffsetSameInstant(ZoneOffset.UTC);
        case DATE_TIME -> LocalDateTime.parse(text.strip(), LOCAL);
        case BOOLEAN -> truth(text.strip());
        case BINARY -> bytes(text.strip());
        case TEXT, INTEGER, DECIMAL, DATE, TIME, JSON, OTHER -> text;
      };
    } catch (DateTimeException | IllegalArgumentException _) {
      return text;
    }
  }

  private static Object truth(String text) {
    return switch (text.toLowerCase(Locale.ROOT)) {
      case "true", "t", "1", "yes", "y", "on" -> Boolean.TRUE;
      case "false", "f", "0", "no", "n", "off" -> Boolean.FALSE;
      default -> text;
    };
  }

  private static Binary bytes(String text) {
    var hex = text.startsWith("\\x") || text.startsWith("0x") || text.startsWith("0X")
        ? text.substring(2)
        : text;
    var bytes = HexFormat.of().parseHex(hex);
    return new Binary(bytes, bytes.length);
  }
}
