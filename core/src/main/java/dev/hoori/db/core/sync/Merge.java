package dev.hoori.db.core.sync;

import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.KeyedRead;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rows of two tables merged by their key: each read in the key's order from its {@link
 * Cursor}, a portion at a time, so that only a portion of each is held at once, and paired where
 * their keys are equal. Rows alike in every compared column are counted; each other row is a
 * {@link Difference}, told one at a time in the key's order.
 *
 * <p>The pairing holds only while both reads give their keys in the order {@link
 * KeyedRead#compare} puts them, each key once; a key that comes out of that order, as where a
 * server orders a value in another way, ends the merge with a {@link CompareException} rather than
 * pairing rows that are not each other's. One thread uses it.
 */
public final class Merge {

  private final Side source;
  private final Side target;
  private Pairing pairing; // as the fields of both reads say, once they began
  private long onlyInSource;
  private long onlyInTarget;
  private long different;
  private long equal;

  /** Merges the rows of the cursors as the pairing pairs them; nothing is read yet. */
  public Merge(Pairing pairing, Cursor source, Cursor target) {
    this.pairing = pairing;
    this.source = new Side("source", source, pairing.sourceKey());
    this.target = new Side("target", target, pairing.targetKey());
  }

  /**
   * The next difference in the key's order, or null once both tables are read to their end.
   *
   * @throws QueryException where a cursor failed
   * @throws InterruptedException where the thread was interrupted
   * @throws CompareException where a key came out of order, or the key's columns read as kinds
   *     that sort apart
   */
  public Difference next() throws QueryException, InterruptedException, CompareException {
    if (!source.began || !target.began) {
      source.peek();
      target.peek();
      pairing = pairing.read(source.fields, target.fields);
    }
    while (true) {
      var mine = source.peek();
      var theirs = target.peek();
      if (mine == null && theirs == null) {
        return null;
      }
      int by = mine == null ? 1 : theirs == null ? -1 : compare(mine, theirs);
      if (by < 0) {
        onlyInSource++;
        return new Difference.OnlyInSource(source.take());
      }
      if (by > 0) {
        onlyInTarget++;
        return new Difference.OnlyInTarget(target.take());
      }
      source.take();
      target.take();
      var cells = new ArrayList<Pairing.Pair>();
      for (var pair : pairing.columns()) {
        if (!same(mine.get(pair.source()), theirs.get(pair.target()))) {
          cells.add(pair);
        }
      }
      if (cells.isEmpty()) {
        equal++;
      } else {
        different++;
        return new Difference.Different(mine, theirs, cells);
      }
    }
  }

  /** The pairing as the reads of both tables say their kinds, once {@link #next} was asked. */
  public Pairing pairing() {
    return pairing;
  }

  /** The source's columns as its read says them, once {@link #next} was asked; none before. */
  public List<Field> sourceFields() {
    return source.fields;
  }

  /** What was read and found so far; at the end, of the whole tables. */
  public Tally tally() {
    return new Tally(onlyInSource, onlyInTarget, different, equal, source.read, target.read);
  }

  // The order of the two rows' keys.
  private int compare(List<Object> mine, List<Object> theirs) {
    for (var pair : pairing.key()) {
      int by = KeyedRead.compare(mine.get(pair.source()), theirs.get(pair.target()));
      if (by != 0) {
        return by;
      }
    }
    return 0;
  }

  /**
   * Whether the values are the same: both NULL, or equal as {@link KeyedRead#compare} orders
   * them, so that numbers are the same by their value, whatever their type or scale, and moments
   * where they are the same instant.
   */
  public static boolean same(Object a, Object b) {
    return Objects.equals(a, b) || a != null && b != null && KeyedRead.compare(a, b) == 0;
  }

  // One of the tables: its rows as they come, and the key of the last one taken.
  private static final class Side {
    private final String name;
    private final Cursor cursor;
    private final List<Integer> key;
    private List<Field> fields = List.of();
    private List<List<Object>> rows = List.of();
    private int at;
    private boolean began;
    private boolean over;
    private List<Object> last; // the key of the row taken last, or null
    private long read;

    Side(String name, Cursor cursor, List<Integer> key) {
      this.name = name;
      this.cursor = cursor;
      this.key = key;
    }

    // The row it is on, or null at the end.
    List<Object> peek() throws QueryException, InterruptedException {
      while (at == rows.size() && !over) {
        var next = cursor.next();
        began = true;
        fields = next.fields();
        rows = next.rows();
        at = 0;
        over = rows.isEmpty();
      }
      return over ? null : rows.get(at);
    }

    // Takes the row it is on, whose key must come after the last one's.
    List<Object> take() throws CompareException {
      var row = rows.get(at++);
      var keyed = key.stream().map(row::get).toList();
      if (last != null && order(last, keyed) >= 0) {
        throw new CompareException("The rows of the " + name + " did not come in the order of"
            + " the key at row " + (read + 1) + ", as its server orders a value of the key in"
            + " another way, so the rows cannot be paired.");
      }
      last = keyed;
      read++;
      return row;
    }

    private static int order(List<Object> a, List<Object> b) {
      for (int i = 0; i < a.size(); i++) {
        int by = KeyedRead.compare(a.get(i), b.get(i));
        if (by != 0) {
          return by;
        }
      }
      return 0;
    }
  }
}
