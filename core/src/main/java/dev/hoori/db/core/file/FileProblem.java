package dev.hoori.db.core.file;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/**
 * What went wrong with a file, in words for the user: the file and the cause, where the system's
 * exception gives only the file's path, or nothing.
 */
public final class FileProblem {

  private FileProblem() {}

  /** The failure in words: the file it concerns and what went wrong with it. */
  public static String of(IOException e) {
    return switch (e) {
      case AccessDeniedException a -> "Access to " + a.getFile() + " was denied" + reason(a);
      case NoSuchFileException n -> n.getFile() + " does not exist" + reason(n);
      case FileAlreadyExistsException f -> f.getFile() + " exists already" + reason(f);
      case FileSystemException f ->
        f.getFile() + ": "
            + (f.getReason() == null ? "the system reported " + named(f) : f.getReason());
      default ->
        e.getMessage() == null || e.getMessage().isBlank()
            ? "The system reported " + named(e)
            : e.getMessage();
    };
  }

  private static String reason(FileSystemException e) {
    return e.getReason() == null ? "" : ": " + e.getReason();
  }

  private static String named(IOException e) {
    return e.getClass().getSimpleName();
  }
}
