package dev.hoori.db.app;

/**
 * What a tab holds: the rows of a table, a console's statements and their results, a server's
 * monitor, or a routine's source.
 */
sealed interface Tab permits TableTab, ConsoleTab, MonitorTab, RoutineTab {

  /** The tab was closed: what it has under way ends, and nothing of it arrives any more. */
  void close();
}
