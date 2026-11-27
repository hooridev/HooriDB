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
        "SET @saved = @@autocommit, autocommit := 0",
        "SET SESSION `autocommit`=1",
        "/*!40101 SET AUTOCOMMIT=1 */")) {
      assertTrue(TransactionControl.in(sql, MYSQL), sql);
    }
    for (var sql : List.of(
        "ROLLBACK TO SAVEPOINT s",
        "START REPLICA",
        "SET NAMES utf8mb4",
        // Only reads it, to set it back later.
        "SET @OLD_AUTOCOMMIT=@@AUTOCOMMIT",
        "SET autocommit_x = @@autocommit",
        // A user variable of that name.
        "SET @autocommit = 0",
        "SET @autocommit := 1, @x = @@autocommit",


        "LOCK TABLES t WRITE",
        "CREATE PROCEDURE p() BEGIN COMMIT; END")) {
      assertFalse(TransactionControl.in(sql, MYSQL), sql);
    }
    // MariaDB's compound statement runs its body at once: one only where the body holds one.
    assertFalse(TransactionControl.in("BEGIN NOT ATOMIC SELECT 1; END", MARIADB));
    assertFalse(TransactionControl.in(
        "BEGIN NOT ATOMIC SAVEPOINT s; ROLLBACK TO SAVEPOINT s; END", MARIADB));
    assertFalse(TransactionControl.in(
        "BEGIN NOT ATOMIC SAVEPOINT s; ROLLBACK WORK TO s; SET @a = @@autocommit; END", MARIADB));
    // As are its other compound statements, a label's too.
    assertFalse(TransactionControl.in("IF TRUE THEN SELECT 1; END IF", MARIADB));
    assertFalse(TransactionControl.in("l: LOOP LEAVE l; END LOOP l", MARIADB));
    for (var sql : List.of(
        "IF TRUE THEN COMMIT; END IF",
        "IF FALSE THEN SELECT 1; ELSE ROLLBACK; END IF",
        "CASE WHEN 1 THEN SET autocommit = 1; END CASE",
        "l: LOOP COMMIT; LEAVE l; END LOOP l",
        "REPEAT START TRANSACTION; UNTIL TRUE END REPEAT",
        "WHILE FALSE DO XA END 'x'; END WHILE",
        "FOR i IN 1..2 DO COMMIT; END FOR",
        "outer: BEGIN NOT ATOMIC COMMIT; END outer",
        "BEGIN NOT ATOMIC COMMIT; END",
        "BEGIN NOT ATOMIC INSERT INTO t VALUES (1); ROLLBACK; END",
        "BEGIN NOT ATOMIC START TRANSACTION; END",
        "BEGIN NOT ATOMIC SET autocommit = 1; END",
        "BEGIN NOT ATOMIC ROLLBACK WORK; END",
        "BEGIN NOT ATOMIC DECLARE EXIT HANDLER FOR SQLEXCEPTION ROLLBACK; SELECT 1; END")) {
      assertTrue(TransactionControl.in(sql, MARIADB), sql);
    }
  }

  @Test
  void mySqlsStatementsThatCommitByThemselvesBesidesDdl() {
    for (var provider : List.of(MYSQL, MARIADB)) {
      for (var sql : List.of(
          "LOCK TABLES `t` WRITE",
          "lock table t read, u write",
          "UNLOCK TABLES",
          "GRANT SELECT ON db.* TO 'u'@'%'",
          "REVOKE ALL ON db.* FROM u",
          "CREATE USER u IDENTIFIED BY 'x'",
          "CREATE OR REPLACE USER u",
          "DROP USER IF EXISTS u",
          "ALTER USER u ACCOUNT LOCK",
          "RENAME USER a TO b",
          "CREATE ROLE r",
          "SET PASSWORD FOR u = 'x'",
          "SET DEFAULT ROLE ALL TO u",
          "ANALYZE TABLE t",
          "ANALYZE NO_WRITE_TO_BINLOG TABLE t",
          "OPTIMIZE LOCAL TABLE t",
          "REPAIR TABLE t",
          "CHECK TABLE t",
          "FLUSH PRIVILEGES",
          "CACHE INDEX t IN c",
          "LOAD INDEX INTO CACHE t")) {
        assertTrue(TransactionControl.commits(sql, provider), provider + ": " + sql);
      }
      for (var sql : List.of(
          "INSERT INTO t VALUES (1)",
          "SELECT 'LOCK TABLES'",
          "ANALYZE SELECT * FROM t",
          "CREATE TABLE user (id int)",
          "SET ROLE r",
          "LOAD DATA INFILE 'x' INTO TABLE t")) {
        assertFalse(TransactionControl.commits(sql, provider), provider + ": " + sql);
      }
    }
    // PostgreSQL commits only where the transaction ends.
    assertFalse(TransactionControl.commits("LOCK TABLE t", POSTGRESQL));
    assertFalse(TransactionControl.commits("GRANT SELECT ON t TO u", POSTGRESQL));
  }
}
