package dev.hoori.db.core.file;

import java.util.Objects;

/** How rows are written to a file: delimited text, or JSON. */
public sealed interface Format {

  /** The extension of its files, without the dot. */
  String extension();

  LineEnd lineEnd();

  /**
   * Text with a value per field, the fields of a row divided by the delimiter and the rows by line
   * ends, as CSV and TSV are; RFC 4180 is the comma's with CRLF. A value is put between the quote
   * character, with the character doubled within, where it must be or where every value is to be,
   * as {@link Quoting} says. NULL is written as its text, unquoted, and only an unquoted value equal
   * to it is read as NULL, so that a value with the text of NULL is quoted where it is written.
   *
   * @param header whether the first row names the columns
   * @param nullText what stands for NULL, an empty text by default
   */
  record Delimited(
      char delimiter,
      char quote,
      Quoting quoting,
      boolean header,
      String nullText,
      Encoding encoding,
      LineEnd lineEnd)
      implements Format {

    /** Commas, as RFC 4180 has them, with a header. */
    public static final Delimited CSV =
        new Delimited(',', '"', Quoting.AS_NEEDED, true, "", Encoding.UTF_8, LineEnd.CRLF);

    /** Tabs, with a header. */
    public static final Delimited TSV = CSV.withDelimiter('\t');

    /**
     * @throws IllegalArgumentException if the delimiter or the quote character ends lines, the two
     *     are one, or the text of NULL holds either or a line end
     */
    public Delimited {
      Objects.requireNonNull(quoting, "quoting");
      Objects.requireNonNull(nullText, "nullText");
      Objects.requireNonNull(encoding, "encoding");
      Objects.requireNonNull(lineEnd, "lineEnd");
      if (ends(delimiter) || ends(quote) || delimiter == quote) {
        throw new IllegalArgumentException("A delimiter or quote that cannot divide values");
      }
      if (nullText.indexOf(delimiter) >= 0
          || nullText.indexOf(quote) >= 0
          || nullText.chars().anyMatch(c -> ends((char) c))) {
        throw new IllegalArgumentException("A text of NULL that is no value of its own");
      }
    }

    private static boolean ends(char c) {
      return c == '\n' || c == '\r';
    }

    /** TSV for a tab, CSV for anything else. */
    @Override
    public String extension() {
      return delimiter == '\t' ? "tsv" : "csv";
    }

    public Delimited withDelimiter(char delimiter) {
      return new Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }

    public Delimited withQuote(char quote) {
      return new Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }

    public Delimited withQuoting(Quoting quoting) {
      return new Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }

    public Delimited withHeader(boolean header) {
      return new Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }

    public Delimited withNullText(String nullText) {
      return new Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }

    public Delimited withEncoding(Encoding encoding) {
      return new Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }

    public Delimited withLineEnd(LineEnd lineEnd) {
      return new Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }
  }

  /**
   * JSON in UTF-8, as RFC 8259 has it between systems: an object per row, with a member per
   * column by its name, in an array or one per line, as JSON Lines has them.
   */
  record Json(Layout layout, LineEnd lineEnd) implements Format {

    public static final Json ARRAY = new Json(Layout.ARRAY, LineEnd.LF);
    public static final Json LINES = new Json(Layout.LINES, LineEnd.LF);

    public Json {
      Objects.requireNonNull(layout, "layout");
      Objects.requireNonNull(lineEnd, "lineEnd");
    }

    @Override
    public String extension() {
      return layout == Layout.ARRAY ? "json" : "jsonl";
    }

    public Json withLineEnd(LineEnd lineEnd) {
      return new Json(layout, lineEnd);
    }
  }

  /** Which values of delimited text are quoted. */
  enum Quoting {
    /** Those that hold the delimiter, the quote character or a line end, or are the text of NULL. */
    AS_NEEDED,
    /** All but NULL. */
    ALL
  }

  /** How the objects of JSON are laid out. */
  enum Layout {
    /** In one array, an object per line. */
    ARRAY,
    /** One object per line, without an array, as JSON Lines and NDJSON have them. */
    LINES
  }
}
