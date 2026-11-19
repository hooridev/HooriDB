package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.connection.Capability.CONNECTION_PER_DATABASE;
import static dev.hoori.db.core.connection.Capability.ERROR_POSITION;
import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.connection.Capability.SCHEMAS;
import static dev.hoori.db.core.connection.Capability.TRANSACTIONAL_DDL;
import static dev.hoori.db.core.dialect.Reflection.call;
import static dev.hoori.db.core.dialect.Since.always;
import static dev.hoori.db.core.dialect.Since.since;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.data.PostgreSqlRows;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.execution.Transaction;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** PostgreSQL, with pgjdbc. */
public final class PostgreSql implements Dialect {

  public static final PostgreSql INSTANCE = new PostgreSql();

  private final Map<Capability, Since> capabilities = Map.of(
      SCHEMAS, always(),
      CONNECTION_PER_DATABASE, always(),
      TRANSACTIONAL_DDL, always(),
      ERROR_POSITION, always(),
      EXPLAIN, always("EXPLAIN "),
      EXPLAIN_JSON, since(9, 0, 0, "EXPLAIN (FORMAT JSON) "),
      EXPLAIN_ANALYZE, since(7, 2, 0, "EXPLAIN ANALYZE "));
  private final Driver driver = new Driver("org.postgresql", "postgresql", "org.postgresql.Driver");
  private final ConnectDialect connecting = new Connect();
  private final CatalogDialect catalog = new Catalog();
  private final PlanDialect plan = new Plan();
  private final SyntaxDialect syntax = new Syntax();
  private final SessionDialect session = new Session();
  private final RowsDialect rows = new PostgreSqlRows();

  private PostgreSql() {}

  @Override
  public String displayName() {
    return "PostgreSQL";
  }

  @Override
  public String storedName() {
    return "postgresql";
  }

  @Override
  public int defaultPort() {
    return 5432;
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
    return "PostgreSQL JDBC";
  }

  @Override
  public ConnectDialect connecting() {
    return connecting;
  }

  @Override
  public CatalogDialect catalog() {
    return catalog;
  }

  @Override
  public PlanDialect plan() {
    return plan;
  }

  @Override
  public SyntaxDialect syntax() {
    return syntax;
  }

  @Override
  public SessionDialect session() {
    return session;
  }

  @Override
  public RowsDialect rows() {
    return rows;
  }

  // pgjdbc.
  static final class Connect implements ConnectDialect {

    private static final String PGJDBC_FACTORY = "org.postgresql.core.v3.ConnectionFactoryImpl";
    private static final String PGJDBC_PLUGINS =
        "org.postgresql.core.v3.AuthenticationPluginManager";

    // DefaultJavaSSLFactory checks the chain against the JVM's trust store, where the driver's
    // own factory reads a root.crt from the user's PostgreSQL folder; verify-full still has the
    // driver check the host name.
    private static final Map<Tls, Map<String, String>> TLS = Map.of(
        Tls.OFF, Map.of("sslmode", "disable"),
        Tls.ENCRYPTED, Map.of("sslmode", "require"),
        Tls.VERIFIED,
            Map.of(
                "sslmode", "verify-full",
                "sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory"));

    @Override
    public String scheme() {
      return "postgresql";
    }

    @Override
    public String ipv6(String address) {
      return address;
    }

    @Override
    public Map<String, String> tls(Tls tls) {
      return TLS.get(tls);
    }

    @Override
    public Map<String, String> properties() {
      return Map.of();
    }

    @Override
    public Optional<String> databaseProperty() {
      return Optional.empty();
    }

    // PostgreSQL's loginTimeout is not one of them: it leaves its own thread behind, waiting on a
    // server that never answers.
    @Override
    public Map<String, String> timeouts(Duration timeout) {
      // In whole seconds, and 0 would mean none.
      var seconds = String.valueOf(Math.max(1, (timeout.toMillis() + 999) / 1000));
      return Map.of("connectTimeout", seconds, "socketTimeout", seconds);
    }

    @Override
    public ConnectionException.Failure refusal(
        SQLException e, String state, String message, boolean tls) {
      return switch (state) {
        case "3D000" -> ConnectionException.Failure.NO_SUCH_DATABASE;
        // pgjdbc's own refusal of many kinds, with translated messages; where it was raised
        // tells them apart: the answer to its request for TLS, or the lack of a password when the
        // server asks for one. An authentication method it does not know is neither.
        case "08004" ->
          raisedIn(e, PGJDBC_FACTORY, "enableSSL")
              ? ConnectionException.Failure.TLS_UNAVAILABLE
              : raisedIn(e, PGJDBC_FACTORY, "lambda$doAuthentication$")
                      || raisedIn(e, PGJDBC_PLUGINS, "lambda$withEncodedPassword$")
                  ? ConnectionException.Failure.LOGIN_REFUSED
                  : ConnectionException.Failure.OTHER;
        default -> ConnectionException.Failure.OTHER;
      };
    }

    // Whether the exception was made in the class, by the method or one whose name starts so.
    private static boolean raisedIn(Throwable t, String type, String method) {
      var trace = t.getStackTrace();
      return trace.length > 0
          && trace[0].getClassName().equals(type)
          && trace[0].getMethodName().startsWith(method);
    }

    // PostgreSQL opens the database named after the user where none is given.
    @Override
    public boolean defaultDatabaseIsUser() {
      return true;
    }
  }

  static final class Catalog implements CatalogDialect {

    // PostgreSQL's operators, functions and types are named with their schema, so that what a user
    // has created under the same names, with a better match or earlier in the search path, is
    // never what runs.
    private static final String EQ = " OPERATOR(pg_catalog.=) ";

    // Tables, partitioned and foreign tables, views and materialized views; not indexes,
    // sequences, composite types or TOAST tables.
    private static final String PG_KINDS = EQ + "ANY ('{r,p,f,v,m}'::pg_catalog.\"char\"[])";

    // Whether a domain in the chain of the column's type, a domain over a domain included, says
    // NOT NULL; only of a table, as a view's outer join can give a domain's column NULL all the
    // same.
    private static final String DOMAIN_NOT_NULL = "WITH RECURSIVE d (base, required) AS ("
        + "SELECT t.typbasetype, t.typnotnull FROM pg_catalog.pg_type t"
        + " WHERE t.oid" + EQ + "a.atttypid"
        + " UNION ALL SELECT t.typbasetype, t.typnotnull FROM pg_catalog.pg_type t"
        + " JOIN d ON t.oid" + EQ + "d.base)"
        + " SELECT FROM d WHERE d.required";

    @Override
    public String databases() {
      return "SELECT datname, datistemplate FROM pg_catalog.pg_database ORDER BY datname";
    }

    @Override
    public Database database(ResultSet rows) throws SQLException {
      return new Database(rows.getString(1), rows.getBoolean(2));
    }

    @Override
    public String schemas() {
      return "SELECT nspname FROM pg_catalog.pg_namespace ORDER BY nspname";
    }

    // Names that start with pg_ are kept for the server: pg_toast, pg_temp_3 and the like.
    @Override
    public boolean systemSchema(String name) {
      return name.startsWith("pg_") || name.equals("information_schema");
    }

    @Override
    public String tables() {
      return "SELECT c.relname, c.relkind FROM pg_catalog.pg_class c"
          + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "c.relnamespace"
          + " WHERE n.nspname" + EQ + "? AND c.relkind" + PG_KINDS + " ORDER BY c.relname";
    }

    @Override
    public String containerExists() {
      return "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname" + EQ + "?";
    }

    @Override
    public String allTables() {
      return "SELECT n.nspname, c.relname, c.relkind FROM pg_catalog.pg_class c"
          + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "c.relnamespace"
          + " WHERE c.relkind" + PG_KINDS
          // The system's schemas, as schemas() tells them: pg_ before the name, or this one.
          + " AND pg_catalog.left(n.nspname, 3) OPERATOR(pg_catalog.<>) 'pg_'"
          + " AND n.nspname OPERATOR(pg_catalog.<>) 'information_schema'"
          + " ORDER BY n.nspname, c.relname";
    }

    @Override
    public String columns() {
      return "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),"
          + " NOT a.attnotnull AND NOT (c.relkind" + EQ
          + "ANY ('{r,p}'::pg_catalog.\"char\"[])"
          + " AND EXISTS (" + DOMAIN_NOT_NULL + ")),"
          // Of the index's attributes only its key, not those it merely includes.
          + " (SELECT k.n FROM pg_catalog.pg_index i,"
          + " pg_catalog.unnest(i.indkey) WITH ORDINALITY k (attnum, n)"
          + " WHERE i.indrelid" + EQ + "c.oid AND i.indisprimary AND k.attnum" + EQ
          + "a.attnum AND k.n OPERATOR(pg_catalog.<=) i.indnkeyatts)"
          + " FROM pg_catalog.pg_class c"
          + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "c.relnamespace"
          + " JOIN pg_catalog.pg_attribute a ON a.attrelid" + EQ + "c.oid"
          + " WHERE n.nspname" + EQ + "? AND c.relname" + EQ + "? AND c.relkind" + PG_KINDS
          + " AND a.attnum OPERATOR(pg_catalog.>) 0 AND NOT a.attisdropped"
          + " ORDER BY a.attnum";
    }

    @Override
    public String tableExists() {
      return "SELECT 1 FROM pg_catalog.pg_class c"
          + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "c.relnamespace"
          + " WHERE n.nspname" + EQ + "? AND c.relname" + EQ + "? AND c.relkind" + PG_KINDS;
    }
  }

  static final class Plan implements PlanDialect {

    @Override
    public String beginReadOnly() {
      return "BEGIN READ ONLY";
    }

    @Override
    public String rollback() {
      return "ROLLBACK";
    }

    // A function outside the system's schemas that the planner evaluates, immutable or stable, in
    // a language that can call other code, as one that calls dblink_exec, unless an extension
    // owns it.
    @Override
    public String callsOut() {
      return "SELECT 1 FROM pg_catalog.pg_proc p"
          + " JOIN pg_catalog.pg_namespace n ON n.oid OPERATOR(pg_catalog.=) p.pronamespace"
          + " JOIN pg_catalog.pg_language l ON l.oid OPERATOR(pg_catalog.=) p.prolang"
          + " WHERE p.provolatile OPERATOR(pg_catalog.<>) 'v'"
          + " AND n.nspname OPERATOR(pg_catalog.<>) 'pg_catalog'"
          + " AND n.nspname OPERATOR(pg_catalog.<>) 'information_schema'"
          + " AND l.lanname OPERATOR(pg_catalog.<>) 'c'"
          + " AND l.lanname OPERATOR(pg_catalog.<>) 'internal'"
          + " AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d"
          + " WHERE d.classid OPERATOR(pg_catalog.=) 'pg_catalog.pg_proc'::pg_catalog.regclass"
          + " AND d.objid OPERATOR(pg_catalog.=) p.oid"
          + " AND d.deptype OPERATOR(pg_catalog.=) 'e') LIMIT 1";
    }

    @Override
    public String explain(String sql) {
      return "EXPLAIN " + sql;
    }

    @Override
    public OptionalLong rows(ResultSet plan, String sql, Provider provider) throws SQLException {
      return plan.next() ? Rows.planned(plan.getString(1)) : OptionalLong.empty();
    }
  }

  static final class Syntax implements SyntaxDialect {

    @Override
    public char identifierQuote() {
      return '"';
    }
  }

  // pgjdbc.
  static final class Session implements SessionDialect {

    // The first words of a PostgreSQL statement that may read its rows in a transaction of its
    // own.
    private static final Set<String> QUERIES = Set.of("SELECT", "WITH", "VALUES", "TABLE");

    @Override
    public int status(Connection connection) {
      return -1;
    }

    /**
     * PostgreSQL's transaction state, which pgjdbc keeps from every message of the server; unknown
     * on a closed connection, whose session the server may have ended.
     */
    @Override
    public Transaction transaction(Connection connection, int status) {
      try {
        if (connection.isClosed()) {
          return Transaction.UNKNOWN;
        }
        return switch (call(connection, "getTransactionState").toString()) {
          case "IDLE" -> Transaction.NONE;
          case "OPEN" -> Transaction.OPEN;
          case "FAILED" -> Transaction.FAILED;
          default -> Transaction.UNKNOWN;
        };
      } catch (SQLException | ReflectiveOperationException | RuntimeException e) {
        return Transaction.UNKNOWN;
      }
    }

    // pgjdbc's, which it keeps itself; not known on a closed connection.
    @Override
    public Boolean autoCommit(Connection connection, int status) {
      try {
        return connection.isClosed() ? null : connection.getAutoCommit();
      } catch (SQLException | RuntimeException e) {
        return null;
      }
    }

    @Override
    public Optional<String> statusQuery() {
      return Optional.empty();
    }

    @Override
    public void followAutoCommit(Connection connection, int status) {}

    @Override
    public void keepWriteTimeout(Connection connection) {}

    // pgjdbc's Statement.cancel() does nothing once the statement has returned its first rows; its
    // cancelQuery() cancels whatever the connection runs.
    @Override
    public void cancel(Connection connection, Statement statement) throws SQLException {
      try {
        call(connection, "cancelQuery");
        return;
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Statement.cancel() is all there is.
      }
      statement.cancel();
    }

    // One row more than asked for, so that the look at the next row needs no second round trip.
    @Override
    public int fetchSize(int count) {
      return count == Integer.MAX_VALUE ? 0 : count + 1;
    }

    // PostgreSQL sends rows in portions only within a transaction, and otherwise sends them all at
    // once.
    // shortcut: pgjdbc before 42.7.4 or so still reads all rows at once after a BEGIN the user
    // ran, as its auto-commit is on; the app recommends current drivers.
    @Override
    public Set<String> queriesInOwnTransaction() {
      return QUERIES;
    }

    // pgjdbc gives what comes while rows are fetched to the result set.
    @Override
    public boolean warningsKeptByDriver() {
      return true;
    }

    // PostgreSQL closes its cursor.
    @Override
    public boolean pendingRowsHoldConnection() {
      return false;
    }

    @Override
    public boolean streamedRowsClosedLast() {
      return false;
    }
  }
}
