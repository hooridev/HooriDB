package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.ErrorDialect.Condition;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.sql.Statements;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

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

  static Failure cancelled() {
    return of(Kind.CANCELLED, "");
  }

  static Failure connectionLost() {
    return of(Kind.CONNECTION_LOST, "");
  }

  // A failure of HooriDB's own words, without any of the server's parts.
  static Failure of(Kind kind, String message) {
    return new Failure(kind, message, "", 0, 0, "", 0, 0, "", "", "", "");
  }

  /** The failure that the driver's exception means, from a statement that was not cancelled. */
  static Failure of(Connection connection, Provider provider, Throwable thrown) {
    var message = thrown.getMessage() == null ? thrown.getClass().getName() : thrown.getMessage();
    if (!(thrown instanceof SQLException e)) {
      return of(Kind.OTHER, message);
    }
    var state = e.getSQLState() == null ? "" : e.getSQLState();
    var code = e.getErrorCode();
    var kind = transport(e) ? Kind.CONNECTION_LOST : kind(connection, provider, e, state, code);
    return provider.dialect().errors().failure(kind, e, message, state, code);
  }

  private static Kind kind(
      Connection connection, Provider provider, SQLException e, String state, int code) {
    return switch (Query.failure(connection, provider, e).failure()) {
      case CONNECTION_LOST, ABORTED -> Kind.CONNECTION_LOST;
      case ACCESS_DENIED -> Kind.ACCESS_DENIED;
      // Query.failure never says that columns changed or a clause was refused, which only a read
      // of a table finds.
      case NOT_FOUND, COLUMNS_CHANGED, CLAUSE_REFUSED -> Kind.INVALID;
      // Not by the caller: PostgreSQL's statement timeout, or MariaDB's, which shares the state of
      // an interrupted statement.
      case CANCELLED -> Condition.TIMEOUT.in(provider, state, code) ? Kind.TIMEOUT : Kind.CANCELLED;
      case OTHER -> other(provider, state, code);
    };
  }

  // What a failure that is none of Query's kinds is, by its SQLSTATE and the provider's codes.
  private static Kind other(Provider provider, String state, int code) {
    if (state.startsWith("23") || Condition.CONSTRAINT.in(provider, state, code)) {
      return Kind.CONSTRAINT;
    } else if (Condition.ROUTINE_DENIED.in(provider, state, code)) {
      return Kind.ACCESS_DENIED;
    } else if (state.startsWith("42") || state.startsWith("3D") || state.startsWith("3F")) {
      return Kind.INVALID;
    } else if (Condition.TIMEOUT.in(provider, state, code)) {
      return Kind.TIMEOUT;
    }
    return Kind.OTHER;
  }

  /** A range of a statement's text, in {@code char}s from {@code start} up to {@code end}. */
  public record Place(int start, int end) {}

  /**
   * Where in the statement, as it was sent, the server says the failure is: the word, string or
   * quoted name there, or the last one for the end of the statement. PostgreSQL names the
   * character. MariaDB and MySQL name a line and the statement's text from the failing token on,
   * cut short: the place is its last occurrence on or after that line outside strings and
   * comments, or the whole line where there is none; text that begins with a space follows the
   * token that failed. Empty where the server names no place, or a place the statement does not
   * have. A range never splits a character beyond the BMP.
   *
   * @param provider the provider of the server that said so, whose lexical rules the statement is
   *     read by
   */
  public Optional<Place> place(String sql, Provider provider) {
    if (position > 0) {
      int at = position - 1 < sql.codePointCount(0, sql.length())
          ? sql.offsetByCodePoints(0, position - 1)
          : sql.length();
      return Optional.of(token(sql, at, Statements.pieces(sql, provider)));
    }
    int from = line <= 0 ? -1 : lineStart(sql, line);
    if (from < 0) {
      return Optional.empty();
    }
    var pieces = Statements.pieces(sql, provider);
    if (near.isEmpty()) {
      return Optional.of(token(sql, sql.length(), pieces));
    }
    int found = lastOccurrence(sql, near, from, pieces);
    if (found > 0 && Character.isWhitespace(near.charAt(0))) {
      // MariaDB's lexer may have read the token it failed on already: the text is after it.
      return Optional.of(before(sql, found, pieces));
    }
    if (found >= 0) {
      return Optional.of(token(sql, found, pieces));
    }
    int to = sql.indexOf('\n', from);
    to = to < 0 ? sql.length() : to;
    return to > from ? Optional.of(new Place(from, to)) : Optional.empty();
  }

  // Where the line, from 1, begins; -1 where the text has fewer lines.
  private static int lineStart(String sql, int line) {
    int from = 0;
    for (int n = 1; n < line; n++) {
      int end = sql.indexOf('\n', from);
      if (end < 0) {
        return -1;
      }
      from = end + 1;
    }
    return from;
  }

  // Where the text last occurs from the offset on outside strings and comments; -1 where it does
  // not.
  private static int lastOccurrence(
      String sql, String text, int from, List<Statements.Piece> pieces) {
    int found = -1;
    for (int at = sql.indexOf(text, from); at >= 0; at = sql.indexOf(text, at + 1)) {
      if (!quoted(pieces, at)) {
        found = at;
      }
    }
    return found;
  }

  // Whether the offset is inside a string or a comment, past its first character.
  private static boolean quoted(List<Statements.Piece> pieces, int at) {
    for (var piece : pieces) {
      if (piece.kind() != Statements.Piece.Kind.CODE
          && piece.kind() != Statements.Piece.Kind.QUOTED
          && piece.start() < at
          && at < piece.end()) {
        return true;
      }
    }
    return false;
  }

  // The word, the string or quoted name, or the character at the offset; at the end, the last.
  private static Place token(String sql, int at, List<Statements.Piece> pieces) {
    int n = sql.length();
    if (at >= n) {
      return lastToken(sql, pieces);
    }
    for (var piece : pieces) {
      if (piece.start() == at && piece.kind() != Statements.Piece.Kind.CODE) {
        // An unclosed one runs to the end of its line.
        int line = sql.indexOf('\n', at);
        return new Place(at, piece.open() && line >= 0 ? Math.min(line, piece.end()) : piece.end());
      }
    }
    int c = sql.codePointAt(at);
    int end = at + Character.charCount(c);
    if (word(c)) {
      while (end < n && word(sql.codePointAt(end))) {
        end += Character.charCount(sql.codePointAt(end));
      }
    }
    return new Place(at, end);
  }

  // The last word, string or quoted name, or character of the text.
  private static Place lastToken(String sql, List<Statements.Piece> pieces) {
    int end = sql.length();
    while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
      end--;
    }
    return end == 0 ? new Place(0, 0) : before(sql, end, pieces);
  }

  // The word, the string or quoted name, or the character that ends at the offset.
  private static Place before(String sql, int end, List<Statements.Piece> pieces) {
    for (var piece : pieces) {
      if (piece.end() == end && piece.kind() != Statements.Piece.Kind.CODE) {
        return new Place(piece.start(), end);
      }
    }
    int c = sql.codePointBefore(end);
    int start = end - Character.charCount(c);
    if (word(c)) {
      while (start > 0 && word(sql.codePointBefore(start))) {
        start -= Character.charCount(sql.codePointBefore(start));
      }
    }
    return new Place(start, end);
  }

  private static boolean word(int c) {
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
