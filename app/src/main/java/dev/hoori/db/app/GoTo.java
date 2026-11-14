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
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.connection.ConnectionProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Go to table: the palette after {@code @} finds the tables and views that the tree has read of
 * every connected connection, by name, and opens the chosen one in its tab. A database whose
 * tables are not read yet says so below them; choosing it reads them in the background, and the
 * palette stays open and shows that it waits.
 */
final class GoTo {

  static final String PREFIX = "@";

  /** The heading of the databases whose tables the palette does not know yet. */
  static final String UNREAD = "Not read yet";

  private GoTo() {}

  /** What the palette of the window finds after {@code @}, opening tables in that window. */
  static CommandPalette.Source source(State s, TabGroups.Group<Tab> group) {
    return new CommandPalette.Source(
        PREFIX, "to open a table", FilterList.Entries.by(query -> entries(s, group, query)));
  }

  private record Found(UUID id, Table table, String where, int rank, boolean system) {}

  /** The tables that match, best first, and then what is not read yet. */
  static List<FilterList.Entry> entries(State s, TabGroups.Group<Tab> group, String query) {
    var c = s.catalogue;
    var q = query.strip();
    var found = new ArrayList<Found>();
    var notes = new ArrayList<FilterList.Entry>();
    var connected = c.lives.values().stream()
        .filter(live -> live.phase instanceof Catalogue.Connected)
        .map(live -> live.profile)
        .toList();
    for (var live : c.lives.values()) {
      if (!(live.phase instanceof Catalogue.Connected)) {
        continue;
      }
      var id = live.id();
      var name = label(live.profile, connected);
      var colour = s.colour(id);
      var databases = live.levels.get(new Node.Connection(id));
      if (!(databases instanceof Catalogue.Read read)) {
        if (databases instanceof Catalogue.Reading) { // a failure shows in the tree
          notes.add(note(name, "reading its databases…", colour, true, null));
        }
        continue;
      }
      for (var node : read.children()) {
        var db = (Node.OfDatabase) node;
        // Unread while its own level, or that of a schema of its own, is not read.
        boolean reading = false;
        boolean unread = false;
        String failed = null;
        var level = live.levels.get(db);
        var kids = new ArrayList<Node>();
        if (level instanceof Catalogue.Read r) {
          for (var child : r.children()) {
            if (child instanceof Node.OfSchema x) {
              var below = live.levels.get(x);
              if (below instanceof Catalogue.Read rx) {
                kids.addAll(rx.children());
              } else if (!x.schema().system()) {
                reading |= below instanceof Catalogue.Reading;
                unread |= Catalogue.unread(below);
                failed =
                    failed != null || !failed(below) ? failed : c.problem(s, x).title();
              }
            } else {
              kids.add(child);
            }
          }
        } else {
          reading = level instanceof Catalogue.Reading;
          unread = Catalogue.unread(level);
          failed = failed(level) ? c.problem(s, db).title() : null;
        }
        for (var kid : kids) {
          if (kid instanceof Node.OfTable t) {
            int rank = rank(t.table().name(), q);
            if (rank >= 0) {
              found.add(new Found(id, t.table(), where(name, t.table()), rank, system(t.table())));
            }
          }
        }
        // What the palette itself reads, or failed to.
        var scan = live.scans.get(db);
        if (scan != null) {
          reading |= scan.work != null;
          failed = scan.failed != null ? scan.failed : failed;
        }
        if (db.database().system() || !(reading || unread || failed != null)) {
          continue;
        }
        String database = db.database().name();
        Runnable readThem = () -> {
          var palette = s.palette(group);
          var text = palette.text();
          var started = c.scan(s, db);
          if (started != null) {
            s.readForPalette(group, () -> c.stop(db, started));
          }
          palette.open(text); // it stays, and shows the tables as they come
        };
        notes.add(
            reading
                ? note(database, name + " · reading its tables…", colour, true, null)
                : failed != null
                    ? note(
                        database,
                        name + " · " + failed + ", choose to try again",
                        colour,
                        false,
                        readThem)
                    : note(
                        database, name + " · choose to read its tables", colour, false, readThem));
      }
    }
    found.sort(Comparator.comparingInt(Found::rank)
        .thenComparing(Found::system)
        .thenComparingInt(f -> f.table().name().length()));
    var entries = new ArrayList<FilterList.Entry>(found.size() + notes.size() + 1);
    for (var f : found) {
      var table = f.table();
      var symbol = table.kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE;
      var colour = s.colour(f.id());
      entries.add(FilterList.entry(table.name(), () -> {
            Frame.focusContentSoon(s, s.openTable(f.id(), table, group));
          })
          .detail(f.where())
          .leading(t -> lead(t, colour, Icon.icon(t, symbol, Icon.Variant.MUTED))));
    }
    if (connected.isEmpty()) {
      entries.add(FilterList.entry("No connection is open", null)
          .detail("Open one in the tree to find its tables")
          .symbol(Symbol.INFO));
    }
    entries.addAll(notes);
    return entries;
  }

  private static boolean failed(Catalogue.Level level) {
    return level instanceof Catalogue.NotRead || level instanceof Catalogue.NotOpened;
  }

  /** A row below the tables about a database whose tables are not known. */
  private static FilterList.Entry note(
      String what, String detail, Colour colour, boolean waits, Runnable read) {
    return FilterList.entry(what, read)
        .detail(detail)
        .group(UNREAD)
        .leading(t -> lead(
            t,
            colour,
            waits
                ? Progress.spinner(t).label("Reading")
                : Icon.icon(t, Symbol.DATABASE, Icon.Variant.MUTED)));
  }

  private static El lead(Theme t, Colour colour, El icon) {
    return row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(Colours.mark(t, colour))
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
    return (p.user().isBlank() ? "" : p.user() + "@") + p.host() + ":" + p.port();
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
