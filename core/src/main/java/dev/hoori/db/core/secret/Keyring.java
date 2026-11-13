package dev.hoori.db.core.secret;

/** The keyring of the platform, or why there is none, in which case a password is asked for. */
public sealed interface Keyring {

  record Available(SecretStore store) implements Keyring {}

  record Unavailable(Reason reason, String detail) implements Keyring {}

  enum Reason {
    /** HooriDB knows no keyring on this operating system or architecture. */
    UNSUPPORTED_PLATFORM,
    /** The keyring's native library is not installed. */
    LIBRARY_NOT_FOUND,
    /** The library is there, but no keyring service answers. */
    NO_SERVICE
  }

  /** The keyring of the platform this runs on. On Linux it asks the Secret Service over D-Bus. */
  static Keyring find() {
    return find(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  /** The keyring for the operating system and architecture named as Java names them. */
  static Keyring find(String osName, String osArch) {
    if (!osArch.equals("amd64")) {
      return new Unavailable(Reason.UNSUPPORTED_PLATFORM, osName + " on " + osArch);
    }
    if (osName.startsWith("Windows")) {
      return WindowsCredentials.open();
    }
    if (osName.equals("Linux")) {
      return LinuxSecretService.open(LinuxSecretService.LIBRARY);
    }
    return new Unavailable(Reason.UNSUPPORTED_PLATFORM, osName + " on " + osArch);
  }
}
