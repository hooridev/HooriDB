package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryStoreTest {

  private static final UUID SHOP = UUID.randomUUID();
  private static final UUID DOCS = UUID.randomUUID();
  private static final Instant THEN = Instant.parse("2026-11-20T10:00:00Z");

  @TempDir
  Path folder;

  private static SavedQuery query(UUID connection, String name, String text) {
    return new SavedQuery(UUID.randomUUID(), connection, name, text, THEN);
  }

  @Test
  void whatIsSavedIsLoadedBack() throws Exception {
    var queries = List.of(
        query(SHOP, "Open orders", "SELECT * FROM orders WHERE paid = false"),
        query(DOCS, "Grüße 東京", "db.orders.find({ note: 'a \"b\"\n' })"));
    var store = new QueryStore(folder);
    assertEquals(List.of(), store.load());
    store.save(queries);
    assertEquals(queries, new QueryStore(folder).load());
  }

  @Test
  void aQueryOfTheSameNameIsReplacedInItsPlaceAndKeepsItsId() {
    var first = query(SHOP, "Orders", "SELECT 1");
    var other = query(DOCS, "Orders", "db.orders.find()");
    var all = QueryStore.put(QueryStore.put(List.of(), first), other);
    var again = query(SHOP, " orders ", "SELECT 2");
    var replaced = QueryStore.put(all, again);
    assertEquals(2, replaced.size());
    assertEquals(first.id(), replaced.get(0).id());
    assertEquals("SELECT 2", replaced.get(0).text());
    assertEquals(other, replaced.get(1));
    assertTrue(QueryStore.named(replaced, SHOP, "ORDERS"));
    assertFalse(QueryStore.named(replaced, SHOP, "Customers"));
  }

  @Test
  void aFileThatIsNotUnderstoodIsRefused() throws Exception {
    Files.writeString(folder.resolve(QueryStore.FILE), "{\"version\": 1, \"queries\": [{}]}");
    assertThrows(WorkspaceStore.UnreadableException.class, () -> new QueryStore(folder).load());
  }
}
