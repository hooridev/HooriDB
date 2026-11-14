package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.Locale;
import java.util.Set;

/** Names as they are written into SQL: quoted where the server would not take them as they are. */
public final class Names {

  private Names() {}

  // PostgreSQL's reserved key words, also those that can be a function or a type, from the table
  // of key words in its documentation.
  private static final Set<String> POSTGRESQL = Set.of("""
      all analyse analyze and any array as asc asymmetric authorization binary both case cast
      check collate collation column concurrently constraint create cross current_catalog
      current_date current_role current_schema current_time current_timestamp current_user
      default deferrable desc distinct do else end except false fetch for foreign freeze from
      full grant group having ilike in initially inner intersect into is isnull join lateral
      leading left like limit localtime localtimestamp natural not notnull null offset on only
      or order outer overlaps placing primary references returning right select session_user
      similar some symmetric system_user table tablesample then to trailing true union unique
      user using variadic verbose when where window with
      """.strip().split("\\s+"));

  // The reserved words of MySQL 8.4 and of MariaDB 11, from their manuals, in one set: a name
  // that only one of them reserves is quoted on both.
  private static final Set<String> MYSQL = Set.of("""
      ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB
      BOTH BY CALL CASCADE CASE CHANGE CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONSTRAINT
      CONTINUE CONVERT CREATE CROSS CUBE CUME_DIST CURRENT_DATE CURRENT_ROLE CURRENT_TIME
      CURRENT_TIMESTAMP CURRENT_USER CURSOR DATABASE DATABASES DAY_HOUR DAY_MICROSECOND
      DAY_MINUTE DAY_SECOND DEC DECIMAL DECLARE DEFAULT DELAYED DELETE DELETE_DOMAIN_ID
      DENSE_RANK DESC DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DO_DOMAIN_IDS DOUBLE DROP
      DUAL EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED EXCEPT EXISTS EXIT EXPLAIN FALSE FETCH
      FIRST_VALUE FLOAT FLOAT4 FLOAT8 FOR FORCE FOREIGN FROM FULLTEXT FUNCTION GENERAL
      GENERATED GET GRANT GROUP GROUPING GROUPS HAVING HIGH_PRIORITY HOUR_MICROSECOND
      HOUR_MINUTE HOUR_SECOND IF IGNORE IGNORE_DOMAIN_IDS IGNORE_SERVER_IDS IN INDEX INFILE
      INNER INOUT INSENSITIVE INSERT INT INT1 INT2 INT3 INT4 INT8 INTEGER INTERSECT INTERVAL
      INTO IO_AFTER_GTIDS IO_BEFORE_GTIDS IS ITERATE JOIN JSON_TABLE KEY KEYS KILL LAG
      LAST_VALUE LATERAL LEAD LEADING LEAVE LEFT LIKE LIMIT LINEAR LINES LOAD LOCALTIME
      LOCALTIMESTAMP LOCK LONG LONGBLOB LONGTEXT LOOP LOW_PRIORITY MASTER_BIND
      MASTER_HEARTBEAT_PERIOD MASTER_SSL_VERIFY_SERVER_CERT MATCH MAXVALUE MEDIUMBLOB MEDIUMINT
      MEDIUMTEXT MIDDLEINT MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT
      NO_WRITE_TO_BINLOG NTH_VALUE NTILE NULL NUMERIC OF OFFSET ON OPTIMIZE OPTIMIZER_COSTS
      OPTION OPTIONALLY OR ORDER OUT OUTER OUTFILE OVER PAGE_CHECKSUM PARSE_VCOL_EXPR PARTITION
      PERCENT_RANK POSITION PRECISION PRIMARY PROCEDURE PURGE RANGE RANK READ READS READ_WRITE
      REAL RECURSIVE REF_SYSTEM_ID REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE REQUIRE
      RESIGNAL RESTRICT RETURN RETURNING REVOKE RIGHT RLIKE ROW ROWS ROW_NUMBER SCHEMA SCHEMAS
      SECOND_MICROSECOND SELECT SENSITIVE SEPARATOR SET SHOW SIGNAL SLOW SMALLINT SPATIAL
      SPECIFIC SQL SQLEXCEPTION SQLSTATE SQLWARNING SQL_BIG_RESULT SQL_CALC_FOUND_ROWS
      SQL_SMALL_RESULT SSL STARTING STATS_AUTO_RECALC STATS_PERSISTENT STATS_SAMPLE_PAGES
      STORED STRAIGHT_JOIN SYSTEM TABLE TERMINATED THEN TINYBLOB TINYINT TINYTEXT TO TRAILING
      TRIGGER TRUE UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE USE USING UTC_DATE UTC_TIME
      UTC_TIMESTAMP VALUES VARBINARY VARCHAR VARCHARACTER VARYING VIRTUAL WHEN WHERE WHILE
      WINDOW WITH WRITE XOR YEAR_MONTH ZEROFILL
      """.strip().split("\\s+"));

  /**
   * The name as it is written for the provider's server: as it is where the server takes it so,
   * else in double quotes on PostgreSQL and in backticks on MariaDB and MySQL. Quoted are a
   * reserved word, a name with anything but ASCII letters, digits and {@code _} or that begins
   * with a digit, and on PostgreSQL one with a capital letter, which it would fold.
   */
  public static String quoted(String name, Provider provider) {
    boolean postgres = provider == Provider.POSTGRESQL;
    boolean plain = !name.isEmpty() && !Character.isDigit(name.charAt(0));
    for (int i = 0; plain && i < name.length(); i++) {
      char c = name.charAt(i);
      plain = c >= 'a' && c <= 'z'
          || c >= '0' && c <= '9'
          || c == '_'
          || !postgres && c >= 'A' && c <= 'Z';
    }
    plain &= postgres ? !POSTGRESQL.contains(name) : !MYSQL.contains(name.toUpperCase(Locale.ROOT));
    if (plain) {
      return name;
    }
    var quote = postgres ? "\"" : "`";
    return quote + name.replace(quote, quote + quote) + quote;
  }
}
