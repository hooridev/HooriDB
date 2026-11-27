package dev.hoori.db.app;

/**
 * What a tab holds: the rows of a table, a console's statements and their results, a server's
 * monitor, or a copy of tables.
 */
sealed interface Tab permits TableTab, ConsoleTab, MonitorTab, TransferTab {

  /** The tab was closed: what it has under way ends, and nothing of it arrives any more. */
  void close();
}
