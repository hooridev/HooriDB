package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * A target on a connection to a server of SQL, which writes through {@link Query}, so that an
 * interrupt cancels what runs on the server, and records what it sends with {@link Journal}. The
 * rows of a batch go in one {@code INSERT} of many rows, as far as its parameters allow, which on
 * every server is several times faster than a JDBC batch of single-row ones; a transaction's end
 * runs within {@link Execution#ENDING}, an interrupt or not. The connection serves nothing else
 * while a call runs.
 *
 * @param version the server's version, which tells what its {@code INSERT} can do
 * @param readOnly whether the connection is of a read-only profile, which may not write
 */
public record SqlTarget(
    Connection connection, Provider provider, ServerVersion version, boolean readOnly)
    implements Target {

  /**
   * The most parameters an {@code INSERT} has, which PostgreSQL's protocol counts in two bytes
   * that pgjdbc reads as signed.
   */
  static final int MOST_PARAMETERS = Short.MAX_VALUE;

  // The columns of a table that the server generates, which take no value.
  private static final String GENERATED = "SELECT column_name FROM information_schema.columns"
      + " WHERE table_schema = ? AND table_name = ? AND generation_expression <> ''";

  @Override
  public void prepare() throws QueryException {
    new SqlSource(connection, provider).prepare();
  }

  @Override
  public Shape shape(Table table) throws CatalogException, QueryException {
    var columns = Catalog.columns(connection, provider, table);
    var generated = Journal.sql(
        provider,
        Purpose.CATALOG,
        GENERATED,
        names -> Outcome.rows(names.size()),
        () -> Query.run(connection, provider, GENERATED, statement -> {
          statement.setString(1, table.container().name());
          statement.setString(2, table.name());
          var names = new HashSet<String>();
          try (var rows = statement.executeQuery()) {
            while (rows.next()) {
              names.add(rows.getString(1));
            }
          }
          return names;
        }));
    return new Shape(columns, generated);
  }

  @Override
  public List<ForeignKey> foreignKeys(Table table) throws CatalogException {
    return Catalog.foreignKeys(connection, provider, table);
  }

  @Override
  public boolean hasRows(Table table) throws QueryException {
    var sql = "SELECT 1 FROM " + Rows.qualified(provider, table) + " LIMIT 1";
    return Journal.sql(
        provider,
        Purpose.TRANSFER,
        sql,
        any -> Outcome.rows(any ? 1 : 0),
        () -> Query.query(connection, provider, sql, rows -> rows.next()));
  }

  @Override
  public void begin() throws QueryException {
    autoCommit(false);
  }

  @Override
  public long empty(Table table) throws QueryException {
    var sql = "DELETE FROM " + Rows.qualified(provider, table);
    return Journal.written(
        provider,
        Purpose.TRANSFER,
        sql,
        Outcome.Changed::new,
        () -> Query.run(connection, provider, sql, statement -> statement.executeLargeUpdate()));
  }

  @Override
  public long write(Table table, List<String> columns, List<List<Object>> rows)
      throws QueryException {
    for (var row : rows) {
      if (row.size() != columns.size()) {
        throw new IllegalArgumentException(
            "A row of " + row.size() + " values for " + columns.size() + " columns");
      }
    }
    var into = provider
        .dialect()
        .transfer()
        .insertInto(
            Rows.qualified(provider, table),
            columns.stream().map(provider::quote).toList(),
            provider.supports(Capability.IDENTITY_OVERRIDE, version));
    var each = Math.max(1, MOST_PARAMETERS / columns.size());
    long written = 0;
    for (var from = 0; from < rows.size(); from += each) {
      written +=
          insert(into, columns.size(), rows.subList(from, Math.min(rows.size(), from + each)));
    }
    return written;
  }

  // One INSERT of the rows, recorded by the text of its first row and how many rows it has, as the
  // whole would fill the log with parameters.
  private long insert(String into, int columns, List<List<Object>> rows) throws QueryException {
    var row = "(" + String.join(", ", Collections.nCopies(columns, "?")) + ")";
    var sql = into + " " + String.join(", ", Collections.nCopies(rows.size(), row));
    var shown = into + " " + row + (rows.size() > 1 ? " /* " + rows.size() + " rows */" : "");
    var dialect = provider.dialect().transfer();
    return Journal.written(
        provider,
        Purpose.TRANSFER,
        shown,
        Outcome.Changed::new,
        () -> Query.run(connection, provider, sql, statement -> {
          var index = 1;
          for (var values : rows) {
            for (var value : values) {
              dialect.bind(statement, index++, value);
            }
          }
          return statement.executeLargeUpdate();
        }));
  }

  @Override
  public void written(Table table, List<String> columns) throws QueryException {
    provider.dialect().transfer().written(connection, provider, table, columns);
  }

  @Override
  public void commit() throws QueryException {
    ending(() -> Journal.callWithheld(Purpose.TRANSACTION, Journal.COMMIT, () -> {
      connection.commit();
      return null;
    }));
  }

  @Override
  public void rollback() throws QueryException {
    ending(() -> {
      if (!connection.getAutoCommit()) {
        Journal.call(Purpose.TRANSACTION, Journal.ROLLBACK, () -> {
          connection.rollback();
          return null;
        });
      }
      return null;
    });
  }

  @Override
  public void end() throws QueryException {
    rollback();
    autoCommit(true);
  }

  private void autoCommit(boolean on) throws QueryException {
    ending(() -> {
      if (connection.getAutoCommit() != on) {
        Journal.call(Purpose.TRANSACTION, Journal.autoCommit(on), () -> {
          connection.setAutoCommit(on);
          return null;
        });
      }
      return null;
    });
  }

  // A step of the transaction, which must end, an interrupt or not.
  private void ending(Query.Step<Void> step) throws QueryException {
    Query.within(connection, provider, Execution.ENDING, step);
  }
}
