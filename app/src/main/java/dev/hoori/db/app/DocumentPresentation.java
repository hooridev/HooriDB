package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.ExtendedJson;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared presenter for table rows and console results, switching between {@link Mode#GRID},
 * {@link Mode#JSON}, and {@link Mode#TREE} via a {@link Strategy}. Holds the active mode, the
 * current whole-document page, and per-strategy view state (JSON editor and document tree). Only
 * the app's thread uses it.
 */
final class DocumentPresentation {

  /** The most rows a grid shows: it counts them in an int. */
  static final int GRID_ROWS = Integer.MAX_VALUE;

  /** How rows or documents show. */
  enum Mode {
    /** A row each, a column a field, read as the grid scrolls. */
    GRID("Grid"),
    /** A page of them as an array of Relaxed Extended JSON. */
    JSON("JSON"),
    /** A page of them, each a tree of its fields. */
    TREE("Tree");

    final String title;

    Mode(String title) {
      this.title = title;
    }
  }

  /** What a strategy renders: the main content and its controls for the status line. */
  record View(El body, List<El> footer) {}

  /**
   * Navigation state for the pager: first, previous, next, and optionally last page.
   *
   * @param back whether moving back (first/previous) is enabled
   * @param on whether moving forward (next/last) is enabled
   * @param first jumps to the first page
   * @param previous moves one page back
   * @param next moves one page forward
   * @param last jumps to the last page, or null while the end is not yet known
   */
  record Paging(
      boolean back, boolean on, Runnable first, Runnable previous, Runnable next, Runnable last) {}

  /**
   * What the grid strategy needs to render the grid, its value panel, and optional grid paging.
   * {@code range} and {@code paging} are evaluated after {@code grid} is built so they observe the
   * rows in view from that build.
   */
  record GridView(
      Supplier<El> grid,
      ValuePanel valuePanel,
      ValuePanel.Cell cell,
      String note,
      boolean waiting,
      Supplier<String> range,
      Supplier<Paging> paging) {}

  /**
   * A page of whole documents for the JSON and Tree strategies.
   *
   * @param scope identifies the page so open tree nodes and editor cursor reset when it changes
   * @param source backing list of documents (compared by identity to detect refreshes), or null
   * @param sliceFrom 0-based start index within {@code source}
   * @param sliceTo 0-based exclusive end index within {@code source}
   * @param rangeFrom 0-based start index of this page in the overall collection or result
   * @param rangeTo 0-based exclusive end index of this page in the overall collection or result
   * @param loading whether the page is currently being read
   * @param label accessibility label for the JSON editor or tree
   * @param paging page navigation state and actions
   */
  record DocumentPage(
      String scope,
      List<Document> source,
      int sliceFrom,
      int sliceTo,
      long rangeFrom,
      long rangeTo,
      boolean loading,
      String label,
      Paging paging,
      Menu.State treeMenu,
      Supplier<List<Menu.Item>> treeMenuItems) {

    DocumentPage(
        String scope,
        List<Document> source,
        int sliceFrom,
        int sliceTo,
        long rangeFrom,
        long rangeTo,
        boolean loading,
        String label,
        Paging paging) {
      this(
          scope, source, sliceFrom, sliceTo, rangeFrom, rangeTo, loading, label, paging, null,
          null);
    }

    boolean empty() {
      return source == null || sliceTo <= sliceFrom;
    }
  }

  /** Presentation strategy for a collection or query result: grid, formatted JSON, or tree. */
  sealed interface Strategy permits Grid, Json, Tree {
    Mode mode();

    View render(
        App<State> app,
        ZoneId zone,
        Supplier<GridView> gridSource,
        Supplier<DocumentPage> documentsSource);
  }

  /** Grid strategy: DataGrid wrapped with the value panel, plus optional rows-in-view and pager. */
  static final class Grid implements Strategy {

    @Override
    public Mode mode() {
      return Mode.GRID;
    }

    @Override
    public View render(
        App<State> app,
        ZoneId zone,
        Supplier<GridView> gridSource,
        Supplier<DocumentPage> documentsSource) {
      var g = gridSource.get();
      El built = g.grid().get();
      El body = g.valuePanel().around(app, zone, built, g.cell(), g.note(), g.waiting());
      var footer = new ArrayList<El>();
      if (g.range() != null) {
        footer.add(Label.label(Label.Variant.MUTED, g.range().get()).key("rows-in-view"));
      }
      if (g.paging() != null) {
        footer.add(paging(g.paging().get()));
      }
      footer.add(g.valuePanel().button());
      return new View(body, footer);
    }
  }

  /** JSON strategy: a page of documents formatted as an array of Relaxed Extended JSON. */
  static final class Json implements Strategy {

    private final CodeEditor.State editor = new CodeEditor.State().readOnly(true);
    private String scope = "";
    private List<Document> source = List.of();
    private List<Document> slice = List.of();
    private int from;
    private int count;
    private String text;
    private int[] starts = new int[0];
    private int[] ends = new int[0];

    @Override
    public Mode mode() {
      return Mode.JSON;
    }

    @Override
    public View render(
        App<State> app,
        ZoneId zone,
        Supplier<GridView> gridSource,
        Supplier<DocumentPage> documentsSource) {
      var page = documentsSource.get();
      var footer = documentFooter(page);
      if (page.empty()) {
        slice = List.of();
        text = null;
        starts = new int[0];
        ends = new int[0];
        return new View(emptyOrLoading(page), footer);
      }
      String formatted = text(page);
      El body = col()
          .grow(1)
          .minSize(0, 0)
          .child(CodeEditor.embedded(CodeEditor.codeEditor(
                      app, formatted, editor, com.rethinkj.ui.json.Json.of(), null)
                  .key("json")
                  .label(page.label()))
              .grow(1));
      return new View(body, footer);
    }

    /** The document in the current JSON page at the editor caret, or null when empty/outside. */
    Document currentDocument() {
      if (text == null || slice.isEmpty() || starts.length != slice.size()) {
        return null;
      }
      int caret = Math.clamp(editor.caret(), 0, text.length());
      for (int i = 0; i < starts.length; i++) {
        if (caret >= starts[i] && caret <= ends[i]) {
          return slice.get(i);
        }
      }
      if (caret < starts[0]) {
        return slice.getFirst();
      }
      for (int i = 0; i < starts.length; i++) {
        int before = i == 0 ? 0 : ends[i - 1];
        int lineStart = Math.max(before, text.lastIndexOf('\n', starts[i]) + 1);
        int after = i + 1 < starts.length ? starts[i + 1] : text.length();
        int nl = text.indexOf('\n', ends[i]);
        int lineEnd = Math.min(after, nl < 0 ? text.length() : nl);
        if (caret >= lineStart && caret <= lineEnd) {
          return slice.get(i);
        }
      }
      return null;
    }

    private String text(DocumentPage page) {
      boolean other = !page.scope().equals(scope);
      if (other) {
        editor.select(0, 0);
      }
      int nextCount = page.sliceTo() - page.sliceFrom();
      if (text == null
          || other
          || page.source() != source
          || page.sliceFrom() != from
          || nextCount != count) {
        slice = List.copyOf(page.source().subList(page.sliceFrom(), page.sliceTo()));
        var json = ExtendedJson.array(slice);
        var formatted = com.rethinkj.ui.json.Json.format(json);
        text = formatted.valid() ? formatted.text() : json;
        scanSpans(text, slice.size());
        scope = page.scope();
        source = page.source();
        from = page.sliceFrom();
        count = nextCount;
      }
      return text;
    }

    private void scanSpans(String json, int expected) {
      int[] s = new int[expected];
      int[] e = new int[expected];
      int found = 0;
      int depth = 0;
      boolean inString = false;
      for (int i = 0; i < json.length() && found < expected; i++) {
        char c = json.charAt(i);
        if (inString) {
          if (c == '\\') {
            i++;
          } else if (c == '"') {
            inString = false;
          }
          continue;
        }
        switch (c) {
          case '"' -> inString = true;
          case '[' -> depth++;
          case '{' -> {
            if (depth == 1) {
              s[found] = i;
            }
            depth++;
          }
          case '}' -> {
            depth--;
            if (depth == 1) {
              e[found++] = i + 1;
            }
          }
          case ']' -> depth--;
          default -> {
            // Other characters do not affect nesting.
          }
        }
      }
      starts = found == expected ? s : new int[0];
      ends = found == expected ? e : new int[0];
    }
  }

  /** Tree strategy: a page of documents, each a tree of its fields and array items. */
  static final class Tree implements Strategy {

    private final DocumentTree documentTree = new DocumentTree();

    @Override
    public Mode mode() {
      return Mode.TREE;
    }

    @Override
    public View render(
        App<State> app,
        ZoneId zone,
        Supplier<GridView> gridSource,
        Supplier<DocumentPage> documentsSource) {
      var page = documentsSource.get();
      var footer = documentFooter(page);
      if (page.empty()) {
        documentTree.clear();
        return new View(emptyOrLoading(page), footer);
      }
      documentTree.show(
          page.scope(), page.source(), page.sliceFrom(), page.sliceTo(), page.rangeFrom());
      El body = col()
          .grow(1)
          .minSize(0, 0)
          .child(documentTree
              .view(zone, page.label(), page.treeMenu(), page.treeMenuItems())
              .grow(1));
      return new View(body, footer);
    }

    /** The top-level document containing the selected tree node, or null. */
    Document currentDocument() {
      return documentTree.selectedDocument();
    }
  }

  private final Strategy grid = new Grid();
  private final Json json = new Json();
  private final Tree tree = new Tree();
  private final Menu.State modeMenu = new Menu.State();
  private Mode mode;
  private long page;

  /** The active document in JSON or Tree mode, or null in Grid mode or when none is selected. */
  Document currentDocument() {
    return switch (mode) {
      case GRID -> null;
      case JSON -> json.currentDocument();
      case TREE -> tree.currentDocument();
    };
  }

  DocumentPresentation() {
    this(Mode.GRID);
  }

  DocumentPresentation(Mode initial) {
    this.mode = initial;
  }

  static Mode of(Settings.DocumentView view) {
    return switch (view) {
      case GRID -> Mode.GRID;
      case JSON -> Mode.JSON;
      case TREE -> Mode.TREE;
    };
  }

  Mode mode() {
    return mode;
  }

  void mode(Mode next) {
    mode = next;
  }

  boolean isGrid() {
    return mode == Mode.GRID;
  }

  long page() {
    return page;
  }

  void page(long next) {
    page = Math.max(0, next);
  }

  /** The strategy corresponding to the current mode. */
  Strategy strategy() {
    return switch (mode) {
      case GRID -> grid;
      case JSON -> json;
      case TREE -> tree;
    };
  }

  /** The segmented control that switches between Grid, JSON, and Tree. */
  El switcher(String key, Consumer<Mode> onChange) {
    return Segmented.segmented(List.of(Mode.values()), mode, m -> m.title, onChange)
        .key(key)
        .label("Show documents as");
  }

  /** The compact select that switches between Grid, JSON, and Tree in a narrow footer. */
  El picker(String key, Consumer<Mode> onChange) {
    Theme t = Theme.CURRENT.get();
    return Select.select(List.of(Mode.values()), mode, m -> m.title, modeMenu, onChange)
        .key(key)
        .label("Show documents as")
        .w(3.5f * t.size().control());
  }

  /** Renders the active strategy, or Grid when the data is not a collection of documents. */
  View view(
      App<State> app,
      ZoneId zone,
      boolean ofDocuments,
      Supplier<GridView> gridSource,
      Supplier<DocumentPage> documentsSource) {
    Strategy active = ofDocuments ? strategy() : grid;
    return active.render(app, zone, gridSource, documentsSource);
  }

  /** Rows or documents from first to last, counted from 1, as "Rows 201–223". */
  static String range(long first, long last, boolean documents) {
    return String.format(
        Locale.ROOT, documents ? "Documents %,d–%,d" : "Rows %,d–%,d", first, last);
  }

  /**
   * Buttons that turn to the first, the previous, the next or the last page: those back are on
   * where back is, those on where on is, the last only where it is given.
   */
  static El paging(Paging p) {
    Theme t = Theme.CURRENT.get();
    return row()
        .gap(t.space().xs())
        .align(Style.Align.CENTER)
        .child(pageButton(Symbol.ARROW_UP, "First page", p.back(), p.first()))
        .child(pageButton(Symbol.CHEVRON_UP, "Previous page", p.back(), p.previous()))
        .child(pageButton(Symbol.CHEVRON_DOWN, "Next page", p.on(), p.next()))
        .child(pageButton(Symbol.ARROW_DOWN, "Last page", p.on() && p.last() != null, p.last()))
        .key("paging");
  }

  private static El pageButton(Symbol icon, String label, boolean enabled, Runnable onPress) {
    return Button.icon(
        Button.Variant.GHOST,
        icon,
        label,
        enabled ? onPress : null,
        Button.options().size(Button.Size.SMALL));
  }

  private static List<El> documentFooter(DocumentPage page) {
    String text = page.rangeTo() <= page.rangeFrom()
        ? "No documents"
        : range(page.rangeFrom() + 1, page.rangeTo(), true);
    return List.of(
        Label.label(Label.Variant.MUTED, text).key("rows-in-view"), paging(page.paging()));
  }

  private static El emptyOrLoading(DocumentPage page) {
    Theme t = Theme.CURRENT.get();
    if (page.loading()) {
      return col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading documents")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading documents…"));
    }
    return col()
        .grow(1)
        .center()
        .child(Label.label(Label.Variant.MUTED, "No documents").key("no-documents"));
  }
}
