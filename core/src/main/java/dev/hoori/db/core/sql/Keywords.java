package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.Locale;
import java.util.Set;

/**
 * The key words of each provider's SQL, in upper case: those its server reserves, which a name
 * must be quoted to be, and those completion offers, which are the reserved ones and the
 * non-reserved ones typed often.
 */
public final class Keywords {

  private Keywords() {}

  /** The words the provider's server reserves. */
  public static Set<String> reserved(Provider provider) {
    return provider.dialect().syntax().reservedWords();
  }

  /** The words completion offers on the provider's server. */
  public static Set<String> offered(Provider provider) {
    return provider.dialect().syntax().offeredWords();
  }

  /** Whether the word, in any case, is reserved on the provider's server. */
  public static boolean isReserved(String word, Provider provider) {
    return reserved(provider).contains(word.toUpperCase(Locale.ROOT));
  }
}
