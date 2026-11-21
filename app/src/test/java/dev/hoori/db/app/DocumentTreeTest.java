package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.document.Document;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTreeTest {

  @Test
  void wholeDocumentsKeepOrdinaryPagesAndRejectOversizedOnes() {
    var view = new DocumentTree();
    assertTrue(view.show("ordinary", List.of(Document.of("name", "Ada")), 0, 1));
    assertFalse(
        view.show(
            "large",
            List.of(Document.of("text", "x".repeat(DocumentTree.MAX_TEXT))),
            0,
            1));
  }
}
