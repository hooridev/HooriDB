package dev.hoori.db.app;

import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.dialect.Dialect;
import dev.hoori.db.core.dialect.DocumentDialect;

/** Why a connection failed, in the same words wherever it shows: in the dialog and in the tree. */
final class Words {

  private Words() {}

  static String title(ConnectionException.Failure failure) {
    return switch (failure) {
      case LOGIN_REFUSED -> "Login refused";
      case NO_SUCH_DATABASE -> "No such database";
      case UNREACHABLE -> "The server cannot be reached";
      case TIMED_OUT -> "No answer in time";
      case TLS_UNAVAILABLE -> "The server offers no TLS";
      case CERTIFICATE_REJECTED -> "The certificate was not accepted";
      case UNFIT, CANCELLED, OTHER -> "The connection failed";
    };
  }

  // A server of documents looks the user up in one database, which a wrong one fails as a wrong
  // password does.
  private static String definedIn(ConnectionProfile p) {
    return switch (p.provider().traits()) {
      case Dialect _ -> "";
      case DocumentDialect d ->
        " in the database " + (p.authDatabase().isEmpty() ? d.authDatabase() : p.authDatabase());
    };
  }

  /**
   * What the user can do about the failure, or null where the driver's words are all there is.
   *
   * @param withPassword whether a password was sent
   */
  static String note(
      ConnectionException.Failure failure, ConnectionProfile p, boolean withPassword) {
    return switch (failure) {
      case LOGIN_REFUSED ->
        withPassword
            ? "The server refused this user name and password" + definedIn(p) + "."
            : "The server refused the login without a password. Enter the password.";
      case NO_SUCH_DATABASE ->
        p.database().isBlank()
            ? "The server has no database named after the user, which it opens when none is"
                + " given. Enter the database to open."
            : "The server has no database named " + p.database()
                + ", or this user may not open it.";
      case UNREACHABLE ->
        p.srv()
            ? "Nothing answered at the servers that DNS lists for " + p.host()
                + ". Check the host, and that the servers are running."
            : "Nothing answered at " + p.host() + ", port " + p.port()
                + ". Check the host and the port, and that the server is running.";
      case TIMED_OUT ->
        (p.srv()
                ? "The servers that DNS lists for " + p.host() + " did not answer within "
                : p.host() + " did not answer on port " + p.port() + " within ")
            + Tester.TIMEOUT.toSeconds()
            + (p.srv()
                ? " seconds. Check the host; a firewall may be in the way."
                : " seconds. Check the host and the port; a firewall may be in the way.");
      case TLS_UNAVAILABLE ->
        "The server offers no TLS. Off connects without it, and password and data go over the"
            + " network as they are.";
      case CERTIFICATE_REJECTED ->
        "The server's certificate is not trusted, or not for " + p.host()
            + ". “Encrypted, any certificate” accepts it, without verifying the server.";
      case UNFIT, CANCELLED, OTHER -> null;
    };
  }
}
