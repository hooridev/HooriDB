package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StatementsTest {

  private static final List<Provider> MYSQL_FAMILY = List.of(MARIADB, MYSQL);

  private static List<String> split(String text, Provider provider) {
    return Statements.split(text, provider).stream()
        .map(s -> text.substring(s.start(), s.end()))
        .toList();
  }

  private static void assertSplit(Provider provider, String text, String... statements) {
    assertEquals(List.of(statements), split(text, provider), provider + ": " + text);
  }

  private static void assertSplitMySqlFamily(String text, String... statements) {
    for (var provider : MYSQL_FAMILY) {
      assertSplit(provider, text, statements);
    }
  }

  private static void assertSplitAll(String text, String... statements) {
    for (var provider : Provider.SQL) {
      assertSplit(provider, text, statements);
    }
  }

  @Test
  void offsetsLeaveOutWhitespaceCommentsAndTheSemicolon() {
    assertEquals(
        List.of(new Statement(2, 10), new Statement(21, 29)),
        Statements.split("  SELECT 1 ;\n/* x */ SELECT 2 -- y\n;", POSTGRESQL));
  }

  @Test
  void theFirstWordComesAfterCommentsAndParentheses() {
    assertEquals("VACUUM", Statements.firstWord("-- SELECT\nVACUUM t", POSTGRESQL));
    assertEquals("VACUUM", Statements.firstWord("/* a /* SELECT */ b */ VACUUM", POSTGRESQL));
    assertEquals("select", Statements.firstWord(" ( /* x */ (select 1))", POSTGRESQL));
    assertEquals("WITH", Statements.firstWord("WITH x AS (SELECT 1) SELECT * FROM x;", POSTGRESQL));
    assertEquals("t1", Statements.firstWord("t1", POSTGRESQL));
    assertEquals("", Statements.firstWord("\"SELECT\"", POSTGRESQL));
    assertEquals("", Statements.firstWord("-- only a comment", POSTGRESQL));
    assertEquals("SELECT", Statements.firstWord("# x\nSELECT 1", MYSQL));
  }

  @Test
  void emptyStatementsAreNone() {
    assertSplitAll(";;SELECT 1;; ;\n;", "SELECT 1");
  }

  @Test
  void textWithoutFinalSemicolonEndsItsLastStatementAtTheEnd() {
    assertSplitAll("SELECT 1; SELECT 2", "SELECT 1", "SELECT 2");
  }

  @Test
  void emptyTextAndOnlyCommentsHaveNoStatements() {
    assertSplitAll("");
    assertSplitAll(" \n\t ");
    assertSplitAll("/* a; */ -- b;\n;/* c */;");
    assertSplitMySqlFamily("# a;\n-- b;\n");
  }

  @Test
  void windowsLineEnds() {
    assertSplitAll("SELECT 1;\r\n-- c;\r\nSELECT\r\n 2;\r\n", "SELECT 1", "SELECT\r\n 2");
    assertSplitMySqlFamily(
        "DELIMITER //\r\nSELECT 1; SELECT 2//\r\nDELIMITER ;\r\nSELECT 3;\r\n",
        "SELECT 1; SELECT 2",
        "SELECT 3");
  }

  @Test
  void surrogatePairsAndNonAsciiPassThrough() {
    var text = "SELECT 'ä😀;'; SELECT 😀;";
    assertSplitAll(text, "SELECT 'ä😀;'", "SELECT 😀");
    assertEquals(text.length() - 1, Statements.split(text, POSTGRESQL).getLast().end());
  }

  // PostgreSQL

  @Test
  void postgresStringWithDoubledQuote() {
    assertSplit(POSTGRESQL, "SELECT 'a;''b'; SELECT 2", "SELECT 'a;''b'", "SELECT 2");
  }

  @Test
  void postgresBackslashDoesNotEscapeInPlainString() {
    assertSplit(POSTGRESQL, "SELECT 'a\\'; SELECT 2", "SELECT 'a\\'", "SELECT 2");
    assertSplitMySqlFamily("SELECT 'a\\'; SELECT 2", "SELECT 'a\\'; SELECT 2");
  }

  @Test
  void postgresEscapeString() {
    assertSplit(POSTGRESQL, "SELECT E'a\\';b'; SELECT 2", "SELECT E'a\\';b'", "SELECT 2");
    assertSplit(POSTGRESQL, "SELECT e'a'';b'; SELECT 2", "SELECT e'a'';b'", "SELECT 2");
    // An E that ends an identifier does not make the string an escape string.
    assertSplit(POSTGRESQL, "SELECT some'a\\'; SELECT 2", "SELECT some'a\\'", "SELECT 2");
  }

  @Test
  void postgresDollarQuotes() {
    assertSplit(POSTGRESQL, "SELECT $$a;'b$$; SELECT 2", "SELECT $$a;'b$$", "SELECT 2");
    assertSplit(
        POSTGRESQL,
        "CREATE FUNCTION f() AS $fn$ BEGIN; $$ ; $FN$ ; $fn$; SELECT 2",
        "CREATE FUNCTION f() AS $fn$ BEGIN; $$ ; $FN$ ; $fn$",
        "SELECT 2");
    assertSplit(POSTGRESQL, "SELECT $_1é$;$_1é$; SELECT 2", "SELECT $_1é$;$_1é$", "SELECT 2");
  }

  @Test
  void postgresDollarInIdentifierOrParameterOpensNoQuote() {
    assertSplit(POSTGRESQL, "SELECT a$$; SELECT 2$$", "SELECT a$$", "SELECT 2$$");
    assertSplit(POSTGRESQL, "SELECT $1; SELECT $2", "SELECT $1", "SELECT $2");
    assertSplit(POSTGRESQL, "SELECT $1$; SELECT 2", "SELECT $1$", "SELECT 2");
  }

  @Test
  void dollarsMeanNothingInMySqlFamily() {
    assertSplitMySqlFamily("SELECT $$a;b$$", "SELECT $$a", "b$$");
  }

  @Test
  void postgresQuotedIdentifier() {
    assertSplit(POSTGRESQL, "SELECT \"a;\"\"b\"; SELECT 2", "SELECT \"a;\"\"b\"", "SELECT 2");
    assertSplit(POSTGRESQL, "SELECT \"a\\\"; SELECT 2", "SELECT \"a\\\"", "SELECT 2");
  }

  @Test
  void postgresLineCommentNeedsNoSpace() {
    assertSplit(POSTGRESQL, "SELECT 1 --;x\n, 2; SELECT 3", "SELECT 1 --;x\n, 2", "SELECT 3");
    assertSplitMySqlFamily("SELECT 1 --;x\n, 2; SELECT 3", "SELECT 1 --", "x\n, 2", "SELECT 3");
  }

  @Test
  void postgresBlockCommentsNest() {
    var text = "/* a /* b */ ; */ SELECT 1; SELECT 2";
    assertSplit(POSTGRESQL, text, "SELECT 1", "SELECT 2");
    assertSplitMySqlFamily(text, "*/ SELECT 1", "SELECT 2");
  }

  @Test
  void deeplyNestedCommentDoesNotOverflow() {
    var text = "/*".repeat(1_000_000) + ";" + "*/".repeat(1_000_000) + "SELECT 1";
    assertSplit(POSTGRESQL, text, "SELECT 1");
  }

  @Test
  void hashIsNoCommentInPostgres() {
    assertSplit(POSTGRESQL, "SELECT 1 # 2; SELECT 3", "SELECT 1 # 2", "SELECT 3");
    assertSplitMySqlFamily("SELECT 1 # 2; SELECT 3", "SELECT 1");
  }

  // MariaDB and MySQL

  @Test
  void mySqlStrings() {
    assertSplitMySqlFamily("SELECT 'a\\';b'; SELECT 2", "SELECT 'a\\';b'", "SELECT 2");
    assertSplitMySqlFamily("SELECT 'a'';b'; SELECT 2", "SELECT 'a'';b'", "SELECT 2");
    assertSplitMySqlFamily("SELECT \"a\\\";b\"; SELECT 2", "SELECT \"a\\\";b\"", "SELECT 2");
    assertSplitMySqlFamily("SELECT \"a\"\";b\"; SELECT 2", "SELECT \"a\"\";b\"", "SELECT 2");
    assertSplitMySqlFamily("SELECT '\\\\'; SELECT 2", "SELECT '\\\\'", "SELECT 2");
  }

  @Test
  void mySqlBacktickIdentifier() {
    assertSplitMySqlFamily("SELECT `a;``b`; SELECT 2", "SELECT `a;``b`", "SELECT 2");
    assertSplitMySqlFamily("SELECT `a\\`; SELECT 2", "SELECT `a\\`", "SELECT 2");
    assertSplit(POSTGRESQL, "SELECT `a;b`", "SELECT `a", "b`");
  }

  @Test
  void mySqlDashCommentNeedsWhitespaceOrControlCharacter() {
    assertSplitMySqlFamily("SELECT 1 -- ;\n; SELECT 2", "SELECT 1", "SELECT 2");
    assertSplitMySqlFamily("SELECT 1 --\t;\n; SELECT 2", "SELECT 1", "SELECT 2");
    assertSplitMySqlFamily("SELECT 1 --\n; SELECT 2", "SELECT 1", "SELECT 2");
    assertSplitMySqlFamily("SELECT 1 --", "SELECT 1");
    assertSplitMySqlFamily("SELECT 1--1; SELECT 2", "SELECT 1--1", "SELECT 2");
  }

  @Test
  void mySqlBlockCommentsDoNotNest() {
    assertSplitMySqlFamily("SELECT 1 /* ; /* ; */; SELECT 2", "SELECT 1", "SELECT 2");
  }

  @Test
  void executableCommentsAreStatementsWhoseSemicolonsDoNotCount() {
    assertSplitMySqlFamily(
        "/*!40101 SET a = 1; */; SELECT 2", "/*!40101 SET a = 1; */", "SELECT 2");
    assertSplit(
        MARIADB, "/*M!100100 SET a = 1; */; SELECT 2", "/*M!100100 SET a = 1; */", "SELECT 2");
    // MySQL reads the MariaDB form as an ordinary comment.
    assertSplit(MYSQL, "/*M!100100 SET a = 1; */; SELECT 2", "SELECT 2");
    assertSplit(POSTGRESQL, "/*!40101 SET a = 1; */; SELECT 2", "SELECT 2");
  }

  @Test
  void delimiterChangesAndChangesBack() {
    assertSplitMySqlFamily(
        "SELECT 1;\nDELIMITER //\nSELECT 2; SELECT 3//\nDELIMITER ;\nSELECT 4; SELECT 5",
        "SELECT 1",
        "SELECT 2; SELECT 3",
        "SELECT 4",
        "SELECT 5");
  }

  @Test
  void delimiterWithRoutineBody() {
    var body = "CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n  SELECT 2;\nEND";
    assertSplitMySqlFamily(
        "DELIMITER $$\n" + body + "$$\nDELIMITER ;\nCALL p();", body, "CALL p()");
  }

  @Test
  void delimiterOfSeveralCharactersInAnyCase() {
    assertSplitMySqlFamily(
        "delimiter ;;\nSELECT 1; SELECT 2;; SELECT 3;;\nDeLiMiTeR ;\nSELECT 4;",
        "SELECT 1; SELECT 2",
        "SELECT 3",
        "SELECT 4");
  }

  @Test
  void delimiterDoesNotCountInStringsOrComments() {
    assertSplitMySqlFamily(
        "DELIMITER //\nSELECT '//', `//` /* // */ -- //\n, 1//",
        "SELECT '//', `//` /* // */ -- //\n, 1");
  }

  @Test
  void delimiterCommandAfterStatementOnTheSameLineAndQuoted() {
    assertSplitMySqlFamily(
        "SELECT 1; DELIMITER '| |'\nSELECT 2;| |SELECT 3", "SELECT 1", "SELECT 2;", "SELECT 3");
  }

  @Test
  void delimiterInsideStatementIsNoCommand() {
    assertSplitMySqlFamily("SELECT\ndelimiter //\n;", "SELECT\ndelimiter //");
    assertSplitMySqlFamily("SELECT delimiter FROM t;", "SELECT delimiter FROM t");
    assertSplitMySqlFamily("DELIMITERS;", "DELIMITERS");
    assertSplit(POSTGRESQL, "DELIMITER //\nSELECT 1;", "DELIMITER //\nSELECT 1");
  }

  @Test
  void delimiterThatTheClientsRefuseChangesNothing() {
    assertSplitMySqlFamily("DELIMITER\nSELECT 1; SELECT 2", "SELECT 1", "SELECT 2");
    assertSplitMySqlFamily("DELIMITER \\\\\nSELECT 1; SELECT 2", "SELECT 1", "SELECT 2");
  }

  @Test
  void unterminatedConstructsRunToTheEnd() {
    for (var text : List.of(
        "SELECT 'a; b",
        "SELECT E'a\\'; b",
        "SELECT \"a; b",
        "SELECT $$a; b",
        "SELECT $t$a; $$ b")) {
      assertSplit(POSTGRESQL, "SELECT 0; " + text, "SELECT 0", text);
    }
    assertSplit(POSTGRESQL, "SELECT 0; /* a /* b */ ; c", "SELECT 0");
    assertSplit(POSTGRESQL, "SELECT 0 -- a; b", "SELECT 0");
    for (var text : List.of("SELECT 'a; b", "SELECT \"a\\\"; b", "SELECT `a; b", "/*! a; b")) {
      assertSplitMySqlFamily("SELECT 0; " + text, "SELECT 0", text);
    }
    assertSplitMySqlFamily("SELECT 0; /* a; b", "SELECT 0");
    assertSplitMySqlFamily("SELECT 0 # a; b", "SELECT 0");
    assertSplitMySqlFamily("SELECT 0; DELIMITER", "SELECT 0");
    assertSplitMySqlFamily("SELECT 0; DELIMITER 'ab", "SELECT 0");
  }

  // The caret

  private static Optional<String> at(String text, int caret) {
    return Statements.at(text, POSTGRESQL, caret).map(s -> text.substring(s.start(), s.end()));
  }

  @Test
  void caret() {
    // The semicolons are at 8 and 25, the comment from 11 to 15, the empty line at 16.
    var text = "SELECT 1;  -- c\n\nSELECT 2;\n";
    var first = Optional.of("SELECT 1");
    var second = Optional.of("SELECT 2");
    // In a statement, its ends included.
    assertEquals(first, at(text, 0));
    assertEquals(first, at(text, 4));
    assertEquals(first, at(text, 8));
    // Directly after the semicolon, and in the blanks after it on its line.
    assertEquals(first, at(text, 9));
    assertEquals(first, at(text, 11));
    // In a comment and on an empty line between statements.
    assertEquals(second, at(text, 12));
    assertEquals(second, at(text, 15));
    assertEquals(second, at(text, 16));
    assertEquals(second, at(text, 17));
    // After the last statement, on its line and on the lines below.
    assertEquals(second, at(text, 26));
    assertEquals(second, at(text, 27));
  }

  @Test
  void caretBeforeTheFirstStatementMeansIt() {
    assertEquals(Optional.of("SELECT 1"), at("\n-- c\n\nSELECT 1;", 0));
  }

  @Test
  void caretBetweenStatementAndItsSemicolonMeansIt() {
    assertEquals(Optional.of("SELECT 1"), at("SELECT 1 \n ; SELECT 2", 10));
  }

  @Test
  void caretAtTheStartOfAStatementDirectlyAfterASemicolonMeansTheNewOne() {
    assertEquals(Optional.of("SELECT 2"), at("SELECT 1;SELECT 2", 9));
  }

  @Test
  void caretAfterTheLastStatementWithoutSemicolon() {
    assertEquals(Optional.of("SELECT 1"), at("SELECT 1   ", 11));
    assertEquals(Optional.of("SELECT 1"), at("SELECT 1\n\n", 10));
  }

  @Test
  void caretOnADelimiterLine() {
    var text = "SELECT 1;\nDELIMITER //\nSELECT 2//";
    assertEquals(
        new Statement(23, 31), Statements.at(text, MYSQL, text.indexOf("//")).orElseThrow());
  }

  @Test
  void caretInATextWithoutStatements() {
    assertEquals(Optional.empty(), at("", 0));
    assertEquals(Optional.empty(), at("-- c\n;", 3));
  }

  @Test
  void caretOutsideTheText() {
    assertEquals(Optional.of("SELECT 1"), at("SELECT 1; SELECT 2", -5));
    assertEquals(Optional.of("SELECT 2"), at("SELECT 1; SELECT 2", 100));
  }

  // Behavior on large and random input

  @Test
  void megabytesSplitInLinearTime() {
    int count = 50_000;
    var postgres = "SELECT 'a;b', \"c;d\", $x$e;f$x$ /* g; /* h; */ */ -- i;\n FROM t;\n";
    var mysql = "SELECT 'a;\\'b', `c;d` /* g; */ # i;\n FROM t//\n";
    long began = System.nanoTime();
    assertEquals(count, Statements.split(postgres.repeat(count), POSTGRESQL).size());
    assertEquals(
        count, Statements.split("DELIMITER //\n" + mysql.repeat(count), MYSQL).size());
    // Texts on which a scan that backtracks would take quadratic time.
    assertEquals(
        1, Statements.split("SELECT $a$" + "$a".repeat(1_000_000), POSTGRESQL).size());
    assertEquals(
        1,
        Statements.split("DELIMITER aaaaaaaaaaaaab\nSELECT " + "a".repeat(2_000_000), MARIADB)
            .size());
    assertEquals(1, Statements.split("SELECT " + "-".repeat(2_000_000), MYSQL).size());
    long seconds = (System.nanoTime() - began) / 1_000_000_000;
    assertTrue(seconds < 20, "took " + seconds + " s");
  }

  @Test
  void randomInputSplitsIntoOrderedRangesWithinTheText() {
    var pieces = new String[] {
      "'",
      "\"",
      "`",
      "\\",
      "$",
      "$a$",
      "-",
      "/",
      "*",
      "!",
      "M",
      ";",
      "\n",
      "\r",
      " ",
      "\t",
      "#",
      "a",
      "E",
      "1",
      "DELIMITER ",
      "//",
      "é",
      "😀"
    };
    var random = new Random(42);
    for (int round = 0; round < 3_000; round++) {
      var text = new StringBuilder();
      for (int k = random.nextInt(60); k > 0; k--) {
        text.append(pieces[random.nextInt(pieces.length)]);
      }
      var sql = text.toString();
      for (var provider : Provider.SQL) {
        var statements = Statements.split(sql, provider);
        int previous = 0;
        for (var statement : statements) {
          assertTrue(
              previous <= statement.start()
                  && statement.start() < statement.end()
                  && statement.end() <= sql.length(),
              provider + ": " + sql);
          previous = statement.end();
        }
        for (int caret = 0; caret <= sql.length(); caret++) {
          var found = Statements.at(sql, provider, caret);
          assertEquals(statements.isEmpty(), found.isEmpty());
          found.ifPresent(s -> assertTrue(statements.contains(s)));
        }
      }
    }
  }

  @Test
  void aTextScannedOnceAnswersForEveryCaretAsAScanOfItsOwn() {
    var text = "SELECT 1;  \n-- a note\n\nSELECT 'a;b'\nFROM t;\n/* end */ UPDATE t SET x = 1";
    for (var provider : Provider.SQL) {
      var scanned = Statements.of(text, provider);
      assertEquals(Statements.split(text, provider), scanned.statements());
      for (int caret = -1; caret <= text.length() + 1; caret++) {
        assertEquals(
            Statements.at(text, provider, caret), scanned.at(caret), provider + " at " + caret);
      }
    }
  }

  @Test
  void aroundTheCaretIsTheStatementWithTheCommentsBeforeItUpToItsTerminator() {
    var text = "SELECT 1;\n-- two\nSELECT 2;\n-- after";
    var split = Statements.of(text, Provider.POSTGRESQL);
    int second = text.indexOf("-- two");
    assertEquals(new Statement(0, 9), split.around(0));
    assertEquals(new Statement(9, text.indexOf("2;") + 2), split.around(second + 3));
    // Right after a terminator, the next part; after the last, the rest.
    assertEquals(new Statement(9, text.indexOf("2;") + 2), split.around(9));
    assertEquals(new Statement(text.indexOf("2;") + 2, text.length()), split.around(text.length()));
    // A statement without a terminator goes on to the end, spaces too.
    var open = "SELECT 1; SELECT * FROM t WHERE ";
    assertEquals(
        new Statement(9, open.length()),
        Statements.of(open, Provider.POSTGRESQL).around(open.length()));
    assertEquals(new Statement(0, 0), Statements.of("", Provider.MYSQL).around(0));
  }

  @Test
  void aTextIsOpenWhereItsLastStatementHasNoTerminator() {
    assertTrue(Statements.of("SELECT 1; SELECT 2 -- next", Provider.POSTGRESQL).open());
    assertFalse(Statements.of("SELECT 1;\n-- after", Provider.MYSQL).open());
    assertFalse(Statements.of("-- nothing", Provider.MARIADB).open());
  }

  @Test
  void theShellsStatementsEndAtSemicolonsAndCompleteLines() {
    var text = "db.a.find({\n  x: 1\n});  \ndb.b.find()\n\n// done\n";
    var statements = Statements.split(text, Provider.MONGODB);
    assertEquals(
        List.of("db.a.find({\n  x: 1\n})", "db.b.find()"),
        statements.stream().map(x -> text.substring(x.start(), x.end())).toList());
    var scanned = Statements.of(text, Provider.MONGODB);
    assertEquals(statements.get(0), scanned.at(text.indexOf(";") + 2).orElseThrow());
    assertEquals(statements.get(1), scanned.at(text.length()).orElseThrow());
    assertEquals("find", Statements.firstWord("db.a.find()", Provider.MONGODB));
  }

  @Test
  void aCommentAfterAShellStatementIsBetweenStatements() {
    var text = "db.a.find() // note;\ndb.b.find()";
    var statements = Statements.split(text, Provider.MONGODB);
    assertEquals(
        List.of("db.a.find()", "db.b.find()"),
        statements.stream().map(x -> text.substring(x.start(), x.end())).toList());
    // In the comment, the next statement, as in one of SQL between statements.
    var scanned = Statements.of(text, Provider.MONGODB);
    assertEquals(statements.get(1), scanned.at(text.indexOf("note")).orElseThrow());
  }

  /** A semicolon in a string or comment left open ends no statement. */
  @Test
  void aSemicolonInAnOpenStringOfTheShellIsNoTerminator() {
    for (var text : List.of("db.a.find({ x: 'a;", "db.a.find({ x: /a;", "db.a.find() /* a;")) {
      var scanned = Statements.of(text, Provider.MONGODB);
      assertTrue(scanned.open(), text);
      assertEquals(text.length(), scanned.around(text.length()).end(), text);
    }
    assertFalse(Statements.of("db.a.find({ x: 'a;' });", Provider.MONGODB).open());
  }

  @Test
  void aQuoteInARegularExpressionOfTheShellStartsNoString() {
    var text = "db.a.find({ x: /'/ }); db.b.find({ y: 'z' })";
    var strings = Statements.pieces(text, Provider.MONGODB).stream()
        .filter(p -> p.kind() == Statements.Piece.Kind.STRING)
        .map(p -> text.substring(p.start(), p.end()))
        .toList();
    assertEquals(List.of("/'/", "'z'"), strings);
  }
}
