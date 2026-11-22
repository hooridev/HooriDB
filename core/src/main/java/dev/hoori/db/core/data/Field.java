package dev.hoori.db.core.data;

/**
 * A column of a page or of a statement's result.
 *
 * @param type the type as the server names it, as the catalog read it; of a statement's result,
 *     as the driver names it
 * @param origin the table column the field was read from, where known for a statement's result;
 *     null for an expression, a system column, or a table page
 */
public record Field(String name, String type, Kind kind, Origin origin) {

  public Field(String name, String type, Kind kind) {
    this(name, type, kind, null);
  }
}
