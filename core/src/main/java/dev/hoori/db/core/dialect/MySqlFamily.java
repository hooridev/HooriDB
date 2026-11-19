package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.dialect.ErrorDialect.Condition;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Transaction;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** What MariaDB and MySQL do alike; their drivers differ. */
abstract sealed class MySqlFamily implements Dialect permits MariaDb, MySql {

  // What MariaDB and MySQL write alike.
  static final String PLAN = "EXPLAIN ";
  static final String PLAN_JSON = "EXPLAIN FORMAT=JSON ";

  private final CatalogDialect catalog = new Catalog();
  private final ErrorDialect errors = new Errors();
  private final SyntaxDialect syntax = new Syntax();

  @Override
  public int defaultPort() {
    return 3306;
  }

  @Override
  public CatalogDialect catalog() {
    return catalog;
  }

  @Override
  public ErrorDialect errors() {
    return errors;
  }

  @Override
  public SyntaxDialect syntax() {
    return syntax;
  }

  static final class Errors implements ErrorDialect {

    // MariaDB's and MySQL's syntax error, whose English message says where; a line beyond an int
    // is none they write.
    private static final int MYSQL_SYNTAX = 1064;
    private static final Pattern MYSQL_NEAR =
        Pattern.compile(".*? to use near '(.*)' at line (\\d{1,9})", Pattern.DOTALL);
    private static final Pattern MARIADB_CONNECTION = Pattern.compile("^\\(conn=\\d+\\) ");

    @Override
    public Set<String> states(Condition condition) {
      return switch (condition) {
        // MariaDB's and MySQL's unknown table and column.
        case NOT_FOUND -> Set.of("42S02", "42S22");
        // MariaDB's and MySQL's unknown column.
        case COLUMN_GONE -> Set.of("42S22");
        // MariaDB's and MySQL's "Query execution was interrupted".
        case CANCELLED -> Set.of("70100");
        case ACCESS_DENIED, ROUTINE_DENIED, TIME_LIMIT, TIMEOUT, CONSTRAINT -> Set.of();
      };
    }

    @Override
    public Set<Integer> codes(Condition condition) {
      return switch (condition) {
        // MySQL error codes: no access to a database, a table, a column, or without a privilege.
        case ACCESS_DENIED -> Set.of(1044, 1142, 1143, 1227);
        // MySQL's no right to run a routine, beyond what Query knows.
        case ROUTINE_DENIED -> Set.of(1370);
        // MariaDB's and MySQL's unknown database, whose state is the general 42000.
        case NOT_FOUND -> Set.of(1049);
        // MariaDB's max_statement_time, which shares the state of a cancelled statement.
        case TIME_LIMIT -> Set.of(1969);
        // MariaDB's max_statement_time, MySQL's max_execution_time and their lock wait timeout.
        case TIMEOUT -> Set.of(1969, 3024, 1205);
        // MySQL's check constraint, whose state is the general HY000.
        case CONSTRAINT -> Set.of(3819);
        case COLUMN_GONE, CANCELLED -> Set.of();
      };
    }

    @Override
    public Failure failure(
        Failure.Kind kind, SQLException e, String message, String state, int code) {
      message = MARIADB_CONNECTION.matcher(message).replaceFirst("");
      var near = MYSQL_NEAR.matcher(message);
      var syntax = code == MYSQL_SYNTAX && near.matches();
      return new Failure(
          kind,
          message,
          state,
          code,
          0,
          "",
          0,
          syntax ? Integer.parseInt(near.group(2)) : 0,
          syntax ? near.group(1) : "",
          "",
          "",
          "");
    }
  }

  // How their planners are asked alike.
  abstract static sealed class Plan implements PlanDialect permits MariaDb.Plan, MySql.Plan {

    @Override
    public String beginReadOnly() {
      return "START TRANSACTION READ ONLY";
    }

    // MariaDB's and MySQL's ROLLBACK would chain or disconnect as completion_type says.
    @Override
    public String rollback() {
      return "ROLLBACK AND NO CHAIN NO RELEASE";
    }

    // Any stored function outside the system's schemas, which may call a loadable function.
    @Override
    public String callsOut() {
      return "SELECT 1 FROM information_schema.ROUTINES WHERE ROUTINE_TYPE = 'FUNCTION'"
          // As bytes: MariaDB compares names without case and accents, which takes SYS for sys.
          + " AND CAST(ROUTINE_SCHEMA AS BINARY) NOT IN ('sys', 'mysql', 'information_schema',"
          + " 'performance_schema') LIMIT 1";
    }
  }

  // How their drivers connect alike.
  abstract static sealed class Connect implements ConnectDialect
      permits MariaDb.Connect, MySql.Connect {

    /** The English message of the driver that says that the server has no TLS. */
    abstract String noTls();

    @Override
    public String ipv6(String address) {
      return address;
    }

    @Override
    public Map<String, String> properties() {
      return Map.of();
    }

    @Override
    public ConnectionException.Failure refusal(
        SQLException e, String state, String message, boolean tls) {
      return switch (e.getErrorCode()) {
        // Unknown database, and no access to it, which is what a user without global
        // privileges gets for a database that does not exist.
        case 1044, 1049 -> ConnectionException.Failure.NO_SUCH_DATABASE;
        default ->
          // Both drivers give a missing TLS no code of its own; their messages are English only.
          tls && state.startsWith("08") && message.contains(noTls())
              ? ConnectionException.Failure.TLS_UNAVAILABLE
              : ConnectionException.Failure.OTHER;
      };
    }

    @Override
    public boolean defaultDatabaseIsUser() {
      return false;
    }
  }

  static final class Catalog implements CatalogDialect {

    private static final Set<String> MYSQL_SYSTEM =
        Set.of("information_schema", "mysql", "performance_schema", "sys");

    @Override
    public String databases() {
      return "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME";
    }

    @Override
    public Database database(ResultSet rows) throws SQLException {
      var name = rows.getString(1);
      return new Database(name, MYSQL_SYSTEM.contains(name));
    }

    @Override
    public String schemas() {
      throw new UnsupportedOperationException("No schemas");
    }

    @Override
    public boolean systemSchema(String name) {
      throw new UnsupportedOperationException("No schemas");
    }

    // MariaDB's sequences are tables to the server; they come later, with PostgreSQL's.
    @Override
    public String tables() {
      return "SELECT TABLE_NAME, CASE WHEN TABLE_TYPE IN ('VIEW', 'SYSTEM VIEW') THEN 'v' ELSE 'r' END"
          + " FROM information_schema.TABLES"
          + " WHERE TABLE_SCHEMA = ? AND TABLE_TYPE <> 'SEQUENCE' ORDER BY TABLE_NAME";
    }

    @Override
    public String containerExists() {
      return "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?";
    }

    @Override
    public String allTables() {
      throw new UnsupportedOperationException("No schemas");
    }

    // shortcut: a temporary table of the session hides a persistent one of the same name from
    // information_schema, so the columns are the temporary one's; tell them apart if the browsing
    // connection ever creates temporary tables.
    @Override
    public String columns() {
      return "SELECT c.COLUMN_NAME, c.COLUMN_TYPE, c.IS_NULLABLE = 'YES', k.ORDINAL_POSITION"
          + " FROM information_schema.COLUMNS c"
          + " LEFT JOIN information_schema.KEY_COLUMN_USAGE k"
          + " ON k.TABLE_SCHEMA = c.TABLE_SCHEMA AND k.TABLE_NAME = c.TABLE_NAME"
          + " AND k.CONSTRAINT_NAME = 'PRIMARY' AND k.COLUMN_NAME = c.COLUMN_NAME"
          + " WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ? ORDER BY c.ORDINAL_POSITION";
    }

    @Override
    public String tableExists() {
      return "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
    }
  }

  static final class Syntax implements SyntaxDialect {

    @Override
    public char identifierQuote() {
      return '`';
    }
  }

  // What their drivers do alike.
  abstract static sealed class Session implements SessionDialect
      permits MariaDb.Session, MySql.Session {

    // Bits of the server status that MariaDB and MySQL send with every OK and EOF.
    static final int SERVER_STATUS_IN_TRANS = 1;
    static final int SERVER_STATUS_AUTOCOMMIT = 2;

    // MariaDB's and MySQL's statement that does nothing, whose answer carries the server's status.
    private static final Optional<String> NOTHING = Optional.of("DO 0");

    /**
     * The server status that MariaDB's and MySQL's drivers last read, or -1. It is current only
     * right after a result ended well: an error carries none, and MySQL Connector/J clears it while
     * it streams rows and once it closes them.
     */
    @Override
    public abstract int status(Connection connection);

    @Override
    public Transaction transaction(Connection connection, int status) {
      if (status < 0) {
        return Transaction.UNKNOWN;
      }
      return (status & SERVER_STATUS_IN_TRANS) != 0 ? Transaction.OPEN : Transaction.NONE;
    }

    /** Whether auto-commit is on as the status says, or null where it is not known. */
    @Override
    public Boolean autoCommit(Connection connection, int status) {
      return status < 0 ? null : (status & SERVER_STATUS_AUTOCOMMIT) != 0;
    }

    @Override
    public Optional<String> statusQuery() {
      return NOTHING;
    }

    // Their drivers' cancel waits for the server's answer to its KILL QUERY.
    @Override
    public boolean cancelAnswered() {
      return true;
    }

    @Override
    public int fetchSize(int count) {
      return count == Integer.MAX_VALUE ? 0 : count + 1;
    }

    @Override
    public Set<String> queriesInOwnTransaction() {
      return Set.of();
    }

    // MariaDB and MySQL send their warnings only when asked, which they cannot be while rows are on
    // their way.
    @Override
    public boolean warningsKeptByDriver() {
      return false;
    }

    // MariaDB sends the rows while they are read, and waits for the client; MySQL likewise.
    @Override
    public boolean pendingRowsHoldConnection() {
      return true;
    }
  }
}
