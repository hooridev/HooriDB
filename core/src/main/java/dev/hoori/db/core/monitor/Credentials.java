package dev.hoori.db.core.monitor;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Masks the values of credentials in what other sessions run, so that a password that a statement
 * sets or logs in with never leaves core: not into the view, the element tree, a log or an image.
 * It errs towards masking: where a statement names a credential anywhere, all its strings go.
 */
public final class Credentials {

  /** What stands in place of a value that may be a credential. */
  public static final String MASK = "•••";

  // Words that name a credential or bring one along: PASSWORD and its kin (ENCRYPTED PASSWORD,
  // MASTER_PASSWORD, SOURCE_PASSWORD, password_hash), IDENTIFIED BY, a SECRET.
  private static final Pattern WORD =
      Pattern.compile("(?i)[a-z_]*(password|passwd|secret)[a-z_]*|identified|pwd");

  // A string that holds one: a connection string's password=, or a URL with a user and password.
  private static final Pattern HOLDS =
      Pattern.compile("(?i)(password|passwd|pwd|secret)\\s*[=:]|://[^/@\\s]*:[^/@\\s]*@");

  private Credentials() {}

  /**
   * The statement of SQL with its strings masked: all of them where a word of its code names a
   * credential, else those that hold one. A string left open, as at the end of a statement the
   * server cut short, is masked as well. An executable comment is code for the server, whose
   * strings the pieces do not tell apart: its words count as code, and it is masked whole where
   * the strings are or it holds a credential.
   */
  public static String mask(String sql, Provider provider) {
    if (sql.isEmpty()) {
      return sql;
    }
    var pieces = Statements.pieces(sql, provider);
    var openings = provider.keepsDocuments()
        ? List.<String>of()
        : provider.dialect().syntax().executableComments();
    // The code where it stands, with its white space, and blanks in place of all else. A quoted
    // name counts as code, as an option may be named "password".
    var code = new StringBuilder(sql);
    for (var piece : pieces) {
      if (piece.kind() != Statements.Piece.Kind.CODE
          && piece.kind() != Statements.Piece.Kind.QUOTED
          && !executes(sql, piece, openings)) {
        for (int i = piece.start(); i < piece.end(); i++) {
          code.setCharAt(i, ' ');
        }
      }
    }
    // A name written with Unicode escapes (U&"\0070assword") may spell a credential that its
    // text does not: all strings go.
    boolean all = names(code.toString()) || escapesAName(sql, pieces);
    var out = new StringBuilder(sql.length());
    int at = 0;
    for (var piece : pieces) {
      boolean string = piece.kind() == Statements.Piece.Kind.STRING;
      if (!string && !executes(sql, piece, openings)) {
        continue;
      }
      var text = sql.substring(piece.start(), piece.end());
      if (all || HOLDS.matcher(text).find()) {
        out.append(sql, at, piece.start());
        out.append(string ? "'" + MASK + "'" : "/*" + MASK + "*/");
        at = piece.end();
      }
    }
    return out.append(sql, at, sql.length()).toString();
  }

  // Whether a quoted name of the statement is written with Unicode escapes, after U&.
  private static boolean escapesAName(String sql, List<Statements.Piece> pieces) {
    for (var piece : pieces) {
      if (piece.kind() == Statements.Piece.Kind.QUOTED
          && piece.start() >= 2
          && sql.regionMatches(true, piece.start() - 2, "U&", 0, 2)) {
        return true;
      }
    }
    return false;
  }

  // Whether the piece is a comment that the server runs as code.
  private static boolean executes(String sql, Statements.Piece piece, List<String> openings) {
    if (piece.kind() != Statements.Piece.Kind.COMMENT) {
      return false;
    }
    for (var opening : openings) {
      if (sql.startsWith(opening, piece.start())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The document with the values of its fields that name a credential masked, at any depth, and
   * its strings that hold one.
   */
  public static Document mask(Document document) {
    var entries = new ArrayList<Document.Entry>(document.size());
    for (var entry : document.entries()) {
      var value = WORD.matcher(entry.name()).matches() ? MASK : masked(entry.value());
      entries.add(new Document.Entry(entry.name(), value));
    }
    return new Document(entries);
  }

  private static Object masked(Object value) {
    return switch (value) {
      case Document d -> mask(d);
      case List<?> list -> list.stream().map(Credentials::masked).toList();
      case String s -> HOLDS.matcher(s).find() ? MASK : s;
      case null, default -> value;
    };
  }

  /**
   * The variable with its value masked where its name names a credential or its value holds one,
   * as a connection string with a password.
   */
  public static ServerVariable mask(ServerVariable variable) {
    var value = variable.value();
    return WORD.matcher(variable.name()).find()
            || value != null && HOLDS.matcher(value).find()
        ? new ServerVariable(variable.name(), MASK, variable.source())
        : variable;
  }

  // How a string writes a character of the password: in SQL with its quote doubled, or with a
  // backslash before its quote and backslash where a backslash escapes; in JSON with a backslash
  // before its quote and backslash.
  private static final List<Escape> ESCAPES =
      List.of(new Escape("'", '\''), new Escape("'\\", '\\'), new Escape("\"\\", '\\'));

  // The characters that a string writes with the escape before them.
  private record Escape(String characters, char escape) {}

  /**
   * The text with each place where it holds the password masked, also as a string of SQL or JSON
   * writes it; the password is compared where it is, without a copy as a string, which could not
   * be wiped.
   */
  public static String without(String text, char[] password) {
    if (password == null || password.length == 0) {
      return text;
    }
    // The escaped forms first, as they may hold the password as it is.
    for (var escape : ESCAPES) {
      var escaped = escaped(password, escape);
      if (escaped.length > password.length) {
        text = exactly(text, escaped);
      }
      Arrays.fill(escaped, '\0');
    }
    return exactly(text, password);
  }

  // The password with the escape before each of its characters.
  private static char[] escaped(char[] password, Escape escape) {
    int more = 0;
    for (char c : password) {
      more += escape.characters().indexOf(c) >= 0 ? 1 : 0;
    }
    var out = new char[password.length + more];
    int at = 0;
    for (char c : password) {
      if (escape.characters().indexOf(c) >= 0) {
        out[at++] = escape.escape();
      }
      out[at++] = c;
    }
    return out;
  }

  private static String exactly(String text, char[] password) {
    StringBuilder out = null;
    int at = 0;
    for (int i = 0; i + password.length <= text.length(); i++) {
      if (holds(text, i, password)) {
        out = out == null ? new StringBuilder(text.length()) : out;
        out.append(text, at, i).append(MASK);
        at = i + password.length;
        i = at - 1;
      }
    }
    return out == null ? text : out.append(text, at, text.length()).toString();
  }

  private static boolean holds(String text, int from, char[] password) {
    for (int j = 0; j < password.length; j++) {
      if (text.charAt(from + j) != password[j]) {
        return false;
      }
    }
    return true;
  }

  // Whether a word of the code names a credential.
  private static boolean names(String code) {
    var words = Pattern.compile("[\\p{L}\\p{N}_$]+").matcher(code);
    while (words.find()) {
      if (WORD.matcher(words.group().toLowerCase(Locale.ROOT)).matches()) {
        return true;
      }
    }
    return false;
  }
}
