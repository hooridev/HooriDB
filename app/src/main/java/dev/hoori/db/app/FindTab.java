package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.dialect.Dialect;
import dev.hoori.db.core.dialect.DocumentDialect;
import dev.hoori.db.core.find.Candidate;
import dev.hoori.db.core.find.DocumentFinder;
import dev.hoori.db.core.find.Finder;
import dev.hoori.db.core.find.Found;
import dev.hoori.db.core.find.Search;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A search of a database or schema of a connection: the names and definitions of its objects, or
 * the values in its tables, on a session of the tab's own, which it opens for its first search
 * and which nothing else uses. A search in data reads the tables and their estimated sizes first,
 * which the user chooses from, and then searches one table after another, each in one query, at
 * most {@link Search#LIMIT} rows of it counted; it can be cancelled between and in its queries.
 */
final class FindTab implements Tab {

  /** What is searched. */
  enum In {
    OBJECTS("Objects"),
    DATA("Data");

    final String title;

    In(String title) {
      this.title = title;
    }
  }

  static final List<In> INS = List.of(In.values());
  static final List<Search.Match> MATCHES = List.of(Search.Match.values());

  /** Why a call failed: a title and what the server said. */
  record Problem(String title, String text) {}

  /** A row of the results of a search in data: a column's hits, or why a table was not searched. */
  record Hit(Candidate table, String column, int rows, boolean more, String failure) {}

  final UUID id;
  final Container scope;
  In in = In.OBJECTS;
  String text = "";
  Search.Match match = Search.Match.CONTAINS;
  boolean caseSensitive;
  boolean others; // other types as their text, in data
  final Menu.State matchMenu = new Menu.State();

  List<Found> found; // of the last search of objects, or null before one
  Search searched; // the last search that ran or runs, or null
  boolean searchedOthers;
  List<Candidate> candidates; // the tables to choose from, null until read
  boolean[] chosen = new boolean[0]; // by candidate
  final List<Hit> hits = new ArrayList<>(); // of the last search in data
  List<Candidate> queue = List.of(); // the tables of the search in data under way or last
  int done; // of them searched
  boolean cancelled; // the last search in data was cancelled
  Lane.Asked asked; // the call under way, or null
  private String asking; // what it is, as the status line says it
  Problem problem; // why the last call failed, or null
  boolean tried; // the user tried to search with what cannot be searched for
  String said; // what the tab says for a while in its status line, or null
  private int says;

  final DataGrid.Selection results = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final DataGrid.Selection tables = new DataGrid.Selection(DataGrid.Mode.CELLS);
  private final MonitorGrids.Widths objectWidths = new MonitorGrids.Widths();
  private final MonitorGrids.Widths hitWidths = new MonitorGrids.Widths();
  private final MonitorGrids.Widths tableWidths = new MonitorGrids.Widths();

  private Lane lane; // the search's own session, or null while it has none
  private boolean closed;
  private final State state; // for close, which is called without one

  FindTab(State state, UUID id, Container scope) {
    this.state = state;
    this.id = id;
    this.scope = scope;
  }

  /** Whether the server is one of documents, whose search in data has no other types to choose. */
  private boolean documents(State s) {
    var profile = s.profile(id);
    return profile != null && profile.provider().keepsDocuments();
  }

  /** Whether the case of a text in data follows each column's collation, so it cannot be asked. */
  boolean caseByCollation(State s) {
    var profile = s.profile(id);
    return in == In.DATA
        && profile != null
        && switch (profile.provider().traits()) {
          case Dialect d -> d.find().caseByCollation();
          case DocumentDialect d -> false;
        };
  }

  /** The schema or database as its name tells it, with the database before a schema. */
  String place() {
    return switch (scope) {
      case Schema x -> x.database().name() + "." + x.name();
      case Database x -> x.name();
    };
  }

  /** A table as the results name it: with its schema where the search spans a database's. */
  private String name(Table table) {
    return scope instanceof Database && table.container() instanceof Schema x
        ? x.name() + "." + table.name()
        : table.name();
  }

  // The session

  /** The tab's session, opened where it has none; null while the tree's connection is closed. */
  private Lane lane(State s) {
    if (lane != null && lane.broken()) {
      closeSession(s);
    }
    if (lane == null && !closed) {
      var session = s.catalog.session(id, scope.database().name());
      if (session == null) {
        return null;
      }
      Arrays.fill(session.password(), '\0'); // nothing here masks it
      lane = session.lane();
    }
    return lane;
  }

  /** Whether it has a session of its own. */
  boolean connected() {
    return lane != null;
  }

  /** Whether a call is under way. */
  boolean busy() {
    return asked != null;
  }

  // Handlers

  /** Shows the objects or the data; the data's tables are read the first time. */
  void show(State s, In next) {
    in = next;
    tried = false;
    if (next == In.DATA && candidates == null && asked == null) {
      readTables(s);
    }
  }

  /** Reads the tables and views to choose from, with their sizes; the tables are chosen. */
  void readTables(State s) {
    if (asked != null) {
      return;
    }
    var lane = lane(s);
    if (lane == null) {
      return; // the note says that it is not connected
    }
    problem = null;
    asking = "Reading the tables of " + place() + "…";
    asked = lane.ask(
        s.app,
        "read the tables to search",
        Lane.Priority.VIEW,
        link -> link.candidates(scope),
        read -> {
          asked = null;
          candidates = read;
          chosen = new boolean[read.size()];
          for (int i = 0; i < chosen.length; i++) {
            chosen[i] = read.get(i).table().kind() == Table.Kind.TABLE;
          }
          tables.reset();
        },
        error -> failed(s, error, "The tables could not be read"));
  }

  /** Chooses all tables to search, or none. */
  void chooseAll(boolean all) {
    Arrays.fill(chosen, all);
  }

  /** Why the search cannot start, as a sentence; null where it can. */
  String why(State s) {
    var problem = Search.problem(text, match);
    if (problem != null) {
      return problem;
    }
    if (in == In.DATA && candidates != null && chosen().isEmpty()) {
      return "Choose a table to search.";
    }
    return null;
  }

  private List<Candidate> chosen() {
    var list = new ArrayList<Candidate>();
    for (int i = 0; candidates != null && i < candidates.size(); i++) {
      if (chosen[i]) {
        list.add(candidates.get(i));
      }
    }
    return list;
  }

  /** Find: searches the objects, or the chosen tables one after another. */
  void find(State s) {
    if (asked != null) {
      return;
    }
    if (why(s) != null) {
      tried = true;
      return;
    }
    tried = false;
    if (in == In.DATA && candidates == null) {
      readTables(s);
      return;
    }
    var lane = lane(s);
    if (lane == null) {
      return;
    }
    problem = null;
    var search = new Search(text, match, caseSensitive && !caseByCollation(s));
    searched = search;
    results.reset();
    if (in == In.OBJECTS) {
      asking = "Searching the objects of " + place() + "…";
      asked = lane.ask(
          s.app,
          "find in the objects",
          Lane.Priority.VIEW,
          link -> link.objects(scope, search),
          read -> {
            asked = null;
            found = read;
            say(s, count(read.size(), "object", "objects") + " found");
          },
          error -> failed(s, error, "The objects could not be searched"));
      return;
    }
    searchedOthers = others && !documents(s);
    queue = chosen();
    done = 0;
    cancelled = false;
    hits.clear();
    next(s, lane);
  }

  // Searches the next table of the queue, or ends.
  private void next(State s, Lane lane) {
    if (done >= queue.size()) {
      asked = null;
      int with = (int) hits.stream()
          .filter(h -> h.failure() == null)
          .map(Hit::table)
          .distinct()
          .count();
      say(s, "Hits in " + count(with, "table", "tables") + " of " + queue.size());
      return;
    }
    var table = queue.get(done);
    var search = searched;
    boolean as = searchedOthers;
    asking = "Searching " + name(table.table());
    asked = lane.ask(
        s.app,
        "search " + table.table().name(),
        Lane.Priority.VIEW,
        link -> link.search(table, search, as),
        got -> {
          for (var hit : got.columns()) {
            hits.add(new Hit(table, hit.column(), hit.rows(), got.more(), null));
          }
          done++;
          next(s, lane);
        },
        error -> {
          if (error instanceof InterruptedException) {
            asked = null;
            return; // cancelled, or its session ended under it
          }
          // A table the user may not read, or that is gone, is passed over; anything else stops.
          if (error instanceof QueryException q
              && (q.failure() == QueryException.Failure.ACCESS_DENIED
                  || q.failure() == QueryException.Failure.NOT_FOUND)) {
            hits.add(new Hit(table, "", 0, false, detail(q)));
            done++;
            next(s, lane);
            return;
          }
          failed(s, error, "The search stopped at " + name(table.table()));
        });
  }

  /** Cancel: what runs is cancelled on the server, and what was found stays. */
  void cancel(State s) {
    if (asked == null) {
      return;
    }
    asked.cancel();
    asked = null;
    if (searched != null && in == In.DATA && done < queue.size()) {
      cancelled = true;
      say(s, "Cancelled after " + done + " of " + count(queue.size(), "table", "tables"));
    }
  }

  private void failed(State s, Exception error, String title) {
    asked = null;
    if (error instanceof InterruptedException) {
      return; // cancelled, or its session ended under it
    }
    problem = new Problem(title, detail(error));
    if (lane != null && lane.broken()) {
      closeSession(s); // the next search opens a session anew
    }
  }

  private static String detail(Exception error) {
    return switch (error) {
      case QueryException q -> q.detail().isEmpty() ? q.failure().toString() : q.detail();
      case ConnectionException c -> c.detail().isEmpty() ? c.failure().toString() : c.detail();
      default -> error.getMessage() == null ? error.toString() : error.getMessage();
    };
  }

  /** Opens a result: a table, view or column in the tree, a definition or a hit's rows. */
  void open(State s, int row) {
    if (in == In.OBJECTS) {
      if (found != null && row >= 0 && row < found.size()) {
        open(s, found.get(row));
      }
    } else if (row >= 0 && row < hits.size()) {
      open(s, hits.get(row));
    }
  }

  private void open(State s, Found f) {
    if (f.showsDefinition()) {
      s.openDefinition(
          id,
          scope.database().name(),
          f.definition(),
          f.inDefinition() ? f.start() : 0,
          f.inDefinition() ? f.end() : 0);
      return;
    }
    if (f.table() == null) {
      return;
    }
    s.catalog.reveal(
        s, new Node.OfTable(id, f.table()), f.kind() == Found.Kind.COLUMN ? f.name() : null);
    // The tree is the main window's, which may lie behind.
    var window = s.app.windows().of(s.app);
    if (window != null) {
      window.raise();
    }
  }

  private void open(State s, Hit hit) {
    if (hit.failure() != null || searched == null) {
      return;
    }
    var statement = documents(s)
        ? DocumentFinder.rows(hit.table().table(), hit.column(), searched)
        : s.profile(id) == null
            ? Optional.<String>empty()
            : Finder.rows(
                s.profile(id).provider(), hit.table(), hit.column(), searched, searchedOthers);
    statement.ifPresent(sql -> s.openRows(id, scope.database().name(), sql));
  }

  /** Says something in the tab's status line for a while. */
  void say(State s, String text) {
    said = text;
    int at = ++says;
    s.app.after(4, () -> {
      if (says == at) {
        said = null;
      }
    });
  }

  /**
   * Its session ends, as its connection was disconnected or changed: what runs is given up and the
   * connection closed, which Exit waits for. What was found stays.
   */
  void end(State s) {
    if (asked != null) {
      asked.cancel();
      asked = null;
      if (in == In.DATA && searched != null && done < queue.size()) {
        cancelled = true;
      }
    }
    closeSession(s);
  }

  private void closeSession(State s) {
    if (lane != null) {
      s.closing();
      var closing = lane.close(ended -> {});
      s.app.background(
          "close the search's session",
          () -> {
            closing.join();
            return null;
          },
          done -> s.closed(),
          e -> s.closed());
      lane = null;
    }
  }

  @Override
  public void close() {
    closed = true;
    end(state);
  }

  // The view

  /** The tab's content: its toolbar, why it cannot search, what it found, and its status line. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var profile = s.profile(id);
    String where = profile == null ? place() : State.name(profile) + ": " + place();
    boolean running = asked != null;
    var actions = new ArrayList<El>();
    var problem = why(s);
    El field = TextField.textField(text, "Find", v -> {
          text = v;
          tried = false;
        })
        .key("find-text")
        .label("Find")
        .w(8 * t.size().control());
    if (tried && problem != null) {
      field = TextField.invalid(field);
    }
    actions.add(row().child(TextField.withIcon(field, Symbol.SEARCH)).onKey(k -> {
      if (k.name().equals("Enter") && k.modifiers() == 0) {
        find(s);
        return true;
      }
      return false;
    }));
    actions.add(Select.select(MATCHES, match, FindTab::title, matchMenu, m -> {
          match = m;
          tried = false;
        })
        .key("match")
        .label("Match")
        .w(4 * t.size().control()));
    boolean collation = caseByCollation(s);
    El sensitive = Checkbox.checkbox(
            caseSensitive && !collation,
            "Match case",
            collation ? null : () -> caseSensitive = !caseSensitive)
        .key("case");
    actions.add(
        collation
            ? Tooltip.tooltip(sensitive, "Case follows each column's collation on this server")
            : sensitive);
    if (in == In.DATA && !documents(s)) {
      actions.add(Checkbox.checkbox(others, "Other types as text", () -> others = !others)
          .key("others"));
    }
    actions.add(
        Segmented.segmented(INS, in, x -> x.title, x -> show(s, x)).key("in").label("Find in"));
    actions.add(
        running
            ? button(SECONDARY, "Cancel", () -> cancel(s), Button.options().icon(Symbol.DISMISS))
                .key("cancel")
            : button(PRIMARY, "Find", () -> find(s), Button.options().icon(Symbol.SEARCH))
                .key("find"));
    El top = Card.toolbar(where, null, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    El note = note(s);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note).key("note"));
    }
    if (in == In.OBJECTS) {
      content.child(objects(s, app));
    } else {
      content.child(data(s, app, t));
    }
    El count = Label.label(Label.Variant.MUTED, count(s)).key("count").ellipsis();
    return content.child(Card.footer(count, footer(s, t)).key("footer"));
  }

  static String title(Search.Match match) {
    return switch (match) {
      case CONTAINS -> "Contains";
      case EQUALS -> "Equals";
      case PATTERN -> "Pattern";
    };
  }

  /** Why it cannot search, or what failed, with what the user can do; null where nothing did. */
  private El note(State s) {
    if (!s.catalog.connected(id)) {
      boolean connecting = s.catalog.active(id);
      var lost = s.catalog.lost(id);
      return Callout.callout(
          Callout.Kind.WARNING,
          connecting ? "Connecting…" : "Disconnected",
          lost != null ? CatalogTree.why(lost) : "Its connection is closed.",
          connecting
              ? new El[0]
              : new El[] {button(SECONDARY, "Connect", () -> s.catalog.connect(s, id))});
    }
    if (tried && why(s) != null) {
      return Callout.callout(Callout.Kind.WARNING, why(s), "").key("invalid");
    }
    if (problem != null) {
      return Callout.callout(
              Callout.Kind.DANGER,
              problem.title(),
              problem.text(),
              button(SECONDARY, "Dismiss", () -> problem = null))
          .key("problem");
    }
    return null;
  }

  private El objects(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    if (found == null || found.isEmpty()) {
      return waiting(t)
          .orElseGet(() -> EmptyState.emptyState(
                  Symbol.SEARCH,
                  found == null ? "Find objects by name or definition" : "Nothing found",
                  found == null
                      ? "Tables, views and columns by their names, and views, routines and"
                          + " triggers also by their definitions, in " + place() + "."
                      : "No object of " + place() + " matches.",
                  null)
              .key("no-objects"));
    }
    var shown = List.copyOf(found);
    var w = objectWidths;
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column(w, 0, 4, "Name", 10, i -> shown.get(i).name())
        .cellIcon(i -> DataGrid.CellIcon.before(icon(shown.get(i).kind()), Icon.Variant.MUTED)));
    columns.add(column(w, 1, 4, "Kind", 5, i -> kind(shown.get(i).kind())));
    columns.add(column(w, 2, 4, "In", 8, i -> in(shown.get(i))));
    columns.add(column(w, 3, 4, "Match", 18, i -> match(shown.get(i))));
    return DataGrid.dataGrid(
            columns, shown.size(), options(w, results, app).onActivate(r -> open(s, r)))
        .key("results")
        .label("Results")
        .grow(1);
  }

  private String in(Found f) {
    if (f.table() != null && f.kind() != Found.Kind.TABLE && f.kind() != Found.Kind.VIEW) {
      return name(f.table());
    }
    return f.container() instanceof Schema x ? x.name() : f.container().name();
  }

  /** Where it matched: its name, or the line of its definition around the match. */
  static String match(Found f) {
    if (!f.inDefinition()) {
      return "Name";
    }
    var text = f.definition();
    int from = Math.max(0, f.start() - 30);
    int to = Math.min(text.length(), f.end() + 30);
    var around = text.substring(from, to).replaceAll("\\s+", " ").strip();
    return "Definition: " + (from > 0 ? "…" : "") + around + (to < text.length() ? "…" : "");
  }

  static String kind(Found.Kind kind) {
    return switch (kind) {
      case TABLE -> "Table";
      case VIEW -> "View";
      case COLUMN -> "Column";
      case FUNCTION -> "Function";
      case PROCEDURE -> "Procedure";
      case TRIGGER -> "Trigger";
    };
  }

  private static Symbol icon(Found.Kind kind) {
    return switch (kind) {
      case TABLE -> Symbol.TABLE;
      case VIEW -> Symbol.EYE;
      case COLUMN -> Symbol.COLUMN_SINGLE;
      case FUNCTION, PROCEDURE -> Symbol.CODE;
      case TRIGGER -> Symbol.FLASH;
    };
  }

  // The tables to choose from above, what was found below.
  private El data(State s, App<State> app, Theme t) {
    if (candidates == null) {
      return waiting(t)
          .orElseGet(() -> EmptyState.emptyState(
                  Symbol.TABLE,
                  "No tables read",
                  "The tables of " + place() + " are read before a search of their data.",
                  button(SECONDARY, "Read tables", () -> readTables(s)).key("read-tables"))
              .key("no-tables"));
    }
    var shown = List.copyOf(candidates);
    var w = tableWidths;
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(new DataGrid.Column(
            "Search", width(w, 0, 4, 4), DataGrid.DataType.BOOLEAN, i -> String.valueOf(chosen[i]))
        .editable());
    columns.add(column(
            w,
            1,
            4,
            documents(s) ? "Collection" : "Table",
            12,
            i -> name(shown.get(i).table()))
        .cellIcon(i -> DataGrid.CellIcon.before(
            shown.get(i).table().kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE,
            Icon.Variant.MUTED)));
    columns.add(new DataGrid.Column(
        "Rows",
        width(w, 2, 4, 5),
        DataGrid.Align.RIGHT,
        false,
        false,
        i -> shown.get(i).rows().stream()
            .mapToObj(n -> String.format(Locale.ROOT, "%,d", n))
            .findFirst()
            .orElse("")));
    columns.add(new DataGrid.Column(
        "Size",
        width(w, 3, 4, 4),
        DataGrid.Align.RIGHT,
        false,
        false,
        i -> shown.get(i).bytes().stream().mapToObj(Fetch::size).findFirst().orElse("")));
    var options = options(w, tables, app).onCommit((row, column, value) -> {
      if (asked == null && row >= 0 && row < chosen.length) {
        chosen[row] = Boolean.parseBoolean(value);
      }
      return null;
    });
    El chooser = col()
        .grow(1)
        .minSize(0, 0)
        .child(row()
            .padding(t.space().s(), t.space().m())
            .gap(t.space().s())
            .align(Style.Align.CENTER)
            .child(
                Label.label(Label.Variant.MUTED, sizes()).key("sizes").grow(1).ellipsis())
            .child(button(SECONDARY, "All", asked == null ? () -> chooseAll(true) : null)
                .key("all-tables"))
            .child(button(SECONDARY, "None", asked == null ? () -> chooseAll(false) : null)
                .key("no-tables"))
            .child(button(
                    SECONDARY,
                    "Refresh",
                    asked == null ? () -> readTables(s) : null,
                    Button.options().icon(Symbol.ARROW_CLOCKWISE))
                .key("refresh-tables")))
        .child(DataGrid.dataGrid(columns, shown.size(), options)
            .key("tables")
            .label("Tables")
            .grow(1));
    El found;
    if (searched == null || queue.isEmpty()) {
      found = EmptyState.emptyState(
              Symbol.SEARCH,
              "Find values in the data",
              "Each table chosen is searched in the columns that can hold the value: text for a"
                  + " text, numbers for a number, and the others as text where chosen.",
              null)
          .key("no-hits");
    } else if (hits.isEmpty()) {
      found = col()
          .grow(1)
          .center()
          .key("no-hits")
          .child(Label.label(
              Label.Variant.MUTED,
              asked != null ? "Nothing found yet" : "Nothing found in " + done + " tables"));
    } else {
      var shownHits = List.copyOf(hits);
      var h = hitWidths;
      var hitColumns = new ArrayList<DataGrid.Column>();
      hitColumns.add(column(
          h,
          0,
          3,
          documents(s) ? "Collection" : "Table",
          12,
          i -> name(shownHits.get(i).table().table())));
      hitColumns.add(column(h, 1, 3, documents(s) ? "Field" : "Column", 10, i -> {
            var hit = shownHits.get(i);
            return hit.failure() != null ? "Not searched: " + hit.failure() : hit.column();
          })
          .cellIcon(i -> shownHits.get(i).failure() != null
              ? DataGrid.CellIcon.before(Symbol.WARNING, Icon.Variant.WARNING)
              : null));
      hitColumns.add(new DataGrid.Column(
          "Hits",
          width(h, 2, 3, 4),
          DataGrid.Align.RIGHT,
          false,
          false,
          i -> hits(shownHits.get(i))));
      found = DataGrid.dataGrid(
              hitColumns, shownHits.size(), options(h, results, app).onActivate(r -> open(s, r)))
          .key("results")
          .label("Results")
          .grow(1);
    }
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(chooser)
        .child(Divider.divider())
        .child(col().grow(1).minSize(0, 0).child(found));
  }

  /** A column's hits, with a + where the table had more matching rows than were counted. */
  static String hits(Hit hit) {
    if (hit.failure() != null) {
      return "";
    }
    return String.format(Locale.ROOT, "%,d", hit.rows()) + (hit.more() ? "+" : "");
  }

  /** The tables chosen, and their estimated size. */
  String sizes() {
    var list = chosen();
    long rows = 0;
    long bytes = 0;
    boolean unknownRows = false;
    boolean unknownBytes = false;
    for (var c : list) {
      if (c.rows().isPresent()) {
        rows += c.rows().getAsLong();
      } else {
        unknownRows = true;
      }
      if (c.bytes().isPresent()) {
        bytes += c.bytes().getAsLong();
      } else {
        unknownBytes = true;
      }
    }
    var text = new StringBuilder(
        list.size() + " of " + count(candidates.size(), "table", "tables") + " chosen");
    if (!list.isEmpty()) {
      text.append(", about ")
          .append(String.format(Locale.ROOT, "%,d", rows))
          .append(rows == 1 ? " row" : " rows");
      if (bytes > 0) {
        text.append(" in ").append(Fetch.size(bytes));
      }
      if (unknownRows || unknownBytes) {
        text.append(" and more not estimated");
      }
    }
    return text.toString();
  }

  // While a call runs and nothing was found yet: what it does.
  private Optional<El> waiting(Theme t) {
    if (asked == null) {
      return Optional.empty();
    }
    return Optional.of(col()
        .grow(1)
        .center()
        .gap(t.space().m())
        .role("status")
        .label(asking)
        .key("waiting")
        .child(Progress.spinner())
        .child(Label.label(Label.Variant.MUTED, asking)));
  }

  /** How many it found, or of a search in data how far it is. */
  private String count(State s) {
    if (in == In.OBJECTS) {
      return found == null ? "" : count(found.size(), "object", "objects");
    }
    if (candidates == null) {
      return "";
    }
    if (searched == null || queue.isEmpty()) {
      return count(candidates.size(), "table", "tables");
    }
    long columns = hits.stream().filter(h -> h.failure() == null).count();
    return count((int) columns, "column", "columns") + " with hits, " + done + " of "
        + count(queue.size(), "table", "tables") + " searched"
        + (cancelled ? ", cancelled" : "");
  }

  private El[] footer(State s, Theme t) {
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    if (asked != null && in == In.DATA && searched != null && done < queue.size()) {
      end.add(Progress.labeled(
              asking,
              queue.isEmpty() ? 0 : (double) done / queue.size(),
              done + " of " + queue.size())
          .key("progress")
          .w(10 * t.size().control()));
    }
    return end.toArray(El[]::new);
  }

  static String count(int n, String one, String many) {
    return String.format(Locale.ROOT, "%,d", n) + " " + (n == 1 ? one : many);
  }

  private static DataGrid.Options options(
      MonitorGrids.Widths w, DataGrid.Selection selection, App<State> app) {
    return DataGrid.options()
        .select(selection)
        .copy(app.clipboard())
        .onResize((c, width) -> w.of[c] = width);
  }

  private static float width(MonitorGrids.Widths w, int index, int columns, float controls) {
    if (w.of == null || w.of.length != columns) {
      w.of = new float[columns];
      Arrays.fill(w.of, -1);
    }
    return w.of[index] < 0 ? controls * Theme.CURRENT.get().size().control() : w.of[index];
  }

  private static DataGrid.Column column(
      MonitorGrids.Widths w,
      int index,
      int columns,
      String title,
      float controls,
      java.util.function.IntFunction<String> text) {
    return new DataGrid.Column(
        title, width(w, index, columns, controls), DataGrid.Align.LEFT, false, false, text);
  }
}
