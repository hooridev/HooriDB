package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.dialect.FindDialect;
import dev.hoori.db.core.dialect.FindDialect.Holds;
import dev.hoori.db.core.dialect.FindDialect.Scope;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Searches a server of SQL, on a connection the caller does not use for anything else meanwhile:
 * the names and definitions of the objects of a database or schema, and the values of its tables'
 * columns, a table at a time, by the queries of the dialect's {@link FindDialect}. Every query goes
 * through {@link Journal} as a {@link Purpose#SEARCH}.
 *
 * <p>A database, where the provider has schemas, stands for every schema of it that is not the
 * server's own, and is read on a connection to it, as the catalog's queries are. Each call blocks,
 * without a time limit; an interrupt of the calling thread cancels it, as {@link Query} says.
 */
public final class Finder {

  private Finder() {}

  private interface Row<T> {
    T read(ResultSet rows) throws SQLException;
  }

  /**
   * The tables, views and columns of the container whose names match, the views whose queries,
   * and the routines and triggers whose names or definitions match: by kind, then as the server
   * orders them. A view found in its query is found a second time where its name matches too; a
   * routine or trigger once, by its name where that matches.
   */
  public static List<Found> objects(
      Connection connection, Provider provider, Container scope, Search search)
      throws QueryException {
    var find = provider.dialect().find();
    var found = new ArrayList<Found>();
    var listed = tables(connection, provider, scope);
    var tables = new HashMap<String, Table>();
    listed.forEach((key, each) -> tables.put(key, each.table()));
    for (var each : listed.values()) {
      var table = each.table();
      search
          .in(table.name())
          .ifPresent(at -> found.add(new Found(
              table.kind() == Table.Kind.VIEW ? Found.Kind.VIEW : Found.Kind.TABLE,
              table.container(),
              table,
              table.name(),
              false,
              null,
              at.start(),
              at.end())));
    }
    for (var column : columns(connection, provider, scope, tables)) {
      search
          .in(column.name())
          .ifPresent(at -> found.add(new Found(
              Found.Kind.COLUMN,
              column.table().container(),
              column.table(),
              column.name(),
              false,
              null,
              at.start(),
              at.end())));
    }
    query(connection, provider, scope, find.views(scope(provider, scope)), rows -> {
      var container = container(scope, rows.getString(1));
      var view = tables.getOrDefault(
          key(container.name(), rows.getString(2)),
          new Table(container, rows.getString(2), Table.Kind.VIEW));
      var definition = rows.getString(3);
      search
          .in(definition)
          .ifPresent(at -> found.add(new Found(
              Found.Kind.VIEW,
              container,
              view,
              view.name(),
              true,
              definition,
              at.start(),
              at.end())));
      return null;
    });
    query(connection, provider, scope, find.routines(scope(provider, scope)), rows -> {
      var kind = "p".equals(rows.getString(3)) ? Found.Kind.PROCEDURE : Found.Kind.FUNCTION;
      definition(
          found,
          search,
          kind,
          container(scope, rows.getString(1)),
          null,
          rows.getString(2),
          rows.getString(4));
      return null;
    });
    query(connection, provider, scope, find.triggers(scope(provider, scope)), rows -> {
      var container = container(scope, rows.getString(1));
      var table = tables.getOrDefault(
          key(container.name(), rows.getString(2)),
          new Table(container, rows.getString(2), Table.Kind.TABLE));
      definition(
          found,
          search,
          Found.Kind.TRIGGER,
          container,
          table,
          rows.getString(3),
          rows.getString(4));
      return null;
    });
    return List.copyOf(found);
  }

  // A routine or trigger: by its name where that matches, else by its definition.
  private static void definition(
      List<Found> found,
      Search search,
      Found.Kind kind,
      Container container,
      Table table,
      String name,
      String definition) {
    var byName = search.in(name);
    if (byName.isPresent()) {
      var at = byName.get();
      found.add(new Found(kind, container, table, name, false, definition, at.start(), at.end()));
      return;
    }
    search
        .in(definition)
        .ifPresent(at -> found.add(
            new Found(kind, container, table, name, true, definition, at.start(), at.end())));
  }

  /**
   * The tables and views of the container with the server's estimates of their size and their
   * columns, in the order the server gives them, for a search of their data.
   */
  public static List<Candidate> candidates(
      Connection connection, Provider provider, Container scope) throws QueryException {
    var listed = tables(connection, provider, scope);
    var tables = new HashMap<String, Table>();
    listed.forEach((key, each) -> tables.put(key, each.table()));
    var columns = new HashMap<Table, List<Column>>();
    for (var column : columns(connection, provider, scope, tables)) {
      columns.computeIfAbsent(column.table(), t -> new ArrayList<>()).add(column);
    }
    var candidates = new ArrayList<Candidate>();
    for (var each : listed.values()) {
      candidates.add(new Candidate(
          each.table(), each.rows(), each.bytes(), columns.getOrDefault(each.table(), List.of())));
    }
    return List.copyOf(candidates);
  }

  // A table as the server lists it, with its estimates.
  private record Listed(Table table, OptionalLong rows, OptionalLong bytes) {}

  // The tables of the container, by schema and name, in the server's order.
  private static Map<String, Listed> tables(
      Connection connection, Provider provider, Container scope) throws QueryException {
    var tables = new LinkedHashMap<String, Listed>();
    var sql = provider.dialect().find().tables(scope(provider, scope));
    query(connection, provider, scope, sql, rows -> {
      var container = container(scope, rows.getString(1));
      var table = new Table(container, rows.getString(2), kind(rows.getString(3)));
      tables.put(
          key(container.name(), table.name()),
          new Listed(table, optional(rows, 4), optional(rows, 5)));
      return null;
    });
    return tables;
  }

  private static OptionalLong optional(ResultSet rows, int column) throws SQLException {
    long value = rows.getLong(column);
    return rows.wasNull() || value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
  }

  // The columns of the tables of the container; a column of a table the tables did not list, as
  // one made meanwhile, is left out.
  private static List<Column> columns(
      Connection connection, Provider provider, Container scope, Map<String, Table> tables)
      throws QueryException {
    var columns = new ArrayList<Column>();
    var sql = provider.dialect().find().columns(scope(provider, scope));
    query(connection, provider, scope, sql, rows -> {
      var table = tables.get(key(container(scope, rows.getString(1)).name(), rows.getString(2)));
      if (table != null) {
        columns.add(new Column(table, rows.getString(3), rows.getString(4), true, 0));
      }
      return null;
    });
    return columns;
  }

  // A condition on a column, with the value it binds.
  private record Condition(Column column, String sql, Object value) {}

  /**
   * The columns of the table that a search for the value reads, as {@link #search} does: those of
   * text, those of numbers where the text is one, and the others as their text where {@code
   * others} says so.
   */
  public static List<Column> searched(
      Provider provider, Candidate table, Search search, boolean others) {
    return conditions(provider, table.columns(), search, others).stream()
        .map(Condition::column)
        .toList();
  }

  private static List<Condition> conditions(
      Provider provider, List<Column> columns, Search search, boolean others) {
    var find = provider.dialect().find();
    var number = search.number();
    boolean sensitive = search.caseSensitive();
    var conditions = new ArrayList<Condition>();
    for (var column : columns) {
      var name = provider.quote(column.name());
      var holds = find.holds(column.type());
      if (holds == Holds.NUMBER && number.isPresent()) {
        conditions.add(new Condition(column, find.equalNumber(name), number.get()));
        continue;
      }
      if (holds != Holds.TEXT && !others) {
        continue;
      }
      var text = holds == Holds.TEXT ? name : find.asText(name);
      conditions.add(
          switch (search.match()) {
            case CONTAINS ->
              new Condition(column, find.contains(text, sensitive), find.contained(search.text()));
            case EQUALS -> new Condition(column, find.equal(text, sensitive), search.text());
            case PATTERN -> new Condition(column, find.matches(text, sensitive), search.text());
          });
    }
    return conditions;
  }

  /**
   * Searches the table's columns for the value, as {@link #searched} names them, in one query
   * that reads at most one row more than {@link Search#LIMIT}: how many of its rows matched, and in
   * each column. A table without such a column is not read.
   */
  public static Hits search(
      Connection connection, Provider provider, Candidate candidate, Search search, boolean others)
      throws QueryException {
    var table = candidate.table();
    var conditions = conditions(provider, candidate.columns(), search, others);
    if (conditions.isEmpty()) {
      return Hits.none(table);
    }
    requireConnectedTo(connection, provider, table.container().database());
    var each = conditions.stream().map(Condition::sql).toList();
    var sql = "SELECT " + String.join(", ", each) + " FROM " + qualified(provider, table)
        + " WHERE " + String.join(" OR ", each) + " LIMIT " + (Search.LIMIT + 1);
    var counts = new int[conditions.size()];
    var read = Journal.sql(
        provider,
        Purpose.SEARCH,
        sql,
        rows -> Outcome.rows(rows),
        () -> Query.run(connection, provider, sql, statement -> {
          int at = 1;
          for (int round = 0; round < 2; round++) {
            for (var condition : conditions) {
              bind(statement, at++, condition.value());
            }
          }
          try (var rows = statement.executeQuery()) {
            int matched = 0;
            while (rows.next()) {
              matched++;
              if (matched > Search.LIMIT) {
                break;
              }
              for (int i = 0; i < counts.length; i++) {
                if (rows.getBoolean(i + 1)) {
                  counts[i]++;
                }
              }
            }
            return matched;
          }
        }));
    var hits = new ArrayList<Hits.Hit>();
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] > 0) {
        hits.add(new Hits.Hit(conditions.get(i).column().name(), counts[i]));
      }
    }
    return new Hits(
        table,
        conditions.stream().map(c -> c.column().name()).toList(),
        hits,
        Math.min(read, Search.LIMIT),
        read > Search.LIMIT);
  }

  /**
   * The query of the table's rows whose value in the column matches, as a statement to run, its
   * value written into it as the server reads it; empty where the search would not read the
   * column.
   */
  public static Optional<String> rows(
      Provider provider, Candidate candidate, String column, Search search, boolean others) {
    var columns =
        candidate.columns().stream().filter(c -> c.name().equals(column)).toList();
    var conditions = conditions(provider, columns, search, others);
    if (conditions.isEmpty()) {
      return Optional.empty();
    }
    var condition = conditions.getFirst();
    var at = condition.sql().lastIndexOf('?');
    var where = condition.sql().substring(0, at)
        + literal(provider, condition.value())
        + condition.sql().substring(at + 1);
    return Optional.of(
        "SELECT * FROM " + qualified(provider, candidate.table()) + " WHERE " + where);
  }

  // The value as a literal of the server's SQL.
  private static String literal(Provider provider, Object value) {
    if (value instanceof BigDecimal number) {
      return number.toPlainString();
    }
    var text = value.toString().replace("'", "''");
    if (provider.dialect().syntax().backslashEscapes()) {
      text = text.replace("\\", "\\\\");
    }
    return "'" + text + "'";
  }

  private static void bind(PreparedStatement statement, int at, Object value) throws SQLException {
    if (value instanceof BigDecimal number) {
      statement.setBigDecimal(at, number);
    } else {
      statement.setString(at, (String) value);
    }
  }

  private static String qualified(Provider provider, Table table) {
    return provider.quote(table.container().name()) + "." + provider.quote(table.name());
  }

  private static Table.Kind kind(String kind) {
    return switch (kind) {
      case "v", "m" -> Table.Kind.VIEW;
      case "f" -> Table.Kind.FOREIGN;
      default -> Table.Kind.TABLE;
    };
  }

  private static String key(String container, String table) {
    return container + "\0" + table;
  }

  private static boolean hasSchemas(Provider provider) {
    return provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN);
  }

  // A database stands for its schemas where the provider has them.
  private static Scope scope(Provider provider, Container container) {
    return container instanceof Database && hasSchemas(provider)
        ? Scope.USER_SCHEMAS
        : Scope.CONTAINER;
  }

  // The container of an object a query gave: the scope itself, or the schema it names in the
  // database that stands for its schemas, which is none of the server's own.
  private static Container container(Container scope, String schema) {
    return switch (scope) {
      case Schema s -> s;
      case Database d -> d.name().equals(schema) ? d : new Schema(d, schema, false);
    };
  }

  private static void query(
      Connection connection, Provider provider, Container scope, String sql, Row<?> row)
      throws QueryException {
    requireConnectedTo(connection, provider, scope.database());
    boolean parameter = scope(provider, scope) == Scope.CONTAINER;
    Journal.sql(
        provider,
        Purpose.SEARCH,
        sql,
        rows -> Outcome.rows(rows),
        () -> Query.run(connection, provider, sql, statement -> {
          if (parameter) {
            statement.setString(1, scope.name());
          }
          try (var rows = statement.executeQuery()) {
            long read = 0;
            while (rows.next()) {
              row.read(rows);
              read++;
            }
            return read;
          }
        }));
  }

  // A connection to one database would read its own database's objects in place of the ones
  // asked about.
  private static void requireConnectedTo(
      Connection connection, Provider provider, Database database) throws QueryException {
    if (!provider.supports(Capability.CONNECTION_PER_DATABASE, ServerVersion.UNKNOWN)) {
      return;
    }
    String current;
    try {
      current = connection.getCatalog();
    } catch (SQLException e) {
      throw Query.failure(connection, provider, e);
    }
    if (!database.name().equals(current)) {
      throw QueryException.of(
          QueryException.Failure.OTHER,
          "asked about " + database.name() + ", connected to " + current);
    }
  }
}
