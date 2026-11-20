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
  void aStatementEndsBeforeTheCommentsAfterIt() {
    var text = "db.a.find() // note; not an end\ndb.b.find({ x: /* c */ /[(]/ })\nuse shop// x";
    assertEquals(
        List.of("db.a.find()", "db.b.find({ x: /* c */ /[(]/ })", "use shop"),
        Script.split(text).stream().map(Script.Piece::text).toList());
  }

  /** Where the scans of the shell's syntax disagreed before they were one lexer. */
  @Test
  void aSlashAfterAnOperatorAndAStringNotClosedEndStatementsAsTheEditorShowsThem() {
    // A slash after an operator starts a regular expression: its quote starts no string.
    assertEquals(
        List.of("db.a.find({ x: a + /'/ })", "db.b.find()"),
        Script.split("db.a.find({ x: a + /'/ })\ndb.b.find()").stream()
            .map(Script.Piece::text)
            .toList());
    // A string not closed on its line ends before the line break, which ends the statement.
    assertEquals(
        List.of("use 'shop", "show dbs"),
        Script.split("use 'shop\nshow dbs").stream().map(Script.Piece::text).toList());
    assertWhere(4, "use 'shop");
    // A regular expression ends with its line, as in JavaScript, also after a backslash.
    assertWhere(15, "db.a.find({ x: /a\\\nb/ })");
    // A block comment over lines between the calls of a chain leaves it one statement.
    assertEquals(
        List.of("db.a.find({})\n/* a\n b */ .limit(1)"),
        Script.split("db.a.find({})\n/* a\n b */ .limit(1)").stream()
            .map(Script.Piece::text)
            .toList());
  }

  @Test
  void aUseNameEndsAtAComment() throws Exception {
    assertEquals("shop", Script.parse("use shop// note").use());
    assertEquals("shop", Script.parse("use shop/* note */").use());
    // A no-break space ends it, as JavaScript reads one as space.
    assertEquals("shop", Script.parse("use shop\u00a0").use());
    // A quote in an unquoted name stays in it, and the line still ends the statement.
    assertEquals(
        List.of("use shop'24", "show dbs"),
        Script.split("use shop'24\nshow dbs").stream().map(Script.Piece::text).toList());
    assertEquals("shop'24", Script.parse("use shop'24").use());
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
  void aCommandWithANameTwiceIsNoRead() {
    // What is checked must be what is sent: the server could take either pipeline.
    var twice = new Document(List.of(
        new Document.Entry("aggregate", "a"),
        new Document.Entry("pipeline", Document.array(Document.of("$match", Document.EMPTY))),
        new Document.Entry("pipeline", Document.array(Document.of("$out", "b"))),
        new Document.Entry("cursor", Document.EMPTY)));
    assertFalse(Commands.reads(twice));
    var deep = Document.of(
        "find",
        "a",
        "filter",
        new Document(List.of(new Document.Entry("x", 1), new Document.Entry("x", 2))));
    assertFalse(Commands.reads(deep));
    assertTrue(Commands.reads(Document.of("find", "a", "filter", Document.of("x", 1))));
  }

  @Test
  void aLiteralIsNoStage() throws Exception {
    assertTrue(Script.parse("db.a.aggregate([{ $project: { x: { $literal: { $out: 'b' } } } }])")
        .reads());
    assertFalse(Script.parse("db.a.aggregate([{ $project: { $out: 'b' } }])").reads());
  }

  @Test
  void aWriteKeepsItsOptionsAndRefusesThoseItDoesNotSend() throws Exception {
    var delete = Script.parse("db.a.deleteMany({ x: 1 }, { hint: 'x_1', comment: 'c' })");
    assertEquals(
        Document.of(
            "delete",
            "a",
            "comment",
            "c",
            "deletes",
            Document.array(Document.of("q", Document.of("x", 1), "limit", 0, "hint", "x_1"))),
        delete.command());
    var update = Script.parse("db.a.updateOne({}, { $set: { x: 1 } }, { let: { v: 1 } })");
    assertEquals(Document.of("v", 1), update.command().get("let"));
    assertWhere(26, "db.a.deleteMany({ x: 1 }, { justOne: true })");
    assertWhere(39, "db.a.updateOne({}, { $set: { x: 1 } }, { bogus: 1 })");
  }

  @Test
  void optionsGoWhereTheServerTakesThemAndReplaceNothing() throws Exception {
    var insert = Script.parse("db.a.insertOne({ _id: 1 }, { writeConcern: { w: 'majority' } })");
    assertEquals(Document.of("w", "majority"), insert.command().get("writeConcern"));
    var one = Script.parse("db.a.findOne({}, {}, { sort: { total: -1 } })").command();
    assertEquals(Document.of("total", -1), one.get("sort"));
    assertEquals(1, one.get("limit"));
    var count = Script.parse("db.a.countDocuments({}, { hint: '_id_', maxTimeMS: 100 })");
    assertEquals("_id_", count.command().get("hint"));
    assertEquals(100, count.command().get("maxTimeMS"));
    var indexes =
        Script.parse("db.a.createIndexes([{ email: 1 }], { unique: true })").command();
    var index = (Document) ((List<?>) indexes.get("indexes")).getFirst();
    assertEquals(true, index.get("unique"));
    var view = Script.parse("db.createView('v', 'a', [], { collation: { locale: 'en' } })");
    assertEquals(Document.of("locale", "en"), view.command().get("collation"));
    var aggregate = Script.parse("db.aggregate([{ $currentOp: {} }], { maxTimeMS: 5 })");
    assertEquals(5, aggregate.command().get("maxTimeMS"));
    // What the call says it is stays what it is.
    assertWhere(46, "db.a.findOneAndUpdate({}, { $set: { x: 1 } }, { query: { y: 1 } })");
    assertWhere(19, "db.a.aggregate([], { pipeline: [{ $out: 'b' }] })");
  }

  @Test
  void roundThreeOfTheShellsEdges() throws Exception {
    assertEquals(
        List.of("db.a.find({})\n/* cap */ .limit(1)"),
        Script.split("db.a.find({})\n/* cap */ .limit(1)").stream()
            .map(Script.Piece::text)
            .toList());
    assertWhere(5, "db.a.insertMany({ x: 1 })");
    assertEquals(
        1, ((List<?>) Script.parse("db.a.insert({ x: 1 })").command().get("documents")).size());
    assertWhere(5, "db.a.dropIndex(['a_1', 'b_1'])");
    assertEquals("a_1", Script.parse("db.a.dropIndex('a_1')").command().get("index"));
    assertEquals(
        2147483648L, Script.parse("db.a.find().limit(-2147483648)").command().get("limit"));
  }

  @Test
  void roundFourOfTheShellsEdges() throws Exception {
    assertEquals(
        Shape.REPLY, Script.parse("db.a.aggregate([], { explain: true })").shape());
    assertEquals(Shape.CURSOR, Script.parse("db.a.aggregate([])").shape());
    var batched = Script.parse("db.aggregate([{ $currentOp: {} }], { cursor: { batchSize: 1 } })");
    assertEquals(Document.of("batchSize", 1), batched.command().get("cursor"));
    assertEquals(
        5,
        Script.parse("db.a.estimatedDocumentCount({ maxTimeMS: 5 })").command().get("maxTimeMS"));
    var counted = Script.parse(
            "db.a.find({ n: 'ada' }).collation({ locale: 'en', strength: 2 }).hint('n_1').itcount()")
        .command();
    assertEquals(Document.of("locale", "en", "strength", 2), counted.get("collation"));
    assertEquals("n_1", counted.get("hint"));
  }

  @Test
  void anUnclosedCommentStaysInItsStatement() throws Exception {
    var text = "db.a.insertOne({ x: 1 }) /* not closed";
    var pieces = Script.split(text);
    assertEquals(text, pieces.getFirst().text());
    assertThrows(SyntaxException.class, () -> Script.parse(pieces.getFirst().text()));
  }

  @Test
  void theShellsMethodsDoWhatTheShellDoes() throws Exception {
    assertWhere(26, "db.a.findOneAndUpdate({}, { x: 1 })");
    assertWhere(27, "db.a.findOneAndReplace({}, { $set: { x: 1 } })");
    assertWhere(5, "db.a.dropIndex()");
    assertWhere(5, "db.a.dropIndex('*')");
    assertEquals("*", Script.parse("db.a.dropIndexes()").command().get("index"));

    var batch = Script.parse("db.a.find().limit(-2)").command();
    assertEquals(2, batch.get("limit"));
    assertEquals(true, batch.get("singleBatch"));

    var counted = Script.parse("db.a.find({ x: 1 }).skip(1).limit(5).itcount()");
    assertEquals(Shape.ESTIMATE, counted.shape());
    assertEquals(
        Document.of("count", "a", "query", Document.of("x", 1), "skip", 1, "limit", 5),
        counted.command());

    var name = Script.parse("db.getName()");
    assertEquals(Shape.NAME, name.shape());
    assertNull(name.command());
    assertTrue(name.reads());
    assertEquals("logs", Script.parse("db.getSiblingDB('logs').getName()").database());
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
