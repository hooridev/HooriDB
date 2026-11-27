package dev.hoori.db.core.dialect;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

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
   * Whether a statement begins or ends a transaction, or turns auto-commit on or off, by its words,
   * in lower case and in order, as {@link dev.hoori.db.core.sql.TransactionControl} reads them: a
   * run of a file in one transaction refuses it, as it would end that transaction.
   */
  boolean controlsTransaction(List<String> words);

  /**
   * Whether the server commits the open transaction by itself as it runs a statement, by its words
   * as for {@link #controlsTransaction}: as MySQL's DDL, where {@link
   * dev.hoori.db.core.connection.Capability#TRANSACTIONAL_DDL} is not had, and its {@code LOCK
   * TABLES}, which its dumps write around each table's rows. A run of a file in one transaction
   * runs it, and says that what ran up to it stays. Not {@code UNLOCK TABLES}, which commits only
   * where the session locked tables, as {@link #tableLocks} tells.
   */
  boolean commitsImplicitly(List<String> words);

  /**
   * Whether a statement runs SQL that its words do not show and that may end the open
   * transaction, by its words as for {@link #controlsTransaction}: as a {@code CALL} of a MySQL
   * procedure whose body commits, or an {@code EXECUTE} of a prepared {@code COMMIT}. A run of a
   * file in one transaction refuses it, as it could not tell what the rollback still undoes.
   */
  boolean mayEndUnseen(List<String> words);

  /** What a statement does to the tables that the session locked, as MySQL's LOCK TABLES. */
  enum TableLocks {
    /** It leaves them as they are. */
    KEEPS,
    /** It locks tables, those it names alone. */
    LOCKS,
    /** It unlocks them, which commits the open transaction where it held any. */
    UNLOCKS
  }

  /**
   * What the statement does to the tables the session locked, by its words as for {@link
   * #controlsTransaction}; {@link TableLocks#KEEPS} where locking them commits nothing.
   */
  TableLocks tableLocks(List<String> words);

  /**
   * Whether a statement copies rows from the client, as PostgreSQL's {@code COPY … FROM STDIN}, by
   * its first word and, where that may begin one, its words outside parentheses, each in lower
   * case and in order, a name as an empty word: the rows follow it in the file, from its next line
   * up to the line {@link #endOfRows} alone.
   */
  boolean copiesFromClient(String first, Supplier<List<String>> words);

  /**
   * What ends the rows a statement copies from the client, alone on a line, as psql's {@code \.}.
   *
   * @throws UnsupportedOperationException where the server copies nothing from the client
   */
  String endOfRows();

  /**
   * Copies the rows that the reader gives, as the file has them, into what the statement names,
   * through the driver's copy API; the number of rows the server took. A failure of the reader
   * ends the copy, which the server then rolls back, and is thrown as it is.
   *
   * @throws UnsupportedOperationException where the server copies nothing from the client
   */
  long copyIn(Connection connection, String sql, Reader rows) throws SQLException, IOException;
}
