package dev.hoori.db.core.document;

import dev.hoori.db.core.catalog.Table;
import java.util.Objects;
import java.util.Optional;

/**
 * One single-document write to a collection: replacing a document, inserting a new one, or
 * deleting one. Each produces one server command ({@code update}, {@code insert}, or {@code
 * delete}) acting on at most one document, and its MongoDB shell preview laid out with {@link
 * Layout}.
 *
 * <p>{@link Replace} and {@link Delete} filter on both {@code _id} and an exact {@code $expr}
 * match of {@code $$ROOT} against the document as it was read, so a document changed, reordered,
 * or deleted on the server since it was read matches nothing instead of being overwritten.
 */
public sealed interface DocumentEdit {

  /** What came of running a {@link DocumentEdit}. */
  sealed interface Outcome {

    /** The command wrote or deleted the document. */
    record Written() implements Outcome {}

    /**
     * Nothing was written: the server matched no document ({@code n == 0}), meaning the document
     * changed or was deleted on the server since it was read.
     */
    record Changed() implements Outcome {}

    /**
     * The server took the command but could not confirm it as asked (a write concern error, such
     * as a timeout waiting for other members): it may have been applied or not.
     */
    record Unconfirmed(String message) implements Outcome {}
  }

  /** The target collection (never a view). */
  Table collection();

  /** The server command document to send through {@link DocumentSession#command}. */
  Document command();

  /** The shell command as it will be sent, laid out with {@link Layout}. */
  String preview();

  /**
   * Formats {@code document} in shell notation and verifies that parsing it back with {@link
   * Shell#document} yields an equal document with the same field order and BSON types; returns
   * empty where it does not round-trip exactly, so callers can refuse editing rather than risk
   * changing a value or type.
   */
  static Optional<String> roundTripped(Document document) {
    Objects.requireNonNull(document, "document");
    var text = Shell.pretty(document);
    try {
      return Shell.document(text).equals(document) ? Optional.of(text) : Optional.empty();
    } catch (SyntaxException _) {
      return Optional.empty();
    }
  }

  /**
   * Exact filter matching the document as read: aggregation {@code $eq} compares documents field
   * by field in order, so a changed, added, removed, or reordered field matches nothing.
   *
   * <p>Known limit: a value whose BSON type alone changed while it compares equal, a number of
   * another type at the same value (Int32 {@code 1} to Int64 {@code 1}) or a string turned into a
   * symbol of the same text, still matches under the server's equality.
   */
  static Document exactFilter(Document original) {
    Objects.requireNonNull(original, "original");
    if (!original.has("_id")) {
      throw new IllegalArgumentException("The document has no _id");
    }
    return Document.of(
        "_id",
        original.get("_id"),
        "$expr",
        Document.of("$eq", Document.array("$$ROOT", Document.of("$literal", original))));
  }

  // Compares strings by their bytes, not by a collation the collection may have, under which a
  // string changed in case alone would still match.
  private static Document simple() {
    return Document.of("locale", "simple");
  }

  private static void requireCollection(Table collection) {
    Objects.requireNonNull(collection, "collection");
    if (collection.kind() != Table.Kind.TABLE) {
      throw new IllegalArgumentException("Views cannot be edited");
    }
  }

  private static String formatPreview(String raw) {
    try {
      return Layout.of(raw);
    } catch (SyntaxException _) {
      return raw;
    }
  }

  private static String target(Table collection) {
    return "db.getCollection(" + Shell.quote(collection.name()) + ")";
  }

  /** Replaces one document, refusing to change {@code _id} or send update operators. */
  record Replace(Table collection, Document original, Document replacement)
      implements DocumentEdit {

    public Replace {
      requireCollection(collection);
      Objects.requireNonNull(original, "original");
      Objects.requireNonNull(replacement, "replacement");
      if (!original.has("_id")) {
        throw new IllegalArgumentException("The document has no _id");
      }
      if (replacement.has("_id") && !Objects.equals(replacement.get("_id"), original.get("_id"))) {
        throw new IllegalArgumentException("_id cannot be changed");
      }
      if (replacement.names().stream().anyMatch(name -> name.startsWith("$"))) {
        throw new IllegalArgumentException(
            "A replacement document cannot have top-level fields starting with $");
      }
    }

    @Override
    public Document command() {
      return Document.of(
          "update",
          collection.name(),
          "updates",
          Document.array(Document.of(
              "q",
              exactFilter(original),
              "u",
              replacement,
              "multi",
              false,
              "upsert",
              false,
              "collation",
              simple())));
    }

    @Override
    public String preview() {
      return formatPreview(target(collection)
          + ".replaceOne("
          + Shell.compact(exactFilter(original))
          + ", "
          + Shell.compact(replacement)
          + ", "
          + Shell.compact(Document.of("collation", simple()))
          + ")");
    }
  }

  /** Inserts one new document (with or without {@code _id}). */
  record Insert(Table collection, Document document) implements DocumentEdit {

    public Insert {
      requireCollection(collection);
      Objects.requireNonNull(document, "document");
    }

    @Override
    public Document command() {
      return Document.of("insert", collection.name(), "documents", Document.array(document));
    }

    @Override
    public String preview() {
      return formatPreview(target(collection) + ".insertOne(" + Shell.compact(document) + ")");
    }
  }

  /** Deletes one document matching the exact document as read. */
  record Delete(Table collection, Document original) implements DocumentEdit {

    public Delete {
      requireCollection(collection);
      Objects.requireNonNull(original, "original");
      if (!original.has("_id")) {
        throw new IllegalArgumentException("The document has no _id");
      }
    }

    @Override
    public Document command() {
      return Document.of(
          "delete",
          collection.name(),
          "deletes",
          Document.array(
              Document.of("q", exactFilter(original), "limit", 1, "collation", simple())));
    }

    @Override
    public String preview() {
      return formatPreview(target(collection)
          + ".deleteOne("
          + Shell.compact(exactFilter(original))
          + ", "
          + Shell.compact(Document.of("collation", simple()))
          + ")");
    }
  }
}
