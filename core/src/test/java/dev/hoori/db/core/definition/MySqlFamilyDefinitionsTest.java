package dev.hoori.db.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MySqlFamilyDefinitionsTest {

  @Test
  void aCreateTableLeavesItsForeignKeysAndIndexesThatAreNoKeysForLater() {
    var created = MySqlFamilyDefinitions.Created.of(Provider.MYSQL, """
        CREATE TABLE `it``em` (
          `id` int NOT NULL AUTO_INCREMENT,
          `name` varchar(50) NOT NULL COMMENT 'KEY (x),',
          `other` int DEFAULT NULL,
          `txt` text,
          PRIMARY KEY (`id`),
          UNIQUE KEY `named` (`name`),
          KEY `by_other` (`other`),
          FULLTEXT KEY `words` (`txt`),
          CONSTRAINT `to_other` FOREIGN KEY (`other`) REFERENCES `other` (`id`) ON DELETE CASCADE,
          CONSTRAINT `positive` CHECK ((`other` > 0))
        ) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4""", "`it``em`");
    assertEquals("""
        CREATE TABLE `it``em` (
          `id` int NOT NULL AUTO_INCREMENT,
          `name` varchar(50) NOT NULL COMMENT 'KEY (x),',
          `other` int DEFAULT NULL,
          `txt` text,
          PRIMARY KEY (`id`),
          UNIQUE KEY `named` (`name`),
          CONSTRAINT `positive` CHECK ((`other` > 0))
        ) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4""", created.create());
    assertEquals(
        List.of(
            "ALTER TABLE `it``em` ADD KEY `by_other` (`other`)",
            "ALTER TABLE `it``em` ADD FULLTEXT KEY `words` (`txt`)"),
        created.indexes());
    assertEquals(
        List.of("ALTER TABLE `it``em` ADD CONSTRAINT `to_other` FOREIGN KEY (`other`) REFERENCES"
            + " `other` (`id`) ON DELETE CASCADE"),
        created.keys());
  }

  @Test
  void anIndexOnTheAutoIncrementColumnStaysInItsCreateTable() {
    var created = MySqlFamilyDefinitions.Created.of(Provider.MYSQL, """
        CREATE TABLE `t` (
          `id` int NOT NULL AUTO_INCREMENT COMMENT 'counted',
          `k` int DEFAULT NULL,
          KEY `by_id` (`id`,`k`),
          KEY `by_k` (`k`),
          KEY `by_sum` (((`id` + `k`)))
        ) ENGINE=InnoDB""", "`t`");
    assertEquals("""
        CREATE TABLE `t` (
          `id` int NOT NULL AUTO_INCREMENT COMMENT 'counted',
          `k` int DEFAULT NULL,
          KEY `by_id` (`id`,`k`)
        ) ENGINE=InnoDB""", created.create());
    assertEquals(
        List.of(
            "ALTER TABLE `t` ADD KEY `by_k` (`k`)",
            "ALTER TABLE `t` ADD KEY `by_sum` (((`id` + `k`)))"),
        created.indexes());
  }

  @Test
  void aDefinitionGoesWithoutItsDefiner() {
    assertEquals(
        "CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `v` AS select 1",
        MySqlFamilyDefinitions.withoutDefiner(
            "CREATE ALGORITHM=UNDEFINED DEFINER=`ro``ot`@`%` SQL SECURITY DEFINER VIEW `v` AS"
                + " select 1"));
    assertEquals(
        "CREATE TRIGGER `t` BEFORE INSERT ON `x` FOR EACH ROW SET @a = 1",
        MySqlFamilyDefinitions.withoutDefiner(
            "CREATE DEFINER=`admin` TRIGGER `t` BEFORE INSERT ON `x` FOR EACH ROW SET @a = 1"));
    assertEquals(
        "CREATE VIEW `v` AS select 1",
        MySqlFamilyDefinitions.withoutDefiner("CREATE VIEW `v` AS select 1"));
  }

  @Test
  void aCreateTableSplitsOnlyAtCommasOutsideNamesAndText() {
    var created = MySqlFamilyDefinitions.Created.of(Provider.MARIADB, """
        CREATE TABLE `t` (
          `first
        second, third` int DEFAULT NULL,
          `k` varchar(5) DEFAULT 'a,
        b',
          KEY `by, k` (`k`),
          CONSTRAINT `to,
        other` FOREIGN KEY (`k`) REFERENCES `other` (`k`)
        ) ENGINE=InnoDB""", "`t`");
    assertEquals("""
        CREATE TABLE `t` (
          `first
        second, third` int DEFAULT NULL,
          `k` varchar(5) DEFAULT 'a,
        b'
        ) ENGINE=InnoDB""", created.create());
    assertEquals(List.of("ALTER TABLE `t` ADD KEY `by, k` (`k`)"), created.indexes());
    assertEquals(
        List.of("ALTER TABLE `t` ADD CONSTRAINT `to,\nother` FOREIGN KEY (`k`) REFERENCES"
            + " `other` (`k`)"),
        created.keys());
  }

  @Test
  void aSequencesDatabaseGoesFromItsDefaultButNotFromText() {
    assertEquals(
        "`id` int DEFAULT nextval(`s`), `k` varchar(40) DEFAULT 'nextval(`db`.`s`)'",
        MySqlFamilyDefinitions.withoutDatabase(
            Provider.MARIADB,
            "`id` int DEFAULT nextval(`db`.`s`), `k` varchar(40) DEFAULT 'nextval(`db`.`s`)'",
            "`db`"));
    // Another database's sequence keeps its name.
    assertEquals(
        "`id` int DEFAULT NEXTVAL(`other`.`s`)",
        MySqlFamilyDefinitions.withoutDatabase(
            Provider.MARIADB, "`id` int DEFAULT NEXTVAL(`other`.`s`)", "`db`"));
  }

  @Test
  void aViewNeedsWhatItReadsAndCallsNotTheNamesOfItsColumnsAndAliases() {
    var database = new Database("db", false);
    var a = Item.of(database, Item.Kind.VIEW, "a");
    var z = Item.of(database, Item.Kind.VIEW, "z");
    var t = Item.of(database, Item.Kind.TABLE, "t");
    var u = Item.of(database, Item.Kind.TABLE, "u");
    var f = Item.of(database, Item.Kind.FUNCTION, "f");
    var known = Set.of(a, z, t, u, f);
    assertEquals(
        Set.of(t),
        MySqlFamilyDefinitions.viewNeeds(
            Provider.MYSQL,
            "CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `z` AS select `t`.`id` AS `a`"
                + " from `t`",
            z,
            known));
    assertEquals(
        Set.of(z, u, f),
        MySqlFamilyDefinitions.viewNeeds(
            Provider.MYSQL,
            "CREATE VIEW `a` AS select `x`.`a` AS `a`,`f`(`x`.`a`) AS `t`, 'from `t`' AS `s`"
                + " from (`z` `x` join `db`.`u` `t`) where `x`.`a` = `t`.`id`",
            a,
            known));
  }
}
