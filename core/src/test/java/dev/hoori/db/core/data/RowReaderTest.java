package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.hoori.db.core.connection.Provider;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Long text budget and cutting of {@link RowReader} without a database server. */
class RowReaderTest {

  private record ColumnMeta(String label, String typeName, int jdbcType, int precision) {}

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"POSTGRESQL", "MYSQL", "MARIADB"})
  void longTextsAreKeptWholeWhileWithinBudgetAndCutBeyondIt(Provider provider) throws Exception {
    var metadata = metadata(List.of(
        new ColumnMeta("t", "text", Types.VARCHAR, 0),
        new ColumnMeta("j", "json", Types.OTHER, 0)));
    var reader = RowReader.of(provider, metadata, 4_000);
    var firstText = "a".repeat(2_000);
    var firstJson = "{\"v\":\"" + "b".repeat(1_500) + "\"}";
    var exactLimit = "😀".repeat(Rows.LIMIT);
    var overBoundary = "a".repeat(Rows.LIMIT - 1) + "😀" + "c".repeat(1_000);
    // Row 1: two long texts fit in the 4,000-character budget (2,000 + 1,508 = 3,508).
    assertEquals(List.of(firstText, firstJson), reader.read(row(firstText, firstJson)));
    // Row 2: a string of exactly Rows.LIMIT code points (2,048 chars) does not spend the budget.
    assertEquals(List.of(exactLimit, "short"), reader.read(row(exactLimit, "short")));
    // Row 3: beyond what is left (492 chars), cut to Rows.LIMIT code points without splitting
    // emoji.
    var third = reader.read(row(overBoundary, null));
    assertEquals(new Cut("a".repeat(Rows.LIMIT - 1) + "😀", OptionalLong.empty()), third.get(0));
    assertNull(third.get(1));
  }

  @Test
  void aValueLargerThanWhatIsLeftIsCutWithoutSpendingTheBudget() throws Exception {
    var metadata = metadata(List.of(new ColumnMeta("t", "text", Types.VARCHAR, 0)));
    var reader = RowReader.of(Provider.POSTGRESQL, metadata, 2_500);
    var tooBig = "x".repeat(3_000);
    var fits = "y".repeat(2_500);
    var after = "z".repeat(Rows.LIMIT + 1);
    assertEquals(
        new Cut("x".repeat(Rows.LIMIT), OptionalLong.empty()),
        reader.read(row(tooBig)).getFirst());
    assertEquals(fits, reader.read(row(fits)).getFirst());
    assertEquals(
        new Cut("z".repeat(Rows.LIMIT), OptionalLong.empty()),
        reader.read(row(after)).getFirst());
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MYSQL", "MARIADB"})
  void binaryDataStaysCutToLimitWithoutSpendingTheTextBudget(Provider provider) throws Exception {
    var metadata = metadata(List.of(
        new ColumnMeta("b", "longblob", Types.LONGVARBINARY, 0),
        new ColumnMeta("t", "text", Types.VARCHAR, 0)));
    var reader = RowReader.of(provider, metadata, 2_000);
    var bytes = new byte[Rows.LIMIT + 50];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i & 0xff);
    }
    var text = "t".repeat(2_000);
    var values = reader.read(row(bytes, text));
    var binary = (Binary) values.get(0);
    assertEquals(Rows.LIMIT, binary.bytes().length);
    assertEquals(bytes.length, binary.length());
    assertEquals(text, values.get(1));
  }

  @Test
  void aValueLongerThanTheWholeLimitIsCutWithinTheBudget() throws Exception {
    var metadata = metadata(List.of(new ColumnMeta("t", "text", Types.VARCHAR, 0)));
    var reader = RowReader.of(Provider.POSTGRESQL, metadata);
    var most = "w".repeat(Rows.WHOLE_LIMIT);
    assertEquals(most, reader.read(row(most)).getFirst());
    assertEquals(
        new Cut("w".repeat(Rows.LIMIT), OptionalLong.empty()),
        reader.read(row(most + "w")).getFirst());
  }

  private static ResultSetMetaData metadata(List<ColumnMeta> columns) {
    return (ResultSetMetaData) Proxy.newProxyInstance(
        ResultSetMetaData.class.getClassLoader(),
        new Class<?>[] {ResultSetMetaData.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getColumnCount" -> columns.size();
          case "getColumnLabel" -> columns.get((int) args[0] - 1).label();
          case "getColumnTypeName" -> columns.get((int) args[0] - 1).typeName();
          case "getColumnType" -> columns.get((int) args[0] - 1).jdbcType();
          case "getPrecision" -> columns.get((int) args[0] - 1).precision();
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static ResultSet row(Object... values) {
    return (ResultSet) Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(),
        new Class<?>[] {ResultSet.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getString" -> (String) values[(int) args[0] - 1];
          case "getBytes" -> (byte[]) values[(int) args[0] - 1];
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
