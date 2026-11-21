package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    var client = provider.dialect().client();
    for (var reading : Tokens.readings(provider)) {
      if (client.controlsTransaction(words(sql, provider, reading))) {
        return true;
      }
    }
    return false;
  }

  // The code's words as the reading reads them, in lower case, without a variable's @.
  private static List<String> words(String sql, Provider provider, Tokens.Reading reading) {
    var words = new ArrayList<String>();
    for (var token : Tokens.of(sql, provider, reading)) {
      var text = token.text();
      int k = 0;
      while (k < text.length() && text.charAt(k) == '@') {
        k++;
      }
      words.add(Tokens.keyword(text.substring(k)).toLowerCase(Locale.ROOT));
    }
    return words;
  }
}
