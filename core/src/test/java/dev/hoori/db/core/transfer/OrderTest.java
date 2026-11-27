package dev.hoori.db.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final Database DB = new Database("db", false);
  private static final Table A = table("a");
  private static final Table B = table("b");
  private static final Table C = table("c");
  private static final Table D = table("d");

  @Test
  void aTableComesAfterTheTablesItRefersTo() {
    assertEquals(List.of(C, B, A), Order.of(List.of(A, B, C), List.of(key(A, B), key(B, C))));
  }

  @Test
  void keysToOtherTablesOrToTheirOwnDoNotCount() {
    assertEquals(List.of(A, B), Order.of(List.of(A, B), List.of(key(A, A), key(A, D), key(D, B))));
  }

  @Test
  void aCircleKeepsTheOrderGivenAfterWhatItRefersTo() {
    assertEquals(
        List.of(D, A, B, C),
        Order.of(List.of(A, B, C, D), List.of(key(A, B), key(B, A), key(C, A), key(A, D))));
  }

  private static Table table(String name) {
    return new Table(DB, name, Table.Kind.TABLE);
  }

  private static ForeignKey key(Table from, Table to) {
    return new ForeignKey(
        from.name() + "_" + to.name(), from, List.of("x"), to, List.of("id"), null, null);
  }
}
