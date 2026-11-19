package dev.hoori.db.core.dialect;

/** The lexical rules of a provider's SQL, and how its server reads names. */
public interface SyntaxDialect {

  /** The character that quotes an identifier, doubled inside it. */
  char identifierQuote();
}
