package dev.hoori.db.core.connection;

/** Whether a connection is encrypted, and whether the server must prove who it is. */
public enum Tls {
  /** Not encrypted. */
  OFF,
  /**
   * Encrypted, or no connection if the server offers no TLS, but any certificate is accepted: it
   * keeps out those who listen, not those who stand in between.
   */
  ENCRYPTED,
  /**
   * Encrypted, with the server's certificate chain checked against the JVM's trust store and its
   * host name against the certificate.
   */
  VERIFIED
}
