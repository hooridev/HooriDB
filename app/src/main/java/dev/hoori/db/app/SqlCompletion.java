package dev.hoori.db.app;

import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.dialect.SyntaxDialect;
import dev.hoori.db.core.sql.Context;
import dev.hoori.db.core.sql.Keywords;
import dev.hoori.db.core.sql.Names;
import dev.hoori.db.core.sql.Ranking;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a console of SQL suggests, by what the statement asks for at the caret: after {@code
 * name.} the columns of the table or alias, or the tables of the schema; after a conditional {@code
 * JOIN} foreign-key joins ahead of schemas and tables; after a join's {@code ON} foreign-key
 * conditions ahead of keywords, schemas, tables and columns; where a table goes the schemas and
 * tables; and else the keywords, schemas, tables, and the columns of the statement's tables.
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
      var asked = Context.at(statement, SyntaxDialect.STANDARD, caret).asked();
      if (asked == Context.Asked.ANY || asked == Context.Asked.ON) {
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
      case JOIN -> {
        joins(known, context, candidates);
        schemas(known, candidates);
        tables(known, candidates);
      }
      case ON -> {
        conditions(known, context, candidates);
        any(known, provider, context, candidates);
      }
      default -> any(known, provider, context, candidates);
    }
    // A name of several tables or schemas is put in once for each kind of thing.
    var once = new LinkedHashMap<String, Suggestions.Candidate>();
    for (var c : candidates) {
      once.putIfAbsent(c.text() + "\0" + c.symbol(), c);
    }
    return List.copyOf(once.values());
  }

  private static void any(
      Suggestions.Known known,
      Provider provider,
      Context context,
      List<Suggestions.Candidate> candidates) {
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

  private record Side(Context.Reference ref, Table table, String qualifier) {
    boolean matches(Table candidate, Suggestions.Known known) {
      if (table != null) {
        return candidate.equals(table);
      }
      if (!known.same(candidate.name(), ref.name())) {
        return false;
      }
      if (ref.schema() != null) {
        return known.same(candidate.container().name(), ref.schema());
      }
      return !(candidate.container() instanceof Schema x) || known.path.contains(x.name());
    }
  }

  private record Link(Table other, List<String> newCols, List<String> existingCols) {}

  /**
   * Foreign-key joins from each table named before the {@code JOIN} slot, in both directions, one
   * candidate per key inserting the table, optional alias and full {@code ON} condition.
   */
  private static void joins(
      Suggestions.Known known, Context context, List<Suggestions.Candidate> into) {
    var prior = context.left();
    if (prior.isEmpty()) {
      return;
    }
    // The names the new alias must not take: the statement's, but for the word being typed, the
    // reference that follows the last of the left ones.
    var used = new ArrayList<>(context.tables());
    int last = 0;
    while (used.get(last) != prior.getLast()) {
      last++;
    }
    if (!context.typed().isEmpty() && last + 1 < used.size()) {
      used.remove(last + 1);
    }
    boolean aliased = used.stream().anyMatch(r -> r.alias() != null);
    for (var ref : prior) {
      var table = known.table(ref.schema(), ref.name());
      if (table == null) {
        continue;
      }
      var keys = known.foreignKeys(table);
      var source = new Side(ref, table, qualifier(ref, known.provider));
      for (var key : ForeignKeys.outgoing(table, keys)) {
        addJoin(
            known,
            prior,
            used,
            aliased,
            source,
            new Link(key.referenced(), key.referencedColumns(), key.columns()),
            into);
      }
      for (var key : ForeignKeys.incoming(table, keys)) {
        addJoin(
            known,
            prior,
            used,
            aliased,
            source,
            new Link(key.table(), key.columns(), key.referencedColumns()),
            into);
      }
    }
  }

  private static void addJoin(
      Suggestions.Known known,
      List<Context.Reference> prior,
      List<Context.Reference> used,
      boolean aliased,
      Side source,
      Link link,
      List<Suggestions.Candidate> into) {
    if (Math.min(link.newCols().size(), link.existingCols().size()) == 0) {
      return;
    }
    var provider = known.provider;
    var tableRef = tableName(link.other(), known);
    boolean needsAlias = aliased || alreadyUnaliased(used, known, link.other());
    String tableClause;
    String newQual;
    if (needsAlias) {
      var alias = Names.quoted(alias(link.other().name(), used, known), provider);
      tableClause = tableRef + " " + alias;
      newQual = alias;
    } else {
      tableClause = tableRef;
      newQual = tableRef;
    }
    var on = known.keywordCase == Settings.KeywordCase.LOWER ? " on " : " ON ";
    var full = tableClause
        + on
        + condition(newQual, link.newCols(), source.qualifier(), link.existingCols(), known);
    into.add(new Suggestions.Candidate(
        link.other().name(), full, full, Symbol.LINK, "join", Ranking.FIRST));
  }

  /**
   * Foreign-key {@code ON} conditions between the table just joined and every table named before
   * it in the statement, checking cached keys on both sides.
   */
  private static void conditions(
      Suggestions.Known known, Context context, List<Suggestions.Candidate> into) {
    var left = context.left();
    if (left.size() < 2) {
      return;
    }
    var joinedRef = left.getLast();
    var joined = new Side(
        joinedRef,
        known.table(joinedRef.schema(), joinedRef.name()),
        qualifier(joinedRef, known.provider));
    var joinedKeys =
        joined.table() == null ? List.<ForeignKey>of() : known.foreignKeys(joined.table());
    for (var prevRef : left.subList(0, left.size() - 1)) {
      var prev = new Side(
          prevRef,
          known.table(prevRef.schema(), prevRef.name()),
          qualifier(prevRef, known.provider));
      addConditions(joined, prev, joinedKeys, known, into);
      if (prev.table() != null) {
        addConditions(joined, prev, known.foreignKeys(prev.table()), known, into);
      }
    }
  }

  private static void addConditions(
      Side joined,
      Side prev,
      List<ForeignKey> keys,
      Suggestions.Known known,
      List<Suggestions.Candidate> into) {
    for (var key : keys) {
      if (joined.matches(key.table(), known) && prev.matches(key.referenced(), known)) {
        addCondition(
            joined.qualifier(),
            key.columns(),
            prev.qualifier(),
            key.referencedColumns(),
            known,
            into);
      }
      if (joined.matches(key.referenced(), known) && prev.matches(key.table(), known)) {
        addCondition(
            joined.qualifier(),
            key.referencedColumns(),
            prev.qualifier(),
            key.columns(),
            known,
            into);
      }
    }
  }

  private static void addCondition(
      String leftQual,
      List<String> leftCols,
      String rightQual,
      List<String> rightCols,
      Suggestions.Known known,
      List<Suggestions.Candidate> into) {
    if (Math.min(leftCols.size(), rightCols.size()) == 0) {
      return;
    }
    var cond = condition(leftQual, leftCols, rightQual, rightCols, known);
    into.add(new Suggestions.Candidate(cond, cond, cond, Symbol.LINK, "condition", Ranking.FIRST));
  }

  private static String condition(
      String leftQual,
      List<String> leftCols,
      String rightQual,
      List<String> rightCols,
      Suggestions.Known known) {
    int count = Math.min(leftCols.size(), rightCols.size());
    var and = known.keywordCase == Settings.KeywordCase.LOWER ? " and " : " AND ";
    var sb = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        sb.append(and);
      }
      sb.append(leftQual)
          .append('.')
          .append(Names.quoted(leftCols.get(i), known.provider))
          .append(" = ")
          .append(rightQual)
          .append('.')
          .append(Names.quoted(rightCols.get(i), known.provider));
    }
    return sb.toString();
  }

  /** How an existing table reference is qualified in a join condition: alias, or as written. */
  private static String qualifier(Context.Reference ref, Provider provider) {
    if (ref.alias() != null) {
      return Names.quoted(ref.alias(), provider);
    }
    if (ref.schema() != null) {
      return Names.quoted(ref.schema(), provider) + "." + Names.quoted(ref.name(), provider);
    }
    return Names.quoted(ref.name(), provider);
  }

  /**
   * How a joined table is written: as it is where its name alone finds it, else with its schema,
   * or on a server without schemas its database.
   */
  private static String tableName(Table other, Suggestions.Known known) {
    var name = Names.quoted(other.name(), known.provider);
    return other.equals(known.table(null, other.name()))
        ? name
        : Names.quoted(other.container().name(), known.provider) + "." + name;
  }

  // Whether the statement names a table of that name without an alias: the same table, or one of
  // another schema, which the server would not tell apart from the new one.
  private static boolean alreadyUnaliased(
      List<Context.Reference> used, Suggestions.Known known, Table other) {
    for (var ref : used) {
      if (ref.alias() == null && known.same(ref.name(), other.name())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Short alias from the initials of {@code tableName}'s word parts ({@code order_items} -> {@code
   * oi}), appending a number on a clash with an existing alias, unaliased table name, or reserved
   * word.
   */
  private static String alias(
      String tableName, List<Context.Reference> prior, Suggestions.Known known) {
    var sb = new StringBuilder();
    boolean start = true;
    for (int i = 0; i < tableName.length(); ) {
      int cp = tableName.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isLetterOrDigit(cp)) {
        if (start) {
          sb.appendCodePoint(Character.toLowerCase(cp));
          start = false;
        }
      } else {
        start = true;
      }
    }
    var base = sb.isEmpty() || !Character.isLetter(sb.codePointAt(0)) ? "t" : sb.toString();
    var candidate = base;
    for (int n = 2;
        taken(candidate, prior) || Keywords.isReserved(candidate, known.provider);
        n++) {
      candidate = base + n;
    }
    return candidate;
  }

  private static boolean taken(String name, List<Context.Reference> prior) {
    for (var ref : prior) {
      var used = ref.alias() != null ? ref.alias() : ref.name();
      if (used.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
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
          into.add(candidate(known, x.schema().name(), Glyphs.SCHEMA, "schema"));
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
      Suggestions.Known known, String name, Icon symbol, String kind) {
    return new Suggestions.Candidate(
        name, name, Names.quoted(name, known.provider), symbol, kind, false);
  }
}
