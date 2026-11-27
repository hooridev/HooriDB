package dev.hoori.db.core.sync;

import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.query.QueryException;
import java.util.List;

/**
 * The rows of one of the tables, in the order of the key, a portion at a time, as a {@link
 * dev.hoori.db.core.data.KeyedRead} gives them.
 */
@FunctionalInterface
public interface Cursor {

  /**
   * The next rows, with the table's fields; none at the end, after which it is not asked again.
   *
   * @throws QueryException where reading them failed
   * @throws InterruptedException where the thread was interrupted, which gives the read up
   */
  Result.Rows next() throws QueryException, InterruptedException;

  /**
   * How a portion of a statement's run is read: the next portion, of at most the given rows, where
   * it is read on another thread that this one waits for, also how that failed or was given up.
   */
  @FunctionalInterface
  interface Run {
    Portion next(int count) throws QueryException, InterruptedException;
  }

  /**
   * The rows of a keyed read run as a statement, such as an {@link
   * dev.hoori.db.core.execution.Execution} of it, read {@code count} at a time; where a portion
   * ends in a failure, that is thrown, a cancel as {@link QueryException.Failure#CANCELLED}.
   */
  static Cursor of(Run run, int count) {
    return new Cursor() {
      private boolean over;
      private List<Field> fields = List.of();

      @Override
      public Result.Rows next() throws QueryException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException();
        }
        if (over) {
          return new Result.Rows(fields, 0, List.of());
        }
        var portion = run.next(count);
        over = !portion.more();
        Result.Rows rows = null;
        for (var result : portion.results()) {
          switch (result) {
            case Failure f -> throw failure(f);
            case Result.Rows r -> rows = r;
            case Result.Count _ -> {
              // A read gives no count.
            }
          }
        }
        if (rows == null) {
          over = true;
          return new Result.Rows(fields, 0, List.of());
        }
        fields = rows.fields();
        return rows;
      }
    };
  }

  private static QueryException failure(Failure failure) {
    var kind = switch (failure.kind()) {
      case CONNECTION_LOST -> QueryException.Failure.CONNECTION_LOST;
      case CANCELLED -> QueryException.Failure.CANCELLED;
      case ACCESS_DENIED -> QueryException.Failure.ACCESS_DENIED;
      case INVALID, CONSTRAINT, TIMEOUT, OTHER -> QueryException.Failure.OTHER;
    };
    return QueryException.of(kind, failure.message());
  }
}
