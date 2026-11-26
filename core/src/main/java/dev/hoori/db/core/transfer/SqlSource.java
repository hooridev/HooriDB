package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.dialect.TransferDialect.Carry;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * A source on a connection to a server of SQL, which reads through {@link Query}, so that an
 * interrupt cancels what runs on the server, and records what it sends with {@link Journal}. The
 * connection serves nothing else while a call runs, nor while a reading is open.
 */
public record SqlSource(Connection connection, Provider provider) implements Source {

  // The alias of the table in a read.
  private static final String ALIAS = "r";

  // How many rows the first fetch of a reading asks for, before the size of its rows is known.
  private static final int FIRST = 16;

  @Override
  public void prepare() throws QueryException {
    for (var sql : provider.dialect().transfer().prepare()) {
      Journal.sql(
          provider,
          Purpose.SESSION,
          sql,
          done -> Outcome.DONE,
          () -> Query.run(connection, provider, sql, statement -> statement.execute()));
    }
  }

  @Override
  public String identity() throws QueryException {
    return identity(connection, provider);
  }

  // The values of the dialect's identity, a line each.
  static String identity(Connection connection, Provider provider) throws QueryException {
    var sql = provider.dialect().transfer().identity();
    return Journal.sql(
        provider,
        Purpose.TRANSFER,
        sql,
        named -> Outcome.rows(1),
        () -> Query.query(connection, provider, sql, rows -> {
          var named = new StringJoiner("\n");
          if (rows.next()) {
            for (var i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
              named.add(String.valueOf(rows.getString(i)));
            }
          }
          return named.toString();
        }));
  }

  @Override
  public List<Column> columns(Table table) throws CatalogException {
    return Catalog.columns(connection, provider, table);
  }

  @Override
  public long count(Table table) throws QueryException {
    var sql = "SELECT count(*) FROM " + Rows.qualified(provider, table);
    return Journal.sql(
        provider,
        Purpose.TRANSFER,
        sql,
        n -> Outcome.rows(1),
        () -> Query.query(connection, provider, sql, rows -> {
          rows.next();
          return rows.getLong(1);
        }));
  }

  @Override
  public Reading read(Selection selection) {
    var dialect = provider.dialect().transfer();
    var sql = new StringBuilder("SELECT ")
        .append(String.join(
            ", ",
            selection.columns().stream()
                .map(column -> dialect.read(column, ALIAS + "." + provider.quote(column.name())))
                .toList()))
        .append(" FROM ")
        .append(Rows.qualified(provider, selection.table()))
        .append(' ')
        .append(ALIAS);
    if (!selection.key().isEmpty()) {
      sql.append(" ORDER BY ")
          .append(String.join(
              ", ",
              selection.key().stream()
                  .map(column -> ALIAS + "." + provider.quote(column.name()))
                  .toList()));
    }
    return new SqlReading(
        connection,
        provider,
        sql.toString(),
        selection.columns().stream().map(dialect::carry).toList());
  }

  /**
   * The rows of one statement, read as they come: the driver fetches them in portions, of a size
   * that fits the budget once the first rows told how large they are. Where the server sends rows
   * in portions only within a transaction, the reading has one of its own. The connection's calls
   * run one at a time; the cancel of the call that runs comes from the waiting thread.
   */
  private static final class SqlReading implements Reading {

    private final Connection connection;
    private final Provider provider;
    private final String sql;
    private final List<Carry> carries;
    private volatile Statement statement;
    private ResultSet rows;
    private List<Object> ahead; // the row read past the budget of the last batch, or null
    private boolean own; // whether it turned auto-commit off for a transaction of its own
    private boolean over; // whether its rows were all read, or it was closed

    SqlReading(Connection connection, Provider provider, String sql, List<Carry> carries) {
      this.connection = connection;
      this.provider = provider;
      this.sql = sql;
      this.carries = carries;
    }

    @Override
    public Batch next(int count, long budget) throws QueryException {
      if (count < 1) {
        throw new IllegalArgumentException("A batch of " + count + " rows");
      }
      if (over) {
        return Batch.LAST;
      }
      if (statement != null) {
        return read(count, budget);
      }
      return Journal.sql(
          provider,
          Purpose.TRANSFER,
          sql,
          batch -> new Outcome.Rows(batch.rows().size(), batch.more()),
          () -> read(count, budget));
    }

    private Batch read(int count, long budget) throws QueryException {
      var session = provider.dialect().session();
      return Query.run(
          connection,
          provider,
          () -> {
            var running = statement;
            if (running != null) {
              Journal.call(Purpose.TRANSFER, Journal.CANCEL, () -> {
                session.cancel(connection, running);
                return null;
              });
            }
          },
          () -> {
            if (statement == null) {
              execute();
            }
            return batch(count, budget);
          },
          batch -> false);
    }

    private void execute() throws SQLException {
      var session = provider.dialect().session();
      if (!session.queriesInOwnTransaction().isEmpty() && connection.getAutoCommit()) {
        Journal.call(Purpose.TRANSACTION, Journal.autoCommit(false), () -> {
          connection.setAutoCommit(false);
          return null;
        });
        own = true;
      }
      session.keepWriteTimeout(connection);
      var made = connection.createStatement();
      statement = made;
      made.setEscapeProcessing(false);
      made.setFetchSize(session.fetchSize(FIRST));
      rows = made.executeQuery(sql);
    }

    private Batch batch(int count, long budget) throws SQLException {
      var read = new ArrayList<List<Object>>();
      long size = 0;
      if (ahead != null) {
        read.add(ahead);
        size = Batch.size(ahead);
        ahead = null;
      }
      while (read.size() < count) {
        if (!rows.next()) {
          finish();
          return new Batch(read, false);
        }
        var row = row();
        var bytes = Batch.size(row);
        if (!read.isEmpty() && size + bytes > budget) {
          ahead = row;
          break;
        }
        read.add(row);
        size += bytes;
      }
      // As many rows a fetch as the budget holds of rows of this size.
      var fit = size <= 0 ? count : budget * read.size() / size;
      try {
        rows.setFetchSize(provider.dialect().session().fetchSize(Math.clamp(fit, 1, count)));
      } catch (SQLException _) {
        // The driver fetches as it began to.
      }
      return new Batch(read, true);
    }

    private List<Object> row() throws SQLException {
      var values = new Object[carries.size()];
      for (var i = 0; i < values.length; i++) {
        values[i] = switch (carries.get(i)) {
          case TEXT -> rows.getString(i + 1);
          case NUMBER -> {
            var text = rows.getString(i + 1);
            yield text == null ? null : new BigDecimal(text);
          }
          case BYTES -> rows.getBytes(i + 1);
        };
      }
      return Collections.unmodifiableList(Arrays.asList(values));
    }

    // All rows were read: the statement ends, and its transaction, which only read.
    private void finish() throws SQLException {
      over = true;
      closeStatement();
      if (own) {
        own = false;
        Journal.call(Purpose.TRANSACTION, Journal.COMMIT, () -> {
          connection.commit();
          return null;
        });
        autoCommitOn();
      }
    }

    @Override
    public void close() throws QueryException {
      if (over && !own) {
        return;
      }
      Query.within(connection, provider, Execution.ENDING, () -> {
        if (!over) {
          over = true;
          giveUp();
        }
        if (own) {
          own = false;
          if (!connection.getAutoCommit()) {
            Journal.call(Purpose.TRANSACTION, Journal.ROLLBACK, () -> {
              connection.rollback();
              return null;
            });
          }
          autoCommitOn();
        }
        return null;
      });
    }

    // Stops the rows on their way where they hold the connection, and reads what still comes.
    private void giveUp() throws SQLException {
      var session = provider.dialect().session();
      if (statement != null && rows != null && session.pendingRowsHoldConnection()) {
        try {
          Journal.call(Purpose.TRANSFER, Journal.CANCEL, () -> {
            session.cancel(connection, statement);
            return null;
          });
          while (rows.next()) {
            // Read and dropped.
          }
        } catch (SQLException _) {
          // The server ended the statement, its cancel among the reasons: nothing more comes.
        }
      }
      closeStatement();
    }

    private void closeStatement() throws SQLException {
      var made = statement;
      statement = null;
      try {
        if (rows != null) {
          rows.close();
        }
      } finally {
        rows = null;
        if (made != null) {
          made.close();
        }
      }
    }

    private void autoCommitOn() throws SQLException {
      if (!connection.getAutoCommit()) {
        Journal.call(Purpose.TRANSACTION, Journal.autoCommit(true), () -> {
          connection.setAutoCommit(true);
          return null;
        });
      }
    }
  }
}
