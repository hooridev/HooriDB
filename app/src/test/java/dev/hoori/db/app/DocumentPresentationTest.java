package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.document.Document;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit and script tests for {@link DocumentPresentation} and its Grid, JSON, and Tree strategies. */
class DocumentPresentationTest {

  static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

  @TempDir
  Path dir;

  @Test
  void modeSelectsMatchingStrategyAndNonDocumentsAlwaysUseGrid() {
    var presentation = new DocumentPresentation();
    assertEquals(DocumentPresentation.Mode.GRID, presentation.mode());
    assertTrue(presentation.isGrid());
    assertInstanceOf(DocumentPresentation.Grid.class, presentation.strategy());

    presentation.mode(DocumentPresentation.Mode.JSON);
    assertFalse(presentation.isGrid());
    assertInstanceOf(DocumentPresentation.Json.class, presentation.strategy());

    presentation.mode(DocumentPresentation.Mode.TREE);
    assertFalse(presentation.isGrid());
    assertInstanceOf(DocumentPresentation.Tree.class, presentation.strategy());

    presentation.page(-5);
    assertEquals(0, presentation.page());
    presentation.page(3);
    assertEquals(3, presentation.page());
  }

  @Test
  void rangeFormatsRowsAndDocuments() {
    assertEquals("Rows 1–200", DocumentPresentation.range(1, 200, false));
    assertEquals("Documents 1,001–1,200", DocumentPresentation.range(1_001, 1_200, true));
  }

  @Test
  void gridStrategyBuildsGridBeforeEvaluatingRangeAndPaging() throws Exception {
    var presentation = new DocumentPresentation();
    presentation.mode(DocumentPresentation.Mode.JSON);
    var valuePanel = new ValuePanel();
    var order = new ArrayList<String>();
    var state = new State(dir, null, null, null, null, null, HooriDb.version());
    var app = new App<>(state, s -> {
          // ofDocuments = false forces Grid even though mode is JSON.
          var view = presentation.view(
              s.app,
              ZONE,
              false,
              () -> new DocumentPresentation.GridView(
                  () -> {
                    order.add("grid");
                    return Label.label("cells").key("grid");
                  },
                  valuePanel,
                  null,
                  null,
                  false,
                  () -> {
                    order.add("range");
                    return DocumentPresentation.range(1, 10, false);
                  },
                  () -> {
                    order.add("paging");
                    return new DocumentPresentation.Paging(
                        false, true, () -> {}, () -> {}, () -> {}, () -> {});
                  }),
              () -> {
                throw new AssertionError("documentPage must not be called in Grid mode");
              });
          return col()
              .grow(1)
              .child(view.body())
              .child(Card.footer(Label.label("status"), view.footer().toArray(El[]::new)));
        })
        .provide(Theme.CURRENT, s -> s.theme);
    state.app = app;

    Script.run("size 800 500\nframe\nsnap grid\n", app, dir);
    assertEquals(List.of("grid", "range", "paging"), order);
    var tree = Files.readString(dir.resolve("grid.tree.txt"));
    assertTrue(line(tree, "#rows-in-view").contains("\"Rows 1–10\""), tree);
    assertTrue(line(tree, "role=button label=\"First page\"").contains("disabled"), tree);
    assertFalse(line(tree, "role=button label=\"Next page\"").contains("disabled"), tree);
    assertTrue(tree.contains("#value-toggle"), tree);
  }

  @Test
  void switcherCyclesThroughGridJsonAndTreeWithPagingAndStates() throws Exception {
    var presentation = new DocumentPresentation();
    var valuePanel = new ValuePanel();
    var docs = new ArrayList<>(
        List.of(Document.of("_id", 1, "name", "Ada"), Document.of("_id", 2, "name", "Grace")));
    var loading = new AtomicBoolean(false);
    var turns = new AtomicInteger();

    var state = new State(dir, null, null, null, null, null, HooriDb.version());
    var app = new App<>(state, s -> {
          var view = presentation.view(
              s.app,
              ZONE,
              true,
              () -> new DocumentPresentation.GridView(
                  () -> Label.label("grid-body").key("grid"),
                  valuePanel,
                  null,
                  null,
                  false,
                  null,
                  null),
              () -> new DocumentPresentation.DocumentPage(
                  "p" + presentation.page(),
                  loading.get() ? null : docs,
                  0,
                  loading.get() ? 0 : docs.size(),
                  0,
                  loading.get() ? 0 : docs.size(),
                  loading.get(),
                  "Documents of test",
                  new DocumentPresentation.Paging(
                      presentation.page() > 0,
                      true,
                      () -> presentation.page(0),
                      () -> presentation.page(presentation.page() - 1),
                      () -> {
                        turns.incrementAndGet();
                        presentation.page(presentation.page() + 1);
                      },
                      null)));
          var footer = new ArrayList<El>();
          footer.add(presentation.switcher("shown", presentation::mode));
          footer.addAll(view.footer());
          return col()
              .grow(1)
              .child(view.body())
              .child(Card.footer(Label.label("status"), footer.toArray(El[]::new)));
        })
        .provide(Theme.CURRENT, s -> s.theme);
    state.app = app;

    Script.run("""
        size 800 500
        frame
        snap grid
        press role=radio label="JSON"
        frame
        advance 1
        frame
        snap json
        press role=radio label="Tree"
        frame
        advance 1
        frame
        snap tree
        press role=button label="Next page"
        frame
        """, app, dir);

    var gridTree = Files.readString(dir.resolve("grid.tree.txt"));
    assertTrue(gridTree.contains("#grid"), gridTree);
    assertTrue(gridTree.contains("#value-toggle"), gridTree);

    var jsonTree = Files.readString(dir.resolve("json.tree.txt"));
    assertTrue(line(jsonTree, "#json").contains("readonly"), jsonTree);
    assertTrue(line(jsonTree, "#json").contains("\\\"name\\\": \\\"Ada\\\""), jsonTree);
    assertTrue(line(jsonTree, "#rows-in-view").contains("\"Documents 1–2\""), jsonTree);
    assertTrue(line(jsonTree, "role=button label=\"Last page\"").contains("disabled"), jsonTree);

    var treeView = Files.readString(dir.resolve("tree.tree.txt"));
    assertTrue(treeView.contains("#documents"), treeView);
    assertTrue(treeView.contains("role=treeitem label=\"1\""), treeView);
    assertTrue(treeView.contains("role=treeitem label=\"2\""), treeView);
    assertEquals(1, turns.get());
    assertEquals(1, presentation.page());

    // Loading state
    app.post(() -> loading.set(true));
    Script.run("await\nframe\nadvance 1\nframe\nsnap loading\n", app, dir);
    var loadingTree = Files.readString(dir.resolve("loading.tree.txt"));
    assertTrue(loadingTree.contains("Reading documents…"), loadingTree);

    // Empty state
    app.post(() -> {
      loading.set(false);
      docs.clear();
    });
    Script.run("await\nframe\nadvance 1\nframe\nsnap empty\n", app, dir);
    var emptyTree = Files.readString(dir.resolve("empty.tree.txt"));
    assertTrue(emptyTree.contains("#no-documents"), emptyTree);
    assertTrue(line(emptyTree, "#rows-in-view").contains("\"No documents\""), emptyTree);
  }
}
