package dev.hoori.db.core.sql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import org.junit.jupiter.api.Test;

class ReadWriteTest {

  private static void turnsOff(Provider provider, String sql) {
    assertTrue(ReadWrite.in(sql, provider), sql);
  }

  private static void keeps(Provider provider, String sql) {
    assertFalse(ReadWrite.in(sql, provider), sql);
  }

  @Test
  void postgreSqlsWaysBackToReadWriteAreSeen() {
    var p = Provider.POSTGRESQL;
    turnsOff(p, "SET default_transaction_read_only = off");
    turnsOff(p, "set Default_Transaction_Read_Only to DEFAULT");
    turnsOff(p, "SET LOCAL \"transaction_read_only\" = false");
    turnsOff(p, "SET SESSION CHARACTERISTICS AS TRANSACTION READ\n WRITE");
    turnsOff(p, "BEGIN ISOLATION LEVEL SERIALIZABLE, READ WRITE");
    turnsOff(p, "START TRANSACTION READ WRITE");
    turnsOff(p, "RESET ALL");
    turnsOff(p, "DISCARD ALL");
    turnsOff(p, "SELECT set_config('default_transaction_read_only', 'off', false)");
    turnsOff(p, "DO $$BEGIN PERFORM set_config('transaction_read_only', 'off', true); END$$");
    turnsOff(p, "DO $$BEGIN RESET   ALL; END$$");
  }

  @Test
  void mariaDbsAndMySqlsWaysBackToReadWriteAreSeen() {
    for (var p : new Provider[] {Provider.MARIADB, Provider.MYSQL}) {
      turnsOff(p, "SET SESSION TRANSACTION READ WRITE");
      turnsOff(p, "SET TRANSACTION ISOLATION LEVEL READ COMMITTED, READ WRITE");
      turnsOff(p, "START TRANSACTION READ WRITE");
      turnsOff(p, "SET @@session.transaction_read_only = 0");
      turnsOff(p, "SET @@tx_read_only=OFF");
      turnsOff(p, "SET SESSION `transaction_read_only` = DEFAULT");
      turnsOff(p, "/*!50605 SET SESSION TRANSACTION READ WRITE */");
      turnsOff(p, "PREPARE s FROM 'SET SESSION TRANSACTION READ WRITE'");
      turnsOff(p, "/*!50000 PREPARE s FROM 'SET SESSION TRANSACTION READ WRITE' */");
    }
    turnsOff(Provider.MARIADB, "SET SESSION AUTHORIZATION 'other'@'%'");
    turnsOff(Provider.MYSQL, "CREATE TABLE t (id integer) ENGINE=InnoDB START TRANSACTION");
  }

  @Test
  void otherStatementsAndReadingTheSettingGo() {
    for (var p : new Provider[] {Provider.POSTGRESQL, Provider.MARIADB, Provider.MYSQL}) {
      keeps(p, "SELECT * FROM t WHERE a = 1");
      keeps(p, "INSERT INTO t VALUES (1)");
      keeps(p, "SET TRANSACTION READ ONLY");
      keeps(p, "START TRANSACTION");
      keeps(p, "start transaction read only");
      keeps(p, "SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
      keeps(p, "SHOW transaction_read_only");
      keeps(p, "SELECT 1 -- SET transaction_read_only = off");
      keeps(p, "SELECT read_write FROM t");
    }
    keeps(Provider.MYSQL, "SHOW VARIABLES LIKE 'transaction_read_only'");
    keeps(Provider.POSTGRESQL, "RESET search_path");
    keeps(Provider.POSTGRESQL, "DISCARD PLANS");
  }
}
