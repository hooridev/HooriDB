package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.document.Request.Shape;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptTest {

  @Test
  void statementsEndAtSemicolonsAndLinesThatAreComplete() {
    var text = """
        db.a.find({
          x: 1
        })
          .sort({ x: -1 })
        // a comment
        db.b.find(); db.c.find({ s: 'a;b' })

        use shop
        """;
    var pieces = Script.split(text);
    assertEquals(
        List.of(
            "db.a.find({\n  x: 1\n})\n  .sort({ x: -1 })",
            "db.b.find();",
            "db.c.find({ s: 'a;b' })",
            "use shop"),
        pieces.stream().map(Script.Piece::text).toList());
    assertEquals(text.indexOf("db.b"), pieces.get(1).start());
    assertEquals(text.indexOf("use shop") + 8, pieces.get(3).end());
  }

  @Test
  void aFindWithItsCursorIsOneCommand() throws Exception {
    var request = Script.parse(
        "db.orders.find({ total: { $gt: 5 } }, { total: 1 }).sort({ total: -1 }).skip(10).limit(5)");
    assertEquals(Shape.CURSOR, request.shape());
    assertNull(request.database());
    assertEquals(
        Document.of(
            "find",
            "orders",
            "filter",
            Document.of("total", Document.of("$gt", 5)),
            "projection",
            Document.of("total", 1),
            "sort",
            Document.of("total", -1),
            "skip",
            10,
            "limit",
            5),
        request.command());
    assertTrue(request.reads());
  }

  @Test
  void collectionsAreNamedInEachWay() throws Exception {
    assertEquals(
        "system.users", Script.parse("db.system.users.find()").command().get("find"));
    assertEquals("a-b", Script.parse("db['a-b'].find()").command().get("find"));
    assertEquals("x y", Script.parse("db.getCollection('x y').find()").command().get("find"));
    var other = Script.parse("db.getSiblingDB('logs').events.find()");
    assertEquals("logs", other.database());
    assertEquals("events", other.command().get("find"));
  }

  @Test
  void writesAreCommandsThatDoNotRead() throws Exception {
    var insert = Script.parse("db.a.insertOne({ x: 1 })");
    assertEquals(Shape.WRITE, insert.shape());
    var documents = (List<?>) insert.command().get("documents");
    var inserted = assertInstanceOf(Document.class, documents.getFirst());
    assertEquals(List.of("_id", "x"), inserted.names());
    assertFalse(insert.reads());

    var update = Script.parse("db.a.updateMany({ x: 1 }, { $set: { y: 2 } }, { upsert: true })");
    assertEquals(
        Document.of(
            "update",
            "a",
            "updates",
            Document.array(Document.of(
                "q",
                Document.of("x", 1),
                "u",
                Document.of("$set", Document.of("y", 2)),
                "multi",
                true,
                "upsert",
                true))),
        update.command());
    assertFalse(update.reads());

    var delete = Script.parse("db.a.deleteOne({})");
    assertEquals(
        Document.of(
            "delete", "a", "deletes", Document.array(Document.of("q", Document.EMPTY, "limit", 1))),
        delete.command());
    for (var statement : List.of(
        "db.a.drop()",
        "db.a.createIndex({ x: 1 })",
        "db.a.findOneAndDelete({})",
        "db.a.findOneAndUpdate({}, { $inc: { n: 1 } })",
        "db.dropDatabase()",
        "db.createCollection('x')",
        "db.runCommand({ insert: 'a', documents: [] })",
        "db.adminCommand({ shutdown: 1 })",
        "db.a.aggregate([{ $match: {} }, { $out: 'b' }])",
        "db.a.aggregate([{ $facet: { x: [{ $merge: 'b' }] } }])",
        "db.a.aggregate([{ $lookup: { from: 'b', pipeline: [{ $out: 'c' }], as: 'd' } }])",
        "db.a.explain().updateOne({}, { $set: { x: 1 } })",
        "db.runCommand({ explain: { delete: 'a', deletes: [] } })")) {
      assertFalse(Script.parse(statement).reads(), statement);
    }
  }

  @Test
  void readsAreLetThrough() throws Exception {
    for (var statement : List.of(
        "db.a.find()",
        "db.a.findOne({ _id: 1 })",
        "db.a.aggregate([{ $group: { _id: '$x' } }])",
        "db.a.aggregate([{ $lookup: { from: 'b', pipeline: [{ $match: {} }], as: 'd' } }])",
        "db.a.countDocuments({ x: 1 })",
        "db.a.estimatedDocumentCount()",
        "db.a.distinct('x', { y: 1 })",
        "db.a.getIndexes()",
        "db.a.find().explain('executionStats')",
        "db.a.explain().find({})",
        "db.a.stats()",
        "db.stats()",
        "db.getCollectionNames()",
        "db.runCommand({ ping: 1 })",
        "db.runCommand('buildInfo')",
        "show dbs",
        "show collections",
        "use other")) {
      assertTrue(Script.parse(statement).reads(), statement);
    }
  }

  @Test
  void aCountIsAPipeline() throws Exception {
    var count = Script.parse("db.a.countDocuments({ x: 1 })");
    assertEquals(Shape.COUNT, count.shape());
    assertEquals("aggregate", count.command().first());
    var size = Script.parse("db.a.find({ x: 1 }).count()");
    assertEquals(Shape.ESTIMATE, size.shape());
    assertEquals(Document.of("count", "a", "query", Document.of("x", 1)), size.command());
  }

  @Test
  void useAndShow() throws Exception {
    assertEquals("shop", Script.parse("use shop").use());
    assertEquals("shop", Script.parse("use 'shop';").use());
    assertEquals(Shape.DATABASES, Script.parse("show dbs").shape());
    assertEquals("admin", Script.parse("show dbs").database());
  }

  @Test
  void whatIsNotAStatementSaysWhere() {
    assertWhere(0, "select * from a");
    assertWhere(5, "db.a.frob()");
    assertWhere(19, "db.a.aggregate([]).sort({})");
    assertWhere(10, "db.a.find(1)");
    assertWhere(12, "db.a.find() db.b.find()");
    assertWhere(19, "db.a.updateOne({}, { x: 1 })");
  }

  private static void assertWhere(int offset, String statement) {
    var e = assertThrows(SyntaxException.class, () -> Script.parse(statement));
    assertEquals(offset, e.offset(), e.getMessage());
  }
}
