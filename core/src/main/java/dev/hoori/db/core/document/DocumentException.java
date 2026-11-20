package dev.hoori.db.core.document;

import java.util.Objects;

/**
 * Why a command of a server of documents failed, or the client could not reach it: the kind, and
 * for the server's own refusal its code and its words. The detail never holds the password.
 */
public final class DocumentException extends Exception {

  public enum Kind {
    /** Nothing answered at the host and port: the host is unknown or unreachable, or refused. */
    UNREACHABLE,
    /** The time limit passed before the server answered or let the login finish. */
    TIMED_OUT,
    /** The server refused the user or the password. */
    LOGIN_REFUSED,
    /** The target asks for TLS, and the server offers none. */
    TLS_UNAVAILABLE,
    /** The server's certificate is not trusted, or not for the host, under the verified level. */
    CERTIFICATE_REJECTED,
    /** The thread was interrupted, or the client closed under the command. */
    CANCELLED,
    /** The connection broke while the command ran. */
    CONNECTION_LOST,
    /** The server refused the command; {@link #code} and {@link #codeName} say why. */
    SERVER,
    /** The connection is read-only, and the command would change something; it was not sent. */
    READ_ONLY,
    /** Anything else; the detail says what. */
    OTHER
  }

  /** The server's code of a command it does not know. */
  public static final int COMMAND_NOT_FOUND = 59;

  /** The server's code of a user that may not run the command. */
  public static final int UNAUTHORIZED = 13;

  /** The server's code of a namespace, such as a collection, that is not there. */
  public static final int NAMESPACE_NOT_FOUND = 26;

  /** The server's code of a cursor that is not there any more, as one that timed out. */
  public static final int CURSOR_NOT_FOUND = 43;

  /** The server's code of an operation it stopped, as one that was killed. */
  public static final int INTERRUPTED = 11601;

  /** The server's code of an operation that ran past its maxTimeMS. */
  public static final int MAX_TIME_EXPIRED = 50;

  private final Kind kind;
  private final int code;
  private final String codeName;
  private final String detail;

  public DocumentException(Kind kind, int code, String codeName, String detail) {
    super(detail.isEmpty() ? kind.toString() : kind + ": " + detail);
    this.kind = Objects.requireNonNull(kind, "kind");
    this.code = code;
    this.codeName = Objects.requireNonNull(codeName, "codeName");
    this.detail = detail;
  }

  /** A failure that is not the server's refusal of a command. */
  public static DocumentException of(Kind kind, String detail) {
    return new DocumentException(kind, 0, "", detail);
  }

  /** The server's refusal of a command. */
  public static DocumentException server(int code, String codeName, String detail) {
    return new DocumentException(Kind.SERVER, code, codeName, detail);
  }

  public Kind kind() {
    return kind;
  }

  /** The server's code, or 0 where the server did not refuse the command. */
  public int code() {
    return code;
  }

  /** The server's name of the code, such as {@code Unauthorized}, or empty. */
  public String codeName() {
    return codeName;
  }

  /** The words of the server or the driver, or empty. */
  public String detail() {
    return detail;
  }
}
