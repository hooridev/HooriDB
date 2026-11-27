package dev.hoori.db.core.file;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * The character encoding of a file, with the byte order mark it starts with, where it has one. A
 * file that is read skips such a mark of its encoding, also where it is not asked for.
 */
public enum Encoding {
  UTF_8("UTF-8", StandardCharsets.UTF_8, false),
  /** UTF-8 that starts with its mark, by which spreadsheets tell it from the system's encoding. */
  UTF_8_BOM("UTF-8 with BOM", StandardCharsets.UTF_8, true),
  UTF_16LE("UTF-16 LE", StandardCharsets.UTF_16LE, true),
  UTF_16BE("UTF-16 BE", StandardCharsets.UTF_16BE, true),
  ISO_8859_1("ISO-8859-1", StandardCharsets.ISO_8859_1, false),
  WINDOWS_1252("Windows-1252", Charset.forName("windows-1252"), false);

  /** The character that a byte order mark encodes, in any of the encodings that have one. */
  static final char MARK = '\uFEFF';

  private final String label;
  private final Charset charset;
  private final boolean marked;

  Encoding(String label, Charset charset, boolean marked) {
    this.label = label;
    this.charset = charset;
    this.marked = marked;
  }

  /** Its name, as people know it. */
  public String label() {
    return label;
  }

  public Charset charset() {
    return charset;
  }

  /** Whether a file written in it starts with a byte order mark. */
  public boolean marked() {
    return marked;
  }
}
