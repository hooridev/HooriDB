package dev.hoori.db.core.secret;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.secret.Keyring.Reason;
import dev.hoori.db.core.secret.Keyring.Unavailable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class KeyringTest {

  private static final char[] SECRET = "pä$$ wörd €𝄞".toCharArray();

  @Test
  void otherPlatformsHaveNoKeyring() {
    assertEquals(Reason.UNSUPPORTED_PLATFORM, reason(Keyring.find("Mac OS X", "aarch64")));
    assertEquals(Reason.UNSUPPORTED_PLATFORM, reason(Keyring.find("FreeBSD", "amd64")));
    assertEquals(Reason.UNSUPPORTED_PLATFORM, reason(Keyring.find("Linux", "aarch64")));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void windowsCredentialsAreMissingOnLinux() {
    assertEquals(Reason.LIBRARY_NOT_FOUND, reason(Keyring.find("Windows 11", "amd64")));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void libsecretIsMissingOnWindows() {
    assertEquals(Reason.LIBRARY_NOT_FOUND, reason(Keyring.find("Linux", "amd64")));
  }

  @Test
  void aMissingLibsecretIsNoKeyring() {
    assertEquals(
        Reason.LIBRARY_NOT_FOUND, reason(LinuxSecretService.open("libhooridb-missing.so.0")));
  }

  @Test
  void entriesAreMarkedAsHooriDbs() {
    // A UUID has one spelling, so Windows' case-insensitive target names cannot alias two keys.
    UUID key = UUID.fromString("0F8FAD5B-D9CB-469F-A165-70867728950E");
    assertEquals("HooriDB:0f8fad5b-d9cb-469f-a165-70867728950e", WindowsCredentials.target(key));
    assertEquals("HooriDB 0f8fad5b-d9cb-469f-a165-70867728950e", LinuxSecretService.label(key));
    assertEquals("dev.hoori.db.Secret", LinuxSecretService.SCHEMA_NAME);
  }

  @Test
  void secretsGoToLibsecretInUtf8() throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment bytes = LinuxSecretService.utf8(SECRET, arena);
      MemorySegment string = LinuxSecretService.cString(bytes);
      assertEquals(new String(SECRET).getBytes(StandardCharsets.UTF_8).length, string.byteSize());
      assertTrue(Arrays.equals(SECRET, LinuxSecretService.fromUtf8(string)));
      assertEquals(
          0,
          LinuxSecretService.cString(LinuxSecretService.utf8(new char[0], arena))
              .byteSize());
    }
  }

  @Test
  void secretsThatLibsecretCannotHoldAreRefused() {
    try (Arena arena = Arena.ofConfined()) {
      var nul = "a\0b".toCharArray();
      assertThrows(IllegalArgumentException.class, () -> LinuxSecretService.utf8(nul, arena));
      assertThrows(
          IllegalArgumentException.class,
          () -> LinuxSecretService.utf8(new char[] {'\uD834'}, arena));
      MemorySegment malformed = arena.allocateFrom(ValueLayout.JAVA_BYTE, (byte) 0xC3);
      assertThrows(IOException.class, () -> LinuxSecretService.fromUtf8(malformed));
    }
  }

  @Test
  void secretsGoToWindowsInUtf16() throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment blob = WindowsCredentials.utf16(SECRET, arena);
      assertEquals(SECRET.length * 2L, blob.byteSize());
      assertEquals((byte) 'p', blob.get(ValueLayout.JAVA_BYTE, 0));
      assertTrue(Arrays.equals(SECRET, WindowsCredentials.fromUtf16(blob)));
      assertThrows(IOException.class, () -> WindowsCredentials.fromUtf16(blob.asSlice(0, 3)));
      MemorySegment target = WindowsCredentials.wide("ab", arena);
      assertEquals(6, target.byteSize());
      assertEquals('b', target.getAtIndex(WindowsCredentials.WCHAR, 1));
      assertEquals(0, target.getAtIndex(WindowsCredentials.WCHAR, 2));
    }
  }

  @Test
  void nativeStructuresHaveTheirX64Layout() {
    assertEquals(80, WindowsCredentials.CREDENTIAL.byteSize());
    assertEquals(32, WindowsCredentials.CREDENTIAL.byteOffset(groupElement("CredentialBlobSize")));
    assertEquals(40, WindowsCredentials.CREDENTIAL.byteOffset(groupElement("CredentialBlob")));
    assertEquals(72, WindowsCredentials.CREDENTIAL.byteOffset(groupElement("UserName")));
    assertEquals(592, LinuxSecretService.SCHEMA_LAYOUT.byteSize());
    assertEquals(16, LinuxSecretService.SCHEMA_LAYOUT.byteOffset(groupElement("attributes")));
  }

  @Test
  void anUnavailableKeyringSaysWhy() {
    Unavailable none = assertInstanceOf(Unavailable.class, Keyring.find("Mac OS X", "aarch64"));
    assertFalse(none.detail().isBlank());
  }

  private static Reason reason(Keyring keyring) {
    return assertInstanceOf(Unavailable.class, keyring, keyring::toString).reason();
  }
}
