package dev.hoori.db.core.file;

import dev.hoori.db.core.data.Rows;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes rows to a stream in a format, as {@link Rows#each} hands them over: the fields first,
 * then each row as it comes, and {@link #finish} after the last. Nothing is held but what the
 * stream buffers.
 */
sealed interface RowWriter extends Rows.Each permits DelimitedWriter, JsonWriter {

  /** Writes what ends the file and flushes it, without closing the stream. */
  void finish() throws IOException;

  static RowWriter of(Format format, OutputStream out) {
    return switch (format) {
      case Format.Delimited delimited -> new DelimitedWriter(delimited, out);
      case Format.Json json -> new JsonWriter(json, out);
    };
  }
}
