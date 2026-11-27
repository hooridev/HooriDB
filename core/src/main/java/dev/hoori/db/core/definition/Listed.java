package dev.hoori.db.core.definition;

import java.util.OptionalLong;

/**
 * An object as an export lists it, a table with what the server estimates of it.
 *
 * @param rows the server's estimate of a table's rows, empty where it has none or for any other
 *     object
 * @param bytes the server's estimate of the size of a table's rows on disk, empty as {@code rows}
 * @param consistent whether a table's rows are read in the export's snapshot: not those of an
 *     engine without transactions, as MyISAM, which are read as they are at that moment
 */
public record Listed(Item item, OptionalLong rows, OptionalLong bytes, boolean consistent) {

  /** An object that is not a table, or one without estimates. */
  public static Listed of(Item item) {
    return new Listed(item, OptionalLong.empty(), OptionalLong.empty(), true);
  }
}
