package dev.hoori.db.core.connection;

/**
 * Something a database server may or may not be able to do. Which provider has which, from which
 * version, is {@link Provider#supports}.
 */
public enum Capability {
  /** A level of schemas between the database and its tables. */
  SCHEMAS,
  /**
   * A connection reads only the database it was opened on: every other database needs a
   * connection of its own.
   */
  CONNECTION_PER_DATABASE,
  /** DDL takes part in a transaction: a rollback undoes it, and it commits nothing. */
  TRANSACTIONAL_DDL,
  /**
   * An error says where in the statement it lies, as an offset of characters. PostgreSQL counts
   * from 1, in characters and not bytes.
   */
  ERROR_POSITION,
  /** The plan of a statement, without running it. */
  EXPLAIN,
  /** The plan of a statement as JSON, without running it. */
  EXPLAIN_JSON,
  /** The plan of a statement with the times measured while it runs. */
  EXPLAIN_ANALYZE,
  /**
   * An {@code INSERT} gives a column that the server always generates itself, an identity, a value
   * of its own, which {@code OVERRIDING SYSTEM VALUE} lets it.
   */
  IDENTITY_OVERRIDE
}
