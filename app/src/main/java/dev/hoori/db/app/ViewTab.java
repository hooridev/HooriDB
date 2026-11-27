package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.definition.Reference;
import dev.hoori.db.core.definition.ViewChange;
import dev.hoori.db.core.definition.ViewDefinition;
import dev.hoori.db.core.definition.Views;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Layout;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A view's definition, in a tab of its connection: the statements that create it, as its dialect
 * writes them ({@link ViewDefinition#text}) and laid out, in an editor with the console's colors
 * and completions, read-only until Edit, beside what it uses and what uses it. Save, Drop and a
 * materialized view's Refresh show their statements before they run. All of it runs on a session
 * of the tab's own on the view's database, never the tree's, which it opens once the tree's
 * connection is connected; once a change is made, the tree reads the schema or database again.
 *
 * <p>A new view's tab holds its schema, its name and its {@code SELECT} until Save creates it, and
 * then the view's definition. A read-only connection changes nothing here: its buttons are off,
 * and core would send nothing. Only the app's thread uses it.
 */
final class ViewTab implements Tab {

  static final String READ_ONLY = "The connection is read-only, which changes no view";
  static final String NOT_CONNECTED = "The connection is not connected.";

  /** Why something could not be read or done, in words for the user. */
  record Problem(String title, String text) {}

  /**
   * The statements of a change, shown before they run.
   *
   * @param refused what the server said where it refused to replace the view in place, which this
   *     drops and creates anew instead; null otherwise
   */
  record Preview(ViewChange change, String refused) {}

  /** What the tab does once the definition is read, as the tree's menu asked. */
  enum Then {
    NOTHING,
    DROP,
    REFRESH
  }

  final UUID id;
  Table view; // the view; a new view's, once saved
  boolean creating; // a new view's tab, until Save creates it
  Container container; // where a new view goes
  String name = ""; // a new view's name
  String text = "";
  private String original = ""; // the text as read; a new view's is empty
  ViewDefinition read; // null until read
  boolean editing; // whether the editor takes edits
  boolean concurrently = true; // a refresh's choice, where the view allows it
  final CodeEditor.State editor = new CodeEditor.State().readOnly(true);
  private final CodeEditor.State previewEditor = new CodeEditor.State().readOnly(true);
  private final Completion.State completion = new Completion.State().openAfter(".");
  private final Suggestions suggestions;
  final Menu.State containers = new Menu.State();
  Split.Position split = SPLIT;
  Lane.Asked reading; // the definition being read, or null
  Lane.Asked changing; // the change under way, or null
  Problem problem; // why the last read or change failed, until the next or a dismiss
  Preview asked; // the statements shown before they run, or null
  Then then = Then.NOTHING;
  String said; // what the status line says for a while, or null
  private int says;
  private Lane lane; // the tab's own session, or null while it has none
  private char[] password; // a copy, for keeping it out of what is shown; wiped with its lane
  private boolean closed;

  private final State state; // for close, which is called without one

  static final Split.Position SPLIT = Split.Position.second(260);

  private ViewTab(State state, UUID id, Table view, Container container, boolean creating) {
    this.state = state;
    this.id = id;
    this.view = view;
    this.container = container;
    this.creating = creating;
    this.suggestions = new Suggestions(id);
    if (creating) {
      editing = true;
      editor.readOnly(false);
    }
  }

  /** The tab of the view's definition, which {@link #open} reads. */
  static ViewTab of(State s, UUID id, Table view) {
    return new ViewTab(s, id, view, view.container(), false);
  }

  /** The tab of a new view in the schema or database, which Save creates. */
  static ViewTab creating(State s, UUID id, Container container) {
    return new ViewTab(s, id, null, container, true);
  }

  /** Opens the tab's session where it has none, and reads the definition where it has none. */
  void open(State s) {
    if (closed || creating) {
      return;
    }
    read(s);
  }

  /** The tree's connection connected again: what was not read yet is read. */
  void connected(State s) {
    if (read == null && reading == null) {
      open(s);
    }
  }

  /** The database its session is on: the view's. */
  String database() {
    return container.database().name();
  }

  /** Whether its text differs from what was read, or a new view has a name or a query. */
  boolean edited() {
    return creating ? !name.isBlank() || !text.isBlank() : !text.equals(original);
  }

  /** Whether it reads or changes now. */
  boolean busy() {
    return reading != null || changing != null;
  }

  /** Whether it is the view's tab, or would be once saved. */
  boolean of(Table table) {
    return view != null
        && view.container().equals(table.container())
        && view.name().equals(table.name());
  }

  // The session

  /**
   * Whether the lane can take a call: where it is gone, a new session takes its place; false where
   * the tree's connection is not connected, and none can be opened.
   */
  private boolean renewed(State s) {
    if (lane != null && !lane.broken()) {
      return true;
    }
    var session = s.catalog.session(s, id, database());
    if (session == null) {
      return false;
    }
    closeLane(s);
    lane = session.lane();
    password = session.password();
    return true;
  }

  // Closes the lane without waiting for it, which Exit waits for, and wipes its password.
  private void closeLane(State s) {
    if (lane != null) {
      s.closing();
      var closing = lane.close(ended -> {});
      s.app.background(
          "close the view's session",
          () -> {
            closing.join();
            return null;
          },
          done -> s.closed(),
          e -> s.closed());
      lane = null;
    }
    if (password != null) {
      Arrays.fill(password, '\0');
      password = null;
    }
  }

  /**
   * Its session ends, as its connection was disconnected or changed: what runs is given up. The
   * text stays, and the next read or change opens a session anew.
   */
  void end(State s) {
    stop();
    closeLane(s);
  }

  private void stop() {
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
    if (changing != null) {
      changing.cancel();
      changing = null;
    }
  }

  @Override
  public void close() {
    closed = true;
    asked = null;
    stop();
    closeLane(state);
    completion.close();
    suggestions.close();
  }

  // Reading

  /** Reads the definition anew; not while it reads or changes, nor while the text is edited. */
  void read(State s) {
    if (closed || creating || busy()) {
      return;
    }
    problem = null;
    if (!renewed(s)) {
      problem = new Problem("Not connected", NOT_CONNECTED);
      return;
    }
    var of = view;
    reading = lane.ask(
        s.app,
        "read the definition of " + of.name(),
        Lane.Priority.VIEW,
        link -> link.views().read(of),
        got -> {
          reading = null;
          shown(s, got);
        },
        e -> {
          reading = null;
          then = Then.NOTHING;
          problem = new Problem("The definition could not be read", words(e));
        });
  }

  private void shown(State s, ViewDefinition got) {
    read = got;
    view = got.view();
    text = laidOut(got.text(), provider(s));
    original = text;
    editing = false;
    editor.readOnly(true);
    concurrently = got.concurrently();
    var next = then;
    then = Then.NOTHING;
    switch (next) {
      case DROP -> askDrop(s);
      case REFRESH -> askRefresh(s);
      case NOTHING -> {
        // As read.
      }
    }
  }

  // Each statement on a line of its own, as Control+Alt+L lays it out; as given where it cannot.
  private static String laidOut(String text, Provider provider) {
    if (provider == null) {
      return text;
    }
    try {
      var laid = Layout.of(text, provider);
      return laid.endsWith("\n") ? laid : laid + "\n";
    } catch (Layout.UnclosedException | StackOverflowError _) {
      return text;
    }
  }

  /** What the tree's menu asks once the definition is read, at once where it is. */
  void then(State s, Then next) {
    if (read != null && !busy()) {
      switch (next) {
        case DROP -> askDrop(s);
        case REFRESH -> askRefresh(s);
        case NOTHING -> {
          // Nothing to ask.
        }
      }
    } else {
      then = next;
    }
  }

  // Editing and changing

  /** Why a change cannot be asked for now; null where it can. */
  String changeOff(State s) {
    var profile = s.profile(id);
    if (profile == null) {
      return "The connection was deleted";
    }
    if (profile.readOnly()) {
      return READ_ONLY;
    }
    if (busy()) {
      return "Wait for what runs";
    }
    if (!creating && read == null) {
      return "Read the definition first";
    }
    return null;
  }

  void edit() {
    editing = true;
    editor.readOnly(false);
  }

  /** Takes back the edits, to the definition as read. */
  void revert() {
    text = original;
    editing = false;
    editor.readOnly(true);
    problem = null;
  }

  /** Save: the statements that create or replace the view, shown before they run. */
  void save(State s) {
    var provider = provider(s);
    if (provider == null || changeOff(s) != null) {
      return;
    }
    try {
      var change = creating
          ? ViewChange.create(provider, container, name.strip(), text)
          : ViewChange.replace(provider, read, text);
      problem = null;
      asked = new Preview(change, null);
    } catch (IllegalArgumentException e) {
      problem = new Problem(
          "Nothing to save",
          creating
              ? "A new view needs a name and one SELECT."
              : "The definition holds no statement.");
    }
  }

  /** Drop: the statement, with what depends on it where the server drops that with it. */
  void askDrop(State s) {
    var provider = provider(s);
    if (provider == null || creating || changeOff(s) != null) {
      return;
    }
    var dependents = !read.references().usedBy().isEmpty();
    asked = new Preview(ViewChange.drop(provider, view, dependents), null);
  }

  /** Refresh of a materialized view's rows, concurrently where it can and that is chosen. */
  void askRefresh(State s) {
    var provider = provider(s);
    if (provider == null || creating || changeOff(s) != null || !read.materialized()) {
      return;
    }
    asked =
        new Preview(ViewChange.refresh(provider, view, concurrently && read.concurrently()), null);
  }

  /** The refresh's choice changed, which the statement shown follows. */
  void concurrently(State s, boolean on) {
    concurrently = on;
    if (asked != null && asked.change().kind() == ViewChange.Kind.REFRESH) {
      askRefresh(s);
    }
  }

  void decline() {
    asked = null;
  }

  /** Runs the change shown, on the tab's session. */
  void proceed(State s) {
    var preview = asked;
    asked = null;
    if (preview == null || changeOff(s) != null) {
      return;
    }
    problem = null;
    if (!renewed(s)) {
      problem = new Problem("Not connected", NOT_CONNECTED);
      return;
    }
    var change = preview.change();
    changing = lane.ask(
        s.app,
        "change the view " + change.view().name(),
        Lane.Priority.VIEW,
        link -> link.views().change(change),
        outcome -> {
          changing = null;
          switch (outcome) {
            case Views.Done _ -> done(s, change);
            case Views.KeepsColumns kept -> kept(s, kept.words());
          }
        },
        e -> {
          changing = null;
          problem = new Problem(failed(change.kind()), words(e));
          if (change.kind() != ViewChange.Kind.REFRESH) {
            s.catalog.refresh(s, node(change.view().container())); // as it may have changed
          }
        });
  }

  // The server keeps the view's columns: drop and create it anew, where nothing depends on it and
  // the server does that in one transaction.
  private void kept(State s, String words) {
    var provider = provider(s);
    var usedBy = read.references().usedBy();
    var dialect = provider == null ? null : provider.dialect().definitions();
    if (dialect != null && dialect.changesInTransaction() && usedBy.isEmpty()) {
      asked = new Preview(ViewChange.recreate(provider, read, text), words(words));
      return;
    }
    problem = new Problem(
        "The view's columns cannot change in place",
        words(words)
            + (usedBy.isEmpty()
                ? ""
                : "\n\n" + names(usedBy) + (usedBy.size() == 1 ? " uses" : " use")
                    + " it, which a drop would take: change the view so that its columns stay,"
                    + " or drop what uses it first."));
  }

  private void done(State s, ViewChange change) {
    s.catalog.refresh(s, node(change.view().container()));
    switch (change.kind()) {
      case CREATE -> {
        creating = false;
        view = change.view();
        container = view.container();
        name = "";
        say(s, "Created");
        read(s);
      }
      case REPLACE, RECREATE -> {
        say(s, "Saved");
        read(s);
      }
      case DROP -> {
        s.tell("Dropped " + view.name());
        s.closeView(this);
      }
      case REFRESH -> {
        say(s, "Refreshed");
        read(s); // whether it can refresh concurrently now
      }
    }
  }

  private Node node(Container of) {
    return switch (of) {
      case Database d -> new Node.OfDatabase(id, d);
      case Schema x -> new Node.OfSchema(id, x);
    };
  }

  private static String failed(ViewChange.Kind kind) {
    return switch (kind) {
      case CREATE -> "The view could not be created";
      case REPLACE, RECREATE -> "The view could not be saved";
      case DROP -> "The view could not be dropped";
      case REFRESH -> "The view's rows could not be refreshed";
    };
  }

  /** Says something in the tab's status line for a while. */
  void say(State s, String words) {
    said = words;
    int told = ++says;
    s.app.after(State.MESSAGE_SECONDS, () -> {
      if (says == told) {
        said = null;
      }
    });
  }

  /** Opens what the view uses, or what uses it: a table's rows, a view's definition. */
  void open(State s, Reference reference) {
    reference.table().ifPresent(table -> {
      if (table.kind().view()) {
        s.openView(id, table);
      } else {
        s.openTable(id, table);
      }
    });
  }

  // What failed, without the connection's password.
  private String words(Exception e) {
    var detail = switch (e) {
      case QueryException q -> q.detail().isEmpty() ? q.failure().toString() : q.detail();
      case ConnectionException c -> c.detail();
      default -> State.messageOf(e);
    };
    return words(detail);
  }

  private String words(String words) {
    return ConsoleTab.words(password, words);
  }

  private Provider provider(State s) {
    var profile = s.profile(id);
    return profile == null ? null : profile.provider();
  }

  private static String names(List<Reference> references) {
    return references.stream()
        .map(r -> r.container().name() + "." + r.shown())
        .collect(Collectors.joining(", "));
  }

  // The view

  /** Its name as the title says it: the view's, or a new view's. */
  String title() {
    if (creating) {
      return name.isBlank() ? "New view" : name.strip();
    }
    return view.name();
  }

  /** The tab's content: its toolbar, why something failed, the editor beside what it uses. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var provider = provider(s);
    var off = changeOff(s);
    El top = Card.toolbar(where(s), kind(), actions(s, t, off).toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    if (problem != null) {
      content.child(col()
          .padding(t.space().m())
          .shrink(0)
          .key("problem-note")
          .child(Callout.callout(
                  Callout.Kind.DANGER,
                  problem.title(),
                  problem.text(),
                  button(SECONDARY, "Dismiss", () -> problem = null))
              .key("problem")));
    }
    El code = editor(s, app, provider, off);
    if (creating) {
      content.child(code.grow(1));
    } else {
      content.child(Split.split(
              Split.Direction.ROW,
              split,
              SPLIT,
              p -> split = p,
              code,
              8 * t.size().control(),
              references(s, t),
              5 * t.size().control())
          .grow(1));
    }
    return content.child(Card.footer(status(s, t), footer(s)).key("footer"));
  }

  // Where the view is: its schema or database, and the connection.
  private String where(State s) {
    var profile = s.profile(id);
    var at = creating ? container.name() : view.container().name();
    return (profile == null ? "" : State.name(profile) + " · ") + at;
  }

  private String kind() {
    if (creating) {
      return "New view";
    }
    return view.kind() == Table.Kind.MATERIALIZED ? "Materialized view" : "View";
  }

  private List<El> actions(State s, Theme t, String off) {
    var actions = new ArrayList<El>();
    if (creating) {
      var choices = choices(s);
      if (choices.size() > 1) {
        actions.add(
            Select.select(choices, container, Container::name, containers, c -> container = c)
                .key("schema")
                .label("Schema")
                .w(5 * t.size().control()));
      }
      actions.add(TextField.textField(name, "Name", v -> name = v)
          .key("name")
          .label("Name")
          .w(7 * t.size().control()));
    } else if (!editing) {
      actions.add(gated(
          button(
                  SECONDARY,
                  "Edit",
                  off == null ? this::edit : null,
                  Button.options().icon(Symbol.EDIT))
              .key("edit"),
          off));
    } else {
      actions.add(button(
              SECONDARY,
              "Revert",
              edited() && !busy() ? this::revert : null,
              Button.options().icon(Symbol.ARROW_UNDO))
          .key("revert"));
    }
    boolean saves = editing && edited() && (!creating || !name.isBlank() && !text.isBlank());
    actions.add(gated(
        button(
                PRIMARY,
                "Save…",
                off == null && saves ? () -> save(s) : null,
                Button.options()
                    .icon(Symbol.SAVE)
                    .state(changing != null ? Button.State.BUSY : Button.State.READY))
            .key("save"),
        off));
    if (creating) {
      return actions;
    }
    actions.add(Card.spacer());
    if (view.kind() == Table.Kind.MATERIALIZED) {
      actions.add(gated(
          button(
                  SECONDARY,
                  "Refresh rows…",
                  off == null ? () -> askRefresh(s) : null,
                  Button.options().icon(Symbol.ARROW_SYNC))
              .key("refresh-rows"),
          off));
    }
    actions.add(gated(
        button(
                SECONDARY,
                "Drop…",
                off == null ? () -> askDrop(s) : null,
                Button.options().icon(Symbol.DELETE))
            .key("drop"),
        off));
    actions.add(button(
            SECONDARY,
            "Reload",
            !busy() && !edited() ? () -> read(s) : null,
            Button.options()
                .icon(Symbol.ARROW_CLOCKWISE)
                .state(reading != null ? Button.State.BUSY : Button.State.READY))
        .key("reload"));
    return actions;
  }

  // A change's button, with why it is off where that is the connection's.
  private static El gated(El button, String off) {
    return READ_ONLY.equals(off) ? Tooltip.tooltip(button, off) : button;
  }

  /**
   * Where a new view may go: the schemas of its database that the tree read, but the server's
   * own, where the provider has schemas; else the database alone.
   */
  List<Container> choices(State s) {
    if (!(container instanceof Schema schema)) {
      return List.of(container);
    }
    var schemas = s.catalog.schemas(id, schema.database()).stream()
        .filter(x -> !x.system())
        .map(x -> (Container) x)
        .toList();
    return schemas.contains(container) ? schemas : List.of(container);
  }

  private El editor(State s, App<State> app, Provider provider, String off) {
    Theme t = Theme.CURRENT.get();
    editor.occurrences(s.settings.highlightOccurrences());
    El code = CodeEditor.codeEditor(
            app, text, editor, provider == null ? null : Syntax.of(provider), v -> text = v)
        .key("definition")
        .label(creating ? "Query" : "Definition")
        .wFull()
        .grow(1);
    var keys = code.onKey();
    code.onKey(k -> {
      if (k.name().equals("S") && k.modifiers() == Key.CONTROL) {
        save(s);
        return true;
      }
      return keys != null && keys.test(k);
    });
    if (editing && provider != null && s.catalog.connected(id)) {
      code = completing(s, app, provider, code);
    } else if (completion.isOpen()) {
      completion.close();
    }
    El embedded = CodeEditor.embedded(code).grow(1).wFull();
    if (reading != null && read == null) {
      return col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading the definition")
          .key("reading")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading the definition…"));
    }
    return col().grow(1).minSize(0, 0).padding(t.space().s()).child(embedded).key("editor");
  }

  // The console's completions, for the statement the caret is in, on the view's database.
  private El completing(State s, App<State> app, Provider provider, El code) {
    if (!completion.isOpen()) {
      suggestions.forget();
    }
    int caret = Math.min(editor.caret(), text.length());
    var around = Statements.of(text, provider).around(caret);
    var statement = text.substring(around.start(), around.end());
    var database = database();
    El completing = Completion.complete(
        app,
        code,
        editor.caret(),
        completion,
        word -> suggestions.at(s, provider, database, statement, caret - around.start(), word));
    var keys = completing.onKey();
    return completing.onKey(k -> {
      if (k.name().equals("Space") && k.modifiers() == Key.CONTROL) {
        suggestions.forget();
      }
      return keys != null && keys.test(k);
    });
  }

  // What the view uses and what uses it, each opened by a press where it is a table or view.
  private El references(State s, Theme t) {
    var entries = new ArrayList<NavList.Entry>();
    var opened = new ArrayList<Reference>();
    var references = read == null ? null : read.references();
    for (var part : List.of(true, false)) {
      entries.add(NavList.heading(part ? "Uses" : "Used by"));
      opened.add(null);
      var shown = references == null
          ? List.<Reference>of()
          : part ? references.uses() : references.usedBy();
      if (shown.isEmpty()) {
        entries.add(NavList.item(references == null ? "…" : "Nothing").disabled(true));
        opened.add(null);
      }
      for (var reference : shown) {
        entries.add(NavList.item(symbol(reference), reference.shown())
            .detail(reference.container().name())
            .disabled(reference.table().isEmpty()));
        opened.add(reference);
      }
    }
    El list = NavList.navList(entries, -1, i -> {
          if (i >= 0 && i < opened.size() && opened.get(i) != null) {
            open(s, opened.get(i));
          }
        })
        .key("references")
        .label("What it uses and what uses it");
    return col().grow(1).minSize(0, 0).padding(t.space().s()).child(list);
  }

  private static com.rethinkj.ui.kit.Icon symbol(Reference reference) {
    return reference.table().map(table -> Glyphs.table(table.kind())).orElse(Symbol.MATH_FORMULA);
  }

  private El status(State s, Theme t) {
    String words;
    if (changing != null) {
      words = "Running…";
    } else if (reading != null) {
      words = "Reading the definition…";
    } else if (creating) {
      words = "Save shows the CREATE VIEW before it runs";
    } else if (read == null) {
      words = "";
    } else if (!editing) {
      words = changeOff(s) == null ? "Read-only: Edit to change it" : "Read-only";
    } else {
      words = edited() ? "Edited: Save shows the statements before they run" : "Editing";
    }
    return row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(Label.label(Label.Variant.MUTED, words).ellipsis().key("state"));
  }

  private El[] footer(State s) {
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    if (read != null && read.materialized() && !read.concurrently()) {
      end.add(Label.label(Label.Variant.MUTED, "Refreshes only as a whole").key("refreshes"));
    }
    return end.toArray(El[]::new);
  }

  /** The statements shown before they run, while they are; in the tab's window. */
  El question(State s, App<State> app) {
    var preview = asked;
    if (preview == null) {
      return null;
    }
    Theme t = Theme.CURRENT.get();
    var provider = provider(s);
    var change = preview.change();
    var sql = change.statements().stream().map(x -> x + ";").collect(Collectors.joining("\n"));
    int lines = (int) sql.lines().count();
    El code = CodeEditor.embedded(CodeEditor.codeEditor(
                app, sql, previewEditor, provider == null ? null : Syntax.of(provider), null)
            .key("view-sql")
            .label("SQL preview"))
        .h(Math.min(12, Math.max(3, lines + 1)) * t.size().control())
        .wFull();
    El content = col()
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(summary(s, preview)).wrapText().key("view-summary"));
    if (change.kind() == ViewChange.Kind.REFRESH) {
      boolean can = read != null && read.concurrently();
      El box = Checkbox.checkbox(
              can && concurrently,
              "Concurrently, without keeping others from reading it",
              can ? () -> concurrently(s, !concurrently) : null)
          .key("concurrently");
      content.child(
          can
              ? box
              : Tooltip.tooltip(
                  box, "Needs rows read once and a unique index of columns over all of them"));
    }
    content.child(code);
    boolean danger =
        change.kind() == ViewChange.Kind.DROP || change.kind() == ViewChange.Kind.RECREATE;
    El go = button(
            PRIMARY,
            go(change.kind()),
            () -> proceed(s),
            danger ? Button.options().tone(Button.Tone.DANGER) : Button.options())
        .key("view-go");
    El cancel = button(
            SECONDARY,
            preview.refused() != null ? "Keep editing" : ConsoleTab.CANCEL_BUTTON,
            this::decline)
        .key("view-cancel");
    return Dialog.dialog(
            title(change.kind(), preview.refused() != null),
            content,
            List.of(cancel, go),
            this::decline,
            danger ? Dialog.options() : Dialog.options().defaultButton(go))
        .key("view-question");
  }

  private String summary(State s, Preview preview) {
    var provider = provider(s);
    var change = preview.change();
    var named = provider == null ? change.view().name() : Rows.qualified(provider, change.view());
    var all = change.transaction() ? " in one transaction" : "";
    return switch (change.kind()) {
      case CREATE -> "Creates the view " + named + ".";
      case REPLACE ->
        "Replaces the definition of " + named + " in place" + all
            + ": privileges granted on it stay.";
      case RECREATE ->
        (preview.refused() == null
                ? ""
                : (provider == null ? "The server" : provider.displayName())
                    + " cannot change the view's columns in place: " + preview.refused()
                    + "\n\n")
            + "Drops " + named + " and creates it anew" + all
            + (change.view().kind() == Table.Kind.MATERIALIZED ? ", with its rows read anew" : "")
            + ": privileges granted on it are lost, and must be granted again.";
      case DROP -> "Drops " + named + "." + dependents(provider);
      case REFRESH -> "Reads the rows of " + named + " anew from what it uses.";
    };
  }

  // What uses the view, which its drop takes with it or leaves without it.
  private String dependents(Provider provider) {
    var usedBy = read == null ? List.<Reference>of() : read.references().usedBy();
    if (usedBy.isEmpty()) {
      return "";
    }
    var cascades = provider != null && provider.dialect().definitions().dropsDependents();
    return cascades
        ? " What depends on it is dropped with it: " + names(usedBy) + "."
        : " What uses it stays, and fails without it: " + names(usedBy) + ".";
  }

  private static String title(ViewChange.Kind kind, boolean refused) {
    return switch (kind) {
      case CREATE -> "Create view";
      case REPLACE -> "Save view";
      case RECREATE -> refused ? "Drop and create the view?" : "Save view";
      case DROP -> "Drop view";
      case REFRESH -> "Refresh materialized view";
    };
  }

  private static String go(ViewChange.Kind kind) {
    return switch (kind) {
      case CREATE -> "Create";
      case REPLACE -> "Save";
      case RECREATE -> "Drop and create";
      case DROP -> "Drop";
      case REFRESH -> "Refresh";
    };
  }
}
