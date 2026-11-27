package dev.hoori.db.core.file;

import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * JSON, as {@link Format.Json} says: an object per row, its members in the fields' order. Numbers
 * and truths are JSON's own but a floating point number that is not finite, which is its text; a
 * decimal is written in full, whatever its scale. A JSON column's value goes in as the JSON it is,
 * where it is one value of valid JSON, however long, deep or many-digited, and as its text
 * otherwise, its numbers as they are written there; every other value is its text, as {@link
 * Texts} writes it.
 */
final class JsonWriter implements RowWriter {

  // No limit of Jackson's own on what a JSON column holds, which the server took as valid: a
  // value that passes one is no less valid, and is no text.
  private static final JsonFactory FACTORY = JsonFactory.builder()
      .rootValueSeparator("")
      .streamReadConstraints(StreamReadConstraints.builder()
          .maxNestingDepth(Integer.MAX_VALUE)
          .maxNumberLength(Integer.MAX_VALUE)
          .maxStringLength(Integer.MAX_VALUE)
          .maxNameLength(Integer.MAX_VALUE)
          .build())
      .streamWriteConstraints(
          StreamWriteConstraints.builder().maxNestingDepth(Integer.MAX_VALUE).build())
      .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
      .build();

  private final Format.Json format;
  private final JsonGenerator json;
  private List<Field> fields = List.of();
  private long rows;

  JsonWriter(Format.Json format, OutputStream out) {
    this.format = format;
    this.json = FACTORY.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8);
  }

  @Override
  public void fields(List<Field> fields) throws IOException {
    this.fields = List.copyOf(fields);
    if (format.layout() == Format.Layout.ARRAY) {
      raw("[");
    }
  }

  @Override
  public void row(List<Object> values) throws IOException {
    var line = format.lineEnd().text();
    if (format.layout() == Format.Layout.ARRAY) {
      raw(rows == 0 ? line : "," + line);
    }
    rows++;
    try {
      json.writeStartObject();
      for (var i = 0; i < values.size(); i++) {
        json.writeName(fields.get(i).name());
        value(fields.get(i).kind(), values.get(i));
      }
      json.writeEndObject();
    } catch (JacksonException e) {
      throw failed(e, "Row " + rows + " cannot be written: ");
    }
    if (format.layout() == Format.Layout.LINES) {
      raw(line);
    }
  }

  private void value(Kind kind, Object value) {
    switch (value) {
      case null -> json.writeNull();
      case Long number -> json.writeNumber(number);
      case BigInteger number -> json.writeNumber(number);
      // As its plain text, which has no bound on the scale, unlike Jackson's plain BigDecimal.
      case BigDecimal number -> json.writeNumber(number.toPlainString());
      case Float number when Float.isFinite(number) -> json.writeNumber(number);
      case Double number when Double.isFinite(number) -> json.writeNumber(number);
      case Boolean truth -> json.writeBoolean(truth);
      case String text
      when kind == Kind.JSON && valid(text) -> {
        try (var parser = FACTORY.createParser(ObjectReadContext.empty(), text)) {
          copy(parser);
        }
      }
      default -> json.writeString(Texts.of(value));
    }
  }

  // Copies the value token by token, its numbers as their text, so that none is read into a double
  // and written otherwise, as 1.10 as 1.1 or 1e400 as "Infinity".
  private void copy(JsonParser parser) {
    for (var token = parser.nextToken(); token != null; token = parser.nextToken()) {
      if (token.isNumeric()) {
        json.writeNumber(parser.getString());
      } else {
        json.copyCurrentEvent(parser);
      }
    }
  }

  // Whether the text is one value of JSON, and nothing after it: what fails is no JSON, as the
  // parser has no limits.
  private static boolean valid(String text) {
    try (var parser = FACTORY.createParser(ObjectReadContext.empty(), text)) {
      if (parser.nextToken() == null) {
        return false;
      }
      parser.skipChildren();
      return parser.nextToken() == null;
    } catch (JacksonException _) {
      return false;
    }
  }

  private void raw(String text) throws IOException {
    try {
      json.writeRaw(text);
    } catch (JacksonException e) {
      throw failed(e, "");
    }
  }

  // The stream's own failure, which the generator wraps, or the generator's.
  private static IOException failed(JacksonException e, String what) {
    return e.getCause() instanceof IOException io
        ? io
        : new IOException(what + e.getOriginalMessage(), e);
  }

  @Override
  public void finish() throws IOException {
    if (format.layout() == Format.Layout.ARRAY) {
      raw(
          rows == 0
              ? "]" + format.lineEnd().text()
              : format.lineEnd().text() + "]" + format.lineEnd().text());
    }
    try {
      json.flush();
    } catch (JacksonException e) {
      throw failed(e, "");
    }
  }
}
