package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Work;
import dev.hoori.db.core.connection.StatementLogStore;
import dev.hoori.db.core.log.Logged;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What HooriDB sent to servers, of every connection, as the statement log keeps it: the last
 * {@value #KEPT} entries in memory, the oldest first by when they were sent, and all of them in
 * {@link StatementLogStore}'s file.
 *
 * <p>An entry comes from the thread that sent it, which never waits: it goes into a queue of at most
 * {@value #WAITING}, which drops what does not fit, and the app's thread takes what has come in a
 * task posted once for all that came meanwhile. What it takes it writes to the file in the
 * background, a batch at a time, one call on the file after the other. Turned off, it takes
 * nothing.
 */
final class StatementLog {

  /** The most entries kept in memory, which the panel shows. */
  static final int KEPT = 2_000;

  /** The most entries that wait for the app's thread; more are dropped. */
  static final int WAITING = 10_000;

  private final StatementLogStore store;
  private final BlockingQueue<Logged> arriving = new ArrayBlockingQueue<>(WAITING);
  private final AtomicBoolean posted = new AtomicBoolean(); // a task will take what has come
  private volatile boolean on = true;
  private volatile App<?> app; // from the start on

  // On the app's thread.
  private final List<Logged> entries = new ArrayList<>();
  private int changes; // counted, so that a view knows to look again
  private List<Logged> unsaved = new ArrayList<>();
  private final ArrayDeque<Call> calls = new ArrayDeque<>();
  private Work calling; // the call on the file that runs, or null
  private boolean loaded; // the file was read, or could not be: from then on it is written
  private boolean chosen; // turned on or off before the setting was read
  private String problem; // why the file could not be read or written, or null

  /** A call on the file, by what it does. */
  private record Call(String name, Step step) {}

  private interface Step {
    void run() throws IOException;
  }

  /** The last entries of the file and whether logging is on, as the start reads them. */
  private record Loaded(boolean enabled, List<Logged> recent) {}

  StatementLog(Path folder) {
    this.store = new StatementLogStore(folder);
  }

  /** Whether what is sent is logged: read by the threads that send. */
  boolean on() {
    return on;
  }

  /**
   * From any thread: the entry goes into the log, if it is on, without waiting; what the queue has
   * no room for is dropped.
   */
  void add(Logged entry) {
    if (!on || !arriving.offer(entry)) {
      return;
    }
    var app = this.app;
    if (app != null && posted.compareAndSet(false, true)) {
      app.post(this::take);
    }
  }

  /** Reads the file's last entries and whether logging is on, and from then on writes it. */
  void start(App<?> app) {
    this.app = app;
    var store = this.store;
    app.background(
        "read the statement log",
        () -> new Loaded(store.enabled(), store.recent(KEPT)),
        loaded -> {
          if (!chosen) {
            on = loaded.enabled();
          }
          entries.addAll(0, loaded.recent());
          trim();
          changes++;
          read();
        },
        error -> {
          problem = error.getMessage();
          read();
        });
    take();
    next(); // what was asked before
  }

  private void read() {
    loaded = true;
    take();
    write();
  }

  /** The entries, the oldest first; the list changes on the app's thread. */
  List<Logged> entries() {
    return entries;
  }

  /** How often the entries changed, so that a view knows to look again. */
  int changes() {
    return changes;
  }

  /** Why the log could not be read or written, or null. */
  String problem() {
    return problem;
  }

  /** Turns logging on or off, and keeps the choice; off, what waits is dropped. */
  void turn(boolean on) {
    this.on = on;
    chosen = true;
    if (!on) {
      arriving.clear();
    }
    changes++;
    var store = this.store;
    call("keep whether the statement log is on", () -> store.enable(on));
  }

  /** Empties the log, here and on the disk. */
  void clear() {
    arriving.clear();
    entries.clear();
    unsaved = new ArrayList<>();
    problem = null;
    changes++;
    call("clear the statement log", store::clear);
  }

  // Takes what has come, in the order it was sent, and has it written.
  private void take() {
    posted.set(false);
    var batch = new ArrayList<Logged>();
    arriving.drainTo(batch);
    if (batch.isEmpty()) {
      return;
    }
    batch.sort(Comparator.comparing(e -> e.sent().at()));
    for (var entry : batch) {
      // Mostly at the end: what came later was mostly sent later.
      int at = entries.size();
      while (at > 0 && entries.get(at - 1).sent().at().isAfter(entry.sent().at())) {
        at--;
      }
      entries.add(at, entry);
    }
    trim();
    unsaved.addAll(batch);
    changes++;
    write();
  }

  private void trim() {
    if (entries.size() > KEPT) {
      entries.subList(0, entries.size() - KEPT).clear();
    }
  }

  private void write() {
    if (!loaded || unsaved.isEmpty()) {
      return;
    }
    var batch = List.copyOf(unsaved);
    unsaved = new ArrayList<>();
    var store = this.store;
    call("write the statement log", () -> store.append(batch));
  }

  // The call runs once those asked for before it have ended, each in the background.
  private void call(String name, Step step) {
    calls.add(new Call(name, step));
    next();
  }

  private void next() {
    var app = this.app;
    if (calling != null || calls.isEmpty() || app == null) {
      return;
    }
    var call = calls.poll();
    calling = app.background(
        call.name(),
        () -> {
          call.step().run();
          return null;
        },
        done -> {
          calling = null;
          next();
        },
        error -> {
          calling = null;
          problem = error.getMessage();
          changes++;
          next();
        });
  }
}
