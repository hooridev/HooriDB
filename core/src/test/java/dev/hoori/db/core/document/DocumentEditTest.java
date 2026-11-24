package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.query.QueryException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DocumentEdit} and {@link DocumentSession#write(DocumentEdit)}. */
class DocumentEditTest {

  private static final Database HOORI = new Database("hoori", false);
  private static final Table ITEMS = new Table(HOORI, "items", Table.Kind.TABLE);
  private static final Table VIEW = new Table(HOORI, "items_view", Table.Kind.VIEW);
  private static final Bson.ObjectId ID = new Bson.ObjectId("64b7f0c2a1b2c3d4e5f60718");

  @Test
  void roundTripAcceptsAllBsonTypesAndRefusesDocumentsThatDoNotRoundTrip() {
    var allTypes = Document.of(
        "_id",
        ID,
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
        Bson.Binary.of(UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962")),
        "bin0",
        new Bson.Binary(0, new byte[] {1, 2, 3, 4}),
        "bin5",
        new Bson.Binary(5, new byte[] {9, 8, 7}),
        "bin128",
        new Bson.Binary(128, new byte[] {0x7f, (byte) 0x80}),
        "re",
        new Bson.Regex("a/b.*", "im"),
        "reEscaped",
        new Bson.Regex("a\\/b", "i"),
        "code",
        new Bson.Code("function() { return 1; }", null),
        "scoped",
        new Bson.Code("function() { return x; }", Document.of("x", 7)),
        "sym",
        new Bson.Symbol("legacy"),
        "min",
        Bson.Special.MIN_KEY,
        "max",
        Bson.Special.MAX_KEY,
        "nil",
        null,
        "flag",
        true,
        "text",
        "Ada's \"notes\"\nline 2",
        "nested",
        Document.of("$tag", "keep", "a.b", 1),
        "arr",
        Document.array("x", null, 5L, Document.of("k", "v")),
        "weird key",
        "quoted");

    var text = DocumentEdit.roundTripped(allTypes);
    assertTrue(text.isPresent());
    assertTrue(text.get().contains("Long('5')"), text.get());
    assertTrue(text.get().contains("-0.0"), text.get());
    assertTrue(text.get().contains("Decimal128('19.90')"), text.get());

    // Duplicate field names collapse when parsed by Shell.document, so roundTripped refuses.
    var duplicate = new Document(List.of(
        new Document.Entry("_id", 1), new Document.Entry("a", 1), new Document.Entry("a", 2)));
    assertTrue(DocumentEdit.roundTripped(duplicate).isEmpty());

    // A DBPointer has no constructor in the shell, so its text does not read back.
    var pointer = Document.of("_id", 1, "p", new Bson.DbPointer("hoori.items", ID));
    assertTrue(DocumentEdit.roundTripped(pointer).isEmpty());
  }

  @Test
  void maskingReachesTheScopeOfCode() {
    var code = new Bson.Code("f()", Document.of("pwd", "hunter2", "x", 1));
    var masked = Document.of("c", code).masked(java.util.Set.of("pwd"), "***");
    assertEquals(Document.of("c", new Bson.Code("f()", Document.of("pwd", "***", "x", 1))), masked);
  }

  @Test
  void replaceInsertAndDeleteBuildSingleDocumentCommandsAndParseablePreviews() throws Exception {
    var original = Document.of("_id", ID, "name", "Ada", "score", 42L);
    var updated = Document.of("_id", ID, "name", "Alicia", "score", 42L);

    var replace = new DocumentEdit.Replace(ITEMS, original, updated);
    var replaceCmd = replace.command();
    assertEquals("update", replaceCmd.first());
    assertEquals("items", replaceCmd.get("update"));
    var updates = (List<?>) replaceCmd.get("updates");
    assertEquals(1, updates.size());
    var stmt = (Document) updates.getFirst();
    assertEquals(DocumentEdit.exactFilter(original), stmt.get("q"));
    assertEquals(updated, stmt.get("u"));
    assertEquals(false, stmt.get("multi"));
    assertEquals(false, stmt.get("upsert"));
    assertEquals(Document.of("locale", "simple"), stmt.get("collation"));
    assertTrue(
        replace.preview().contains("db.getCollection('items').replaceOne("), replace.preview());
    assertEquals(replaceCmd, Script.parse(replace.preview()).command());

    // Omitting _id in replacement is allowed.
    var noIdReplace = new DocumentEdit.Replace(ITEMS, original, Document.of("name", "Alicia"));
    assertEquals(
        Document.of("name", "Alicia"),
        ((Document) ((List<?>) noIdReplace.command().get("updates")).getFirst()).get("u"));

    var inserted = Document.of("name", "Grace", "score", 10L);
    var insert = new DocumentEdit.Insert(ITEMS, inserted);
    assertEquals(
        Document.of("insert", "items", "documents", Document.array(inserted)), insert.command());
    assertTrue(insert.preview().contains("db.getCollection('items').insertOne("), insert.preview());
    // Script.parse adds a client-side ObjectId to insertOne without _id; check it parses cleanly.
    assertEquals("insert", Script.parse(insert.preview()).command().first());

    var delete = new DocumentEdit.Delete(ITEMS, original);
    var deleteCmd = delete.command();
    assertEquals("delete", deleteCmd.first());
    var deletes = (List<?>) deleteCmd.get("deletes");
    assertEquals(1, deletes.size());
    var delStmt = (Document) deletes.getFirst();
    assertEquals(DocumentEdit.exactFilter(original), delStmt.get("q"));
    assertEquals(1, delStmt.get("limit"));
    assertEquals(Document.of("locale", "simple"), delStmt.get("collation"));
    assertTrue(delete.preview().contains("db.getCollection('items').deleteOne("), delete.preview());
    assertEquals(deleteCmd, Script.parse(delete.preview()).command());
  }

  @Test
  void validationRefusesViewsMissingIdChangedIdAndTopLevelOperators() {
    var original = Document.of("_id", 1, "name", "Ada");
    assertThrows(
        IllegalArgumentException.class, () -> new DocumentEdit.Replace(VIEW, original, original));
    assertThrows(IllegalArgumentException.class, () -> new DocumentEdit.Insert(VIEW, original));
    assertThrows(IllegalArgumentException.class, () -> new DocumentEdit.Delete(VIEW, original));

    var noId = Document.of("name", "Ada");
    assertThrows(IllegalArgumentException.class, () -> new DocumentEdit.Replace(ITEMS, noId, noId));
    assertThrows(IllegalArgumentException.class, () -> new DocumentEdit.Delete(ITEMS, noId));

    // Changing _id value or type is refused.
    assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentEdit.Replace(ITEMS, original, Document.of("_id", 2, "name", "Ada")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentEdit.Replace(ITEMS, original, Document.of("_id", 1L, "name", "Ada")));

    // Top-level $ field in replacement is refused so update cannot run operators.
    assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentEdit.Replace(
            ITEMS, original, Document.of("$set", Document.of("name", "B"))));
  }

  @Test
  void sessionWriteRefusesReadOnlyLogsMaskedChangesAndDetectsWriteErrorsAndConflicts()
      throws Exception {
    var sent = new ArrayList<Document>();
    var reply = new Document[] {Document.of("ok", 1, "n", 1, "nModified", 1)};
    var client = new DocumentClient() {
      @Override
      public Document command(String database, Document command) {
        sent.add(command);
        return reply[0];
      }

      @Override
      public void close() {}
    };

    var doc = Document.of("_id", 1, "name", "Ada", "pwd", "top-secret");
    var insert = new DocumentEdit.Insert(ITEMS, doc);

    // Read-only session refuses without sending.
    try (var ro = new DocumentSession(client, Provider.MONGODB.documents(), true)) {
      var err = assertThrows(QueryException.class, () -> ro.write(insert));
      assertEquals(QueryException.Failure.ACCESS_DENIED, err.failure());
      assertTrue(sent.isEmpty());
    }

    // Writable session logs under Purpose.CHANGES with secret fields masked.
    var logged = new ArrayList<Sent>();
    try (var rw = new DocumentSession(client, Provider.MONGODB.documents(), false)) {
      var written = Journal.recording(logged::add, () -> rw.write(insert));
      assertInstanceOf(DocumentEdit.Outcome.Written.class, written);
      assertEquals(1, logged.size());
      assertEquals(Purpose.CHANGES, logged.getFirst().purpose());
      assertTrue(logged.getFirst().text().contains("***"), logged.getFirst().text());
      assertTrue(
          !logged.getFirst().text().contains("top-secret"), logged.getFirst().text());

      // n == 0 on Replace/Delete returns Changed.
      reply[0] = Document.of("ok", 1, "n", 0, "nModified", 0);
      assertInstanceOf(
          DocumentEdit.Outcome.Changed.class, rw.write(new DocumentEdit.Replace(ITEMS, doc, doc)));
      assertInstanceOf(
          DocumentEdit.Outcome.Changed.class, rw.write(new DocumentEdit.Delete(ITEMS, doc)));

      // writeErrors throws QueryException rather than returning Changed.
      reply[0] = Document.of(
          "ok",
          1,
          "n",
          0,
          "writeErrors",
          Document.array(Document.of("index", 0, "code", 11000, "errmsg", "E11000 duplicate key")));
      var dup = assertThrows(QueryException.class, () -> rw.write(insert));
      assertTrue(dup.detail().contains("11000"), dup.detail());

      // A writeConcernError may follow a write the server applied: unconfirmed, not failed.
      reply[0] = Document.of(
          "ok",
          1,
          "n",
          1,
          "writeConcernError",
          Document.of("code", 64, "codeName", "WriteConcernFailed", "errmsg", "waiting timed out"));
      assertEquals(new DocumentEdit.Outcome.Unconfirmed("waiting timed out"), rw.write(insert));
    }
  }
}
