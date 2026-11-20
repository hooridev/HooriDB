package dev.hoori.db.core.data;

import dev.hoori.db.core.document.Document;
import java.util.List;

/**
 * Rows of a table or view, with their columns. Each row holds a value per field, in the fields'
 * order, of the Java type that the field's {@link Kind} says; the lists cannot be changed. Of a
 * collection of documents, the documents too, whole, a row each; of a table, none.
 */
public record Page(List<Field> fields, List<List<Object>> rows, List<Document> documents) {

  public Page {
    documents = List.copyOf(documents);
  }

  /** Rows of a table, which has no documents. */
  public Page(List<Field> fields, List<List<Object>> rows) {
    this(fields, rows, List.of());
  }
}
