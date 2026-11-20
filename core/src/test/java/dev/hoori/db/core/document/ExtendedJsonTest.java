package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Rows;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExtendedJsonTest {

  @Test
  void eachTypeIsWrittenAsRelaxedExtendedJson() {
    var document = Document.of(
        "_id",
        Bson.ObjectId.of("5f1d7a3b9c1e4b2a8d6f0e12"),
        "s",
        "a \"b\"\n\u0001",
        "i",
        1,
        "l",
        9_000_000_000L,
        "d",
        1.5,
        "nan",
        Double.NaN,
        "dec",
        new Bson.Decimal128("19.90"),
        "date",
        Instant.parse("2024-05-01T12:00:00Z"),
        "old",
        Instant.parse("1815-12-10T00:00:00Z"),
        "uuid",
        Bson.Binary.of(UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962")),
        "ts",
        new Bson.Timestamp(1700000000L, 3),
        "re",
        new Bson.Regex("a.c", "i"),
        "code",
        new Bson.Code("x", Document.of("v", 1)),
        "min",
        Bson.Special.MIN_KEY,
        "list",
        Document.array(1, null, Document.EMPTY));
    assertEquals(
        "{\"_id\": {\"$oid\": \"5f1d7a3b9c1e4b2a8d6f0e12\"}, \"s\": \"a \\\"b\\\"\\n\\u0001\","
            + " \"i\": 1, \"l\": 9000000000, \"d\": 1.5, \"nan\": {\"$numberDouble\": \"NaN\"},"
            + " \"dec\": {\"$numberDecimal\": \"19.90\"},"
            + " \"date\": {\"$date\": \"2024-05-01T12:00:00.000Z\"},"
            + " \"old\": {\"$date\": {\"$numberLong\": \"-4861728000000\"}},"
            + " \"uuid\": {\"$binary\": {\"base64\": \"OyQRAeK7QlWMr0E2xWapYg==\", \"subType\":"
            + " \"04\"}}, \"ts\": {\"$timestamp\": {\"t\": 1700000000, \"i\": 3}},"
            + " \"re\": {\"$regularExpression\": {\"pattern\": \"a.c\", \"options\": \"i\"}},"
            + " \"code\": {\"$code\": \"x\", \"$scope\": {\"v\": 1}}, \"min\": {\"$minKey\": 1},"
            + " \"list\": [1, null, {}]}",
        ExtendedJson.of(document));
  }

  @Test
  void aDocumentOrAnArrayInTheGridIsJson() {
    var fields = Values.found(List.of(Document.of("a", Document.of("x", 1), "b", List.of(1))));
    assertEquals(Kind.JSON, fields.get(0).kind());
    assertEquals(Kind.JSON, fields.get(1).kind());
    assertEquals(
        List.of("{\"x\": 1}", "[1]"),
        Values.row(fields, Document.of("a", Document.of("x", 1), "b", List.of(1))));
  }

  @Test
  void aTextCutShortKeepsItsCharactersWhole() {
    // An emoji is two chars; one that would be cut in half is left out.
    var text = "a".repeat(Rows.LIMIT - 1) + "😀";
    var cut = assertInstanceOf(Cut.class, Values.value(text, Kind.TEXT));
    assertEquals(Rows.LIMIT - 1, cut.start().length());
  }
}
