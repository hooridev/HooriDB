package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Statements of the shell run on a real MongoDB, as a console runs them. */
class DocumentRunServerTest {

  @Test
  void aFindIsReadInPortionsAndClosed() throws Exception {
    try (var session = DocumentSessionServerTest.session(false)) {
      var run = new DocumentRun(session, "hoori", "db.orders.find().sort({ _id: 1 })", x -> {});
      var first = run.next(100);
      assertTrue(first.more());
      var rows = assertInstanceOf(Result.Rows.class, first.results().getFirst());
      assertEquals(100, rows.rows().size());
      assertEquals(100, rows.documents().size());
      assertEquals(
          List.of("_id", "customer", "total", "items", "paid"),
          rows.fields().stream().map(f -> f.name()).toList());
      var second = (Result.Rows) run.next(100).results().getFirst();
      assertEquals(100, second.from());
      assertEquals(101, second.documents().getFirst().get("_id"));
      assertTrue(run.close().quiet());
      assertFalse(run.next(100).more());
    }
  }

  @Test
  void aFindOneGivesOneDocumentAndACountItsNumber() throws Exception {
    try (var session = DocumentSessionServerTest.session(false)) {
      var one = new DocumentRun(
              session, "hoori", "db.customers.findOne({ name: 'Grace' })", x -> {})
          .next(100);
      assertFalse(one.more());
      var rows = (Result.Rows) one.results().getFirst();
      assertEquals(List.of(List.of(2L)), List.of(List.of(rows.rows().getFirst().getFirst())));
      var count = (Result.Rows)
          new DocumentRun(session, "hoori", "db.orders.find({ paid: true }).count()", x -> {})
              .next(10)
              .results()
              .getFirst();
      assertEquals(125L, count.rows().getFirst().getFirst());
    }
  }

  @Test
  void aWriteCountsWhatItChanged() throws Exception {
    try (var session = DocumentSessionServerTest.session(false)) {
      try {
        var inserted = new DocumentRun(
                session, "hoori", "db.runs.insertMany([{ _id: 1 }, { _id: 2 }])", x -> {})
            .next(10);
        assertEquals(new Result.Count(2), inserted.results().getFirst());
        var updated = new DocumentRun(
                session, "hoori", "db.runs.updateMany({}, { $set: { a: 1 } })", x -> {})
            .next(10);
        assertEquals(new Result.Count(2), updated.results().getFirst());
        assertEquals("Matched 2, modified 2", updated.messages().getFirst().text());
        var duplicate =
            new DocumentRun(session, "hoori", "db.runs.insertOne({ _id: 1 })", x -> {}).next(10);
        var failure = assertInstanceOf(Failure.class, duplicate.results().getLast());
        assertEquals(Failure.Kind.CONSTRAINT, failure.kind());
        assertEquals(11000, failure.code());
      } finally {
        new DocumentRun(session, "hoori", "db.runs.drop()", x -> {}).next(1);
      }
    }
  }

  @Test
  void aReadOnlySessionRefusesAWriteAndASyntaxErrorSaysWhere() throws Exception {
    try (var session = DocumentSessionServerTest.session(true)) {
      var refused = new DocumentRun(session, "hoori", "db.orders.deleteMany({})", x -> {}).next(10);
      var failure = (Failure) refused.results().getFirst();
      assertEquals(Failure.Kind.ACCESS_DENIED, failure.kind());
      assertTrue(failure.message().contains("read-only"), failure.message());
      var wrong = (Failure) new DocumentRun(session, "hoori", "db.orders.find({ a: })", x -> {})
          .next(10)
          .results()
          .getFirst();
      assertEquals(Failure.Kind.INVALID, wrong.kind());
      assertEquals(21, wrong.position()); // the "}" where a value is needed, counted from 1
    }
  }

  @Test
  void useSwitchesTheDatabaseWithoutSendingAnything() throws Exception {
    try (var session = DocumentSessionServerTest.session(true)) {
      var used = new ArrayList<String>();
      var portion = new DocumentRun(session, "hoori", "use admin", used::add).next(10);
      assertEquals(List.of("admin"), used);
      assertEquals(List.of(), portion.results());
      assertFalse(portion.more());
    }
  }
}
