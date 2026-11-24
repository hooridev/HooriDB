package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Missing;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The catalog and the documents of a real MongoDB, as the app reads them. */
class DocumentSessionServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final Table CUSTOMERS = new Table(HOORI, "customers", Table.Kind.TABLE);
  private static final Table ORDERS = new Table(HOORI, "orders", Table.Kind.TABLE);
  private static final Table PAID = new Table(HOORI, "paid_orders", Table.Kind.VIEW);

  static DocumentSession session(boolean readOnly) throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var opened =
        DocumentConnector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    return new DocumentSession(opened.client(), Provider.MONGODB.documents(), readOnly);
  }

  @Test
  void theCatalogHasDatabasesAndCollectionsAndViews() throws Exception {
    try (var session = session(false)) {
      var databases = session.databases();
      assertTrue(databases.contains(HOORI), databases.toString());
      assertTrue(databases.contains(new Database("admin", true)), databases.toString());
      assertFalse(databases.getFirst().system());
      assertEquals(
          List.of(CUSTOMERS, new Table(HOORI, "empty", Table.Kind.TABLE), ORDERS, PAID),
          session.collections(HOORI));
    }
  }

  @Test
  void theFieldsAreThoseOfTheDocuments() throws Exception {
    try (var session = session(false)) {
      var fields = session.fields(CUSTOMERS);
      assertEquals(
          List.of("_id", "name", "email", "born", "tags", "address", "score"),
          fields.stream().map(Column::name).toList());
      assertEquals(new Column(CUSTOMERS, "_id", "int", false, 1), fields.get(0));
      assertEquals(new Column(CUSTOMERS, "name", "string", false, 0), fields.get(1));
      assertEquals(new Column(CUSTOMERS, "email", "string | null", true, 0), fields.get(2));
      assertEquals("date", fields.get(3).type());
      assertEquals("array | null", fields.get(4).type());
      assertEquals("long | null", fields.get(6).type());
      assertEquals(
          List.of(),
          session.fields(new Table(HOORI, "empty", Table.Kind.TABLE)).stream()
              .filter(c -> !c.name().equals("_id"))
              .toList());
    }
  }

  @Test
  void documentsAreReadInPagesInTheOrderOfTheirIds() throws Exception {
    try (var session = session(false)) {
      var fields = session.fields(ORDERS);
      var page = session.read(ORDERS, fields, 200, 300);
      assertEquals(50, page.rows().size());
      assertEquals(50, page.documents().size());
      assertEquals(201, page.documents().getFirst().get("_id"));
      var kinds = page.fields().stream().map(f -> f.kind()).toList();
      assertEquals(
          List.of(Kind.INTEGER, Kind.INTEGER, Kind.DECIMAL, Kind.INTEGER, Kind.BOOLEAN), kinds);
      assertEquals(
          List.of(201L, 1L, new BigDecimal("301.5"), 1L, false), page.rows().getFirst());

      var customers = session.read(CUSTOMERS, session.fields(CUSTOMERS), 0, 10);
      var ada = customers.rows().getFirst();
      assertEquals(OffsetDateTime.parse("1815-12-10T00:00Z"), ada.get(3));
      // A document or an array as Relaxed Extended JSON, of Kind.JSON.
      assertEquals("[\"math\", \"poetry\"]", ada.get(4));
      assertEquals("{\"city\": \"London\", \"zip\": \"W1\"}", ada.get(5));
      // Grace's email is null; Edsger has none.
      assertNull(customers.rows().get(1).get(2));
      assertEquals(Missing.FIELD, customers.rows().get(2).get(2));

      // A limit of 0 would read them all.
      assertEquals(List.of(), session.read(ORDERS, fields, 5, 5).rows());
    }
  }

  @Test
  void aValueIsReadWholeByItsDocumentsId() throws Exception {
    try (var session = session(false)) {
      var fields = session.fields(CUSTOMERS);
      var ada = session.read(CUSTOMERS, fields, 0, 1).rows().getFirst();
      assertEquals("[\"math\", \"poetry\"]", session.whole(CUSTOMERS, fields, ada, 4));
      assertEquals("Ada", session.whole(CUSTOMERS, fields, ada, 1));
      var gone = new ArrayList<>(ada);
      gone.set(0, 999_999L);
      var e = assertThrows(QueryException.class, () -> session.whole(CUSTOMERS, fields, gone, 1));
      assertEquals(QueryException.Failure.NOT_FOUND, e.failure());
    }
  }

  @Test
  void anEstimateAndACount() throws Exception {
    try (var session = session(false)) {
      assertEquals(250, session.estimate(ORDERS).getAsLong());
      assertTrue(session.estimate(PAID).isEmpty());
      assertEquals(125, session.count(PAID));
      assertEquals(0, session.count(new Table(HOORI, "empty", Table.Kind.TABLE)));
      assertEquals(0, session.count(new Table(HOORI, "missing", Table.Kind.TABLE)));
    }
  }

  @Test
  void readEstimateAndCountHonorFilterSortAndIdTieBreaker() throws Exception {
    try (var session = session(false)) {
      var fields = session.fields(ORDERS);
      var paid = Document.of("paid", true);
      // Filtered collection has no estimate; empty filter keeps the metadata estimate.
      assertTrue(session.estimate(ORDERS, paid).isEmpty());
      assertEquals(250, session.estimate(ORDERS, Document.EMPTY).getAsLong());
      assertEquals(125, session.count(ORDERS, paid));
      assertEquals(0, session.count(ORDERS, Document.of("customer", 99)));

      // Filter and descending sort on total.
      var topPaid = session.read(ORDERS, fields, 0, 3, paid, Document.of("total", -1));
      assertEquals(
          List.of(250, 248, 246),
          topPaid.documents().stream().map(d -> d.get("_id")).toList());

      // Ties in sort are broken by _id: 1 when _id is not named, keeping paging deterministic.
      var byCustomerFirst = session.read(ORDERS, fields, 0, 3, paid, Document.of("customer", 1));
      var byCustomerNext = session.read(ORDERS, fields, 3, 6, paid, Document.of("customer", 1));
      assertEquals(
          List.of(6, 12, 18),
          byCustomerFirst.documents().stream().map(d -> d.get("_id")).toList());
      assertEquals(
          List.of(24, 30, 36),
          byCustomerNext.documents().stream().map(d -> d.get("_id")).toList());

      // Explicit _id in sort is honored without appending _id: 1.
      var byCustomerDescId =
          session.read(ORDERS, fields, 0, 3, paid, Document.of("customer", 1, "_id", -1));
      assertEquals(
          List.of(246, 240, 234),
          byCustomerDescId.documents().stream().map(d -> d.get("_id")).toList());
      var reverseIds = session.read(ORDERS, fields, 0, 3, Document.EMPTY, Document.of("_id", -1));
      assertEquals(
          List.of(250, 249, 248),
          reverseIds.documents().stream().map(d -> d.get("_id")).toList());
    }
  }

  @Test
  void aReadOnlySessionFiltersAndCountsDocuments() throws Exception {
    try (var session = session(true)) {
      var fields = session.fields(ORDERS);
      var filter = Document.of("paid", true, "customer", 2);
      assertTrue(session.estimate(ORDERS, filter).isEmpty());
      assertEquals(42, session.count(ORDERS, filter));
      var page = session.read(ORDERS, fields, 0, 3, filter, Document.of("total", -1));
      assertEquals(
          List.of(250, 244, 238),
          page.documents().stream().map(d -> d.get("_id")).toList());
    }
  }

  @Test
  void aCursorIsReadInPortionsAndClosed() throws Exception {
    try (var session = session(false)) {
      var reply = session.command(
          "hoori", Document.of("find", "orders", "sort", Document.of("_id", 1), "batchSize", 30));
      var cursor = session.cursor("hoori", reply);
      assertEquals(100, cursor.next(100).size());
      var next = cursor.next(100);
      assertEquals(101, next.getFirst().get("_id"));
      assertTrue(cursor.more());
      cursor.close();
      assertFalse(cursor.more());
      assertEquals(List.of(), cursor.next(10));
    }
  }

  @Test
  void aReadOnlySessionSendsNothingThatWrites() throws Exception {
    try (var session = session(true)) {
      assertEquals(250, session.estimate(ORDERS).getAsLong());
      var refused = assertThrows(
          DocumentException.class,
          () -> session.command(
              "hoori",
              Document.of(
                  "delete",
                  "orders",
                  "deletes",
                  Document.array(Document.of("q", Document.EMPTY, "limit", 0)))));
      assertEquals(DocumentException.Kind.READ_ONLY, refused.kind());
      assertThrows(
          DocumentException.class,
          () -> session.command(
              "hoori",
              Document.of(
                  "aggregate",
                  "orders",
                  "pipeline",
                  Document.array(Document.of("$out", "copy")),
                  "cursor",
                  Document.EMPTY)));
    }
    try (var session = session(false)) {
      assertEquals(250, session.count(ORDERS));
      assertFalse(session.collections(HOORI).stream().anyMatch(t -> t.name().equals("copy")));
    }
  }

  @Test
  void theServersRefusalsAreTheCatalogsAndQueriesFailures() throws Exception {
    try (var session = session(false)) {
      var e = assertThrows(
          DocumentException.class, () -> session.command("hoori", Document.of("nope", 1)));
      assertEquals(DocumentException.COMMAND_NOT_FOUND, e.code());
      assertInstanceOf(QueryException.class, DocumentSession.query(e));
      assertEquals(CatalogException.Failure.OTHER, DocumentSession.catalog(e).failure());
    }
  }
}
