package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The statements of a file of SQL, read as the file is: split as {@link Statements} splits a text,
 * a window at a time, so that only what is being read is held and files of any size can be read.
 * What it finds besides statements is what the provider's client reads as its own: a {@code
 * DELIMITER} line changes the terminator and is no step, a line with a command of the client is a
 * {@link Command}, and the rows after a statement that copies them from the client are its {@link
 * #rows}.
 *
 * <p>The file is UTF-8, and a byte order mark at its start is skipped; a file that is not UTF-8
 * fails where it stops being so. Lines count from 1 at each {@code \n}; offsets are in bytes of the
 * file.
 *
 * <p>It is read by one thread, except for {@link #cancel}.
 */
public final class StatementReader implements Closeable {

  // How much it reads at a time, in chars, and how far beyond a part the scan may have looked to
  // tell what the part is: a delimiter of at most 15 chars, a word DELIMITER, "--" and its next.
  private static final int CHUNK = 1 << 16;
  private static final int MARGIN = 16;
  private static final char BYTE_ORDER_MARK = 0xFEFF;

  /** What the file holds next. */
  public sealed interface Step permits Sql, Copy, Command {

    /** The line its text begins on, from 1. */
    long line();

    /** The offset in bytes where its text begins. */
    long offset();
  }

  /** A statement to send, without its terminator. */
  public record Sql(String sql, long line, long offset) implements Step {}

  /**
   * A statement that copies rows from the client, without its terminator; its rows are those that
   * {@link #rows} gives, from the line after its own.
   *
   * @param followed whether its line holds more after it than white space and comments, which
   *     would not run
   */
  public record Copy(String sql, long line, long offset, boolean followed) implements Step {}

  /**
   * A command of the provider's client, as psql's {@code \connect db}: its line without the line's
   * end, and its name without the backslash, in lower case.
   */
  public record Command(String text, String name, long line, long offset) implements Step {}

  private final Reader in;
  private final Provider provider;
  private final long size;
  private final int windowSize;
  private final StringBuilder buffer = new StringBuilder();
  // What was taken of the buffer, and the line and offset there.
  private int at;
  private long line = 1;
  private long offset;
  private boolean ended; // the file has no more to read
  private IOException broken; // why it has not, where it stopped being UTF-8
  private boolean started;
  private String delimiter = ";";
  // The chars to have read before the next scan: more while a part goes on past the window.
  private int wanted;
  private final ArrayDeque<Taken> ready = new ArrayDeque<>();
  private Rows rows; // the rows of the last copy, while the file is on them
  private volatile boolean cancelled;

  // A part that the last scan found, its step or none for a DELIMITER line, and where the reader
  // is after it, with the terminator from there on.
  private record Taken(Optional<Step> step, int after, long line, long offset, String delimiter) {}

  /**
   * Reads the text, whose size in bytes is given for {@link #size}, or -1 where it is not known.
   */
  public StatementReader(Reader in, Provider provider, long size) {
    this(in, provider, size, CHUNK);
  }

  // Reading the given chars at a time, as a test cuts the file into windows.
  StatementReader(Reader in, Provider provider, long size, int windowSize) {
    this.in = in;
    this.provider = provider;
    this.size = size;
    this.windowSize = windowSize;
    this.wanted = windowSize;
  }

  /** Opens the file, UTF-8. */
  public static StatementReader open(Path file, Provider provider) throws IOException {
    long size = Files.size(file);
    return new StatementReader(new Utf8(Files.newInputStream(file)), provider, size);
  }

  /**
   * The chars of UTF-8 bytes, which gives all the chars before a byte that is not UTF-8 and fails
   * only at the next read.
   */
  static final class Utf8 extends Reader {
    private final InputStream in;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final ByteBuffer bytes = ByteBuffer.allocate(CHUNK).flip();
    private boolean end;
    private boolean flushed;
    private CharacterCodingException failed;

    Utf8(InputStream in) {
      this.in = in;
    }

    @Override
    public int read(char[] into, int from, int length) throws IOException {
      if (failed != null) {
        throw failed;
      }
      if (length == 0) {
        return 0;
      }
      var out = CharBuffer.wrap(into, from, length);
      while (out.position() == from && decoded(out, from)) {
        if (end) {
          return last(out, from);
        }
        refill();
      }
      return out.position() - from;
    }

    // Decodes what the bytes hold into what is read; false where that is full, or where the bytes
    // stop being UTF-8 after chars that are given first.
    private boolean decoded(CharBuffer out, int from) throws CharacterCodingException {
      var result = decoder.decode(bytes, out, end);
      if (result.isError()) {
        failed = new MalformedInputException(result.length());
        if (out.position() == from) {
          throw failed;
        }
        return false;
      }
      return !result.isOverflow();
    }

    // The last chars of the file, or -1 where none is left.
    private int last(CharBuffer out, int from) {
      if (!flushed) {
        flushed = true;
        decoder.flush(out);
      }
      return out.position() > from ? out.position() - from : -1;
    }

    private void refill() throws IOException {
      bytes.compact();
      int read = in.read(bytes.array(), bytes.position(), bytes.remaining());
      if (read < 0) {
        end = true;
      } else {
        bytes.position(bytes.position() + read);
      }
      bytes.flip();
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }

  /** The size of the file in bytes, or -1 where it is not known. */
  public long size() {
    return size;
  }

  /** The offset in bytes up to which the file was taken: of the steps given, and of their rows. */
  public long offset() {
    return offset;
  }

  /** The line up to which the file was taken. */
  public long line() {
    return line;
  }

  /**
   * The next step, or empty at the end of the file. The rows of a copy that were not read are
   * skipped first.
   *
   * @throws IOException where the file cannot be read, or is not UTF-8
   */
  public Optional<Step> next() throws IOException {
    if (rows != null) {
      rows.skip();
      rows = null;
    }
    while (true) {
      while (ready.isEmpty()) {
        if (!scan()) {
          return Optional.empty();
        }
      }
      var taken = ready.poll();
      at = taken.after();
      line = taken.line();
      offset = taken.offset();
      delimiter = taken.delimiter();
      if (taken.step().orElse(null) instanceof Copy copy) {
        boolean followed = restOfLine();
        rows = new Rows();
        return Optional.of(new Copy(copy.sql(), copy.line(), copy.offset(), followed));
      }
      if (taken.step().isPresent()) {
        return taken.step();
      }
    }
  }

  // Takes the rest of the line that a copy's statement ends on, which its rows follow: whether it
  // holds more than white space and comments.
  private boolean restOfLine() throws IOException {
    int end = buffer.indexOf("\n", at);
    while (end < 0 && !ended) {
      int searched = buffer.length() - at; // past the reader, as a fill may move it
      fill(searched + windowSize);
      end = buffer.indexOf("\n", at + searched);
    }
    int to = end < 0 ? buffer.length() : end + 1;
    var rest = buffer.substring(at, to);
    advance(to);
    for (var piece : Statements.pieces(rest, provider)) {
      if (piece.kind() != Statements.Piece.Kind.COMMENT) {
        return true;
      }
    }
    return false;
  }

  /**
   * The rows of the {@link Copy} that {@link #next} gave last, as the file has them, from the line
   * after the statement's up to the line alone that the dialect's {@link
   * dev.hoori.db.core.dialect.ClientDialect#endOfRows} names, or the end of the file; empty for any
   * other step.
   */
  public Reader rows() {
    return rows != null ? rows : Reader.nullReader();
  }

  /** Has what reads from the file fail from now on, as a cancel of the run does; any thread. */
  public void cancel() {
    cancelled = true;
  }

  @Override
  public void close() throws IOException {
    in.close();
  }

  // Scans what the buffer holds from where the reader is, and queues the steps that what follows
  // in the file cannot change; reads more first where it has to. False at the end of the file.
  private boolean scan() throws IOException {
    if (buffer.length() - at < wanted && !ended) {
      fill(wanted);
    }
    int n = buffer.length();
    int context = Math.max(at - 1, 0); // what a token at the reader follows
    var window = buffer.substring(context);
    queue(Statements.parts(window, at - context, provider, delimiter), window, context);
    if (!ready.isEmpty()) {
      wanted = windowSize;
      return true;
    }
    if (broken != null) {
      throw broken;
    }
    if (ended) {
      advance(n); // nothing but white space and comments is left
      return false;
    }
    wanted = Math.max(windowSize, 2 * (n - at)); // a part goes on beyond the window
    return true;
  }

  // Queues the parts of the window that what follows in the file cannot change, each with the line
  // and offset where it begins and where the reader is after it, up to the first copy, as what
  // follows that is rows, which the scan took for statements.
  private void queue(List<Statements.Part> parts, String window, int context) {
    int position = at;
    long lineAt = line;
    long offsetAt = offset;
    for (var part : parts) {
      int after = part.after() + context;
      if (!whole(part, after)) {
        return;
      }
      int start = part.start() + context;
      lineAt += lines(position, start);
      offsetAt += bytes(position, start);
      var step = step(part, window.substring(part.start(), part.end()), lineAt, offsetAt);
      lineAt += lines(start, after);
      offsetAt += bytes(start, after);
      position = after;
      ready.add(new Taken(step, after, lineAt, offsetAt, part.delimiter()));
      if (step.orElse(null) instanceof Copy) {
        return;
      }
    }
  }

  // Whether what follows the part in the file cannot change it: the file ended, or the scan
  // looked far enough beyond it, past the terminator of a statement. Where the file stops being
  // UTF-8, a statement whose terminator came before that is whole, and so is a line that ended:
  // the next read fails anyway.
  private boolean whole(Statements.Part part, int after) {
    if (ended && broken != null) {
      return part.kind() == Statements.Part.Kind.STATEMENT
          ? part.after() > part.end()
          : after > 0 && buffer.charAt(after - 1) == '\n';
    }
    if (ended) {
      return true;
    }

    return after + MARGIN <= buffer.length() && terminated(part);
  }

  // Whether the part is no statement, or one that its terminator ended.
  private static boolean terminated(Statements.Part part) {
    return part.kind() != Statements.Part.Kind.STATEMENT || part.after() > part.end();
  }

  // The step of the part that begins at the line and offset; none for a DELIMITER line.
  private Optional<Step> step(Statements.Part part, String text, long lineOf, long offsetOf) {
    return switch (part.kind()) {
      case STATEMENT ->
        Optional.of(
            copies(text)
                ? new Copy(text, lineOf, offsetOf, false)
                : new Sql(text, lineOf, offsetOf));
      case COMMAND -> Optional.of(new Command(text, name(text), lineOf, offsetOf));
      case DELIMITER -> Optional.empty();
    };
  }

  // Whether the statement copies rows from the client, as the dialect tells by its first word
  // and, where that may begin such a statement, by its words outside parentheses.
  private boolean copies(String sql) {
    var first = Statements.firstWord(sql, provider).toLowerCase(Locale.ROOT);
    return provider.dialect().client().copiesFromClient(first, () -> {
      var reading = new Tokens.Reading(provider.dialect().syntax().backslashEscapes(), false);
      var words = new ArrayList<String>();
      for (var token : Tokens.of(sql, provider, reading)) {
        if (token.depth() == 0) {
          // A name, quoted or after a period, equals no word.
          words.add(token.ident() ? "" : Tokens.keyword(token.text()).toLowerCase(Locale.ROOT));
        }
      }
      return words;
    });
  }

  // A command's name: its word after the backslash, or the one character there.
  private static String name(String command) {
    int end = 1;
    while (end < command.length() && Character.isLetterOrDigit(command.charAt(end))) {
      end++;
    }
    if (end == 1 && command.length() > 1) {
      end = 2;
    }
    return command.substring(1, end).toLowerCase(Locale.ROOT);
  }

  // Reads until the buffer holds at least the given chars past the reader, or the file ends;
  // drops what was taken before, but the one char a token may follow.
  private void fill(int chars) throws IOException {
    if (at > windowSize && at > buffer.length() / 2) {
      buffer.delete(0, at - 1);
      at = 1;
    }
    var read = new char[Math.max(windowSize, 4)];
    while (!ended && buffer.length() - at < chars) {
      if (cancelled) {
        throw new InterruptedIOException("Cancelled");
      }
      append(read);
      if (!started && !buffer.isEmpty()) {
        started = true;
        skipByteOrderMark();
      }
    }
  }

  // Appends what one read of the file gives; marks the file ended at its end, and where it stops
  // being UTF-8, after what came before that.
  private void append(char[] read) throws IOException {
    int count;
    try {
      count = in.read(read);
    } catch (CharacterCodingException e) {
      broken = new IOException(
          "The file is not UTF-8 on line " + (line + lines(at, buffer.length())), e);
      ended = true;
      return;
    }
    if (count < 0) {
      ended = true;
    } else {
      buffer.append(read, 0, count);
    }
  }

  private void skipByteOrderMark() {
    if (buffer.charAt(0) == BYTE_ORDER_MARK) {
      buffer.deleteCharAt(0);
      offset += 3;
    }
  }

  private int lines(int from, int to) {
    int count = 0;
    for (int k = from; k < to; k++) {
      if (buffer.charAt(k) == '\n') {
        count++;
      }
    }
    return count;
  }

  // The bytes of UTF-8 the chars take.
  private long bytes(int from, int to) {
    long count = 0;
    for (int k = from; k < to; k++) {
      count += bytes(buffer.charAt(k));
    }
    return count;
  }

  // The bytes of UTF-8 a char takes; each of a surrogate pair two, four for the pair.
  private static int bytes(char c) {
    if (c < 0x80) {
      return 1;
    }
    return c < 0x800 || Character.isSurrogate(c) ? 2 : 3;
  }

  // Takes the buffer up to the offset.
  private void advance(int to) {
    line += lines(at, to);
    offset += bytes(at, to);
    at = to;
  }

  // The rows of a copy, taken from the file as they are read, from the start of a line. A read
  // never ends between the two chars of a surrogate pair where it has room for both, as pgjdbc
  // encodes each read by itself.
  private final class Rows extends Reader {
    private final String closing = provider.dialect().client().endOfRows();
    private boolean lineStart = true;
    private boolean over;

    @Override
    public int read(char[] into, int from, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      while (!over) {
        if (cancelled) {
          throw new InterruptedIOException("Cancelled");
        }
        int count = take(into, from, length);
        if (count > 0) {
          return count;
        }
      }
      return -1;
    }

    // Gives the next chars of the rows, at most up to a line's end; 0 where it only took what is
    // not rows, read more, or found their end.
    private int take(char[] into, int from, int length) throws IOException {
      if (!more()) {
        if (broken != null) {
          throw broken;
        }
        over = true;
        return 0;
      }
      if (lineStart && buffer.charAt(at) == closing.charAt(0) && ending()) {
        return 0;
      }
      return copy(into, from, length);
    }

    // Reads more, where the buffer holds less than a pair of surrogates past the reader; false at
    // the end of the file.
    private boolean more() throws IOException {
      if (buffer.length() - at < 2) {
        fill(2);
      }
      return at < buffer.length();
    }

    // At a line that begins as the end of the rows does: whether it ends them, which are then
    // over, or more must be read to tell.
    private boolean ending() throws IOException {
      int needed = closing.length() + 2; // and the line's end, \r\n
      if (buffer.length() - at < needed && !ended) {
        fill(needed);
        return true;
      }
      int after = endOfRows();
      if (after < 0) {
        return false;
      }
      advance(after);
      over = true;
      return true;
    }

    // Gives the chars from the reader on, at most to the end of the line.
    private int copy(char[] into, int from, int length) {
      lineStart = false;
      int end = Math.min(buffer.length(), at + length);
      int lineEnd = buffer.indexOf("\n", at);
      if (lineEnd >= 0 && lineEnd < end) {
        end = lineEnd + 1;
        lineStart = true;
      } else if (end - at > 1 && Character.isHighSurrogate(buffer.charAt(end - 1))) {
        end--;
      }
      buffer.getChars(at, end, into, from);
      int count = end - at;
      advance(end);
      return count;
    }

    // Where the line the reader is at ends, after its line's end, where it holds the end of the
    // rows alone; -1 for other lines, and for one the file stops being UTF-8 on, which the rows
    // then fail at.
    private int endOfRows() {
      int n = buffer.length();
      int k = at + closing.length();
      if (k > n) {
        return -1;
      }
      for (int j = 0; j < closing.length(); j++) {
        if (buffer.charAt(at + j) != closing.charAt(j)) {
          return -1;
        }
      }
      if (k < n && buffer.charAt(k) == '\r') {
        k++;
      }
      if (k == n) {
        return broken == null ? k : -1;
      }
      return buffer.charAt(k) == '\n' ? k + 1 : -1;
    }

    // Reads the rows to their end, unread.
    void skip() throws IOException {
      var unread = new char[CHUNK];
      while (read(unread, 0, unread.length) >= 0) {
        // dropped
      }
    }

    @Override
    public void close() {
      // The file stays open; the next step skips what is left of the rows.
    }
  }
}
