package dev.hoori.db.app;

import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Sql;
import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Context;
import dev.hoori.db.core.sql.Names;
import dev.hoori.db.core.sql.Ranking;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a console's editor suggests at the caret: keywords, and the schemas, tables, views and
 * columns that the tree has read of the console's connection and database. What it lacks it reads
 * in the background on the tree's connection, behind what the tree and the tabs ask for, and
 * suggests at the next keystroke; never on the console's own connection, which may be busy or in
 * a transaction. Closing the console gives up those reads. Only the app's thread uses it.
 */
final class Suggestions {

  /** A name it may suggest, and whether it belongs to the statement's tables. */
  private record Candidate(String name, String text, Symbol symbol, String kind, boolean own) {}

  private final ConsoleTab tab;
  private final Map<Node, Catalogue.Scan> asked = new HashMap<>(); // the reads it started
  private Catalogue catalogue; // where they were asked, once they are

  Suggestions(ConsoleTab tab) {
    this.tab = tab;
  }

  /** The entries for the word before the caret, best first. */
  List<Completion.Entry> at(State s, Provider provider, String word) {
    var text = tab.text;
    int caret = Math.min(tab.editor.caret(), text.length());
    // The statement the caret is in, also after spaces at its end; none after its terminator.
    var statement = Statements.at(text, provider, caret)
        .filter(at -> at.start() <= caret
            && (caret <= at.end() || text.substring(at.end(), caret).isBlank()));
    var sql = statement
        .map(at -> text.substring(at.start(), Math.max(at.end(), caret)))
        .orElse("");
    var context =
        Context.at(sql, provider, statement.map(at -> caret - at.start()).orElse(0));
    if (context.asked() == Context.Asked.NOTHING) {
      return List.of();
    }
    var known = new Known(s, provider);
    var candidates = new ArrayList<Candidate>();
    switch (context.asked()) {
      case MEMBER -> member(known, context, candidates);
      case TABLE -> {
        known.schemas(candidates);
        known.tables(known.tables(), candidates);
      }
      default -> {
        for (var keyword : Sql.keywords()) {
          candidates.add(new Candidate(keyword, keyword, null, "keyword", false));
        }
        known.schemas(candidates);
        known.tables(known.tables(), candidates);
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
        .map(c -> new Completion.Entry(c.text(), c.symbol(), c.kind()))
        .toList();
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
    if (catalogue != null) {
      asked.forEach(catalogue::stop);
    }
    asked.clear();
  }

  /** Reads the node's tables or columns, unless it did already and that is under way or failed. */
  private void want(State s, Catalogue.Live live, Node node) {
    var mine = asked.get(node);
    if (mine != null && live.scans.get(node) == mine) {
      return; // a failed read is not tried again from here: the tree and the palette say why
    }
    catalogue = s.catalogue;
    var scan = node instanceof Node.OfTable t
        ? catalogue.scan(s, t)
        : catalogue.scan(s, (Node.OfDatabase) node);
    if (scan != null) {
      asked.put(node, scan);
    }
  }

  /** What the tree has read of the console's connection, and what it asks for of what it lacks. */
  private final class Known {
    private final State s;
    private final Provider provider;
    private final Catalogue.Live live; // null while not connected
    private final boolean schemas; // whether the server has schemas within its databases
    private final Node.OfDatabase database; // the console's, or null

    Known(State s, Provider provider) {
      this.s = s;
      this.provider = provider;
      var live = s.catalogue.live(tab.id);
      var connected = live != null && live.phase instanceof Catalogue.Connected c ? c : null;
      this.live = connected == null ? null : live;
      schemas = connected != null && connected.schemas();
      var name = !tab.database.isEmpty() || connected == null ? tab.database : connected.database();
      database = name == null ? null : database(name);
    }

    /** Whether two names are the same as the server compares them. */
    boolean same(String a, String b) {
      return provider == Provider.POSTGRESQL ? a.equals(b) : a.equalsIgnoreCase(b);
    }

    private List<Node> read(Node node) {
      return live != null && live.levels.get(node) instanceof Catalogue.Read r
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
      boolean unread = !(live.levels.get(database) instanceof Catalogue.Read);
      for (var node : read(database)) {
        switch (node) {
          case Node.OfTable t -> tables.add(t.table());
          case Node.OfSchema x
          when !x.schema().system() -> {
            unread |= Catalogue.unread(live.levels.get(x));
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
      if (!system && Catalogue.unread(live.levels.get(container))) {
        want(s, live, container instanceof Node.OfDatabase d ? d : database);
      }
      return read(container).stream().map(n -> ((Node.OfTable) n).table()).toList();
    }

    /** The table of that name, in the schema or else among those of its database, or null. */
    Table table(String schema, String name) {
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

    void tables(List<Table> tables, List<Candidate> into) {
      for (var table : tables) {
        boolean view = table.kind() == Table.Kind.VIEW;
        into.add(
            candidate(table.name(), view ? Symbol.EYE : Symbol.TABLE, view ? "view" : "table"));
      }
    }

    /** The table's columns, of the statement, with their types; read if need be. */
    void columns(Table table, List<Candidate> into) {
      var columns = s.catalogue.columns(tab.id, table);
      if (columns == null) {
        want(s, live, new Node.OfTable(tab.id, table));
        return;
      }
      for (var column : columns) {
        var name = column.name();
        into.add(new Candidate(
            name, Names.quoted(name, provider), Symbol.COLUMN_SINGLE, column.type(), true));
      }
    }

    private Candidate candidate(String name, Symbol symbol, String kind) {
      return new Candidate(name, Names.quoted(name, provider), symbol, kind, false);
    }
  }
}
