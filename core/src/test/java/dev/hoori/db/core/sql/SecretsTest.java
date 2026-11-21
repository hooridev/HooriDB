package dev.hoori.db.core.sql;

import static dev.hoori.db.core.sql.Secrets.MASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Passwords masked in statements before they are logged, by each provider's rules. */
class SecretsTest {

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"POSTGRESQL", "MARIADB", "MYSQL"})
  void aStatementWithoutAPasswordIsKeptAsItIs(Provider provider) {
    var sql = "SELECT id, 'x' FROM t WHERE name = 'ada'";
    assertSame(sql, Secrets.masked(sql, provider));
    // A quoted name or one after a period is a column, not the keyword.
    var column = provider.quote("password");
    var quoted = "SELECT " + column + ", t.password FROM t WHERE name = 'ada'";
    assertEquals(quoted, Secrets.masked(quoted, provider));
  }

  @Test
  void postgreSqlRolesAndUsers() {
    var pg = Provider.POSTGRESQL;
    assertEquals(
        "CREATE ROLE app LOGIN PASSWORD " + MASK,
        Secrets.masked("CREATE ROLE app LOGIN PASSWORD 'hunter2'", pg));
    assertEquals(
        "alter user app with encrypted password " + MASK + " valid until " + MASK,
        Secrets.masked(
            "alter user app with encrypted password 'md5abc' valid until '2030-01-01'", pg));
    assertEquals(
        "CREATE USER app PASSWORD " + MASK, Secrets.masked("CREATE USER app PASSWORD $$x'y$$", pg));
    assertEquals(
        "CREATE USER app PASSWORD " + MASK,
        Secrets.masked("CREATE USER app PASSWORD E'it\\'s'", pg));
    // A backslash escapes with standard_conforming_strings off: masked either way.
    assertEquals(
        "ALTER ROLE app PASSWORD " + MASK, Secrets.masked("ALTER ROLE app PASSWORD 'a\\'b'", pg));
  }

  @Test
  void aConnectionStringThatGivesAPasswordIsMaskedWhereverItStands() {
    assertEquals(
        "SELECT dblink_connect(" + MASK + ")",
        Secrets.masked(
            "SELECT dblink_connect('host=db user=app password=hunter2')", Provider.POSTGRESQL));
    assertEquals(
        "SELECT dblink_connect(" + MASK + ")",
        Secrets.masked(
            "SELECT dblink_connect('postgresql://app:hunter2@db/example')", Provider.POSTGRESQL));
    // A user mapping's quoted option, as its plain one; a quoted column of that name is no option.
    assertEquals(
        "CREATE USER MAPPING FOR app SERVER remote OPTIONS (\"password\" " + MASK + ")",
        Secrets.masked(
            "CREATE USER MAPPING FOR app SERVER remote OPTIONS (\"password\" 'remote-secret')",
            Provider.POSTGRESQL));
    var column = "SELECT \"password\" FROM t WHERE name = 'ada'";
    assertEquals(column, Secrets.masked(column, Provider.POSTGRESQL));
    // A URI without a password is kept.
    var plain = "SELECT dblink_connect('postgresql://app@db/example')";
    assertSame(plain, Secrets.masked(plain, Provider.POSTGRESQL));
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void mariaDbAndMySqlAccounts(Provider provider) {
    assertEquals(
        "CREATE USER 'app'@'%' IDENTIFIED BY " + MASK,
        Secrets.masked("CREATE USER 'app'@'%' IDENTIFIED BY 'hunter2'", provider));
    assertEquals(
        "ALTER USER app IDENTIFIED WITH caching_sha2_password BY " + MASK + " REPLACE " + MASK,
        Secrets.masked(
            "ALTER USER app IDENTIFIED WITH caching_sha2_password BY 'new' REPLACE 'old'",
            provider));
    assertEquals(
        "SET PASSWORD FOR " + MASK + "@" + MASK + " = " + MASK,
        Secrets.masked("SET PASSWORD FOR 'app'@'%' = 'hunter2'", provider));
    assertEquals(
        "SET PASSWORD = PASSWORD(" + MASK + ")",
        Secrets.masked("SET PASSWORD = PASSWORD(\"hunter2\")", provider));
    assertEquals(
        "GRANT ALL ON *.* TO app IDENTIFIED BY " + MASK,
        Secrets.masked("GRANT ALL ON *.* TO app IDENTIFIED BY 'hunter2'", provider));
    assertEquals(
        "CHANGE REPLICATION SOURCE TO SOURCE_HOST = 'db', SOURCE_PASSWORD = " + MASK,
        Secrets.masked(
            "CHANGE REPLICATION SOURCE TO SOURCE_HOST = 'db', SOURCE_PASSWORD = 'hunter2'",
            provider));
    // Without NO_BACKSLASH_ESCAPES, and with it: masked to the end either way.
    var escaped = Secrets.masked("CREATE USER app IDENTIFIED BY 'a\\'b' -- 'c'", provider);
    assertFalse(escaped.contains("b'"), escaped);
  }

  @Test
  void aPasswordInAnExecutableCommentIsMasked() {
    var masked =
        Secrets.masked("CREATE USER app /*!80000 IDENTIFIED BY 'hunter2' */", Provider.MYSQL);
    assertFalse(masked.contains("hunter2"), masked);
    // With NO_BACKSLASH_ESCAPES the backslash ends the first string, and a second one follows.
    var two = Secrets.masked(
        "CREATE USER app /*!80000 IDENTIFIED BY 'a\\', other IDENTIFIED BY 'hunter2' */",
        Provider.MYSQL);
    assertFalse(two.contains("hunter2"), two);
    // A URI in a comment the server runs, without a word a password follows.
    var uri = Secrets.masked(
        "INSERT INTO c(uri) VALUES (/*!80000 'mysql://app:hunter2@db/example' */)", Provider.MYSQL);
    assertFalse(uri.contains("hunter2"), uri);
    var plain = "SELECT 1 /*!80000 , 'ada' */";
    assertSame(plain, Secrets.masked(plain, Provider.MYSQL));
    var mariaDb = Secrets.masked(
        "CREATE USER app IDENTIFIED VIA ed25519 USING PASSWORD('hunter2')", Provider.MARIADB);
    assertEquals("CREATE USER app IDENTIFIED VIA ed25519 USING PASSWORD(" + MASK + ")", mariaDb);
  }

  @Test
  void mongoDbCommandsHaveTheirPasswordsMasked() {
    var dialect = Provider.MONGODB.documents();
    var create = dialect.shown(Document.of(
        "createUser",
        "app",
        "pwd",
        "hunter2",
        "roles",
        Document.array(Document.of("role", "read", "db", "hoori"))));
    assertFalse(create.contains("hunter2"), create);
    assertEquals(
        "db.runCommand(" + Document.of("updateUser", "app", "pwd", "***") + ")",
        dialect.shown(Document.of("updateUser", "app", "pwd", "hunter2")));
    var nested = Document.of("applyOps", List.of(Document.of("o", Document.of("pwd", "x"))));
    assertFalse(dialect.shown(nested).contains("\"x\""), dialect.shown(nested));
    var find = Document.of("find", "orders", "filter", Document.of("total", 3));
    assertEquals("db.runCommand(" + find + ")", dialect.shown(find));
  }
}
