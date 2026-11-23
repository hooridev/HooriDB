package dev.hoori.db.app;

import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.sql.Ranking;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What an editor or clause field suggests at the caret, as its language's {@link Strategy} makes it
 * from what the tree has read of the connection and database. What it lacks it reads in the
 * background on the tree's connection, behind what the tree and the tabs ask for, and suggests at
 * the next keystroke; never on a console's own connection, which may be busy or in a transaction.
 * Closing the tab gives up those reads. Only the app's thread uses it.
 */
final class Suggestions {

  /**
   * A name it may suggest, as it is listed, and as it is put in, quoted where it needs to be; and
   * whether it belongs to the statement's tables.
   */
  record Candidate(
      String name, String shown, String text, Symbol symbol, String kind, boolean own) {}

  /** What a language suggests: its words, and the names of what the tree has read. */
  sealed interface Strategy permits SqlCompletion, ShellCompletion {

    /** Whether the server compares names as they are written, case and all. */
    boolean exactNames(Provider provider);

    /**
     * The candidates for the word before the caret in the statement, unranked.
     *
     * @param caret the caret's offset in the statement
     */
    List<Candidate> candidates(Known known, String statement, int caret, String word);
  }

  private final ConsoleTab tab; // the console it suggests in, or null
  private final TableTab grid; // or the table tab whose clauses it suggests in, or null
  private final Map<Node, CatalogTree.Scan> asked = new HashMap<>(); // the reads it started
  private CatalogTree catalog; // where they were asked, once they are
  // The last entries, and what they were made for: the list keeps them while only its highlight
  // moves, and until it closes.
  private List<Completion.Entry> entries;
  private String madeFor;
  private int madeAt;
  private String madeOf;
  private Settings.KeywordCase madeCase;
  int made; // how often they were made, which a test counts

  Suggestions(ConsoleTab tab) {
    this.tab = tab;
    this.grid = null;
  }

  /** For the clauses of a table tab, one statement at a time, with the columns the tab read. */
  Suggestions(TableTab grid) {
    this.tab = null;
    this.grid = grid;
  }

  /**
   * The entries for the word before the caret, best first: made again only where the text, the
   * caret, the word or the keyword case changed since the last, or the list was closed meanwhile.
   */
  List<Completion.Entry> at(State s, Provider provider, String word) {
    var text = tab.text;
    int caret = Math.min(tab.editor.caret(), text.length());
    var keywordCase = s.settings.keywordCase();
    if (entries != null
        && text.equals(madeFor)
        && caret == madeAt
        && word.equals(madeOf)
        && keywordCase == madeCase) {
      return entries;
    }
    // The statement the caret writes in, with the comments around it, from the console's split.
    var around = tab.statements(provider).around(caret);
    var statement = text.substring(around.start(), around.end());
    entries = make(s, provider, tab.database, statement, caret - around.start(), word);
    made++;
    madeFor = text;
    madeAt = caret;
    madeOf = word;
    madeCase = keywordCase;
    return entries;
  }

  /**
   * The entries for the word before the caret in one statement on the database, such as the
   * {@code SELECT} a table tab's clause field is a part of.
   */
  List<Completion.Entry> at(
      State s, Provider provider, String database, String statement, int caret, String word) {
    int at = Math.min(caret, statement.length());
    var keywordCase = s.settings.keywordCase();
    if (entries != null
        && statement.equals(madeFor)
        && at == madeAt
        && word.equals(madeOf)
        && keywordCase == madeCase) {
      return entries;
    }
    entries = make(s, provider, database, statement, at, word);
    made++;
    madeFor = statement;
    madeAt = at;
    madeOf = word;
    madeCase = keywordCase;
    return entries;
  }

  /** The list closed: the next one is made anew, with what was read meanwhile. */
  void forget() {
    entries = null;
  }

  /** What the tree has read of the console's connection and database, asking for what it lacks. */
  Known known(State s, Provider provider) {
    var strategy = Language.of(provider).completion();
    return new Known(s, provider, tab.database, strategy.exactNames(provider));
  }

  private List<Completion.Entry> make(
      State s, Provider provider, String database, String statement, int caret, String word) {
    var strategy = Language.of(provider).completion();
    var known = new Known(s, provider, database, strategy.exactNames(provider));
    return Ranking.rank(
            strategy.candidates(known, statement, caret, word),
            Candidate::name,
            Candidate::own,
            word)
        .stream()
        .map(c -> new Completion.Entry(c.shown(), c.symbol(), c.kind(), c.text()))
        .toList();
  }

  /** Gives up the reads it asked for that have not delivered. */
  void close() {
    if (catalog != null) {
      asked.forEach(catalog::stop);
    }
    asked.clear();
  }

  /** What the tree has read of the connection, and what it asks for of what it lacks. */
  final class Known {
    final Provider provider;
    final Settings.KeywordCase keywordCase;
    final boolean schemas; // whether the server has schemas within its databases
    // Where PostgreSQL looks for a name without a schema, in order.
    // shortcut: the search path is taken to be PostgreSQL's default, "$user", public; read the
    // session's search_path once a console can change it or users set another.
    final List<String> path;
    private final State s;
    private final boolean exact;
    private final Live live; // null while not connected
    private final String named; // the current database, or null
    private final UUID connection;
    private Node.OfDatabase console; // the node of that name, or null, once looked up
    private boolean looked;

    private Known(State s, Provider provider, String database, boolean exact) {
      this.s = s;
      this.provider = provider;
      this.keywordCase = s.settings.keywordCase();
      this.exact = exact;
      connection = tab != null ? tab.id : grid.id;
      var opened = s.catalog.live(connection);
      var connected = opened != null && opened.phase instanceof Live.Connected c ? c : null;
      this.live = connected == null ? null : opened;
      schemas = connected != null && connected.schemas();
      named = !database.isEmpty() || connected == null ? database : connected.database();
      var profile = s.profile(connection);
      path = profile == null ? List.of("public") : List.of(profile.user(), "public");
    }

    /** Whether two names are the same as the server compares them. */
    boolean same(String a, String b) {
      return exact ? a.equals(b) : a.equalsIgnoreCase(b);
    }

    /**
     * Reads the node's tables or columns, unless it did already and that is under way or failed.
     */
    private void want(Node node) {
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

    private List<Node> read(Node node) {
      return live != null && live.levels.get(node) instanceof CatalogTree.Read(var children)
          ? children
          : List.of();
    }

    private Node.OfDatabase database(String name) {
      for (var node : read(new Node.Connection(connection))) {
        if (node instanceof Node.OfDatabase d && same(d.database().name(), name)) {
          return d;
        }
      }
      return null;
    }

    // The current database, looked up once and only where a candidate needs it.
    private Node.OfDatabase database() {
      if (!looked) {
        looked = true;
        console = named == null ? null : database(named);
      }
      return console;
    }

    /**
     * The schemas of its database, or where there are none within databases, the databases, as
     * the tree has read them.
     */
    List<Node> schemasOrDatabases() {
      if (!schemas) {
        return read(new Node.Connection(connection));
      }
      var database = database();
      return database == null ? List.of() : read(database);
    }

    /** The tables and views of its database, of all schemas but the system's; read if need be. */
    List<Table> tables() {
      var database = database();
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
          default -> {
            // A system schema's tables are not suggested.
          }
        }
      }
      if (unread) {
        want(database);
      }
      return tables;
    }

    /**
     * The tables of the schema of that name, or where there are no schemas, of the database; empty
     * where there is none such.
     */
    // shortcut: a system schema's tables are read only by the tree, as the database's read leaves
    // them out; read them here once completions in pg_catalog are asked for.
    Optional<List<Table>> tablesIn(String name) {
      var database = database();
      Node container = !schemas ? database(name) : schema(database, name);
      if (container == null) {
        return Optional.empty();
      }
      boolean system = container instanceof Node.OfSchema x && x.schema().system();
      if (!system && CatalogTree.unread(live.levels.get(container))) {
        want(container instanceof Node.OfDatabase d ? d : database);
      }
      return Optional.of(
          read(container).stream().map(n -> ((Node.OfTable) n).table()).toList());
    }

    // The schema of that name in the database, null for none; reads the database where it has not.
    private Node schema(Node.OfDatabase database, String name) {
      if (database == null) {
        return null;
      }
      if (CatalogTree.unread(live.levels.get(database))) {
        want(database); // its schemas, as tables() reads them
      }
      Node found = null;
      for (var node : read(database)) {
        if (node instanceof Node.OfSchema x && same(x.schema().name(), name)) {
          found = node;
        }
      }
      return found;
    }

    /**
     * The table of that name in the schema, or without one where the server finds it: on
     * PostgreSQL in the first schema of the search path that has it, on MariaDB and MySQL in the
     * current database; null for none.
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
      var in = schema == null ? Optional.of(tables()) : tablesIn(schema);
      if (in.isEmpty()) {
        return null;
      }
      var tables = in.get();
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

    /** The table's columns, with their types; none yet where it reads them now. */
    List<Column> columns(Table table) {
      if (grid != null && table.equals(grid.table) && grid.columns != null) {
        return grid.columns;
      }
      var columns = s.catalog.columns(connection, table);
      if (columns == null) {
        if (live != null) {
          want(new Node.OfTable(connection, table));
        }
        return List.of();
      }
      return columns;
    }
  }
}
