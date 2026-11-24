package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.dialect.Reflection.call;
import static dev.hoori.db.core.dialect.Since.always;
import static dev.hoori.db.core.dialect.Since.since;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.data.MariaDbRows;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.sql.Statements;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** MariaDB, with MariaDB Connector/J. */
public final class MariaDb extends MySqlFamily {

  // Before INSTANCE, whose syntax holds them.
  // The whole table of "Reserved Words" in MariaDB's documentation, which says from or up to which
  // version a word is reserved: every version's.
  private static final Set<String> RESERVED = Words.words("""
      ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB
      BOTH BY CALL CASCADE CASE CHANGE CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONSTRAINT
      CONTINUE CONVERSION CONVERT CREATE CROSS CURRENT_DATE CURRENT_PATH CURRENT_ROLE
      CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DATABASES DAY_HOUR DAY_MICROSECOND
      DAY_MINUTE DAY_SECOND DEC DECIMAL DECLARE DEFAULT DELAYED DELETE DELETE_DOMAIN_ID DESC
      DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DOUBLE DO_DOMAIN_IDS DROP DUAL EACH ELSE
      ELSEIF ENCLOSED ESCAPED EXCEPT EXISTS EXIT EXPLAIN FALSE FETCH FLOAT FLOAT4 FLOAT8 FOR
      FORCE FOREIGN FROM FULLTEXT GRANT GROUP HAVING HIGH_PRIORITY HOUR_MICROSECOND HOUR_MINUTE
      HOUR_SECOND IF IGNORE IGNORE_DOMAIN_IDS IN INDEX INFILE INNER INOUT INSENSITIVE INSERT
      INT INT1 INT2 INT3 INT4 INT8 INTEGER INTERSECT INTERVAL INTO IS ITERATE JOIN KEY KEYS
      KILL LEADING LEAVE LEFT LIKE LIMIT LINEAR LINES LOAD LOCALTIME LOCALTIMESTAMP LOCK LONG
      LONGBLOB LONGTEXT LOOP LOW_PRIORITY MASTER_DEMOTE_TO_REPLICA MASTER_DEMOTE_TO_SLAVE
      MASTER_SSL_VERIFY_SERVER_CERT MATCH MAXVALUE MEDIUMBLOB MEDIUMINT MEDIUMTEXT MIDDLEINT
      MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT NO_WRITE_TO_BINLOG NULL NUMERIC
      OFFSET ON OPTIMIZE OPTIONALLY OR ORDER OUT OUTER OUTFILE OVER PAGE_CHECKSUM
      PARSE_VCOL_EXPR PARTITION PORTION PRECISION PRIMARY PROCEDURE PURGE RANGE READ READS
      READ_WRITE REAL RECURSIVE REFERENCES REF_SYSTEM_ID REGEXP RELEASE RENAME REPEAT REPLACE
      REQUIRE RESIGNAL RESTRICT RETURN RETURNING REVOKE RIGHT RLIKE ROWS ROW_NUMBER SCHEMAS
      SECOND_MICROSECOND SELECT SENSITIVE SEPARATOR SET SHOW SIGNAL SMALLINT SPATIAL SPECIFIC
      SQL SQLEXCEPTION SQLSTATE SQLWARNING SQL_AFTER_GTIDS SQL_BEFORE_GTIDS SQL_BIG_RESULT
      SQL_CALC_FOUND_ROWS SQL_SMALL_RESULT SSL STARTING STATS_AUTO_RECALC STATS_PERSISTENT
      STATS_SAMPLE_PAGES STRAIGHT_JOIN TABLE TERMINATED THEN TINYBLOB TINYINT TINYTEXT TO
      TO_DATE TRAILING TRIGGER TRUE UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE USE USING
      UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VARBINARY VARCHAR VARCHARACTER VARYING VECTOR WHEN
      WHERE WHILE WITH WRITE XOR YEAR_MONTH ZEROFILL
      """);

  public static final MariaDb INSTANCE = new MariaDb();

  private final Map<Capability, Since> capabilities = Map.of(
      EXPLAIN, always(PLAN),
      EXPLAIN_JSON, since(10, 1, 2, PLAN_JSON),
      EXPLAIN_ANALYZE, since(10, 1, 0, "ANALYZE "));
  private final Driver driver =
      new Driver("org.mariadb.jdbc", "mariadb-java-client", "org.mariadb.jdbc.Driver");
  private final ConnectDialect connecting = new Connect();
  private final SyntaxDialect syntax = new MySqlFamily.Syntax(
      RESERVED,
      Words.union(RESERVED, Words.TYPED, Syntax.TYPED, Set.of("SEQUENCE")),
      List.of("/*!", "/*M!"));
  private final PlanDialect planning = new Plan();
  private final SessionDialect session = new Session();
  private final RowsDialect rows = new MariaDbRows();
  private final MonitorDialect monitor = new MySqlFamily.Monitor(
      "TIME_MS / 1000",
      "SELECT LOWER(VARIABLE_NAME), GLOBAL_VALUE, GLOBAL_VALUE_ORIGIN"
          + " FROM information_schema.SYSTEM_VARIABLES ORDER BY 1",
      // InnoDB's row locks; a metadata lock is not among them.
      "SELECT r.trx_mysql_thread_id, b.trx_mysql_thread_id, l.lock_table, l.lock_mode"
          + " FROM information_schema.INNODB_LOCK_WAITS w"
          + " JOIN information_schema.INNODB_TRX r ON r.trx_id = w.requesting_trx_id"
          + " JOIN information_schema.INNODB_TRX b ON b.trx_id = w.blocking_trx_id"
          + " LEFT JOIN information_schema.INNODB_LOCKS l ON l.lock_id = w.requested_lock_id"
          + " ORDER BY 1, 2");

  private MariaDb() {
    super(false);
  }

  @Override
  public String displayName() {
    return "MariaDB";
  }

  @Override
  public String storedName() {
    return "mariadb";
  }

  @Override
  public Map<Capability, Since> capabilities() {
    return capabilities;
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public String driverName() {
    return "MariaDB Connector/J";
  }

  @Override
  public ConnectDialect connecting() {
    return connecting;
  }

  @Override
  public SyntaxDialect syntax() {
    return syntax;
  }

  @Override
  public PlanDialect plan() {
    return planning;
  }

  @Override
  public SessionDialect session() {
    return session;
  }

  @Override
  public RowsDialect rows() {
    return rows;
  }

  @Override
  public MonitorDialect monitor() {
    return monitor;
  }

  // MariaDB's plan gives only the rows read of each table: the product over the tables of each
  // select of the outermost UNION, summed, at most its LIMIT.
  static final class Plan extends MySqlFamily.Plan {

    @Override
    public String explain(String sql) {
      return "EXPLAIN " + sql;
    }

    @Override
    public OptionalLong rows(ResultSet rows, String sql, Provider provider) throws SQLException {
      var selects = new Selects();
      while (rows.next()) {
        selects.add(rows);
      }
      if (!selects.any) {
        return OptionalLong.empty();
      }
      var limit = Statements.limit(sql, provider);
      return OptionalLong.of(Math.min(selects.outermost(), limit.orElse(Long.MAX_VALUE)));
    }

    // Each select's tables, by its id, joined; and the selects of a UNION inside another.
    private static final class Selects {

      private final Map<Object, Long> products = new LinkedHashMap<>();
      private final Set<Object> unions = new HashSet<>();
      private final Set<String> inner = new HashSet<>();
      // Whether any table has its rows read.
      private boolean any;

      void add(ResultSet rows) throws SQLException {
        var id = rows.getObject("id");
        var type = rows.getString("select_type");
        if (id == null) {
          var table = String.valueOf(rows.getString("table"));
          if (table.startsWith("<union")) {
            // The ids in "<union1,2,3>": without the letters of "<union>" and any dots.
            inner.addAll(List.of(table.replaceAll("[<>.inou]", "").split(",")));
          }
          return;
        }
        if ("UNION".equals(type)) {
          unions.add(id);
        }
        long read = rows.getLong("rows");
        read = rows.wasNull() ? 1 : read;
        any |= !rows.wasNull();
        long product = products.getOrDefault(id, 1L);
        products.put(
            id, read != 0 && product > Long.MAX_VALUE / read ? Long.MAX_VALUE : product * read);
      }

      // The rows of the outermost select, or of the selects of the outermost UNION, summed.
      long outermost() {
        var first = products.keySet().iterator().next();
        // A UNION RESULT that names the first select names the outermost union's selects.
        boolean outermost = inner.contains(String.valueOf(first));
        long sum = 0;
        for (var entry : products.entrySet()) {
          var id = entry.getKey();
          if (id.equals(first)
              || unions.contains(id) && (outermost || !inner.contains(String.valueOf(id)))) {
            sum = sum > Long.MAX_VALUE - entry.getValue() ? Long.MAX_VALUE : sum + entry.getValue();
          }
        }
        return sum;
      }
    }
  }

  static final class Connect extends MySqlFamily.Connect {

    // Without deferCertificateValidation=false, verify-full accepts a certificate the trust
    // store does not, against a fingerprint bound to the password and without the host name.
    private static final String SSL_MODE = "sslMode";
    private static final Map<Tls, Map<String, String>> TLS = Map.of(
        Tls.OFF, Map.of(SSL_MODE, "disable"),
        Tls.ENCRYPTED, Map.of(SSL_MODE, "trust"),
        Tls.VERIFIED, Map.of(SSL_MODE, "verify-full", "deferCertificateValidation", "false"));

    @Override
    public String scheme() {
      return "mariadb";
    }

    // These start the session over with the server's defaults.
    @Override
    public Set<String> readWrite() {
      var words = new HashSet<>(super.readWrite());
      words.addAll(Set.of("session authorization", "reset connection"));
      return Set.copyOf(words);
    }

    @Override
    public Map<String, String> tls(Tls tls) {
      return TLS.get(tls);
    }

    // MariaDB Connector/J does not decode the path of the URL and MySQL Connector/J trims it, so
    // the name goes as a property, which both pass on as it is.
    @Override
    public Optional<String> databaseProperty() {
      return Optional.of("database");
    }

    // Also the time limit for each read while logging in.
    @Override
    public Map<String, String> timeouts(Duration timeout) {
      return Map.of("connectTimeout", String.valueOf(Math.max(1, timeout.toMillis())));
    }

    @Override
    String noTls() {
      return "ssl not enabled in the server";
    }
  }

  // MariaDB Connector/J.
  static final class Session extends MySqlFamily.Session {

    @Override
    public int status(Connection connection) {
      try {
        return (int) call(call(connection, "getContext"), "getServerStatus");
      } catch (ReflectiveOperationException | RuntimeException _) {
        return -1;
      }
    }

    @Override
    public void followAutoCommit(Connection connection, int status) {
      // MariaDB Connector/J reads auto-commit from the status itself.
    }

    @Override
    public void keepWriteTimeout(Connection connection) {
      // MariaDB Connector/J leaves the session's time limits as they are while it streams rows.
    }

    // MariaDB Connector/J cancels as long as it reads.
    @Override
    public void cancel(Connection connection, Statement statement) throws SQLException {
      statement.cancel();
    }

    @Override
    public boolean streamedRowsClosedLast() {
      return false;
    }
  }
}
