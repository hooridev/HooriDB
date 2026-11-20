package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import org.junit.jupiter.api.Test;

/** Each provider's key words, as its documentation lists them. */
class KeywordsTest {

  @Test
  void aKeywordIsOfferedOnlyWhereTheServerKnowsIt() {
    // RETURNING: reserved on PostgreSQL and MariaDB; MySQL has none.
    assertTrue(Keywords.offered(POSTGRESQL).contains("RETURNING"));
    assertTrue(Keywords.offered(MARIADB).contains("RETURNING"));
    assertFalse(Keywords.offered(MYSQL).contains("RETURNING"));
    // ILIKE is PostgreSQL's, STRAIGHT_JOIN MariaDB's and MySQL's.
    assertTrue(Keywords.offered(POSTGRESQL).contains("ILIKE"));
    assertFalse(Keywords.offered(MARIADB).contains("ILIKE"));
    assertFalse(Keywords.offered(MYSQL).contains("ILIKE"));
    assertFalse(Keywords.offered(POSTGRESQL).contains("STRAIGHT_JOIN"));
    assertTrue(Keywords.offered(MARIADB).contains("STRAIGHT_JOIN"));
    assertTrue(Keywords.offered(MYSQL).contains("STRAIGHT_JOIN"));
    // Non-reserved words typed often, where the server has them.
    assertTrue(Keywords.offered(POSTGRESQL).contains("CONFLICT"));
    assertFalse(Keywords.offered(MYSQL).contains("CONFLICT"));
    assertTrue(Keywords.offered(MARIADB).contains("ENGINE"));
    assertFalse(Keywords.offered(POSTGRESQL).contains("ENGINE"));
    for (var provider : Provider.SQL) {
      assertTrue(
          Keywords.offered(provider).containsAll(Keywords.reserved(provider)), provider.name());
      assertTrue(Keywords.offered(provider).contains("COMMIT"), provider.name());
      assertFalse(Keywords.reserved(provider).contains("COMMIT"), provider.name());
    }
  }

  @Test
  void aReservedWordIsQuotedWhereItIsReservedAndNotElsewhere() {
    assertEquals("\"returning\"", Names.quoted("returning", POSTGRESQL));
    assertEquals("`returning`", Names.quoted("returning", MARIADB));
    assertEquals("returning", Names.quoted("returning", MYSQL));
    assertEquals("\"ilike\"", Names.quoted("ilike", POSTGRESQL));
    assertEquals("ilike", Names.quoted("ilike", MYSQL));
    // MariaDB's documentation names DATABASE, SCHEMA and WINDOW as reserved in MySQL only.
    assertEquals("`database`", Names.quoted("database", MYSQL));
    assertEquals("database", Names.quoted("database", MARIADB));
    assertEquals("database", Names.quoted("database", POSTGRESQL));
    assertEquals("`Window`", Names.quoted("Window", MYSQL));
    assertEquals("Window", Names.quoted("Window", MARIADB));
    assertEquals("\"window\"", Names.quoted("window", POSTGRESQL));
    assertEquals("`offset`", Names.quoted("offset", MARIADB));
    // Reserved in MySQL 8.4 and 9, not on MariaDB; and the other way round.
    assertEquals("`qualify`", Names.quoted("qualify", MYSQL));
    assertEquals("`tablesample`", Names.quoted("tablesample", MYSQL));
    assertEquals("qualify", Names.quoted("qualify", MARIADB));
    assertEquals("`portion`", Names.quoted("portion", MARIADB));
    assertEquals("portion", Names.quoted("portion", MYSQL));
    // Reserved in MySQL 8.0 only, and from 9.7 on.
    assertEquals("`master_bind`", Names.quoted("master_bind", MYSQL));
    assertEquals("`library`", Names.quoted("library", MYSQL));
    assertEquals("offset", Names.quoted("offset", MYSQL));
  }
}
