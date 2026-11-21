package dev.hoori.db.core.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
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
      // A field of dates, which no text or number is found in, has a name all the same.
      var born = DocumentFinder.objects(session, HOORI, new Search("born", Match.EQUALS, false));
      assertEquals(
          List.of(new Found(Found.Kind.COLUMN, HOORI, CUSTOMERS, "born", false, null, 0, 4)), born);
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
      var london =
          DocumentFinder.search(session, customers, new Search("LONDON", Match.EQUALS, false));
      assertEquals(List.of(new Hits.Hit("address.city", 1)), london.columns(), "" + london);
      var tags = DocumentFinder.search(session, customers, new Search("av", Match.CONTAINS, true));
      assertEquals(List.of(new Hits.Hit("tags", 1)), tags.columns());
      var a = DocumentFinder.search(session, customers, new Search("^a", Match.PATTERN, false));
      assertEquals(List.of(new Hits.Hit("name", 1), new Hits.Hit("email", 1)), a.columns());
      var number = DocumentFinder.search(session, customers, new Search("42", Match.EQUALS, false));
      assertEquals(List.of(new Hits.Hit("score", 1)), number.columns());
      assertFalse(number.more());
      assertTrue(number.searched().contains("_id"));
    }
  }

  @Test
  void aPatternEndsALineAtALineFeedAloneAsTheServerDoes() throws Exception {
    var lines = new Table(HOORI, "find_lines", Table.Kind.TABLE);
    var opened = TestServer.of(Provider.MONGODB);
    try (var session = new DocumentSession(
        DocumentConnector.open(opened.profile(), TestServer.password(), opened.driver(), TIMEOUT)
            .client(),
        Provider.MONGODB.documents(),
        false)) {
      try {
        session.command(
            HOORI.name(),
            Document.of(
                "insert",
                lines.name(),
                "documents",
                Document.array(Document.of("_id", 1, "note", "a\rb"))));
        var candidate = new Candidate(lines, OptionalLong.empty(), OptionalLong.empty(), List.of());
        var hits =
            DocumentFinder.search(session, candidate, new Search("^a.b$", Match.PATTERN, true));
        assertEquals(List.of(new Hits.Hit("note", 1)), hits.columns(), "" + hits);
      } finally {
        session.command(HOORI.name(), Document.of("drop", lines.name()));
      }
    }
  }
}
