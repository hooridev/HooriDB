package dev.hoori.db.core.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class MySqlFamilyTest {

  private static Failure failure(String message, int code) {
    return MariaDb.INSTANCE
        .errors()
        .failure(Failure.Kind.INVALID, new SQLException(message), message, "42000", code);
  }

  @Test
  void aSyntaxErrorSaysWhereItIs() {
    var failure = failure(
        "(conn=7) You have an error in your SQL syntax; check the manual that corresponds to your"
            + " MariaDB server version for the right syntax to use near 'FROM t\nWHERE' at line 2",
        1064);
    assertEquals("FROM t\nWHERE", failure.near());
    assertEquals(2, failure.line());
    assertEquals(
        "You have an error in your SQL syntax; check the manual that corresponds to your MariaDB"
            + " server version for the right syntax to use near 'FROM t\nWHERE' at line 2",
        failure.message());
  }

  @Test
  void theTextFromThereMayHoldQuotesAndBeEmpty() {
    var quoted = failure("... to use near ''a'' at line 3' at line 1", 1064);
    assertEquals("'a'' at line 3", quoted.near());
    assertEquals(1, quoted.line());
    var empty = failure("... to use near '' at line 4", 1064);
    assertEquals("", empty.near());
    assertEquals(4, empty.line());
  }

  @Test
  void anotherErrorOrMessageSaysNothingOfWhere() {
    for (var failure : new Failure[] {
      failure("... to use near 'x' at line 2", 1146),
      failure("... to use near 'x' at line 2\n", 1064),
      failure("... to use near 'x' at line 1234567890", 1064),
      failure("... near 'x' at line 2", 1064),
      failure("' at line 2 to use near 'x", 1064)
    }) {
      assertEquals("", failure.near(), failure.message());
      assertEquals(0, failure.line(), failure.message());
    }
  }

  @Test
  void restrictIsNoActionOnMysqlFamilyOnly() {
    assertTrue(Provider.MYSQL.dialect().catalog().restrictIsNoAction());
    assertTrue(Provider.MARIADB.dialect().catalog().restrictIsNoAction());
    assertFalse(Provider.POSTGRESQL.dialect().catalog().restrictIsNoAction());
  }
}
