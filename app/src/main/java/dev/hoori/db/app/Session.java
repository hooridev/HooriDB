package dev.hoori.db.app;

import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Message;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import java.io.Serial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On the lane: what the console's calls do with its session, each as one lane call, and what they
 * give back.
 */
final class Session {

  private Session() {}

  /**
   * What a statement's call read: its first portion where it was read lazily, else all of it as
   * one; for each of the portion's results, how many of its rows were read and not kept.
   */
  record Ran(Portion portion, List<Long> dropped, boolean lazy) {}

  /**
   * Commit's or Rollback's call: the transaction the server said before a COMMIT, null before a
   * ROLLBACK; the statement's portion, null where the COMMIT was not sent.
   */
  record Ended(Transaction before, Portion portion) {}

  /** A switch of auto-commit: whether the session has the mode asked for now, and its status. */
  record Switched(boolean done, Status now) {}

  /**
   * A statement not sent, as the session's mode is not the one shown, or cannot be known; with what
   * the session has.
   */
  static final class NotRun extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    // Never serialized: it only carries the session's status from the lane to the app's thread.
    final transient Status status;
    final boolean unverifiable; // the driver cannot say the server's auto-commit

    NotRun(String why, Status status, boolean unverifiable) {
      super(why);
      this.status = status;
      this.unverifiable = unverifiable;
    }
  }

  /**
   * A read not sent, as the console would read it whole and the planner estimates more rows than
   * it keeps: the user is asked first. With the session's status, read before anything was sent.
   */
  static final class TooMany extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    final long rows;
    final transient Status status; // never serialized, as NotRun's

    TooMany(long rows, Status status) {
      super(ConsoleTab.rows(rows));
      this.rows = rows;
      this.status = status;
    }
  }

  /**
   * The lane call of every console call but a further portion, as one unit: the statement still
   * open on the session is given up first, whatever the app's thread knew of it, as a step of its
   * own. Where that fails, as the commit of a read's own transaction does on a deferred
   * constraint, the failure goes to the app at once ({@link Delivery.Tell}), from here, and the call
   * is not made: {@link GaveUp} says why.
   */
  static <T> T after(Servers.Link link, Delivery.Tell tell, String left, Lane.Call<T> call)
      throws Exception {
    var ended = link.giveUp();
    told(ended, left, ENDED, tell);
    if (ended.failure().isPresent()) {
      throw new GaveUp(left, ended.failure().get());
    }
    return call.call(link);
  }

  /** What giving up a statement said, its failure and the server's warnings, told as notices. */
  static void told(Abandoned ended, String sql, String how, Delivery.Tell tell) {
    ended.failure().ifPresent(f -> tell.tell(sql, how + f.message() + MAY_NOT_STAND, true));
    for (var m : ended.messages()) {
      tell.tell(sql, Delivery.warned(m), false);
    }
  }

  static final String ENDED = "Its end, as the console went on, failed: ";
  static final String MAY_NOT_STAND = ". What it changed may not stand.";

  /** Giving up what was still open on the session failed: what wanted to run after it did not. */
  static final class GaveUp extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    final String sql;
    final transient Failure failure; // never serialized, as NotRun's status

    GaveUp(String sql, Failure failure) {
      super(failure.message());
      this.sql = sql;
      this.failure = failure;
    }
  }

  /**
   * The one place where the session's auto-commit changes, as JDBC commits a transaction that is
   * open where it is turned on: only where the server says, right before, that none is open, or
   * where the one open is empty, the server's own begun as the user's Commit or Rollback ended
   * theirs. Done where the session has the mode afterwards, by the server's word.
   */
  static Switched setMode(Servers.Link link, boolean manual, boolean empty) throws QueryException {
    return setMode(link, manual, empty, link.status());
  }

  private static Switched setMode(Servers.Link link, boolean manual, boolean empty, Status now)
      throws QueryException {
    if (now.autoCommit() != null && now.autoCommit() == !manual) {
      return new Switched(true, now);
    }
    if (now.autoCommit() == null
        || now.transaction() != Transaction.NONE
            && !(now.transaction() == Transaction.OPEN && empty)) {
      return new Switched(false, now);
    }
    link.manual(manual);
    now = link.status();
    return new Switched(now.autoCommit() != null && now.autoCommit() == !manual, now);
  }

  /**
   * A statement to send: as the shell read it on a server of documents, else null; in the mode
   * shown; whether a plain read may stay open as the grid scrolls; how many rows of a result it
   * keeps; and the planner's connection and database for an estimate first, or null for none.
   */
  record Send(
      String sql,
      Script.Parsed parsed,
      ConsoleTab.Mode mode,
      boolean lazy,
      int keep,
      Lane planner,
      String database) {}

  /**
   * A statement on the session, as one lane call. It reads the session's status fresh, by the
   * server's word on MariaDB and MySQL and by pgjdbc's on PostgreSQL, and is sent only where the
   * session has the mode shown; a session without it takes it where nothing is open, else the
   * statement is not sent. Auto runs also where the driver cannot say, as nothing then stays open
   * that the console would not ask about. A plain read asked for lazily, in Auto with nothing open
   * by the server's word, gives its first portion and the rest as the grid scrolls; any other
   * statement is read to its end here, so that no execution is ever left open inside a
   * transaction or of anything that may change data, keeping at most {@code keep} rows a result.
   * Such a read is not sent where the planner estimates more rows than that on {@code planner}, a
   * connection of the app's own, or null for no estimate, with its names read on {@code database}:
   * {@link TooMany}. The run, once sent, is set in {@code held}.
   */
  static Ran start(Servers.Link link, Send send, AtomicReference<Servers.Run> held)
      throws QueryException, NotRun, TooMany, InterruptedException {
    var sql = send.sql();
    var parsed = send.parsed();
    var mode = send.mode();
    boolean lazy = send.lazy();
    int keep = send.keep();
    var planner = send.planner();
    var now = link.status();
    if (now.autoCommit() == null && mode == ConsoleTab.Mode.MANUAL) {
      throw new NotRun(ConsoleTab.UNVERIFIABLE, now, true);
    }
    // Read whole, as it is unless it is a lazy read with nothing open, once the session has the
    // mode; estimated before anything is sent on the session, which then stays as it was.
    if (planner != null
        && !(lazy && now.transaction() == Transaction.NONE && now.autoCommit() != null)) {
      long rows = estimate(planner, send.database(), sql);
      if (rows > keep) {
        throw new TooMany(rows, now);
      }
    }
    now = inMode(link, mode, now);
    var run = parsed != null ? link.run(parsed) : link.run(sql);
    held.set(run); // sent: cut short now, what it did is in doubt; and the rest as the grid scrolls
    var first = run.next(ConsoleTab.PORTION);
    if (lazy && Boolean.TRUE.equals(now.autoCommit()) && now.transaction() == Transaction.NONE) {
      return new Ran(first, Collections.nCopies(first.results().size(), 0L), true);
    }
    return drained(run, first, keep);
  }

  /**
   * The session in the mode asked for, by the server's word: where it says another, it takes the
   * mode where nothing is open, else the statement is not sent. Where it cannot say, it is left.
   */
  private static Status inMode(Servers.Link link, ConsoleTab.Mode mode, Status now)
      throws QueryException, NotRun {
    if (now.autoCommit() == null || now.autoCommit() == (mode == ConsoleTab.Mode.AUTO)) {
      return now;
    }
    var switched = setMode(link, mode == ConsoleTab.Mode.MANUAL, false, now);
    if (!switched.done()) {
      throw new NotRun(
          "The session's auto-commit is "
              + (Boolean.TRUE.equals(switched.now().autoCommit()) ? "on" : "off")
              + " with a transaction open, so it was not sent in "
              + mode.title
              + ". The console shows the session's mode now.",
          switched.now(),
          false);
    }
    return switched.now();
  }

  /**
   * The planner's estimate of the read's rows, asked on the app's own connection to the database,
   * so that nothing of it touches the session or its transaction; 0 where it has none, so that the
   * read runs without a question. An interrupt gives it up there too.
   */
  // shortcut: that connection does not see the session's uncommitted DDL, temporary tables or
  // settings such as search_path or a USE run in the console: there the EXPLAIN fails or plans
  // something else, and no question or another is asked. Plan on the session once that misleads.
  private static long estimate(Lane planner, String database, String sql)
      throws InterruptedException {
    Future<OptionalLong> asked;
    try {
      asked = planner.submit(link -> link.estimate(database, sql));
    } catch (RejectedExecutionException _) {
      return 0; // its lane has closed
    }
    try {
      return asked.get().orElse(0);
    } catch (InterruptedException e) {
      asked.cancel(true);
      throw e;
    } catch (ExecutionException | CancellationException _) {
      return 0;
    }
  }

  /**
   * Reads the statement to its end: rows of a result that go on joined as they come, at most
   * {@code keep} of each result kept and those beyond dropped as they arrive, counted. An interrupt
   * ends it, as core cancels the statement on the server and gives no more.
   */
  static Ran drained(Servers.Run run, Portion first, int keep) throws InterruptedException {
    var results = new ArrayList<Result>();
    var dropped = new ArrayList<Long>();
    var documents = new ArrayList<List<Document>>(); // those kept of each result's rows, or null
    var messages = new ArrayList<Message>();
    var took = Duration.ZERO;
    var portion = first;
    while (true) {
      took = took.plus(portion.elapsed());
      messages.addAll(portion.messages());
      for (var result : portion.results()) {
        if (result instanceof Result.Rows r) {
          join(r, results, dropped, documents, keep);
        } else {
          results.add(result);
          dropped.add(0L);
          documents.add(null);
        }
      }
      if (!portion.more()) {
        break;
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException(); // Cancel, also where core did not notice it
      }
      portion = run.next(ConsoleTab.PORTION);
    }
    // A server of documents' rows keep their documents, as many as the rows kept.
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i) instanceof Result.Rows r
          && documents.get(i) != null
          && !documents.get(i).isEmpty()) {
        results.set(i, new Result.Rows(r.fields(), 0, r.rows(), documents.get(i)));
      }
    }
    return new Ran(
        new Portion(results, messages, false, took, portion.transaction(), portion.autoCommit()),
        dropped,
        false);
  }

  /**
   * Rows of a portion, kept as a result of their own where they begin one, else joined to the
   * result before: at most {@code keep} of a result, those beyond counted as dropped.
   */
  private static void join(
      Result.Rows r,
      List<Result> results,
      List<Long> dropped,
      List<List<Document>> documents,
      int keep) {
    int at = results.size() - 1;
    if (r.from() == 0 || at < 0 || !(results.get(at) instanceof Result.Rows)) {
      results.add(new Result.Rows(r.fields(), 0, new ArrayList<>()));
      dropped.add(0L);
      documents.add(new ArrayList<>());
      at++;
    }
    var kept = ((Result.Rows) results.get(at)).rows();
    int room = Math.clamp(keep - (long) kept.size(), 0, r.rows().size());
    kept.addAll(r.rows().subList(0, room));
    if (!r.documents().isEmpty()) {
      documents.get(at).addAll(r.documents().subList(0, room));
    }
    dropped.set(at, dropped.get(at) + r.rows().size() - room);
  }

  /**
   * Commit or Rollback. A COMMIT is sent only where the server says, right before, that a
   * transaction is open: PostgreSQL answers one in a failed transaction by rolling it back, without
   * an error, and MariaDB and MySQL roll one back by themselves, as after a deadlock.
   */
  static Ended finish(Servers.Link link, String sql, Delivery.Tell tell) throws QueryException {
    Transaction before = null;
    if (sql.equals(ConsoleTab.COMMIT)) {
      var now = link.status();
      before = now.transaction();
      if (before != Transaction.OPEN) {
        return new Ended(before, null);
      }
    }
    var run = link.run(sql);
    Portion portion;
    try {
      portion = run.next(1);
    } finally {
      run.close();
    }
    told(portion, sql, tell);
    return new Ended(before, portion);
  }

  /**
   * What Commit's or Rollback's statement came back with beyond its plain outcome, its failure or
   * the server's warnings, goes to the app's notices at once, from the lane.
   */
  private static void told(Portion portion, String sql, Delivery.Tell tell) {
    var failure = failure(portion);
    if (failure != null) {
      tell.tell(
          sql,
          (sql.equals(ConsoleTab.COMMIT) ? "Commit failed: " : "Rollback failed: ")
              + failure.message(),
          true);
    }
    for (var m : portion.messages()) {
      var matter = Delivery.matter(m);
      tell.tell(sql, matter.what(), matter.failure());
    }
  }

  /**
   * The portion's failure, or null where it has none: its first that is not a cancel, as a
   * connection lost while a cancelled read ended, else the cancel.
   */
  static Failure failure(Portion portion) {
    Failure cancel = null;
    for (var result : portion.results()) {
      if (result instanceof Failure f) {
        if (f.kind() != Failure.Kind.CANCELLED) {
          return f;
        }
        if (cancel == null) {
          cancel = f;
        }
      }
    }
    return cancel;
  }
}
