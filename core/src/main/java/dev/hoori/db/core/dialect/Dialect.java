package dev.hoori.db.core.dialect;

import dev.hoori.db.core.data.RowsDialect;

/** Everything a provider does its own way, by concern. */
public interface Dialect extends Session {

  /** How its tables' rows and its results are read. */
  RowsDialect rows();
}
