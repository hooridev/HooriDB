package dev.hoori.db.core.document;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Missing;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.data.Rows;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The values of documents as a grid shows them: a field a column, with the {@link Kind} its types
 * have in common, and each value of the Java type that kind says. A field a document lacks is
 * {@link Missing#FIELD}, and one that is null {@code null}. A document or an array is its Relaxed Extended JSON, of
 * {@link Kind#JSON} where the field holds only them, and a value of another type than the
 * column's the shell's text of it, each cut short beyond {@link Rows#LIMIT} characters.
 */
public final class Values {

  private Values() {}

  /** The kind of a column of the types it held, such as {@code int | null}: one kind or OTHER. */
  public static Kind kind(String type) {
    var kinds = new LinkedHashSet<Kind>();
    for (var part : type.split("\\|")) {
      part = part.strip();
      if (!part.equals("null")) {
        kinds.add(kindOf(part));
      }
    }
    return kinds.size() == 1 ? kinds.iterator().next() : Kind.OTHER;
  }

  private static Kind kindOf(String type) {
    return switch (type) {
      case "string" -> Kind.TEXT;
      case "int", "long" -> Kind.INTEGER;
      case "double", "decimal" -> Kind.DECIMAL;
      case "bool" -> Kind.BOOLEAN;
      case "date" -> Kind.DATE_TIME_ZONED;
      case "binData" -> Kind.BINARY;
      case "object", "array" -> Kind.JSON;
      default -> Kind.OTHER;
    };
  }

  /** The fields of a grid of the columns. */
  public static List<Field> fields(List<Column> columns) {
    return columns.stream()
        .map(c -> new Field(c.name(), c.type(), kind(c.type())))
        .toList();
  }

  /** A page of the documents, with a row of each under the columns. */
  public static Page page(List<Column> columns, List<Document> documents) {
    var fields = fields(columns);
    var rows = new ArrayList<List<Object>>(documents.size());
    for (var document : documents) {
      rows.add(row(fields, document));
    }
    return new Page(fields, Collections.unmodifiableList(rows), documents);
  }

  /**
   * The fields of documents that came without a catalog, as a command's: those of all of them, in
   * the order they first came, {@code _id} first where they have it.
   */
  public static List<Field> found(List<Document> documents) {
    Map<String, Set<String>> types = new LinkedHashMap<>();
    boolean ids = documents.stream().anyMatch(d -> d.has("_id"));
    if (ids) {
      types.put("_id", new LinkedHashSet<>());
    }
    for (var document : documents) {
      for (var entry : document.entries()) {
        types
            .computeIfAbsent(entry.name(), x -> new LinkedHashSet<>())
            .add(Bson.type(entry.value()));
      }
    }
    var fields = new ArrayList<Field>();
    for (var field : types.entrySet()) {
      var type = String.join(" | ", field.getValue());
      fields.add(new Field(field.getKey(), type, kind(type)));
    }
    return fields;
  }

  /** The values of the document under the fields. */
  public static List<Object> row(List<Field> fields, Document document) {
    var row = new ArrayList<Object>(fields.size());
    for (var field : fields) {
      var value = document.get(field.name());
      row.add(
          value != null || document.has(field.name()) ? value(value, field.kind()) : Missing.FIELD);
    }
    return Collections.unmodifiableList(row);
  }

  /** The value of a document as a grid of the kind shows it. */
  public static Object value(Object value, Kind kind) {
    return switch (value) {
      case null -> null;
      case String s when kind == Kind.TEXT -> s.length() > Rows.LIMIT ? cut(s) : s;
      case Integer n when kind == Kind.INTEGER -> (long) n;
      case Long n when kind == Kind.INTEGER -> n;
      case Double n when kind == Kind.DECIMAL -> n;
      case Bson.Decimal128 n when kind == Kind.DECIMAL -> n.value() == null ? n.text() : n.value();
      case Boolean b when kind == Kind.BOOLEAN -> b;
      case Instant i when kind == Kind.DATE_TIME_ZONED -> i.atOffset(ZoneOffset.UTC);
      case Bson.Binary b
      when kind == Kind.BINARY && b.uuid() == null -> {
        var bytes = b.bytes();
        yield new Binary(Arrays.copyOf(bytes, Math.min(bytes.length, Rows.LIMIT)), bytes.length);
      }
      case Document d -> json(d);
      case List<?> list -> json(list);
      default -> {
        var text = Shell.compact(value);
        yield text.length() > Rows.LIMIT ? cut(text) : text;
      }
    };
  }

  private static Object json(Object value) {
    var text = ExtendedJson.of(value);
    return text.length() > Rows.LIMIT ? cut(text) : text;
  }

  // Not between the two halves of a character beyond the 16 bits of one char.
  private static Cut cut(String text) {
    int end = Character.isHighSurrogate(text.charAt(Rows.LIMIT - 1)) ? Rows.LIMIT - 1 : Rows.LIMIT;
    return new Cut(
        text.substring(0, end),
        OptionalLong.of(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
  }
}
