package dev.hoori.db.core.connect;

import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Tls;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Why a driver could not connect, from what it throws, as each driver was seen to report each case
 * against a real server. The network and TLS failures are Java's own, somewhere in the chain of
 * causes; the server's refusals come with SQLSTATE and error codes.
 */
final class Failures {

  // Beyond this, a chain of causes is taken to loop.
  private static final int MAX_DEPTH = 32;

  private Failures() {}

  static ConnectionException of(ConnectionProfile profile, boolean password, Throwable thrown) {
    return new ConnectionException(failure(profile, password, thrown), detail(thrown), thrown);
  }

  private static Failure failure(ConnectionProfile profile, boolean password, Throwable thrown) {
    var depth = 0;
    for (var t = thrown; t != null && depth < MAX_DEPTH; t = t.getCause(), depth++) {
      if (t instanceof SocketTimeoutException || t instanceof SQLTimeoutException) {
        return Failure.TIMED_OUT;
      }
      if (t instanceof UnknownHostException
          || t instanceof ConnectException
          || t instanceof NoRouteToHostException
          || t instanceof SocketException && fromConnect(t)) {
        return Failure.UNREACHABLE;
      }
      // Under the verified level only, as the others accept any certificate.
      if (t instanceof CertificateException
          || t instanceof CertPathValidatorException
          || t instanceof CertPathBuilderException
          || t instanceof SSLPeerUnverifiedException) {
        return Failure.CERTIFICATE_REJECTED;
      }
    }
    if (!(thrown instanceof SQLException e)) {
      return Failure.OTHER;
    }
    var state = e.getSQLState() == null ? "" : e.getSQLState();
    // 28000 and PostgreSQL's 28P01; MariaDB and MySQL say 1045 for a wrong password and an
    // unknown user alike.
    if (state.startsWith("28")) {
      return Failure.LOGIN_REFUSED;
    }
    var tls = profile.tls() != Tls.OFF;
    var message = e.getMessage() == null ? "" : e.getMessage();
    return switch (profile.provider()) {
      case POSTGRESQL ->
        switch (state) {
          case "3D000" -> Failure.NO_SUCH_DATABASE;
          // pgjdbc's own refusal, both when the server offers no TLS and when the server asks for
          // a password and there is none; its messages are translated, so the password tells.
          case "08004" ->
            !password ? Failure.LOGIN_REFUSED : tls ? Failure.TLS_UNAVAILABLE : Failure.OTHER;
          default -> Failure.OTHER;
        };
      case MARIADB, MYSQL ->
        switch (e.getErrorCode()) {
          // Unknown database, and no access to it, which is what a user without global
          // privileges gets for a database that does not exist.
          case 1044, 1049 -> Failure.NO_SUCH_DATABASE;
          default ->
            // Both drivers give a missing TLS no code of its own; their messages are English only.
            tls && state.startsWith("08") && message.contains(noTls(profile))
                ? Failure.TLS_UNAVAILABLE
                : Failure.OTHER;
        };
    };
  }

  private static String noTls(ConnectionProfile profile) {
    return switch (profile.provider()) {
      case MARIADB -> "ssl not enabled in the server";
      case MYSQL -> "SSL Connection required, but not provided by server";
      case POSTGRESQL -> throw new IllegalArgumentException("PostgreSQL has a state for it");
    };
  }

  // Thrown while opening the socket, so that nothing has answered yet: "Network is unreachable",
  // for one, is a plain SocketException.
  private static boolean fromConnect(Throwable t) {
    for (var frame : t.getStackTrace()) {
      if (frame.getMethodName().equals("connect")
          && (frame.getClassName().startsWith("java.net.")
              || frame.getClassName().startsWith("sun.nio.ch."))) {
        return true;
      }
    }
    return false;
  }

  // The driver's message, and what Java said underneath where the driver does not repeat it:
  // MySQL Connector/J's "Communications link failure" names neither the host nor the reason.
  private static String detail(Throwable thrown) {
    var message = thrown.getMessage() == null ? thrown.getClass().getName() : thrown.getMessage();
    var innermost = thrown;
    var depth = 0;
    while (innermost.getCause() != null && depth++ < MAX_DEPTH) {
      innermost = innermost.getCause();
    }
    var reason = innermost.getMessage();
    if (innermost != thrown
        && !(innermost instanceof SQLException)
        && reason != null
        && !message.contains(reason)) {
      message += "\n" + reason;
    }
    return message;
  }
}
