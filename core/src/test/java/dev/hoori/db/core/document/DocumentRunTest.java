package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Replies that no test server gives as they are, made up here. */
class DocumentRunTest {

  // A write that wrote some of its documents, failed for others, and missed its write concern.
  private static final Document PARTLY = Document.of(
      "n",
      2,
      "writeErrors",
      Document.array(
          Document.of("index", 1, "code", 11000, "codeName", "DuplicateKey", "errmsg", "dup"),
          Document.of("index", 2, "code", 11000)),
      "writeConcernError",
      Document.of("code", 64, "codeName", "WriteConcernFailed", "errmsg", "waited"),
      "ok",
      1);

  private static List<Result> results(String statement) {
    var client = new DocumentClient() {
      @Override
      public Document command(String database, Document command) {
        return PARTLY;
      }

      @Override
      public void close() {
        // Nothing to end.
      }
    };
    var session = new DocumentSession(client, Provider.MONGODB.documents(), false);
    return new DocumentRun(session, "hoori", Script.Parsed.of(statement), _ -> {})
        .next(100)
        .results();
  }

  @Test
  void aWriteKeepsItsCountAndTellsItsFailures() {
    var results = results("db.a.insertMany([{ _id: 1 }, { _id: 2 }, { _id: 3 }])");
    assertEquals(3, results.size());
    assertEquals(new Result.Count(2), results.get(0));
    var first = (Failure) results.get(1);
    assertEquals(Failure.Kind.CONSTRAINT, first.kind());
    assertEquals("DuplicateKey: dup (and 1 more)", first.message());
    assertEquals("WriteConcernFailed: waited", ((Failure) results.get(2)).message());
  }

  @Test
  void aWriteSentAsACommandKeepsItsReplyAndTellsItsFailures() {
    var results = results("db.runCommand({ insert: 'a', documents: [{ _id: 1 }] })");
    assertEquals(3, results.size());
    var reply = ((Result.Rows) results.get(0)).documents().getFirst();
    assertEquals(PARTLY.without("ok"), reply);
    assertEquals("DuplicateKey: dup (and 1 more)", ((Failure) results.get(1)).message());
    assertEquals("WriteConcernFailed: waited", ((Failure) results.get(2)).message());
  }

  @Test
  void aFailureWithoutWordsSaysWhatFailed() {
    var client = new DocumentClient() {
      @Override
      public Document command(String database, Document command) {
        return Document.of("n", 0, "writeErrors", Document.array(Document.of("code", 2)));
      }

      @Override
      public void close() {
        // Nothing to end.
      }
    };
    var session = new DocumentSession(client, Provider.MONGODB.documents(), false);
    var write = new DocumentRun(session, "hoori", Script.Parsed.of("db.a.deleteOne({})"), _ -> {});
    assertEquals("deleteOne failed", ((Failure) write.next(100).results().get(1)).message());
    var command = new DocumentRun(
        session, "hoori", Script.Parsed.of("db.runCommand({ delete: 'a', deletes: [] })"), _ -> {});
    assertEquals("The write failed", ((Failure) command.next(100).results().get(1)).message());
  }
}
