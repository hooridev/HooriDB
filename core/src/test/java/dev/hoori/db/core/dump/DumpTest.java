package dev.hoori.db.core.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.connection.Provider;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DumpTest {

  @Test
  void anExportRefusesAConnectionWithATransactionOpenAndLeavesItAlone() {
    for (var provider : List.of(Provider.POSTGRESQL, Provider.MYSQL, Provider.MARIADB)) {
      var calls = new ArrayList<String>();
      var connection = (Connection) Proxy.newProxyInstance(
          Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (_, method, _) -> {
            calls.add(method.getName());
            if (method.getName().equals("getAutoCommit")) {
              return false;
            }
            throw new AssertionError("Called " + method.getName());
          });
      assertThrows(
          IllegalArgumentException.class, () -> Dump.definitions(connection, provider, List.of()));
      assertEquals(List.of("getAutoCommit"), calls, provider.name());
    }
  }
}
