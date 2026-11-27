package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.definition.Item;
import dev.hoori.db.core.definition.Sql;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * How PostgreSQL's objects are exported, besides their definitions, which its {@link
 * dev.hoori.db.core.definition.PostgreSqlDefinitions} reads: the rows of a table itself, not those
 * of its children, and a materialized view refreshed from its tables once their rows are in, as
 * {@code pg_dump} does. The tables exported are locked as the snapshot begins, so that no other
 * session changes their definitions or empties them until it ends, as the functions that write
 * definitions read the catalog as it is then, not as of the snapshot.
 */
public final class PostgreSqlDump extends DumpDialect {

  private static final String LOCK_TIMEOUT = "lock_timeout";

  // What the file's header sets, and its footer puts back from where the header kept it.
  private static final List<String> SETTINGS = List.of(
      "statement_timeout",
      LOCK_TIMEOUT,
      "client_encoding",
      "standard_conforming_strings",
      "search_path",
      "check_function_bodies",
      "client_min_messages",
      "IntervalStyle",
      "DateStyle");

  /** How long an export waits for another session to let go of a table it holds. */
  static final Duration LOCK_WAIT = Duration.ofSeconds(10);

  // The state of a lock that could not be had in time.
  private static final String LOCKED = "55P03";

  public PostgreSqlDump() {
    // Nothing to set up.
  }

  @Override
  public boolean foreignKeyChecks() {
    return false;
  }

  // ACCESS SHARE, as pg_dump takes it: what reads or writes rows goes on, and what changes a
  // table's definition, drops it or empties it waits for the export. A session that holds one
  // already gets the bound to let go of it, after which the export fails rather than holding up
  // everything that waits behind it. Taken before the snapshot, which the first SELECT fixes, so
  // that it holds what such a session committed: of a table it emptied, the rows it put back.
  @Override
  void lock(Connection connection, Provider provider, List<Item> tables) throws QueryException {
    if (tables.isEmpty()) {
      return;
    }
    var names = tables.stream()
        .map(t -> "ONLY " + qualified(provider, t.container().name(), t.name()))
        .collect(Collectors.joining(", "));
    var was = Sql.one(
        connection, provider, LOCK_TIMEOUT, "SHOW " + LOCK_TIMEOUT, rows -> rows.getString(1));
    Sql.execute(
        connection, provider, "SET LOCAL " + LOCK_TIMEOUT + " = '" + LOCK_WAIT.toMillis() + "ms'");
    try {
      Sql.execute(connection, provider, "LOCK TABLE " + names + " IN ACCESS SHARE MODE");
    } catch (QueryException e) {
      if (e.getCause() instanceof SQLException cause && LOCKED.equals(cause.getSQLState())) {
        throw QueryException.of(
            QueryException.Failure.OTHER,
            "Another session holds a table to export, as while it changes the table, and did not"
                + " let go of it within " + LOCK_WAIT.toSeconds() + " seconds: try again once it"
                + " has. " + e.detail());
      }
      throw e;
    }
    setting(connection, provider, LOCK_TIMEOUT, was);
  }

  // For the transaction alone.
  private static void setting(Connection connection, Provider provider, String name, String value)
      throws QueryException {
    Sql.rows(
        connection,
        provider,
        "SELECT pg_catalog.set_config(?, ?, true)",
        rows -> rows.getString(1),
        name,
        value);
  }

  private static String qualified(Provider provider, String schema, String name) {
    return provider.quote(schema) + "." + provider.quote(name);
  }

  private record Insertable(String kind, List<String> columns, boolean always) {}

  // The rows of the table itself, not those of its children; of each column that is not
  // generated, a value of a kind the literal writes as such, otherwise the server's text.
  @Override
  Optional<Data> data(Connection connection, Provider provider, Item table) throws QueryException {
    var name = qualified(provider, table.container().name(), table.name());
    var read = Sql.one(
        connection,
        provider,
        table.shown(),
        """
        SELECT c.relkind,
          pg_catalog.array_agg(pg_catalog.quote_ident(a.attname) ORDER BY a.attnum)
            FILTER (WHERE a.attgenerated = ''),
          pg_catalog.bool_or(a.attidentity = 'a')
        FROM pg_catalog.pg_class c
        LEFT JOIN pg_catalog.pg_attribute a
          ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE c.oid = pg_catalog.to_regclass(?) GROUP BY c.relkind""",
        rows -> {
          var array = rows.getArray(2);
          var columns = array == null ? List.<String>of() : List.of((String[]) array.getArray());
          return new Insertable(rows.getString(1), columns, rows.getBoolean(3));
        },
        name);
    if (read.kind().equals("p") || read.columns().isEmpty()) {
      return Optional.empty();
    }
    var columns = String.join(", ", read.columns());
    return Optional.of(new Data(
        "SELECT " + columns + " FROM ONLY " + name,
        "INSERT INTO " + name + " (" + columns + ")"
            + (read.always() ? " OVERRIDING SYSTEM VALUE" : "") + " VALUES",
        metadata -> {
          var values = new ArrayList<Value>();
          for (var i = 1; i <= metadata.getColumnCount(); i++) {
            values.add(value(metadata.getColumnTypeName(i)));
          }
          return values;
        }));
  }

  // NaN and the infinities of numeric and floating point types stay text, as the literal quotes.
  private static Value value(String type) {
    return switch (type) {
      case "int2", "int4", "int8", "oid" ->
        (rows, i) -> {
          var text = rows.getString(i);
          return text == null ? null : Long.valueOf(text);
        };
      case "numeric" ->
        (rows, i) -> {
          var text = rows.getString(i);
          return text == null || !finite(text) ? text : new BigDecimal(text);
        };
      case "float4", "float8" ->
        (rows, i) -> {
          var text = rows.getString(i);
          return text == null || !finite(text) ? text : Double.valueOf(text);
        };
      case "bool" ->
        (rows, i) -> {
          var truth = rows.getBoolean(i);
          return rows.wasNull() ? null : truth;
        };
      default -> (rows, i) -> rows.getString(i);
    };
  }

  private static boolean finite(String number) {
    return Character.isDigit(number.charAt(number.length() - 1));
  }

  // Each setting the session had is kept under a name of HooriDB's own, which the footer reads.
  @Override
  List<String> header(Dump.Options options) {
    return List.of(
        "SELECT %s"
            .formatted(
                String.join(", ", SETTINGS.stream().map(PostgreSqlDump::kept).toList())),
        "SET statement_timeout = 0",
        "SET lock_timeout = 0",
        "SET client_encoding = 'UTF8'",
        "SET standard_conforming_strings = on",
        "SELECT pg_catalog.set_config('search_path', '', false)",
        "SET check_function_bodies = false",
        "SET client_min_messages = warning",
        "SET IntervalStyle = 'postgres'",
        "SET DateStyle = 'ISO, YMD'");
  }

  @Override
  List<String> footer(Dump.Options options) {
    return List.of("SELECT %s"
        .formatted(
            String.join(", ", SETTINGS.stream().map(PostgreSqlDump::putBack).toList())));
  }

  private static String kept(String setting) {
    return "pg_catalog.set_config('hooridb.%s', pg_catalog.current_setting('%s'), false)"
        .formatted(setting.toLowerCase(Locale.ROOT), setting);
  }

  private static String putBack(String setting) {
    return "pg_catalog.set_config('%s', pg_catalog.current_setting('hooridb.%s'), false)"
        .formatted(setting, setting.toLowerCase(Locale.ROOT));
  }

  // Each table's foreign keys go before it is dropped, as its dropKeys say.
  @Override
  List<String> beforeDrops() {
    return List.of();
  }

  @Override
  List<String> afterDrops() {
    return List.of();
  }

  @Override
  List<String> create(Connection connection, Provider provider, List<Container> containers) {
    return containers.stream()
        .map(c -> "CREATE SCHEMA IF NOT EXISTS " + provider.quote(c.name()))
        .toList();
  }

  @Override
  String beginTransaction() {
    return "BEGIN";
  }
}
