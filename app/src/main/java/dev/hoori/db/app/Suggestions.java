package dev.hoori.db.app;

import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Lexer;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.document.Shell;
import dev.hoori.db.core.sql.Context;
import dev.hoori.db.core.sql.Keywords;
import dev.hoori.db.core.sql.Names;
import dev.hoori.db.core.sql.Ranking;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a console's editor suggests at the caret: keywords, and the schemas, tables, views and
 * columns that the tree has read of the console's connection and database. What it lacks it reads
 * in the background on the tree's connection, behind what the tree and the tabs ask for, and
 * suggests at the next keystroke; never on the console's own connection, which may be busy or in
 * a transaction. Closing the console gives up those reads. Only the app's thread uses it.
 */
final class Suggestions {

  /**
   * A name it may suggest, as it is listed, and as it is put in, quoted where it needs to be; and
   * whether it belongs to the statement's tables.
   */
  private record Candidate(
      String name, String shown, String text, Symbol symbol, String kind, boolean own) {}

  private final ConsoleTab tab;
  private final Map<Node, CatalogTree.Scan> asked = new HashMap<>(); // the reads it started
  private CatalogTree catalog; // where they were asked, once they are
  // The last entries, and what they were made for: the list keeps them while only its highlight
  // moves, and until it closes.
  private List<Completion.Entry> entries;
  private String madeFor;
  private int madeAt;
  private String madeOf;
  int made; // how often they were made, which a test counts

  Suggestions(ConsoleTab tab) {
    this.tab = tab;
  }

  /**
   * The entries for the word before the caret, best first: made again only where the text, the
   * caret or the word changed since the last, or the list was closed meanwhile.
   */
  List<Completion.Entry> at(State s, Provider provider, String word) {
    var text = tab.text;
    int caret = Math.min(tab.editor.caret(), text.length());
    if (entries != null && text == madeFor && caret == madeAt && word.equals(madeOf)) {
      return entries; // each edit hands over a new string
    }
    entries = make(s, provider, word, text, caret);
    made++;
    madeFor = text;
    madeAt = caret;
    madeOf = word;
    return entries;
  }

  /** The list closed: the next one is made anew, with what was read meanwhile. */
  void forget() {
    entries = null;
  }

  private List<Completion.Entry> make(
      State s, Provider provider, String word, String text, int caret) {
    // The statement the caret writes in, with the comments around it, from the console's split.
    var around = tab.statements(provider).around(caret);
    if (provider.keepsDocuments()) {
      var before = text.substring(around.start(), Math.max(around.start(), caret - word.length()));
      return Ranking.rank(shell(s, provider, before, word), Candidate::name, Candidate::own, word)
          .stream()
          .map(c -> new Completion.Entry(c.shown(), c.symbol(), c.kind(), c.text()))
          .toList();
    }
    var context =
        Context.at(text.substring(around.start(), around.end()), provider, caret - around.start());
    if (context.asked() == Context.Asked.NOTHING) {
      return List.of();
    }
    var known = new Known(s, provider);
    var candidates = new ArrayList<Candidate>();
    switch (context.asked()) {
      case MEMBER -> member(known, context, candidates);
      case TABLE -> {
        known.schemas(candidates);
        known.tables(candidates);
      }
      default -> {
        for (var keyword : Keywords.offered(provider)) {
          candidates.add(new Candidate(keyword, keyword, keyword, null, "keyword", false));
        }
        known.schemas(candidates);
        known.tables(candidates);
        for (var reference : context.tables()) {
          var table = known.table(reference.schema(), reference.name());
          if (table != null) {
            known.columns(table, candidates);
          }
        }
      }
    }
    // A name of several tables or schemas is put in once for each kind of thing.
    var once = new LinkedHashMap<String, Candidate>();
    for (var c : candidates) {
      once.putIfAbsent(c.text() + "\0" + c.symbol(), c);
    }
    return Ranking.rank(once.values(), Candidate::name, Candidate::own, word).stream()
        .map(c -> new Completion.Entry(c.shown(), c.symbol(), c.kind(), c.text()))
        .toList();
  }

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

  /** The operators of queries, updates and pipelines, offered after a dollar. */
  static final List<String> OPERATORS = List.of(
      "$eq",
      "$ne",
      "$gt",
      "$gte",
      "$lt",
      "$lte",
      "$in",
      "$nin",
      "$and",
      "$or",
      "$nor",
      "$not",
      "$exists",
      "$type",
      "$regex",
      "$options",
      "$expr",
      "$elemMatch",
      "$size",
      "$all",
      "$text",
      "$search",
      "$set",
      "$unset",
      "$inc",
      "$mul",
      "$min",
      "$max",
      "$rename",
      "$push",
      "$pull",
      "$addToSet",
      "$pop",
      "$currentDate",
      "$setOnInsert",
      "$match",
      "$group",
      "$project",
      "$sort",
      "$limit",
      "$skip",
      "$unwind",
      "$lookup",
      "$count",
      "$addFields",
      "$replaceRoot",
      "$facet",
      "$bucket",
      "$sortByCount",
      "$sample",
      "$sum",
      "$avg",
      "$first",
      "$last");

  /** The methods of a cursor that find gives. */
  static final List<String> CURSOR_METHODS = List.of(
      "sort",
      "limit",
      "skip",
      "projection",
      "count",
      "explain",
      "pretty",
      "toArray",
      "hint",
      "maxTimeMS",
      "collation",
      "comment");

  /**
   * The shell's, by the tokens before the word: after {@code db.} the collections of the console's
   * database and the methods of db, after a collection its methods, after a call the methods of a
   * cursor, after a dollar the operators, and else the fields of the statement's collection and
   * the values the shell makes. Nothing in a string, a regular expression or a comment.
   */
  private List<Candidate> shell(State s, Provider provider, String before, String word) {
    var into = new ArrayList<Candidate>();
    // The caret with its word, also in the flags after a regular expression.
    var at = Lexer.tokens(before + word);
    if (!at.isEmpty()
        && at.getLast().end() == before.length() + word.length()
        && (at.getLast().open() || at.getLast().kind() == Lexer.Kind.REGEX)) {
      return into;
    }
    var tokens = Lexer.tokens(before);
    var code = tokens.stream().filter(t -> t.kind() != Lexer.Kind.COMMENT).toList();
    int n = code.size();
    if (n > 0 && code.getLast().end() == before.length() && is(before, code, n - 1, "$")) {
      // The kit's word has no dollar: it stays, and the rest of the operator goes after it.
      for (var operator : OPERATORS) {
        var rest = operator.substring(1);
        into.add(new Candidate(rest, operator, rest, Symbol.CODE, "operator", false));
      }
      return into;
    }
    if (n > 0
        && code.getLast().end() == before.length()
        && code.getLast().kind() != Lexer.Kind.PUNCTUATION) {
      return into; // the word goes on a name or a number, as total$ or 1.
    }
    var known = new Known(s, provider);
    boolean dot = n > 0 && code.getLast().is(before, '.');
    if (dot && n >= 2 && is(before, code, n - 2, "db")) {
      for (var table : known.tables()) {
        var name = table.name();
        var text = IDENTIFIER.matcher(name).matches() && !Script.DATABASE_METHODS.contains(name)
            ? name
            : "getCollection(" + Shell.quote(name) + ")";
        boolean view = table.kind() == Table.Kind.VIEW;
        into.add(new Candidate(
            name,
            name,
            text,
            view ? Symbol.EYE : Symbol.TABLE,
            view ? "view" : "collection",
            true));
      }
      for (var method : Script.DATABASE_METHODS) {
        into.add(new Candidate(method, method, method, Symbol.CODE, "method", false));
      }
      return into;
    }
    String collection = null; // the first that the statement names
    for (int k = 0; k < n; k++) {
      var named = collection(before, code, k);
      if (named == null) {
        continue;
      }
      if (dot && named.next() == n - 1) {
        for (var method : Script.READING_METHODS) {
          into.add(new Candidate(method, method, method, Symbol.CODE, "method", false));
        }
        for (var method : Script.WRITING_METHODS) {
          into.add(new Candidate(method, method, method, Symbol.EDIT, "method", false));
        }
        return into;
      }
      if (collection == null) {
        collection = named.name();
      }
    }
    if (dot && n >= 2 && code.get(n - 2).is(before, ')')) {
      for (var method : CURSOR_METHODS) {
        into.add(new Candidate(method, method, method, Symbol.CODE, "method", false));
      }
      return into;
    }
    var table = collection == null ? null : known.table(null, collection);
    if (table != null) {
      for (var column : columnsOf(s, known, table)) {
        var field = column.name();
        var text = IDENTIFIER.matcher(field).matches() ? field : Shell.quote(field);
        into.add(new Candidate(field, field, text, Symbol.COLUMN_SINGLE, column.type(), true));
      }
    }
    for (var constructor : Shell.OFFERED) {
      into.add(new Candidate(constructor, constructor, constructor, Symbol.CODE, "value", false));
    }
    return into;
  }

  /** A collection that tokens name, and the index of the token after the name. */
  private record Named(String name, int next) {}

  /**
   * The collection that the code from the token at k names, as {@code db.orders}, {@code
   * db.system.users} up to its method, or {@code db.getCollection('orders')}; null for none.
   */
  private static Named collection(String text, List<Lexer.Token> code, int k) {
    if (!is(text, code, k, "db") || !is(text, code, k + 1, ".") || k + 2 >= code.size()) {
      return null;
    }
    var name = code.get(k + 2);
    if (name.kind() != Lexer.Kind.NAME) {
      return null;
    }
    if (is(text, code, k + 2, "getCollection")
        && is(text, code, k + 3, "(")
        && k + 5 < code.size()
        && code.get(k + 4).kind() == Lexer.Kind.STRING
        && !code.get(k + 4).open()) {
      var quoted = code.get(k + 4);
      int close = is(text, code, k + 5, ",") ? k + 6 : k + 5; // a comma may end its arguments
      if (is(text, code, close, ")")) {
        return new Named(text.substring(quoted.start() + 1, quoted.end() - 1), close + 1);
      }
    }
    // shortcut: a name with dots is the collection's up to a name called after it.
    var dotted = new StringBuilder(text.substring(name.start(), name.end()));
    int j = k + 3;
    while (is(text, code, j, ".")
        && j + 1 < code.size()
        && code.get(j + 1).kind() == Lexer.Kind.NAME
        && !is(text, code, j + 2, "(")) {
      var part = code.get(j + 1);
      dotted.append('.').append(text, part.start(), part.end());
      j += 2;
    }
    return new Named(dotted.toString(), j);
  }

  // Whether the token at k is there and is the text.
  private static boolean is(String text, List<Lexer.Token> code, int k, String what) {
    if (k >= code.size()) {
      return false;
    }
    var token = code.get(k);
    return token.end() - token.start() == what.length() && text.startsWith(what, token.start());
  }

  // The fields the tree read of the collection, read now where it has not.
  private List<dev.hoori.db.core.catalog.Column> columnsOf(State s, Known known, Table table) {
    var columns = s.catalog.columns(tab.id, table);
    if (columns == null) {
      known.readColumns(table);
      return List.of();
    }
    return columns;
  }

  /** After {@code name.}: the columns of the table or alias, or else the tables of the schema. */
  private void member(Known known, Context context, List<Candidate> into) {
    var path = context.path();
    var last = path.getLast();
    if (path.size() >= 2) {
      var table = known.table(path.get(path.size() - 2), last);
      if (table != null) {
        known.columns(table, into);
      }
      return;
    }
    for (var reference : context.tables()) {
      if (reference.alias() != null
          ? known.same(reference.alias(), last)
          : known.same(reference.name(), last)) {
        var table = known.table(reference.schema(), reference.name());
        if (table != null) {
          known.columns(table, into);
        }
        return;
      }
    }
    var schema = known.tablesIn(last);
    if (schema != null) {
      known.tables(schema, into);
      return;
    }
    var table = known.table(null, last);
    if (table != null) {
      known.columns(table, into);
    }
  }

  /** Gives up the reads it asked for that have not delivered. */
  void close() {
    if (catalog != null) {
      asked.forEach(catalog::stop);
    }
    asked.clear();
  }

  /** Reads the node's tables or columns, unless it did already and that is under way or failed. */
  private void want(State s, CatalogTree.Live live, Node node) {
    var mine = asked.get(node);
    if (mine != null && live.scans.get(node) == mine) {
      return; // a failed read is not tried again from here: the tree and the palette say why
    }
    catalog = s.catalog;
    var scan = node instanceof Node.OfTable t
        ? catalog.scan(s, t)
        : catalog.scan(s, (Node.OfDatabase) node);
    if (scan != null) {
      asked.put(node, scan);
    }
  }

  /** What the tree has read of the console's connection, and what it asks for of what it lacks. */
  private final class Known {
    private final State s;
    private final Provider provider;
    private final CatalogTree.Live live; // null while not connected
    private final boolean schemas; // whether the server has schemas within its databases
    private final Node.OfDatabase database; // the console's, or null
    // Where PostgreSQL looks for a name without a schema, in order.
    // shortcut: the search path is taken to be PostgreSQL's default, "$user", public; read the
    // session's search_path once a console can change it or users set another.
    private final List<String> path;

    Known(State s, Provider provider) {
      this.s = s;
      this.provider = provider;
      var live = s.catalog.live(tab.id);
      var connected = live != null && live.phase instanceof CatalogTree.Connected c ? c : null;
      this.live = connected == null ? null : live;
      schemas = connected != null && connected.schemas();
      var name = !tab.database.isEmpty() || connected == null ? tab.database : connected.database();
      database = name == null ? null : database(name);
      var profile = s.profile(tab.id);
      path = profile == null ? List.of("public") : List.of(profile.user(), "public");
    }

    /** Whether two names are the same as the server compares them. */
    boolean same(String a, String b) {
      return provider.keepsDocuments() || provider.dialect().syntax().caseSensitiveNames()
          ? a.equals(b)
          : a.equalsIgnoreCase(b);
    }

    /** Reads the table's columns, as the tree would. */
    void readColumns(Table table) {
      if (live != null) {
        Suggestions.this.want(s, live, new Node.OfTable(tab.id, table));
      }
    }

    private List<Node> read(Node node) {
      return live != null && live.levels.get(node) instanceof CatalogTree.Read r
          ? r.children()
          : List.of();
    }

    private Node.OfDatabase database(String name) {
      for (var node : read(new Node.Connection(tab.id))) {
        if (node instanceof Node.OfDatabase d && same(d.database().name(), name)) {
          return d;
        }
      }
      return null;
    }

    /** The schemas of its database, or where there are none within databases, the databases. */
    void schemas(List<Candidate> into) {
      var nodes = schemas
          ? database == null ? List.<Node>of() : read(database)
          : read(new Node.Connection(tab.id));
      for (var node : nodes) {
        switch (node) {
          case Node.OfSchema x -> into.add(candidate(x.schema().name(), Symbol.FOLDER, "schema"));
          case Node.OfDatabase d ->
            into.add(candidate(d.database().name(), Symbol.DATABASE, "database"));
          default -> {}
        }
      }
    }

    /** The tables and views of its database, of all schemas but the system's; read if need be. */
    List<Table> tables() {
      if (database == null) {
        return List.of();
      }
      var tables = new ArrayList<Table>();
      boolean unread = !(live.levels.get(database) instanceof CatalogTree.Read);
      for (var node : read(database)) {
        switch (node) {
          case Node.OfTable t -> tables.add(t.table());
          case Node.OfSchema x
          when !x.schema().system() -> {
            unread |= CatalogTree.unread(live.levels.get(x));
            read(x).forEach(n -> tables.add(((Node.OfTable) n).table()));
          }
          default -> {}
        }
      }
      if (unread) {
        want(s, live, database);
      }
      return tables;
    }

    /**
     * The tables of the schema of that name, or where there are no schemas, of the database; null
     * where there is none such.
     */
    // shortcut: a system schema's tables are read only by the tree, as the database's read leaves
    // them out; read them here once completions in pg_catalog are asked for.
    List<Table> tablesIn(String name) {
      Node container = null;
      if (!schemas) {
        container = database(name);
      } else if (database != null) {
        for (var node : read(database)) {
          if (node instanceof Node.OfSchema x && same(x.schema().name(), name)) {
            container = node;
          }
        }
      }
      if (container == null) {
        return null;
      }
      boolean system = container instanceof Node.OfSchema x && x.schema().system();
      if (!system && CatalogTree.unread(live.levels.get(container))) {
        want(s, live, container instanceof Node.OfDatabase d ? d : database);
      }
      return read(container).stream().map(n -> ((Node.OfTable) n).table()).toList();
    }

    /**
     * The table of that name in the schema, or without one where the server finds it: on
     * PostgreSQL in the first schema of the search path that has it, on MariaDB and MySQL in the
     * console's database; null for none.
     */
    Table table(String schema, String name) {
      if (schema == null && schemas) {
        tables(); // reads the database's tables where it has not
        for (var first : path) {
          var found = table(first, name);
          if (found != null) {
            return found;
          }
        }
        return null;
      }
      var tables = schema == null ? tables() : tablesIn(schema);
      if (tables == null) {
        return null;
      }
      Table found = null;
      for (var table : tables) {
        // An exact match, else one of another case; the first schema that has it.
        if (table.name().equals(name)) {
          return table;
        }
        if (found == null && same(table.name(), name)) {
          found = table;
        }
      }
      return found;
    }

    /**
     * The tables and views of its database: as they are on the search path, with their schema
     * where they are not, so that the name inserted is one the server finds; those on the path
     * first, so that a name that two schemas have is the one the server takes.
     */
    void tables(List<Candidate> into) {
      var qualified = new ArrayList<Candidate>();
      var tables = new ArrayList<>(tables());
      tables.sort(Comparator.comparingInt(t -> t.container() instanceof Schema x
          ? path.contains(x.name()) ? path.indexOf(x.name()) : path.size()
          : 0));
      for (var table : tables) {
        if (!(table.container() instanceof Schema x) || path.contains(x.name())) {
          tables(List.of(table), into);
        } else {
          boolean view = table.kind() == Table.Kind.VIEW;
          qualified.add(new Candidate(
              table.name(),
              x.name() + "." + table.name(),
              Names.quoted(x.name(), provider) + "." + Names.quoted(table.name(), provider),
              view ? Symbol.EYE : Symbol.TABLE,
              view ? "view" : "table",
              false));
        }
      }
      into.addAll(qualified);
    }

    void tables(List<Table> tables, List<Candidate> into) {
      for (var table : tables) {
        boolean view = table.kind() == Table.Kind.VIEW;
        into.add(
            candidate(table.name(), view ? Symbol.EYE : Symbol.TABLE, view ? "view" : "table"));
      }
    }

    /** The table's columns, of the statement, with their types; read if need be. */
    void columns(Table table, List<Candidate> into) {
      var columns = s.catalog.columns(tab.id, table);
      if (columns == null) {
        want(s, live, new Node.OfTable(tab.id, table));
        return;
      }
      for (var column : columns) {
        var name = column.name();
        into.add(new Candidate(
            name, name, Names.quoted(name, provider), Symbol.COLUMN_SINGLE, column.type(), true));
      }
    }

    private Candidate candidate(String name, Symbol symbol, String kind) {
      return new Candidate(name, name, Names.quoted(name, provider), symbol, kind, false);
    }
  }
}
