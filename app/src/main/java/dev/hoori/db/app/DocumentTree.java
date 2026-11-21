package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tree;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.ExtendedJson;
import dev.hoori.db.core.document.Shell;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Documents as a tree and as text: each document a root, whose fields and the items of its arrays
 * open below it, each with its value or how many it holds; and the documents as an array of
 * Relaxed Extended JSON, formatted, for a read-only editor. Its nodes go by the place of their value, so that
 * what is open stays open while the same documents are shown. Only the app's thread uses it.
 */
final class DocumentTree {

  /** A conservative upper bound on the formatted text and tree work of documents shown whole. */
  static final long VIEW_BUDGET = 2_000_000;

  /** Deep enough for ordinary BSON, without letting nesting exhaust a formatter's stack. */
  static final int VIEW_DEPTH = 64;

  static final String LIMITED_TITLE = "Page too large to show";
  static final String LIMITED_TEXT =
      "Its whole documents are too large or deeply nested. Use Grid to inspect their bounded"
          + " previews.";

  /**
   * A value in a document: the document's place among those shown, and the indexes of the fields
   * and items that lead to it; empty for the document itself.
   */
  record Node(String scope, int document, List<Integer> path, String name, Object value) {

    /** A key of the kit's, unique among the nodes, without spaces or quotes. */
    String key() {
      var key = new StringBuilder(scope).append('-').append(document);
      for (int i : path) {
        key.append('.').append(i);
      }
      return key.toString();
    }
  }

  final Set<String> open = new HashSet<>();
  Node selected;
  private String scope = ""; // the documents shown, such as a page; what is open is theirs
  private List<Document> of = List.of(); // what they are a part of
  private List<Document> documents = List.of();
  private int count;
  private boolean limited;
  private String text; // the documents as text, made once for them
  final CodeEditor.State editor = new CodeEditor.State().readOnly(true);

  /**
   * Shows the documents from to before to of these, which only grow, as the scope: what was open
   * stays open where the scope is the same, and the text is made anew where the documents changed.
   */
  void show(String scope, List<Document> of, int from, int to) {
    boolean other = !scope.equals(this.scope);
    if (other) {
      open.clear();
      selected = null;
      editor.select(0, 0);
    }
    if (other || of != this.of || to - from != count) {
      text = null;
      count = to - from;
      limited = !showable(of, from, to);
      documents = limited ? List.of() : List.copyOf(of.subList(from, to));
      if (limited) {
        open.clear();
        selected = null;
        editor.select(0, 0);
      }
    }
    this.scope = scope;
    this.of = limited ? List.of() : of;
  }

  /** Releases documents when their view is no longer visible. */
  void clear() {
    open.clear();
    selected = null;
    scope = "";
    of = List.of();
    documents = List.of();
    count = 0;
    limited = false;
    text = null;
  }

  /**
   * Whether these documents fit the bounded whole-document view. The estimate covers worst-case
   * JSON escaping, formatting indentation, binary expansion and every value visited by the tree.
   */
  static boolean showable(List<Document> documents, int from, int to) {
    long left = VIEW_BUDGET;
    for (int i = from; i < to && left >= 0; i++) {
      left = left(documents.get(i), 0, left);
    }
    return left >= 0;
  }

  private static long left(Object value, int depth, long left) {
    if (depth > VIEW_DEPTH) {
      return -1;
    }
    // This also limits the number of tree nodes that one expansion can make on the app thread.
    left = take(left, 256L + depth * 4L);
    if (left < 0) {
      return -1;
    }
    return switch (value) {
      case Document d -> {
        long rest = left;
        for (var entry : d.entries()) {
          rest = take(rest, 6L * entry.name().length());
          if (rest < 0 || (rest = left(entry.value(), depth + 1, rest)) < 0) {
            break;
          }
        }
        yield rest;
      }
      case List<?> list -> {
        long rest = left;
        for (var item : list) {
          rest = left(item, depth + 1, rest);
          if (rest < 0) {
            break;
          }
        }
        yield rest;
      }
      case String text -> take(left, 6L * text.length());
      case Bson.Binary binary -> take(left, 2L * binary.length());
      case Bson.Decimal128 decimal -> take(left, 6L * decimal.text().length());
      case Bson.Regex regex ->
        take(take(left, 6L * regex.pattern().length()), 6L * regex.options().length());
      case Bson.Code code -> {
        long rest = take(left, 6L * code.code().length());
        yield code.scope() == null || rest < 0 ? rest : left(code.scope(), depth + 1, rest);
      }
      case Bson.Symbol symbol -> take(left, 6L * symbol.symbol().length());
      case null, default -> left;
    };
  }

  private static long take(long left, long cost) {
    return cost > left ? -1 : left - cost;
  }

  /** The documents as a JSON array of Relaxed Extended JSON, formatted. */
  String text() {
    if (text == null) {
      var json = ExtendedJson.array(documents);
      var formatted = Json.format(json);
      text = formatted.valid() ? formatted.text() : json;
    }
    return text;
  }

  /**
   * The documents shown as JSON, in an editor that only shows them, or as a tree, with dates in
   * the zone.
   */
  El view(App<State> app, TableTab.Shown shown, ZoneId zone, String label) {
    if (limited) {
      return Callout.callout(Callout.Kind.WARNING, LIMITED_TITLE, LIMITED_TEXT);
    }
    return shown == TableTab.Shown.JSON
        ? CodeEditor.embedded(CodeEditor.codeEditor(app, text(), editor, Json.of(), null)
            .key("json")
            .label(label))
        : tree(zone).key("documents").label(label);
  }

  /** The tree of the documents, with dates in the zone. */
  private El tree(ZoneId zone) {
    var model = new Tree.Model<Node>() {
      @Override
      public List<Node> roots() {
        var roots = new ArrayList<Node>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
          roots.add(new Node(scope, i, List.of(), null, documents.get(i)));
        }
        return roots;
      }

      @Override
      public Tree.Children<Node> children(Node node) {
        var children = new ArrayList<Node>();
        switch (node.value()) {
          case Document d -> {
            var entries = d.entries();
            for (int i = 0; i < entries.size(); i++) {
              children.add(child(node, i, entries.get(i).name(), entries.get(i).value()));
            }
          }
          case List<?> list -> {
            for (int i = 0; i < list.size(); i++) {
              children.add(child(node, i, "[" + i + "]", list.get(i)));
            }
          }
          case null, default -> {
            // A value of its own has no children.
          }
        }
        return Tree.ready(children);
      }

      @Override
      public boolean isLeaf(Node node) {
        return switch (node.value()) {
          case Document d -> d.isEmpty();
          case List<?> list -> list.isEmpty();
          case null, default -> true;
        };
      }

      @Override
      public String label(Node node) {
        if (node.name() != null) {
          return node.name();
        }
        var document = (Document) node.value();
        return document.has("_id")
            ? Shell.scalar(document.get("_id"))
            : "Document " + (node.document() + 1);
      }

      @Override
      public El icon(Node node) {
        var symbol = switch (node.value()) {
          case Document d -> Symbol.BRACES;
          case List<?> list -> Symbol.TEXT_BULLET_LIST_LTR;
          case null, default -> Symbol.TAG;
        };
        return Icon.icon(symbol, Icon.Variant.MUTED);
      }

      @Override
      public String detail(Node node) {
        return DocumentTree.detail(node.value(), zone);
      }

      @Override
      public boolean muted(Node node) {
        return node.value() == null;
      }

      @Override
      public String key(Node node) {
        return node.key();
      }
    };
    return Tree.tree(
        model,
        new Tree.Options<Node>()
            .open(node -> open.contains(node.key()), this::opened)
            .select(selected, node -> selected = node));
  }

  private void opened(Node node, boolean opened) {
    if (opened) {
      open.add(node.key());
    } else {
      open.remove(node.key());
    }
  }

  private static Node child(Node parent, int index, String name, Object value) {
    var path = new ArrayList<>(parent.path());
    path.add(index);
    return new Node(parent.scope(), parent.document(), List.copyOf(path), name, value);
  }

  /** What a value is, beside its name: a value itself, or what a document or array holds. */
  static String detail(Object value, ZoneId zone) {
    return switch (value) {
      case Document d -> d.size() == 1 ? "1 field" : d.size() + " fields";
      case List<?> list -> list.size() == 1 ? "1 item" : list.size() + " items";
      case Instant i -> Cells.text(i.atOffset(ZoneOffset.UTC), zone) + " · date";
      case String s -> Shell.quote(s.length() > 200 ? s.substring(0, 200) + "…" : s);
      case null -> "null";
      default -> Shell.scalar(value) + " · " + Bson.type(value);
    };
  }
}
