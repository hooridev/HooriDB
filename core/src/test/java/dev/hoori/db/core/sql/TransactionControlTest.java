package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionControlTest {

  @Test
  void postgreSqlsStatementsThatBeginOrEndATransaction() {
    for (var sql : List.of(
        "BEGIN",
        "begin work",
        "START TRANSACTION ISOLATION LEVEL SERIALIZABLE",
        "COMMIT",
        "END",
        "ABORT",
        "ROLLBACK",
        "ROLLBACK AND CHAIN",
        "PREPARE TRANSACTION 'x'")) {
      assertTrue(TransactionControl.in(sql, POSTGRESQL), sql);
    }
    for (var sql : List.of(
        "ROLLBACK TO SAVEPOINT s",
        "SAVEPOINT s",
        "PREPARE q AS SELECT 1",
        "SELECT 'COMMIT'",
        "-- COMMIT\nSELECT 1",
        "CREATE FUNCTION f() RETURNS int BEGIN ATOMIC SELECT 1; END")) {
      assertFalse(TransactionControl.in(sql, POSTGRESQL), sql);
    }
  }

  @Test
  void mySqlsStatementsThatBeginOrEndATransaction() {
    for (var sql : List.of(
        "START TRANSACTION",
        "BEGIN",
        "COMMIT",
        "ROLLBACK WORK",
        "XA START 'x'",
        "SET autocommit=0",
        "SET @@session.autocommit = 1",
        "/*!40101 SET AUTOCOMMIT=1 */")) {
      assertTrue(TransactionControl.in(sql, MYSQL), sql);
    }
    for (var sql : List.of(
        "ROLLBACK TO SAVEPOINT s",
        "START REPLICA",
        "SET NAMES utf8mb4",
        "LOCK TABLES t WRITE",
        "CREATE PROCEDURE p() BEGIN COMMIT; END")) {
      assertFalse(TransactionControl.in(sql, MYSQL), sql);
    }
    // MariaDB's compound statement is none.
    assertFalse(TransactionControl.in("BEGIN NOT ATOMIC SELECT 1; END", MARIADB));
  }
}
