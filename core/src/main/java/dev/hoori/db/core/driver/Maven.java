package dev.hoori.db.core.driver;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * The names and versions of Maven artifacts that HooriDB accepts. They become parts of URLs and
 * folder names, so only letters, digits and single separators between them are allowed: nothing
 * that could climb out of a folder, start a query or be read as an escape.
 */
public final class Maven {

  // Possessive, as no separator is in a class: nothing to backtrack into, and no recursion that a
  // long text could overflow the stack with.
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]++(\\.[A-Za-z0-9_-]++)*+");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9]++([._-][A-Za-z0-9]++)*+");
  private static final Pattern STABLE = Pattern.compile("\\d++(\\.\\d++)*+");
  // Names that Windows reserves for devices, also when followed by an extension.
  private static final Pattern DEVICE =
      Pattern.compile("(?i)(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?");
  private static final Pattern TOKEN = Pattern.compile("[._-]");

  private Maven() {}

  /** Whether the text is a group or an artifact id that HooriDB accepts. */
  public static boolean isName(String text) {
    return text != null && text.length() <= 100 && NAME.matcher(text).matches() && !isDevice(text);
  }

  /** Whether the text is a version that HooriDB accepts. */
  public static boolean isVersion(String text) {
    return text != null
        && text.length() <= 64
        && VERSION.matcher(text).matches()
        && !isDevice(text);
  }

  /**
   * Whether the version is a release, made of numbers only. That leaves out pre-releases such as
   * MariaDB's 3.0.0-alpha or 1.5.0-RC1, and pgjdbc's builds for old Java, such as 42.2.0.jre7.
   */
  public static boolean isStable(String version) {
    return STABLE.matcher(version).matches();
  }

  /**
   * Newest first: numbers compare as numbers, so 42.7.10 comes before 42.7.9, and a qualifier
   * after a version makes it older than the version alone, so 3.0.0 comes before 3.0.0-alpha.
   */
  public static final Comparator<String> NEWEST_FIRST = Maven::compare;

  private static int compare(String a, String b) {
    String[] x = TOKEN.split(a);
    String[] y = TOKEN.split(b);
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      if (i == x.length) {
        return isNumber(y[i]) ? 1 : -1;
      }
      if (i == y.length) {
        return isNumber(x[i]) ? -1 : 1;
      }
      int c = compareTokens(x[i], y[i]);
      if (c != 0) {
        return c;
      }
    }
    return b.compareTo(a);
  }

  // Newest first, of a token each: numbers as numbers and before words, words without case.
  private static int compareTokens(String x, String y) {
    boolean xNumber = isNumber(x);
    boolean yNumber = isNumber(y);
    if (xNumber && yNumber) {
      return new BigInteger(y).compareTo(new BigInteger(x));
    }
    if (xNumber != yNumber) {
      return xNumber ? -1 : 1;
    }
    return y.compareToIgnoreCase(x);
  }

  private static boolean isNumber(String token) {
    return !token.isEmpty() && token.chars().allMatch(c -> c >= '0' && c <= '9');
  }

  private static boolean isDevice(String text) {
    return DEVICE.matcher(text).matches();
  }
}
