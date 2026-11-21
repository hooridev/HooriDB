package dev.hoori.db.core.dialect;

import java.util.List;
import java.util.Set;

/** The lexical rules of a provider's SQL, and how its server reads names and key words. */
public interface SyntaxDialect {

  /** The character that quotes an identifier, doubled inside it. */
  char identifierQuote();

  /** Whether a backslash escapes in a plain string unless a setting of the server says not. */
  boolean backslashEscapes();

  /** Whether a double quote begins a string, as a single one does. */
  boolean doubleQuotedStrings();

  /** Whether {@code E'…'} is a string in which a backslash always escapes. */
  boolean escapeStrings();

  /** Whether {@code $tag$…$tag$} is a string. */
  boolean dollarQuotedStrings();

  /** Whether {@code --} begins a comment only before a space or a control character. */
  boolean dashCommentNeedsSpace();

  /** Whether {@code #} begins a comment to the end of the line. */
  boolean hashComments();

  /** Whether a {@code /*} comment nests in another. */
  boolean nestedComments();

  /**
   * What begins a comment whose text the server runs as code, as a server new enough for the
   * version that may follow does; empty where it has none.
   */
  List<String> executableComments();

  /** Whether its clients take a line {@code DELIMITER x} as the terminator from then on. */
  boolean clientDelimiter();

  /** Whether {@code @name} is one word, a variable of the session. */
  boolean userVariables();

  /** Whether a {@code LIMIT} bounds the rows an {@code UPDATE} or {@code DELETE} changes. */
  boolean limitBoundsChanges();

  /** Whether the server folds a name that is not quoted to lower case. */
  boolean foldsNamesToLowerCase();

  /** Whether the server tells names apart by case. */
  boolean caseSensitiveNames();

  /** The words its server reserves, in upper case, which a name must be quoted to be. */
  Set<String> reservedWords();

  /**
   * The words completion offers, in upper case: the reserved ones and the non-reserved ones typed
   * often.
   */
  Set<String> offeredWords();

  /**
   * The keywords, in upper case, after which a statement carries a password in a string, as {@code
   * PASSWORD} does in {@code CREATE ROLE r PASSWORD '…'}: where a statement's text is kept, as in
   * the statement log, its strings after the first of them are masked, and so is a string that
   * names one of them with a value, as {@code 'password=…'}.
   */
  Set<String> passwordWords();
}
