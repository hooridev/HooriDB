package dev.hoori.db.core.file;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

  private static final List<Field> FIELDS = List.of(
      new Field("id", "bigint", Kind.INTEGER),
      new Field("price", "numeric", Kind.DECIMAL),
      new Field("ok", "boolean", Kind.BOOLEAN),
      new Field("doc", "jsonb", Kind.JSON),
      new Field("at", "timestamptz", Kind.DATE_TIME_ZONED),
      new Field("name", "text", Kind.TEXT));

  private static String written(Format.Json format, List<List<Object>> rows) throws IOException {
    var out = new ByteArrayOutputStream();
    var writer = RowWriter.of(format, out);
    writer.fields(FIELDS);
    for (var row : rows) {
      writer.row(row);
    }
    writer.finish();
    return out.toString(StandardCharsets.UTF_8);
  }

  private static final OffsetDateTime AT =
      OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);

  @Test
  void writesAnObjectPerRowInAnArrayWithJsonsOwnValues() throws IOException {
    var rows = List.of(
        Arrays.<Object>asList(1L, new BigDecimal("1E+2"), true, "{\"a\": [1, 2]}", AT, "x\"y"),
        Arrays.<Object>asList(2L, Double.NaN, null, "not json", null, null));
    assertEquals(
        "[\n"
            + "{\"id\":1,\"price\":100,\"ok\":true,\"doc\":{\"a\":[1,2]},"
            + "\"at\":\"2024-01-02T03:04:05+00:00\",\"name\":\"x\\\"y\"},\n"
            + "{\"id\":2,\"price\":\"NaN\",\"ok\":null,\"doc\":\"not json\",\"at\":null,"
            + "\"name\":null}\n"
            + "]\n",
        written(Format.Json.ARRAY, rows));
  }

  @Test
  void writesAnObjectPerLineAndAnEmptyArrayForNoRows() throws IOException {
    var rows = List.of(
        Arrays.<Object>asList(1L, null, false, "[1] [2]", null, "a"),
        Arrays.<Object>asList(2L, null, false, "3", null, "b"));
    assertEquals(
        "{\"id\":1,\"price\":null,\"ok\":false,\"doc\":\"[1] [2]\",\"at\":null,\"name\":\"a\"}\r\n"
            + "{\"id\":2,\"price\":null,\"ok\":false,\"doc\":3,\"at\":null,\"name\":\"b\"}\r\n",
        written(Format.Json.LINES.withLineEnd(LineEnd.CRLF), rows));
    assertEquals("[]\n", written(Format.Json.ARRAY, List.of()));
    assertEquals("", written(Format.Json.LINES, List.of()));
    assertEquals("jsonl", Format.Json.LINES.extension());
  }

  @Test
  void keepsTheNumbersOfAJsonValueAsTheyAreWritten() throws IOException {
    var doc = "{\"a\": 1.10, \"b\": [0.1234567890123456789012345, -0], \"d\": 1e400,"
        + " \"e\": 1E2, \"f\": 12345678901234567890123}";
    var rows = List.of(Arrays.<Object>asList(1L, null, null, doc, null, null));
    assertEquals(
        "{\"id\":1,\"price\":null,\"ok\":null,\"doc\":{\"a\":1.10,"
            + "\"b\":[0.1234567890123456789012345,-0],\"d\":1e400,\"e\":1E2,"
            + "\"f\":12345678901234567890123},\"at\":null,\"name\":null}\n",
        written(Format.Json.LINES, rows));
  }

  @Test
  void writesADecimalOfAnyScaleInFull() throws IOException {
    var small = new BigDecimal("1E-10000");
    var large = new BigDecimal("-12E+10000");
    var rows = List.of(
        Arrays.<Object>asList(1L, small, null, null, null, null),
        Arrays.<Object>asList(2L, large, null, null, null, null));
    var rest = ",\"ok\":null,\"doc\":null,\"at\":null,\"name\":null}\n";
    assertEquals(
        "{\"id\":1,\"price\":0." + "0".repeat(9999) + "1" + rest + "{\"id\":2,\"price\":-12"
            + "0".repeat(10000) + rest,
        written(Format.Json.LINES, rows));
  }

  @Test
  void keepsAJsonValueJsonBeyondTheLimitsOfJacksonsOwn() throws IOException {
    var digits = "9".repeat(1001);
    var name = "k".repeat(60_000);
    var deep = "[".repeat(1500) + "]".repeat(1500);
    var doc = "{\"n\": " + digits + ", \"" + name + "\": " + deep + "}";
    var rows = List.of(Arrays.<Object>asList(1L, null, null, doc, null, null));
    assertEquals(
        "{\"id\":1,\"price\":null,\"ok\":null,\"doc\":{\"n\":" + digits + ",\"" + name + "\":"
            + deep + "},\"at\":null,\"name\":null}\n",
        written(Format.Json.LINES, rows));
  }
}
