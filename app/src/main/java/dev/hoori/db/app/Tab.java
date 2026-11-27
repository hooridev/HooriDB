package dev.hoori.db.app;

/**
 * What a tab holds: the rows of a table, a console's statements and their results, a server's
 * monitor, or a search of a database or schema.
 */
sealed interface Tab permits TableTab, ConsoleTab, MonitorTab, FindTab {

  /** The tab was closed: what it has under way ends, and nothing of it arrives any more. */
  void close();
}
