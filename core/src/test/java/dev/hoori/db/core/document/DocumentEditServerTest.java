package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.query.QueryException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Single-document edit, insert, copy, delete, BSON type round-tripping, conflict detection, and
 * read-only/view refusal against the real MongoDB {@link TestServer}.
 */
class DocumentEditServerTest {

  private static final Database HOORI = new Database(TestServer.DATABASE, false);

  private static Table tempCollection() {
    return new Table(
        HOORI, "edit_" + UUID.randomUUID().toString().replace("-", ""), Table.Kind.TABLE);
  }

  private static void drop(DocumentSession session, Table collection) {
    try {
      session.command(HOORI.name(), Document.of("drop", collection.name()));
    } catch (DocumentException _) {
      // Collection may never have been created.
    }
  }

  private static List<Document> rawFind(DocumentSession session, Table collection)
      throws Exception {
    var reply = session.command(
        HOORI.name(), Document.of("find", collection.name(), "sort", Document.of("_id", 1)));
    var cursor = session.cursor(HOORI.name(), reply);
    try {
      var all = new ArrayList<Document>();
      while (cursor.more()) {
        all.addAll(cursor.next(100));
      }
      return all;
    } finally {
      cursor.close();
    }
  }

  @Test
  void editPreservesEveryBsonTypeThroughPageReadAndShellRoundTrip() throws Exception {
    var collection = tempCollection();
    var id = new Bson.ObjectId("64b7f0c2a1b2c3d4e5f60718");
    var uuid = UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962");
    var original = Document.of(
        "_id",
        id,
        "note",
        "before",
        "int32",
        42,
        "smallLong",
        5L,
        "bigLong",
        9_000_000_000L,
        "dbl",
        3.5,
        "wholeDbl",
        1.0,
        "negZero",
        -0.0,
        "nan",
        Double.NaN,
        "inf",
        Double.POSITIVE_INFINITY,
        "negInf",
        Double.NEGATIVE_INFINITY,
        "dec",
        new Bson.Decimal128("19.90"),
        "date",
        Instant.parse("2024-01-02T03:04:05.678Z"),
        "oldDate",
        Instant.parse("1815-12-10T00:00:00Z"),
        "ts",
        new Bson.Timestamp(1700000000L, 3),
        "uuid",
        Bson.Binary.of(uuid),
        "binGeneric",
        new Bson.Binary(0, new byte[] {1, 2, 3, 4}),
        "binMd5",
        new Bson.Binary(5, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}),
        "binUser",
        new Bson.Binary(128, new byte[] {0x7f, (byte) 0x80}),
        "regex",
        new Bson.Regex("a/b.*", "im"),
        "regexEscaped",
        new Bson.Regex("a\\/b", "i"),
        "code",
        new Bson.Code("function() { return 1; }", null),
        "codeWithScope",
        new Bson.Code("function() { return x; }", Document.of("x", 7)),
        "symbol",
        new Bson.Symbol("legacy"),
        "minKey",
        Bson.Special.MIN_KEY,
        "maxKey",
        Bson.Special.MAX_KEY,
        "nil",
        null,
        "bool",
        true,
        "nested",
        Document.of("$tag", "keep", "a.b", 1, "deep", Document.of("v", 2L)),
        "array",
        Document.array("x", null, 5L, -0.0, Document.of("k", "v")),
        "weird key",
        "quoted",
        "123",
        "digits");

    try (var session = DocumentSessionServerTest.session(false)) {
      try {
        assertInstanceOf(
            DocumentEdit.Outcome.Written.class,
            session.write(new DocumentEdit.Insert(collection, original)));

        // Read through the tab's page path, round-trip through Shell.pretty -> Shell.document.
        var fields = session.fields(collection);
        var page = session.read(collection, fields, 0, 10);
        assertEquals(1, page.documents().size());
        var read = page.documents().getFirst();
        assertEquals(original, read);

        var text = DocumentEdit.roundTripped(read).orElseThrow();
        var editedText = text.replace("note: 'before'", "note: 'after'");
        var edited = Shell.document(editedText);

        var logged = new ArrayList<Sent>();
        var outcome = Journal.recording(
            logged::add, () -> session.write(new DocumentEdit.Replace(collection, read, edited)));
        assertInstanceOf(DocumentEdit.Outcome.Written.class, outcome);
        assertEquals(1, logged.size());
        assertEquals(Purpose.CHANGES, logged.getFirst().purpose());

        // Read raw document back from the server and verify every single field and BSON type.
        var after = rawFind(session, collection).getFirst();
        var expected = original.with("note", "after");
        assertEquals(expected, after);
        assertEquals("after", after.get("note"));
        assertEquals(Integer.class, after.get("int32").getClass());
        assertEquals(Long.class, after.get("smallLong").getClass());
        assertEquals(Long.class, after.get("bigLong").getClass());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits((Double)
            after.get("negZero")));
        assertTrue(Double.isNaN((Double) after.get("nan")));
        assertEquals(new Bson.Decimal128("19.90"), after.get("dec"));
        assertEquals(Bson.Binary.of(uuid), after.get("uuid"));
        assertEquals(
            new Bson.Code("function() { return x; }", Document.of("x", 7)),
            after.get("codeWithScope"));
        assertEquals(new Bson.Symbol("legacy"), after.get("symbol"));
        assertEquals(Bson.Special.MIN_KEY, after.get("minKey"));
        assertEquals(Bson.Special.MAX_KEY, after.get("maxKey"));
        assertEquals("keep", ((Document) after.get("nested")).get("$tag"));
        assertEquals("quoted", after.get("weird key"));
      } finally {
        drop(session, collection);
      }
    }
  }

  @Test
  void insertCopyReplaceWithoutIdConflictAndDeleteWorkAgainstServer() throws Exception {
    var collection = tempCollection();
    try (var session = DocumentSessionServerTest.session(false)) {
      try {
        // Insert without _id: server assigns an ObjectId.
        assertInstanceOf(
            DocumentEdit.Outcome.Written.class,
            session.write(new DocumentEdit.Insert(collection, Document.of("name", "Ada", "n", 1))));
        var inserted = rawFind(session, collection).getFirst();
        assertInstanceOf(Bson.ObjectId.class, inserted.get("_id"));

        // Duplicate _id throws QueryException, not Changed.
        var dup = assertThrows(
            QueryException.class,
            () -> session.write(new DocumentEdit.Insert(collection, inserted)));
        assertTrue(
            dup.detail().contains("11000") || dup.detail().toLowerCase().contains("duplicate"),
            dup.detail());

        // Copy (without _id) inserts a second document with its own _id.
        var copy = inserted.without("_id").with("name", "Grace");
        assertInstanceOf(
            DocumentEdit.Outcome.Written.class,
            session.write(new DocumentEdit.Insert(collection, copy)));
        assertEquals(2, rawFind(session, collection).size());

        // Replace omitting _id keeps the original _id.
        assertInstanceOf(
            DocumentEdit.Outcome.Written.class,
            session.write(new DocumentEdit.Replace(
                collection, inserted, Document.of("name", "Ada Lovelace", "n", 2))));
        var afterReplace = rawFind(session, collection).stream()
            .filter(d -> inserted.get("_id").equals(d.get("_id")))
            .findFirst()
            .orElseThrow();
        assertEquals("Ada Lovelace", afterReplace.get("name"));
        assertEquals(2, afterReplace.get("n"));

        // Conflict on Replace: another client modifies the document between read and save.
        session.command(
            HOORI.name(),
            Document.of(
                "update",
                collection.name(),
                "updates",
                Document.array(Document.of(
                    "q", Document.of("_id", afterReplace.get("_id")),
                    "u", Document.of("$set", Document.of("n", 99))))));
        assertInstanceOf(
            DocumentEdit.Outcome.Changed.class,
            session.write(new DocumentEdit.Replace(
                collection, afterReplace, afterReplace.with("name", "Stale"))));
        // Server still holds n: 99 and name: "Ada Lovelace".
        var stillServer = rawFind(session, collection).stream()
            .filter(d -> afterReplace.get("_id").equals(d.get("_id")))
            .findFirst()
            .orElseThrow();
        assertEquals("Ada Lovelace", stillServer.get("name"));
        assertEquals(99, stillServer.get("n"));

        // Conflict on Delete with stale document matches nothing and deletes nothing.
        assertInstanceOf(
            DocumentEdit.Outcome.Changed.class,
            session.write(new DocumentEdit.Delete(collection, afterReplace)));
        assertEquals(2, rawFind(session, collection).size());

        // Delete with current document deletes it.
        assertInstanceOf(
            DocumentEdit.Outcome.Written.class,
            session.write(new DocumentEdit.Delete(collection, stillServer)));
        assertEquals(1, rawFind(session, collection).size());
        assertFalse(rawFind(session, collection).stream()
            .anyMatch(d -> stillServer.get("_id").equals(d.get("_id"))));
      } finally {
        drop(session, collection);
      }
    }
  }

  @Test
  void caseOnlyChangeInACaseInsensitiveCollectionIsAConflict() throws Exception {
    var collection = tempCollection();
    try (var session = DocumentSessionServerTest.session(false)) {
      try {
        session.command(
            HOORI.name(),
            Document.of(
                "create",
                collection.name(),
                "collation",
                Document.of("locale", "en", "strength", 2)));
        var read = Document.of("_id", 1, "name", "Ada");
        session.write(new DocumentEdit.Insert(collection, read));
        session.command(
            HOORI.name(),
            Document.of(
                "update",
                collection.name(),
                "updates",
                Document.array(Document.of(
                    "q",
                    Document.of("_id", 1),
                    "u",
                    Document.of("$set", Document.of("name", "ADA"))))));

        assertInstanceOf(
            DocumentEdit.Outcome.Changed.class,
            session.write(new DocumentEdit.Replace(collection, read, read.with("name", "Bea"))));
        assertInstanceOf(
            DocumentEdit.Outcome.Changed.class,
            session.write(new DocumentEdit.Delete(collection, read)));
        assertEquals(List.of(Document.of("_id", 1, "name", "ADA")), rawFind(session, collection));
      } finally {
        drop(session, collection);
      }
    }
  }

  @Test
  void aDbPointerReadsBackAsItselfAndIsNotOpenedForEditing() throws Exception {
    var collection = tempCollection();
    var pointer =
        new Bson.DbPointer(HOORI.name() + ".other", new Bson.ObjectId("64b7f0c2a1b2c3d4e5f60718"));
    try (var session = DocumentSessionServerTest.session(false)) {
      try {
        session.write(new DocumentEdit.Insert(collection, Document.of("_id", 1, "p", pointer)));
        var read = session.read(collection, session.fields(collection), 0, 10).documents();
        assertEquals(List.of(Document.of("_id", 1, "p", pointer)), read);
        assertTrue(DocumentEdit.roundTripped(read.getFirst()).isEmpty());
      } finally {
        drop(session, collection);
      }
    }
  }

  @Test
  void readOnlySessionRefusesDocumentWritesAndJournalMasksSecrets() throws Exception {
    var collection = tempCollection();
    try (var rw = DocumentSessionServerTest.session(false);
        var ro = DocumentSessionServerTest.session(true)) {
      try {
        var logged = new ArrayList<Sent>();
        var doc = Document.of("_id", 1, "name", "Ada", "pwd", "super-secret-value");
        Journal.recording(logged::add, () -> rw.write(new DocumentEdit.Insert(collection, doc)));
        assertEquals(1, logged.size());
        assertEquals(Purpose.CHANGES, logged.getFirst().purpose());
        assertFalse(
            logged.getFirst().text().contains("super-secret-value"),
            logged.getFirst().text());
        assertTrue(logged.getFirst().text().contains("***"), logged.getFirst().text());

        var replaceErr = assertThrows(
            QueryException.class,
            () -> ro.write(new DocumentEdit.Replace(collection, doc, doc.with("name", "B"))));
        assertEquals(QueryException.Failure.ACCESS_DENIED, replaceErr.failure());

        var insertErr = assertThrows(
            QueryException.class,
            () -> ro.write(new DocumentEdit.Insert(collection, Document.of("_id", 2))));
        assertEquals(QueryException.Failure.ACCESS_DENIED, insertErr.failure());

        var deleteErr = assertThrows(
            QueryException.class, () -> ro.write(new DocumentEdit.Delete(collection, doc)));
        assertEquals(QueryException.Failure.ACCESS_DENIED, deleteErr.failure());

        assertEquals(List.of(doc), rawFind(rw, collection));
      } finally {
        drop(rw, collection);
      }
    }
  }
}
