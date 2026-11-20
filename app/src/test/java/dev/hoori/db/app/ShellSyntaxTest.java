package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Formatted.Kind;
import com.rethinkj.ui.Formatted.Problem;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The colors of the shell's statements, line by line, as a console's editor shows them. */
class ShellSyntaxTest {

  // A color of its own for each kind, to tell them apart in the spans.
  static final Theme.Code CODE = new Theme.Code(1, 2, 3, 4, 5, 6, 7);
  static final Map<Integer, String> KINDS = Map.of(
      CODE.keyword(), "keyword",
      CODE.string(), "string",
      CODE.number(), "number",
      CODE.comment(), "comment",
      CODE.operator(), "operator");

  /** The colored parts of each line, as kind and text, the state carried from line to line. */
  static List<String> colored(String... lines) {
    var syntax = new ShellSyntax(CODE);
    var out = new ArrayList<String>();
    Object state = null;
    for (var line : lines) {
      var spans = new Spans(line.length());
      state = syntax.line(line, state, spans);
      for (var span : spans.toString().lines().toList()) {
        // As the kit writes a span: "color <from>-<to> <color in hex>".
        var parts = span.split(" ");
        var range = parts[1].split("-");
        int color = Integer.parseUnsignedInt(parts[2], 16);
        out.add(KINDS.get(color) + " "
            + line.substring(Integer.parseInt(range[0]), Integer.parseInt(range[1])));
      }
    }
    return out;
  }

  @Test
  void wordsStringsNumbersOperatorsAndComments() {
    assertEquals(
        List.of(
            "keyword db",
            "string 'Ada'",
            "operator $gt",
            "number 5",
            "number 1.5e3",
            "keyword true",
            "comment // the best"),
        colored("db.orders.find({ name: 'Ada', total: { $gt: 5 }, n: 1.5e3, paid: true }) "
            + "// the best"));
  }

  @Test
  void theConstructorsOfValues() {
    assertEquals(
        List.of(
            "keyword ObjectId",
            "string '65f0c0ffee0000000000beef'",
            "keyword new",
            "keyword Date",
            "keyword NumberLong",
            "number 5"),
        colored("{ _id: ObjectId('65f0c0ffee0000000000beef'), at: new Date(), n: NumberLong(5) }"));
  }

  /** Every constructor the shell reads, also those it writes, as BSONRegExp and Symbol. */
  @Test
  void theConstructorsTheShellWrites() {
    assertEquals(
        List.of(
            "keyword BSONRegExp",
            "string 'x'",
            "keyword Symbol",
            "string 's'",
            "keyword Binary",
            "string 'AA=='"),
        colored("{ a: BSONRegExp('x'), b: Symbol('s'), c: Binary.createFromBase64('AA==') }"));
  }

  @Test
  void aSlashAfterAnOperatorStartsARegularExpression() {
    assertEquals(List.of("string /'/", "number 1"), colored("{ x: a + /'/, y: 1 }"));
  }

  @Test
  void aStringInBackticksGoesOnOverLines() {
    assertEquals(
        List.of("string `one", "string two`", "number 1"), colored("{ a: `one", "two`, b: 1 }"));
  }

  @Test
  void aStringWhoseLineBreakIsEscapedGoesOn() {
    assertEquals(
        List.of("string 'one\\", "string two'", "number 1"),
        colored("{ a: 'one\\", "two', b: 1 }"));
  }

  @Test
  void aLineOfABackslashAloneGoesOnInItsString() {
    assertEquals(
        List.of("string 'one\\", "string \\", "string two'"),
        colored("{ a: 'one\\", "\\", "two' }"));
  }

  @Test
  void aBlockCommentGoesOnAlsoOverALineThatStartsWithTwoSlashes() {
    assertEquals(
        List.of("comment /* a", "comment // b", "comment c */", "keyword db"),
        colored("/* a", "// b", "c */ db"));
  }

  @Test
  void aRegularExpressionWithItsFlagsAndASlashInItsClass() {
    assertEquals(
        List.of("string /a\\/b[/]c/i", "string 'd'"), colored("{ x: /a\\/b[/]c/i, y: 'd' }"));
  }

  @Test
  void aSlashAfterAValueDivides() {
    assertEquals(List.of("number 6", "number 2", "number 3"), colored("{ x: 6 / 2 / 3 }"));
  }

  @Test
  void aQuoteInAStringOrAnEscapedOneEndsNoString() {
    assertEquals(
        List.of("string \"it's\"", "string 'a\\'b'", "number 1"),
        colored("{ a: \"it's\", b: 'a\\'b', c: 1 }"));
  }

  @Test
  void aBlockCommentGoesOnOverLines() {
    assertEquals(
        List.of(
            "keyword db",
            "comment /* from here",
            "comment over ( lines",
            "comment and */",
            "keyword ObjectId",
            "string 'x'"),
        colored("db.a.find() /* from here", "over ( lines", "and */ ObjectId('x')"));
  }

  @Test
  void anOpenStringEndsWithItsLine() {
    assertEquals(
        List.of("keyword db", "string 'open", "keyword db"),
        colored("db.a.find({ x: 'open", "db.b.find()"));
  }

  /** Control+Alt+L: the statements laid out, or the first problem, where and of which kind. */
  @Test
  void formatsOrSaysWhereAStatementGoesWrong() {
    assertEquals(
        new Formatted("db.c.find({ a: ObjectId('65f0c0ffee0000000000beef') }) // one", null),
        ShellSyntax.format("db.c.find({a:ObjectId('65f0c0ffee0000000000beef')}) // one"));
    assertEquals(
        new Formatted(null, new Problem(15, 16, Kind.UNEXPECTED)),
        ShellSyntax.format("db.c.find({ a: }, {})"));
    assertEquals(
        new Formatted(null, new Problem(15, 18, Kind.UNCLOSED_STRING)),
        ShellSyntax.format("db.c.find({ a: 'Ad"));
    assertEquals(
        new Formatted(null, new Problem(16, 16, Kind.UNEXPECTED_END)),
        ShellSyntax.format("db.c.find({ a: 1 "));
    // A string not closed at the end of its line, where the reader stops.
    assertEquals(
        new Formatted(null, new Problem(13, 18, Kind.UNCLOSED_STRING)),
        ShellSyntax.format("db.c.find({a:'oops\n  b: 2})"));
    // Not the comment, nor the next statement: the end of the one that goes wrong.
    assertEquals(
        new Formatted(null, new Problem(3, 3, Kind.UNEXPECTED_END)),
        ShellSyntax.format("use // later\ndb.c.find()"));
    // The token after the offset in the same statement, and a block comment not closed.
    assertEquals(
        new Formatted(null, new Problem(19, 24, Kind.UNEXPECTED)),
        ShellSyntax.format("db.c.find({a:new\n  Bogus()})"));
    assertEquals(
        new Formatted(null, new Problem(12, 25, Kind.UNEXPECTED_END)),
        ShellSyntax.format("db.c.find() /* unfinished"));
    int deep = 100_000;
    assertEquals(
        new Formatted(null, new Problem(0, 0, Kind.TOO_LARGE)),
        ShellSyntax.format("db.c.find({ a: " + "[".repeat(deep) + "]".repeat(deep) + " })"));
  }
}
