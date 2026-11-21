package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatementReaderTest {

  // Windows from one char on, so that a statement, a delimiter or a line of rows is cut at every
  // place, and the reader's own.
  private static final int[] CHUNKS = {1, 2, 3, 5, 7, 11, 16, 17, 31, 64, 1 << 16};

  // What a step is, with the rows of a copy as read.
  private record Seen(String kind, String text, long line, long offset, String rows) {}

  private static List<Seen> read(String text, Provider provider, int chunk) throws IOException {
    // A reader that gives few chars at a time, as a slow disk may.
    Reader in = new StringReader(text) {
      @Override
      public int read(char[] into, int from, int length) throws IOException {
        return super.read(into, from, Math.min(length, 3));
      }
    };
    return read(new StatementReader(in, provider, text.length(), chunk));
  }

  private static List<Seen> read(StatementReader reader) throws IOException {
    var seen = new ArrayList<Seen>();
    for (var next = reader.next(); next.isPresent(); next = reader.next()) {
      seen.add(
          switch (next.get()) {
            case StatementReader.Sql s -> new Seen("sql", s.sql(), s.line(), s.offset(), null);
            case StatementReader.Copy c ->
              new Seen(
                  c.followed() ? "copy followed" : "copy",
                  c.sql(),
                  c.line(),
                  c.offset(),
                  all(reader.rows()));
            case StatementReader.Command c ->
              new Seen(c.name(), c.text(), c.line(), c.offset(), null);
          });
    }
    return seen;
  }

  private static String all(Reader rows) throws IOException {
    var text = new StringBuilder();
    var into = new char[5];
    for (int n = rows.read(into); n >= 0; n = rows.read(into)) {
      text.append(into, 0, n);
    }
    return text.toString();
  }

  // The step as the whole text has it, at the first place the text is found from the offset on.
  private static Seen at(String text, String kind, String step, int from, String rows) {
    int start = text.indexOf(step, from);
    assertTrue(start >= 0, step);
    var before = text.substring(0, start);
    return new Seen(
        kind,
        step,
        1 + before.chars().filter(c -> c == '\n').count(),
        before.getBytes(StandardCharsets.UTF_8).length,
        rows);
  }

  private static void assertRead(Provider provider, String text, List<Seen> expected)
      throws IOException {
    for (int chunk : CHUNKS) {
      assertEquals(expected, read(text, provider, chunk), provider + ", chunk " + chunk);
    }
  }

  @Test
  void readsTheStatementsTheConsoleSplitsInEveryWindow() throws IOException {
    var random = new Random(99);
    String[] pieces = {
      "SELECT 1",
      ";",
      "\n",
      " ",
      "'a;b'",
      "\"x;y\"",
      "-- c;\n",
      "/* d; */",
      "$$ e; $$",
      "$t$;$t$",
      "`q;`",
      "# h;\n",
      "E'\\';'",
      "'\\';'",
      "Grüße",
      "😀",
      "/*!40101 SET x=1 */",
      "\r\n"
    };
    for (var provider : Provider.SQL) {
      for (int round = 0; round < 200; round++) {
        var text = new StringBuilder();
        for (int k = random.nextInt(20); k > 0; k--) {
          text.append(pieces[random.nextInt(pieces.length)]);
        }
        var whole = text.toString();
        var expected = new ArrayList<Seen>();
        for (var statement : Statements.split(whole, provider)) {
          var sql = whole.substring(statement.start(), statement.end());
          var before = whole.substring(0, statement.start());
          expected.add(new Seen(
              "sql",
              sql,
              1 + before.chars().filter(c -> c == '\n').count(),
              before.getBytes(StandardCharsets.UTF_8).length,
              null));
        }
        // A backslash at a statement's start is psql's: those texts are read on their own below.
        if (provider == POSTGRESQL && expected.stream().anyMatch(s -> s.text().startsWith("\\"))) {
          continue;
        }
        assertRead(provider, whole, expected);
      }
    }
  }

  @Test
  void linesAndOffsetsCountFromTheFileStart() throws IOException {
    var text = "-- Grüße 😀\nSELECT 'ä';\n\n  SELECT 2;";
    assertRead(
        POSTGRESQL,
        text,
        List.of(at(text, "sql", "SELECT 'ä'", 0, null), at(text, "sql", "SELECT 2", 0, null)));
  }

  @Test
  void aDelimiterGoesOnFromWindowToWindow() throws IOException {
    var text = """
        /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
        /*M!999999\\- enable the sandbox mode */
        /*!40101 SET NAMES utf8mb4 */;
        DELIMITER ;;
        /*!50003 CREATE*/ /*!50017 DEFINER=`root`@`%`*/ /*!50003 TRIGGER t BEFORE INSERT ON a
        FOR EACH ROW BEGIN SET NEW.x = 1; SET NEW.y = 2; END */;;
        DELIMITER ;
        INSERT INTO a VALUES (1,'x;\\'y');
        """;
    for (var provider : List.of(MARIADB, MYSQL)) {
      assertRead(
          provider,
          text,
          List.of(
              at(
                  text,
                  "sql",
                  "/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */",
                  0,
                  null),
              at(
                  text,
                  "sql",
                  // MySQL has no comments for MariaDB alone, which are comments to it.
                  provider == MARIADB
                      ? "/*M!999999\\- enable the sandbox mode */\n/*!40101 SET NAMES utf8mb4 */"
                      : "/*!40101 SET NAMES utf8mb4 */",
                  0,
                  null),
              at(
                  text,
                  "sql",
                  text.substring(text.indexOf("/*!50003 CREATE"), text.indexOf(";;\nDELIMITER ;")),
                  0,
                  null),
              at(text, "sql", "INSERT INTO a VALUES (1,'x;\\'y')", 0, null)));
    }
  }

  @Test
  void theRowsOfACopyAreTheLinesUpToItsEnd() throws IOException {
    var text = """
        SET x = 1;
        COPY public.t (a, b) FROM stdin;
        1\tit's; not SQL
        2\t\\N
        3\ta \\. inside
        \\.
        SELECT 1;
        copy t from STDIN with (format csv);
        "x;",'y
        \\.\r
        SELECT 2
        """;
    var rows = "1\tit's; not SQL\n2\t\\N\n3\ta \\. inside\n";
    assertRead(
        POSTGRESQL,
        text,
        List.of(
            at(text, "sql", "SET x = 1", 0, null),
            at(text, "copy", "COPY public.t (a, b) FROM stdin", 0, rows),
            at(text, "sql", "SELECT 1", 0, null),
            at(text, "copy", "copy t from STDIN with (format csv)", 0, "\"x;\",'y\n"),
            at(text, "sql", "SELECT 2", 0, null)));
  }

  @Test
  void aCopyAtTheEndOfTheFileTakesTheRestAsItsRows() throws IOException {
    var text = "COPY t FROM stdin;\n1\n2";
    assertRead(POSTGRESQL, text, List.of(at(text, "copy", "COPY t FROM stdin", 0, "1\n2")));
    assertRead(
        POSTGRESQL,
        "COPY t FROM stdin;",
        List.of(at("COPY t FROM stdin;", "copy", "COPY t FROM stdin", 0, "")));
  }

  @Test
  void whatFollowsACopyOnItsLineIsTold() throws IOException {
    var text = """
        COPY a FROM stdin; -- the rows /* follow */
        1
        \\.
        COPY b FROM stdin; SELECT 1;
        2
        \\.
        SELECT 3;""";
    assertRead(
        POSTGRESQL,
        text,
        List.of(
            at(text, "copy", "COPY a FROM stdin", 0, "1\n"),
            at(text, "copy followed", "COPY b FROM stdin", 0, "2\n"),
            at(text, "sql", "SELECT 3", 0, null)));
  }

  @Test
  void rowsNotReadAreSkipped() throws IOException {
    var text = "COPY t FROM stdin;\n1\n2\n\\.\nSELECT 1;";
    for (int chunk : CHUNKS) {
      var reader = new StatementReader(new StringReader(text), POSTGRESQL, -1, chunk);
      assertInstanceOf(StatementReader.Copy.class, reader.next().orElseThrow());
      assertEquals(new StatementReader.Sql("SELECT 1", 5, 26), reader.next().orElseThrow());
      assertTrue(reader.next().isEmpty());
    }
  }

  @Test
  void copyIsOnlyPostgreSqlsAndOnlyFromTheClient() throws IOException {
    var text = """
        COPY t FROM '/tmp/x';
        COPY t TO stdout;
        COPY (SELECT 'FROM STDIN') TO stdout;
        COPY t FROM '/tmp/x' WHERE id IS DISTINCT FROM stdin;""";
    assertRead(
        POSTGRESQL,
        text,
        List.of(
            at(text, "sql", "COPY t FROM '/tmp/x'", 0, null),
            at(text, "sql", "COPY t TO stdout", 0, null),
            at(text, "sql", "COPY (SELECT 'FROM STDIN') TO stdout", 0, null),
            at(text, "sql", "COPY t FROM '/tmp/x' WHERE id IS DISTINCT FROM stdin", 0, null)));
    var mysql = "COPY t FROM stdin;\n1\n";
    assertRead(
        MYSQL,
        mysql,
        List.of(at(mysql, "sql", "COPY t FROM stdin", 0, null), at(mysql, "sql", "1", 0, null)));
  }

  @Test
  void psqlsCommandsAreTheirLines() throws IOException {
    var text = """
        \\restrict abc123
        SELECT 1;
        -- before
        \\connect -reuse-previous=on "dbname='x'"
        \\c y\r
        SELECT '\\connect' \\gexec
        \\.
        """;
    assertRead(
        POSTGRESQL,
        text,
        List.of(
            at(text, "restrict", "\\restrict abc123", 0, null),
            at(text, "sql", "SELECT 1", 0, null),
            at(text, "connect", "\\connect -reuse-previous=on \"dbname='x'\"", 0, null),
            at(text, "c", "\\c y", 0, null),
            at(text, "sql", "SELECT '\\connect' \\gexec\n\\.", 0, null)));
    // MariaDB and MySQL have no such commands: a backslash is the server's to refuse.
    assertRead(MYSQL, "\\c y;", List.of(at("\\c y;", "sql", "\\c y", 0, null)));
  }

  @Test
  void aByteOrderMarkIsSkippedButCounted(@TempDir Path folder) throws IOException {
    var file = folder.resolve("bom.sql");
    Files.write(file, "\uFEFFSELECT 1;\nSELECT 2;".getBytes(StandardCharsets.UTF_8));
    try (var reader = StatementReader.open(file, POSTGRESQL)) {
      assertEquals(new StatementReader.Sql("SELECT 1", 1, 3), reader.next().orElseThrow());
      assertEquals(new StatementReader.Sql("SELECT 2", 2, 13), reader.next().orElseThrow());
      assertTrue(reader.next().isEmpty());
      assertEquals(22, reader.size());
      assertEquals(22, reader.offset());
    }
  }

  @Test
  void aFileThatIsNotUtf8FailsAtItsLine(@TempDir Path folder) throws IOException {
    var file = folder.resolve("latin1.sql");
    Files.write(file, "SELECT 1;\nSELECT 'Gr\u00fc\u00dfe';".getBytes(StandardCharsets.ISO_8859_1));
    try (var reader = StatementReader.open(file, POSTGRESQL)) {
      var failure = assertThrows(IOException.class, reader::next);
      assertEquals("The file is not UTF-8 on line 2", failure.getMessage());
    }
    // In windows of its own, the statements before it come first.
    var in = new StatementReader.Utf8(
        new ByteArrayInputStream("SELECT 1;\n-- a comment, of some length\nSELECT '\u00fc';"
            .getBytes(StandardCharsets.ISO_8859_1)));
    var reader = new StatementReader(in, POSTGRESQL, -1, 1);
    assertEquals(new StatementReader.Sql("SELECT 1", 1, 0), reader.next().orElseThrow());
    assertEquals(
        "The file is not UTF-8 on line 3",
        assertThrows(IOException.class, reader::next).getMessage());
    // The end of a copy's rows on the line where it stops ends none: the rows fail, unless the
    // line has ended before. A row of some length, as the scan looks beyond the copy's statement.
    var row = "1\tthe first row, of some length\n";
    for (var rows : List.of(row + "\\.\u00fc\n", row + "\\.\r\u00fc")) {
      for (int chunk : CHUNKS) {
        var copy = new StatementReader(
            new StatementReader.Utf8(new ByteArrayInputStream(
                ("COPY t FROM stdin;\n" + rows).getBytes(StandardCharsets.ISO_8859_1))),
            POSTGRESQL,
            -1,
            chunk);
        assertInstanceOf(StatementReader.Copy.class, copy.next().orElseThrow());
        assertThrows(IOException.class, () -> all(copy.rows()), rows + ", chunk " + chunk);
      }
    }
    var ended = new StatementReader(
        new StatementReader.Utf8(new ByteArrayInputStream(
            ("COPY t FROM stdin;\n" + row + "\\.\n\u00fc").getBytes(StandardCharsets.ISO_8859_1))),
        POSTGRESQL,
        -1,
        1);
    assertInstanceOf(StatementReader.Copy.class, ended.next().orElseThrow());
    assertEquals(row, all(ended.rows()));
    assertThrows(IOException.class, ended::next);
  }

  @Test
  void aCancelStopsTheRows() throws IOException {
    var reader =
        new StatementReader(new StringReader("COPY t FROM stdin;\n1\n2\n"), POSTGRESQL, -1, 4);
    reader.next();
    var rows = reader.rows();
    reader.cancel();
    assertThrows(InterruptedIOException.class, () -> rows.read(new char[8]));
  }

  @Test
  void aReadOfRowsEndsBeforeASurrogatePairItCannotHoldWhole() throws IOException {
    // pgjdbc encodes each read by itself, which would turn half a pair into a replacement.
    var row = "xxxxxx\uD83D\uDE00y\n";
    var text = "COPY t FROM stdin;\n" + row + "\\.\n";
    for (int chunk : CHUNKS) {
      for (int length = 2; length <= 9; length++) {
        var reader = new StatementReader(new StringReader(text), POSTGRESQL, -1, chunk);
        reader.next();
        var rows = reader.rows();
        var into = new char[length];
        var read = new StringBuilder();
        for (int n = rows.read(into); n >= 0; n = rows.read(into)) {
          assertFalse(Character.isHighSurrogate(into[n - 1]), chunk + " " + length);
          read.append(into, 0, n);
        }
        assertEquals(row, read.toString());
      }
    }
  }

  @Test
  void aStatementLongerThanAWindowIsReadWhole() throws IOException {
    var text = "INSERT INTO t VALUES " + "(1,'x;y'),".repeat(30_000) + "(2,'z');\nSELECT 1;";
    var reader = new StatementReader(new StringReader(text), MARIADB, -1);
    var first = (StatementReader.Sql) reader.next().orElseThrow();
    assertEquals(text.indexOf(";\nSELECT"), first.sql().length());
    assertEquals(
        new StatementReader.Sql("SELECT 1", 2, text.length() - 9), reader.next().orElseThrow());
    assertTrue(reader.next().isEmpty());
  }
}
