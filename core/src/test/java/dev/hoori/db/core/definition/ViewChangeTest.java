package dev.hoori.db.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ViewChangeTest {

  private static final Database SHOP = new Database("shop", false);
  private static final Schema PUBLIC = new Schema(SHOP, "public", false);

  private static ViewDefinition read(Table view, String... restore) {
    var definition = new Definition(
        Item.of(view.container(), Item.Kind.VIEW, view.name()),
        List.of("CREATE VIEW x AS SELECT 1"),
        false,
        List.of(),
        List.of(),
        List.of(),
        "",
        List.of(restore),
        List.of(),
        Set.of(),
        Set.of());
    return new ViewDefinition(view, definition, References.NONE, false);
  }

  @Test
  void aReplaceTurnsEachStatementThatCreatesAViewIntoOrReplace() {
    var view = new Table(SHOP, "v", Table.Kind.VIEW);
    var change = ViewChange.replace(Provider.MYSQL, read(view), """
        -- as shown
        CREATE ALGORITHM=UNDEFINED DEFINER=`me`@`%` SQL SECURITY DEFINER VIEW `v` AS select 1;
        create or replace view shop.v as select 2;
        ALTER VIEW `v` AS select 3""");
    assertEquals(
        List.of(
            "CREATE OR REPLACE ALGORITHM=UNDEFINED DEFINER=`me`@`%` SQL SECURITY DEFINER VIEW `v` AS"
                + " select 1",
            "create or replace view shop.v as select 2", "ALTER VIEW `v` AS select 3"),
        change.statements());
    assertEquals(ViewChange.Kind.REPLACE, change.kind());
    assertFalse(change.transaction(), "MySQL commits each statement of DDL");
  }

  @Test
  void aDefinerOfAnyQuotedNameKeepsTheReplaceInPlace() {
    var view = new Table(SHOP, "v", Table.Kind.VIEW);
    for (var definer : List.of(
        "`my user`@`%`", "'my user'@'localhost'", "`a``b`@`%`", "CURRENT_USER", "CURRENT_USER()")) {
      var created = "CREATE ALGORITHM=MERGE DEFINER = " + definer
          + " SQL SECURITY DEFINER VIEW `v` AS select 1";
      assertEquals(
          List.of(created.replaceFirst("CREATE", "CREATE OR REPLACE")),
          ViewChange.replace(Provider.MARIADB, read(view), created + ";").statements(),
          definer);
    }
  }

  @Test
  void aDefinitionThatCreatesAViewOfAnotherNameIsRefused() {
    var mine = new Table(SHOP, "v", Table.Kind.VIEW);
    var other = assertThrows(
        ViewChange.OtherView.class,
        () -> ViewChange.replace(Provider.MYSQL, read(mine), "CREATE VIEW `w` AS select 1"));
    assertEquals("w", other.named());
    assertThrows(
        ViewChange.OtherView.class,
        () -> ViewChange.replace(Provider.MYSQL, read(mine), "CREATE VIEW other.v AS select 1"));
    var view = new Table(PUBLIC, "v", Table.Kind.VIEW);
    assertThrows(
        ViewChange.OtherView.class,
        () -> ViewChange.replace(
            Provider.POSTGRESQL, read(view), "CREATE VIEW public.\"V\" AS SELECT 1"));
    assertThrows(
        ViewChange.OtherView.class,
        () -> ViewChange.recreate(
            Provider.POSTGRESQL, read(view), "CREATE VIEW sales.v AS SELECT 1"));
    // Unquoted, PostgreSQL folds the name to lower case: the same view.
    assertEquals(
        List.of("CREATE OR REPLACE VIEW Public.V AS SELECT 1"),
        ViewChange.replace(Provider.POSTGRESQL, read(view), "CREATE VIEW Public.V AS SELECT 1")
            .statements());
  }

  @Test
  void aReplaceTakesFromTheViewWhatItsDefinitionNoLongerSets() {
    var view = new Table(PUBLIC, "v", Table.Kind.VIEW);
    var was = new ViewDefinition(
        view,
        read(view).definition(),
        References.NONE,
        false,
        List.of(),
        List.of("COMMENT ON VIEW \"public\".\"v\" IS NULL"));
    assertEquals(
        List.of(
            "CREATE OR REPLACE VIEW public.v AS SELECT 2",
            "COMMENT ON VIEW \"public\".\"v\" IS NULL"),
        ViewChange.replace(Provider.POSTGRESQL, was, "CREATE VIEW public.v AS SELECT 2;")
            .statements(),
        "The comment line deleted, the comment goes");
    var kept = ViewChange.replace(
        Provider.POSTGRESQL,
        was,
        "CREATE VIEW public.v AS SELECT 2;\nCOMMENT ON VIEW public.v IS 'kept';");
    assertEquals(
        List.of(
            "CREATE OR REPLACE VIEW public.v AS SELECT 2",
            "COMMENT ON VIEW \"public\".\"v\" IS NULL",
            "COMMENT ON VIEW public.v IS 'kept'"),
        kept.statements());
    assertTrue(kept.transaction());
  }

  @Test
  void aDropAndCreateGivesBackWhatTheDropTook() {
    var view = new Table(PUBLIC, "v", Table.Kind.VIEW);
    var was = new ViewDefinition(
        view,
        read(view).definition(),
        References.NONE,
        false,
        List.of("ALTER VIEW \"public\".\"v\" OWNER TO \"owner\""),
        List.of("COMMENT ON VIEW \"public\".\"v\" IS NULL"));
    assertEquals(
        List.of(
            "DROP VIEW \"public\".\"v\"",
            "CREATE VIEW public.v AS SELECT 2",
            "ALTER VIEW \"public\".\"v\" OWNER TO \"owner\""),
        ViewChange.recreate(Provider.POSTGRESQL, was, "CREATE VIEW public.v AS SELECT 2")
            .statements());
  }

  @Test
  void aReplaceOfSeveralStatementsRunsInOneTransactionWhereTheServerCan() {
    var view = new Table(PUBLIC, "v", Table.Kind.VIEW);
    var change = ViewChange.replace(
        Provider.POSTGRESQL,
        read(view),
        "CREATE RECURSIVE VIEW public.v (n) AS SELECT 1;\nCOMMENT ON VIEW public.v IS 'x';");
    assertEquals(
        List.of(
            "CREATE OR REPLACE RECURSIVE VIEW public.v (n) AS SELECT 1",
            "COMMENT ON VIEW public.v IS 'x'"),
        change.statements());
    assertTrue(change.transaction());
    assertFalse(
        ViewChange.replace(Provider.POSTGRESQL, read(view), "CREATE VIEW public.v AS SELECT 1")
            .transaction(),
        "One statement is a transaction by itself");
  }

  @Test
  void aMaterializedViewIsDroppedCreatedAndRefreshedInOneTransaction() {
    var view = new Table(PUBLIC, "m", Table.Kind.MATERIALIZED);
    var change = ViewChange.replace(
        Provider.POSTGRESQL, read(view, "REFRESH MATERIALIZED VIEW \"public\".\"m\""), """
        CREATE MATERIALIZED VIEW public.m AS SELECT 1
          WITH NO DATA;
        CREATE UNIQUE INDEX i ON public.m USING btree (x);""");
    assertEquals(ViewChange.Kind.RECREATE, change.kind());
    assertEquals(
        List.of(
            "DROP MATERIALIZED VIEW \"public\".\"m\"",
            "CREATE MATERIALIZED VIEW public.m AS SELECT 1\n  WITH NO DATA",
            "CREATE UNIQUE INDEX i ON public.m USING btree (x)",
            "REFRESH MATERIALIZED VIEW \"public\".\"m\""),
        change.statements());
    assertTrue(change.transaction());
  }

  @Test
  void aServerThatChangesNoDefinitionInATransactionIsNotAskedToDropAndCreate() {
    var read = read(new Table(SHOP, "v", Table.Kind.VIEW));
    assertThrows(
        IllegalArgumentException.class,
        () -> ViewChange.recreate(Provider.MARIADB, read, "CREATE VIEW v AS SELECT 1"));
  }

  @Test
  void aCreateQualifiesTheNameAndTakesOneQuery() {
    var change = ViewChange.create(Provider.POSTGRESQL, PUBLIC, "Big \"one\"", "\nSELECT 1;\n");
    assertEquals(
        List.of("CREATE VIEW \"public\".\"Big \"\"one\"\"\" AS\nSELECT 1"), change.statements());
    assertEquals(new Table(PUBLIC, "Big \"one\"", Table.Kind.VIEW), change.view());
    assertThrows(
        IllegalArgumentException.class,
        () -> ViewChange.create(Provider.POSTGRESQL, PUBLIC, " ", "SELECT 1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ViewChange.create(Provider.POSTGRESQL, PUBLIC, "v", "SELECT 1; SELECT 2"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ViewChange.create(Provider.POSTGRESQL, PUBLIC, "v", "-- nothing"));
  }

  @Test
  void aDropCascadesOnlyWhereAskedAndTheServerCan() {
    var view = new Table(PUBLIC, "v", Table.Kind.VIEW);
    assertEquals(
        List.of("DROP VIEW \"public\".\"v\" CASCADE"),
        ViewChange.drop(Provider.POSTGRESQL, view, true).statements());
    assertEquals(
        List.of("DROP VIEW \"public\".\"v\""),
        ViewChange.drop(Provider.POSTGRESQL, view, false).statements());
    assertEquals(
        List.of("DROP VIEW `shop`.`v`"),
        ViewChange.drop(Provider.MYSQL, new Table(SHOP, "v", Table.Kind.VIEW), true)
            .statements());
  }

  @Test
  void onlyAMaterializedViewRefreshes() {
    var kept = new Table(PUBLIC, "m", Table.Kind.MATERIALIZED);
    assertEquals(
        List.of("REFRESH MATERIALIZED VIEW CONCURRENTLY \"public\".\"m\""),
        ViewChange.refresh(Provider.POSTGRESQL, kept, true).statements());
    var plain = new Table(PUBLIC, "v", Table.Kind.VIEW);
    assertThrows(
        IllegalArgumentException.class,
        () -> ViewChange.refresh(Provider.POSTGRESQL, plain, false));
  }
}
