package dev.hoori.db.core.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MySqlFamilyDumpTest {

  @Test
  void aCreateTableLeavesItsForeignKeysAndIndexesThatAreNoKeysForLater() {
    var created = MySqlFamilyDump.Created.of("""
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
    var created = MySqlFamilyDump.Created.of("""
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
        MySqlFamilyDump.withoutDefiner(
            "CREATE ALGORITHM=UNDEFINED DEFINER=`ro``ot`@`%` SQL SECURITY DEFINER VIEW `v` AS"
                + " select 1"));
    assertEquals(
        "CREATE TRIGGER `t` BEFORE INSERT ON `x` FOR EACH ROW SET @a = 1",
        MySqlFamilyDump.withoutDefiner(
            "CREATE DEFINER=`admin` TRIGGER `t` BEFORE INSERT ON `x` FOR EACH ROW SET @a = 1"));
    assertEquals(
        "CREATE VIEW `v` AS select 1",
        MySqlFamilyDump.withoutDefiner("CREATE VIEW `v` AS select 1"));
  }
}
