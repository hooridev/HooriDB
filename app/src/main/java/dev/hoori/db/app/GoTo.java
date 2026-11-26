package dev.hoori.db.app;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Workspace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Go to table or query: the palette after {@code @} finds the tables and views that the tree has
 * read of every connected connection and every workspace query, by name, and opens the chosen one
 * in its tab. A database whose tables are not read yet says so below them; choosing it reads them
 * in the background, and the palette stays open and shows that it waits.
 */
final class GoTo {

  static final String PREFIX = "@";

  /** The heading of the databases whose tables the palette does not know yet. */
  static final String UNREAD = "Not read yet";

  private GoTo() {}

  /** What the palette of the window finds after {@code @}, opening tables and queries there. */
  static CommandPalette.Source source(State s, TabGroups.Group<Tab> group) {
    return new CommandPalette.Source(
        PREFIX,
        "to open a table or query",
        FilterList.Entries.by(query -> entries(s, group, query)));
  }

  private sealed interface Found {
    String name();

    String where();

    int rank();

    boolean system();

    record OfTable(UUID id, Table table, String where, int rank, boolean system) implements Found {
      @Override
      public String name() {
        return table.name();
      }
    }

    record OfQuery(Workspace.Query query, String where, int rank) implements Found {
      @Override
      public String name() {
        return query.name();
      }

      @Override
      public boolean system() {
        return false;
      }
    }
  }

  /** The tables and queries that match, best first, and then what is not read yet. */
  static List<FilterList.Entry> entries(State s, TabGroups.Group<Tab> group, String query) {
    var search = new Search(s, group, query.strip());
    var connected = s.catalog.lives.values().stream()
        .filter(live -> live.phase instanceof Live.Connected)
        .map(live -> live.profile)
        .toList();
    for (var live : s.catalog.lives.values()) {
      if (live.phase instanceof Live.Connected) {
        search.connection(live, label(live.profile, connected));
      }
    }
    search.queries(s.workspace().entries(), "");
    var found = search.found;
    found.sort(Comparator.comparingInt(Found::rank)
        .thenComparing(Found::system)
        .thenComparingInt(f -> f.name().length()));
    var entries = new ArrayList<FilterList.Entry>(found.size() + search.notes.size() + 1);
    for (var f : found) {
      switch (f) {
        case Found.OfTable t -> {
          var table = t.table();
          var symbol = table.kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE;
          var color = s.color(t.id());
          entries.add(FilterList.entry(
                  table.name(), () -> Frame.focusContentSoon(s, s.openTable(t.id(), table, group)))
              .key(key(t.id(), table))
              .detail(t.where())
              .leading(() -> lead(color, Icon.icon(symbol, Icon.Variant.MUTED))));
        }
        case Found.OfQuery q -> {
          var item = q.query();
          entries.add(FilterList.entry(item.name(), () -> {
                var opened = s.openQuery(item.id(), group);
                if (opened != null) {
                  Frame.focusContentSoon(s, opened);
                }
              })
              .key("query\0" + item.id())
              .detail(q.where())
              .leading(
                  () -> lead(null, Icon.icon(Glyphs.query(item.language()), Icon.Variant.MUTED))));
        }
      }
    }
    if (connected.isEmpty()) {
      entries.add(FilterList.entry("No connection is open", null)
          .detail("Open one in the tree to find its tables")
          .symbol(Symbol.INFO));
    }
    entries.addAll(search.notes);
    return entries;
  }

  /** One search of the palette: the tables and queries that match, and unread databases. */
  private static final class Search {
    private final State s;
    private final TabGroups.Group<Tab> group;
    private final String q;
    final List<Found> found = new ArrayList<>();
    final List<FilterList.Entry> notes = new ArrayList<>();

    Search(State s, TabGroups.Group<Tab> group, String q) {
      this.s = s;
      this.group = group;
      this.q = q;
    }

    /** Every workspace query in the tree that matches, with its folder path as where. */
    void queries(List<Workspace.Entry> entries, String above) {
      for (var entry : entries) {
        switch (entry) {
          case Workspace.Folder f ->
            queries(f.entries(), above.isEmpty() ? f.name() : above + " / " + f.name());
          case Workspace.Query query -> {
            int rank = rank(query.name(), q);
            if (rank >= 0) {
              found.add(new Found.OfQuery(query, above.isEmpty() ? "Workspace" : above, rank));
            }
          }
          case ConnectionProfile _ -> {
            // a connection is found by its tables, above
          }
        }
      }
    }

    /** A connected connection, by the name the palette calls it. */
    void connection(Live live, String name) {
      var databases = live.levels.get(new Node.Connection(live.id()));
      if (databases instanceof CatalogTree.Read(var children)) {
        for (var node : children) {
          database(live, name, (Node.OfDatabase) node);
        }
      } else if (databases instanceof CatalogTree.Reading) { // a failure shows in the tree
        notes.add(note(name, "reading its databases…", s.color(live.id()), true, null));
      }
    }

    /**
     * A database's tables that match, and a note where they are not all known: while its own
     * level, or that of a schema of its own, is not read.
     */
    private void database(Live live, String name, Node.OfDatabase db) {
      var unknown = new Unknown();
      for (var kid : known(live, db, unknown)) {
        if (kid instanceof Node.OfTable t) {
          var table = t.table();
          int rank = rank(table.name(), q);
          if (rank >= 0) {
            found.add(new Found.OfTable(live.id(), table, where(name, table), rank, system(table)));
          }
        }
      }
      // What the palette itself reads, or failed to.
      var scan = live.scans.get(db);
      if (scan != null) {
        unknown.reading |= scan.asked != null;
        unknown.failed = scan.failed != null ? scan.failed : unknown.failed;
      }
      if (!db.database().system() && unknown.any()) {
        notes.add(unread(live.id(), name, db, unknown));
      }
    }

    /**
     * What the tree has read below the database: its tables, and those of its schemas that are
     * read; what is not read goes into {@code unknown}.
     */
    private List<Node> known(Live live, Node.OfDatabase db, Unknown unknown) {
      var level = live.levels.get(db);
      var kids = new ArrayList<Node>();
      if (!(level instanceof CatalogTree.Read(var children))) {
        unknown.add(level, db);
        return kids;
      }
      for (var child : children) {
        if (!(child instanceof Node.OfSchema x)) {
          kids.add(child);
        } else if (live.levels.get(x) instanceof CatalogTree.Read(var below)) {
          kids.addAll(below);
        } else if (!x.schema().system()) {
          unknown.add(live.levels.get(x), x);
        }
      }
      return kids;
    }

    /** The note of a database not read: choosing it reads it, and the palette stays open. */
    private FilterList.Entry unread(UUID id, String name, Node.OfDatabase db, Unknown unknown) {
      var c = s.catalog;
      var color = s.color(id);
      String database = db.database().name();
      // The palette stays open, and shows the tables as they come.
      Runnable readThem = () -> {
        var started = c.scan(s, db);
        if (started != null) {
          s.readForPalette(group, () -> c.stop(db, started));
        }
      };
      FilterList.Entry note;
      if (unknown.reading) {
        note = note(database, name + " · reading its tables…", color, true, null);
      } else if (unknown.failed != null) {
        note = note(
            database,
            name + " · " + unknown.failed + ", choose to try again",
            color,
            false,
            readThem);
      } else {
        note = note(database, name + " · choose to read its tables", color, false, readThem);
      }
      return note.key(String.join("\0", id.toString(), database)).keepsOpen(true);
    }

    private static boolean failed(CatalogTree.Level level) {
      return level instanceof CatalogTree.NotRead || level instanceof CatalogTree.NotOpened;
    }

    /** A row below the tables about a database whose tables are not known. */
    private static FilterList.Entry note(
        String what, String detail, Color color, boolean waits, Runnable read) {
      return FilterList.entry(what, read)
          .detail(detail)
          .group(UNREAD)
          .leading(() -> lead(
              color,
              waits
                  ? Progress.spinner().label("Reading")
                  : Icon.icon(Symbol.DATABASE, Icon.Variant.MUTED)));
    }

    /** Where the table is: its connection, database and schema. */
    private static String where(String connection, Table table) {
      return switch (table.container()) {
        case Database d -> connection + " · " + d.name();
        case Schema x -> connection + " · " + x.database().name() + " · " + x.name();
      };
    }

    private static boolean system(Table table) {
      return switch (table.container()) {
        case Database d -> d.system();
        case Schema x -> x.system();
      };
    }

    /** What keeps a database's tables from being known: a read, none yet, or the first failure. */
    private final class Unknown {
      boolean reading;
      boolean unread;
      String failed;

      void add(CatalogTree.Level level, Node node) {
        reading |= level instanceof CatalogTree.Reading;
        unread |= CatalogTree.unread(level);
        if (failed == null && failed(level)) {
          failed = s.catalog.problem(s, node).title();
        }
      }

      boolean any() {
        return reading || unread || failed != null;
      }
    }
  }

  private static El lead(Color color, El icon) {
    Theme t = Theme.CURRENT.get();
    return row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(ConnectionColors.mark(color))
        .child(icon);
  }

  /**
   * What the palette calls a connection: its name, and where another connected one has the same,
   * its user and server too, or its place among them where those are the same as well.
   */
  static String label(ConnectionProfile profile, List<ConnectionProfile> connected) {
    var name = State.name(profile);
    var same = connected.stream().filter(p -> State.name(p).equals(name)).toList();
    if (same.size() < 2) {
      return name;
    }
    var where = server(profile);
    if (same.stream().filter(p -> server(p).equals(where)).count() < 2) {
      return name + " (" + where + ")";
    }
    return name + " (" + (same.indexOf(profile) + 1) + ")";
  }

  private static String server(ConnectionProfile p) {
    return (p.user().isBlank() ? "" : p.user() + "@") + p.host() + (p.srv() ? "" : ":" + p.port());
  }

  /** What tells the table from every other: its connection, database, schema and name. */
  private static String key(UUID id, Table table) {
    var path = switch (table.container()) {
      case Database d -> List.of(d.name());
      case Schema x -> List.of(x.database().name(), x.name());
    };
    return id + "\0" + String.join("\0", path) + "\0" + table.name();
  }

  /**
   * How well a name matches what was typed, whatever the case: 0 for the name itself, 1 for its
   * start, 2 for the start of a word in it, 3 for the initials of its words ({@code oi} for {@code
   * order_items}), 4 for anywhere in it; -1 for not at all. Everything matches nothing typed. The
   * name is compared as it is, a character at a time, as lower-casing can change its length.
   */
  static int rank(String name, String q) {
    if (q.isEmpty()) {
      return 0;
    }
    if (name.equalsIgnoreCase(q)) {
      return 0;
    }
    if (at(name, 0, q)) {
      return 1;
    }
    var initials = new StringBuilder();
    boolean wordStart = false;
    boolean anywhere = false;
    for (int i = 0; i < name.length(); i++) {
      boolean here = at(name, i, q);
      anywhere |= here;
      if (starts(name, i)) {
        initials.append(name.charAt(i));
        wordStart |= here;
      }
    }
    if (wordStart) {
      return 2;
    }
    if (at(initials.toString(), 0, q)) {
      return 3;
    }
    return anywhere ? 4 : -1;
  }

  /** Whether the text holds what was typed at the index, whatever the case. */
  private static boolean at(String text, int i, String q) {
    return text.regionMatches(true, i, q, 0, q.length());
  }

  /** Whether a word begins at the index: after a separator, or a capital after a small letter. */
  private static boolean starts(String name, int i) {
    char ch = name.charAt(i);
    if (!Character.isLetterOrDigit(ch)) {
      return false;
    }
    if (i == 0) {
      return true;
    }
    char before = name.charAt(i - 1);
    return !Character.isLetterOrDigit(before)
        || Character.isUpperCase(ch) && Character.isLowerCase(before);
  }
}
