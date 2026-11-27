package dev.hoori.db.core.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.find.Search.Match;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.query.QueryException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Searches of the MongoDB test server's sample, on a read-only session. */
class DocumentFinderServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final Table CUSTOMERS = new Table(HOORI, "customers", Table.Kind.TABLE);

  private static DocumentSession session(boolean readOnly) throws Exception {
    return session(TestServer.of(Provider.MONGODB).profile(), readOnly);
  }

  private static DocumentSession session(ConnectionProfile profile, boolean readOnly)
      throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var opened = DocumentConnector.open(profile, TestServer.password(), server.driver(), TIMEOUT);
    return new DocumentSession(opened.client(), Provider.MONGODB.documents(), readOnly);
  }

  private interface Body {
    void run(DocumentSession session, Candidate collection) throws Exception;
  }

  // A collection of the test's own with the documents, searched on a read-only session.
  private static void with(List<Document> documents, Body body) throws Exception {
    var collection =
        new Table(HOORI, "find_" + UUID.randomUUID().toString().substring(0, 8), Table.Kind.TABLE);
    try (var writer = session(false)) {
      writer.command(
          HOORI.name(),
          Document.of(
              "insert", collection.name(), "documents", Document.array(List.copyOf(documents))));
      try (var reader = session(true)) {
        var candidate =
            new Candidate(collection, OptionalLong.empty(), OptionalLong.empty(), List.of());
        body.run(reader, candidate);
      } finally {
        writer.command(HOORI.name(), Document.of("drop", collection.name()));
      }
    }
  }

  @Test
  void collectionsAndTheirFieldsAreFoundByName() throws Exception {
    try (var session = session(true)) {
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
    try (var session = session(true)) {
      var sent = new ArrayList<Sent>();
      var candidates =
          Journal.recording(sent::add, () -> DocumentFinder.candidates(session, HOORI));
      var customers = candidates.stream()
          .filter(c -> c.table().equals(CUSTOMERS))
          .findFirst()
          .orElseThrow();
      assertEquals(OptionalLong.of(3), customers.rows());
      var london = Journal.recording(
          sent::add,
          () ->
              DocumentFinder.search(session, customers, new Search("LONDON", Match.EQUALS, false)));
      assertEquals(List.of(new Hits.Hit("address.city", 1)), london.columns(), "" + london);
      var tags = DocumentFinder.search(session, customers, new Search("av", Match.CONTAINS, true));
      assertEquals(List.of(new Hits.Hit("tags", 1)), tags.columns());
      var a = DocumentFinder.search(session, customers, new Search("^a", Match.PATTERN, false));
      assertEquals(List.of(new Hits.Hit("name", 1), new Hits.Hit("email", 1)), a.columns());
      var number = DocumentFinder.search(session, customers, new Search("42", Match.EQUALS, false));
      assertEquals(List.of(new Hits.Hit("score", 1)), number.columns());
      assertFalse(number.more());
      assertTrue(number.searched().contains("_id"));
      // The collections, their counts, samples and searches go to the log as a search's.
      Journal.recording(
          sent::add,
          () -> DocumentFinder.objects(session, HOORI, new Search("x", Match.CONTAINS, false)));
      assertFalse(sent.isEmpty());
      for (var each : sent) {
        assertEquals(Purpose.SEARCH, each.purpose(), each.text());
      }
    }
  }

  @Test
  void aPatternEndsALineAtALineFeedAloneAsTheServerDoes() throws Exception {
    with(List.of(Document.of("_id", 1, "note", "a\rb")), (session, candidate) -> {
      var hits =
          DocumentFinder.search(session, candidate, new Search("^a.b$", Match.PATTERN, true));
      assertEquals(List.of(new Hits.Hit("note", 1)), hits.columns(), "" + hits);
    });
  }

  @Test
  void aPatternIsTheServersAsItCountsTheFieldsToo() throws Exception {
    // A class of POSIX's, which PCRE2 reads and Java's patterns do not.
    with(List.of(Document.of("_id", 1, "note", "7")), (session, candidate) -> {
      var hits = DocumentFinder.search(
          session, candidate, new Search("^[[:digit:]]$", Match.PATTERN, true));
      assertEquals(List.of(new Hits.Hit("note", 1)), hits.columns(), "" + hits);
    });
  }

  @Test
  void aNameThatAPathCannotNameIsNoField() throws Exception {
    var document = Document.of(
        "_id",
        1,
        "x.y",
        "needle",
        "$z",
        "needle",
        "w",
        "needle",
        "v",
        Document.of("u.t", "needle"));
    with(List.of(document), (session, candidate) -> {
      var hits =
          DocumentFinder.search(session, candidate, new Search("needle", Match.CONTAINS, true));
      assertEquals(List.of("w"), hits.searched(), "" + hits);
      assertEquals(List.of(new Hits.Hit("w", 1)), hits.columns());
      var found = DocumentFinder.objects(session, HOORI, new Search("y", Match.EQUALS, false));
      assertTrue(found.isEmpty(), found.toString());
    });
  }

  @Test
  void theCountsPastTheLimitStopWithTheServer() throws Exception {
    var documents = new ArrayList<Document>();
    for (int i = 1; i <= Search.LIMIT + 5; i++) {
      documents.add(Document.of("_id", i, "word", "apple " + i));
    }
    with(documents, (session, candidate) -> {
      var hits =
          DocumentFinder.search(session, candidate, new Search("apple", Match.CONTAINS, false));
      assertTrue(hits.more());
      assertEquals(List.of(new Hits.Hit("word", Search.LIMIT)), hits.columns());
    });
  }

  @Test
  void aCollectionTheUserMayNotReadIsChosenAndFoundByItsNameAlone() throws Exception {
    var name = "find_" + UUID.randomUUID().toString().substring(0, 8);
    var hoori = HOORI.name();
    try (var admin = session(false)) {
      admin.command(
          hoori,
          Document.of(
              "createRole",
              name,
              "privileges",
              Document.array(
                  Document.of(
                      "resource",
                      Document.of("db", hoori, "collection", ""),
                      "actions",
                      Document.array("listCollections")),
                  Document.of(
                      "resource",
                      Document.of("db", hoori, "collection", "customers"),
                      "actions",
                      Document.array("find"))),
              "roles",
              Document.array()));
      try {
        admin.command(
            hoori,
            Document.of(
                "createUser", name, "pwd", TestServer.PASSWORD, "roles", Document.array(name)));
        var profile =
            TestServer.of(Provider.MONGODB).profile().withUser(name).withAuthDatabase(hoori);
        try (var session = session(profile, true)) {
          var candidates = DocumentFinder.candidates(session, HOORI);
          var rows = candidates.stream()
              .filter(c -> c.table().name().equals("orders") || c.table().equals(CUSTOMERS))
              .map(Candidate::rows)
              .toList();
          assertEquals(List.of(OptionalLong.of(3), OptionalLong.empty()), rows);
          var found =
              DocumentFinder.objects(session, HOORI, new Search("o", Match.CONTAINS, false));
          var names = found.stream().map(Found::name).toList();
          assertTrue(names.contains("orders"), names.toString());
          assertTrue(names.contains("born"), names.toString());
          var orders = candidates.stream()
              .filter(c -> c.table().name().equals("orders"))
              .findFirst()
              .orElseThrow();
          var denied = assertThrows(
              QueryException.class,
              () -> DocumentFinder.search(session, orders, new Search("1", Match.EQUALS, false)));
          assertEquals(QueryException.Failure.ACCESS_DENIED, denied.failure());
        }
      } finally {
        admin.command(hoori, Document.of("dropUser", name));
        admin.command(hoori, Document.of("dropRole", name));
      }
    }
  }
}
