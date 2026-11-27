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
import com.rethinkj.ui.kit.Icon;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A view's definition, in a tab of its connection: the statements that create it, as its dialect
 * writes them ({@link ViewDefinition#text}) and laid out, in an editor with the console's colors
 * and completions, read-only until Edit, beside what it uses and what uses it. Save, Drop and a
 * materialized view's Refresh show their statements before they run, and a change that runs can be
 * cancelled. The definition is read on the tree's connection to the view's database, which only
 * reads; a change runs on the connection's own for changes of views ({@link
 * CatalogTree#definitions}), which the view tabs of that database share and which closes with the
 * tree's connection; once a change is made, the tree reads the schema or database again.
 *
 * <p>A new view's tab holds its schema, its name and its {@code SELECT} until Save creates it, and
 * then the view's definition. Edits not saved are at stake ({@link Guard.Guarded}): closing the
 * tab, its window, its connection's deletion and Exit ask first. A read-only connection changes
 * nothing here: its buttons are off, and core would send nothing. Only the app's thread uses it.
 */
final class ViewTab implements Tab, Guard.Guarded {

  static final String READ_ONLY = "The connection is read-only, which changes no view";
  static final String NOT_CONNECTED = "The connection is not connected.";

  /** Why something could not be read or done, in words for the user. */
  record Problem(String title, String text) {}

  /**
   * The statements of a change, shown before they run.
   *
   * @param refused what the server said where it refused the change asked before: to replace the
   *     view in place, which this drops and creates anew instead, or to drop it alone, which this
   *     drops with what depends on it; null otherwise
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
  ViewDefinition definition; // null until read
  boolean editing; // whether the editor takes edits
  boolean concurrently = true; // a refresh's choice, where the view allows it
  final CodeEditor.State editor = new CodeEditor.State().readOnly(true);
  private final CodeEditor.State previewEditor = new CodeEditor.State().readOnly(true);
  private final Completion.State completion = new Completion.State().openAfter(".");
  private final Suggestions suggestions;
  final Menu.State containers = new Menu.State();
  Split.Position split = INITIAL_SPLIT;
  Lane.Asked reading; // the definition being read, or null
  Lane.Asked changing; // the change under way, or null
  private ViewChange running; // what changing runs, or null
  Problem problem; // why the last read or change failed, until the next or a dismiss
  Preview asked; // the statements shown before they run, or null
  Then afterRead = Then.NOTHING;
  String said; // what the status line says for a while, or null
  private int says;
  private boolean closed;

  static final Split.Position INITIAL_SPLIT = Split.Position.second(260);

  private ViewTab(UUID id, Table view, Container container, boolean creating) {
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
  static ViewTab of(UUID id, Table view) {
    return new ViewTab(id, view, view.container(), false);
  }

  /** The tab of a new view in the schema or database, which Save creates. */
  static ViewTab forNew(UUID id, Container container) {
    return new ViewTab(id, null, container, true);
  }

  /** Reads the definition, but of a new view's tab. */
  void open(State s) {
    if (closed || creating) {
      return;
    }
    read(s);
  }

  /** The tree's connection connected again: what was not read yet is read. */
  void connected(State s) {
    if (definition == null && reading == null) {
      open(s);
    }
  }

  /** The database it reads and changes: the view's. */
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

  /**
   * Its connection ended, or HooriDB exits: what it reads or changes is given up; the text stays,
   * and the next read or change goes to the connection anew.
   */
  void end() {
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
    if (changing != null) {
      changing.cancel();
      changing = null;
      running = null;
    }
  }

  @Override
  public void close() {
    closed = true;
    asked = null;
    end();
    completion.close();
    suggestions.close();
  }

  // Reading

  /** Reads the definition anew; not while it reads or changes, nor while the text is edited. */
  void read(State s) {
    if (closed || creating || busy()) {
      return;
    }
    var lane = s.catalog.lane(s, id, view);
    if (lane == null) {
      problem = new Problem("Not connected", NOT_CONNECTED);
      return;
    }
    problem = null;
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
          afterRead = Then.NOTHING;
          problem = new Problem("The definition could not be read", words(s, e));
        });
  }

  private void shown(State s, ViewDefinition got) {
    definition = got;
    view = got.view();
    text = laidOut(got.text(), provider(s));
    original = text;
    editing = false;
    editor.readOnly(true);
    concurrently = got.concurrently();
    var next = afterRead;
    afterRead = Then.NOTHING;
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
    if (definition != null && !busy()) {
      switch (next) {
        case DROP -> askDrop(s);
        case REFRESH -> askRefresh(s);
        case NOTHING -> {
          // Nothing to ask.
        }
      }
    } else {
      afterRead = next;
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
    var locked = s.guard.locked(this);
    if (locked != null) {
      return locked;
    }
    if (busy()) {
      return "Wait for what runs";
    }
    if (!creating && definition == null) {
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

  /**
   * Whether Save can be asked for: the text edited, a new view's with a name and a query, and
   * nothing keeping a change off; as its button and Control+S both ask.
   */
  boolean saves(State s) {
    return changeOff(s) == null
        && editing
        && edited()
        && (!creating || !name.isBlank() && !text.isBlank());
  }

  /** Save: the statements that create or replace the view, shown before they run. */
  void save(State s) {
    var provider = provider(s);
    if (provider == null || !saves(s)) {
      return;
    }
    try {
      var change = creating
          ? ViewChange.create(provider, container, name.strip(), text)
          : ViewChange.replace(provider, definition, text);
      problem = null;
      asked = new Preview(change, null);
    } catch (ViewChange.OtherView other) {
      // Saved, it would create a second view and leave this one as it was.
      problem = new Problem(
          "The definition names another view",
          "It creates " + other.named() + ", where Save replaces " + view.name()
              + " alone: name " + view.name() + " in it again, or create the other view with New"
              + " view… of its schema.");
    } catch (IllegalArgumentException e) {
      problem = new Problem(
          "Nothing to save",
          creating
              ? "A new view needs a name and one SELECT."
              : "The definition holds no statement.");
    }
  }

  /**
   * Drop: the statement that drops the view alone, which a server that can refuses while something
   * depends on it, naming what as it is then; a drop with that is asked for after ({@link
   * #dependedOn}), never from what was read before.
   */
  void askDrop(State s) {
    var provider = provider(s);
    if (provider == null || creating || changeOff(s) != null) {
      return;
    }
    asked = new Preview(ViewChange.drop(provider, view, false), null);
  }

  /** Refresh of a materialized view's rows, concurrently where it can and that is chosen. */
  void askRefresh(State s) {
    var provider = provider(s);
    if (provider == null || creating || changeOff(s) != null || !definition.materialized()) {
      return;
    }
    asked = new Preview(
        ViewChange.refresh(provider, view, concurrently && definition.concurrently()), null);
  }

  /** The refresh's choice changed, which the statement shown follows. */
  void chooseConcurrently(State s, boolean on) {
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
    var lane = s.catalog.definitions(s, id, database());
    if (lane == null) {
      problem = new Problem("Not connected", NOT_CONNECTED);
      return;
    }
    s.acted();
    problem = null;
    var change = preview.change();
    running = change;
    changing = lane.ask(
        s.app,
        "change the view " + change.view().name(),
        Lane.Priority.VIEW,
        link -> link.views().change(change),
        outcome -> {
          if (running != change) {
            return; // cancelled
          }
          changing = null;
          running = null;
          switch (outcome) {
            case Views.Done _ -> done(s, change);
            case Views.KeepsColumns kept -> kept(s, kept.words());
            case Views.DependedOn depended -> dependedOn(s, depended.words());
          }
        },
        e -> {
          if (running != change) {
            return;
          }
          changing = null;
          running = null;
          problem = new Problem(failed(change.kind()), words(s, e));
          if (change.kind() != ViewChange.Kind.REFRESH) {
            s.catalog.refresh(s, node(change.view().container())); // as it may have changed
          }
        });
  }

  /**
   * Cancel while a change runs: the server is asked to stop it. What it had done by then stays
   * where its statements commit one by one; in one transaction it took effect only where the
   * server had committed it. The edited text stays, and the tree reads the view's place again.
   */
  void cancel(State s) {
    var change = running;
    if (changing == null || change == null) {
      return;
    }
    changing.cancel();
    changing = null;
    running = null;
    problem = new Problem(
        ConsoleTab.UNSURE,
        change.transaction()
            ? "The change was cancelled: it took effect only if the server had already committed"
                + " it. Reload shows the view as it is, once the edits are reverted."
            : "The change was cancelled: what the server had already run of it took effect."
                + " Reload shows the view as it is, once the edits are reverted.");
    if (change.kind() != ViewChange.Kind.REFRESH) {
      s.catalog.refresh(s, node(change.view().container()));
    }
  }

  // The server refused to drop the view alone and named what depends on it, now: the drop with
  // it is asked for, with the server's words.
  private void dependedOn(State s, String words) {
    var provider = provider(s);
    if (provider == null) {
      return;
    }
    asked = new Preview(ViewChange.drop(provider, view, true), words(s, words));
  }

  // The server keeps the view's columns: drop and create it anew, where nothing depends on it and
  // the server does that in one transaction.
  private void kept(State s, String words) {
    var provider = provider(s);
    var usedBy = definition.references().usedBy();
    if (provider != null && provider.dialect().views().changesInTransaction() && usedBy.isEmpty()) {
      asked = new Preview(ViewChange.recreate(provider, definition, text), words(s, words));
      return;
    }
    problem = new Problem(
        "The view's columns cannot change in place", words(s, words) + takenByDrop(usedBy));
  }

  // What uses the view, which a drop would take; empty where nothing does.
  private static String takenByDrop(List<Reference> usedBy) {
    if (usedBy.isEmpty()) {
      return "";
    }
    return "\n\n" + names(usedBy) + (usedBy.size() == 1 ? " uses" : " use")
        + " it, which a drop would take: change the view so that its columns stay,"
        + " or drop what uses it first.";
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
  private String words(State s, Exception e) {
    var detail = switch (e) {
      case QueryException q -> q.detail().isEmpty() ? q.failure().toString() : q.detail();
      case ConnectionException c -> c.detail();
      default -> State.messageOf(e);
    };
    return words(s, detail);
  }

  // The tree's connections sign in with the password it keeps, which the words are checked against.
  private String words(State s, String words) {
    return CatalogTree.words(s.catalog.live(id), words);
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
  El content(State s, App<State> app) {
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
    El code = editor(s, app, provider);
    if (creating) {
      content.child(code.grow(1));
    } else {
      content.child(Split.split(
              Split.Direction.ROW,
              split,
              INITIAL_SPLIT,
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
    var actions = new ArrayList<El>(editing(s, t, off));
    actions.add(gated(
        button(
                PRIMARY,
                "Save…",
                saves(s) ? () -> save(s) : null,
                Button.options()
                    .icon(Symbol.SAVE)
                    .state(changing != null ? Button.State.BUSY : Button.State.READY))
            .key("save"),
        off));
    if (!creating) {
      actions.add(Card.spacer());
      actions.addAll(changes(s, off));
    }
    return actions;
  }

  // A new view's schema and name, else Edit, or Revert while editing.
  private List<El> editing(State s, Theme t, String off) {
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
    return actions;
  }

  // What an existing view has: Refresh rows… of a materialized one, Drop… and Reload.
  private List<El> changes(State s, String off) {
    var actions = new ArrayList<El>();
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

  private El editor(State s, App<State> app, Provider provider) {
    Theme t = Theme.CURRENT.get();
    editor.occurrences(s.settings.highlightOccurrences());
    El code = CodeEditor.codeEditor(
            app, text, editor, provider == null ? null : Syntax.of(provider), v -> text = v)
        .key("definition")
        .label(creating ? "Query" : "Definition")
        .wFull()
        .grow(1);
    // Before the editor's own keys: Control+S is Save, as its button, and asks what it asks.
    code.onKeyFirst(k -> {
      if (k.name().equals("S") && k.modifiers() == Key.CONTROL) {
        save(s);
        return true;
      }
      return false;
    });
    if (editing && provider != null && s.catalog.connected(id)) {
      code = completing(s, app, provider, code);
    } else if (completion.isOpen()) {
      completion.close();
    }
    El embedded = CodeEditor.embedded(code).grow(1).wFull();
    if (reading != null && definition == null) {
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

  // The console's completions, for the statement the caret is in, on the view's database: the
  // text is split only while the list asks for them.
  private El completing(State s, App<State> app, Provider provider, El code) {
    if (!completion.isOpen()) {
      suggestions.forget();
    }
    var database = database();
    El completing =
        Completion.completeWithContext(app, code, editor.caret(), completion, context -> {
          var around = Statements.of(context.text(), provider).around(context.caret());
          var statement = context.text().substring(around.start(), around.end());
          int caret = context.caret() - around.start();
          return suggestions.at(s, provider, database, statement, caret, context.word());
        });
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
    var references = definition == null ? null : definition.references();
    addPart(entries, opened, "Uses", references == null ? null : references.uses());
    addPart(entries, opened, "Used by", references == null ? null : references.usedBy());
    El list = NavList.navList(entries, -1, i -> {
          if (i >= 0 && i < opened.size() && opened.get(i) != null) {
            open(s, opened.get(i));
          }
        })
        .key("references")
        .label("What it uses and what uses it");
    return col().grow(1).minSize(0, 0).padding(t.space().s()).child(list);
  }

  // A part's heading and its references, each with what a press opens; null while not read.
  private static void addPart(
      List<NavList.Entry> entries, List<Reference> opened, String heading, List<Reference> shown) {
    entries.add(NavList.heading(heading));
    opened.add(null);
    if (shown == null || shown.isEmpty()) {
      entries.add(NavList.item(shown == null ? "…" : "Nothing").disabled(true));
      opened.add(null);
      return;
    }
    for (var reference : shown) {
      entries.add(NavList.item(symbol(reference), reference.shown())
          .detail(reference.container().name())
          .disabled(reference.table().isEmpty()));
      opened.add(reference);
    }
  }

  private static Icon symbol(Reference reference) {
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
    } else if (definition == null) {
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
    Theme t = Theme.CURRENT.get();
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    if (changing != null) {
      end.add(row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .key("changing")
          .child(Progress.spinner().label("Changing"))
          .child(
              button(SECONDARY, ConsoleTab.CANCEL_BUTTON, () -> cancel(s)).key("cancel-change")));
    }
    if (definition != null && definition.materialized() && !definition.concurrently()) {
      end.add(Label.label(Label.Variant.MUTED, "Refreshes only as a whole").key("refreshes"));
    }
    return end.toArray(El[]::new);
  }

  /** The statements shown before they run, while they are; in the tab's window. */
  El preview(State s, App<State> app) {
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
        .h(Math.clamp(lines + 1L, 3, 12) * t.size().control())
        .wFull();
    El content = col()
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(summary(s, preview)).wrapText().key("view-summary"));
    if (change.kind() == ViewChange.Kind.REFRESH) {
      boolean can = definition != null && definition.concurrently();
      El box = Checkbox.checkbox(
              can && concurrently,
              "Concurrently, without keeping others from reading it",
              can ? () -> chooseConcurrently(s, !concurrently) : null)
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
    boolean refused = preview.refused() != null;
    El go = button(
            PRIMARY,
            go(change.kind(), refused),
            () -> proceed(s),
            danger ? Button.options().tone(Button.Tone.DANGER) : Button.options())
        .key("view-go");
    El cancel = button(
            SECONDARY,
            refused && change.kind() == ViewChange.Kind.RECREATE
                ? "Keep editing"
                : ConsoleTab.CANCEL_BUTTON,
            this::decline)
        .key("view-cancel");
    return Dialog.dialog(
            title(change.kind(), refused),
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
        cannotInPlace(provider, preview)
            + "Drops " + named + " and creates it anew" + all
            + (change.view().kind() == Table.Kind.MATERIALIZED ? ", with its rows read anew" : "")
            + (definition == null || definition.restore().isEmpty()
                ? ""
                : ", and gives it back its owner, triggers and rules")
            + ": privileges granted on it and on its columns are lost, and must be granted again.";
      case DROP ->
        preview.refused() == null
            ? "Drops " + named + "." + dependents(provider)
            : "Something depends on " + named + ", so the server did not drop it: "
                + preview.refused() + "\n\nDrops " + named + " with all of that, as the server"
                + " names it now.";
      case REFRESH -> "Reads the rows of " + named + " anew from what it uses.";
    };
  }

  // Why the server cannot change the view in place, where it refused; else empty.
  private static String cannotInPlace(Provider provider, Preview preview) {
    if (preview.refused() == null) {
      return "";
    }
    var server = provider == null ? "The server" : provider.displayName();
    return server + " cannot change the view's columns in place: " + preview.refused() + "\n\n";
  }

  // What uses the view as read, which a server that can refuses its drop for, naming what then
  // depends on it, and another leaves without it.
  private String dependents(Provider provider) {
    var usedBy =
        definition == null ? List.<Reference>of() : definition.references().usedBy();
    if (usedBy.isEmpty()) {
      return "";
    }
    var cascades = provider != null && provider.dialect().views().dropsDependents();
    return cascades
        ? " The server refuses while something depends on it, as " + names(usedBy)
            + " did when it was read, and then says what a drop with it would take."
        : " What uses it stays, and fails without it: " + names(usedBy) + ".";
  }

  private static String title(ViewChange.Kind kind, boolean refused) {
    return switch (kind) {
      case CREATE -> "Create view";
      case REPLACE -> "Save view";
      case RECREATE -> refused ? "Drop and create the view?" : "Save view";
      case DROP -> refused ? "Drop with what depends on it?" : "Drop view";
      case REFRESH -> "Refresh materialized view";
    };
  }

  private static String go(ViewChange.Kind kind, boolean refused) {
    return switch (kind) {
      case CREATE -> "Create";
      case REPLACE -> "Save";
      case RECREATE -> "Drop and create";
      case DROP -> refused ? "Drop all" : "Drop";
      case REFRESH -> "Refresh";
    };
  }

  // Edits at stake

  @Override
  public UUID connectionId() {
    return id;
  }

  // Only edits not saved: a change that runs is cancelled with the tab, as the server decides.
  @Override
  public boolean atStake(Guard.Scope scope) {
    return switch (scope) {
      case BOTH, CHANGES -> edited();
      case TRANSACTION -> false;
    };
  }

  private Guard.Pending.Question asked(State s) {
    return s.guard.pending instanceof Guard.Pending.Question question && question.tab() == this
        ? question
        : null;
  }

  /** What becomes of the edits, while the guard asks this tab before what it asks for. */
  @Override
  public El question(State s) {
    var question = asked(s);
    if (question == null) {
      return null;
    }
    Theme t = Theme.CURRENT.get();
    El content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(s.title(this) + " has edits of its definition that are not saved."
                + " Discard them before " + question.what() + "?")
            .wrapText());
    Runnable cancel = () -> s.guard.pending = null;
    return Dialog.dialog(
            "Discard the edits?",
            content,
            List.of(
                button(SECONDARY, ConsoleTab.CANCEL_BUTTON, cancel).key("answer-cancel"),
                button(
                        PRIMARY,
                        "Discard",
                        () -> discard(s),
                        Button.options().tone(Button.Tone.DANGER))
                    .key("answer-discard")),
            cancel)
        .key("edits-question");
  }

  // The edits go, and what waited on them goes on.
  private void discard(State s) {
    var question = asked(s);
    if (question == null) {
      return;
    }
    if (creating) {
      name = "";
      text = "";
    } else {
      revert();
    }
    if (!atStake(question.scope())) {
      s.guard.pending = null;
      question.then().run();
    }
  }
}
