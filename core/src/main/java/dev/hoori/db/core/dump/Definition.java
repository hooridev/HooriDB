package dev.hoori.db.core.dump;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The statements of SQL that create an object as it is now, each without its terminator, and what
 * it needs to be there before it. The parts are apart so that an export can put them where they
 * belong: the indexes and foreign keys of tables after their rows, and what sets a sequence's value
 * or refreshes a materialized view after all rows.
 *
 * @param create what creates it, with its comments and what it owns, as a sequence its column
 * @param body whether {@code create} holds statements of their own, ended by semicolons, as a
 *     routine or a trigger of MariaDB and MySQL, which their clients read only after a {@code
 *     DELIMITER}
 * @param indexes a table's or materialized view's indexes that no constraint makes
 * @param keys a table's foreign keys
 * @param dropKeys what drops its foreign keys where they exist, so that the tables they join can go
 *     in any order
 * @param drop what drops it where it exists
 * @param restore what gives it, after the rows, the state it has besides them: a sequence's value,
 *     that of a table's identity column; a populated materialized view's refresh, from its tables'
 *     rows as they are then
 * @param afterRows what follows its rows wherever they go, as it would refuse or hide them: a
 *     table's constraints that are not valid, which rows already there need not pass, and its row
 *     security with its policies
 * @param needs the objects that must be there before it, as a view's tables; a table's foreign keys
 *     are not among them
 * @param refers the tables its foreign keys refer to, itself too where one does, whose rows go
 *     first where they can
 */
public record Definition(
    Item item,
    List<String> create,
    boolean body,
    List<String> indexes,
    List<String> keys,
    List<String> dropKeys,
    String drop,
    List<String> restore,
    List<String> afterRows,
    Set<Item> needs,
    Set<Item> refers) {

  public Definition {
    create = List.copyOf(create);
    indexes = List.copyOf(indexes);
    keys = List.copyOf(keys);
    dropKeys = List.copyOf(dropKeys);
    restore = List.copyOf(restore);
    afterRows = List.copyOf(afterRows);
    needs = Set.copyOf(needs);
    refers = Set.copyOf(refers);
  }

  /**
   * Its statements in the order that creates it on its own: itself, its indexes, its keys, and
   * what follows its rows.
   */
  public List<String> statements() {
    var all = new ArrayList<String>(create);
    all.addAll(indexes);
    all.addAll(keys);
    all.addAll(afterRows);
    return List.copyOf(all);
  }
}
