package dev.hoori.db.core.secret;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import dev.hoori.db.core.secret.Keyring.Available;
import dev.hoori.db.core.secret.Keyring.Reason;
import dev.hoori.db.core.secret.Keyring.Unavailable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/** Secrets as generic credentials of Windows Credential Manager, through advapi32. */
final class WindowsCredentials implements SecretStore {

  static final String LIBRARY = "advapi32.dll";

  /** CREDENTIALW on x64. */
  static final StructLayout CREDENTIAL = structLayout(
      JAVA_INT.withName("Flags"),
      JAVA_INT.withName("Type"),
      ADDRESS.withName("TargetName"),
      ADDRESS.withName("Comment"),
      structLayout(JAVA_INT.withName("dwLowDateTime"), JAVA_INT.withName("dwHighDateTime"))
          .withName("LastWritten"),
      JAVA_INT.withName("CredentialBlobSize"),
      paddingLayout(4),
      ADDRESS.withName("CredentialBlob"),
      JAVA_INT.withName("Persist"),
      JAVA_INT.withName("AttributeCount"),
      ADDRESS.withName("Attributes"),
      ADDRESS.withName("TargetAlias"),
      ADDRESS.withName("UserName"));

  static final ValueLayout.OfChar WCHAR = JAVA_CHAR_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  private static final long TYPE = CREDENTIAL.byteOffset(groupElement("Type"));
  private static final long TARGET_NAME = CREDENTIAL.byteOffset(groupElement("TargetName"));
  private static final long BLOB_SIZE = CREDENTIAL.byteOffset(groupElement("CredentialBlobSize"));
  private static final long BLOB = CREDENTIAL.byteOffset(groupElement("CredentialBlob"));
  private static final long PERSIST = CREDENTIAL.byteOffset(groupElement("Persist"));

  private static final int CRED_TYPE_GENERIC = 1;
  // Kept for this user on this machine across logons, and not roamed to other machines.
  private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
  private static final int ERROR_NOT_FOUND = 1168;

  private final StructLayout state = Linker.Option.captureStateLayout();
  private final long lastError = state.byteOffset(groupElement("GetLastError"));
  private final MethodHandle credWrite;
  private final MethodHandle credRead;
  private final MethodHandle credDelete;
  private final MethodHandle credFree;

  static Keyring open() {
    try {
      return new Available(
          new WindowsCredentials(SymbolLookup.libraryLookup(LIBRARY, Arena.global())));
    } catch (IllegalArgumentException | NoSuchElementException e) {
      return new Unavailable(Reason.LIBRARY_NOT_FOUND, e.getMessage());
    }
  }

  private WindowsCredentials(SymbolLookup lib) {
    Linker linker = Linker.nativeLinker();
    Linker.Option capture = Linker.Option.captureCallState("GetLastError");
    credWrite = linker.downcallHandle(
        lib.findOrThrow("CredWriteW"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), capture);
    credRead = linker.downcallHandle(
        lib.findOrThrow("CredReadW"),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
        capture);
    credDelete = linker.downcallHandle(
        lib.findOrThrow("CredDeleteW"),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
        capture);
    credFree =
        linker.downcallHandle(lib.findOrThrow("CredFree"), FunctionDescriptor.ofVoid(ADDRESS));
  }

  @Override
  public void store(UUID key, char[] secret) throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment blob = utf16(secret, call);
      try {
        MemorySegment credential = call.allocate(CREDENTIAL);
        credential.set(JAVA_INT, TYPE, CRED_TYPE_GENERIC);
        credential.set(ADDRESS, TARGET_NAME, wide(target(key), call));
        credential.set(JAVA_INT, BLOB_SIZE, (int) blob.byteSize());
        credential.set(ADDRESS, BLOB, blob);
        credential.set(JAVA_INT, PERSIST, CRED_PERSIST_LOCAL_MACHINE);
        MemorySegment error = call.allocate(state);
        if ((int) call(credWrite, error, credential, 0) == 0) {
          throw failure("CredWriteW", error.get(JAVA_INT, lastError));
        }
      } finally {
        blob.fill((byte) 0);
      }
    }
  }

  @Override
  public Optional<char[]> read(UUID key) throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment error = call.allocate(state);
      MemorySegment out = call.allocate(ADDRESS);
      if ((int) call(credRead, error, wide(target(key), call), CRED_TYPE_GENERIC, 0, out) == 0) {
        int code = error.get(JAVA_INT, lastError);
        if (code == ERROR_NOT_FOUND) {
          return Optional.empty();
        }
        throw failure("CredReadW", code);
      }
      MemorySegment credential = out.get(ADDRESS, 0).reinterpret(CREDENTIAL.byteSize());
      try {
        MemorySegment blob =
            credential.get(ADDRESS, BLOB).reinterpret(credential.get(JAVA_INT, BLOB_SIZE));
        try {
          return Optional.of(fromUtf16(blob));
        } finally {
          blob.fill((byte) 0);
        }
      } finally {
        call(credFree, credential);
      }
    }
  }

  @Override
  public void delete(UUID key) throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment error = call.allocate(state);
      if ((int) call(credDelete, error, wide(target(key), call), CRED_TYPE_GENERIC, 0) == 0) {
        int code = error.get(JAVA_INT, lastError);
        if (code != ERROR_NOT_FOUND) {
          throw failure("CredDeleteW", code);
        }
      }
    }
  }

  private static IOException failure(String function, int code) {
    return new IOException(function + " failed with Windows error " + code);
  }

  static String target(UUID key) {
    return "HooriDB:" + key;
  }

  /** The string in UTF-16 and ended by a zero, as Windows takes it. */
  static MemorySegment wide(String text, Arena arena) {
    MemorySegment out = arena.allocate((text.length() + 1L) * 2);
    for (int i = 0; i < text.length(); i++) {
      out.setAtIndex(WCHAR, i, text.charAt(i));
    }
    return out;
  }

  /** The secret in UTF-16, without an end, as the blob of a credential. */
  static MemorySegment utf16(char[] secret, Arena arena) {
    MemorySegment out = arena.allocate(secret.length * 2L);
    MemorySegment.copy(secret, 0, out, WCHAR, 0, secret.length);
    return out;
  }

  static char[] fromUtf16(MemorySegment blob) throws IOException {
    if (blob.byteSize() % 2 != 0) {
      throw new IOException("The stored secret is not UTF-16");
    }
    char[] secret = new char[Math.toIntExact(blob.byteSize() / 2)];
    MemorySegment.copy(blob, WCHAR, 0, secret, 0, secret.length);
    return secret;
  }

  private static Object call(MethodHandle handle, Object... arguments) {
    try {
      return handle.invokeWithArguments(arguments);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new AssertionError(e);
    }
  }
}
