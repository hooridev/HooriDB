package dev.hoori.db.app;

/** What a tab holds: the rows of a table; a console's statements will join it. */
sealed interface Tab permits TableTab {

  /** The tab was closed: what it has under way ends, and nothing of it arrives any more. */
  void close();
}
