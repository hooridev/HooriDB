package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.ServerVersion;

/**
 * From which version a provider's servers have a capability, and for a form of {@code EXPLAIN}
 * what goes before the statement; null for the others.
 */
public record Since(ServerVersion version, String prefix) {

  static Since always() {
    return always(null);
  }

  static Since always(String prefix) {
    return new Since(ServerVersion.UNKNOWN, prefix);
  }

  static Since since(int major, int minor, int patch, String prefix) {
    return new Since(new ServerVersion(major, minor, patch), prefix);
  }
}
