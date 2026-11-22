package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Optional;

/**
 * Whether a SQL clause typed by the user, such as a {@code WHERE} or {@code ORDER BY} clause, stays
 * in its place when embedded in a statement: by every way the server may read its text, as {@link
 * EveryRow} and {@link ReadWrite} read it, it holds at most one statement without a semicolon,
 * leaves no string, quoted name or block comment open, has balanced parentheses, and on a read-only
 * connection holds nothing that {@link ReadWrite} refuses.
 */
public final class Clauses {

  private Clauses() {}

  /** Why a clause is refused before anything is sent. */
  public enum Problem {
    /** More than one statement, or a semicolon outside strings, quoted names and comments. */
    MULTIPLE,
    /**
     * A string, quoted identifier or block comment is not closed before the end of the clause, or
     * an executable comment, which the server may read in part, is in it.
     */
    UNCLOSED,
    /** A closing parenthesis without an opening one, or an opening one never closed. */
    PARENTHESES,
    /** On a read-only connection, what {@link ReadWrite} refuses. */
    READ_ONLY
  }

  /**
   * The first problem that refuses the clause for the provider, or empty where it is safe to embed;
   * a blank clause has none.
   */
  public static Optional<Problem> problem(String clause, Provider provider, boolean readOnly) {
    if (clause == null || clause.isBlank()) {
      return Optional.empty();
    }
    if (Statements.split(clause, provider).size() > 1) {
      return Optional.of(Problem.MULTIPLE);
    }
    for (var reading : Tokens.readings(provider)) {
      var problem = unclosed(clause, provider, reading)
          ? Problem.UNCLOSED
          : outside(Tokens.of(clause, provider, reading));
      if (problem != null) {
        return Optional.of(problem);
      }
    }
    // Prefixed with SELECT so that a clause starting with the word SHOW is checked all the same.
    if (readOnly && ReadWrite.in("SELECT " + clause, provider)) {
      return Optional.of(Problem.READ_ONLY);
    }
    return Optional.empty();
  }

  // A semicolon, or parentheses that do not balance, among the tokens; null where neither.
  private static Problem outside(List<Tokens.Token> tokens) {
    int depth = 0;
    for (var token : tokens) {
      if (token.is(";")) {
        return Problem.MULTIPLE;
      } else if (token.is("(")) {
        depth++;
      } else if (token.is(")")) {
        depth--;
      }
      if (depth < 0) {
        return Problem.PARENTHESES;
      }
    }
    return depth == 0 ? null : Problem.PARENTHESES;
  }

  // Whether the clause leaves a string, quoted name or block comment open, or holds an executable
  // comment, whose code the server may run in part, by its version.
  private static boolean unclosed(String sql, Provider provider, Tokens.Reading reading) {
    for (var piece : Statements.pieces(sql, provider, reading.escapes())) {
      var comment = piece.kind() == Statements.Piece.Kind.COMMENT;
      // A line comment is open at the end of the text, and ends at the line break after it.
      if (piece.open() && (!comment || sql.startsWith("/*", piece.start()))
          || comment && ReadWrite.executable(sql, piece, provider)) {
        return true;
      }
    }
    return false;
  }
}
