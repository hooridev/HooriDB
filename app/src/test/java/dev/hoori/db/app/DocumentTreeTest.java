package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.document.Document;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The work and retained input of whole-document JSON and tree views are bounded. */
class DocumentTreeTest {

  @Test
  void ordinaryDocumentsRemainShowable() {
    var documents = List.of(Document.of("_id", 1, "name", "Ada", "tags", List.of("math")));

    assertTrue(DocumentTree.showable(documents, 0, documents.size()));
  }

  @Test
  void largeStringsAreNotShownWhole() {
    var documents = List.of(Document.of("value", "x".repeat((int) DocumentTree.VIEW_BUDGET)));

    assertFalse(DocumentTree.showable(documents, 0, documents.size()));
  }

  @Test
  void deeplyNestedValuesAreNotShownWhole() {
    Object value = "end";
    for (int i = 0; i <= DocumentTree.VIEW_DEPTH; i++) {
      value = List.of(value);
    }
    var documents = List.of(Document.of("value", value));

    assertFalse(DocumentTree.showable(documents, 0, documents.size()));
  }
}
