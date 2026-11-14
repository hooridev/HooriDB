package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;

/** Names as they are written into SQL: quoted where the server would not take them as they are. */
public final class Names {

  private Names() {}

  /**
   * The name as it is written for the provider's server: as it is where the server takes it so,
   * else in double quotes on PostgreSQL and in backticks on MariaDB and MySQL. Quoted are a
   * reserved word, a name with anything but ASCII letters, digits and {@code _} or that begins
   * with a digit, and on PostgreSQL one with a capital letter, which it would fold.
   */
  public static String quoted(String name, Provider provider) {
    boolean postgres = provider == Provider.POSTGRESQL;
    boolean plain = !name.isEmpty() && !Character.isDigit(name.charAt(0));
    for (int i = 0; plain && i < name.length(); i++) {
      char c = name.charAt(i);
      plain = c >= 'a' && c <= 'z'
          || c >= '0' && c <= '9'
          || c == '_'
          || !postgres && c >= 'A' && c <= 'Z';
    }
    plain &= !Keywords.isReserved(name, provider);
    if (plain) {
      return name;
    }
    var quote = postgres ? "\"" : "`";
    return quote + name.replace(quote, quote + quote) + quote;
  }
}
