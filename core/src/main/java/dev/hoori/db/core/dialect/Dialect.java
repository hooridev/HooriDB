package dev.hoori.db.core.dialect;

import dev.hoori.db.core.data.RowsDialect;

/** Everything a provider does its own way, by concern. */
public interface Dialect {

  /** What its driver does on an open connection beyond JDBC. */
  SessionDialect session();

  /** How its tables' rows and its results are read. */
  RowsDialect rows();
}
