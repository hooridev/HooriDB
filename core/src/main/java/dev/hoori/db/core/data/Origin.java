package dev.hoori.db.core.data;

/**
 * The table column that a column of a statement's result was read from, as the driver reported it
 * without asking the server again.
 */
public sealed interface Origin {

  /** Whether the other origin belongs to the same table as this one. */
  boolean sameTable(Origin other);

  /**
   * A PostgreSQL column, by the unsigned OID of its table and its positive position ({@code
   * attnum}) in that table.
   */
  record Oid(long table, int position) implements Origin {

    @Override
    public boolean sameTable(Origin other) {
      return other instanceof Oid oid && table == oid.table;
    }
  }

  /** A MariaDB or MySQL column, by the names of its catalog (database), table and column. */
  record Named(String catalog, String table, String column) implements Origin {

    @Override
    public boolean sameTable(Origin other) {
      return other instanceof Named named
          && catalog.equals(named.catalog)
          && table.equals(named.table);
    }
  }
}
