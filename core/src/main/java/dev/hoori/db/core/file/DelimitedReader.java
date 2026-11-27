package dev.hoori.db.core.file;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads delimited text a row at a time, as {@link Format.Delimited} says; its header is a row like
 * any other here. A quoted value may hold the delimiter, the quote character doubled and line ends;
 * after its closing quote, what comes before the next delimiter is taken as it is. Lines end with
 * LF, CRLF or CR, and a line that holds nothing at all is no row, but in a file whose first row,
 * the header where it has one, has one value: there it is a row of that value empty, NULL where
 * NULL's text is empty, as a NULL of a file of one column is written. A byte order mark of the
 * encoding at the start is skipped, and a byte that is no character of the encoding fails the read.
 */
final class DelimitedReader implements Closeable {

  /** A row, by the line it starts on, from 1; null for an unquoted value that is NULL's text. */
  record Record(long line, List<String> values) {}

  private final Format.Delimited format;
  private final Reader in;
  private final char[] buffer = new char[64 * 1024];
  private int at;
  private int filled;
  private long line = 1;
  private boolean started;
  private int width = -1; // the number of values of the first row, once it was read
  private final ArrayDeque<Record> ahead = new ArrayDeque<>(); // rows read, not yet given

  DelimitedReader(Format.Delimited format, InputStream in) {
    this.format = format;
    this.in = new InputStreamReader(
        in,
        format
            .encoding()
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT));
  }

  /** The next row, or null after the last. */
  Record next() throws IOException {
    if (!ahead.isEmpty()) {
      return ahead.poll();
    }
    skipMark();
    var from = line;
    long empty = 0;
    int c;
    while ((c = read()) == '\n' || c == '\r') {
      end(c);
      empty++;
    }
    Record row = null;
    if (c >= 0) {
      unread();
      row = row();
    }
    var opening = width < 0;
    if (opening && row != null) {
      width = row.values().size();
    }
    // Lines before the header are no rows.
    if (width == 1 && !(opening && format.header())) {
      emptyRows(from, empty);
    }
    if (row != null) {
      ahead.add(row);
    }
    return ahead.poll();
  }

  // Past the byte order mark the text starts with, where it has one.
  private void skipMark() throws IOException {
    if (started) {
      return;
    }
    started = true;
    var first = read();
    if (first != Encoding.MARK && first >= 0) {
      unread();
    }
  }

  // The empty lines from the line, each a row of one column's empty value.
  private void emptyRows(long from, long empty) {
    for (long i = 0; i < empty; i++) {
      ahead.add(new Record(from + i, Collections.singletonList(value(new StringBuilder(), false))));
    }
  }

  // The row that starts here, which holds a character.
  private Record row() throws IOException {
    var start = line;
    var values = new ArrayList<String>();
    var value = new StringBuilder();
    var quoted = false;
    while (true) {
      var c = read();
      if (c == format.quote() && value.isEmpty() && !quoted) {
        quoted = true;
        quoted(value, start);
      } else if (c == format.delimiter()) {
        values.add(value(value, quoted));
        value.setLength(0);
        quoted = false;
      } else if (c < 0 || c == '\n' || c == '\r') {
        end(c);
        values.add(value(value, quoted));
        return new Record(start, Collections.unmodifiableList(values));
      } else {
        value.append((char) c);
      }
    }
  }

  // The rest of a quoted value, up to its closing quote, whose line ends count.
  private void quoted(StringBuilder value, long at) throws IOException {
    while (true) {
      var c = read();
      if (c < 0) {
        throw new IOException("The quoted value of line " + at + " does not end");
      }
      if (c == format.quote()) {
        if (!doubledQuote()) {
          return;
        }
        value.append((char) c);
      } else if (c == '\r' || c == '\n') {
        value.append(quotedLineEnd(c));
      } else {
        value.append((char) c);
      }
    }
  }

  // Whether the quote read is the first of two, which stand for one; else it closed the value,
  // and what follows is read next.
  private boolean doubledQuote() throws IOException {
    var next = read();
    if (next == format.quote()) {
      return true;
    }
    if (next >= 0) {
      unread();
    }
    return false;
  }

  // The line end in a quoted value that the character begins, CRLF as one, as the value holds it.
  private String quotedLineEnd(int c) throws IOException {
    var next = c == '\r' ? read() : -1;
    if (next >= 0 && next != '\n') {
      unread();
    }
    line++;
    return c == '\r' && next == '\n' ? "\r\n" : String.valueOf((char) c);
  }

  private String value(StringBuilder value, boolean quoted) {
    var text = value.toString();
    return !quoted && text.equals(format.nullText()) ? null : text;
  }

  // Past the line end that the character begins, CRLF as one.
  private void end(int c) throws IOException {
    if (c == '\r') {
      var next = read();
      if (next >= 0 && next != '\n') {
        unread();
      }
    }
    if (c >= 0) {
      line++;
    }
  }

  // The next character, or -1 at the end.
  private int read() throws IOException {
    if (at < filled) {
      return buffer[at++];
    }
    try {
      filled = in.read(buffer);
    } catch (CharacterCodingException e) {
      throw new IOException(
          "Line " + line + " holds bytes that are no text of "
              + format.encoding().label(),
          e);
    }
    if (filled <= 0) {
      filled = 0;
      at = 0;
      return -1;
    }
    at = 1;
    return buffer[0];
  }

  // Takes back the character read last, which is in the buffer still.
  private void unread() {
    at--;
  }

  @Override
  public void close() throws IOException {
    in.close();
  }
}
