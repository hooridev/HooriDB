package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShellTest {

  private static final Bson.ObjectId ID = new Bson.ObjectId("64b7f0c2a1b2c3d4e5f60718");

  @Test
  void whatIsWrittenReadsBackAsTheSameValue() throws Exception {
    var document = Document.of(
        "_id",
        ID,
        "name",
        "Ada's \"notes\"\n",
        "age",
        36,
        "big",
        9_000_000_000L,
        "ratio",
        1.0,
        "small",
        1.5e-20,
        "price",
        new Bson.Decimal128("19.90"),
        "born",
        Instant.parse("1815-12-10T00:00:00Z"),
        "tags",
        Document.array("a", null, 2),
        "nested",
        Document.of("deep", Document.of("x", List.of())),
        "empty",
        Document.EMPTY,
        "uuid",
        Bson.Binary.of(UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962")),
        "bin",
        new Bson.Binary(0, new byte[] {1, 2, 3}),
        "ts",
        new Bson.Timestamp(1700000000L, 3),
        "re",
        new Bson.Regex("a/b.*", "im"),
        "min",
        Bson.Special.MIN_KEY,
        "max",
        Bson.Special.MAX_KEY,
        "nothing",
        null,
        "weird key",
        true,
        "nan",
        Double.NaN,
        "negative",
        -0.0);
    assertEquals(document, Shell.parse(Shell.compact(document)));
    assertEquals(document, Shell.parse(Shell.pretty(document)));
  }

  @Test
  void aDocumentIsWrittenAsTheShellDoes() {
    var document = Document.of("_id", ID, "n", 1, "l", 5L, "s", "x", "a b", List.of());
    assertEquals(
        "{ _id: ObjectId('64b7f0c2a1b2c3d4e5f60718'), n: 1, l: Long('5'), s: 'x', 'a b': [] }",
        Shell.compact(document));
    assertEquals(
        "ISODate('2024-01-02T03:04:05.006Z')",
        Shell.scalar(Instant.parse("2024-01-02T03:04:05.006Z")));
  }

  @Test
  void aLongDocumentIsWrittenOverLines() {
    var document = Document.of(
        "first", "a".repeat(40), "second", "b".repeat(40), "inner", Document.of("x", 1));
    assertEquals("""
        {
          first: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          second: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
          inner: { x: 1 }
        }""", Shell.pretty(document));
  }

  @Test
  void whatPeopleWriteInTheShellIsRead() throws Exception {
    var read = Shell.document("""
        {
          // a comment
          a: NumberLong(5), b: NumberLong("6"), c: NumberInt('7'), "d": 'x', /* another */
          e: new Date('2024-01-02'), f: ISODate("2024-01-02T03:04:05+01:00"),
          g: /x[/]y/gi, h: ObjectId('64B7F0C2A1B2C3D4E5F60718'), i: NumberDecimal('1.5'),
          j: -Infinity, k: .5, l: 1e3, m: 0x10, n: BinData(0, 'AQID'), o: MinKey,
          p: Timestamp(5, 6), q: 12345678901,
        }""");
    assertEquals(5L, read.get("a"));
    assertEquals(6L, read.get("b"));
    assertEquals(7, read.get("c"));
    assertEquals("x", read.get("d"));
    assertEquals(Instant.parse("2024-01-02T00:00:00Z"), read.get("e"));
    assertEquals(Instant.parse("2024-01-02T02:04:05Z"), read.get("f"));
    assertEquals(new Bson.Regex("x[/]y", "gi"), read.get("g"));
    assertEquals(ID, read.get("h"));
    assertEquals(new Bson.Decimal128("1.5"), read.get("i"));
    assertEquals(Double.NEGATIVE_INFINITY, read.get("j"));
    assertEquals(0.5, read.get("k"));
    assertEquals(1000.0, read.get("l"));
    assertEquals(16, read.get("m"));
    assertEquals(new Bson.Binary(0, new byte[] {1, 2, 3}), read.get("n"));
    assertEquals(Bson.Special.MIN_KEY, read.get("o"));
    assertEquals(new Bson.Timestamp(5, 6), read.get("p"));
    assertEquals(12345678901L, read.get("q"));
  }

  @Test
  void aNewObjectIdIsMadeEachTime() throws Exception {
    long before = Instant.now().getEpochSecond();
    var one = assertInstanceOf(Bson.ObjectId.class, Shell.parse("ObjectId()"));
    var two = assertInstanceOf(Bson.ObjectId.class, Shell.parse("new ObjectId()"));
    long after = Instant.now().getEpochSecond();
    // Not ordered: the counter may wrap between them.
    assertNotEquals(one, two);
    for (var id : List.of(one, two)) {
      long at = id.time().getEpochSecond();
      assertTrue(before <= at && at <= after, id.toString());
    }
  }

  @Test
  void anArrayIsCopiedWholeAndHoldsOnlyValuesOfDocuments() {
    var inner = new java.util.ArrayList<Object>(List.of(1));
    var outer = new java.util.ArrayList<Object>(List.of(inner));
    var document = Document.of("a", outer);
    inner.add(2);
    outer.add(3);
    assertEquals(Document.of("a", List.of(List.of(1))), document);
    var array = (List<?>) document.get("a");
    assertThrows(UnsupportedOperationException.class, array::clear);
    var notAValue = List.of(List.of(new Object()));
    assertThrows(IllegalArgumentException.class, () -> Document.array(notAValue));
    assertEquals(Arrays.asList(1, null), Document.array(Arrays.asList(1, null)));
  }

  @Test
  void codeWithAScopeIsATypeOfItsOwn() {
    assertEquals("javascript", Bson.type(new Bson.Code("x", null)));
    assertEquals("javascriptWithScope", Bson.type(new Bson.Code("x", Document.EMPTY)));
  }

  @Test
  void aNameGivenTwiceKeepsItsPlaceAndItsLastValue() throws Exception {
    // As in JavaScript: what the server is sent has each name once.
    assertEquals(Document.of("a", 2, "b", 1), Shell.parse("{ a: 1, b: 1, a: 2 }"));
  }

  @Test
  void aPatternTheLiteralCannotHoldIsWrittenAsAConstructor() throws Exception {
    for (var pattern : List.of("a\\/b", "", "line\nbreak", "end\\")) {
      var regex = new Bson.Regex(pattern, "i");
      var written = Shell.compact(regex);
      assertTrue(written.startsWith("BSONRegExp("), written);
      assertEquals(regex, Shell.parse(written), written);
    }
    assertEquals("/a\\/b[\\/]/", Shell.compact(new Bson.Regex("a/b[/]", "")));
    // An escaped backslash before the closing slash stays one, and ends nothing.
    assertEquals(new Bson.Regex("a\\\\", "i"), Shell.parse("/a\\\\/i"));
  }

  @Test
  void wrongArgumentsOfConstructorsAreErrorsAtTheirPlace() {
    assertOffset(5, "{ a: 0x10000000000000000 }");
    assertOffset(5, "{ a: BinData() }");
    assertOffset(13, "{ a: BinData(0) }");
    assertOffset(10, "{ a: Code('return 1', 42) }");
  }

  @Test
  void anErrorSaysWhere() {
    assertOffset(7, "{ a: 1 b: 2 }");
    assertOffset(5, "{ a: 'x }");
    assertOffset(5, "{ a: Foo(1) }");
    assertOffset(14, "{ a: ObjectId('12') }");
    assertOffset(0, "[1, 2");
    assertOffset(9, "{ a: 1 } x");
    // null where a constructor needs a number or a date, not a NullPointerException
    for (var name : List.of("NumberLong", "NumberInt", "NumberDecimal", "ISODate", "new Date")) {
      assertOffset(name.length() + 1, name + "(null)");
    }
  }

  private static void assertOffset(int offset, String text) {
    var e = assertThrows(SyntaxException.class, () -> Shell.parse(text));
    if (offset != e.offset() && !(offset == 0 && e.offset() == text.length())) {
      assertEquals(offset, e.offset(), e.getMessage());
    }
  }
}
