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
import dev.hoori.db.core.data.MySqlRows;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** MySQL, with MySQL Connector/J. */
public final class MySql extends MySqlFamily {

  // Before INSTANCE, whose syntax holds them.
  // The words marked (R) in "Keywords and Reserved Words" of the MySQL 8.0, 8.4 and 9.7 manuals:
  // a word reserved in any of them.
  private static final Set<String> RESERVED = Words.words("""
      ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB
      BOTH BY CALL CASCADE CASE CHANGE CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONSTRAINT
      CONTINUE CONVERT CREATE CROSS CUBE CUME_DIST CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP
      CURRENT_USER CURSOR DATABASE DATABASES DAY_HOUR DAY_MICROSECOND DAY_MINUTE DAY_SECOND DEC
      DECIMAL DECLARE DEFAULT DELAYED DELETE DENSE_RANK DESC DESCRIBE DETERMINISTIC DISTINCT
      DISTINCTROW DIV DOUBLE DROP DUAL EACH ELSE ELSEIF EMPTY ENCLOSED ESCAPED EXCEPT EXISTS
      EXIT EXPLAIN EXTERNAL FALSE FETCH FIRST_VALUE FLOAT FLOAT4 FLOAT8 FOR FORCE FOREIGN FROM
      FULLTEXT FUNCTION GENERATED GET GRANT GROUP GROUPING GROUPS HAVING HIGH_PRIORITY
      HOUR_MICROSECOND HOUR_MINUTE HOUR_SECOND IF IGNORE IN INDEX INFILE INNER INOUT
      INSENSITIVE INSERT INT INT1 INT2 INT3 INT4 INT8 INTEGER INTERSECT INTERVAL INTO
      IO_AFTER_GTIDS IO_BEFORE_GTIDS IS ITERATE JOIN JSON_TABLE KEY KEYS KILL LAG LAST_VALUE
      LATERAL LEAD LEADING LEAVE LEFT LIBRARY LIKE LIMIT LINEAR LINES LOAD LOCALTIME
      LOCALTIMESTAMP LOCK LONG LONGBLOB LONGTEXT LOOP LOW_PRIORITY MASTER_BIND
      MASTER_SSL_VERIFY_SERVER_CERT MATCH MAXVALUE MEDIUMBLOB MEDIUMINT MEDIUMTEXT MIDDLEINT
      MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT NO_WRITE_TO_BINLOG NTH_VALUE
      NTILE NULL NUMERIC OF ON OPTIMIZE OPTIMIZER_COSTS OPTION OPTIONALLY OR ORDER OUT OUTER
      OUTFILE OVER PARTITION PERCENT_RANK PRECISION PRIMARY PROCEDURE PURGE QUALIFY RANGE RANK
      READ READS READ_WRITE REAL RECURSIVE REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE
      REQUIRE RESIGNAL RESTRICT RETURN REVOKE RIGHT RLIKE ROW ROWS ROW_NUMBER SCHEMA SCHEMAS
      SECOND_MICROSECOND SELECT SENSITIVE SEPARATOR SET SHOW SIGNAL SMALLINT SPATIAL SPECIFIC
      SQL SQLEXCEPTION SQLSTATE SQLWARNING SQL_BIG_RESULT SQL_CALC_FOUND_ROWS SQL_SMALL_RESULT
      SSL STARTING STORED STRAIGHT_JOIN SYSTEM TABLE TABLESAMPLE TERMINATED THEN TINYBLOB
      TINYINT TINYTEXT TO TRAILING TRIGGER TRUE UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE
      USE USING UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VARBINARY VARCHAR VARCHARACTER VARYING
      VIRTUAL WHEN WHERE WHILE WINDOW WITH WRITE XOR YEAR_MONTH ZEROFILL
      """);

  public static final MySql INSTANCE = new MySql();

  private final Map<Capability, Since> capabilities = Map.of(
      EXPLAIN, always(PLAN),
      EXPLAIN_JSON, since(5, 6, 5, PLAN_JSON),
      EXPLAIN_ANALYZE, since(8, 0, 18, "EXPLAIN ANALYZE "));
  private final Driver driver =
      new Driver("com.mysql", "mysql-connector-j", "com.mysql.cj.jdbc.Driver");
  private final ConnectDialect connecting = new Connect();
  private final SyntaxDialect syntax = new MySqlFamily.Syntax(
      RESERVED, Words.union(RESERVED, Words.TYPED, Syntax.TYPED), List.of("/*!"));
  private final PlanDialect planning = new Plan();
  private final SessionDialect session = new Session();
  private final RowsDialect rows = new MySqlRows();

  private MySql() {}

  @Override
  public String displayName() {
    return "MySQL";
  }

  @Override
  public String storedName() {
    return "mysql";
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
    return "MySQL Connector/J";
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

  static final class Plan extends MySqlFamily.Plan {

    // The tree, from MySQL 8.0.18, estimates what each node gives; the table only the rows read.
    @Override
    public String explain(String sql) {
      return "EXPLAIN FORMAT=TREE " + sql;
    }

    @Override
    public OptionalLong rows(ResultSet plan, String sql, Provider provider) throws SQLException {
      return plan.next() ? Rows.planned(plan.getString(1)) : OptionalLong.empty();
    }
  }

  static final class Connect extends MySqlFamily.Connect {

    private static final String SSL_MODE = "sslMode";
    private static final Map<Tls, Map<String, String>> TLS = Map.of(
        // Without TLS, a first login with caching_sha2_password fails unless the client may ask
        // for the server's public key. Whoever can alter the traffic can hand it a key of their
        // own and read the password; OFF already accepts an unprotected link, and does not work
        // at all against a current MySQL without it.
        Tls.OFF, Map.of(SSL_MODE, "DISABLED", "allowPublicKeyRetrieval", "true"),
        Tls.ENCRYPTED, Map.of(SSL_MODE, "REQUIRED"),
        Tls.VERIFIED, Map.of(SSL_MODE, "VERIFY_IDENTITY"));

    @Override
    public String scheme() {
      return "mysql";
    }

    @Override
    public Map<String, String> tls(Tls tls) {
      return TLS.get(tls);
    }

    // MariaDB Connector/J does not decode the path of the URL and MySQL Connector/J trims it, so
    // the name goes as a property, which both pass on as it is.
    @Override
    public Optional<String> databaseProperty() {
      return Optional.of("dbname");
    }

    // MySQL Connector/J decodes the host, and fails on the "%" of a zone that is not "%25".
    @Override
    public String ipv6(String address) {
      return address.replace("%", "%25");
    }

    // Else Connector/J applies JDBC escapes to a prepared statement, also inside a quoted name;
    // and on a read-only session it refuses a write by its own reading of the text, in place of the
    // server's error.
    @Override
    public Map<String, String> properties() {
      return Map.of(
          "processEscapeCodesForPrepStmts", "false", "readOnlyPropagatesToServer", "false");
    }

    @Override
    public Map<String, String> timeouts(Duration timeout) {
      var millis = String.valueOf(Math.max(1, timeout.toMillis()));
      return Map.of("connectTimeout", millis, "socketTimeout", millis);
    }

    @Override
    String noTls() {
      return "SSL Connection required, but not provided by server";
    }
  }

  // MySQL Connector/J.
  static final class Session extends MySqlFamily.Session {

    @Override
    public int status(Connection connection) {
      try {
        return (int)
            call(call(call(connection, "getSession"), "getServerSession"), "getStatusFlags");
      } catch (ReflectiveOperationException | RuntimeException _) {
        return -1;
      }
    }

    /**
     * Has MySQL Connector/J's auto-commit say what the server's status says, after a statement such
     * as {@code SET autocommit=0} changed it on the server behind the driver's back; it then
     * refuses a commit. Setting it to what the server has already commits nothing.
     */
    @Override
    public void followAutoCommit(Connection connection, int status) throws SQLException {
      if (status < 0) {
        return;
      }
      var server = (status & SERVER_STATUS_AUTOCOMMIT) != 0;
      if (connection.getAutoCommit() != server) {
        Journal.call(Purpose.TRANSACTION, Journal.autoCommit(server), () -> {
          connection.setAutoCommit(server);
          return null;
        });
      }
    }

    /**
     * Keeps MySQL Connector/J from raising the session's {@code net_write_timeout} for streamed
     * rows: it sets it back to what it read when it connected, not to what the session had, and
     * not at all for rows it does not close.
     */
    @Override
    public void keepWriteTimeout(Connection connection) {
      try {
        var properties = call(connection, "getPropertySet");
        var timeout = properties
            .getClass()
            .getMethod("getIntegerProperty", String.class)
            .invoke(properties, "netTimeoutForStreamingResults");
        timeout.getClass().getMethod("setValue", Object.class).invoke(timeout, 0);
      } catch (ReflectiveOperationException | RuntimeException _) {
        // The driver changes it, and sets it back as it closes the rows.
      }
    }

    // MySQL Connector/J's Statement.cancel() does nothing once the statement has returned its first
    // rows: it is told that the statement still runs.
    @Override
    public void cancel(Connection connection, Statement statement) throws SQLException {
      try {
        var executing = call(call(statement, "getQuery"), "getStatementExecuting");
        ((AtomicBoolean) executing).set(true);
      } catch (ReflectiveOperationException | RuntimeException _) {
        // Statement.cancel() is all there is.
      }
      statement.cancel();
    }

    // MySQL Connector/J streams only with this value, a row at a time.
    @Override
    public int fetchSize(int count) {
      return Integer.MIN_VALUE;
    }

    // MySQL Connector/J may close streamed rows with a statement of its own, which it refuses while
    // a
    // procedure has more results; they are closed after the last.
    @Override
    public boolean streamedRowsClosedLast() {
      return true;
    }
  }
}
