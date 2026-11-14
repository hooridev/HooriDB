package dev.hoori.db.core.execution;

import dev.hoori.db.core.query.Query;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Why a statement failed, as the server said it. Where the server does not give a part, a number
 * is 0 and a text is empty.
 *
 * @param message the server's own words, without the driver's additions
 * @param state the SQLSTATE
 * @param code the server's error code: MariaDB's and MySQL's, such as 1064 for a syntax error; 0 on
 *     PostgreSQL, whose code is the SQLSTATE
 * @param position on PostgreSQL, where in the statement the error is, in characters as code points
 *     from 1; {@code text.offsetByCodePoints(0, position - 1)} is its offset in the statement's
 *     text
 * @param internalQuery on PostgreSQL, the query that a function or procedure ran and that failed,
 *     such as one in PL/pgSQL, when the error is in that query rather than the statement
 * @param internalPosition where in that internal query the error is, as {@code position} counts
 * @param line on MariaDB and MySQL, the line of the statement, from 1, that a syntax error is on,
 *     from their English message
 * @param near on MariaDB and MySQL, the text of the statement from where a syntax error is, cut
 *     short by the server, empty at the end of the statement; the caller finds it on that line
 * @param detail PostgreSQL's detail, such as the key that is already there
 * @param hint PostgreSQL's hint of what to do
 * @param context PostgreSQL's context, such as the line of a function that failed
 */
public record Failure(
    Kind kind,
    String message,
    String state,
    int code,
    int position,
    String internalQuery,
    int internalPosition,
    int line,
    String near,
    String detail,
    String hint,
    String context)
    implements Result {

  /** What sort of failure it is. */
  public enum Kind {
    /** The statement is wrong: its syntax, or a table, column, function or type it names. */
    INVALID,
    /** A row broke a constraint: a unique or primary key, a foreign key, not null, a check. */
    CONSTRAINT,
    /** The server refused the user what the statement asks for. */
    ACCESS_DENIED,
    /** The connection is closed or broken; going on needs a new one. */
    CONNECTION_LOST,
    /** The caller cancelled the statement, or another session did on MariaDB and MySQL. */
    CANCELLED,
    /**
     * A time limit of the server ended the statement: PostgreSQL's {@code statement_timeout} and
     * {@code lock_timeout}, MariaDB's {@code max_statement_time}, MySQL's {@code
     * max_execution_time} and their lock wait timeout. PostgreSQL gives a cancel from another
     * session the same code as its statement timeout, so it is one of these too.
     */
    TIMEOUT,
    /** Anything else, such as a division by zero or a value out of range; the message says what. */
    OTHER
  }

  // MySQL's no right to run a routine, beyond what Query knows.
  private static final int MYSQL_ROUTINE_DENIED = 1370;
  // MySQL's check constraint, whose state is the general HY000.
  private static final int MYSQL_CHECK = 3819;
  // MariaDB's max_statement_time, MySQL's max_execution_time and their lock wait timeout.
  private static final Set<Integer> MYSQL_TIMEOUT = Set.of(1969, 3024, 1205);
  // PostgreSQL's lock_not_available, of lock_timeout and NOWAIT.
  private static final String PG_LOCK_NOT_AVAILABLE = "55P03";
  // MariaDB's and MySQL's syntax error, whose English message says where; a line beyond an int is
  // none they write.
  private static final int MYSQL_SYNTAX = 1064;
  private static final Pattern MYSQL_NEAR =
      Pattern.compile(".*? to use near '(.*)' at line (\\d{1,9})", Pattern.DOTALL);
  private static final Pattern MARIADB_CONNECTION = Pattern.compile("^\\(conn=\\d+\\) ");

  static Failure cancelled() {
    return new Failure(Kind.CANCELLED, "", "", 0, 0, "", 0, 0, "", "", "", "");
  }

  static Failure connectionLost() {
    return new Failure(Kind.CONNECTION_LOST, "", "", 0, 0, "", 0, 0, "", "", "", "");
  }

  /** The failure that the driver's exception means, from a statement that was not cancelled. */
  static Failure of(Connection connection, Throwable thrown) {
    var message = thrown.getMessage() == null ? thrown.getClass().getName() : thrown.getMessage();
    if (!(thrown instanceof SQLException e)) {
      return new Failure(Kind.OTHER, message, "", 0, 0, "", 0, 0, "", "", "", "");
    }
    var state = e.getSQLState() == null ? "" : e.getSQLState();
    var code = e.getErrorCode();
    var kind = transport(e)
        ? Kind.CONNECTION_LOST
        : switch (Query.failure(connection, e).failure()) {
          case CONNECTION_LOST, ABORTED -> Kind.CONNECTION_LOST;
          case ACCESS_DENIED -> Kind.ACCESS_DENIED;
          // Query.failure never says that columns changed, which only a read of a table finds.
          case NOT_FOUND, COLUMNS_CHANGED -> Kind.INVALID;
          // Not by the caller: PostgreSQL's statement timeout, or MariaDB's, which shares the state
          // of
          // an interrupted statement.
          case CANCELLED ->
            state.equals("57014") || MYSQL_TIMEOUT.contains(code) ? Kind.TIMEOUT : Kind.CANCELLED;
          case OTHER -> {
            if (state.startsWith("23") || code == MYSQL_CHECK) {
              yield Kind.CONSTRAINT;
            } else if (code == MYSQL_ROUTINE_DENIED) {
              yield Kind.ACCESS_DENIED;
            } else if (state.startsWith("42") || state.startsWith("3D") || state.startsWith("3F")) {
              yield Kind.INVALID;
            } else if (state.equals(PG_LOCK_NOT_AVAILABLE) || MYSQL_TIMEOUT.contains(code)) {
              yield Kind.TIMEOUT;
            }
            yield Kind.OTHER;
          }
        };
    var server = Drivers.serverError(e);
    if (server != null) {
      return new Failure(
          kind,
          server.message(),
          state,
          code,
          server.position(),
          server.internalQuery(),
          server.internalPosition(),
          0,
          "",
          server.detail(),
          server.hint(),
          server.context());
    }
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

  /** A range of a statement's text, in {@code char}s from {@code start} up to {@code end}. */
  public record Place(int start, int end) {}

  /**
   * Where in the statement, as it was sent, the server says the failure is: the word or quoted
   * name there, or the last word for the end of the statement. PostgreSQL names the character;
   * MariaDB and MySQL a line and the text from there on, which is looked for on or after that
   * line, or the whole line where it is not found; text that begins with a space follows the
   * token that failed. Empty where the server names no place, or a
   * place the statement does not have.
   */
  public Optional<Place> place(String sql) {
    if (position > 0) {
      int at = position - 1 < sql.codePointCount(0, sql.length())
          ? sql.offsetByCodePoints(0, position - 1)
          : sql.length();
      return Optional.of(token(sql, at));
    }
    if (line <= 0) {
      return Optional.empty();
    }
    int from = 0;
    for (int n = 1; n < line; n++) {
      int end = sql.indexOf('\n', from);
      if (end < 0) {
        return Optional.empty();
      }
      from = end + 1;
    }
    if (near.isEmpty()) {
      return Optional.of(token(sql, sql.length()));
    }
    int found = sql.indexOf(near, from);
    if (found > 0 && Character.isWhitespace(near.charAt(0))) {
      // MariaDB's lexer may have read the token it failed on already: the text is after it.
      int start = found - 1;
      while (start > 0 && word(sql.charAt(start)) && word(sql.charAt(start - 1))) {
        start--;
      }
      return Optional.of(new Place(start, found));
    }
    if (found >= 0) {
      return Optional.of(token(sql, found));
    }
    int to = sql.indexOf('\n', from);
    to = to < 0 ? sql.length() : to;
    return to > from ? Optional.of(new Place(from, to)) : Optional.empty();
  }

  // The word, the quoted text or the character at the offset; at the end, the last word.
  private static Place token(String sql, int at) {
    int n = sql.length();
    if (at >= n) {
      int end = n;
      while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
        end--;
      }
      int start = end;
      while (start > 0 && word(sql.charAt(start - 1))) {
        start--;
      }
      return start < end ? new Place(start, end) : new Place(Math.max(0, end - 1), end);
    }
    char c = sql.charAt(at);
    int end = at + 1;
    if (word(c)) {
      while (end < n && word(sql.charAt(end))) {
        end++;
      }
    } else if (c == '"' || c == '`' || c == '\'') {
      int close = sql.indexOf(c, at + 1);
      int line = sql.indexOf('\n', at + 1);
      end = close >= 0 && (line < 0 || close < line) ? close + 1 : line < 0 ? n : line;
    }
    return new Place(at, end);
  }

  private static boolean word(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  // A driver that lost its stream may say so only in a cause, with a state of its own and the
  // connection not yet closed, as MySQL Connector/J does with an EOFException.
  private static boolean transport(Throwable thrown) {
    for (var cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof IOException) {
        return true;
      }
    }
    return false;
  }
}
