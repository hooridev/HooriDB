package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.data.ColumnFilter;
import dev.hoori.db.core.data.Condition;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.query.QueryException;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Header filter translation to MongoDB filter documents and shell text. */
class DocumentFiltersTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void buildsTypedFilterDocumentsAndRoundTripsThroughShell() throws Exception {
    var customer = new Field("customer", "string", Kind.TEXT);
    var orderName = new Field("order name", "string | null", Kind.TEXT);
    var count = new Field("count", "int | null", Kind.INTEGER);
    var big = new Field("big", "long", Kind.INTEGER);
    var total = new Field("total", "double", Kind.DECIMAL);
    var amount = new Field("amount", "decimal", Kind.DECIMAL);
    var paid = new Field("paid", "bool", Kind.BOOLEAN);
    var created = new Field("created", "date", Kind.DATE_TIME_ZONED);
    var id = new Field("_id", "objectId", Kind.OTHER);

    // String text matching escapes PCRE metacharacters (not Pattern.quote) with $options: 'i'.
    var contains = DocumentFilters.filterText(
        "",
        List.of(new ColumnFilter(
            customer, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "a.b+c(d)[e]{f}|^$*?\\"))),
        BERLIN);
    assertEquals(
        "{ customer: { $regex: 'a\\\\.b\\\\+c\\\\(d\\\\)\\\\[e\\\\]\\\\{f\\\\}\\\\|\\\\^\\\\$\\\\*\\\\?\\\\\\\\', $options: 'i' } }",
        contains);
    assertEquals(
        Document.of(
            "customer",
            Document.of(
                "$regex", "a\\.b\\+c\\(d\\)\\[e\\]\\{f\\}\\|\\^\\$\\*\\?\\\\", "$options", "i")),
        Shell.document(contains));

    assertEquals(
        "{ 'order name': { $regex: '^Ada', $options: 'i' } }",
        DocumentFilters.filterText(
            "",
            List.of(new ColumnFilter(
                orderName, new ColumnFilter.Text(ColumnFilter.Match.STARTS_WITH, "Ada"))),
            BERLIN));
    assertEquals(
        "{ customer: { $regex: '^Ada\\\\z', $options: 'i' } }",
        DocumentFilters.filterText(
            "",
            List.of(new ColumnFilter(
                customer, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "Ada"))),
            BERLIN));
    assertEquals(
        "{ customer: 'Ada' }",
        DocumentFilters.filterText(
            "",
            List.of(new ColumnFilter(
                customer, new ColumnFilter.Text(ColumnFilter.Match.EXACTLY, "Ada"))),
            BERLIN));
    assertEquals(
        "{ customer: { $regex: '^ada.*', $options: 'i' } }",
        DocumentFilters.filterText(
            "", List.of(new ColumnFilter(customer, new ColumnFilter.Regex("^ada.*"))), BERLIN));

    // Typed values: int, long, double, decimal, bool, date (in zone), ObjectId (hex and ctor).
    var typed = DocumentFilters.filterText(
        "",
        List.of(
            new ColumnFilter(count, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "42")),
            new ColumnFilter(big, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "99")),
            new ColumnFilter(total, new ColumnFilter.Between("10", "50.5")),
            new ColumnFilter(amount, new ColumnFilter.Between("1.25", "")),
            new ColumnFilter(paid, new ColumnFilter.OneOf(List.of("true"))),
            new ColumnFilter(
                created, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-05-14 14:30:00")),
            new ColumnFilter(
                id, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "507f1f77bcf86cd799439011"))),
        BERLIN);
    assertEquals(
        "{ count: 42, big: Long('99'), total: { $gte: 10.0, $lte: 50.5 },"
            + " amount: { $gte: Decimal128('1.25') }, paid: { $in: [ true ] },"
            + " created: ISODate('2026-05-14T12:30:00.000Z'),"
            + " _id: ObjectId('507f1f77bcf86cd799439011') }",
        typed);
    Shell.document(typed);

    // ObjectId constructor form copied from a cell also works.
    assertEquals(
        "{ _id: ObjectId('507f1f77bcf86cd799439011') }",
        DocumentFilters.filterText(
            "",
            List.of(new ColumnFilter(
                id,
                new ColumnFilter.Text(
                    ColumnFilter.Match.EQUALS, "ObjectId('507f1f77bcf86cd799439011')"))),
            BERLIN));

    // IS NULL, IS NOT NULL, and empty OneOf.
    assertEquals(
        "{ customer: { $type: 'null' }, paid: { $not: { $type: 'null' } }, count: { $in: [] } }",
        DocumentFilters.filterText(
            "",
            List.of(
                new ColumnFilter(customer, new ColumnFilter.Null(true)),
                new ColumnFilter(paid, new ColumnFilter.Null(false)),
                new ColumnFilter(count, new ColumnFilter.OneOf(List.of()))),
            BERLIN));
  }

  @Test
  void joinsWithHandWrittenFilterUsingAndAndSkipsEmptyBraces() throws Exception {
    var customer = new Field("customer", "string", Kind.TEXT);
    var f = new ColumnFilter(customer, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "Ada"));
    var combined = DocumentFilters.filterText("{ total: { $gt: 0 } }", List.of(f), BERLIN);
    assertEquals(
        "{ $and: [ { total: { $gt: 0 } }, { customer: { $regex: 'Ada', $options: 'i' } } ] }",
        combined);
    Shell.document(combined);
    // A line comment at the end is ended before the generated part.
    Shell.document(DocumentFilters.filterText("{ total: 1 } // paid", List.of(f), BERLIN));
    assertEquals(
        "{ $and: [ { url: 'http://a' }, { customer: { $regex: 'Ada', $options: 'i' } } ] }",
        DocumentFilters.filterText("{ url: 'http://a' }", List.of(f), BERLIN));

    assertEquals(
        "{ customer: { $regex: 'Ada', $options: 'i' } }",
        DocumentFilters.filterText("{}", List.of(f), BERLIN));
    assertEquals(
        "{ total: { $gt: 0 } }",
        DocumentFilters.filterText("{ total: { $gt: 0 } }", List.of(), BERLIN));
  }

  @Test
  void refusesInvalidTypedValuesOperatorsFieldsAndBase() {
    var count = new Field("count", "int", Kind.INTEGER);
    var total = new Field("total", "double", Kind.DECIMAL);
    var paid = new Field("paid", "bool", Kind.BOOLEAN);
    var created = new Field("created", "date", Kind.DATE_TIME_ZONED);
    var id = new Field("_id", "objectId", Kind.OTHER);
    var leadingDot = new Field(".tag", "string", Kind.TEXT);
    var trailingDot = new Field("meta.", "string", Kind.TEXT);
    var doubleDot = new Field("meta..tag", "string", Kind.TEXT);
    var dollar = new Field("$score", "double", Kind.DECIMAL);
    var nestedDollar = new Field("meta.$tag", "string", Kind.TEXT);

    List<ColumnFilter> bad = List.of(
        new ColumnFilter(
            new Field("amount", "decimal", Kind.DECIMAL),
            new ColumnFilter.Text(
                ColumnFilter.Match.EQUALS, "1.00000000000000000000000000000000001")),
        new ColumnFilter(
            new Field("amount", "decimal", Kind.DECIMAL),
            new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "100e2147483647")),
        new ColumnFilter(count, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "abc")),
        new ColumnFilter(count, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "1.5")),
        new ColumnFilter(total, new ColumnFilter.Between("abc", "10")),
        new ColumnFilter(paid, new ColumnFilter.OneOf(List.of("maybe"))),
        new ColumnFilter(created, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "not-a-date")),
        new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "not-an-oid")),
        new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "507f")),
        new ColumnFilter(count, new ColumnFilter.Regex("123")),
        new ColumnFilter(leadingDot, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "x")),
        new ColumnFilter(trailingDot, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "x")),
        new ColumnFilter(doubleDot, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "x")),
        new ColumnFilter(dollar, new ColumnFilter.Null(true)),
        new ColumnFilter(nestedDollar, new ColumnFilter.Exists(true)));
    for (var filter : bad) {
      assertEquals(
          QueryException.Failure.CLAUSE_REFUSED,
          assertThrows(QueryException.class, () -> DocumentFilters.filter(List.of(filter), BERLIN))
              .failure(),
          filter.toString());
    }
    var ok =
        new ColumnFilter(new Field("customer", "string", Kind.TEXT), new ColumnFilter.Null(true));
    assertEquals(
        QueryException.Failure.CLAUSE_REFUSED,
        assertThrows(
                QueryException.class,
                () -> DocumentFilters.filterText("{ bad:", List.of(ok), BERLIN))
            .failure());
  }

  @Test
  void supportsConditionGroupsNegationExistsNestedPathsAndOperators() throws Exception {
    var customer = new Field("customer", "string", Kind.TEXT);
    var city = new Field("address.city", "string", Kind.TEXT);
    var count = new Field("count", "int", Kind.INTEGER);
    var paid = new Field("paid", "bool", Kind.BOOLEAN);
    var address = new Field("address", "object", Kind.JSON);

    // Operators per sampled type, including Exists / Does not exist.
    assertEquals(
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.CONTAINS,
            ColumnFilter.Operator.STARTS_WITH,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST),
        DocumentFilters.operators(city));
    assertEquals(
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.AT_LEAST,
            ColumnFilter.Operator.AT_MOST,
            ColumnFilter.Operator.BETWEEN,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST),
        DocumentFilters.operators(count));
    assertEquals(
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST),
        DocumentFilters.operators(paid));
    assertEquals(
        List.of(
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST),
        DocumentFilters.operators(address));
    assertEquals(List.of(), DocumentFilters.operators(new Field("$score", "double", Kind.DECIMAL)));

    // OR with nested AND, Not equal ($not regex on string, $ne on int), Exists, and nested path.
    var condition = Condition.Group.or(List.of(
        new ColumnFilter(city, ColumnFilter.Operator.EQUALS.spec(List.of("Berlin"))),
        Condition.Group.and(List.of(
            new ColumnFilter(count, ColumnFilter.Operator.NOT_EQUAL.spec(List.of("0"))),
            new ColumnFilter(address, ColumnFilter.Operator.EXISTS.spec(List.of())),
            new ColumnFilter(customer, ColumnFilter.Operator.NOT_EQUAL.spec(List.of("Grace")))))));
    var text = DocumentFilters.filterText("", condition, BERLIN);
    assertEquals(
        "{ $or: [ { 'address.city': { $regex: '^Berlin\\\\z', $options: 'i' } },"
            + " { count: { $ne: 0 }, address: { $exists: true }, customer: { $not: /^Grace\\z/i }"
            + " } ] }",
        text);
    Shell.document(text);

    // Repeated field in AND uses $and so keys do not collide.
    var repeated = Condition.Group.and(List.of(
        new ColumnFilter(count, ColumnFilter.Operator.AT_LEAST.spec(List.of("1"))),
        new ColumnFilter(count, ColumnFilter.Operator.NOT_EQUAL.spec(List.of("5")))));
    var repeatedText = DocumentFilters.filterText("", repeated, BERLIN);
    assertEquals("{ $and: [ { count: { $gte: 1 } }, { count: { $ne: 5 } } ] }", repeatedText);
    Shell.document(repeatedText);

    // Nested OR inside AND uses $and and $or.
    var andWithOr = Condition.Group.and(List.of(
        new ColumnFilter(address, ColumnFilter.Operator.DOES_NOT_EXIST.spec(List.of())),
        Condition.Group.or(List.of(
            new ColumnFilter(paid, ColumnFilter.Operator.EQUALS.spec(List.of("true"))),
            new ColumnFilter(count, ColumnFilter.Operator.AT_MOST.spec(List.of("3")))))));
    var andWithOrText = DocumentFilters.filterText("", andWithOr, BERLIN);
    assertEquals(
        "{ $and: [ { address: { $exists: false } }, { $or: [ { paid: true }, { count: { $lte: 3 }"
            + " } ] } ] }",
        andWithOrText);
    Shell.document(andWithOrText);
  }
}
