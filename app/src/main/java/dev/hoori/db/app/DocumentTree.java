package dev.hoori.db.app;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tree;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Shell;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Documents as a tree and as text: each document a root, whose fields and the items of its arrays
 * open below it, each with its value or how many it holds; and the documents in the shell's syntax,
 * one after another, for a read-only editor. Its nodes go by the place of their value, so that
 * what is open stays open while the same documents are shown. Only the app's thread uses it.
 */
final class DocumentTree {

  private static final DateTimeFormatter MOMENT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSxxx");

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
  private List<Document> documents = List.of();
  private int size; // of the documents when the text was made, as more come to a result
  private String text; // the documents as text, made once for them
  final CodeEditor.State editor = new CodeEditor.State().readOnly(true);

  /**
   * Shows these documents, of the scope: what was open stays open where the scope is the same,
   * and the text is made anew where the documents changed.
   */
  void show(String scope, List<Document> documents) {
    if (!scope.equals(this.scope)) {
      open.clear();
      selected = null;
      editor.select(0, 0);
    }
    if (!scope.equals(this.scope) || documents != this.documents || documents.size() != size) {
      text = null;
    }
    size = documents.size();
    this.scope = scope;
    this.documents = documents;
  }

  /** The documents in the shell's syntax, each over its lines, separated by an empty line. */
  String text() {
    if (text == null) {
      var out = new StringBuilder();
      for (var document : documents) {
        if (!out.isEmpty()) {
          out.append("\n\n");
        }
        out.append(Shell.pretty(document));
      }
      text = out.toString();
    }
    return text;
  }

  /** The tree of the documents, with dates in the zone. */
  El tree(ZoneId zone, Consumer<Node> activate) {
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
          case null, default -> {}
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
            .open(node -> open.contains(node.key()), (node, opened) -> {
              if (opened) {
                open.add(node.key());
              } else {
                open.remove(node.key());
              }
            })
            .select(selected, node -> selected = node)
            .onActivate(activate::accept));
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
      case Instant i -> MOMENT.format(i.atZone(zone)) + " · date";
      case String s -> Shell.quote(s.length() > 200 ? s.substring(0, 200) + "…" : s);
      case null -> "null";
      default -> Shell.scalar(value) + " · " + Bson.type(value);
    };
  }
}
