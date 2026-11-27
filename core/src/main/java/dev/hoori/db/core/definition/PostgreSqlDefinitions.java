package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How PostgreSQL's objects are defined: their definitions built from its catalog, by its own
 * functions where it has them ({@code pg_get_viewdef}, {@code pg_get_functiondef}, {@code
 * pg_get_triggerdef}, {@code pg_get_constraintdef}, {@code pg_get_indexdef}), and what each needs
 * from {@code pg_depend}. Objects of extensions are left to their extension, an identity column's
 * sequence to its column, a partition's inherited constraints and triggers to its table, whose
 * indexes its own are attached to. A child's columns that it inherits keep the defaults and NOT
 * NULL of their own. A materialized view is created without its rows and refreshed after, as
 * {@code pg_dump} does.
 *
 * <p>The snapshot's search path is empty, so that the server names every object with its schema,
 * and nothing a user created under the name of a function or operator of {@code pg_catalog} runs.
 * It reads without row security, so that a table whose policies would hide rows from the user
 * fails an export rather than leaving them out. The functions that write definitions read the
 * catalog as it is then, not as of the snapshot: an export locks its tables first ({@link
 * dev.hoori.db.core.dump.Dump}).
 */
public final class PostgreSqlDefinitions extends DefinitionDialect {

  private static final String CLASS = "'pg_catalog.pg_class'::pg_catalog.regclass";
  private static final String PROC = "'pg_catalog.pg_proc'::pg_catalog.regclass";
  private static final String TYPE = "'pg_catalog.pg_type'::pg_catalog.regclass";

  public PostgreSqlDefinitions() {
    // Nothing to set up.
  }

  @Override
  public void begin(Connection connection, Provider provider) throws QueryException {
    try {
      autoCommit(connection, provider, false);
      Sql.execute(
          connection, provider, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
      // No SELECT, as the first fixes the snapshot, which an export's lock goes before.
      Sql.execute(connection, provider, "SET LOCAL search_path = ''");
      Sql.execute(connection, provider, "SET LOCAL row_security = off");
      Sql.execute(connection, provider, "SET LOCAL IntervalStyle = 'postgres'");
      Sql.execute(connection, provider, "SET LOCAL DateStyle = 'ISO, YMD'");
      Sql.execute(connection, provider, "SET LOCAL extra_float_digits = 3");
    } catch (QueryException e) {
      // Where auto-commit went off, also as an interrupt cut its call short after it did.
      throw Sql.undone(e, () -> {
        if (!autoCommits(connection)) {
          end(connection, provider);
        }
      });
    }
  }

  private static boolean autoCommits(Connection connection) {
    try {
      return connection.getAutoCommit();
    } catch (SQLException _) {
      return false; // a connection that does not tell is ended all the same
    }
  }

  // Rolled back, the transaction takes its settings with it.
  @Override
  public void end(Connection connection, Provider provider) throws QueryException {
    try {
      Sql.ending(connection, provider, Journal.ROLLBACK, () -> {
        connection.rollback();
        return null;
      });
    } finally {
      Sql.ending(connection, provider, Journal.autoCommit(true), () -> {
        connection.setAutoCommit(true);
        return null;
      });
    }
  }

  private static void autoCommit(Connection connection, Provider provider, boolean on)
      throws QueryException {
    Journal.call(
        Sql.purpose(),
        Journal.autoCommit(on),
        () -> Query.run(connection, provider, () -> {
          connection.setAutoCommit(on);
          return null;
        }));
  }

  // An object as found in the catalog, with its oid.
  private record Found(Listed listed, long oid) {}

  @Override
  public List<Listed> list(Connection connection, Provider provider, Container container)
      throws QueryException {
    return found(connection, provider, container).stream().map(Found::listed).toList();
  }

  private static List<Found> found(Connection connection, Provider provider, Container container)
      throws QueryException {
    var schemas = new ArrayList<Schema>();
    switch (container) {
      case Schema schema -> schemas.add(schema);
      case Database database ->
        schemas.addAll(Sql.rows(
            connection,
            provider,
            """
            SELECT n.nspname FROM pg_catalog.pg_namespace n
            WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
            %s
            ORDER BY n.nspname""".formatted(notOfExtension("pg_catalog.pg_namespace", "n.oid")),
            rows -> new Schema(database, rows.getString(1), false)));
    }
    var found = new ArrayList<Found>();
    for (var schema : schemas) {
      found.addAll(found(connection, provider, schema));
    }
    return found;
  }

  private static List<Found> found(Connection connection, Provider provider, Schema schema)
      throws QueryException {
    var relations = Sql.rows(
        connection, provider, """
        SELECT c.oid, c.relname, c.relkind, c.reltuples, pg_catalog.pg_table_size(c.oid)
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
        AND NOT EXISTS (SELECT FROM pg_catalog.pg_depend d WHERE d.classid = %s
          AND d.objid = c.oid AND d.objsubid = 0 AND d.deptype IN ('e', 'i'))
        ORDER BY c.relname""".formatted(CLASS), rows -> relation(schema, rows), schema.name());
    var routines = Sql.rows(
        connection,
        provider,
        """
        SELECT p.oid, p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid), p.prokind
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = ? AND p.prokind IN ('f', 'p')
        %s
        ORDER BY p.proname, 3""".formatted(notOfExtension("pg_catalog.pg_proc", "p.oid")),
        rows -> new Found(
            Listed.of(new Item(
                schema,
                rows.getString(4).equals("p") ? Item.Kind.PROCEDURE : Item.Kind.FUNCTION,
                rows.getString(2),
                rows.getString(3),
                "")),
            rows.getLong(1)),
        schema.name());
    // A partition's triggers come from its table's, which make them.
    var triggers = Sql.rows(
        connection,
        provider,
        """
        SELECT t.oid, t.tgname, c.relname FROM pg_catalog.pg_trigger t
        JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND NOT t.tgisinternal AND t.tgparentid = 0
        ORDER BY c.relname, t.tgname""",
        rows -> new Found(
            Listed.of(
                new Item(schema, Item.Kind.TRIGGER, rows.getString(2), "", rows.getString(3))),
            rows.getLong(1)),
        schema.name());
    var all = new ArrayList<Found>(relations);
    all.addAll(routines);
    all.addAll(triggers);
    var found = new ArrayList<Found>();
    for (var kind : Item.Kind.values()) {
      all.stream().filter(f -> f.listed().item().kind() == kind).forEach(found::add);
    }
    return found;
  }

  // A relation as listed: a table with the server's estimates, which a table never analyzed
  // has as -1 rows.
  private static Found relation(Schema schema, ResultSet rows) throws SQLException {
    var relkind = rows.getString(3);
    var kind = switch (relkind) {
      case "v", "m" -> Item.Kind.VIEW;
      case "S" -> Item.Kind.SEQUENCE;
      default -> Item.Kind.TABLE;
    };
    var item = Item.of(schema, kind, rows.getString(2));
    if (!relkind.equals("r")) {
      return new Found(Listed.of(item), rows.getLong(1));
    }
    var estimate = rows.getDouble(4);
    return new Found(
        new Listed(
            item,
            estimate < 0 ? OptionalLong.empty() : OptionalLong.of((long) estimate),
            OptionalLong.of(rows.getLong(5)),
            true),
        rows.getLong(1));
  }

  private static String notOfExtension(String catalog, String oid) {
    return """
        AND NOT EXISTS (SELECT FROM pg_catalog.pg_depend d
          WHERE d.classid = '%s'::pg_catalog.regclass AND d.objid = %s AND d.deptype = 'e')""".formatted(catalog, oid);
  }

  // What the definitions are read from: the objects of the schemas asked about, by item, and the
  // relations and routines by oid, for what they need.
  private static final class Known {
    final Map<Item, Found> items = new LinkedHashMap<>();
    final Map<Long, Item> relations = new HashMap<>();
    final Map<Long, Item> routines = new HashMap<>();
  }

  @Override
  public List<Definition> define(Connection connection, Provider provider, List<Item> items)
      throws QueryException {
    var known = new Known();
    for (var schema : items.stream().map(Item::container).distinct().toList()) {
      for (var found : found(connection, provider, schema)) {
        var item = found.listed().item();
        known.items.put(item, found);
        switch (item.kind()) {
          case TABLE, VIEW, SEQUENCE -> known.relations.put(found.oid(), item);
          case FUNCTION, PROCEDURE -> known.routines.put(found.oid(), item);
          case TRIGGER -> {
            // Nothing needs a trigger.
          }
        }
      }
    }
    var chosen = Set.copyOf(items);
    var definitions = new ArrayList<Definition>();
    for (var item : items) {
      var found = known.items.get(item);
      if (found == null) {
        throw QueryException.of(
            QueryException.Failure.NOT_FOUND, item.shown() + " no longer exists");
      }
      var object = new Defining(connection, provider, known, chosen, found);
      definitions.add(
          switch (item.kind()) {
            case TABLE -> object.table();
            case VIEW -> object.view();
            case SEQUENCE -> object.sequence();
            case FUNCTION, PROCEDURE -> object.routine();
            case TRIGGER -> object.trigger();
          });
    }
    return definitions;
  }

  // The definition of one object.
  private static final class Defining {

    private static final String SEQUENCE_OPTIONS = """
        pg_catalog.format_type(s.seqtypid, NULL), s.seqstart, s.seqincrement, s.seqmin, s.seqmax,
        s.seqcache, s.seqcycle""";

    private final Connection connection;
    private final Provider provider;
    private final Known known;
    private final Set<Item> chosen;
    private final Item item;
    private final String oid;
    private final String name;

    Defining(Connection connection, Provider provider, Known known, Set<Item> chosen, Found found) {
      this.connection = connection;
      this.provider = provider;
      this.known = known;
      this.chosen = chosen;
      this.item = found.listed().item();
      this.oid = "'" + found.oid() + "'::pg_catalog.oid";
      this.name = Rows.qualified(provider, item.container(), item.name());
    }

    private String literal(String text) {
      return provider.dialect().rows().literal(text);
    }

    private String quote(String name) {
      return provider.quote(name);
    }

    private <T> T one(String sql, Sql.Row<T> row) throws QueryException {
      return Sql.one(connection, provider, item.shown(), sql, row);
    }

    private <T> List<T> rows(String sql, Sql.Row<T> row) throws QueryException {
      return Sql.rows(connection, provider, sql, row);
    }

    private record Relation(
        String persistence,
        String kind,
        String options,
        String partitionKey,
        String bound,
        String comment,
        boolean rowSecurity,
        boolean forceRowSecurity) {}

    private record Parent(long oid, String name) {}

    private record Constraint(
        String name, String type, String definition, long refers, boolean valid) {}

    // A table's constraints: those that go into its CREATE TABLE, its foreign keys apart, and
    // those that are not valid, which follow its rows.
    private record Constraints(
        List<String> inline,
        List<String> keys,
        List<String> dropKeys,
        List<String> notValid,
        Set<Item> refers) {}

    // A table as CREATE TABLE writes it: its own columns and its constraints but foreign keys; a
    // partition as one of its table, whose columns it has; a child with the tables it inherits.
    Definition table() throws QueryException {
      var relation = one(
          """
          SELECT c.relpersistence, c.relkind, pg_catalog.array_to_string(c.reloptions, ', '),
            CASE WHEN c.relkind = 'p' THEN pg_catalog.pg_get_partkeydef(c.oid) END,
            CASE WHEN c.relispartition THEN pg_catalog.pg_get_expr(c.relpartbound, c.oid) END,
            pg_catalog.obj_description(c.oid, 'pg_class'), c.relrowsecurity, c.relforcerowsecurity
          FROM pg_catalog.pg_class c WHERE c.oid = %s""".formatted(oid),
          rows -> new Relation(
              rows.getString(1),
              rows.getString(2),
              rows.getString(3),
              rows.getString(4),
              rows.getString(5),
              rows.getString(6),
              rows.getBoolean(7),
              rows.getBoolean(8)));
      var parents =
          rows("""
          SELECT i.inhparent, i.inhparent::pg_catalog.regclass::pg_catalog.text
          FROM pg_catalog.pg_inherits i WHERE i.inhrelid = %s ORDER BY i.inhseqno""".formatted(oid), rows -> new Parent(rows.getLong(1), rows.getString(2)));
      var needs = new HashSet<>(needs(CLASS));
      for (var parent : parents) {
        var of = known.relations.get(parent.oid());
        if (of != null) {
          needs.add(of);
        }
      }
      var lines = new ArrayList<String>();
      var overrides = new ArrayList<String>();
      var comments = new ArrayList<String>();
      var restore = new ArrayList<String>();
      for (var column : columns()) {
        if (column.comment() != null) {
          comments.add("COMMENT ON COLUMN " + name + "." + quote(column.name()) + " IS "
              + literal(column.comment()));
        }
        if (column.local()) {
          lines.add(column(column, restore));
        } else {
          overrides.addAll(overridden(column));
        }
      }
      var constraints = constraints(relation.kind().equals("p") ? "" : "ONLY ");
      var statements = new ArrayList<String>();
      statements.add(created(relation, parents, lines, constraints.inline()));
      statements.addAll(overrides);
      if (relation.comment() != null) {
        statements.add("COMMENT ON TABLE " + name + " IS " + literal(relation.comment()));
      }
      statements.addAll(comments);
      statements.addAll(owned());
      var afterRows = new ArrayList<>(constraints.notValid());
      afterRows.addAll(security(relation));
      return new Definition(
          item,
          statements,
          false,
          indexes(),
          constraints.keys(),
          constraints.dropKeys(),
          "DROP TABLE IF EXISTS " + name,
          restore,
          afterRows,
          needs,
          constraints.refers());
    }

    private record Policy(
        String name,
        boolean permissive,
        String command,
        String roles,
        String using,
        String check) {}

    // Row security as the table has it, and its policies.
    private List<String> security(Relation relation) throws QueryException {
      var statements = new ArrayList<String>();
      if (relation.rowSecurity()) {
        statements.add("ALTER TABLE ONLY " + name + " ENABLE ROW LEVEL SECURITY");
      }
      if (relation.forceRowSecurity()) {
        statements.add("ALTER TABLE ONLY " + name + " FORCE ROW LEVEL SECURITY");
      }
      var policies = rows(
          """
          SELECT p.polname, p.polpermissive, p.polcmd,
            (SELECT pg_catalog.string_agg(CASE WHEN u.role = 0 THEN 'PUBLIC'
                ELSE pg_catalog.quote_ident(pg_catalog.pg_get_userbyid(u.role)) END, ', '
                ORDER BY u.n)
              FROM pg_catalog.unnest(p.polroles) WITH ORDINALITY u(role, n)),
            pg_catalog.pg_get_expr(p.polqual, p.polrelid),
            pg_catalog.pg_get_expr(p.polwithcheck, p.polrelid)
          FROM pg_catalog.pg_policy p WHERE p.polrelid = %s ORDER BY p.polname""".formatted(oid),
          rows -> new Policy(
              rows.getString(1),
              rows.getBoolean(2),
              rows.getString(3),
              rows.getString(4),
              rows.getString(5),
              rows.getString(6)));
      for (var policy : policies) {
        var create = new StringBuilder("CREATE POLICY ")
            .append(quote(policy.name()))
            .append(" ON ")
            .append(name)
            .append(policy.permissive() ? " AS PERMISSIVE" : " AS RESTRICTIVE")
            .append(" FOR ")
            .append(
                switch (policy.command()) {
                  case "r" -> "SELECT";
                  case "a" -> "INSERT";
                  case "w" -> "UPDATE";
                  case "d" -> "DELETE";
                  default -> "ALL";
                })
            .append(" TO ")
            .append(policy.roles());
        if (policy.using() != null) {
          create.append(" USING (").append(policy.using()).append(')');
        }
        if (policy.check() != null) {
          create.append(" WITH CHECK (").append(policy.check()).append(')');
        }
        statements.add(create.toString());
      }
      return statements;
    }

    // Those that a partition or a child has from its table come with it.
    private Constraints constraints(String only) throws QueryException {
      var constraints = rows(
          """
          SELECT co.conname, co.contype, pg_catalog.pg_get_constraintdef(co.oid), co.confrelid,
            co.convalidated
          FROM pg_catalog.pg_constraint co WHERE co.conrelid = %s
          AND co.contype IN ('p', 'u', 'c', 'x', 'f') AND co.conislocal
          ORDER BY co.contype = 'f', co.contype = 'c', co.conname""".formatted(oid),
          rows -> new Constraint(
              rows.getString(1),
              rows.getString(2),
              rows.getString(3),
              rows.getLong(4),
              rows.getBoolean(5)));
      var inline = new ArrayList<String>();
      var keys = new ArrayList<String>();
      var dropKeys = new ArrayList<String>();
      var notValid = new ArrayList<String>();
      var refers = new HashSet<Item>();
      for (var constraint : constraints) {
        var named = "CONSTRAINT " + quote(constraint.name()) + " " + constraint.definition();
        // Added NOT VALID, as its definition says, to the table and what inherits it.
        if (!constraint.valid()) {
          notValid.add("ALTER TABLE " + name + " ADD " + named);
        } else if (!constraint.type().equals("f")) {
          inline.add(named);
        } else {
          keys.add("ALTER TABLE " + only + name + " ADD " + named);
        }
        if (!constraint.type().equals("f")) {
          continue;
        }
        dropKeys.add("ALTER TABLE IF EXISTS " + only + name + " DROP CONSTRAINT IF EXISTS "
            + quote(constraint.name()));
        var to = known.relations.get(constraint.refers());
        if (to != null) {
          refers.add(to);
        }
      }
      return new Constraints(inline, keys, dropKeys, notValid, refers);
    }

    // The CREATE TABLE itself.
    private String created(
        Relation relation, List<Parent> parents, List<String> lines, List<String> constraints) {
      var create = new StringBuilder("CREATE ");
      if (relation.persistence().equals("u")) {
        create.append("UNLOGGED ");
      }
      create.append("TABLE ").append(name);
      if (relation.bound() != null && !parents.isEmpty()) {
        // A partition's columns are its table's.
        create.append(" PARTITION OF ").append(parents.getFirst().name());
        if (!constraints.isEmpty()) {
          create.append(" (\n    ").append(String.join(",\n    ", constraints)).append("\n)");
        }
        create.append(' ').append(relation.bound());
      } else {
        var all = new ArrayList<>(lines);
        all.addAll(constraints);
        create.append(" (");
        if (!all.isEmpty()) {
          create.append("\n    ").append(String.join(",\n    ", all)).append('\n');
        }
        create.append(')');
        if (!parents.isEmpty()) {
          create
              .append(" INHERITS (")
              .append(parents.stream().map(Parent::name).collect(Collectors.joining(", ")))
              .append(')');
        }
      }
      if (relation.partitionKey() != null) {
        create.append(" PARTITION BY ").append(relation.partitionKey());
      }
      if (relation.options() != null && !relation.options().isEmpty()) {
        create.append(" WITH (").append(relation.options()).append(')');
      }
      return create.toString();
    }

    // A column as CREATE TABLE writes it; an identity column's value goes into what restores.
    private String column(Column column, List<String> restore) throws QueryException {
      var line = new StringBuilder(quote(column.name())).append(' ').append(column.type());
      if (column.collation() != null) {
        line.append(" COLLATE ").append(column.collation());
      }
      if (!column.generated().isEmpty()) {
        line.append(" GENERATED ALWAYS AS (")
            .append(column.expression())
            .append(column.generated().equals("v") ? ") VIRTUAL" : ") STORED");
      } else if (!column.identity().isEmpty()) {
        line.append(column.identity().equals("a") ? " GENERATED ALWAYS" : " GENERATED BY DEFAULT")
            .append(" AS IDENTITY");
        if (column.sequence() != null) {
          line.append(" (SEQUENCE NAME ")
              .append(column.sequence())
              .append(identityOptions(column.sequence()))
              .append(')');
          restore.add(value(column.sequence()));
        }
      } else if (column.expression() != null) {
        line.append(" DEFAULT ").append(column.expression());
      }
      if (column.notNull()) {
        line.append(" NOT NULL");
      }
      return line.toString();
    }

    // What a column that the table inherits has of its own, which its table's gives it otherwise:
    // another default, or none, and NOT NULL. Identity and generation come with the column.
    private List<String> overridden(Column column) {
      if (!column.identity().isEmpty() || !column.generated().isEmpty()) {
        return List.of();
      }
      var alter = "ALTER TABLE ONLY " + name + " ALTER COLUMN " + quote(column.name());
      var statements = new ArrayList<String>();
      if (!Objects.equals(column.expression(), column.inheritedDefault())) {
        statements.add(
            column.expression() == null
                ? alter + " DROP DEFAULT"
                : alter + " SET DEFAULT " + column.expression());
      }
      if (column.notNull() && !column.inheritedNotNull()) {
        statements.add(alter + " SET NOT NULL");
      }
      return statements;
    }

    private String identityOptions(String sequence) throws QueryException {
      return one("""
          SELECT %s FROM pg_catalog.pg_sequence s
          WHERE s.seqrelid = pg_catalog.to_regclass(%s)""".formatted(SEQUENCE_OPTIONS, literal(sequence)), rows -> options(rows, false));
    }

    // The sequences that its columns own, where they go too.
    private List<String> owned() throws QueryException {
      return Sql.some(connection, provider, """
          SELECT d.objid, a.attname FROM pg_catalog.pg_depend d
          JOIN pg_catalog.pg_class s ON s.oid = d.objid AND s.relkind = 'S'
          JOIN pg_catalog.pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
          WHERE d.classid = %1$s AND d.refclassid = %1$s AND d.refobjid = %2$s
          AND d.deptype = 'a'
          ORDER BY a.attnum""".formatted(CLASS, oid), rows -> {
        var column = quote(rows.getString(2));
        return Optional.ofNullable(known.relations.get(rows.getLong(1)))
            .filter(chosen::contains)
            .map(sequence -> "ALTER SEQUENCE "
                + Rows.qualified(provider, sequence.container(), sequence.name())
                + " OWNED BY " + name + "." + column);
      });
    }

    private record Column(
        String name,
        String type,
        boolean notNull,
        String expression,
        String identity,
        String generated,
        String collation,
        String comment,
        boolean local,
        String sequence,
        boolean inheritedNotNull,
        String inheritedDefault) {}

    // Of a column the table inherits, also whether a table it inherits it from has it NOT NULL,
    // and the default it has there, one that has one first.
    private List<Column> columns() throws QueryException {
      return rows(
          """
          SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull,
            pg_catalog.pg_get_expr(d.adbin, d.adrelid), a.attidentity, a.attgenerated,
            CASE WHEN a.attcollation <> t.typcollation AND a.attcollation <> 0 THEN
              (SELECT pg_catalog.quote_ident(cn.nspname) || '.'
                || pg_catalog.quote_ident(co.collname)
              FROM pg_catalog.pg_collation co
              JOIN pg_catalog.pg_namespace cn ON cn.oid = co.collnamespace
              WHERE co.oid = a.attcollation) END,
            pg_catalog.col_description(a.attrelid, a.attnum), a.attislocal,
            CASE WHEN a.attidentity <> '' THEN pg_catalog.pg_get_serial_sequence(
              a.attrelid::pg_catalog.regclass::pg_catalog.text, a.attname) END,
            CASE WHEN NOT a.attislocal THEN EXISTS (SELECT FROM pg_catalog.pg_inherits h
              JOIN pg_catalog.pg_attribute pa
                ON pa.attrelid = h.inhparent AND pa.attname = a.attname
              WHERE h.inhrelid = a.attrelid AND pa.attnotnull) END,
            CASE WHEN NOT a.attislocal THEN (
              SELECT pg_catalog.pg_get_expr(pd.adbin, pd.adrelid) FROM pg_catalog.pg_inherits h
              JOIN pg_catalog.pg_attribute pa
                ON pa.attrelid = h.inhparent AND pa.attname = a.attname
              LEFT JOIN pg_catalog.pg_attrdef pd
                ON pd.adrelid = pa.attrelid AND pd.adnum = pa.attnum
              WHERE h.inhrelid = a.attrelid
              ORDER BY pd.adbin IS NULL, h.inhseqno LIMIT 1) END
          FROM pg_catalog.pg_attribute a
          JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
          LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
          WHERE a.attrelid = %s AND a.attnum > 0 AND NOT a.attisdropped
          ORDER BY a.attnum""".formatted(oid),
          rows -> new Column(
              rows.getString(1),
              rows.getString(2),
              rows.getBoolean(3),
              rows.getString(4),
              rows.getString(5),
              rows.getString(6),
              rows.getString(7),
              rows.getString(8),
              rows.getBoolean(9),
              rows.getString(10),
              rows.getBoolean(11),
              rows.getString(12)));
    }

    // An index as its table has it; of a partition, the index of its table's it is a partition
    // of, and that table, or null and 0.
    private record Index(String definition, String name, String parent, long parentTable) {}

    // The indexes that no constraint makes. A partitioned table's is created on it alone, and is
    // valid once each partition's is attached to it, as pg_dump does: a partition's that is one of
    // its table's is attached where that table goes too, and where it does not, the partition gets
    // it from the table it is created on.
    private List<String> indexes() throws QueryException {
      var indexes = rows(
          """
          SELECT pg_catalog.pg_get_indexdef(i.indexrelid),
            pg_catalog.quote_ident(n.nspname) || '.' || pg_catalog.quote_ident(ic.relname),
            (SELECT pg_catalog.quote_ident(pn.nspname) || '.' || pg_catalog.quote_ident(pc.relname)
              FROM pg_catalog.pg_inherits h
              JOIN pg_catalog.pg_class pc ON pc.oid = h.inhparent
              JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
              WHERE h.inhrelid = i.indexrelid),
            (SELECT pi.indrelid FROM pg_catalog.pg_inherits h
              JOIN pg_catalog.pg_index pi ON pi.indexrelid = h.inhparent
              WHERE h.inhrelid = i.indexrelid)
          FROM pg_catalog.pg_index i
          JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
          JOIN pg_catalog.pg_namespace n ON n.oid = ic.relnamespace
          WHERE i.indrelid = %s
          AND NOT EXISTS (SELECT FROM pg_catalog.pg_constraint co
            WHERE co.conindid = i.indexrelid AND co.conrelid = i.indrelid
            AND co.contype IN ('p', 'u', 'x'))
          ORDER BY ic.relname""".formatted(oid),
          rows ->
              new Index(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4)));
      var statements = new ArrayList<String>();
      for (var index : indexes) {
        if (index.parent() == null) {
          statements.add(index.definition());
        } else if (known.relations.containsKey(index.parentTable())
            && chosen.contains(known.relations.get(index.parentTable()))) {
          statements.add(index.definition());
          statements.add("ALTER INDEX " + index.parent() + " ATTACH PARTITION " + index.name());
        }
      }
      return statements;
    }

    private record View(
        String kind, String options, String definition, String comment, boolean populated) {}

    // A materialized view without its rows, which its refresh gives after the tables' rows where
    // it has any; one never refreshed stays so.
    Definition view() throws QueryException {
      var view = one(
          """
          SELECT c.relkind, pg_catalog.array_to_string(c.reloptions, ', '),
            pg_catalog.pg_get_viewdef(c.oid), pg_catalog.obj_description(c.oid, 'pg_class'),
            c.relispopulated
          FROM pg_catalog.pg_class c WHERE c.oid = %s""".formatted(oid),
          rows -> new View(
              rows.getString(1),
              rows.getString(2),
              rows.getString(3),
              rows.getString(4),
              rows.getBoolean(5)));
      var materialized = view.kind().equals("m");
      var create = new StringBuilder(materialized ? "CREATE MATERIALIZED VIEW " : "CREATE VIEW ")
          .append(name);
      if (view.options() != null && !view.options().isEmpty()) {
        create.append(" WITH (").append(view.options()).append(')');
      }
      create.append(" AS\n").append(withoutTerminator(view.definition()));
      if (materialized) {
        create.append("\n  WITH NO DATA");
      }
      var statements = new ArrayList<String>();
      statements.add(create.toString());
      var kind = materialized ? "MATERIALIZED VIEW " : "VIEW ";
      if (view.comment() != null) {
        statements.add("COMMENT ON " + kind + name + " IS " + literal(view.comment()));
      }
      return new Definition(
          item,
          statements,
          false,
          materialized ? indexes() : List.of(),
          List.of(),
          List.of(),
          "DROP " + kind + "IF EXISTS " + name,
          materialized && view.populated()
              ? List.of("REFRESH MATERIALIZED VIEW " + name)
              : List.of(),
          List.of(),
          needs(CLASS),
          Set.of());
    }

    private record Sequence(String options, String comment) {}

    Definition sequence() throws QueryException {
      var sequence = one(
          """
          SELECT %s, pg_catalog.obj_description(s.seqrelid, 'pg_class')
          FROM pg_catalog.pg_sequence s WHERE s.seqrelid = %s""".formatted(SEQUENCE_OPTIONS, oid),
          rows -> new Sequence(options(rows, true), rows.getString(8)));
      var statements = new ArrayList<String>();
      statements.add("CREATE SEQUENCE " + name + sequence.options());
      if (sequence.comment() != null) {
        statements.add("COMMENT ON SEQUENCE " + name + " IS " + literal(sequence.comment()));
      }
      return new Definition(
          item,
          statements,
          false,
          List.of(),
          List.of(),
          List.of(),
          "DROP SEQUENCE IF EXISTS " + name,
          List.of(value(name)),
          List.of(),
          Set.of(),
          Set.of());
    }

    // The statement that gives the sequence the value it has now.
    private String value(String sequence) throws QueryException {
      return one(
          "SELECT last_value, is_called FROM " + sequence,
          rows -> "SELECT pg_catalog.setval(" + literal(sequence) + ", " + rows.getString(1) + ", "
              + rows.getBoolean(2) + ")");
    }

    private record Routine(String definition, String comment) {}

    Definition routine() throws QueryException {
      var routine =
          one("""
          SELECT pg_catalog.pg_get_functiondef(p.oid), pg_catalog.obj_description(p.oid, 'pg_proc')
          FROM pg_catalog.pg_proc p WHERE p.oid = %s""".formatted(oid), rows -> new Routine(rows.getString(1), rows.getString(2)));
      var kind = item.kind() == Item.Kind.PROCEDURE ? "PROCEDURE " : "FUNCTION ";
      var signature = name + "(" + item.arguments() + ")";
      var statements = new ArrayList<String>();
      statements.add(routine.definition().strip());
      if (routine.comment() != null) {
        statements.add("COMMENT ON " + kind + signature + " IS " + literal(routine.comment()));
      }
      return new Definition(
          item,
          statements,
          false,
          List.of(),
          List.of(),
          List.of(),
          "DROP " + kind + "IF EXISTS " + signature,
          List.of(),
          List.of(),
          needs(PROC),
          Set.of());
    }

    private record Trigger(String definition, String enabled, long function, long table) {}

    Definition trigger() throws QueryException {
      var trigger = one(
          """
          SELECT pg_catalog.pg_get_triggerdef(t.oid), t.tgenabled, t.tgfoid, t.tgrelid
          FROM pg_catalog.pg_trigger t WHERE t.oid = %s""".formatted(oid),
          rows ->
              new Trigger(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4)));
      var table = Rows.qualified(provider, item.container(), item.table());
      var statements = new ArrayList<String>();
      statements.add(trigger.definition());
      // Created enabled in origin mode, as the catalog's O.
      var mode = switch (trigger.enabled()) {
        case "D" -> " DISABLE TRIGGER ";
        case "R" -> " ENABLE REPLICA TRIGGER ";
        case "A" -> " ENABLE ALWAYS TRIGGER ";
        default -> "";
      };
      if (!mode.isEmpty()) {
        statements.add("ALTER TABLE " + table + mode + quote(item.name()));
      }
      var needs = new HashSet<Item>();
      var function = known.routines.get(trigger.function());
      if (function != null) {
        needs.add(function);
      }
      var on = known.relations.get(trigger.table());
      if (on != null) {
        needs.add(on);
      }
      return new Definition(
          item,
          statements,
          false,
          List.of(),
          List.of(),
          List.of(),
          "DROP TRIGGER IF EXISTS " + quote(item.name()) + " ON " + table,
          List.of(),
          List.of(),
          needs,
          Set.of());
    }

    // What the object needs, as its own rows of pg_depend name it, and those of its view's rules,
    // its columns' defaults and its constraints but foreign keys: relations, routines, and a
    // relation by its row type.
    private Set<Item> needs(String own) throws QueryException {
      var refs = rows("""
          SELECT DISTINCT d.refclassid = %2$s,
            CASE WHEN d.refclassid = %3$s THEN t.typrelid ELSE d.refobjid END
          FROM pg_catalog.pg_depend d
          LEFT JOIN pg_catalog.pg_type t ON t.oid = d.refobjid AND d.refclassid = %3$s
          WHERE d.deptype = 'n' AND d.refclassid IN (%1$s, %2$s, %3$s)
          AND ((d.classid = %4$s AND d.objid = %5$s)
            OR (d.classid = 'pg_catalog.pg_rewrite'::pg_catalog.regclass AND d.objid IN
              (SELECT r.oid FROM pg_catalog.pg_rewrite r WHERE r.ev_class = %5$s))
            OR (d.classid = 'pg_catalog.pg_attrdef'::pg_catalog.regclass AND d.objid IN
              (SELECT a.oid FROM pg_catalog.pg_attrdef a WHERE a.adrelid = %5$s))
            OR (d.classid = 'pg_catalog.pg_constraint'::pg_catalog.regclass AND d.objid IN
              (SELECT c.oid FROM pg_catalog.pg_constraint c WHERE c.conrelid = %5$s
                AND c.contype <> 'f')))""".formatted(CLASS, PROC, TYPE, own, oid), rows -> {
        var by = rows.getBoolean(1) ? known.routines : known.relations;
        var ref = by.get(rows.getLong(2));
        return ref == null ? item : ref;
      });
      var needs = new HashSet<>(refs);
      needs.remove(item);
      return needs;
    }

    // A sequence's options as CREATE SEQUENCE writes them, its type only where asked: an identity
    // column's sequence has its column's.
    private static String options(ResultSet rows, boolean type) throws SQLException {
      return (type ? " AS " + rows.getString(1) : "")
          + " START WITH " + rows.getString(2)
          + " INCREMENT BY " + rows.getString(3)
          + " MINVALUE " + rows.getString(4)
          + " MAXVALUE " + rows.getString(5)
          + " CACHE " + rows.getString(6)
          + (rows.getBoolean(7) ? " CYCLE" : " NO CYCLE");
    }

    private static String withoutTerminator(String sql) {
      var trimmed = sql.strip();
      return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
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
  public References references(Connection connection, Provider provider, Table view)
      throws QueryException {
    var schema = view.container().name();
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
    uses.addAll(
        referred(connection, provider, view, VIEW + RELATIONS.formatted(rules.formatted(CLASS))));
    uses.addAll(
        referred(connection, provider, view, VIEW + ROUTINES.formatted(rules.formatted(PROC))));
    var usedBy = new ArrayList<Reference>();
    usedBy.addAll(referred(connection, provider, view, VIEW + RELATIONS.formatted(readers)));
    usedBy.addAll(referred(connection, provider, view, VIEW + ROUTINES.formatted(routines)));
    return new References(sorted(uses), sorted(usedBy));
  }

  private static List<Reference> referred(
      Connection connection, Provider provider, Table view, String sql) throws QueryException {
    var database = view.container().database();
    return Sql.some(
        connection,
        provider,
        sql,
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
  public boolean refreshesConcurrently(Connection connection, Provider provider, Table view)
      throws QueryException {
    if (view.kind() != Table.Kind.MATERIALIZED) {
      return false;
    }
    return Sql.one(
        connection,
        provider,
        view.name(),
        """
        SELECT c.relispopulated AND EXISTS (SELECT FROM pg_catalog.pg_index i
          WHERE i.indrelid = c.oid AND i.indisunique AND i.indisvalid
          AND i.indpred IS NULL AND i.indexprs IS NULL)
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'm'""",
        rows -> rows.getBoolean(1),
        view.container().name(),
        view.name());
  }

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
    return refusal.getCause() instanceof SQLException e && "42P16".equals(e.getSQLState());
  }
}
