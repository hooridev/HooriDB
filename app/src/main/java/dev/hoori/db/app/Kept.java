package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * A file kept beside the connections, saved as they are ({@link State#persist()}): one save at a
 * time, a change meanwhile saves again with what is there then. None before its load has ended, nor
 * after a load that failed with the file still there, which nothing may replace.
 */
final class Kept {
  final String what; // "consoles", in messages
  final Callable<Callable<?>> next; // on the app's thread: the save of what is there now
  boolean canSave; // its load ended, and for the consoles they were restored
  boolean unsaved; // differs from what was last written
  String saveError; // why the last save failed, or null
  Work saving;
  boolean again;
  int edits; // counted for delayed saves, so that a late patience is dropped
  boolean quitAnyway; // the last Exit stopped for it, and the next goes without it

  Kept(String what, Callable<Callable<?>> next) {
    this.what = what;
    this.next = next;
  }

  /** Whether Exit waits for it: a save runs, or one is due. */
  boolean waits() {
    return saving != null || unsaved && canSave && !quitAnyway;
  }

  /** What notices call it: "Consoles", "History", "Saved queries". */
  String title() {
    return what.substring(0, 1).toUpperCase(Locale.ROOT) + what.substring(1);
  }

  /** What writes a file: a store's save. */
  interface Store<T> {
    void save(T value) throws IOException;
  }

  /** The save of the value by the store, which gets only these two, off the app's thread. */
  static <T> Callable<T> writing(T value, Store<T> store) {
    return () -> {
      store.save(value);
      return value;
    };
  }
}
