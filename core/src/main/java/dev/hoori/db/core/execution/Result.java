package dev.hoori.db.core.execution;

import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.RowReader;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Values;
import java.util.List;

/** One of the things a statement yielded, in the order the server gave them. */
public sealed interface Result permits Result.Rows, Result.Count, Failure {

  /**
   * Rows of a result, as many as were asked for, from the position {@code from} on, counting from
   * 0; a portion of rows from a position above 0 goes on with the rows of the previous portion's
   * last result. The values are typed as {@link RowReader} says, or of documents as {@link
   * Values#row} says; the lists cannot be changed. Of a server of documents, the documents too,
   * whole, a row each; of SQL, none.
   */
  record Rows(List<Field> fields, long from, List<List<Object>> rows, List<Document> documents)
      implements Result {

    public Rows {
      documents = List.copyOf(documents);
    }

    /** Rows of a statement of SQL, which has no documents. */
    public Rows(List<Field> fields, long from, List<List<Object>> rows) {
      this(fields, from, rows, List.of());
    }
  }

  /**
   * How many rows the statement inserted, changed or deleted; 0 for one that changes no rows, such
   * as one of DDL.
   */
  record Count(long count) implements Result {}
}
