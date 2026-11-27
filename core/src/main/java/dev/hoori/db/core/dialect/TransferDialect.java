package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * How a provider's tables' rows are copied into another of its servers: how each column's values
 * are read so that the other server takes them back for the same values, what tells two sessions
 * on the same server and database apart, and what a table needs once its rows are written.
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
   * that the server takes it as a value of the column it goes into.
   */
  void bind(PreparedStatement statement, int index, Object value) throws SQLException;

  /**
   * The statements that put each session of a copy, the source's and the target's, alike where
   * the text of a value depends on the session, as the zone a moment is written in, let the target
   * take each value as it is given, and keep the source's rows coming while the target writes.
   */
  List<String> prepare();

  /**
   * The query of the names of a table's columns that the server computes itself, which take no
   * value, with the name of the table's schema, or database where it has none, and the table's
   * name as its parameters.
   */
  String generated();

  /**
   * The query whose one row names the server and the database the session is on, so that two
   * sessions on the same read the same values.
   */
  String identity();

  /**
   * The query whose one value is the most bytes one statement may carry to the server; empty
   * where it has no such limit.
   */
  Optional<String> statementLimit();

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
   * its identity and serial columns, moved past the values the table holds where they are behind,
   * so that a new row's does not take one of them; never back.
   */
  void written(Connection connection, Provider provider, Table table, List<String> columns)
      throws QueryException;
}
