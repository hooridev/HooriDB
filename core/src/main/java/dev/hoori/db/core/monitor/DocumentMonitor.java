package dev.hoori.db.core.monitor;

import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The monitor of a server of documents, by its dialect's commands, each through {@link
 * DocumentSession#command}, so that a read-only session sends only those that read.
 */
public record DocumentMonitor(DocumentSession session) implements Monitor {

  /**
   * Of every user where the user may see them, else the user's own: a server refuses to tell a
   * user without the privilege of the others'.
   */
  @Override
  public List<ServerSession> sessions() throws QueryException {
    var monitor = session.dialect().monitor();
    // A mark on the read, so that its own entry tells the monitor's session among the others.
    var mark = "HooriDB monitor " + UUID.randomUUID();
    try {
      List<Document> entries;
      try {
        entries = all(monitor.database(), monitor.sessions(true, mark));
      } catch (DocumentException e) {
        if (e.kind() != DocumentException.Kind.SERVER
            || e.code() != DocumentException.UNAUTHORIZED) {
          throw e;
        }
        entries = all(monitor.database(), monitor.sessions(false, mark));
      }
      var masked = entries.stream().map(Credentials::mask).toList();
      return List.copyOf(monitor.sessions(masked, mark));
    } catch (DocumentException e) {
      throw DocumentSession.query(e);
    }
  }

  // The documents of a command's cursor, all of them; its cursor is freed where reading fails.
  private List<Document> all(String database, Document command) throws DocumentException {
    var cursor = session.cursor(database, session.command(database, command));
    var documents = new ArrayList<Document>();
    try {
      while (cursor.more()) {
        var next = cursor.next(Integer.MAX_VALUE);
        if (next.isEmpty()) {
          break;
        }
        documents.addAll(next);
      }
    } catch (DocumentException e) {
      try {
        cursor.close();
      } catch (DocumentException left) {
        e.addSuppressed(left);
      }
      throw e;
    }
    return documents;
  }
}
