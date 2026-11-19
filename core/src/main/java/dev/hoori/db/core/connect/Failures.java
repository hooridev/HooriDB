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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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

  /** The failure; the password is the one the driver got, or null for none. */
  static ConnectionException of(ConnectionProfile profile, String password, Throwable thrown) {
    var failure = failure(profile, thrown);
    // A server may quote what it received. Every text of the chain is dropped then, and not just
    // the password cut out, which would also cut a short one out of words that merely hold it.
    if (password != null
        && mentions(thrown, password, Collections.newSetFromMap(new IdentityHashMap<>()))) {
      return new ConnectionException(failure, ConnectionException.WITHHELD, null);
    }
    return new ConnectionException(failure, detail(thrown), thrown);
  }

  private static Failure failure(ConnectionProfile profile, Throwable thrown) {
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
    return profile.provider().dialect().connecting().refusal(e, state, message, tls);
  }

  // Each text a caller could see of the exception: its causes, suppressed and next exceptions.
  private static boolean mentions(Throwable t, String password, Set<Throwable> seen) {
    if (t == null || !seen.add(t)) {
      return false;
    }
    if (String.valueOf(t.getMessage()).contains(password) || t.toString().contains(password)) {
      return true;
    }
    for (var suppressed : t.getSuppressed()) {
      if (mentions(suppressed, password, seen)) {
        return true;
      }
    }
    return mentions(t.getCause(), password, seen)
        || t instanceof SQLException e && mentions(e.getNextException(), password, seen);
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
