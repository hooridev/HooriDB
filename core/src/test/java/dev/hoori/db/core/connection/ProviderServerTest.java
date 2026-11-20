package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** What a provider says about its servers, against real ones. */
class ProviderServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final String QUERY = "SELECT name FROM customer WHERE id = 1";

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theFormsOfExplainRunOnTheServer(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var version = Connector.test(server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .parsed();
    var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .connection();
    try {
      var plan = run(connection, provider.explain(QUERY, version));
      assertFalse(plan.isEmpty());

      var json = run(connection, provider.explainJson(QUERY, version));
      assertTrue(
          JsonMapper.builder().build().readTree(json.getFirst().getFirst()).isContainer());

      // It runs the statement: MariaDB's ANALYZE answers with its rows as counted, and the others
      // with the plan and its times as text.
      var analyzed = run(connection, provider.explainAnalyze(QUERY, version));
      assertFalse(analyzed.isEmpty());
    } finally {
      Connector.close(connection);
    }
  }

  private static List<List<String>> run(Connection connection, Optional<String> statement)
      throws Exception {
    try (var s = connection.createStatement();
        var rows = s.executeQuery(statement.orElseThrow())) {
      var columns = rows.getMetaData().getColumnCount();
      var result = new ArrayList<List<String>>();
      while (rows.next()) {
        var row = new ArrayList<String>();
        for (int i = 1; i <= columns; i++) {
          row.add(rows.getString(i));
        }
        result.add(row);
      }
      return result;
    }
  }
}
