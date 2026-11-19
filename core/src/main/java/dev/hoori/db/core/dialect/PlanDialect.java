package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Provider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.OptionalLong;

/** How a provider's planner is asked how many rows a statement gives, without running it. */
public interface PlanDialect {

  /** The statement that begins a read-only transaction. */
  String beginReadOnly();

  /** The statement that rolls the transaction back, and does nothing else. */
  String rollback();

  /**
   * A query that gives a row where planning may run code of the user's that reaches beyond the
   * read-only transaction.
   */
  String callsOut();

  /** The statement that shows the plan of the given one. */
  String explain(String sql);

  /** The rows the plan, the result of {@link #explain}, estimates the statement to give. */
  OptionalLong rows(ResultSet plan, String sql, Provider provider) throws SQLException;
}
