package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.monitor.ServerSession;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** What MariaDB and MySQL do alike; their drivers differ. */
abstract sealed class MySqlFamily implements Dialect permits MariaDb, MySql {

  // What MariaDB and MySQL write alike.
  static final String PLAN = "EXPLAIN ";
  static final String PLAN_JSON = "EXPLAIN FORMAT=JSON ";

  private final CatalogDialect catalog;
  private final ErrorDialect errors = new Errors();

  MySqlFamily(boolean flagsHiddenIndexColumns, boolean functionalIndexExpressions) {
    catalog = new Catalog(flagsHiddenIndexColumns, functionalIndexExpressions);
  }

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

  static final class Errors implements ErrorDialect {

    // MariaDB's and MySQL's syntax error, whose English message ends "... to use near '<text>' at
    // line <n>"; a line beyond an int is none they write.
    private static final int MYSQL_SYNTAX = 1064;
    private static final String USE_NEAR = " to use near '";
    private static final Pattern AT_LINE = Pattern.compile("' at line (\\d{1,9})\\z");
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
        case ACCESS_DENIED, ROUTINE_DENIED, TIME_LIMIT, TIMEOUT, CONSTRAINT, NO_SUCH_SESSION ->
          Set.of();
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
        // MariaDB's and MySQL's unknown thread id, of a KILL.
        case NO_SUCH_SESSION -> Set.of(1094);
        case COLUMN_GONE, CANCELLED -> Set.of();
      };
    }

    @Override
    public Failure failure(
        Failure.Kind kind, SQLException e, String message, String state, int code) {
      message = MARIADB_CONNECTION.matcher(message).replaceFirst("");
      var near = code == MYSQL_SYNTAX ? Near.in(message) : Near.NONE;
      return new Failure(
          kind, message, state, code, 0, "", 0, near.line(), near.text(), "", "", "");
    }

    // Where a syntax error is, as its message says: the text from there, cut short, and the line.
    private record Near(String text, int line) {

      static final Near NONE = new Near("", 0);

      static Near in(String message) {
        var from = message.indexOf(USE_NEAR);
        var at = AT_LINE.matcher(message);
        if (from < 0 || !at.find() || at.start() < from + USE_NEAR.length()) {
          return NONE;
        }
        return new Near(
            message.substring(from + USE_NEAR.length(), at.start()), Integer.parseInt(at.group(1)));
      }
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
    public String readOnly() {
      return "SET SESSION TRANSACTION READ ONLY";
    }

    // tx_read_only is the older name, which MariaDB keeps beside transaction_read_only.
    @Override
    public Set<String> readWrite() {
      return Set.of("transaction_read_only", "tx_read_only", "read write");
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

    // Whether any index of the referencing table that holds this key column also holds a column
    // the user cannot see (a functional index part has COLUMN_NAME NULL and does not count as
    // hidden). InnoDB backs every foreign key with an index holding all its columns, so every
    // visible row of a partial key has this flag set.
    private static final String HIDDEN_INDEX_COLUMN = "EXISTS ("
        + "SELECT 1 FROM information_schema.STATISTICS s"
        + " JOIN information_schema.STATISTICS h"
        + " ON h.TABLE_SCHEMA = CAST(s.TABLE_SCHEMA AS BINARY)"
        + " AND h.TABLE_NAME = CAST(s.TABLE_NAME AS BINARY)"
        + " AND h.INDEX_NAME = CAST(s.INDEX_NAME AS BINARY)"
        + " WHERE s.TABLE_SCHEMA = CAST(f.TABLE_SCHEMA AS BINARY)"
        + " AND s.TABLE_NAME = CAST(f.TABLE_NAME AS BINARY)"
        + " AND s.COLUMN_NAME = CAST(f.COLUMN_NAME AS BINARY)"
        + " AND h.COLUMN_NAME IS NOT NULL"
        + " AND NOT EXISTS ("
        + "SELECT 1 FROM information_schema.COLUMNS c"
        + " WHERE c.TABLE_SCHEMA = CAST(h.TABLE_SCHEMA AS BINARY)"
        + " AND c.TABLE_NAME = CAST(h.TABLE_NAME AS BINARY)"
        + " AND c.COLUMN_NAME = CAST(h.COLUMN_NAME AS BINARY)))";

    private final boolean flagHiddenIndexColumns;
    private final boolean functionalIndexExpressions;

    Catalog(boolean flagHiddenIndexColumns, boolean functionalIndexExpressions) {
      this.flagHiddenIndexColumns = flagHiddenIndexColumns;
      this.functionalIndexExpressions = functionalIndexExpressions;
    }

    @Override
    public String databases() {
      return "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME";
    }

    @Override
    public Database database(ResultSet rows) throws SQLException {
      var name = rows.getString(1);
      return new Database(name, systemDatabase(name));
    }

    @Override
    public boolean systemDatabase(String name) {
      return MYSQL_SYSTEM.contains(name);
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
          // Exact names, as MariaDB's information_schema compares them regardless of case and
          // accents, where two tables or columns may differ only in them.
          + " ON k.TABLE_SCHEMA = CAST(c.TABLE_SCHEMA AS BINARY)"
          + " AND k.TABLE_NAME = CAST(c.TABLE_NAME AS BINARY)"
          + " AND k.CONSTRAINT_NAME = CAST('PRIMARY' AS BINARY)"
          + " AND k.REFERENCED_TABLE_NAME IS NULL" // MySQL lets a foreign key take the name
          + " AND k.COLUMN_NAME = CAST(c.COLUMN_NAME AS BINARY)"
          + " WHERE c.TABLE_SCHEMA = CAST(? AS BINARY) AND c.TABLE_NAME = CAST(? AS BINARY)"
          + " ORDER BY c.ORDINAL_POSITION";
    }

    @Override
    public String tableExists() {
      return "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
    }

    // Two branches so the outgoing branch keeps a filter MariaDB can optimize on TABLE_SCHEMA and
    // TABLE_NAME, while the incoming branch reads across every database and skips self-references
    // already returned by the first branch. Exact binary comparisons and sort prevent names that
    // differ only in case or accents from matching or interleaving.
    @Override
    public String foreignKeys() {
      var select = "SELECT k.CONSTRAINT_NAME, k.TABLE_SCHEMA, k.TABLE_NAME,"
          + " k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME,"
          + " k.COLUMN_NAME, k.REFERENCED_COLUMN_NAME,"
          + " r.UPDATE_RULE, r.DELETE_RULE, k.ORDINAL_POSITION"
          + " FROM information_schema.KEY_COLUMN_USAGE k"
          // MariaDB shows a key's rules only to a user with more than SELECT on its table.
          + " LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS r"
          + " ON r.CONSTRAINT_SCHEMA = CAST(k.CONSTRAINT_SCHEMA AS BINARY)"
          + " AND r.CONSTRAINT_NAME = CAST(k.CONSTRAINT_NAME AS BINARY)"
          + " AND r.TABLE_NAME = CAST(k.TABLE_NAME AS BINARY)";
      return "SELECT CONSTRAINT_NAME, TABLE_SCHEMA, TABLE_NAME,"
          + " REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME,"
          + " COLUMN_NAME, REFERENCED_COLUMN_NAME, UPDATE_RULE, DELETE_RULE, "
          + (flagHiddenIndexColumns ? HIDDEN_INDEX_COLUMN : "FALSE")
          + " FROM ("
          + select
          + " WHERE k.TABLE_SCHEMA = CAST(? AS BINARY)"
          + " AND k.TABLE_NAME = CAST(? AS BINARY)"
          + " AND k.REFERENCED_TABLE_NAME IS NOT NULL"
          + " UNION ALL "
          + select
          + " WHERE k.REFERENCED_TABLE_SCHEMA = CAST(? AS BINARY)"
          + " AND k.REFERENCED_TABLE_NAME = CAST(? AS BINARY)"
          + " AND NOT (k.TABLE_SCHEMA = CAST(k.REFERENCED_TABLE_SCHEMA AS BINARY)"
          + " AND k.TABLE_NAME = CAST(k.REFERENCED_TABLE_NAME AS BINARY))"
          + ") f"
          + " ORDER BY CAST(CONSTRAINT_NAME AS BINARY), CAST(TABLE_SCHEMA AS BINARY),"
          + " CAST(TABLE_NAME AS BINARY), ORDINAL_POSITION";
    }

    // MySQL 8+ stores functional key parts with COLUMN_NAME NULL and an EXPRESSION column, which
    // MariaDB lacks. Exact binary comparisons and sort prevent names that differ only in case or
    // accents from matching or interleaving.
    @Override
    public String indexes() {
      var column = functionalIndexExpressions ? "COALESCE(COLUMN_NAME, EXPRESSION)" : "COLUMN_NAME";
      return "SELECT INDEX_NAME, " + column + ", NON_UNIQUE = 0,"
          + " INDEX_NAME = CAST('PRIMARY' AS BINARY), FALSE"
          + " FROM information_schema.STATISTICS"
          + " WHERE TABLE_SCHEMA = CAST(? AS BINARY) AND TABLE_NAME = CAST(? AS BINARY)"
          + " ORDER BY INDEX_NAME = CAST('PRIMARY' AS BINARY) DESC,"
          + " CAST(INDEX_NAME AS BINARY), SEQ_IN_INDEX";
    }

    @Override
    public boolean restrictIsNoAction() {
      return true;
    }
  }

  // Their lexical rules alike, with each one's key words and kinds of executable comments.
  static final class Syntax implements SyntaxDialect {

    // Typed often on MariaDB and MySQL, each in MySQL's list; MariaDB has sequences as well.
    static final Set<String> TYPED = Words.words("""
        AUTO_INCREMENT CHARSET DATABASES DATE DESCRIBE DUPLICATE ENGINE IGNORE PROCESSLIST STATUS
        UNSIGNED USE VARIABLES VIRTUAL WAIT
        """);

    private final Set<String> reserved;
    private final Set<String> offered;
    private final List<String> executableComments;

    Syntax(Set<String> reserved, Set<String> offered, List<String> executableComments) {
      this.reserved = reserved;
      this.offered = offered;
      this.executableComments = executableComments;
    }

    @Override
    public char identifierQuote() {
      return '`';
    }

    // As MariaDB and MySQL read a plain string by default, without NO_BACKSLASH_ESCAPES.
    @Override
    public boolean backslashEscapes() {
      return true;
    }

    @Override
    public boolean doubleQuotedStrings() {
      return true;
    }

    @Override
    public boolean escapeStrings() {
      return false;
    }

    @Override
    public boolean dollarQuotedStrings() {
      return false;
    }

    @Override
    public boolean dashCommentNeedsSpace() {
      return true;
    }

    @Override
    public boolean hashComments() {
      return true;
    }

    @Override
    public boolean nestedComments() {
      return false;
    }

    @Override
    public List<String> executableComments() {
      return executableComments;
    }

    // As their command-line clients do.
    @Override
    public boolean clientDelimiter() {
      return true;
    }

    @Override
    public boolean userVariables() {
      return true;
    }

    @Override
    public boolean limitBoundsChanges() {
      return true;
    }

    @Override
    public boolean foldsNamesToLowerCase() {
      return false;
    }

    @Override
    public boolean caseSensitiveNames() {
      return false;
    }

    @Override
    public Set<String> reservedWords() {
      return reserved;
    }

    @Override
    public Set<String> offeredWords() {
      return offered;
    }

    /**
     * As in {@code CREATE USER u IDENTIFIED BY …, SET PASSWORD = …} and a replica's {@code CHANGE
     * REPLICATION SOURCE TO SOURCE_PASSWORD = …}, once {@code MASTER_PASSWORD}.
     */
    @Override
    public Set<String> passwordWords() {
      return Set.of("PASSWORD", "IDENTIFIED", "SOURCE_PASSWORD", "MASTER_PASSWORD");
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

    // The server status that their drivers read is current only right after a result ended well:
    // an error carries none, and MySQL Connector/J clears it while it streams rows and once it
    // closes them.

    @Override
    public Transaction transaction(Connection connection, int status) {
      if (status < 0) {
        return Transaction.UNKNOWN;
      }
      return (status & SERVER_STATUS_IN_TRANS) == 0 ? Transaction.NONE : Transaction.OPEN;
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

  /**
   * Their process lists, alike but for how long a thread has been in its state: MariaDB tells it
   * to the millisecond, MySQL in whole seconds.
   */
  static final class Monitor implements MonitorDialect {

    private final String elapsed; // the seconds in its state, as a column of the list
    private final String variables; // the query that tells their sources
    private final String locks;

    Monitor(String elapsed, String variables, String locks) {
      this.elapsed = elapsed;
      this.variables = variables;
      this.locks = locks;
    }

    @Override
    public List<String> variables() {
      return List.of(variables, "SHOW GLOBAL VARIABLES");
    }

    @Override
    public String status() {
      return "SHOW GLOBAL STATUS";
    }

    @Override
    public String locks() {
      return locks;
    }

    // Without the PROCESS privilege the server lists the user's own threads alone.
    @Override
    public String sessions() {
      return "SELECT ID, ID = CONNECTION_ID(), USER, HOST, DB, COMMAND, STATE, INFO, " + elapsed
          + " FROM information_schema.PROCESSLIST ORDER BY ID";
    }

    // An unknown thread fails as NO_SUCH_SESSION. A thread id is not used again while the server
    // runs, so End reaches the session asked about; KILL QUERY cannot check that the thread still
    // runs the statement asked about, as the server has no statement id to give it.
    @Override
    public Stop cancel(ServerSession session) {
      return new Stop("KILL QUERY " + Long.parseLong(session.operation()), List.of());
    }

    @Override
    public Stop end(ServerSession session) {
      return new Stop("KILL " + Long.parseLong(session.id()), List.of());
    }

    // A thread's state that waits says what for; any other says what the command does.
    @Override
    public ServerSession session(ResultSet row) throws SQLException {
      var id = row.getString(1);
      var command = MonitorRows.text(row, 6);
      var stage = MonitorRows.text(row, 7);
      boolean waits = stage.startsWith("Waiting") || stage.equals("Locked");
      boolean runs = command.equals("Query") || command.equals("Execute");
      return new ServerSession(
          id,
          runs ? id : "",
          "",
          row.getBoolean(2),
          true,
          MonitorRows.text(row, 3),
          MonitorRows.text(row, 4),
          MonitorRows.text(row, 5),
          waits || command.equals("Sleep") ? command : MonitorRows.joined(": ", command, stage),
          MonitorRows.text(row, 8),
          MonitorRows.seconds(row, 9),
          waits ? stage : "");
    }
  }
}
