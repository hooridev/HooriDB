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
  private static final String PGJDBC_FACTORY = "org.postgresql.core.v3.ConnectionFactoryImpl";
  private static final String PGJDBC_PLUGINS = "org.postgresql.core.v3.AuthenticationPluginManager";

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
    return switch (profile.provider()) {
      case POSTGRESQL ->
        switch (state) {
          case "3D000" -> Failure.NO_SUCH_DATABASE;
          // pgjdbc's own refusal of many kinds, with translated messages; where it was raised
          // tells them apart: the answer to its request for TLS, or the lack of a password when the
          // server asks for one. An authentication method it does not know is neither.
          case "08004" ->
            raisedIn(e, PGJDBC_FACTORY, "enableSSL")
                ? Failure.TLS_UNAVAILABLE
                : raisedIn(e, PGJDBC_FACTORY, "lambda$doAuthentication$")
                        || raisedIn(e, PGJDBC_PLUGINS, "lambda$withEncodedPassword$")
                    ? Failure.LOGIN_REFUSED
                    : Failure.OTHER;
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

  // Whether the exception was made in the class, by the method or one whose name starts so.
  private static boolean raisedIn(Throwable t, String type, String method) {
    var trace = t.getStackTrace();
    return trace.length > 0
        && trace[0].getClassName().equals(type)
        && trace[0].getMethodName().startsWith(method);
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
