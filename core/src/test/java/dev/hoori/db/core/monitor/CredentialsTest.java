package dev.hoori.db.core.monitor;

import static dev.hoori.db.core.monitor.Credentials.MASK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import org.junit.jupiter.api.Test;

class CredentialsTest {

  private static final String M = "'" + MASK + "'";

  @Test
  void aStatementThatNamesACredentialLosesAllItsStrings() {
    assertEquals(
        "CREATE USER ada WITH PASSWORD " + M + " VALID UNTIL " + M,
        Credentials.mask(
            "CREATE USER ada WITH PASSWORD 'Pw-1' VALID UNTIL '2030-01-01'", Provider.POSTGRESQL));
    // The account's name goes with it: masking errs that way.
    assertEquals(
        "ALTER USER " + M + "@" + M + " IDENTIFIED BY " + M,
        Credentials.mask("ALTER USER 'ada'@'%' IDENTIFIED BY 'Pw-1'", Provider.MYSQL));
    assertEquals(
        "CHANGE MASTER TO MASTER_USER=" + M + ", MASTER_PASSWORD=" + M,
        Credentials.mask(
            "CHANGE MASTER TO MASTER_USER='r', MASTER_PASSWORD='Pw-1'", Provider.MARIADB));
  }

  @Test
  void otherStatementsLoseOnlyTheStringsThatHoldOne() {
    assertEquals(
        "SELECT dblink_connect(" + M + "), 'plain'",
        Credentials.mask(
            "SELECT dblink_connect('host=db user=u password=Pw-1'), 'plain'", Provider.POSTGRESQL));
    assertEquals(
        "SELECT " + M + ", name FROM customer WHERE name = 'Ada'",
        Credentials.mask(
            "SELECT 'postgresql://u:Pw-1@db/x', name FROM customer WHERE name = 'Ada'",
            Provider.POSTGRESQL));
    // A word in a comment is no credential of the code.
    assertEquals(
        "SELECT name FROM t WHERE a = 'x' -- password",
        Credentials.mask("SELECT name FROM t WHERE a = 'x' -- password", Provider.POSTGRESQL));
  }

  @Test
  void aQuotedNameCountsAsCode() {
    assertEquals(
        "CREATE USER MAPPING FOR ada SERVER films OPTIONS (\"user\" " + M + ", \"password\" " + M
            + ")",
        Credentials.mask(
            "CREATE USER MAPPING FOR ada SERVER films OPTIONS (\"user\" 'ada', \"password\" 'Pw-1')",
            Provider.POSTGRESQL));
    assertEquals(
        "ALTER USER MAPPING FOR ada SERVER films OPTIONS (SET \"password\" " + M + ")",
        Credentials.mask(
            "ALTER USER MAPPING FOR ada SERVER films OPTIONS (SET \"password\" 'Pw-1')",
            Provider.POSTGRESQL));
    assertEquals(
        "SELECT \"password\" FROM t WHERE a = " + M,
        Credentials.mask("SELECT \"password\" FROM t WHERE a = 'x'", Provider.POSTGRESQL));
  }

  @Test
  void aNameWrittenWithUnicodeEscapesLosesAllStrings() {
    assertEquals(
        "ALTER USER MAPPING FOR ada SERVER films OPTIONS (SET U&\"\\0070assword\" " + M + ")",
        Credentials.mask(
            "ALTER USER MAPPING FOR ada SERVER films OPTIONS (SET U&\"\\0070assword\" 'Pw-1')",
            Provider.POSTGRESQL));
    assertEquals(
        "CREATE USER MAPPING FOR ada SERVER films OPTIONS (u&\"!0070assword\" UESCAPE " + M + " "
            + M + ")",
        Credentials.mask(
            "CREATE USER MAPPING FOR ada SERVER films OPTIONS (u&\"!0070assword\" UESCAPE '!' 'Pw-1')",
            Provider.POSTGRESQL));
  }

  @Test
  void aStringCutShortIsMaskedToo() {
    assertEquals(
        "SET PASSWORD = " + M,
        Credentials.mask("SET PASSWORD = 'Pw-1 and the rest was cut", Provider.MARIADB));
  }

  @Test
  void aDocumentLosesTheValuesOfItsCredentialsAtAnyDepth() {
    var command = Document.of(
        "createUser",
        "ada",
        "pwd",
        "Pw-1",
        "roles",
        Document.array(Document.of("role", "read", "db", "hoori")),
        "comment",
        Document.of("uri", "mongodb://u:Pw-1@db/x", "keyFile", "x"));
    assertEquals(
        Document.of(
            "createUser",
            "ada",
            "pwd",
            MASK,
            "roles",
            Document.array(Document.of("role", "read", "db", "hoori")),
            "comment",
            Document.of("uri", MASK, "keyFile", "x")),
        Credentials.mask(command));
  }

  @Test
  void theConnectionsOwnPasswordIsMaskedWhereverItIs() {
    assertEquals(
        "a " + MASK + " b " + MASK, Credentials.without("a Pw-1 b Pw-1", "Pw-1".toCharArray()));
    assertEquals("nothing", Credentials.without("nothing", "Pw-1".toCharArray()));
    assertEquals("empty", Credentials.without("empty", new char[0]));
  }

  @Test
  void theConnectionsOwnPasswordIsMaskedAsAStringOfSqlOrJsonWritesItToo() {
    var password = "ab'c\"d\\e".toCharArray();
    assertEquals("SELECT '" + MASK + "'", Credentials.without("SELECT 'ab''c\"d\\e'", password));
    assertEquals("SELECT '" + MASK + "'", Credentials.without("SELECT 'ab\\'c\"d\\\\e'", password));
    assertEquals(
        "{\"x\": \"" + MASK + "\"}", Credentials.without("{\"x\": \"ab'c\\\"d\\\\e\"}", password));
    assertEquals("a " + MASK, Credentials.without("a ab'c\"d\\e", password));
  }

  @Test
  void anExecutableCommentIsCode() {
    assertEquals(
        "SELECT /*" + MASK + "*/ SLEEP(30)",
        Credentials.mask("SELECT /*! 'postgresql://u:Pw-1@db/x', */ SLEEP(30)", Provider.MYSQL));
    assertEquals(
        "SELECT /*" + MASK + "*/ " + M,
        Credentials.mask("SELECT /*! PASSWORD */ 'Pw-1'", Provider.MARIADB));
    // An ordinary one stays.
    assertEquals(
        "SELECT 1 /* postgresql://u:x@db */",
        Credentials.mask("SELECT 1 /* postgresql://u:x@db */", Provider.MYSQL));
  }

  @Test
  void aVariableLosesItsValueWhereItsNameOrValueNamesACredential() {
    assertEquals(
        new ServerVariable("primary_conninfo", MASK, "configuration file"),
        Credentials.mask(new ServerVariable(
            "primary_conninfo", "host=db user=repl password=Pw-1", "configuration file")));
    assertEquals(
        new ServerVariable("ssl_key_password", MASK, ""),
        Credentials.mask(new ServerVariable("ssl_key_password", "x", "")));
    var plain = new ServerVariable("work_mem", "4MB", "default");
    assertEquals(plain, Credentials.mask(plain));
  }
}
