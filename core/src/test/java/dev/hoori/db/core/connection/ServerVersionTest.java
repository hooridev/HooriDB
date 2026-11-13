package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerVersionTest {

  @Test
  void parsesWhatServersReport() {
    assertEquals(new ServerVersion(17, 2, 0), ServerVersion.parse("17.2"));
    assertEquals(
        new ServerVersion(16, 4, 0), ServerVersion.parse("16.4 (Debian 16.4-1.pgdg120+2)"));
    assertEquals(new ServerVersion(9, 6, 24), ServerVersion.parse("9.6.24"));
    assertEquals(new ServerVersion(18, 0, 0), ServerVersion.parse("18beta1"));
    assertEquals(new ServerVersion(11, 4, 2), ServerVersion.parse("11.4.2-MariaDB-ubu2404"));
    assertEquals(new ServerVersion(8, 0, 39), ServerVersion.parse("8.0.39"));
    assertEquals(new ServerVersion(8, 4, 3), ServerVersion.parse("8.4.3-0ubuntu1"));
    assertEquals(new ServerVersion(8, 0, 39), ServerVersion.parse(" 8.0.39-log\n"));
  }

  @Test
  void dropsThePrefixOfOldMariaDbHandshakes() {
    assertEquals(new ServerVersion(10, 6, 18), ServerVersion.parse("5.5.5-10.6.18-MariaDB"));
    assertEquals(new ServerVersion(5, 5, 5), ServerVersion.parse("5.5.5"));
    assertEquals(new ServerVersion(5, 5, 5), ServerVersion.parse("5.5.5-log"));
  }

  @Test
  void unparsableIsUnknown() {
    for (var text :
        List.of("", "   ", "MariaDB", "v17.2", "PostgreSQL 17.2", ".5", "99999999999.1")) {
      assertEquals(ServerVersion.UNKNOWN, ServerVersion.parse(text), text);
    }
    assertEquals(ServerVersion.UNKNOWN, ServerVersion.parse(null));
  }

  @Test
  void unknownIsOlderThanEveryVersion() {
    assertTrue(ServerVersion.UNKNOWN.compareTo(ServerVersion.parse("0.0.1")) < 0);
    assertFalse(ServerVersion.UNKNOWN.atLeast(ServerVersion.parse("7.2")));
  }

  @Test
  void comparesNumbersNotText() {
    var sorted =
        List.of("5.6.5", "8.0.9", "8.0.18", "8.4", "9.6.24", "10.1.2", "11.4.2", "17.2").stream()
            .map(ServerVersion::parse)
            .toList();
    var shuffled = new ArrayList<>(sorted);
    Collections.reverse(shuffled);
    Collections.sort(shuffled);
    assertEquals(sorted, shuffled);

    assertEquals(0, ServerVersion.parse("17.2").compareTo(ServerVersion.parse("17.2.0")));
    assertTrue(ServerVersion.parse("8.0.18").atLeast(ServerVersion.parse("8.0.18")));
    assertFalse(ServerVersion.parse("8.0.17").atLeast(ServerVersion.parse("8.0.18")));
  }
}
