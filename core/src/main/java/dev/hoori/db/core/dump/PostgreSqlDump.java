package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.definition.Definition;
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
 * of its children, and a materialized view that has rows refreshed from its tables once their
 * rows are in, as {@code pg_dump} does. The file reads its rows without row security, so that a
 * table whose policies would hide them from the user fails rather than leaving them out; an export
 * of the data alone turns off the tables' triggers for the rows, and what would refuse them, as
 * {@code pg_dump --disable-triggers} does, and puts them back as they were. The tables exported
 * are locked as the snapshot begins, so that no other session changes their definitions or empties
 * them until it ends, as the functions that write definitions read the catalog as it is then, not
 * as of the snapshot.
 */
public final class PostgreSqlDump extends DumpDialect {

  private static final String ALTER = "ALTER TABLE ";
  private static final String ALTER_ONLY = "ALTER TABLE ONLY ";
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
      "row_security",
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

  @Override
  public boolean quietsTriggers() {
    return true;
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
        .map(t -> "ONLY " + Rows.qualified(provider, t.container(), t.name()))
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

  private record Insertable(
      String kind, List<String> columns, boolean always, boolean forceRowSecurity) {}

  // The rows of the table itself, not those of its children; of each column that is not
  // generated, a value of a kind the literal writes as such, otherwise the server's text. A table
  // without such a column has its rows as many INSERTs of its defaults.
  @Override
  Optional<Data> data(Connection connection, Provider provider, Item table, boolean alone)
      throws QueryException {
    var name = Rows.qualified(provider, table.container(), table.name());
    var read = Sql.one(
        connection,
        provider,
        table.shown(),
        """
        SELECT c.relkind,
          pg_catalog.array_agg(pg_catalog.quote_ident(a.attname) ORDER BY a.attnum)
            FILTER (WHERE a.attgenerated = ''),
          pg_catalog.bool_or(a.attidentity = 'a'), c.relforcerowsecurity
        FROM pg_catalog.pg_class c
        LEFT JOIN pg_catalog.pg_attribute a
          ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        WHERE c.oid = pg_catalog.to_regclass(?) GROUP BY c.relkind, c.relforcerowsecurity""",
        rows -> {
          var array = rows.getArray(2);
          var columns = array == null ? List.<String>of() : List.of((String[]) array.getArray());
          return new Insertable(rows.getString(1), columns, rows.getBoolean(3), rows.getBoolean(4));
        },
        name);
    if (read.kind().equals("p")) {
      return Optional.empty();
    }
    var around = alone ? around(connection, provider, name, read) : Around.NOTHING;
    if (read.columns().isEmpty()) {
      return Optional.of(new Data(
          "SELECT FROM ONLY " + name,
          "INSERT INTO " + name + " DEFAULT VALUES",
          true,
          true,
          metadata -> List.of(),
          around));
    }
    var columns = String.join(", ", read.columns());
    return Optional.of(new Data(
        "SELECT " + columns + " FROM ONLY " + name,
        "INSERT INTO " + name + " (" + columns + ")"
            + (read.always() ? " OVERRIDING SYSTEM VALUE" : "") + " VALUES",
        false,
        true,
        metadata -> {
          var values = new ArrayList<Value>();
          for (var i = 1; i <= metadata.getColumnCount(); i++) {
            values.add(value(metadata.getColumnTypeName(i)));
          }
          return values;
        },
        around));
  }

  private record Enabled(String trigger, String mode) {}

  private record NotValid(String name, String definition) {}

  // What keeps a table that exists from refusing or changing the rows read into it, and puts it
  // back after them: its triggers, which go back to the mode each had; forced row security, which
  // would hold its owner to the policies; constraints that are not valid, which old rows need not
  // pass, dropped and added again as they were.
  private static Around around(
      Connection connection, Provider provider, String name, Insertable read)
      throws QueryException {
    var before = new ArrayList<String>();
    var after = new ArrayList<String>();
    var triggers = Sql.rows(
        connection, provider, """
        SELECT t.tgname, t.tgenabled FROM pg_catalog.pg_trigger t
        WHERE t.tgrelid = pg_catalog.to_regclass(?) AND NOT t.tgisinternal
        ORDER BY t.tgname""", rows -> new Enabled(rows.getString(1), rows.getString(2)), name);
    if (!triggers.isEmpty()) {
      before.add(ALTER_ONLY + name + " DISABLE TRIGGER USER");
      for (var trigger : triggers) {
        var mode = switch (trigger.mode()) {
          case "D" -> "";
          case "R" -> " ENABLE REPLICA TRIGGER ";
          case "A" -> " ENABLE ALWAYS TRIGGER ";
          default -> " ENABLE TRIGGER ";
        };
        if (!mode.isEmpty()) {
          after.add(ALTER_ONLY + name + mode + provider.quote(trigger.trigger()));
        }
      }
    }
    if (read.forceRowSecurity()) {
      before.add(ALTER_ONLY + name + " NO FORCE ROW LEVEL SECURITY");
      after.add(ALTER_ONLY + name + " FORCE ROW LEVEL SECURITY");
    }
    var checks = Sql.rows(
        connection,
        provider,
        """
        SELECT co.conname, pg_catalog.pg_get_constraintdef(co.oid)
        FROM pg_catalog.pg_constraint co
        WHERE co.conrelid = pg_catalog.to_regclass(?) AND NOT co.convalidated
        AND co.contype IN ('c', 'f') AND co.conislocal
        ORDER BY co.conname""",
        rows -> new NotValid(rows.getString(1), rows.getString(2)),
        name);
    for (var check : checks) {
      var constraint = provider.quote(check.name());
      before.add(ALTER + name + " DROP CONSTRAINT IF EXISTS " + constraint);
      after.add(ALTER + name + " ADD CONSTRAINT " + constraint + " " + check.definition());
    }
    return new Around(before, after);
  }

  // Their keys go while the rows go in, and come back after them, checking them all.
  @Override
  Around circle(List<Definition> tables) {
    var before = new ArrayList<String>();
    var after = new ArrayList<String>();
    for (var table : tables) {
      before.addAll(table.dropKeys());
      after.addAll(table.keys());
    }
    return new Around(before, after);
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
        "SET row_security = off",
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
