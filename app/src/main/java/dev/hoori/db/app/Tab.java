package dev.hoori.db.app;

/** What a tab holds: the rows of a table, or a console's statements and their results. */
sealed interface Tab permits TableTab, ConsoleTab {

  /** The tab was closed: what it has under way ends, and nothing of it arrives any more. */
  void close();
}
