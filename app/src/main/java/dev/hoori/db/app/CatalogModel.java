package dev.hoori.db.app;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import dev.hoori.db.core.catalog.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * The catalog's rows for the kit's tree, as the state holds them: the workspace's folders and
 * connections, and below a connection what the tree read of its server, or why it could not.
 */
record CatalogModel(Theme t, State s) implements Tree.Model<Node> {

  @Override
  public List<Node> roots() {
    return s.workspace().entries().stream().map(Node::of).toList();
  }

  @Override
  public Tree.Children<Node> children(Node node) {
    if (node instanceof Node.Folder(var id)) {
      var folder = s.folder(id);
      return Tree.ready(
          folder == null ? List.of() : folder.entries().stream().map(Node::of).toList());
    }
    if (node instanceof Node.Query) {
      return Tree.ready(List.of());
    }
    var c = s.catalog;
    var live = c.lives.get(node.id());
    if (live == null) {
      return Tree.ready(List.of());
    }
    if (node instanceof Node.ForeignKeys(var id, var table)) {
      var keys = live.keys.get(table);
      var groups = new ArrayList<Node>(2);
      if (!ForeignKeys.outgoing(table, keys).isEmpty()) {
        groups.add(new Node.KeyGroup(id, table, false));
      }
      if (!ForeignKeys.incoming(table, keys).isEmpty()) {
        groups.add(new Node.KeyGroup(id, table, true));
      }
      return Tree.ready(groups);
    }
    if (node instanceof Node.KeyGroup(var id, var table, var incoming)) {
      var keys = live.keys.get(table);
      var matching =
          incoming ? ForeignKeys.incoming(table, keys) : ForeignKeys.outgoing(table, keys);
      return Tree.ready(matching.stream()
          .map(k -> (Node) new Node.OfKey(id, table, k, incoming))
          .toList());
    }
    if (node instanceof Node.OfKey) {
      return Tree.ready(List.of());
    }
    if (node instanceof Node.Indexes(var id, var table)) {
      var indexes = live.indexes.get(table);
      return Tree.ready(
          indexes == null
              ? List.of()
              : indexes.stream().map(i -> (Node) new Node.OfIndex(id, table, i)).toList());
    }
    if (node instanceof Node.OfIndex) {
      return Tree.ready(List.of());
    }
    if (CatalogTree.failedWhole(live, node)) {
      return CatalogTree.ended(live.phase)
          ? Tree.ready(List.of(new Node.Problem(node)))
          : Tree.loading(loading(node));
    }
    var level = live.levels.get(node);
    if (level instanceof CatalogTree.Read(var children)) {
      if (node instanceof Node.OfTable(var id, var table)) {
        var keys = live.keys.get(table);
        var indexes = live.indexes.get(table);
        boolean hasKeys = keys != null && !keys.isEmpty();
        boolean hasIndexes = indexes != null && !indexes.isEmpty();
        if (hasKeys || hasIndexes) {
          var all = new ArrayList<Node>(children.size() + 2);
          all.addAll(children);
          if (hasKeys) {
            all.add(new Node.ForeignKeys(id, table));
          }
          if (hasIndexes) {
            all.add(new Node.Indexes(id, table));
          }
          return Tree.ready(all);
        }
      }
      return Tree.ready(children);
    }
    if (level instanceof CatalogTree.NotRead || level instanceof CatalogTree.NotOpened) {
      return Tree.failed(c.problem(s, node).title(), CatalogTree.TRY_AGAIN, () -> c.retry(s, node));
    }
    return Tree.loading(loading(node));
  }

  @Override
  public boolean muted(Node node) {
    return switch (node) {
      case Node.OfDatabase n -> n.database().system();
      case Node.OfSchema n -> n.schema().system();
      case Node.OfTable n -> system(n.table());
      default -> false;
    };
  }

  @Override
  public boolean isLeaf(Node node) {
    return node instanceof Node.Query
        || node instanceof Node.OfColumn
        || node instanceof Node.OfKey
        || node instanceof Node.OfIndex
        || node instanceof Node.Problem;
  }

  @Override
  public String label(Node node) {
    return s.catalog.label(s, node);
  }

  @Override
  public String detail(Node node) {
    return s.catalog.detail(s, node);
  }

  /** The text of the row below an open node whose children are on their way. */
  private String loading(Node node) {
    var live = s.catalog.lives.get(node.id());
    if (!(node instanceof Node.Connection) || live == null) {
      return "Loading…";
    }
    return switch (live.phase) {
      case Live.ForDriver _ -> "Waiting for the driver…";
      case Live.ForKeyring _ -> "Looking for the system keyring…";
      case Live.ReadingPassword _ -> "Reading the password…";
      case Live.ForPassword _ -> "Waiting for the password…";
      case Live.Connecting _ -> "Connecting…";
      default -> "Loading…";
    };
  }

  @Override
  public El icon(Node node) {
    return switch (node) {
      case Node.Folder(var id) ->
        icon(s.folderOpen(id) ? Symbol.FOLDER_OPEN : Symbol.FOLDER, false);
      case Node.Connection n -> connection(n);
      case Node.Query(var id) -> icon(Glyphs.query(s.query(id).language()), false);
      case Node.OfDatabase n -> icon(Symbol.DATABASE, n.database().system());
      case Node.OfSchema n -> icon(Glyphs.SCHEMA, n.schema().system());
      case Node.OfTable n ->
        icon(n.table().kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE, system(n.table()));
      case Node.OfColumn n -> columnIcon(n);
      case Node.ForeignKeys _ -> icon(Symbol.LINK, false);
      case Node.KeyGroup n -> icon(n.incoming() ? Symbol.ARROW_LEFT : Symbol.ARROW_RIGHT, false);
      case Node.OfKey _ -> icon(Symbol.LINK, false);
      case Node.Indexes _ -> icon(Symbol.KEY_MULTIPLE, false);
      case Node.OfIndex n ->
        n.index().primary()
            ? Icon.icon(Symbol.KEY, Icon.Variant.WARNING)
            : icon(Symbol.KEY_MULTIPLE, false);
      case Node.Problem n -> Icon.icon(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER);
    };
  }

  private El columnIcon(Node.OfColumn n) {
    if (n.column().keyPosition() > 0) {
      return Icon.icon(Symbol.KEY, Icon.Variant.WARNING);
    }
    if (ForeignKeys.isOutgoingColumn(
        n.column(), s.catalog.foreignKeys(n.id(), n.column().table()))) {
      return Icon.icon(Symbol.LINK, Icon.Variant.MUTED);
    }
    return icon(Symbol.COLUMN_SINGLE, false);
  }

  // A system object's icon is quieter too, as its name is muted.
  private El icon(Icon symbol, boolean system) {
    return Icon.icon(symbol, system ? Icon.Variant.DISABLED : Icon.Variant.MUTED);
  }

  private static boolean system(Table table) {
    return switch (table.container()) {
      case dev.hoori.db.core.catalog.Database d -> d.system();
      case dev.hoori.db.core.catalog.Schema x -> x.system();
    };
  }

  // The kind of server is the row's muted text; the icon's color says whether it is connected.
  private El connection(Node.Connection n) {
    var live = s.catalog.lives.get(n.id());
    Live.Phase phase = live == null ? null : live.phase;
    El icon = switch (phase) {
      case Live.Connected _ -> Icon.icon(Symbol.SERVER, Icon.Variant.SUCCESS);
      case Live.Failed _, Live.Lost _ -> Icon.icon(Symbol.SERVER, Icon.Variant.DANGER);
      case null, default -> Icon.icon(Symbol.SERVER, Icon.Variant.MUTED);
    };
    El mark = row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(ConnectionColors.mark(s.color(n.id())))
        .child(icon);
    if (phase == null || phase instanceof Live.Connected || CatalogTree.ended(phase)) {
      return mark;
    }
    return mark.child(Progress.spinner().label("Connecting"));
  }
}
