package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.data.ColumnFilter;
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
    var dotted = new Field("meta.tag", "string", Kind.TEXT);
    var dollar = new Field("$score", "double", Kind.DECIMAL);

    List<ColumnFilter> bad = List.of(
        new ColumnFilter(count, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "abc")),
        new ColumnFilter(count, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "1.5")),
        new ColumnFilter(total, new ColumnFilter.Between("abc", "10")),
        new ColumnFilter(paid, new ColumnFilter.OneOf(List.of("maybe"))),
        new ColumnFilter(created, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "not-a-date")),
        new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "not-an-oid")),
        new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "507f")),
        new ColumnFilter(count, new ColumnFilter.Regex("123")),
        new ColumnFilter(dotted, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "x")),
        new ColumnFilter(dollar, new ColumnFilter.Null(true)));
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
}
