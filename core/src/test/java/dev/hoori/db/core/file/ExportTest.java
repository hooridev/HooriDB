package dev.hoori.db.core.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportTest {

  @TempDir
  Path dir;

  private static final List<Field> FIELDS = List.of(new Field("id", "integer", Kind.INTEGER));

  private List<Path> files() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.sorted().toList();
    }
  }

  @Test
  void replacesTheFileOnceItIsWhole() throws Exception {
    var file = dir.resolve("t.csv");
    Files.writeString(file, "old");
    var rows = Export.write(file, Format.Delimited.CSV, each -> {
      each.fields(FIELDS);
      each.row(List.of(1L));
      each.row(List.of(2L));
      return 2;
    });
    assertEquals(2, rows);
    assertEquals("id\r\n1\r\n2\r\n", Files.readString(file));
    assertEquals(List.of(file), files());
  }

  @Test
  void makesTheFileWithThePermissionsOfAnyNewFile() throws Exception {
    assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    var made = Files.createFile(dir.resolve("made"));
    var file = dir.resolve("t.csv");
    Export.write(file, Format.Delimited.CSV, each -> {
      each.fields(FIELDS);
      return 0;
    });
    assertEquals(Files.getPosixFilePermissions(made), Files.getPosixFilePermissions(file));
  }

  @Test
  void leavesTheFolderAsItWasWhereTheReadingFails() throws Exception {
    var file = dir.resolve("t.json");
    Files.writeString(file, "old");
    var failure = QueryException.of(QueryException.Failure.CANCELLED, "");
    var thrown = assertThrows(
        QueryException.class,
        () -> Export.write(file, Format.Json.ARRAY, e -> {
          e.fields(FIELDS);
          e.row(List.of(1L));
          throw failure;
        }));
    assertEquals(failure, thrown);
    assertEquals("old", Files.readString(file));
    assertEquals(List.of(file), files());
    assertThrows(
        IOException.class,
        () -> Export.write(dir.resolve("new.csv"), Format.Json.LINES, e -> {
          throw new IOException("disk full");
        }));
    assertEquals(List.of(file), files());
  }

  @Test
  void namesFilesAsFileSystemsTakeThemOncePerFolder() {
    var db = new Database("db", false);
    var tables = Stream.of("orders", "Orders", "a/b:c?", "con", "dots...", "orders", "a .", " . ")
        .map(name -> new Table(db, name, Table.Kind.TABLE))
        .toList();
    assertEquals(
        List.of(
            "orders.csv",
            "Orders-2.csv",
            "a_b_c_.csv",
            "con_.csv",
            "dots.csv",
            "orders-3.csv",
            "a.csv",
            "_.csv"),
        Export.fileNames(tables, Format.Delimited.CSV));
  }
}
