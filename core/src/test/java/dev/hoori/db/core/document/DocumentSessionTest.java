package dev.hoori.db.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Index;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Index mapping and error handling of {@link DocumentSession} without a server. */
class DocumentSessionTest {

  private static final Database HOORI = new Database("hoori", false);
  private static final Table ORDERS = new Table(HOORI, "orders", Table.Kind.TABLE);
  private static final Table VIEW = new Table(HOORI, "paid_orders", Table.Kind.VIEW);

  @Test
  void mapsIndexKeysTextWeightsDetailsAndOrder() {
    var docs = List.of(
        Document.of(
            "name",
            "z_compound",
            "key",
            Document.of(
                "customer", 2.0, "createdAt", -0.5, "location", "2dsphere", "shard", "hashed")),
        Document.of(
            "name", "email_uidx", "key", Document.of("email", 1), "unique", true, "sparse", true),
        Document.of(
            "name",
            "expire_ttl",
            "key",
            Document.of("expireAt", 1),
            "expireAfterSeconds",
            0,
            "partialFilterExpression",
            Document.of("active", true),
            "sparse",
            false),
        Document.of(
            "name",
            "full_detail",
            "key",
            Document.of("ts", 1),
            "expireAfterSeconds",
            3600L,
            "partialFilterExpression",
            Document.of("kind", "log"),
            "sparse",
            true),
        Document.of(
            "name",
            "search_text",
            "key",
            Document.of("tenant", 1, "_fts", "text", "_ftsx", 1, "createdAt", -1),
            "weights",
            Document.of("title", 10, "body", 5)),
        Document.of(
            "name",
            "plain_empty_partial",
            "key",
            Document.of("a", 1),
            "partialFilterExpression",
            Document.EMPTY),
        Document.of("name", "_id_", "key", Document.of("_id", 1)));

    assertEquals(
        List.of(
            new Index("_id_", ORDERS, List.of("_id: 1"), true, true, ""),
            new Index("email_uidx", ORDERS, List.of("email: 1"), true, false, "sparse"),
            new Index(
                "expire_ttl", ORDERS, List.of("expireAt: 1"), false, false, "TTL 0 s · partial"),
            new Index(
                "full_detail",
                ORDERS,
                List.of("ts: 1"),
                false,
                false,
                "TTL 3600 s · partial · sparse"),
            new Index("plain_empty_partial", ORDERS, List.of("a: 1"), false, false, "partial"),
            new Index(
                "search_text",
                ORDERS,
                List.of("tenant: 1", "title: text", "body: text", "createdAt: -1"),
                false,
                false,
                ""),
            new Index(
                "z_compound",
                ORDERS,
                List.of("customer: 1", "createdAt: -1", "location: 2dsphere", "shard: hashed"),
                false,
                false,
                "")),
        DocumentSession.indexes(ORDERS, docs));
  }

  @Test
  void viewReturnsEmptyWithoutSendingAndMissingCollectionReturnsEmpty() throws Exception {
    var sent = new ArrayList<Document>();
    var client = new DocumentClient() {
      @Override
      public Document command(String database, Document command) throws DocumentException {
        sent.add(command);
        throw DocumentException.server(
            DocumentException.NAMESPACE_NOT_FOUND, "NamespaceNotFound", "ns does not exist");
      }

      @Override
      public void close() {}
    };
    try (var session = new DocumentSession(client, Provider.MONGODB.documents(), true)) {
      assertEquals(List.of(), session.indexes(VIEW));
      assertTrue(sent.isEmpty(), sent.toString());

      assertEquals(List.of(), session.indexes(ORDERS));
      assertEquals(1, sent.size());
      assertEquals(Document.of("listIndexes", "orders", "cursor", Document.EMPTY), sent.getFirst());
    }
  }

  @Test
  void unauthorizedMapsToAccessDeniedAndReadOnlyCursorLogsUnderCatalog() throws Exception {
    var unauthorized = new DocumentClient() {
      @Override
      public Document command(String database, Document command) throws DocumentException {
        throw DocumentException.server(
            DocumentException.UNAUTHORIZED, "Unauthorized", "not authorized");
      }

      @Override
      public void close() {}
    };
    try (var session = new DocumentSession(unauthorized, Provider.MONGODB.documents(), true)) {
      var error = assertThrows(CatalogException.class, () -> session.indexes(ORDERS));
      assertEquals(CatalogException.Failure.ACCESS_DENIED, error.failure());
    }

    var paged = new DocumentClient() {
      @Override
      public Document command(String database, Document command) {
        if (command.has("listIndexes")) {
          return Document.of(
              "ok",
              1,
              "cursor",
              Document.of(
                  "id",
                  42L,
                  "ns",
                  "hoori.orders",
                  "firstBatch",
                  Document.array(
                      Document.of("name", "customer_1", "key", Document.of("customer", 1)))));
        }
        return Document.of(
            "ok",
            1,
            "cursor",
            Document.of(
                "id",
                0L,
                "ns",
                "hoori.orders",
                "nextBatch",
                Document.array(Document.of("name", "_id_", "key", Document.of("_id", 1)))));
      }

      @Override
      public void close() {}
    };
    var logged = new ArrayList<Sent>();
    try (var session = new DocumentSession(paged, Provider.MONGODB.documents(), true)) {
      var indexes = Journal.recording(logged::add, () -> session.indexes(ORDERS));
      assertEquals(
          List.of(
              new Index("_id_", ORDERS, List.of("_id: 1"), true, true, ""),
              new Index("customer_1", ORDERS, List.of("customer: 1"), false, false, "")),
          indexes);
    }
    assertEquals(2, logged.size());
    assertTrue(logged.stream().allMatch(s -> s.purpose() == Purpose.CATALOG), logged.toString());
  }
}
