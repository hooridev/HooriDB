package dev.hoori.db.core.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.data.Inserts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportTest {

  @TempDir
  Path dir;

  @Test
  void previewsTheFirstRowsAsTheFormatReadsThem() throws Exception {
    var file = dir.resolve("people.csv");
    Files.writeString(file, "id;name\n1;Ann\n2;\n3;\"Bo; b\"\n4;Cy\n");
    var format = Format.Delimited.CSV.withDelimiter(';');
    var preview = Import.preview(file, format, 2);
    assertEquals(List.of("id", "name"), preview.names());
    assertEquals(List.of(List.of("1", "Ann"), Arrays.asList("2", null)), preview.rows());

    var plain = Import.preview(file, format.withHeader(false), 10);
    assertEquals(List.of("Column 1", "Column 2"), plain.names());
    assertEquals(5, plain.rows().size());
    assertEquals(List.of("3", "Bo; b"), plain.rows().get(3));
  }

  @Test
  void readsNoRowPastThoseItPreviews() throws Exception {
    var file = dir.resolve("people.csv");
    // The quoted value of line 4 does not end, which the rows before it do not show.
    Files.writeString(file, "id,name\n1,Ann\n2,Bo\n3,\"Cy\n");
    var preview = Import.preview(file, Format.Delimited.CSV, 2);
    assertEquals(List.of(List.of("1", "Ann"), List.of("2", "Bo")), preview.rows());
    assertEquals(List.of(), Import.preview(file, Format.Delimited.CSV, 0).rows());
    assertThrows(IOException.class, () -> Import.preview(file, Format.Delimited.CSV, 3));
  }

  @Test
  void givesTheRowsAfterTheHeaderWithTheColumnsAskedFor() throws Exception {
    var file = dir.resolve("people.csv");
    Files.writeString(file, "id,name,note\n1,Ann,x\n2,Bo\n3,\"Cy\",\n");
    var read = new ArrayList<Inserts.Row>();
    try (var rows = Import.open(file, Format.Delimited.CSV, List.of(1, 0))) {
      for (var row = rows.next(); row != null; row = rows.next()) {
        read.add(row);
      }
    }
    assertEquals(3, read.size());
    assertEquals(new Inserts.Row.Values(2, List.of("Ann", "1")), read.get(0));
    assertEquals(
        new Inserts.Row.Unreadable(3, "It has 2 values where 3 values come before"), read.get(1));
    var last = (Inserts.Row.Values) read.get(2);
    assertEquals(List.of("Cy", "3"), last.values());

    try (var rows = Import.open(file, Format.Delimited.CSV.withHeader(false), List.of(2))) {
      var first = (Inserts.Row.Values) rows.next();
      assertEquals(List.of("note"), first.values());
      assertEquals(1, first.line());
      rows.next();
      assertEquals(
          new Inserts.Row.Unreadable(3, "It has 2 values where 3 values come before"), rows.next());
      assertNull(((Inserts.Row.Values) rows.next()).values().getFirst());
      assertNull(rows.next());
    }
  }
}
