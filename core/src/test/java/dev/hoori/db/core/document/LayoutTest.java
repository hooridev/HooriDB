package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutTest {

  @Test
  void spacesAShortStatementOnOneLine() throws SyntaxException {
    assertEquals(
        "db.people.find({ name: 'Ada', born: { $lt: 1900 } }, { _id: 0 }).limit(5)",
        Layout.of("db . people.find( {name:'Ada',born:{$lt :1900}},{_id:0} ).limit( 5 )"));
    assertEquals("db.c.aggregate([])", Layout.of("db.c.aggregate([\n])"));
  }

  @Test
  void breaksWhatDoesNotFitOverLines() throws SyntaxException {
    assertEquals("""
        db.people.insertMany([
          { _id: 1, name: "Ada", tags: [ 'math' ] },
          {
            _id: ObjectId("5f1d7f3e1c9d440000a1b2c4"),
            name: 'Grace',
            born: ISODate('1906-12-09T00:00:00Z'),
            pattern: /^gr.ce$/i
          }
        ])""", Layout.of("""
            db.people.insertMany([{_id: 1, name: "Ada", \
            tags: ['math']}, {_id: ObjectId("5f1d7f3e1c9d440000a1b2c4"), name: 'Grace', \
            born: ISODate('1906-12-09T00:00:00Z'), pattern: /^gr.ce$/i}])"""));
  }

  @Test
  void keepsCommentsWhereTheyStand() throws SyntaxException {
    assertEquals("""
        // the old ones
        db.people.find({
          born: { $lt: 1900 }, // before
          /* and */ name: 'Ada'
        })""", Layout.of("""
            // the old ones
            db.people.find({born:{$lt:1900}, // before
              /* and */ name:'Ada'})"""));
  }

  @Test
  void putsEachStatementOnALineAndKeepsABlankLine() throws SyntaxException {
    assertEquals(
        """
        use shop;
        db.orders.countDocuments({})

        show collections""", Layout.of("use shop; db.orders.countDocuments( {} )\n\n\n  show collections"));
    assertEquals(
        "/* the count */\n\ndb.c.find() // all\ndb.c.find({ a: 1 }).sort({ a: -1 })\n// end",
        Layout.of(
            "/* the count */\n\n\ndb.c.find() // all\ndb.c\n  .find({a:1})\n  .sort({a:-1})\n// end"));
  }

  @Test
  void leavesALaidOutTextAsItIs() throws SyntaxException {
    for (var text : List.of(
        "db.c.find({\n  a: 1, /* x */\n  b: 2,\n  // last\n})",
        "db.c.find( // why\n{ a: 1 })",
        "db.c.updateMany({ status: 'A' }, {\n  $set: { note: 'a note long enough to break the line it is on' }\n})")) {
      assertEquals(text, Layout.of(text));
    }
  }

  @Test
  void keepsTheNameThatUseTakesAsItIsWritten() throws SyntaxException {
    assertEquals("use my-shop:1", Layout.of("use my-shop:1"));
    assertEquals("use foo[bar]\ndb.c.find({ a: 1 })", Layout.of("use foo[bar]\ndb.c.find({a:1})"));
  }

  /** A statement that makes a new value each time it is read, as an _id, formats all the same. */
  @Test
  void formatsWhatMakesANewIdEachTime() throws SyntaxException {
    assertEquals(
        "db.c.insertOne({ a: 1, at: ISODate(), id: ObjectId() })",
        Layout.of("db.c.insertOne({a:1,at:ISODate(),id:ObjectId()})"));
  }

  @Test
  void leavesStringsWithSpacesAndBreaksAsTheyAre() throws SyntaxException {
    var text = "db.c.find({ a: '  x , y : z  ', b: \"// not /* a */ comment\" })";
    assertEquals(text, Layout.of(text));
  }

  @Test
  void refusesAStatementThatDoesNotParse() {
    var e = assertThrows(
        SyntaxException.class,
        () -> Layout.of("db.c.find({ a: 1 })\ndb.c.find({ a: 1, })\n" + "db.c.find({ a: })"));
    assertEquals(56, e.offset());
    var open = assertThrows(SyntaxException.class, () -> Layout.of("db.c.find({ a: 'x"));
    assertEquals(15, open.offset());
  }
}
