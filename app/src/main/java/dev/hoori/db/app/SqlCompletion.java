package dev.hoori.db.app;

import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.dialect.SyntaxDialect;
import dev.hoori.db.core.sql.Context;
import dev.hoori.db.core.sql.Keywords;
import dev.hoori.db.core.sql.Names;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a console of SQL suggests, by what the statement asks for at the caret: after {@code
 * name.} the columns of the table or alias, or the tables of the schema; where a table goes the
 * schemas and tables; and else the keywords, schemas, tables, and the columns of the statement's
 * tables.
 */
record SqlCompletion() implements Suggestions.Strategy {

  @Override
  public boolean exactNames(Provider provider) {
    return provider != null && provider.dialect().syntax().caseSensitiveNames();
  }

  @Override
  public List<Suggestions.Candidate> candidates(
      Suggestions.Known known, String statement, int caret, String word) {
    var provider = known.provider;
    var candidates = new ArrayList<Suggestions.Candidate>();
    if (provider == null) { // a workspace query before its target: the words all servers share
      if (Context.at(statement, SyntaxDialect.STANDARD, caret).asked() == Context.Asked.ANY) {
        keywords(known, SyntaxDialect.STANDARD.offeredWords(), candidates);
      }
      return candidates;
    }
    var context = Context.at(statement, provider, caret);
    if (context.asked() == Context.Asked.NOTHING) {
      return List.of();
    }
    switch (context.asked()) {
      case MEMBER -> member(known, context, candidates);
      case TABLE -> {
        schemas(known, candidates);
        tables(known, candidates);
      }
      default -> {
        keywords(known, Keywords.offered(provider), candidates);
        schemas(known, candidates);
        tables(known, candidates);
        for (var reference : context.tables()) {
          var table = known.table(reference.schema(), reference.name());
          if (table != null) {
            columns(known, table, candidates);
          }
        }
      }
    }
    // A name of several tables or schemas is put in once for each kind of thing.
    var once = new LinkedHashMap<String, Suggestions.Candidate>();
    for (var c : candidates) {
      once.putIfAbsent(c.text() + "\0" + c.symbol(), c);
    }
    return List.copyOf(once.values());
  }

  private static void keywords(
      Suggestions.Known known, Set<String> words, List<Suggestions.Candidate> candidates) {
    boolean lower = known.keywordCase == Settings.KeywordCase.LOWER;
    for (var keyword : words) {
      var written = lower ? keyword.toLowerCase(Locale.ROOT) : keyword;
      candidates.add(new Suggestions.Candidate(written, written, written, null, "keyword", false));
    }
  }

  /** After {@code name.}: the columns of the table or alias, or else the tables of the schema. */
  private static void member(
      Suggestions.Known known, Context context, List<Suggestions.Candidate> into) {
    var path = context.path();
    var last = path.getLast();
    if (path.size() >= 2) {
      var table = known.table(path.get(path.size() - 2), last);
      if (table != null) {
        columns(known, table, into);
      }
      return;
    }
    for (var reference : context.tables()) {
      if (reference.alias() != null
          ? known.same(reference.alias(), last)
          : known.same(reference.name(), last)) {
        var table = known.table(reference.schema(), reference.name());
        if (table != null) {
          columns(known, table, into);
        }
        return;
      }
    }
    var schema = known.tablesIn(last);
    if (schema.isPresent()) {
      tables(known, schema.get(), into);
      return;
    }
    var table = known.table(null, last);
    if (table != null) {
      columns(known, table, into);
    }
  }

  /** The schemas of its database, or where there are none within databases, the databases. */
  private static void schemas(Suggestions.Known known, List<Suggestions.Candidate> into) {
    for (var node : known.schemasOrDatabases()) {
      switch (node) {
        case Node.OfSchema x ->
          into.add(candidate(known, x.schema().name(), Symbol.FOLDER, "schema"));
        case Node.OfDatabase d ->
          into.add(candidate(known, d.database().name(), Symbol.DATABASE, "database"));
        default -> {
          // Nothing else stands beside schemas and databases.
        }
      }
    }
  }

  /**
   * The tables and views of its database: as they are on the search path, with their schema
   * where they are not, so that the name inserted is one the server finds; those on the path
   * first, so that a name that two schemas have is the one the server takes.
   */
  private static void tables(Suggestions.Known known, List<Suggestions.Candidate> into) {
    var provider = known.provider;
    var path = known.path;
    var qualified = new ArrayList<Suggestions.Candidate>();
    var tables = new ArrayList<>(known.tables());
    tables.sort(Comparator.comparingInt(table -> onPath(path, table)));
    for (var table : tables) {
      if (!(table.container() instanceof Schema x) || path.contains(x.name())) {
        tables(known, List.of(table), into);
      } else {
        boolean view = table.kind() == Table.Kind.VIEW;
        qualified.add(new Suggestions.Candidate(
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

  /** Where the table's schema stands on the search path: first without one, last off it. */
  private static int onPath(List<String> path, Table table) {
    if (!(table.container() instanceof Schema x)) {
      return 0;
    }
    return path.contains(x.name()) ? path.indexOf(x.name()) : path.size();
  }

  private static void tables(
      Suggestions.Known known, List<Table> tables, List<Suggestions.Candidate> into) {
    for (var table : tables) {
      boolean view = table.kind() == Table.Kind.VIEW;
      into.add(candidate(
          known, table.name(), view ? Symbol.EYE : Symbol.TABLE, view ? "view" : "table"));
    }
  }

  /** The table's columns, of the statement, with their types; read if need be. */
  private static void columns(
      Suggestions.Known known, Table table, List<Suggestions.Candidate> into) {
    for (var column : known.columns(table)) {
      var name = column.name();
      into.add(new Suggestions.Candidate(
          name,
          name,
          Names.quoted(name, known.provider),
          Symbol.COLUMN_SINGLE,
          column.type(),
          true));
    }
  }

  private static Suggestions.Candidate candidate(
      Suggestions.Known known, String name, Symbol symbol, String kind) {
    return new Suggestions.Candidate(
        name, name, Names.quoted(name, known.provider), symbol, kind, false);
  }
}
