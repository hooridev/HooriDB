package dev.hoori.db.core.dialect;

import java.util.HashSet;
import java.util.Set;

// Sets of key words, as the providers' syntaxes write them.
final class Words {

  private Words() {}

  // Non-reserved words typed often that all three know: each is in PostgreSQL's table of key
  // words and in MySQL's list; MariaDB, which MySQL's grammar comes from, knows them too.
  static final Set<String> TYPED = words("""
      ACTION ADD ALTER ALWAYS BEGIN BETWEEN BIGINT BOOLEAN BY CASCADE CASCADED CHAR CHARACTER
      COALESCE COLLATION COLUMNS COMMENT COMMIT COMMITTED CURRENT DATA DATABASE DAY DEC DECIMAL
      DELETE DISABLE DOUBLE DROP ENABLE ENUM ESCAPE EXCLUDE EXECUTE EXISTS EXPLAIN FIRST FLOAT
      FOLLOWING FORMAT FULL FUNCTION GLOBAL HOUR IF INDEX INOUT INSERT INT INTEGER INTERVAL
      ISOLATION JSON KEY LANGUAGE LAST LEVEL LOCAL LOCK MINUTE MODE MONTH NAMES NO NOWAIT NULLS
      NUMERIC OPTION OUT OWNER PARTITION PRECEDING PRECISION PREPARE PRIVILEGES PROCEDURE RANGE
      READ REAL RECURSIVE RELEASE RENAME REPEATABLE REPLACE RESTRICT RETURN RETURNS REVOKE ROLE
      ROLLBACK ROLLUP ROW ROWS SAVEPOINT SCHEMA SCHEMAS SECOND SERIALIZABLE SESSION SET SHARE
      SHOW SKIP SMALLINT START STORED TABLES TEMPORARY TEXT TIES TIME TIMESTAMP TRANSACTION
      TRIGGER TRUNCATE TYPE UNBOUNDED UNCOMMITTED UPDATE VALUE VALUES VARCHAR VARYING VIEW WORK
      WRITE YEAR ZONE
      """);

  static Set<String> words(String text) {
    return Set.of(text.strip().split("\\s+"));
  }

  @SafeVarargs
  static Set<String> union(Set<String>... sets) {
    var all = new HashSet<String>();
    for (var set : sets) {
      all.addAll(set);
    }
    return Set.copyOf(all);
  }
}
