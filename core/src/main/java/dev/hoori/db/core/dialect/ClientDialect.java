package dev.hoori.db.core.dialect;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * What the server's own command-line client does with a file of statements beyond the server's
 * SQL, as its dumps need it: commands of its own, and rows that follow a statement in the file.
 * {@link SyntaxDialect#clientDelimiter} is the one of MariaDB's and MySQL's clients.
 */
public interface ClientDialect {

  /**
   * Whether a backslash where a statement could begin starts a command of the client that runs to
   * the end of its line, as psql's meta-commands such as {@code \connect} do.
   */
  boolean backslashCommands();

  /**
   * The client's commands, by name without the backslash, that a run of a file skips, as they do
   * nothing that it needs; it refuses every other.
   */
  Set<String> skippedCommands();

  /**
   * Whether a statement can copy rows from the client, as PostgreSQL's {@code COPY … FROM STDIN}:
   * the rows follow it in the file, from its next line up to a line {@code \.} alone.
   */
  boolean copiesFromClient();

  /**
   * Copies the rows that the reader gives, as the file has them, into what the statement names,
   * through the driver's copy API; the number of rows the server took. A failure of the reader
   * ends the copy, which the server then rolls back, and is thrown as it is.
   *
   * @throws UnsupportedOperationException where the server copies nothing from the client
   */
  long copyIn(Connection connection, String sql, Reader rows) throws SQLException, IOException;
}
