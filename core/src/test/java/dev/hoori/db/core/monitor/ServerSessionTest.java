package dev.hoori.db.core.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.hoori.db.core.connection.Provider;
import org.junit.jupiter.api.Test;

class ServerSessionTest {

  static ServerSession masked(String statement) {
    var session = new ServerSession(
        "101", "101", "", false, true, "ada", "", "shop", "query", statement, null, "");
    return session.withStatement(Credentials.mask(statement, Provider.MYSQL));
  }

  @Test
  void statementsThatMaskAlikeKeepDigestsOfWhatTheServerTold() {
    var one = masked("SET PASSWORD = 'one'");
    var two = masked("SET PASSWORD = 'two'");
    assertEquals(one.statement(), two.statement());
    assertNotEquals(one.digest(), two.digest());
    assertEquals(one.digest(), masked("SET PASSWORD = 'one'").digest());
  }
}
