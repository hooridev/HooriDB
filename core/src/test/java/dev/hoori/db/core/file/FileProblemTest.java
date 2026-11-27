package dev.hoori.db.core.file;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class FileProblemTest {

  @Test
  void namesTheCauseBesideTheFile() {
    assertEquals("Access to a.csv was denied", FileProblem.of(new AccessDeniedException("a.csv")));
    assertEquals(
        "a.csv does not exist: gone",
        FileProblem.of(new NoSuchFileException("a.csv", null, "gone")));
    assertEquals(
        "a.csv: the system reported FileSystemException",
        FileProblem.of(new FileSystemException("a.csv")));
    assertEquals("The disk is full", FileProblem.of(new IOException("The disk is full")));
    assertEquals("The system reported IOException", FileProblem.of(new IOException()));
  }
}
