package dev.hoori.db.core.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DelimitedTest {

  private static final List<Field> FIELDS =
      List.of(new Field("id", "integer", Kind.INTEGER), new Field("name, full", "text", Kind.TEXT));

  private static String written(Format.Delimited format, List<List<Object>> rows)
      throws IOException {
    return new String(bytes(format, FIELDS, rows), format.encoding().charset());
  }

  private static byte[] bytes(Format.Delimited format, List<Field> fields, List<List<Object>> rows)
      throws IOException {
    var out = new ByteArrayOutputStream();
    var writer = RowWriter.of(format, out);
    writer.fields(fields);
    for (var row : rows) {
      writer.row(row);
    }
    writer.finish();
    return out.toByteArray();
  }

  private static List<DelimitedReader.Record> read(Format.Delimited format, byte[] bytes)
      throws IOException {
    var records = new ArrayList<DelimitedReader.Record>();
    try (var reader = new DelimitedReader(format, new ByteArrayInputStream(bytes))) {
      for (var r = reader.next(); r != null; r = reader.next()) {
        records.add(r);
      }
    }
    return records;
  }

  private static List<Object> row(Object... values) {
    return Arrays.asList(values);
  }

  @Test
  void quotesWhatMustBeAndWritesNullAsItsText() throws IOException {
    var rows = List.of(
        row(1L, "plain"),
        row(2L, "a, b"),
        row(3L, "say \"hi\""),
        row(4L, "two\nlines"),
        row(5L, ""),
        row(null, null));
    assertEquals(
        "id,\"name, full\"\r\n1,plain\r\n2,\"a, b\"\r\n3,\"say \"\"hi\"\"\"\r\n"
            + "4,\"two\nlines\"\r\n5,\"\"\r\n,\r\n",
        written(Format.Delimited.CSV, rows));
  }

  @Test
  void quotesEveryValueButNullWhereAskedAndTakesOtherDelimiters() throws IOException {
    var format = Format.Delimited.TSV
        .withQuoting(Format.Quoting.ALL)
        .withHeader(false)
        .withNullText("\\N")
        .withLineEnd(LineEnd.LF);
    assertEquals(
        "\"1\"\t\"a\tb\"\n\\N\t\"\\N\"\n",
        written(format, List.of(row(1L, "a\tb"), row(null, "\\N"))));
    assertEquals("tsv", format.extension());
    assertEquals("csv", Format.Delimited.CSV.withDelimiter(';').extension());
  }

  @Test
  void readsBackWhatItWroteTellingNullFromAnEmptyText() throws IOException {
    for (var format : List.of(
        Format.Delimited.CSV,
        Format.Delimited.TSV.withNullText("NULL").withLineEnd(LineEnd.LF),
        Format.Delimited.CSV.withQuote('\'').withQuoting(Format.Quoting.ALL))) {
      var rows = List.of(
          row(1L, "a, b\r\nc"),
          row(2L, "it's \"so\""),
          row(3L, ""),
          row(4L, "NULL"),
          row(5L, null));
      var read = read(format, bytes(format, FIELDS, rows));
      assertEquals(List.of("id", "name, full"), read.get(0).values(), format.toString());
      assertEquals(List.of("1", "a, b\r\nc"), read.get(1).values());
      assertEquals(List.of("2", "it's \"so\""), read.get(2).values());
      assertEquals(List.of("3", ""), read.get(3).values());
      assertEquals(List.of("4", "NULL"), read.get(4).values());
      assertEquals(Arrays.asList("5", null), read.get(5).values());
    }
  }

  @Test
  void readsBackTheNullsOfAFileOfOneColumnAsTheEmptyLinesTheyAre() throws IOException {
    var name = List.of(new Field("name", "text", Kind.TEXT));
    var rows =
        List.of(row((Object) null), row("a"), row((Object) null), row(""), row((Object) null));
    for (var format : List.of(
        Format.Delimited.CSV,
        Format.Delimited.CSV.withHeader(false).withLineEnd(LineEnd.LF),
        Format.Delimited.TSV.withNullText("\\N"))) {
      var read = read(format, bytes(format, name, rows));
      var values = read.stream().map(DelimitedReader.Record::values).toList();
      var header = format.header() ? List.of(List.of("name")) : List.<List<String>>of();
      var expected = new ArrayList<List<String>>(header);
      rows.forEach(r -> expected.add(Arrays.asList(Texts.of(r.getFirst()))));
      assertEquals(expected, values, format.toString());
      for (int i = 0; i < read.size(); i++) {
        assertEquals(i + 1, read.get(i).line(), format.toString());
      }
    }
    // An empty line is the empty value of a file of one column, but before its header.
    assertEquals(
        List.of(List.of("x"), List.of(""), List.of("y"), List.of("")),
        read(Format.Delimited.TSV.withNullText("\\N").withHeader(false), utf8("x\n\ny\n\n"))
            .stream()
            .map(DelimitedReader.Record::values)
            .toList());
    assertEquals(
        List.of(
            new DelimitedReader.Record(2, List.of("name")),
            new DelimitedReader.Record(3, Arrays.asList((String) null)),
            new DelimitedReader.Record(4, List.of("b"))),
        read(Format.Delimited.CSV, utf8("\nname\n\nb\n")));
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void writesAndSkipsTheMarkOfItsEncoding() throws IOException {
    var marked = Format.Delimited.CSV.withEncoding(Encoding.UTF_8_BOM);
    var bytes = bytes(marked, FIELDS, List.of(row(1L, "Grüße")));
    assertArrayEquals(
        new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'i'}, Arrays.copyOf(bytes, 4));
    // Read as UTF-8 without a mark, the mark is skipped all the same.
    var read = read(Format.Delimited.CSV, bytes);
    assertEquals(List.of("id", "name, full"), read.get(0).values());
    assertEquals(List.of("1", "Grüße"), read.get(1).values());

    var utf16 = Format.Delimited.CSV.withEncoding(Encoding.UTF_16LE);
    bytes = bytes(utf16, FIELDS, List.of(row(1L, "日本")));
    assertEquals((byte) 0xFF, bytes[0]);
    assertEquals((byte) 0xFE, bytes[1]);
    assertEquals(List.of("1", "日本"), read(utf16, bytes).get(1).values());
  }

  @Test
  void quotesAValueThatStartsWithAMarkSoThatItIsReadBackWhole() throws IOException {
    var name = List.of(new Field("name", "text", Kind.TEXT));
    var plain = Format.Delimited.CSV.withHeader(false);
    var rows = List.of(row("\uFEFFfirst"), row("\uFEFF"));
    assertEquals(
        "\"\uFEFFfirst\"\r\n\"\uFEFF\"\r\n",
        new String(bytes(plain, name, rows), StandardCharsets.UTF_8));
    for (var format : List.of(plain, Format.Delimited.CSV.withEncoding(Encoding.UTF_8_BOM))) {
      var values = read(format, bytes(format, name, rows)).stream()
          .map(DelimitedReader.Record::values)
          .toList();
      assertEquals(
          List.of(List.of("\uFEFFfirst"), List.of("\uFEFF")),
          format.header() ? values.subList(1, values.size()) : values,
          format.toString());
    }
  }

  @Test
  void failsARowWhoseCharactersTheEncodingCannotWrite() {
    var latin = Format.Delimited.CSV.withEncoding(Encoding.ISO_8859_1);
    var e = assertThrows(
        IOException.class, () -> written(latin, List.of(row(1L, "ok"), row(2L, "日本"))));
    assertEquals("Row 2 holds a character that ISO-8859-1 cannot write", e.getMessage());
  }

  @Test
  void readsEveryLineEndCountsLinesAndSkipsEmptyOnes() throws IOException {
    var text = "a,b\r\n\r\n1,\"x\ny\"\r2,z\n\n3,\"q\"\"\"tail\n";
    var read = read(Format.Delimited.CSV, text.getBytes(StandardCharsets.UTF_8));
    assertEquals(4, read.size());
    assertEquals(new DelimitedReader.Record(1, List.of("a", "b")), read.get(0));
    assertEquals(new DelimitedReader.Record(3, List.of("1", "x\ny")), read.get(1));
    assertEquals(new DelimitedReader.Record(5, List.of("2", "z")), read.get(2));
    assertEquals(new DelimitedReader.Record(7, List.of("3", "q\"tail")), read.get(3));
    var last = read(Format.Delimited.CSV, "1,".getBytes(StandardCharsets.UTF_8)).getFirst();
    assertEquals(1, last.line());
    assertEquals("1", last.values().get(0));
    assertNull(last.values().get(1));
  }

  @Test
  void failsAQuoteThatDoesNotEndAndBytesOfAnotherEncoding() {
    var open = assertThrows(
        IOException.class,
        () -> read(Format.Delimited.CSV, "a\n1,\"open\n2,x\n".getBytes(StandardCharsets.UTF_8)));
    assertEquals("The quoted value of line 2 does not end", open.getMessage());
    var latin = "a\nGr\u00fc\u00dfe\n".getBytes(StandardCharsets.ISO_8859_1);
    var wrong = assertThrows(IOException.class, () -> read(Format.Delimited.CSV, latin));
    assertTrue(wrong.getMessage().contains("no text of UTF-8"), wrong.getMessage());
  }

  @Test
  void refusesDelimitersAndNullTextsThatCannotDivideValues() {
    assertThrows(IllegalArgumentException.class, () -> Format.Delimited.CSV.withDelimiter('\n'));
    assertThrows(IllegalArgumentException.class, () -> Format.Delimited.CSV.withQuote(','));
    assertThrows(IllegalArgumentException.class, () -> Format.Delimited.CSV.withNullText("a,b"));
  }

  @Test
  void writesValuesAsTheServerGivesThemAndMomentsWithTheirOffset() {
    assertEquals("12", Texts.of(12L));
    assertEquals("18446744073709551615", Texts.of(new BigInteger("18446744073709551615")));
    assertEquals("1000", Texts.of(new BigDecimal("1E+3")));
    assertEquals("0.5", Texts.of(0.5));
    assertEquals("NaN", Texts.of(Double.NaN));
    assertEquals("true", Texts.of(true));
    assertEquals("2024-02-29", Texts.of(LocalDate.of(2024, 2, 29)));
    assertEquals("23:59:58.5", Texts.of(LocalTime.of(23, 59, 58, 500_000_000)));
    assertEquals("10:00:00+02:00", Texts.of(OffsetTime.of(10, 0, 0, 0, ZoneOffset.ofHours(2))));
    assertEquals("2024-03-31T02:30:00", Texts.of(LocalDateTime.of(2024, 3, 31, 2, 30)));
    assertEquals(
        "2024-03-31T02:30:00.123+00:00",
        Texts.of(OffsetDateTime.of(2024, 3, 31, 2, 30, 0, 123_000_000, ZoneOffset.UTC)));
    assertEquals("00ff10", Texts.of(new Binary(new byte[] {0, -1, 16}, 3)));
    assertEquals("0000-00-00", Texts.of("0000-00-00"));
    assertNull(Texts.of(null));
    assertThrows(IllegalArgumentException.class, () -> Texts.of(new Binary(new byte[1], 2)));
  }
}
