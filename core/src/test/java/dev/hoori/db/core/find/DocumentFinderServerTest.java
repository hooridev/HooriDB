package dev.hoori.db.core.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.find.Search.Match;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Searches of the MongoDB test server's sample, on a read-only session. */
class DocumentFinderServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final Table CUSTOMERS = new Table(HOORI, "customers", Table.Kind.TABLE);

  private static DocumentSession session() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var opened =
        DocumentConnector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    return new DocumentSession(opened.client(), Provider.MONGODB.documents(), true);
  }

  @Test
  void collectionsAndTheirFieldsAreFoundByName() throws Exception {
    try (var session = session()) {
      var found = DocumentFinder.objects(session, HOORI, new Search("cit", Match.CONTAINS, false));
      assertEquals(
          List.of(
              new Found(Found.Kind.COLUMN, HOORI, CUSTOMERS, "address.city", false, null, 8, 11)),
          found);
      var orders =
          DocumentFinder.objects(session, HOORI, new Search("orders", Match.CONTAINS, false));
      assertEquals(
          List.of("orders", "paid_orders"), orders.stream().map(Found::name).toList(), "" + orders);
      assertEquals(Found.Kind.VIEW, orders.getLast().kind());
    }
  }

  @Test
  void theStringFieldsOfTheSampleAreSearchedNestedOnesAndArraysIncluded() throws Exception {
    try (var session = session()) {
      var candidates = DocumentFinder.candidates(session, HOORI);
      var customers = candidates.stream()
          .filter(c -> c.table().equals(CUSTOMERS))
          .findFirst()
          .orElseThrow();
      assertEquals(OptionalLong.of(3), customers.rows());
      var london = DocumentFinder.search(
          session, customers, new Search("LONDON", Match.EQUALS, false), false);
      assertEquals(List.of(new Hits.Hit("address.city", 1)), london.columns(), "" + london);
      var tags =
          DocumentFinder.search(session, customers, new Search("av", Match.CONTAINS, true), false);
      assertEquals(List.of(new Hits.Hit("tags", 1)), tags.columns());
      var a =
          DocumentFinder.search(session, customers, new Search("^a", Match.PATTERN, false), false);
      assertEquals(List.of(new Hits.Hit("name", 1), new Hits.Hit("email", 1)), a.columns());
      var number =
          DocumentFinder.search(session, customers, new Search("42", Match.EQUALS, false), false);
      assertEquals(List.of(new Hits.Hit("score", 1)), number.columns());
      assertFalse(number.more());
      assertTrue(number.searched().contains("_id"));
    }
  }
}
