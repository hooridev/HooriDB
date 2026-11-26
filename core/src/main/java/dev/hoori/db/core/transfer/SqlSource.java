package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.dialect.TransferDialect;
import dev.hoori.db.core.dialect.TransferDialect.Carry;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * A source on a connection to a server of SQL, which reads through {@link Query}, so that an
 * interrupt cancels what runs on the server, and records what it sends with {@link Journal}. The
 * connection serves nothing else while a call runs.
 */
public record SqlSource(Connection connection, Provider provider) implements Source {

  // The alias of the table in a read.
  private static final String ALIAS = "r";

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
  public Batch read(Selection selection, Position from, int count) throws QueryException {
    if (from instanceof Position.End) {
      return new Batch(List.of(), Position.END);
    }
    if (count < 1) {
      throw new IllegalArgumentException("A batch of " + count + " rows");
    }
    var dialect = provider.dialect().transfer();
    var read = Stream.concat(selection.columns().stream(), selection.key().stream())
        .map(column -> dialect.read(column, name(column)))
        .toList();
    var key = selection.key().stream().map(this::name).toList();
    var sql = new StringBuilder("SELECT ")
        .append(String.join(", ", read))
        .append(" FROM ")
        .append(Rows.qualified(provider, selection.table()))
        .append(' ')
        .append(ALIAS);
    var parameters = new ArrayList<Object>();
    long offset = 0;
    switch (from) {
      case Position.After(var values) -> {
        var after = dialect.after(key);
        sql.append(" WHERE ").append(after.sql());
        after.values().forEach(i -> parameters.add(values.get(i)));
      }
      case Position.At(var at) -> offset = at;
      case Position.Start _, Position.End _ -> {}
    }
    var order = key.isEmpty()
        ? dialect.order(
            ALIAS,
            selection.columns().stream().map(c -> provider.quote(c.name())).toList())
        : key;
    sql.append(" ORDER BY ").append(String.join(", ", order)).append(" LIMIT ").append(count);
    if (offset > 0) {
      sql.append(" OFFSET ").append(offset);
    }
    var text = sql.toString();
    var carries = Stream.concat(selection.columns().stream(), selection.key().stream())
        .map(dialect::carry)
        .toList();
    var at = offset;
    return Journal.sql(
        provider,
        Purpose.TRANSFER,
        text,
        batch -> new Outcome.Rows(batch.rows().size(), !(batch.next() instanceof Position.End)),
        () -> Query.run(connection, provider, text, statement -> {
          for (var i = 0; i < parameters.size(); i++) {
            dialect.bind(statement, i + 1, parameters.get(i));
          }
          try (var rows = statement.executeQuery()) {
            return batch(rows, carries, selection, count, at);
          }
        }));
  }

  private String name(Column column) {
    return ALIAS + "." + provider.quote(column.name());
  }

  // The rows read, the selection's columns of each, and where the next batch begins: after the
  // last row's key, or after its position, where the batch is full.
  private static Batch batch(
      ResultSet rows, List<Carry> carries, Selection selection, int count, long offset)
      throws SQLException {
    var read = new ArrayList<List<Object>>();
    List<Object> last = List.of();
    var columns = selection.columns().size();
    while (rows.next()) {
      var values = new Object[carries.size()];
      for (var i = 0; i < values.length; i++) {
        values[i] = value(rows, i + 1, carries.get(i));
      }
      read.add(Collections.unmodifiableList(Arrays.asList(values).subList(0, columns)));
      last = Arrays.asList(values).subList(columns, values.length);
    }
    Position next;
    if (read.size() < count) {
      next = Position.END;
    } else if (selection.key().isEmpty()) {
      next = new Position.At(offset + read.size());
    } else {
      next = new Position.After(last);
    }
    return new Batch(read, next);
  }

  private static Object value(ResultSet rows, int index, TransferDialect.Carry carry)
      throws SQLException {
    return switch (carry) {
      case TEXT -> rows.getString(index);
      case NUMBER -> {
        var text = rows.getString(index);
        yield text == null ? null : new BigDecimal(text);
      }
      case BYTES -> rows.getBytes(index);
    };
  }
}
