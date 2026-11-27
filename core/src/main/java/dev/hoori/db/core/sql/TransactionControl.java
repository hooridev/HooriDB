package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Whether a statement begins or ends a transaction, or turns auto-commit on or off, as the
 * dialect's {@link dev.hoori.db.core.dialect.ClientDialect#controlsTransaction} tells by its words,
 * by every way the server may read its text: a file that runs in one transaction does not send it.
 */
// shortcut: a procedure that commits by itself, as a CALL on MariaDB and MySQL may, is not seen,
// as DDL is not, which those servers commit by themselves too.
public final class TransactionControl {

  private TransactionControl() {}

  /** Whether the statement begins or ends a transaction, or turns auto-commit on or off. */
  public static boolean in(String sql, Provider provider) {
    return any(sql, provider, provider.dialect().client()::controlsTransaction);
  }

  /**
   * Whether the server may commit the open transaction by itself as it runs the statement, other
   * than as it runs DDL, as the dialect's {@link
   * dev.hoori.db.core.dialect.ClientDialect#commitsImplicitly} tells, by any way it may read it.
   */
  public static boolean commits(String sql, Provider provider) {
    return any(sql, provider, provider.dialect().client()::commitsImplicitly);
  }

  // Whether the words of any reading of the code are such.
  private static boolean any(String sql, Provider provider, Predicate<List<String>> such) {
    for (var reading : Tokens.readings(provider)) {
      if (such.test(words(sql, provider, reading))) {
        return true;
      }
    }
    return false;
  }

  // The code's words as the reading reads them, in lower case, without a system variable's @@,
  // as SET @@autocommit sets autocommit; a user variable's @ stays, as @autocommit is another.
  private static List<String> words(String sql, Provider provider, Tokens.Reading reading) {
    var words = new ArrayList<String>();
    for (var token : Tokens.of(sql, provider, reading)) {
      var text = token.text();
      var word = text.startsWith("@@") ? text.substring(2) : text;
      words.add(Tokens.keyword(word).toLowerCase(Locale.ROOT));
    }
    return words;
  }
}
