package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** How a provider's servers and driver say why a statement failed. */
public interface ErrorDialect {

  /** What a failure means, as far as its SQLSTATE or error code tells it. */
  enum Condition {
    /** The server refused the user what the statement asks for. */
    ACCESS_DENIED,
    /** The server refused the user to run a routine, beyond {@link #ACCESS_DENIED}. */
    ROUTINE_DENIED,
    /** A table, column, schema or database the statement names does not exist. */
    NOT_FOUND,
    /** A column the statement names does not exist. */
    COLUMN_GONE,
    /** The statement was cancelled, by the caller, another session or a time limit. */
    CANCELLED,
    /** A time limit of the server that ends a statement as a cancel would. */
    TIME_LIMIT,
    /** A time limit of the server ended the statement, or a wait for a lock. */
    TIMEOUT,
    /** A row broke a constraint, beyond the SQLSTATEs of class 23. */
    CONSTRAINT;

    /**
     * Whether a failure with the SQLSTATE and error code, on a server of the provider, means this:
     * by its SQLSTATE as any provider's say so, as the states are one namespace that any server
     * may send and the drivers make up some of their own; by its error code as the provider's own
     * say so.
     */
    public boolean in(Provider provider, String state, int code) {
      return States.ANY.get(this).contains(state)
          || provider.dialect().errors().codes(this).contains(code);
    }

    // Every provider's states, gathered at the first use rather than as the dialects are made.
    private static final class States {
      static final Map<Condition, Set<String>> ANY = any();

      private static Map<Condition, Set<String>> any() {
        var any = new EnumMap<Condition, Set<String>>(Condition.class);
        for (var condition : values()) {
          var states = new HashSet<String>();
          for (var provider : Provider.SQL) {
            states.addAll(provider.dialect().errors().states(condition));
          }
          any.put(condition, Set.copyOf(states));
        }
        return any;
      }
    }
  }

  /** The SQLSTATEs by which the provider's servers and driver say so. */
  Set<String> states(Condition condition);

  /** The error codes by which the provider's servers say so. */
  Set<Integer> codes(Condition condition);

  /**
   * The failure of the kind, with what the server says of it beyond its SQLSTATE and code, such as
   * where in the statement it is.
   *
   * @param message the driver's message
   */
  Failure failure(Failure.Kind kind, SQLException e, String message, String state, int code);
}
