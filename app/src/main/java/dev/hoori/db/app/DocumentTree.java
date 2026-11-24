package dev.hoori.db.app;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tree;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Shell;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Documents as a tree: each document a root, whose fields and the items of its arrays open below
 * it, each with its value or how many it holds. Its nodes go by the place of their value, so that
 * what is open stays open while the same documents are shown. Only the app's thread uses it.
 */
final class DocumentTree {

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
  private int from;
  private long offset; // 0-based start of these documents in the whole collection or result
  private List<Document> documents = List.of();

  /**
   * Shows the documents from {@code from} to before {@code to} of {@code of}, which only grow, as
   * the scope, starting at {@code offset} in the whole collection or result: what was open stays
   * open where the scope is the same.
   */
  void show(String scope, List<Document> of, int from, int to, long offset) {
    boolean other = !scope.equals(this.scope);
    if (other) {
      open.clear();
      selected = null;
    }
    if (other || of != this.of || from != this.from || to - from != documents.size()) {
      documents = List.copyOf(of.subList(from, to));
    }
    this.scope = scope;
    this.of = of;
    this.from = from;
    this.offset = offset;
  }

  /** The tree of the documents, with dates in the zone. */
  El view(ZoneId zone, String label) {
    return tree(zone).key("documents").label(label);
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
            : "Document " + (offset + node.document() + 1);
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
