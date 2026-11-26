package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Clauses.Problem;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClausesTest {

  private static void accepts(Provider provider, String clause, boolean readOnly) {
    assertEquals(
        Optional.empty(), Clauses.problem(clause, provider, readOnly), provider + ": " + clause);
  }

  private static void refuses(
      Provider provider, String clause, boolean readOnly, Problem expected) {
    assertEquals(
        Optional.of(expected),
        Clauses.problem(clause, provider, readOnly),
        provider + ": " + clause);
  }

  @Test
  void blankAndWellFormedClausesAreAccepted() {
    for (var provider : Provider.SQL) {
      for (var clause : List.of(
          "",
          "   \n\t  ",
          "id > 10 AND amount <= 99.5",
          "(status IN ('open', 'pending') OR total > (SELECT avg(total) FROM orders))",
          "label = '1) OR (1; DROP TABLE x /*'",
          "label = 'O''Brien'",
          "id > 1 /* inline comment */",
          "id > 1 -- trailing comment with ' and ( and ;",
          "amount DESC, id ASC",
          "CASE WHEN amount IS NULL THEN 1 ELSE 0 END, amount DESC")) {
        accepts(provider, clause, false);
        accepts(provider, clause, true);
      }
      accepts(provider, null, false);
      var q = String.valueOf(provider.dialect().syntax().identifierQuote());
      accepts(provider, q + "a;b)c" + q + " = 1", false);
    }
    accepts(POSTGRESQL, "t = $$a; ) /* b$$", false);
    accepts(POSTGRESQL, "t = E'O\\'Brien'", false);
    accepts(POSTGRESQL, "id > 1 /* outer /* inner */ still */", false);
    accepts(POSTGRESQL, "amount DESC NULLS LAST", false);
    for (var provider : List.of(MARIADB, MYSQL)) {
      accepts(provider, "id > 1 # trailing hash comment with ' and ;", false);
      accepts(provider, "label = 'C:\\\\'", false);
    }
  }

  @Test
  void multipleStatementsAndSemicolonsAreRefused() {
    for (var provider : Provider.SQL) {
      refuses(provider, "1=1; DROP TABLE x", false, Problem.MULTIPLE);
      refuses(provider, "id DESC; DROP TABLE x", false, Problem.MULTIPLE);
      refuses(provider, "1=1;", false, Problem.MULTIPLE);
      refuses(provider, "; DROP TABLE x", false, Problem.MULTIPLE);
      refuses(provider, "/* comment */; DROP TABLE x", false, Problem.MULTIPLE);
      // Caught under the reading where backslash does not escape in plain strings.
      refuses(provider, "a = '\\'; DROP TABLE x -- '", false, Problem.MULTIPLE);
    }
    for (var provider : List.of(MARIADB, MYSQL)) {
      assertTrue(
          Clauses.problem("1=1 /*!50000 ; DROP TABLE x */", provider, false).isPresent());
      refuses(provider, "DELIMITER //\n1=1 //\nDROP TABLE x", false, Problem.MULTIPLE);
    }
  }

  @Test
  void unclosedStringsIdentifiersAndBlockCommentsAreRefused() {
    for (var provider : Provider.SQL) {
      refuses(provider, "1=1 /*", false, Problem.UNCLOSED);
      refuses(provider, "/* unclosed", false, Problem.UNCLOSED);
      refuses(provider, "a = 'unclosed", false, Problem.UNCLOSED);
      refuses(provider, "a = \"unclosed", false, Problem.UNCLOSED);
      // Under the backslash-escape reading the closing quote is escaped.
      refuses(provider, "a = 'foo\\'", false, Problem.UNCLOSED);
    }
    refuses(POSTGRESQL, "1=1 /* outer /* inner */", false, Problem.UNCLOSED);
    refuses(POSTGRESQL, "a = $$unclosed", false, Problem.UNCLOSED);
    refuses(POSTGRESQL, "a = E'foo\\'", false, Problem.UNCLOSED);
    refuses(MARIADB, "1=1 /*M!100000 AND 1=1 */", false, Problem.UNCLOSED);
    for (var provider : List.of(MARIADB, MYSQL)) {
      refuses(provider, "`unclosed = 1", false, Problem.UNCLOSED);
      refuses(provider, "1=1 /*!50000 'unclosed */", false, Problem.UNCLOSED);
      refuses(provider, "1=1 /*!50000 /* unclosed */", false, Problem.UNCLOSED);
      // Each executable comment by itself, as the server runs some by its version and not others.
      refuses(provider, "1=1 /*!50000 ( */ /*!99999 ) */", false, Problem.UNCLOSED);
    }
  }

  @Test
  void unbalancedParenthesesAreRefused() {
    for (var provider : Provider.SQL) {
      refuses(provider, "1) OR (1", false, Problem.PARENTHESES);
      refuses(provider, "1=1)", false, Problem.PARENTHESES);
      refuses(provider, "(1=1", false, Problem.PARENTHESES);
      refuses(provider, ")()", false, Problem.PARENTHESES);
      refuses(provider, "((a = 1) AND (b = 2)", false, Problem.PARENTHESES);
      refuses(provider, "(a = 1)) AND (b = 2", false, Problem.PARENTHESES);
    }
    for (var provider : List.of(MARIADB, MYSQL)) {
      refuses(provider, "1=1 /*!50000 ) OR (1=1 */", false, Problem.UNCLOSED);
      refuses(provider, "1=1 /*!50000 ( */", false, Problem.UNCLOSED);
    }
  }

  @Test
  void readOnlyConnectionsRefuseReadWriteClauses() {
    var pgBypass = "set_config('default_transaction_read_only', 'off', false) IS NOT NULL";
    var pgShowBypass = "show = 1 AND set_config('transaction_read_only', 'off', false) IS NOT NULL";
    accepts(POSTGRESQL, pgBypass, false);
    refuses(POSTGRESQL, pgBypass, true, Problem.READ_ONLY);
    refuses(POSTGRESQL, pgShowBypass, true, Problem.READ_ONLY);

    for (var provider : List.of(MARIADB, MYSQL)) {
      var mysqlBypass = "@@transaction_read_only = 0";
      var execBypass = "1=1 /*!50000 AND @@tx_read_only = 0 */";
      accepts(provider, mysqlBypass, false);
      refuses(provider, mysqlBypass, true, Problem.READ_ONLY);
      refuses(provider, execBypass, true, Problem.UNCLOSED);
    }
  }
}
