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
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
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
import java.util.function.IntFunction;

/**
 * A search of a database or schema of a connection: the names and definitions of its objects, or
 * the values in its tables, on a session of the tab's own, which it opens for its first search,
 * read-only at a server of SQL as the tree's, and which nothing else uses. A search in data reads the tables and their estimated sizes first,
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

  private static final String TABLE = "Table";

  /** Why a call failed: a title and what the server said. */
  record Problem(String title, String text) {}

  /** A row of the results of a search in data: a column's hits, or a table not searched. */
  sealed interface Result permits Hit, Skipped {
    Candidate table();
  }

  /** A column's hits: the rows counted, and whether the table had more. */
  record Hit(Candidate table, String column, int rows, boolean more) implements Result {}

  /** A table that was not searched, and why, as the server said. */
  record Skipped(Candidate table, String why) implements Result {}

  final UUID id;
  final Container scope;
  In in = In.OBJECTS;
  String text = "";
  Search.Match match = Search.Match.CONTAINS;
  boolean caseSensitive;
  boolean others; // other types as their text, in data
  final Menu.State matchMenu = new Menu.State();

  List<Found> found; // of the last search of objects, or null before one
  Search searched; // the last search in data that ran or runs, or null
  boolean searchedOthers;
  List<Candidate> candidates; // the tables to choose from, null until read
  boolean[] chosen = new boolean[0]; // by candidate
  final List<Result> hits = new ArrayList<>(); // of the last search in data
  List<Candidate> queue = List.of(); // the tables of the search in data under way or last
  int done; // of them searched
  boolean cancelled; // the last search in data was cancelled
  Lane.Asked asked; // the call under way, or null
  private Lane.Asked searching; // the last call that searched a table, which may be the one asked
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
  private char[] password; // a copy, for masking it in what the server says; wiped as it ends
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

  /** The tab's session, opened where it has none; empty while the tree's connection is closed. */
  private Optional<Lane> lane(State s) {
    if (lane != null && lane.broken()) {
      closeSession(s);
    }
    if (lane == null && !closed) {
      var session = s.catalog.browsingSession(s, id, scope.database().name());
      if (session == null) {
        return Optional.empty();
      }
      password = session.password();
      lane = session.lane();
    }
    return Optional.ofNullable(lane);
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
    // Where it has no session, the note says that it is not connected.
    lane(s).ifPresent(session -> {
      problem = null;
      asking = "Reading the tables of " + place() + "…";
      asked = session.ask(
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
    });
  }

  /** Chooses all tables to search, or none. */
  void chooseAll(boolean all) {
    Arrays.fill(chosen, all);
  }

  /**
   * Why the search cannot start, as a sentence; empty where it can. A pattern in data is the
   * server's to read.
   */
  Optional<String> why() {
    var reader = in == In.DATA ? Search.Reader.SERVER : Search.Reader.HOORIDB;
    var invalid = Search.problem(text, match, reader);
    if (invalid.isEmpty() && in == In.DATA && candidates != null && chosen().isEmpty()) {
      return Optional.of("Choose a table to search.");
    }
    return invalid;
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
    if (why().isPresent()) {
      tried = true;
      return;
    }
    tried = false;
    if (in == In.DATA && candidates == null) {
      readTables(s);
      return;
    }
    lane(s).ifPresent(session -> find(s, session));
  }

  private void find(State s, Lane session) {
    problem = null;
    var search = new Search(text, match, caseSensitive);
    results.reset();
    if (in == In.OBJECTS) {
      asking = "Searching the objects of " + place() + "…";
      asked = session.ask(
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
    // Set apart from a search of objects, which leaves the hits and how they were found.
    searched = search;
    searchedOthers = others && !documents(s);
    queue = chosen();
    done = 0;
    cancelled = false;
    hits.clear();
    next(s, session);
  }

  // Searches the next table of the queue, or ends.
  private void next(State s, Lane session) {
    if (done >= queue.size()) {
      asked = null;
      int with = (int) hits.stream()
          .filter(Hit.class::isInstance)
          .map(Result::table)
          .distinct()
          .count();
      say(s, "Hits in " + tableCount(with) + " of " + queue.size());
      return;
    }
    var table = queue.get(done);
    var search = searched;
    boolean as = searchedOthers;
    asking = "Searching " + name(table.table());
    asked = searching = session.ask(
        s.app,
        "search " + table.table().name(),
        Lane.Priority.VIEW,
        link -> link.search(table, search, as),
        got -> {
          for (var hit : got.columns()) {
            hits.add(new Hit(table, hit.column(), hit.rows(), got.more()));
          }
          done++;
          next(s, session);
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
            hits.add(new Skipped(table, detail(q)));
            done++;
            next(s, session);
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
    boolean data = asked == searching;
    asked.cancel();
    asked = null;
    if (data) {
      cancelled = true;
      say(s, "Cancelled after " + done + " of " + tableCount(queue.size()));
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

  // The server's words, withheld where they hold the password.
  private String detail(Exception error) {
    return ConsoleTab.words(
        password,
        switch (error) {
          case QueryException q -> q.detail().isEmpty() ? q.failure().toString() : q.detail();
          case ConnectionException c -> c.detail().isEmpty() ? c.failure().toString() : c.detail();
          default -> error.getMessage() == null ? error.toString() : error.getMessage();
        });
  }

  /** Opens a result: a table, view or column in the tree, a definition or a hit's rows. */
  void open(State s, int row) {
    if (in == In.OBJECTS) {
      if (found != null && row >= 0 && row < found.size()) {
        open(s, found.get(row));
      }
    } else if (row >= 0 && row < hits.size() && hits.get(row) instanceof Hit hit) {
      open(s, hit);
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

  // Its table's tab, filtered to the rows the search counted, beside the search.
  private void open(State s, Hit hit) {
    var profile = s.profile(id);
    if (searched == null || profile == null) {
      return;
    }
    var provider = profile.provider();
    var where = provider.keepsDocuments()
        ? Optional.of(DocumentFinder.filter(hit.column(), searched))
        : Finder.where(provider, hit.table(), hit.column(), searched, searchedOthers);
    var group = s.tabs.groupOf(this);
    where.ifPresent(w ->
        s.openWhere(id, hit.table().table(), w, group == null ? s.tabs.main().active() : group));
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
      cancelled |= asked == searching;
      asked.cancel();
      asked = null;
    }
    closeSession(s);
  }

  private void closeSession(State s) {
    if (lane != null) {
      s.closing();
      var closing = lane.close(_ -> {});
      s.app.background(
          "close the search's session",
          () -> {
            closing.join();
            return null;
          },
          _ -> s.closed(),
          _ -> s.closed());
      lane = null;
      Arrays.fill(password, '\0');
      password = null;
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
    El top = Card.toolbar(where, null, actions(s, t).toArray(El[]::new));
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
    El count = Label.label(Label.Variant.MUTED, summary()).key("count").ellipsis();
    return content.child(Card.footer(count, footer(t)).key("footer"));
  }

  // The toolbar's controls: what to find and how, where, and Find or Cancel.
  private List<El> actions(State s, Theme t) {
    var actions = new ArrayList<El>();
    El field = TextField.textField(text, "Find", v -> {
          text = v;
          tried = false;
        })
        .key("find-text")
        .label("Find")
        .w(8 * t.size().control());
    if (tried && why().isPresent()) {
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
    actions.add(Checkbox.checkbox(caseSensitive, "Match case", () -> caseSensitive = !caseSensitive)
        .key("case"));
    if (in == In.DATA && !documents(s)) {
      actions.add(Checkbox.checkbox(others, "Other types as text", () -> others = !others)
          .key("others"));
    }
    actions.add(
        Segmented.segmented(INS, in, x -> x.title, x -> show(s, x)).key("in").label("Find in"));
    actions.add(
        asked != null
            ? button(SECONDARY, "Cancel", () -> cancel(s), Button.options().icon(Symbol.DISMISS))
                .key("cancel")
            : button(PRIMARY, "Find", () -> find(s), Button.options().icon(Symbol.SEARCH))
                .key("find"));
    return actions;
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
    var invalid = why();
    if (tried && invalid.isPresent()) {
      return Callout.callout(Callout.Kind.WARNING, invalid.get(), "").key("invalid");
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
    columns.add(column(w, 3, 4, "Match", 18, i -> matchText(shown.get(i))));
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
  static String matchText(Found f) {
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
      case TABLE -> TABLE;
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
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(chooser(s, app, t))
        .child(Divider.divider())
        .child(col().grow(1).minSize(0, 0).child(dataFound(s, app)));
  }

  // What a table is called: a collection on a server of documents.
  private String tableTitle(State s) {
    return documents(s) ? "Collection" : TABLE;
  }

  // The tables, each with whether it is searched and its size, and the buttons that choose them.
  private El chooser(State s, App<State> app, Theme t) {
    var shown = List.copyOf(candidates);
    var w = tableWidths;
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(new DataGrid.Column(
            "Search", width(w, 0, 4, 4), DataGrid.DataType.BOOLEAN, i -> String.valueOf(chosen[i]))
        .editable());
    columns.add(column(w, 1, 4, tableTitle(s), 12, i -> name(shown.get(i).table()))
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
    return col()
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
  }

  // What the search in data found, or that it found nothing.
  private El dataFound(State s, App<State> app) {
    if (searched == null || queue.isEmpty()) {
      return EmptyState.emptyState(
              Symbol.SEARCH,
              "Find values in the data",
              "Each table chosen is searched in the columns that can hold the value: text for a"
                  + " text, numbers for a number, and the others as text where chosen.",
              null)
          .key("no-hits");
    }
    if (hits.isEmpty()) {
      return col()
          .grow(1)
          .center()
          .key("no-hits")
          .child(Label.label(
              Label.Variant.MUTED,
              asked != null ? "Nothing found yet" : "Nothing found in " + tableCount(done)));
    }
    var shown = List.copyOf(hits);
    var h = hitWidths;
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(
        column(h, 0, 3, tableTitle(s), 12, i -> name(shown.get(i).table().table())));
    columns.add(column(h, 1, 3, documents(s) ? "Field" : "Column", 10, i -> switch (shown.get(i)) {
          case Hit hit -> hit.column();
          case Skipped skipped -> "Not searched: " + skipped.why();
        })
        .cellIcon(i -> shown.get(i) instanceof Skipped
            ? DataGrid.CellIcon.before(Symbol.WARNING, Icon.Variant.WARNING)
            : null));
    columns.add(new DataGrid.Column(
        "Hits", width(h, 2, 3, 4), DataGrid.Align.RIGHT, false, false, i -> hitText(shown.get(i))));
    return DataGrid.dataGrid(
            columns, shown.size(), options(h, results, app).onActivate(r -> open(s, r)))
        .key("results")
        .label("Results")
        .grow(1);
  }

  /** A column's hits, with a + where the table had more matching rows than were counted. */
  static String hitText(Result result) {
    if (!(result instanceof Hit hit)) {
      return "";
    }
    var rows = String.format(Locale.ROOT, "%,d", hit.rows());
    return hit.more() ? rows + "+" : rows;
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
    var out = new StringBuilder(list.size() + " of " + tableCount(candidates.size()) + " chosen");
    if (!list.isEmpty()) {
      out.append(", about ")
          .append(String.format(Locale.ROOT, "%,d", rows))
          .append(rows == 1 ? " row" : " rows");
      if (bytes > 0) {
        out.append(" in ").append(Fetch.size(bytes));
      }
      if (unknownRows || unknownBytes) {
        out.append(" and more not estimated");
      }
    }
    return out.toString();
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
  private String summary() {
    if (in == In.OBJECTS) {
      return found == null ? "" : count(found.size(), "object", "objects");
    }
    if (candidates == null) {
      return "";
    }
    if (searched == null || queue.isEmpty()) {
      return tableCount(candidates.size());
    }
    long columns = hits.stream().filter(Hit.class::isInstance).count();
    return count((int) columns, "column", "columns") + " with hits, " + done + " of "
        + tableCount(queue.size()) + " searched"
        + (cancelled ? ", cancelled" : "");
  }

  private El[] footer(Theme t) {
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

  private static String tableCount(int n) {
    return count(n, "table", "tables");
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
      IntFunction<String> text) {
    return new DataGrid.Column(
        title, width(w, index, columns, controls), DataGrid.Align.LEFT, false, false, text);
  }
}
