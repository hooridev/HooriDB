package dev.hoori.db.core.dialect;

import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.monitor.ServerSession;
import java.util.List;

/** How a provider of documents tells what the sessions of its server do now. */
public interface DocumentMonitorDialect {

  /** The database its commands of the server's state run on. */
  String database();

  /**
   * The command whose cursor gives the server's operations and the connections of its clients,
   * marked with the text, so that its own entry is found among them.
   *
   * @param allUsers of every user, which needs a privilege, or of the user's own
   */
  Document sessions(boolean allUsers, String mark);

  /**
   * The sessions of the clients' connections among the entries of the command's cursor, in the
   * order of their ids; the connection that runs the entry with the mark is the own.
   */
  List<ServerSession> sessions(List<Document> entries, String mark);

  /** The command that stops what runs by the operation's id; the server does not say whether. */
  Document cancel(String operation);
}
