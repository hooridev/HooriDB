package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Work;
import dev.hoori.db.core.connection.StatementLogStore;
import dev.hoori.db.core.log.Logged;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
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
 * background, a batch at a time, one call on the file after the other; while one is written, at
 * most {@value #WAITING} more wait for it, the newest. Turned off, it takes nothing, also not
 * what was sent before it was turned on again; before the setting is read, what comes is kept
 * until the setting says whether to.
 */
final class StatementLog {

  /** The most entries kept in memory, which the panel shows. */
  static final int KEPT = 2_000;

  /** The most entries that wait for the app's thread, or for the file; more are dropped. */
  static final int WAITING = 10_000;

  private final StatementLogStore store;
  private final BlockingQueue<Logged> arriving = new ArrayBlockingQueue<>(WAITING);
  private final AtomicBoolean posted = new AtomicBoolean(); // a task will take what has come
  private volatile boolean on = true;
  private volatile Instant since = Instant.MIN; // what was sent before was sent while it was off
  private volatile App<?> app; // from the start on

  // On the app's thread.
  private final List<Logged> entries = new ArrayList<>();
  private int changes; // counted, so that a view knows to look again
  private List<Logged> unsaved = new ArrayList<>();
  private final ArrayDeque<Call> calls = new ArrayDeque<>();
  private Work calling; // the call on the file that runs, or null
  private boolean writing; // a batch waits for the file or is written
  private boolean loaded; // the file was read, or could not be: from then on it is written
  private boolean chosen; // turned on or off before the setting was read
  private boolean cleared; // cleared before the file was read, whose entries then stay out
  private int dropped; // counts each clear and each turning off: a failed write from before stays
  private String problem; // why the file could not be read or written, or null

  /**
   * A call on the file, by what it does; the batch it writes, or null, and the count of {@link
   * #dropped} as it was asked for.
   */
  private record Call(String name, Step step, List<Logged> batch, int dropped) {}

  private interface Step {
    void run() throws IOException;
  }

  /**
   * Whether logging is on and the last entries of the file, as the start reads them, or why they
   * could not be read: the setting counts either way.
   */
  private record Loaded(boolean enabled, List<Logged> recent, String problem) {}

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
    if (!on || entry.sent().at().isBefore(since) || !arriving.offer(entry)) {
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
        () -> {
          boolean enabled = store.enabled();
          try {
            return new Loaded(enabled, store.recent(KEPT), null);
          } catch (IOException e) {
            return new Loaded(enabled, List.of(), e.getMessage());
          }
        },
        loaded -> {
          if (!chosen && !loaded.enabled()) {
            // Off since the last time: what came before the setting was read is not logged.
            suspend();
          }
          if (!cleared) {
            entries.addAll(0, loaded.recent());
            trim();
          }
          if (loaded.problem() != null) {
            problem = loaded.problem();
          }
          changes++;
          read();
        },
        error -> {
          // Whether it is off cannot be known: it is, until it is turned on.
          if (!chosen) {
            suspend();
          }
          problem = error.getMessage();
          changes++;
          read();
        });
    take();
    next(); // what was asked before
  }

  // Off before the setting was read: what came meanwhile is not logged.
  private void suspend() {
    on = false;
    arriving.clear();
    entries.clear();
    unsaved = new ArrayList<>();
    dropped++;
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
    if (on && !this.on) {
      since = Instant.now();
    }
    this.on = on;
    chosen = true;
    if (!on) {
      // What waits goes, also for the file; a batch the file is given already is written.
      arriving.clear();
      unsaved = new ArrayList<>();
      dropped++;
    }
    changes++;
    var store = this.store;
    call("keep whether the statement log is on", () -> store.enable(on), null);
  }

  /** Empties the log, here and on the disk. */
  void clear() {
    cleared |= !loaded;
    arriving.clear();
    entries.clear();
    unsaved = new ArrayList<>();
    dropped++;
    problem = null;
    changes++;
    call("clear the statement log", store::clear, null);
  }

  // Takes what has come, in the order it was sent, and has it written.
  private void take() {
    posted.set(false);
    var batch = new ArrayList<Logged>();
    arriving.drainTo(batch);
    var since = this.since;
    // Off, or of a statement sent before it was last turned on: in the queue as it was turned off.
    batch.removeIf(e -> !on || e.sent().at().isBefore(since));
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
    if (unsaved.size() > WAITING) {
      unsaved.subList(0, unsaved.size() - WAITING).clear();
    }
    changes++;
    write();
  }

  private void trim() {
    if (entries.size() > KEPT) {
      entries.subList(0, entries.size() - KEPT).clear();
    }
  }

  // One batch at a time: what comes while it is written waits in unsaved, bounded, for the next.
  private void write() {
    if (!loaded || !on || writing || unsaved.isEmpty()) {
      return;
    }
    var batch = List.copyOf(unsaved);
    unsaved = new ArrayList<>();
    var store = this.store;
    writing = true;
    call("write the statement log", () -> store.append(batch), batch);
  }

  // The call runs once those asked for before it have ended, each in the background.
  private void call(String name, Step step, List<Logged> batch) {
    calls.add(new Call(name, step, batch, dropped));
    next();
  }

  // A call has ended: the next one runs, and a batch that waited for a write is written. Of a
  // batch that failed, what was not written waits for the next, unless it was dropped meanwhile:
  // tried again as more comes, not at once, so that a disk that keeps failing is not kept busy.
  private void ended(Call call, Exception error) {
    calling = null;
    if (call.batch() != null) {
      writing = false;
      if (error == null) {
        write();
      } else if (call.dropped() == dropped) {
        int written = error instanceof StatementLogStore.Unwritten u ? u.written() : 0;
        var rest = new ArrayList<>(call.batch().subList(written, call.batch().size()));
        rest.addAll(unsaved);
        if (rest.size() > WAITING) {
          rest.subList(0, rest.size() - WAITING).clear();
        }
        unsaved = rest;
      }
    }
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
        done -> ended(call, null),
        error -> {
          problem = error.getMessage();
          changes++;
          ended(call, error);
        });
  }
}
