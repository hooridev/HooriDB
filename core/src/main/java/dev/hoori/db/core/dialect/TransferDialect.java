package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * How a provider's tables' rows are copied into another of its servers: how each column's values
 * are read so that the other server takes them back for the same values, how a page of rows comes
 * after another, and what a table needs once its rows are written.
 */
public interface TransferDialect {

  /** How the values of a column travel, as the driver reads and binds them. */
  enum Carry {
    /** As the server's text, a {@code String}. */
    TEXT,
    /** As a number, a {@code BigDecimal}, read from the server's text. */
    NUMBER,
    /** As their bytes, a {@code byte[]}. */
    BYTES
  }

  /** How the column's values travel. */
  Carry carry(Column column);

  /** The expression that reads the column, named as given through an alias, as it travels. */
  String read(Column column, String name);

  /**
   * Binds a value as it was read, a {@code String}, {@code BigDecimal}, {@code byte[]} or null, so
   * that the server takes it as a value of the column it goes into or is compared with.
   */
  void bind(PreparedStatement statement, int index, Object value) throws SQLException;

  /**
   * The statements that put each session of a copy, the source's and the target's, alike where
   * the text of a value depends on the session, as the zone a moment is written in.
   */
  List<String> prepare();

  /**
   * The condition that a row comes after a row of the given values of the primary key, whose
   * columns are given quoted in their order, in the order of those columns.
   *
   * @return the condition with a parameter {@code ?} for each value, and for each parameter the
   *     index of its value among the key's
   */
  After after(List<String> key);

  /** A condition with parameters, and the index of each one's value. */
  record After(String sql, List<Integer> values) {
    public After {
      values = List.copyOf(values);
    }
  }

  /**
   * The expressions that order the rows of a table without a primary key, read through the alias
   * with the given columns, so that a position names the same row while the table does not change.
   */
  List<String> order(String alias, List<String> columns);

  /**
   * The {@code INSERT} into the qualified table's quoted columns up to its {@code VALUES}, which
   * rows of parameters follow.
   *
   * @param overriding whether identity columns that the server always generates take the values
   *     given, as {@link dev.hoori.db.core.connection.Capability#IDENTITY_OVERRIDE} says
   */
  String insertInto(String table, List<String> columns, boolean overriding);

  /**
   * What the table needs once rows were copied into the columns: on PostgreSQL its sequences, of
   * its identity and serial columns, set past the values copied, so that a new row's does not take
   * one of them.
   */
  void written(Connection connection, Provider provider, Table table, List<String> columns)
      throws QueryException;
}
