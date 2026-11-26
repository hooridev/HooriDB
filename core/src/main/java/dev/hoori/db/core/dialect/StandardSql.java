package dev.hoori.db.core.dialect;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialect-neutral standard SQL lexical rules (ISO SQL): double-quoted identifiers, single-quoted
 * strings without backslash escapes, {@code --} line comments and non-nested {@code /* … *&#47;}
 * block comments, and nothing specific to a server.
 */
final class StandardSql implements SyntaxDialect {

  // The reserved words that PostgreSQL, MariaDB and MySQL all reserve, and those the SQL standard
  // reserves that one of them takes as a name, such as END in CASE … END.
  private static final Set<String> RESERVED = Words.union(shared(), Words.words("""
      ANY ARRAY AUTHORIZATION CAST CURRENT_ROLE END LATERAL OFFSET ONLY OVER OVERLAPS SESSION_USER
      SIMILAR SOME SYSTEM_USER USER WINDOW
      """));

  private static final Set<String> OFFERED = Words.union(RESERVED, Words.TYPED);

  private static Set<String> shared() {
    var all = new HashSet<>(PostgreSql.INSTANCE.syntax().reservedWords());
    all.retainAll(MariaDb.INSTANCE.syntax().reservedWords());
    all.retainAll(MySql.INSTANCE.syntax().reservedWords());
    return Set.copyOf(all);
  }

  @Override
  public char identifierQuote() {
    return '"';
  }

  @Override
  public boolean backslashEscapes() {
    return false;
  }

  @Override
  public boolean doubleQuotedStrings() {
    return false;
  }

  @Override
  public boolean escapeStrings() {
    return false;
  }

  @Override
  public boolean dollarQuotedStrings() {
    return false;
  }

  @Override
  public boolean dashCommentNeedsSpace() {
    return false;
  }

  @Override
  public boolean hashComments() {
    return false;
  }

  @Override
  public boolean nestedComments() {
    return false;
  }

  @Override
  public List<String> executableComments() {
    return List.of();
  }

  @Override
  public boolean clientDelimiter() {
    return false;
  }

  // A body of BEGIN ATOMIC … END kept whole is psql's reading, not the standard's.
  @Override
  public boolean atomicBodies() {
    return false;
  }

  @Override
  public boolean userVariables() {
    return false;
  }

  @Override
  public boolean limitBoundsChanges() {
    return false;
  }

  @Override
  public boolean foldsNamesToLowerCase() {
    return false;
  }

  @Override
  public boolean caseSensitiveNames() {
    return false;
  }

  @Override
  public Set<String> reservedWords() {
    return RESERVED;
  }

  @Override
  public Set<String> offeredWords() {
    return OFFERED;
  }

  @Override
  public Set<String> passwordWords() {
    return Set.of();
  }
}
