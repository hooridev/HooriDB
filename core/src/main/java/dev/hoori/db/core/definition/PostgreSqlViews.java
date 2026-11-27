package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How PostgreSQL's views are read and changed: what a view uses and what uses it from {@code
 * pg_depend}, through the rules that make it; changed in one transaction, a drop refused while
 * something depends on it, which the server then names, and a replace in place refused where its
 * columns would change, which then drops it and creates it anew with what the drop took: its
 * owner, triggers and rules, and a materialized view's rows. What was granted on it is lost.
 */
public final class PostgreSqlViews extends ViewDialect {

  private static final String CLASS = "'pg_catalog.pg_class'::pg_catalog.regclass";
  private static final String PROC = "'pg_catalog.pg_proc'::pg_catalog.regclass";
  private static final String TYPE = "'pg_catalog.pg_type'::pg_catalog.regclass";

  // A statement that creates a view: CREATE and the options before the word VIEW, then that word.
  private static final Pattern CREATE = Pattern.compile(
      "(?is)CREATE(\\s+OR\\s+REPLACE)?(?:\\s+TEMP(?:ORARY)?)?(?:\\s+RECURSIVE)?\\s+");
  private static final Pattern VIEW_WORD =
      Pattern.compile("(?is)(MATERIALIZED\\s+)?VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?");

  public PostgreSqlViews() {
    // Nothing to set up.
  }

  // The view by its names, with its row type, which a routine's result may use.
  private static final String VIEW = """
      WITH v AS (SELECT c.oid, c.reltype FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('v', 'm'))
      """;

  // A relation or routine that a row of pg_depend names, in a schema that is not the server's
  // own: a relation's kind, or a routine's kind and arguments.
  private static final String RELATIONS = """
      SELECT DISTINCT n.nspname, c.relname, c.relkind, ''
      FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
      WHERE c.oid IN (%s) AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'""";

  private static final String ROUTINES = """
      SELECT DISTINCT n.nspname, p.proname,
        CASE p.prokind WHEN 'p' THEN 'procedure' ELSE 'function' END,
        pg_catalog.pg_get_function_identity_arguments(p.oid)
      FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
      WHERE p.oid IN (%s) AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'""";

  // What the view's rules read; what reads the view in its rules, or in a routine's body or
  // result, which keep it from being dropped alone.
  @Override
  public References references(Sql sql, Table view) throws QueryException {
    var rules = """
        SELECT d.refobjid FROM pg_catalog.pg_depend d
        JOIN pg_catalog.pg_rewrite r ON r.oid = d.objid
        WHERE d.classid = 'pg_catalog.pg_rewrite'::pg_catalog.regclass
        AND r.ev_class = (SELECT oid FROM v) AND d.refobjid <> (SELECT oid FROM v)
        AND d.refclassid = %s""";
    var readers = """
        SELECT r.ev_class FROM pg_catalog.pg_depend d
        JOIN pg_catalog.pg_rewrite r ON r.oid = d.objid
        WHERE d.classid = 'pg_catalog.pg_rewrite'::pg_catalog.regclass
        AND d.refclassid = %1$s AND d.refobjid = (SELECT oid FROM v)
        AND r.ev_class <> (SELECT oid FROM v)""".formatted(CLASS);
    var routines = """
        SELECT d.objid FROM pg_catalog.pg_depend d
        WHERE d.classid = %1$s AND d.deptype = 'n'
        AND ((d.refclassid = %2$s AND d.refobjid = (SELECT oid FROM v))
          OR (d.refclassid = %3$s AND d.refobjid = (SELECT reltype FROM v)))""".formatted(PROC, CLASS, TYPE);
    var uses = new ArrayList<Reference>();
    uses.addAll(referred(sql, view, VIEW + RELATIONS.formatted(rules.formatted(CLASS))));
    uses.addAll(referred(sql, view, VIEW + ROUTINES.formatted(rules.formatted(PROC))));
    var usedBy = new ArrayList<Reference>();
    usedBy.addAll(referred(sql, view, VIEW + RELATIONS.formatted(readers)));
    usedBy.addAll(referred(sql, view, VIEW + ROUTINES.formatted(routines)));
    return new References(sorted(uses), sorted(usedBy));
  }

  private static List<Reference> referred(Sql sql, Table view, String query) throws QueryException {
    var database = view.container().database();
    return sql.some(
        query,
        rows -> {
          var schema = new Schema(database, rows.getString(1), false);
          var name = rows.getString(2);
          var arguments = rows.getString(4);
          return kind(rows.getString(3)).map(kind -> new Reference(schema, kind, name, arguments));
        },
        view.container().name(),
        view.name());
  }

  // A relation's kind, or a routine's as ROUTINES names it; nothing of a sequence or a type.
  private static Optional<Reference.Kind> kind(String kind) {
    return switch (kind) {
      case "r", "p" -> Optional.of(Reference.Kind.TABLE);
      case "f" -> Optional.of(Reference.Kind.FOREIGN_TABLE);
      case "v" -> Optional.of(Reference.Kind.VIEW);
      case "m" -> Optional.of(Reference.Kind.MATERIALIZED_VIEW);
      case "function" -> Optional.of(Reference.Kind.FUNCTION);
      case "procedure" -> Optional.of(Reference.Kind.PROCEDURE);
      default -> Optional.empty();
    };
  }

  private static List<Reference> sorted(List<Reference> references) {
    return references.stream()
        .sorted(Comparator.comparing((Reference r) -> r.container().name())
            .thenComparing(Reference::name)
            .thenComparing(Reference::arguments))
        .toList();
  }

  // A unique index that CONCURRENTLY takes: of columns alone, over all rows, and valid.
  @Override
  public boolean refreshesConcurrently(Sql sql, Table view) throws QueryException {
    if (view.kind() != Table.Kind.MATERIALIZED) {
      return false;
    }
    return sql.one(
        view.name(), """
        SELECT c.relispopulated AND EXISTS (SELECT FROM pg_catalog.pg_index i
          WHERE i.indrelid = c.oid AND i.indisunique AND i.indisvalid
          AND i.indpred IS NULL AND i.indexprs IS NULL)
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'm'""", rows -> rows.getBoolean(1), view.container().name(), view.name());
  }

  // Its owner, then its triggers and the rules other than the one that makes it.
  @Override
  public List<String> restore(Sql sql, Table view) throws QueryException {
    var name = Rows.qualified(sql.provider(), view);
    var kind = view.kind() == Table.Kind.MATERIALIZED ? "MATERIALIZED VIEW " : "VIEW ";
    var restore = new ArrayList<String>();
    restore.addAll(sql.rows(
        VIEW + """
        SELECT pg_catalog.pg_get_userbyid(c.relowner) FROM pg_catalog.pg_class c
        WHERE c.oid = (SELECT oid FROM v)""",
        rows -> "ALTER " + kind + name + " OWNER TO " + sql.quote(rows.getString(1)),
        view.container().name(),
        view.name()));
    restore.addAll(
        sql.rows(VIEW + """
        SELECT pg_catalog.pg_get_triggerdef(t.oid) FROM pg_catalog.pg_trigger t
        WHERE t.tgrelid = (SELECT oid FROM v) AND NOT t.tgisinternal ORDER BY t.tgname""", rows -> rows.getString(1), view.container().name(), view.name()));
    restore.addAll(sql.rows(
        VIEW + """
        SELECT pg_catalog.pg_get_ruledef(r.oid) FROM pg_catalog.pg_rewrite r
        WHERE r.ev_class = (SELECT oid FROM v) AND r.rulename <> '_RETURN' ORDER BY r.rulename""",
        rows -> withoutTerminator(rows.getString(1)),
        view.container().name(),
        view.name()));
    return List.copyOf(restore);
  }

  // Its comment and its columns', and a view's columns' defaults, where it has them.
  @Override
  public List<String> unset(Sql sql, Table view) throws QueryException {
    var name = Rows.qualified(sql.provider(), view);
    var kind = view.kind() == Table.Kind.MATERIALIZED ? "MATERIALIZED VIEW " : "VIEW ";
    var unset = new ArrayList<String>();
    unset.addAll(sql.rows(
        VIEW + """
        SELECT 1 WHERE pg_catalog.obj_description((SELECT oid FROM v), 'pg_class') IS NOT NULL""",
        rows -> "COMMENT ON " + kind + name + " IS NULL",
        view.container().name(),
        view.name()));
    for (var column : sql.rows(
        VIEW + """
        SELECT a.attname, pg_catalog.col_description(a.attrelid, a.attnum) IS NOT NULL,
          EXISTS (SELECT FROM pg_catalog.pg_attrdef d
            WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum)
        FROM pg_catalog.pg_attribute a
        WHERE a.attrelid = (SELECT oid FROM v) AND a.attnum > 0 AND NOT a.attisdropped
        ORDER BY a.attnum""",
        rows -> new Unset(rows.getString(1), rows.getBoolean(2), rows.getBoolean(3)),
        view.container().name(),
        view.name())) {
      var named = sql.quote(column.name());
      if (column.comment()) {
        unset.add("COMMENT ON COLUMN " + name + "." + named + " IS NULL");
      }
      if (column.fallback()) {
        unset.add("ALTER VIEW " + name + " ALTER COLUMN " + named + " DROP DEFAULT");
      }
    }
    return List.copyOf(unset);
  }

  private record Unset(String name, boolean comment, boolean fallback) {}

  @Override
  public boolean changesInTransaction() {
    return true;
  }

  @Override
  public boolean dropsDependents() {
    return true;
  }

  // invalid_table_definition, which CREATE OR REPLACE VIEW fails with where a column would be
  // dropped, renamed or moved, or change its type.
  @Override
  public boolean keepsColumns(QueryException refusal) {
    return state(refusal, "42P16");
  }

  // dependent_objects_still_exist, whose detail lists what a CASCADE would drop.
  @Override
  public boolean dependedOn(QueryException refusal) {
    return state(refusal, "2BP01");
  }

  private static boolean state(QueryException refusal, String state) {
    return refusal.getCause() instanceof SQLException e && state.equals(e.getSQLState());
  }

  @Override
  Optional<Head> head(String statement) {
    var create = CREATE.matcher(statement);
    if (!create.lookingAt()) {
      return Optional.empty();
    }
    var view = VIEW_WORD.matcher(statement).region(create.end(), statement.length());
    if (!view.lookingAt()) {
      return Optional.empty();
    }
    return Optional.of(new Head(view.end(), create.group(1) != null, view.group(1) != null));
  }

  @Override
  String unquoted(String part) {
    return part.substring(1, part.length() - 1).replace("\"\"", "\"");
  }

  // A name without quotes is folded to lower case.
  @Override
  String bare(String word) {
    return word.toLowerCase(Locale.ROOT);
  }

  @Override
  public String drop(Provider provider, Table view, boolean dependents) {
    var what = view.kind() == Table.Kind.MATERIALIZED ? "DROP MATERIALIZED VIEW " : "DROP VIEW ";
    return what + Rows.qualified(provider, view) + (dependents ? " CASCADE" : "");
  }

  @Override
  public String refresh(Provider provider, Table view, boolean concurrently) {
    if (view.kind() != Table.Kind.MATERIALIZED) {
      throw new IllegalArgumentException(view.name() + " keeps no rows to refresh");
    }
    return "REFRESH MATERIALIZED VIEW " + (concurrently ? "CONCURRENTLY " : "")
        + Rows.qualified(provider, view);
  }

  private static String withoutTerminator(String sql) {
    var trimmed = sql.strip();
    return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
