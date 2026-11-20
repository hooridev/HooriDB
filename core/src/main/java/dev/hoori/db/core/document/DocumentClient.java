package dev.hoori.db.core.document;

/**
 * A client of a server of documents, which runs one command at a time, each in the same session
 * of the server, so that a cursor that one command opens is read on by the next. An interrupt of
 * the calling thread ends a command with {@link DocumentException.Kind#CANCELLED}.
 */
public interface DocumentClient extends AutoCloseable {

  /** Runs the command on the database and gives the server's reply. */
  Document command(String database, Document command) throws DocumentException;

  /** Ends the session and its connections, from any thread, also while a command runs. */
  @Override
  void close();
}
