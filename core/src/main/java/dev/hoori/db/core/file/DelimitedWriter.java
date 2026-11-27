package dev.hoori.db.core.file;

import dev.hoori.db.core.data.Field;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.List;

/**
 * Delimited text, as {@link Format.Delimited} says, with a byte order mark first where the
 * encoding has one, and a value that starts with one quoted. A character that the encoding cannot
 * write fails the row.
 */
final class DelimitedWriter implements RowWriter {

  private final Format.Delimited format;
  private final OutputStream out;
  private final Writer writer;
  private final String quote;
  private final String doubled;
  private long rows;

  DelimitedWriter(Format.Delimited format, OutputStream out) {
    this.format = format;
    this.out = out;
    this.writer = new OutputStreamWriter(
        out,
        format
            .encoding()
            .charset()
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT));
    this.quote = String.valueOf(format.quote());
    this.doubled = quote + quote;
  }

  @Override
  public void fields(List<Field> fields) throws IOException {
    if (format.encoding().marked()) {
      writer.write(Encoding.MARK);
    }
    if (format.header()) {
      line(fields.stream().map(f -> (Object) f.name()).toList(), "The header");
    }
  }

  @Override
  public void row(List<Object> values) throws IOException {
    rows++;
    line(values, "Row " + rows);
  }

  private void line(List<Object> values, String what) throws IOException {
    var line = new StringBuilder();
    for (var i = 0; i < values.size(); i++) {
      if (i > 0) {
        line.append(format.delimiter());
      }
      var text = Texts.of(values.get(i));
      if (text == null) {
        line.append(format.nullText());
      } else if (quoted(text)) {
        line.append(quote).append(text.replace(quote, doubled)).append(quote);
      } else {
        line.append(text);
      }
    }
    line.append(format.lineEnd().text());
    try {
      writer.write(line.toString());
    } catch (CharacterCodingException e) {
      throw new IOException(
          what + " holds a character that " + format.encoding().label() + " cannot write", e);
    }
  }

  // A text that starts with a byte order mark is quoted too, so that no reader takes it for the
  // file's mark where it comes first.
  private boolean quoted(String text) {
    return format.quoting() == Format.Quoting.ALL
        || text.equals(format.nullText())
        || (!text.isEmpty() && text.charAt(0) == Encoding.MARK)
        || text.indexOf(format.delimiter()) >= 0
        || text.indexOf(format.quote()) >= 0
        || text.indexOf('\n') >= 0
        || text.indexOf('\r') >= 0;
  }

  @Override
  public void finish() throws IOException {
    writer.flush();
    out.flush();
  }
}
