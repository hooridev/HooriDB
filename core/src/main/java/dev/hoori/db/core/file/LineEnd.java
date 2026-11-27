package dev.hoori.db.core.file;

/** How the lines of a file end. A file that is read may end them either way, or with CR alone. */
public enum LineEnd {
  /** As Linux and macOS end them. */
  LF("\n"),
  /** As Windows ends them, and RFC 4180 a CSV file's. */
  CRLF("\r\n");

  private final String text;

  LineEnd(String text) {
    this.text = text;
  }

  /** The characters that end a line. */
  public String text() {
    return text;
  }
}
