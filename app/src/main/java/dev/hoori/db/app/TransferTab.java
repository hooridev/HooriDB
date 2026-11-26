package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.NumberField;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.transfer.Batch;
import dev.hoori.db.core.transfer.Copier;
import dev.hoori.db.core.transfer.Report;
import dev.hoori.db.core.transfer.Selection;
import dev.hoori.db.core.transfer.Source;
import dev.hoori.db.core.transfer.Target;
import dev.hoori.db.core.transfer.Transfer;
import dev.hoori.db.core.transfer.Transfer.Commit;
import dev.hoori.db.core.transfer.Transfer.Existing;
import dev.hoori.db.core.transfer.Transfer.OnError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Copy tables: the rows of tables of one connection's database or schema into the tables of the
 * same names of another connection, database or schema of the same provider, in a tab. The source
 * and the target each have a session and lane of the tab's own, never the tree's, opened on their
 * database once their connection is connected; their databases, schemas and tables are read on it,
 * and the copy runs on it, as core's {@link Copier}. A copy shows how far it is, can be cancelled,
 * and ends with what was copied and what failed. While a copy runs, what would end it, as closing
 * the tab, disconnecting or deleting either connection or closing HooriDB, asks first, and goes on
 * once the copy has ended; ending a session cancels the copy, and the server rolls back what was
 * not committed. Only the app's thread uses it.
 */
final class TransferTab implements Tab, Guard.Guarded {

  static final String COPY = "Copy";

  /** What the tables of the target are given where they have rows, in the order offered. */
  static final List<Existing> EXISTING = List.of(Existing.values());

  /** What the batches of the run are committed by, in the order offered. */
  static final List<Commit> COMMITS = List.of(Commit.values());

  /** What the run does at an error, in the order offered. */
  static final List<OnError> ON_ERRORS = List.of(OnError.values());

  /** The most rows a batch may hold here. */
  static final int MOST_BATCH = 100_000;

  /** One end of the copy: a connection, its database and schema, and what was read of them. */
  static final class End {
    final String role; // "Source" or "Target", as its controls are labeled
    UUID id; // the connection, or null for none
    String database = ""; // as chosen, or the connection's own where empty
    String schema = ""; // as chosen, or the first where empty; empty without schemas
    Lane lane; // its session, or null while it has none
    private String opened; // the database the session was opened on
    List<String> databases; // the connection's, or null before they are read
    List<String> schemas; // the database's, or null before they are read; none without schemas
    List<Table> tables; // the database's or schema's, or null before they are read
    Lane.Asked reading; // what is read now, or null
    String problem; // why it cannot be read, or null
    final Menu.State connections = new Menu.State();
    final Menu.State databaseMenu = new Menu.State();
    final Menu.State schemaMenu = new Menu.State();

    End(String role, UUID id) {
      this.role = role;
      this.id = id;
    }

    /** Where its tables are, as chosen and read; null while that is not known. */
    Container container(Provider provider) {
      if (database.isEmpty()) {
        return null;
      }
      var of = new Database(database, false);
      if (!provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN)) {
        return of;
      }
      return schema.isEmpty() ? null : new Schema(of, schema, false);
    }

    /**
     * Whether it is the same as the other, so that a copy would read what it writes: the same
     * database and schema of the same connection, or of another of the same server.
     */
    boolean same(State s, End other) {
      if (id == null
          || other.id == null
          || !database.equals(other.database)
          || !schema.equals(other.schema)) {
        return false;
      }
      if (id.equals(other.id)) {
        return true;
      }
      var mine = s.profile(id);
      var theirs = s.profile(other.id);
      return mine != null
          && theirs != null
          && mine.provider() == theirs.provider()
          && mine.host().equalsIgnoreCase(theirs.host())
          && mine.port() == theirs.port();
    }
  }

  // What a read of an end gave: its databases, the database taken, its schemas, the schema taken,
  // and its tables.
  private record Listing(
      List<String> databases,
      String database,
      List<String> schemas,
      String schema,
      List<Table> tables) {}

  final End source;
  final End target;
  private final Set<String> unchecked = new HashSet<>(); // the source's tables not to copy
  private final Map<String, Existing> existing = new HashMap<>(); // by table, where not STOP
  private final Map<String, Menu.State> existingMenus = new HashMap<>();
  final Menu.State allMenu = new Menu.State();
  Commit commit = Commit.TABLE;
  OnError onError = OnError.STOP;
  int batch = Transfer.BATCH;
  Work running; // the copy under way, or null
  private AtomicReference<Report> last = new AtomicReference<>(); // as the copy last told it
  Report report; // how far the copy is, or how the last ended; null before one
  boolean cancelling; // the copy was cancelled and has not ended yet
  String failed; // why the last copy could not run, or null
  private boolean closed;
  private final State state; // for close, which is called without one

  TransferTab(State state, UUID id, Container container) {
    this.state = state;
    this.source = new End("Source", id);
    this.target = new End("Target", id);
    if (container != null) {
      source.database = container.database().name();
      target.database = source.database;
      if (container instanceof Schema schema) {
        source.schema = schema.name();
      }
    }
  }

  /** The connection the tab was opened on, which colors it. */
  UUID id() {
    return source.id;
  }

  /** Whether a copy runs, or the reads before it. */
  boolean busy() {
    return running != null || source.reading != null || target.reading != null;
  }

  /** Opens both ends' sessions where they have none and reads them. */
  void open(State s) {
    open(s, source);
    open(s, target);
  }

  /** The connection connected: an end on it that has no session opens one now. */
  void connected(State s, UUID id) {
    for (var end : List.of(source, target)) {
      if (id.equals(end.id) && end.lane == null) {
        open(s, end);
      }
    }
  }

  /** The connection ended: an end on it loses its session, and a copy on it is cancelled. */
  void ended(State s, UUID id) {
    for (var end : List.of(source, target)) {
      if (id.equals(end.id)) {
        if (running != null) {
          cancel();
        }
        closeSession(s, end);
        end.problem = "Its connection is not connected.";
      }
    }
  }

  /** Whether an end is on the connection. */
  @Override
  public boolean on(UUID id) {
    return id.equals(source.id) || id.equals(target.id);
  }

  /** Ends both sessions, as before HooriDB closes; the tab stays. */
  void end(State s) {
    if (running != null) {
      cancel();
    }
    closeSession(s, source);
    closeSession(s, target);
  }

  @Override
  public void close() {
    closed = true;
    end(state);
  }

  // Opens the end's session on its database, anew where it had one, and reads it.
  private void open(State s, End end) {
    if (closed) {
      return;
    }
    closeSession(s, end);
    end.databases = null;
    end.schemas = null;
    end.tables = null;
    end.problem = null;
    var profile = end.id == null ? null : s.profile(end.id);
    if (profile == null) {
      end.problem = "Choose a connection.";
      return;
    }
    var session = s.catalog.session(s, end.id, end.database);
    if (session == null) {
      end.problem = "Connect " + State.name(profile) + " first, in the tree.";
      return;
    }
    Arrays.fill(session.password(), '\0'); // nothing it sends is shown here
    end.lane = session.lane();
    end.opened = end.database;
    read(s, end);
  }

  private void closeSession(State s, End end) {
    if (end.reading != null) {
      end.reading.cancel();
      end.reading = null;
    }
    if (end.lane != null) {
      s.closing();
      var closing = end.lane.close(ended -> {});
      s.app.background(
          "close the copy's session",
          () -> {
            closing.join();
            return null;
          },
          done -> s.closed(),
          e -> s.closed());
      end.lane = null;
    }
  }

  // Reads the end's databases, schemas and tables, on its session, for what is chosen.
  private void read(State s, End end) {
    var profile = s.profile(end.id);
    if (end.lane == null || profile == null) {
      return;
    }
    if (end.reading != null) {
      end.reading.cancel();
    }
    var provider = profile.provider();
    var database = end.database;
    var schema = end.schema;
    end.tables = null;
    end.reading = end.lane.ask(
        s.app,
        "read the tables to copy",
        Lane.Priority.VIEW,
        link -> list(link, provider, database, schema),
        listing -> {
          end.reading = null;
          end.databases = listing.databases();
          end.database = listing.database();
          end.schemas = listing.schemas();
          end.schema = listing.schema();
          end.tables = listing.tables();
          if (end == source) {
            existingMenus.keySet().retainAll(names(end.tables));
          }
        },
        error -> {
          end.reading = null;
          end.problem = error instanceof InterruptedException
              ? "Its session closed."
              : State.messageOf(error);
        });
  }

  // What the end holds: the database taken is the one chosen, else the session's own, else the
  // one there is; the schema is the one chosen, else public, else the first.
  private static Listing list(Servers.Link link, Provider provider, String database, String schema)
      throws Exception {
    var databases =
        link.databases().stream().filter(d -> !d.system()).map(Database::name).toList();
    var db = !database.isEmpty()
        ? database
        : link.database() != null
            ? link.database()
            : databases.size() == 1 ? databases.getFirst() : "";
    if (db.isEmpty()) {
      return new Listing(databases, "", List.of(), "", null);
    }
    var of = new Database(db, false);
    Container container = of;
    var schemas = List.<String>of();
    var taken = "";
    if (provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN)) {
      schemas =
          link.schemas(of).stream().filter(x -> !x.system()).map(Schema::name).toList();
      taken = schemas.contains(schema)
          ? schema
          : schemas.contains("public") ? "public" : schemas.isEmpty() ? "" : schemas.getFirst();
      if (taken.isEmpty()) {
        return new Listing(databases, db, schemas, "", List.of());
      }
      container = new Schema(of, taken, false);
    }
    var tables = link.tables(container).stream()
        .filter(t -> t.kind() == Table.Kind.TABLE)
        .toList();
    return new Listing(databases, db, schemas, taken, tables);
  }

  private static Set<String> names(List<Table> tables) {
    var names = new HashSet<String>();
    if (tables != null) {
      tables.forEach(t -> names.add(t.name()));
    }
    return names;
  }

  // The choices

  // Another connection for the end; the target follows a source of another provider.
  private void choose(State s, End end, UUID id) {
    if (id.equals(end.id) || running != null) {
      return;
    }
    var was = provider(s, end);
    end.id = id;
    end.database = "";
    end.schema = "";
    report = null;
    open(s, end);
    if (end == source) {
      unchecked.clear();
      existing.clear();
      if (was != provider(s, source)) {
        target.id = source.id;
        target.database = "";
        target.schema = "";
        open(s, target);
      }
    }
  }

  private void chooseDatabase(State s, End end, String database) {
    if (database.equals(end.database) || running != null) {
      return;
    }
    end.database = database;
    end.schema = "";
    report = null;
    if (end == source) {
      unchecked.clear();
      existing.clear();
    }
    // A database of PostgreSQL needs a session of its own; MariaDB and MySQL read any.
    var profile = s.profile(end.id);
    if (profile != null
        && profile.provider().supports(Capability.CONNECTION_PER_DATABASE, ServerVersion.UNKNOWN)) {
      open(s, end);
    } else {
      read(s, end);
    }
  }

  private void chooseSchema(State s, End end, String schema) {
    if (schema.equals(end.schema) || running != null) {
      return;
    }
    end.schema = schema;
    report = null;
    if (end == source) {
      unchecked.clear();
      existing.clear();
    }
    read(s, end);
  }

  private static Provider provider(State s, End end) {
    var profile = end.id == null ? null : s.profile(end.id);
    return profile == null ? null : profile.provider();
  }

  Existing existing(String table) {
    return existing.getOrDefault(table, Existing.STOP);
  }

  /** The source's tables checked, in their order. */
  List<Table> checked() {
    if (source.tables == null) {
      return List.of();
    }
    return source.tables.stream().filter(t -> !unchecked.contains(t.name())).toList();
  }

  /** Why the copy cannot run now, in words for the user; null where it can. */
  String blocked(State s) {
    for (var end : List.of(source, target)) {
      if (end.problem != null) {
        return end.role + ": " + end.problem;
      }
      if (end.lane == null || end.tables == null) {
        return end.reading != null
            ? null // the view says that it reads
            : end.role + ": choose its database" + (end.schemas == null ? "." : " and schema.");
      }
      var provider = provider(s, end);
      if (provider != null && end.container(provider) == null) {
        return end.role + ": choose its "
            + (provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN)
                ? "schema."
                : "database.");
      }
    }
    var profile = s.profile(target.id);
    if (profile != null && profile.readOnly()) {
      return "The target connection is read-only: nothing can be copied into it.";
    }
    if (source.same(s, target)) {
      return "The source and the target are the same: choose another target database"
          + (target.schemas == null || target.schemas.isEmpty() ? "." : " or schema.");
    }
    if (checked().isEmpty()) {
      return "Check the tables to copy.";
    }
    return null;
  }

  // The run

  /** Copies the tables checked, on the two sessions, unless it cannot now. */
  void copy(State s) {
    if (running != null || blocked(s) != null || source.reading != null || target.reading != null) {
      return;
    }
    var provider = provider(s, source);
    var into = target.container(provider);
    var copies = checked().stream()
        .map(t ->
            new Transfer.Copy(t, new Table(into, t.name(), Table.Kind.TABLE), existing(t.name())))
        .toList();
    var transfer = new Transfer(copies, commit, onError, batch);
    var from = source(source.lane, provider);
    var to = target(target.lane, provider);
    var told = new AtomicReference<Report>();
    last = told;
    report = null;
    failed = null;
    cancelling = false;
    running = s.app.background(
        "copy tables",
        (Work.Progress<Report> progress) -> Copier.run(transfer, from, to, r -> {
          told.set(r);
          progress.report(r);
        }),
        r -> report = r,
        r -> {
          running = null;
          report = r;
          if (r.stage() == Report.Stage.DONE && r.in(Report.State.FAILED).isEmpty()) {
            read(s, target); // what the target holds now
          }
          settle(s);
        },
        e -> {
          running = null;
          failed = State.messageOf(e);
          settle(s);
        });
  }

  /** Cancels the copy under way: what it wrote and did not commit is rolled back. */
  void cancel() {
    if (running == null || cancelling) {
      return;
    }
    cancelling = true;
    var work = running;
    var told = last;
    work.cancel();
    work.onEnd(() -> {
      if (running == work) {
        running = null;
        cancelling = false;
        report = told.get();
        settle(state);
      }
    });
  }

  // The question

  @Override
  public UUID connectionId() {
    return source.id;
  }

  /** A copy that runs is at stake: what would end it asks first. */
  @Override
  public boolean atStake(Guard.Scope scope) {
    return scope != Guard.Scope.TRANSACTION && running != null;
  }

  private Guard.Pending.Question asked(State s) {
    return s.guard.pending instanceof Guard.Pending.Question question && question.tab() == this
        ? question
        : null;
  }

  @Override
  public El question(State s) {
    var asked = asked(s);
    if (asked == null) {
      return null;
    }
    Theme t = Theme.CURRENT.get();
    var content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(label(s.title(this) + " is copying tables. Stop the copy before " + asked.what()
                + "? What it wrote and did not commit is rolled back.")
            .wrapText())
        .child(
            cancelling
                ? row()
                    .gap(t.space().s())
                    .align(Style.Align.CENTER)
                    .child(Progress.spinner().label("Stopping"))
                    .child(label(Label.Variant.MUTED, "Stopping the copy…"))
                : null);
    var buttons = List.of(
        button(SECONDARY, ConsoleTab.CANCEL_BUTTON, () -> answer(s, false)).key("answer-cancel"),
        button(
                PRIMARY,
                "Stop the copy",
                cancelling ? null : () -> answer(s, true),
                Button.options().tone(Button.Tone.DANGER))
            .key("answer-stop"));
    return Dialog.dialog("Stop the copy?", content, buttons, () -> answer(s, false))
        .key("transfer-question");
  }

  // The question's answer: the copy stops, and the act goes on once it has ended; or it runs on,
  // and the act is given up.
  private void answer(State s, boolean stop) {
    if (asked(s) == null) {
      return;
    }
    if (!stop) {
      s.guard.pending = null;
      return;
    }
    cancel();
    settle(s);
  }

  // The copy ended: an act that asked goes on.
  private void settle(State s) {
    var asked = asked(s);
    if (asked != null && !atStake(asked.scope())) {
      s.guard.pending = null;
      asked.then().run();
    }
  }

  // The source's calls, each on its lane, waited for: an interrupt cancels the one that runs.
  private static Source source(Lane lane, Provider provider) {
    return new Source() {
      @Override
      public Provider provider() {
        return provider;
      }

      @Override
      public void prepare() throws Exception {
        lane.call(link -> {
          link.source().prepare();
          return null;
        });
      }

      @Override
      public List<Column> columns(Table table) throws Exception {
        return lane.call(link -> link.source().columns(table));
      }

      @Override
      public long count(Table table) throws Exception {
        return lane.call(link -> link.source().count(table));
      }

      @Override
      public String identity() throws Exception {
        return lane.call(link -> link.source().identity());
      }

      @Override
      public Reading read(Selection selection) throws Exception {
        var reading = lane.call(link -> link.source().read(selection));
        return new Reading() {
          @Override
          public Batch next(int count, long budget) throws Exception {
            return lane.call(link -> reading.next(count, budget));
          }

          @Override
          public void close() throws Exception {
            lane.call(link -> {
              reading.close();
              return null;
            });
          }
        };
      }
    };
  }

  // The target's calls, each on its lane, as the source's.
  private static Target target(Lane lane, Provider provider) {
    return new Target() {
      private volatile Boolean readOnly;

      @Override
      public Provider provider() {
        return provider;
      }

      @Override
      public boolean readOnly() {
        if (readOnly == null) {
          try {
            readOnly = lane.call(link -> link.target().readOnly());
          } catch (Exception e) {
            return false; // the next call says why the session cannot be used
          }
        }
        return readOnly;
      }

      @Override
      public void prepare() throws Exception {
        lane.call(link -> {
          link.target().prepare();
          return null;
        });
      }

      @Override
      public String identity() throws Exception {
        return lane.call(link -> link.target().identity());
      }

      @Override
      public OptionalLong statementLimit() throws Exception {
        return lane.call(link -> link.target().statementLimit());
      }

      @Override
      public Shape shape(Table table) throws Exception {
        return lane.call(link -> link.target().shape(table));
      }

      @Override
      public List<ForeignKey> foreignKeys(Table table) throws Exception {
        return lane.call(link -> link.target().foreignKeys(table));
      }

      @Override
      public boolean hasRows(Table table) throws Exception {
        return lane.call(link -> link.target().hasRows(table));
      }

      @Override
      public void begin() throws Exception {
        lane.call(link -> {
          link.target().begin();
          return null;
        });
      }

      @Override
      public long empty(Table table) throws Exception {
        return lane.call(link -> link.target().empty(table));
      }

      @Override
      public long write(Table table, List<String> columns, List<List<Object>> rows)
          throws Exception {
        return lane.call(link -> link.target().write(table, columns, rows));
      }

      @Override
      public void written(Table table, List<String> columns) throws Exception {
        lane.call(link -> {
          link.target().written(table, columns);
          return null;
        });
      }

      @Override
      public void commit() throws Exception {
        lane.call(link -> {
          link.target().commit();
          return null;
        });
      }

      @Override
      public void rollback() throws Exception {
        lane.call(link -> {
          link.target().rollback();
          return null;
        });
      }

      @Override
      public void end() throws Exception {
        lane.call(link -> {
          link.target().end();
          return null;
        });
      }
    };
  }

  // The view

  /** The tab's content: the two ends, the tables, the options, and how far a copy is. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var runs = running != null;
    var blocked = blocked(s);
    var copy = button(
            PRIMARY,
            runs ? "Copying" : COPY,
            runs || blocked != null || source.reading != null || target.reading != null
                ? null
                : () -> copy(s))
        .key("transfer-copy")
        .label(COPY);
    var cancel = button(
            SECONDARY,
            cancelling ? "Cancelling" : "Cancel",
            runs && !cancelling ? this::cancel : null)
        .key("transfer-cancel")
        .label("Cancel the copy");
    var actions = row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(copy)
        .child(runs ? cancel : null)
        .child(
            blocked == null || runs
                ? null
                : label(Label.Variant.MUTED, blocked).wrapText().grow(1).key("transfer-blocked"));
    var body = col()
        .wFull()
        .gap(t.space().l())
        .padding(t.space().l())
        .child(row()
            .wFull()
            .gap(t.space().l())
            .child(endView(s, source).grow(1))
            .child(endView(s, target).grow(1)))
        .child(tablesView(s))
        .child(optionsView())
        .child(actions)
        .child(statusView());
    return col()
        .wFull()
        .hFull()
        .key("transfer")
        .child(body.scroll().scrollbars(t.scrollbars()).grow(1));
  }

  // The connection, database and schema of an end.
  private El endView(State s, End end) {
    Theme t = Theme.CURRENT.get();
    var runs = running != null;
    var provider = provider(s, source);
    var profiles = s.profiles.stream()
        .filter(p -> !p.provider().keepsDocuments())
        .filter(p -> end == source || provider == null || p.provider() == provider)
        .map(ConnectionProfile::id)
        .toList();
    var role = end.role;
    var lower = role.toLowerCase(java.util.Locale.ROOT);
    var connection = Select.select(
            profiles,
            end.id,
            id -> {
              var p = s.profile(id);
              return p == null ? "None" : GoTo.label(p, s.profiles);
            },
            end.connections,
            runs ? null : id -> choose(s, end, id))
        .key(lower + "-connection")
        .label(role + " connection");
    var fields = new ArrayList<Form.Entry>();
    fields.add(Form.field("Connection", connection));
    var databases = new ArrayList<String>();
    if (end.databases != null) {
      databases.addAll(end.databases);
    }
    if (!end.database.isEmpty() && !databases.contains(end.database)) {
      databases.addFirst(end.database);
    }
    fields.add(Form.field(
        "Database",
        Select.select(
                databases,
                end.database.isEmpty() ? null : end.database,
                n -> n,
                end.databaseMenu,
                runs ? null : n -> chooseDatabase(s, end, n))
            .key(lower + "-database")
            .label(role + " database")));
    if (provider != null && provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN)) {
      var schemas = end.schemas == null ? List.<String>of() : end.schemas;
      fields.add(Form.field(
          "Schema",
          Select.select(
                  schemas,
                  end.schema.isEmpty() ? null : end.schema,
                  n -> n,
                  end.schemaMenu,
                  runs ? null : n -> chooseSchema(s, end, n))
              .key(lower + "-schema")
              .label(role + " schema")));
    }
    var card = col()
        .gap(t.space().s())
        .child(label(Label.Variant.SECTION, role))
        .child(Form.form(Form.Labels.BESIDE, fields.toArray(Form.Entry[]::new)));
    if (end.reading != null) {
      card.child(Progress.spinner().label("Reading " + lower + " tables"));
    }
    if (end.problem != null) {
      var profile = end.id == null ? null : s.profile(end.id);
      card.child(Callout.callout(
              Callout.Kind.WARNING,
              role + " not read",
              end.problem,
              profile == null || s.catalog.connected(end.id)
                  ? new El[] {}
                  : new El[] {
                    button(SECONDARY, "Connect", () -> s.catalog.connect(s, end.id))
                        .label("Connect " + State.name(profile))
                  })
          .key(lower + "-problem"));
    }
    return Card.card(card).key(lower);
  }

  // The source's tables, each to check, with whether the target has it and what is done where it
  // has rows, and, once a copy ran, how far it is.
  private El tablesView(State s) {
    Theme t = Theme.CURRENT.get();
    var runs = running != null;
    var list = col().key("transfer-tables").role("list").label("Tables").wFull();
    if (source.tables == null) {
      list.child(label(Label.Variant.MUTED, "The source's tables are read once it is chosen.")
          .padding(t.space().m()));
    } else if (source.tables.isEmpty()) {
      list.child(label(Label.Variant.MUTED, "The source holds no tables.")
          .padding(t.space().m()));
    }
    var there = names(target.tables);
    var entries = new HashMap<String, Report.Entry>();
    if (report != null) {
      report.tables().forEach(e -> entries.put(e.copy().from().name(), e));
    }
    var tables = source.tables == null ? List.<Table>of() : source.tables;
    for (var i = 0; i < tables.size(); i++) {
      var name = tables.get(i).name();
      var box = Checkbox.checkbox(!unchecked.contains(name), name, runs ? null : () -> toggle(name))
          .grow(1);
      var line = row()
          .key("table-" + i)
          .role("listitem")
          .label(name)
          .wFull()
          .h(t.size().control())
          .gap(t.space().m())
          .align(Style.Align.CENTER)
          .padding(0, t.space().m())
          .child(box);
      var entry = entries.get(name);
      if (entry != null) {
        line.child(label(Label.Variant.MUTED, state(entry)).key("table-" + i + "-state"));
      } else if (target.tables != null) {
        line.child(
            label(Label.Variant.MUTED, there.contains(name) ? "In the target" : "Not in the target")
                .key("table-" + i + "-target"));
      }
      line.child(Select.select(
              EXISTING,
              existing(name),
              TransferTab::title,
              existingMenus.computeIfAbsent(name, n -> new Menu.State()),
              runs ? null : v -> existing.put(name, v))
          .key("table-" + i + "-existing")
          .label("Where " + name + " has rows")
          .w(9 * t.size().control()));
      list.child(line);
    }
    var header = row()
        .wFull()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(label(Label.Variant.SECTION, "Tables").grow(1))
        .child(button(GHOST, "All", runs ? null : unchecked::clear).label("Check all tables"))
        .child(button(GHOST, "None", runs ? null : () -> unchecked.addAll(names(source.tables)))
            .label("Check no table"))
        .child(label(Label.Variant.MUTED, "Where the target has rows"))
        .child(Select.select(
                EXISTING,
                tables.stream().map(table -> existing(table.name())).distinct().count() == 1
                    ? existing(tables.getFirst().name())
                    : null,
                TransferTab::title,
                allMenu,
                runs ? null : v -> tables.forEach(table -> existing.put(table.name(), v)))
            .key("transfer-existing-all")
            .label("Where any target has rows")
            .w(9 * t.size().control()));
    return col().wFull().gap(t.space().s()).child(header).child(Card.flush(list).wFull());
  }

  private void toggle(String name) {
    if (!unchecked.remove(name)) {
      unchecked.add(name);
    }
  }

  private El optionsView() {
    Theme t = Theme.CURRENT.get();
    var runs = running != null;
    return Form.form(
            Form.Labels.BESIDE,
            Form.field(
                "Commit",
                Segmented.segmented(
                        COMMITS, commit, TransferTab::title, runs ? null : v -> commit = v)
                    .key("transfer-commit")
                    .label("Commit")),
            Form.field(
                "At an error",
                Segmented.segmented(
                        ON_ERRORS, onError, TransferTab::title, runs ? null : v -> onError = v)
                    .key("transfer-on-error")
                    .label("At an error")),
            Form.field(
                "Rows per batch",
                NumberField.numberField(batch, 1, MOST_BATCH, 100, runs ? null : v -> batch = v)
                    .key("transfer-batch")
                    .label("Rows per batch")
                    .w(6 * t.size().control())))
        .key("transfer-options");
  }

  // How far the copy is, or how it ended.
  private El statusView() {
    if (failed != null) {
      return Callout.callout(Callout.Kind.DANGER, "The copy could not run", failed)
          .key("transfer-failed");
    }
    if (report == null) {
      return running == null
          ? null
          : row()
              .gap(Theme.CURRENT.get().space().s())
              .align(Style.Align.CENTER)
              .key("transfer-starting")
              .child(Progress.spinner())
              .child(label(Label.Variant.MUTED, "Reading what the tables need"));
    }
    var copied = report.in(Report.State.COPIED);
    long rows = report.written();
    return switch (report.stage()) {
      case PREPARING ->
        Progress.labeled("Reading the tables", 0, tables(report.tables().size()))
            .wFull()
            .key("transfer-progress");
      case COPYING -> progressView();
      case DONE -> {
        var failures = report.in(Report.State.FAILED);
        yield failures.isEmpty()
            ? Callout.callout(
                    Callout.Kind.SUCCESS,
                    "Copied",
                    tables(copied.size()) + ", " + ConsoleTab.rows(rows) + ".")
                .key("transfer-done")
            : Callout.callout(
                    Callout.Kind.WARNING,
                    "Copied " + copied.size() + " of " + tables(report.tables().size()),
                    failures(failures))
                .key("transfer-done");
      }
      case STOPPED ->
        Callout.callout(
                Callout.Kind.DANGER,
                "The copy stopped at a failure",
                failures(report.in(Report.State.FAILED))
                    + (copied.isEmpty()
                        ? ""
                        : "\n" + tables(copied.size()) + " copied before, " + ConsoleTab.rows(rows)
                            + "."))
            .key("transfer-stopped");
      case CANCELLED ->
        Callout.callout(
                Callout.Kind.INFO,
                "Cancelled",
                tables(copied.size()) + " copied, " + kept() + " in all; what was not committed"
                    + " was rolled back.")
            .key("transfer-cancelled");
      case REFUSED ->
        Callout.callout(Callout.Kind.DANGER, "The copy was refused", report.problem())
            .key("transfer-refused");
    };
  }

  private El progressView() {
    var total = report.total();
    var at = report.tables().stream()
        .filter(e -> e.state() == Report.State.COPYING || e.state() == Report.State.EMPTYING)
        .findFirst();
    var what = at.map(e -> (e.state() == Report.State.EMPTYING ? "Emptying " : "Copying ")
            + e.copy().from().name())
        .orElse("Copying");
    long rows = report.written();
    double fraction = total.isPresent() && total.getAsLong() > 0
        ? Math.min(1, (double) rows / total.getAsLong())
        : 0;
    var detail = total.isPresent()
        ? ConsoleTab.rows(rows) + " of " + ConsoleTab.rows(total.getAsLong())
        : ConsoleTab.rows(rows);
    return Progress.labeled(what, fraction, detail).wFull().key("transfer-progress");
  }

  // The rows kept of all tables.
  private String kept() {
    return ConsoleTab.rows(
        report.tables().stream().mapToLong(Report.Entry::kept).sum());
  }

  // Each table that failed, and why.
  private static String failures(List<Report.Entry> failures) {
    var lines = new ArrayList<String>();
    for (var failure : failures) {
      lines.add(failure.copy().from().name() + ": " + failure.problem());
    }
    return String.join("\n", lines);
  }

  // A table's state as its line says it.
  private static String state(Report.Entry entry) {
    return switch (entry.state()) {
      case WAITING -> "Waiting";
      case EMPTYING -> "Emptying";
      case COPYING ->
        entry.total().isPresent()
            ? ConsoleTab.rows(entry.written()) + " of "
                + ConsoleTab.rows(entry.total().getAsLong())
            : ConsoleTab.rows(entry.written());
      case COPIED -> ConsoleTab.rows(entry.kept()) + " copied";
      case FAILED ->
        "Failed" + (entry.kept() > 0 ? ", " + ConsoleTab.rows(entry.kept()) + " kept" : "");
      case SKIPPED -> "Skipped";
      case CANCELLED ->
        "Cancelled" + (entry.kept() > 0 ? ", " + ConsoleTab.rows(entry.kept()) + " kept" : "");
    };
  }

  private static String tables(int n) {
    return n == 1 ? "1 table" : String.format(java.util.Locale.ROOT, "%,d tables", n);
  }

  static String title(Existing existing) {
    return switch (existing) {
      case STOP -> "Stop";
      case EMPTY -> "Empty it first";
      case APPEND -> "Append";
    };
  }

  static String title(Commit commit) {
    return switch (commit) {
      case TABLE -> "Per table";
      case BATCH -> "Per batch";
    };
  }

  static String title(OnError onError) {
    return switch (onError) {
      case STOP -> "Stop";
      case SKIP -> "Skip the table";
    };
  }
}
