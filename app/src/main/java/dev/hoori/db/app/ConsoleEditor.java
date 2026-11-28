package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.app.Suggestions.Target;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Index;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.sql.Context;
import dev.hoori.db.core.sql.Keywords;
import dev.hoori.db.core.sql.Statement;
import dev.hoori.db.core.sql.Statements;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a console's editor shows beyond the kit's: the place of the last run's error in its own
 * markup layer so it moves with edits, per-statement gutter run and outcome markers, quick
 * documentation and go-to-table navigation for SQL consoles, and completions from what the tree
 * read. Only the app's thread uses it.
 */
final class ConsoleEditor {

  private static final String PRIMARY_KEY = " · primary key";
  private static final String DOCUMENTATION = "document a completion";

  private static final String ERROR_LAYER = "error";
  private static final String STATEMENTS_LAYER = "statements";

  /**
   * The documentation of a completion: of the entry, with what the tree had read of its table on
   * the app's thread, for the kit to make on another thread.
   */
  private record Described(
      Completion.Entry entry,
      Target target,
      List<Column> columns,
      List<ForeignKey> keys,
      List<Index> indexes,
      Provider provider) {
    MarkdownDocument of(Completion.Entry asked) {
      if (!asked.equals(entry)) {
        return null;
      }
      return switch (target) {
        case Target.OfTable(var table) -> tableDoc(table, columns, keys, indexes, provider);
        case Target.OfColumn(var column) -> columnDoc(column, keys, provider);
      };
    }
  }

  /** The last recorded outcome of one statement. */
  private record Ran(boolean failed, Duration took, String detail) {
    String tooltip() {
      var ms = took == null ? null : Format.DURATION.text(took);
      if (failed) {
        var said = ms == null ? "Failed" : "Failed after " + ms;
        return detail.isEmpty() ? said : said + " · " + detail;
      }
      if (ms == null) {
        return detail.isEmpty() ? "Ran" : detail;
      }
      return detail.isEmpty() ? "Ran in " + ms : "Ran in " + ms + " · " + detail;
    }

    Ran withDetail(String updated) {
      return new Ran(failed, took, updated);
    }
  }

  private final ConsoleTab tab;
  // The statements of the last run, each the very string that was sent, and its span in sentFrom:
  // two statements of equal text are told apart.
  private final Map<String, Statement> sent = new IdentityHashMap<>();
  private String sentFrom; // the text they were taken from
  private final Map<String, Ran> outcomes = new HashMap<>(); // last outcome by exact statement text
  private Statements marked; // the statements scan markers were last built for, or null
  int markersBuilt; // how often statement markers were rebuilt, which a test counts
  private final Map<String, Table> navigable = new HashMap<>(); // targets handed to onOpen
  private final Completion.State completion = new Completion.State().openAfter(".");
  private Described described; // the highlighted completion's documentation, or null
  final Suggestions suggestions;

  ConsoleEditor(ConsoleTab tab) {
    this.tab = tab;
    suggestions = new Suggestions(tab);
  }

  /**
   * The statements to run, as the strings that go to the server, from the text where {@code from}
   * is where the statements' offsets count from; running drops the previous server error.
   */
  List<String> sent(String text, int from, List<Statement> statements) {
    clear();
    sent.clear();
    sentFrom = text;
    var sql = new ArrayList<String>(statements.size());
    for (var at : statements) {
      int start = from + at.start();
      int end = from + at.end();
      var one = text.substring(start, end);
      sent.put(one, new Statement(start, end));
      sql.add(one);
    }
    return sql;
  }

  /**
   * The statement failed: where the server says, the place is marked in its own markup layer for
   * the text it was sent from so it moves with edits, with the server's {@code words} (withheld
   * where they hold the password), and the caret goes there while the text has not changed.
   */
  void failed(String sql, Failure failure, Provider provider, String words) {
    Statement span = sent.get(sql);
    var place =
        span == null || provider == null ? null : failure.place(sql, provider).orElse(null);
    if (place == null) {
      return;
    }
    int from = span.start() + place.start();
    var error = new CodeEditor.Diagnostic(
        from, span.start() + place.end(), CodeEditor.Severity.ERROR, words);
    tab.editor.markup(ERROR_LAYER, sentFrom, List.of(error));
    if (sentFrom.equals(tab.text)) {
      tab.editor.select(from, from);
    }
  }

  /** Records a statement's outcome for its gutter marker, keyed by the exact sent SQL. */
  void recorded(String sql, boolean failed, Duration took, String detail) {
    if (sql == null) {
      return;
    }
    outcomes.put(sql, new Ran(failed, took, detail == null ? "" : detail));
    marked = null;
  }

  /** Updates the row count shown in the gutter marker tooltip as further portions arrive. */
  void updateDetail(String sql, String detail) {
    if (sql == null) {
      return;
    }
    var ran = outcomes.get(sql);
    if (ran != null && !ran.failed()) {
      outcomes.put(sql, ran.withDetail(detail == null ? "" : detail));
      marked = null;
    }
  }

  /**
   * Wires quick documentation, go-to-table navigation, and statement gutter markers on the
   * editor's state before {@link CodeEditor#codeEditor} builds it.
   */
  void prepare(State s, Language language, Provider provider) {
    if (provider != null && language instanceof Language.Sql) {
      tab.editor
          .documentation(offset -> documentation(s, provider, offset))
          .declaration(offset -> declaration(s, provider, offset))
          .onOpen(location -> open(s, location));
    } else {
      navigable.clear();
      tab.editor.documentation(null).declaration(null).onOpen(null);
    }
    if (provider == null && !tab.isQuery()) {
      if (marked != null) {
        tab.editor.noMarkup(STATEMENTS_LAYER);
        marked = null;
      }
      return;
    }
    var scanned = tab.statements(language, provider);
    if (scanned == marked) {
      return;
    }
    marked = scanned;
    markersBuilt++;
    var statements = scanned.statements();
    if (statements.isEmpty()) {
      tab.editor.noMarkup(STATEMENTS_LAYER);
      return;
    }
    var markers = new ArrayList<CodeEditor.Marker>(statements.size());
    for (var stmt : statements) {
      var ran = outcomes.get(tab.text.substring(stmt.start(), stmt.end()));
      Runnable runIt = () -> tab.runStatement(s, stmt);
      if (ran == null) {
        markers.add(new CodeEditor.Marker(
            stmt.start(), Symbol.PLAY, Icon.Variant.ACCENT, "Run statement", runIt));
      } else if (ran.failed()) {
        markers.add(new CodeEditor.Marker(
            stmt.start(), Symbol.ERROR_CIRCLE, Icon.Variant.DANGER, ran.tooltip(), runIt));
      } else {
        markers.add(new CodeEditor.Marker(
            stmt.start(), Symbol.CHECKMARK_CIRCLE, Icon.Variant.SUCCESS, ran.tooltip(), runIt));
      }
    }
    tab.editor.markup(STATEMENTS_LAYER, tab.text, markers);
  }

  /** The editor as built, with its completions. */
  El editor(State s, App<State> app, Language language, Provider provider, El editor) {
    if (!completion.isOpen()) {
      suggestions.forget();
      completion.loading(false);
    }
    completion.openAfter(language.opensCompletion());
    boolean documented = provider != null && language instanceof Language.Sql;
    if (documented) {
      // Set before the list is built, which asks for the documentation of a new highlight.
      completion.documentation(DOCUMENTATION, described == null ? e -> null : described::of);
    } else {
      described = null;
      completion.documentation(null, null);
    }
    El completing = Completion.complete(app, editor, tab.editor.caret(), completion, word -> {
      var entries = suggestions.at(s, language, provider, word);
      completion.loading(suggestions.loading(s));
      return entries;
    });
    if (documented) {
      describeHighlighted(s, provider);
    }
    // Control+Space asks anew, with what was read since the list was made.
    return completing.onKeyFirst(k -> {
      if (k.name().equals("Space") && k.modifiers() == Key.CONTROL) {
        suggestions.forget();
      }
      return false;
    });
  }

  /** Takes what the highlighted entry stands for, for its documentation beside the list. */
  private void describeHighlighted(State s, Provider provider) {
    int at = completion.highlight();
    var target = completion.isOpen() ? suggestions.target(at) : null;
    described = target == null ? null : described(s, provider, suggestions.entry(at), target);
    completion.documentation(DOCUMENTATION, described == null ? e -> null : described::of);
  }

  /** The console closes: the reads for its completions and quick documentation are given up. */
  void close() {
    suggestions.close();
  }

  /** The query tab switched target connection: forget old target's outcomes, errors and scans. */
  void clearForSwitch() {
    clear();
    sent.clear();
    sentFrom = null;
    outcomes.clear();
    marked = null;
    navigable.clear();
    suggestions.close();
    suggestions.forget();
  }

  private void clear() {
    tab.editor.noMarkup(ERROR_LAYER);
  }

  /** Quick documentation for a SQL table or column at the character offset, or null. */
  private MarkdownDocument documentation(State s, Provider provider, int offset) {
    var known = suggestions.known(s, provider);
    var target = resolve(known, provider, offset);
    if (target == null) {
      return null;
    }
    return switch (target) {
      case Target.OfTable(var table) ->
        tableDoc(
            table, known.columns(table), known.foreignKeys(table), known.indexes(table), provider);
      case Target.OfColumn(var column) ->
        columnDoc(column, known.foreignKeys(column.table()), provider);
    };
  }

  /**
   * What the documentation of the entry shows, as the tree has read it; reads what it lacks, as
   * quick documentation does, for the next time it is highlighted.
   */
  private Described described(State s, Provider provider, Completion.Entry entry, Target target) {
    var known = suggestions.known(s, provider);
    return switch (target) {
      case Target.OfTable(var table) ->
        new Described(
            entry,
            target,
            List.copyOf(known.columns(table)),
            List.copyOf(known.foreignKeys(table)),
            List.copyOf(known.indexes(table)),
            provider);
      case Target.OfColumn(var column) ->
        new Described(
            entry,
            target,
            List.of(),
            List.copyOf(known.foreignKeys(column.table())),
            List.of(),
            provider);
    };
  }

  /** Go-to-table location for a SQL table or column at the character offset, or null. */
  private CodeEditor.Location declaration(State s, Provider provider, int offset) {
    var known = suggestions.known(s, provider);
    var target = resolve(known, provider, offset);
    if (target == null) {
      return null;
    }
    Table table = switch (target) {
      case Target.OfTable(var t) -> t;
      case Target.OfColumn(var c) -> {
        var outgoing = ForeignKeys.outgoing(c.table(), c.name(), known.foreignKeys(c.table()));
        yield outgoing.size() == 1 ? outgoing.getFirst().referenced() : c.table();
      }
    };
    String key = tableKey(table);
    navigable.put(key, table);
    return new CodeEditor.Location(key, 0, 0, table.name());
  }

  /** Opens the table a navigation {@code Location} points to, in this console's window. */
  private void open(State s, CodeEditor.Location location) {
    Table table = navigable.get(location.document());
    if (table == null || tab.id == null || s.profile(tab.id) == null) {
      return;
    }
    var group = s.tabs.groupOf(tab);
    Frame.focusContentSoon(
        s, s.openTable(tab.id, table, group == null ? s.tabs.main().active() : group));
  }

  /** Resolves the SQL identifier at {@code offset} in {@link ConsoleTab#text} to a table or column. */
  private Target resolve(Suggestions.Known known, Provider provider, int offset) {
    var text = tab.text;
    if (offset < 0 || offset >= text.length()) {
      return null;
    }
    var around = tab.statements(provider).around(offset);
    var statement = text.substring(around.start(), around.end());
    var found = Context.symbol(statement, provider, offset - around.start());
    if (found.isEmpty()) {
      return null;
    }
    var sym = found.get();
    if (!sym.quoted() && Keywords.isReserved(sym.name(), provider)) {
      return null;
    }
    return switch (sym.path().size()) {
      case 0 -> bare(known, sym);
      case 1 -> afterOne(known, sym, sym.path().getFirst());
      default -> sym.qualifier() ? null : afterTwo(known, sym);
    };
  }

  // schema.table.column, or database.schema.table.column: the column of the table before it.
  private static Target afterTwo(Suggestions.Known known, Context.Symbol sym) {
    var path = sym.path();
    var table = known.table(path.get(path.size() - 2), path.getLast());
    return table == null ? null : findColumn(known, table, sym.name());
  }

  // prefix.name: a table of a schema, or a column of a table or alias the prefix names.
  private static Target afterOne(Suggestions.Known known, Context.Symbol sym, String prefix) {
    if (sym.qualifier()) {
      return ofTable(known.table(prefix, sym.name()));
    }
    if (!sym.table()) {
      var refTable = referenced(known, sym.tables(), prefix);
      if (refTable != null) {
        return findColumn(known, refTable, sym.name());
      }
    }
    var table = known.table(prefix, sym.name());
    if (table != null || sym.table()) {
      return ofTable(table);
    }
    var direct = known.table(null, prefix);
    return direct == null ? null : findColumn(known, direct, sym.name());
  }

  // A name without a prefix: an alias or table before a dot, a table where one is named, else a
  // column of the statement's tables, an alias, or a table of that name.
  private static Target bare(Suggestions.Known known, Context.Symbol sym) {
    if (sym.qualifier()) {
      var refTable = referenced(known, sym.tables(), sym.name());
      if (refTable != null) {
        return new Target.OfTable(refTable);
      }
      if (known.tablesIn(sym.name()).isPresent()) {
        return null; // a schema or database qualifier, not a table
      }
      return ofTable(known.table(null, sym.name()));
    }
    if (sym.table()) {
      return ofTable(known.table(null, sym.name()));
    }
    var column = columnOfTables(known, sym);
    if (column != null) {
      return column;
    }
    for (var ref : sym.tables()) {
      if (ref.alias() != null && known.same(ref.alias(), sym.name())) {
        var table = known.table(ref.schema(), ref.name());
        if (table != null) {
          return new Target.OfTable(table);
        }
      }
    }
    return ofTable(known.table(null, sym.name()));
  }

  private static Target.OfColumn columnOfTables(Suggestions.Known known, Context.Symbol sym) {
    for (var ref : sym.tables()) {
      var table = known.table(ref.schema(), ref.name());
      var column = table == null ? null : findColumn(known, table, sym.name());
      if (column != null) {
        return column;
      }
    }
    return null;
  }

  private static Target ofTable(Table table) {
    return table == null ? null : new Target.OfTable(table);
  }

  private static Table referenced(
      Suggestions.Known known, List<Context.Reference> tables, String qualifier) {
    for (var ref : tables) {
      if (ref.alias() != null
          ? known.same(ref.alias(), qualifier)
          : known.same(ref.name(), qualifier)) {
        return known.table(ref.schema(), ref.name());
      }
    }
    return null;
  }

  private static Target.OfColumn findColumn(Suggestions.Known known, Table table, String name) {
    Column fallback = null;
    for (var column : known.columns(table)) {
      if (column.name().equals(name)) {
        return new Target.OfColumn(column);
      }
      if (fallback == null && known.same(column.name(), name)) {
        fallback = column;
      }
    }
    return fallback == null ? null : new Target.OfColumn(fallback);
  }

  private static MarkdownDocument tableDoc(
      Table table,
      List<Column> columns,
      List<ForeignKey> keys,
      List<Index> indexes,
      Provider provider) {
    var md = new StringBuilder();
    md.append("**").append(kind(table.kind())).append("** ").append(code(qualified(table)));
    if (!columns.isEmpty()) {
      md.append("\n\n");
      for (int i = 0; i < columns.size(); i++) {
        if (i > 0) {
          md.append('\n');
        }
        var c = columns.get(i);
        md.append("- ").append(code(c.name())).append(' ').append(code(c.type()));
        if (c.keyPosition() > 0) {
          md.append(PRIMARY_KEY);
        }
      }
    }
    appendKeys(md, "References", table, ForeignKeys.outgoing(table, keys), false, provider);
    appendKeys(md, "Referenced by", table, ForeignKeys.incoming(table, keys), true, provider);
    appendIndexes(md, indexes);
    return Markdown.parse(md.toString());
  }

  private static MarkdownDocument columnDoc(
      Column column, List<ForeignKey> keys, Provider provider) {
    var md = new StringBuilder();
    md.append("**Column** ").append(code(column.name())).append(' ').append(code(column.type()));
    if (column.keyPosition() > 0) {
      md.append(PRIMARY_KEY);
    } else if (!column.nullable()) {
      md.append(" · not null");
    }
    md.append("\n\nIn ")
        .append(kind(column.table().kind()).toLowerCase(Locale.ROOT))
        .append(' ')
        .append(code(qualified(column.table())));
    var targets = ForeignKeys.columnTargets(column, keys, provider);
    if (!targets.isEmpty()) {
      md.append("\n\n**References**\n\n");
      for (int i = 0; i < targets.size(); i++) {
        if (i > 0) {
          md.append('\n');
        }
        var t = targets.get(i);
        md.append("- → ").append(code(t.target())).append(t.rules());
      }
    }
    appendKeys(
        md,
        "Referenced by",
        column.table(),
        ForeignKeys.referencingColumn(column, keys),
        true,
        provider);
    return Markdown.parse(md.toString());
  }

  private static void appendKeys(
      StringBuilder md,
      String heading,
      Table table,
      List<ForeignKey> keys,
      boolean incoming,
      Provider provider) {
    if (keys.isEmpty()) {
      return;
    }
    md.append("\n\n**").append(heading).append("**\n\n");
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        md.append('\n');
      }
      var key = keys.get(i);
      md.append("- ")
          .append(code(key.name()))
          .append(" · ")
          .append(code(ForeignKeys.mapping(table, key, incoming)))
          .append(ForeignKeys.rules(key, provider));
    }
  }

  private static void appendIndexes(StringBuilder md, List<Index> indexes) {
    if (indexes.isEmpty()) {
      return;
    }
    md.append("\n\n**Indexes**\n\n");
    for (int i = 0; i < indexes.size(); i++) {
      if (i > 0) {
        md.append('\n');
      }
      var index = indexes.get(i);
      md.append("- ").append(code(index.name())).append(" (");
      for (int c = 0; c < index.columns().size(); c++) {
        if (c > 0) {
          md.append(", ");
        }
        md.append(code(index.columns().get(c)));
      }
      md.append(')');
      if (index.primary()) {
        md.append(PRIMARY_KEY);
      } else if (index.unique()) {
        md.append(" · unique");
      }
      if (!index.detail().isEmpty()) {
        md.append(" · ").append(index.detail());
      }
    }
  }

  private static String kind(Table.Kind kind) {
    return switch (kind) {
      case TABLE -> "Table";
      case FOREIGN -> "Foreign table";
      case VIEW -> "View";
    };
  }

  private static String qualified(Table table) {
    return table.container().name() + "." + table.name();
  }

  private static String tableKey(Table table) {
    var container = table.container();
    return container.database().name()
        + "\0"
        + (container instanceof Schema x ? x.name() : "")
        + "\0"
        + table.name();
  }

  /** Wraps text in a Markdown inline code span, choosing enough backticks to escape any inside. */
  private static String code(String text) {
    int max = 0;
    int run = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '`') {
        max = Math.max(max, ++run);
      } else {
        run = 0;
      }
    }
    var ticks = "`".repeat(max + 1);
    boolean pad =
        !text.isEmpty() && (text.charAt(0) == '`' || text.charAt(text.length() - 1) == '`');
    return pad ? ticks + " " + text + " " + ticks : ticks + text + ticks;
  }
}
