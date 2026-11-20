package dev.hoori.db.core.document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A document as a server of documents keeps it: its fields in their order, each a name and a value.
 * A value is one of {@link Bson}'s: {@code null}, {@code Boolean}, {@code Integer} for a 32-bit
 * whole number, {@code Long} for a 64-bit one, {@code Double}, {@link Bson.Decimal128}, {@code
 * String}, {@code Instant} for a date, {@link Bson.ObjectId}, {@link Bson.Binary}, {@link
 * Bson.Timestamp}, {@link Bson.Regex}, {@link Bson.Code}, {@link Bson.Symbol}, {@link Bson.Special},
 * a {@code Document}, or a {@code List} of them for an array. Lists cannot be changed and may hold
 * {@code null}. A document compares by its fields in their order.
 */
public record Document(List<Entry> entries) {

  /** A field of a document. */
  public record Entry(String name, Object value) {
    public Entry {
      Objects.requireNonNull(name, "name");
      value = Bson.value(value);
    }
  }

  public static final Document EMPTY = new Document(List.of());

  public Document {
    entries = List.copyOf(entries);
  }

  /** A document of names and values in turn: {@code of("a", 1, "b", "x")}. */
  public static Document of(Object... namesAndValues) {
    if (namesAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("A name without a value");
    }
    var entries = new ArrayList<Entry>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      entries.add(new Entry((String) namesAndValues[i], namesAndValues[i + 1]));
    }
    return new Document(entries);
  }

  /** An array of the values, as a document holds it: a list that cannot be changed. */
  @SuppressWarnings("unchecked")
  public static List<Object> array(List<?> values) {
    return (List<Object>) Bson.value(values);
  }

  /** An array of the values. */
  public static List<Object> array(Object... values) {
    return array(Arrays.asList(values));
  }

  /** Whether it has a field of the name. */
  public boolean has(String name) {
    return entries.stream().anyMatch(e -> e.name().equals(name));
  }

  /** The value of its first field of the name, or {@code null} for none or a null. */
  public Object get(String name) {
    for (var entry : entries) {
      if (entry.name().equals(name)) {
        return entry.value();
      }
    }
    return null;
  }

  /** The names of its fields, in their order. */
  public List<String> names() {
    return entries.stream().map(Entry::name).toList();
  }

  public int size() {
    return entries.size();
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** The name of its first field, as a command's; empty for an empty document. */
  public String first() {
    return entries.isEmpty() ? "" : entries.getFirst().name();
  }

  /** A copy with the field set: replaced where it is, else added at the end. */
  public Document with(String name, Object value) {
    var next = new ArrayList<Entry>(entries.size() + 1);
    boolean set = false;
    for (var entry : entries) {
      if (!set && entry.name().equals(name)) {
        next.add(new Entry(name, value));
        set = true;
      } else {
        next.add(entry);
      }
    }
    if (!set) {
      next.add(new Entry(name, value));
    }
    return new Document(next);
  }

  /** A copy without the fields of the name. */
  public Document without(String name) {
    return new Document(entries.stream().filter(e -> !e.name().equals(name)).toList());
  }

  @Override
  public String toString() {
    return Shell.compact(this);
  }
}
