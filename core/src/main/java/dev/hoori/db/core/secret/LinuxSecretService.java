package dev.hoori.db.core.secret;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
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
import java.lang.invoke.MethodHandle;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/** Secrets in the Secret Service of the desktop, through libsecret. */
final class LinuxSecretService implements SecretStore {

  static final String LIBRARY = "libsecret-1.so.0";
  static final String SCHEMA_NAME = "dev.hoori.db.Secret";
  static final String ATTRIBUTE = "key";

  /** SecretSchema: a name, flags, up to 32 attributes ended by a null name, and reserved fields. */
  static final StructLayout SCHEMA_LAYOUT = structLayout(
      ADDRESS.withName("name"),
      JAVA_INT.withName("flags"),
      paddingLayout(4),
      sequenceLayout(
              32,
              structLayout(ADDRESS.withName("name"), JAVA_INT.withName("type"), paddingLayout(4)))
          .withName("attributes"),
      JAVA_INT.withName("reserved"),
      paddingLayout(4),
      sequenceLayout(7, ADDRESS).withName("reserved1to7"));

  private static final int SECRET_SERVICE_OPEN_SESSION = 1 << 1;
  // Locked items match a search too, and without SECRET_SEARCH_ALL it stops at the first one.
  private static final int SECRET_SEARCH_NONE = 0;
  private static final long ERROR_CODE = 4;
  private static final long ERROR_MESSAGE = 8;

  private final MemorySegment schema;
  private final MethodHandle serviceGet;
  private final MemorySegment objectUnrefSymbol;
  private final MethodHandle objectUnref;
  private final MethodHandle listFreeFull;
  private final MethodHandle errorFree;
  private final MethodHandle storeSync;
  private final MethodHandle lookupSync;
  private final MethodHandle clearSync;
  private final MethodHandle searchSync;
  private final MethodHandle passwordFree;

  /** The service, or why there is none; asks it over D-Bus whether it is there. */
  @SuppressWarnings("restricted") // the keyring reaches its native library through FFM
  static Keyring open(String library) {
    LinuxSecretService service;
    try {
      // GLib cannot be unloaded, so the library stays loaded for good.
      service = new LinuxSecretService(SymbolLookup.libraryLookup(library, Arena.global()));
    } catch (IllegalArgumentException | NoSuchElementException e) {
      return new Unavailable(Reason.LIBRARY_NOT_FOUND, e.getMessage());
    }
    try {
      service.connect();
    } catch (IOException e) {
      return new Unavailable(Reason.NO_SERVICE, e.getMessage());
    }
    return new Available(service);
  }

  @SuppressWarnings("restricted") // the keyring reaches its native library through FFM
  private LinuxSecretService(SymbolLookup lib) {
    Linker linker = Linker.nativeLinker();
    serviceGet = linker.downcallHandle(
        lib.findOrThrow("secret_service_get_sync"),
        FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    objectUnrefSymbol = lib.findOrThrow("g_object_unref");
    objectUnref = linker.downcallHandle(objectUnrefSymbol, FunctionDescriptor.ofVoid(ADDRESS));
    listFreeFull = linker.downcallHandle(
        lib.findOrThrow("g_list_free_full"), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    errorFree =
        linker.downcallHandle(lib.findOrThrow("g_error_free"), FunctionDescriptor.ofVoid(ADDRESS));
    // The variadic functions take the one attribute as arguments, where the ...v ones would
    // need a GHashTable built and freed around each call.
    storeSync = linker.downcallHandle(
        lib.findOrThrow("secret_password_store_sync"),
        FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
            ADDRESS),
        Linker.Option.firstVariadicArg(6));
    lookupSync = linker.downcallHandle(
        lib.findOrThrow("secret_password_lookup_sync"),
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        Linker.Option.firstVariadicArg(3));
    clearSync = linker.downcallHandle(
        lib.findOrThrow("secret_password_clear_sync"),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        Linker.Option.firstVariadicArg(3));
    searchSync = linker.downcallHandle(
        lib.findOrThrow("secret_password_search_sync"),
        FunctionDescriptor.of(
            ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        Linker.Option.firstVariadicArg(4));
    passwordFree = linker.downcallHandle(
        lib.findOrThrow("secret_password_free"), FunctionDescriptor.ofVoid(ADDRESS));

    // Lives as long as this object; the attribute after the first is null and ends the list.
    Arena arena = Arena.ofAuto();
    schema = arena.allocate(SCHEMA_LAYOUT);
    schema.set(ADDRESS, 0, arena.allocateFrom(SCHEMA_NAME));
    schema.set(
        ADDRESS,
        SCHEMA_LAYOUT.byteOffset(groupElement("attributes")),
        arena.allocateFrom(ATTRIBUTE));
  }

  @Override
  public void store(UUID key, char[] secret) throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment password = utf8(secret, call);
      try {
        MemorySegment error = call.allocate(ADDRESS);
        int stored = (int) call(
            storeSync,
            schema,
            MemorySegment.NULL,
            call.allocateFrom(label(key)),
            password,
            MemorySegment.NULL,
            error,
            call.allocateFrom(ATTRIBUTE),
            call.allocateFrom(key.toString()),
            MemorySegment.NULL);
        check(error);
        if (stored == 0) {
          throw new IOException(
              "Secret Service did not store the secret; the keyring may be locked");
        }
      } finally {
        password.fill((byte) 0);
      }
    }
  }

  @Override
  public Optional<char[]> read(UUID key) throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment error = call.allocate(ADDRESS);
      MemorySegment password = (MemorySegment) call(
          lookupSync,
          schema,
          MemorySegment.NULL,
          error,
          call.allocateFrom(ATTRIBUTE),
          call.allocateFrom(key.toString()),
          MemorySegment.NULL);
      check(error);
      if (password.equals(MemorySegment.NULL)) {
        // Also null when the entry is there but its collection stayed locked.
        throwIfFound(key, "The keyring is locked; the secret cannot be read");
        return Optional.empty();
      }
      MemorySegment bytes = cString(password);
      try {
        return Optional.of(fromUtf8(bytes));
      } finally {
        bytes.fill((byte) 0);
        call(passwordFree, password);
      }
    }
  }

  @Override
  public void delete(UUID key) throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment error = call.allocate(ADDRESS);
      call(
          clearSync,
          schema,
          MemorySegment.NULL,
          error,
          call.allocateFrom(ATTRIBUTE),
          call.allocateFrom(key.toString()),
          MemorySegment.NULL);
      check(error);
    }
    // Clearing skips entries in locked collections and says so with no error.
    throwIfFound(key, "The keyring is locked; the secret is still stored");
  }

  private void throwIfFound(UUID key, String message) throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment error = call.allocate(ADDRESS);
      MemorySegment items = (MemorySegment) call(
          searchSync,
          schema,
          SECRET_SEARCH_NONE,
          MemorySegment.NULL,
          error,
          call.allocateFrom(ATTRIBUTE),
          call.allocateFrom(key.toString()),
          MemorySegment.NULL);
      check(error);
      if (!items.equals(MemorySegment.NULL)) {
        call(listFreeFull, items, objectUnrefSymbol);
        throw new IOException(message);
      }
    }
  }

  /** Opens a session with the service, which fails when none is running. */
  private void connect() throws IOException {
    try (Arena call = Arena.ofConfined()) {
      MemorySegment error = call.allocate(ADDRESS);
      MemorySegment service =
          (MemorySegment) call(serviceGet, SECRET_SERVICE_OPEN_SESSION, MemorySegment.NULL, error);
      check(error);
      call(objectUnref, service);
    }
  }

  /** Throws the GError that a call left, with its message and code, and frees it. */
  @SuppressWarnings("restricted") // the keyring reaches its native library through FFM
  private void check(MemorySegment error) throws IOException {
    MemorySegment gerror = error.get(ADDRESS, 0);
    if (gerror.equals(MemorySegment.NULL)) {
      return;
    }
    gerror = gerror.reinterpret(16);
    String message =
        gerror.get(ADDRESS, ERROR_MESSAGE).reinterpret(Long.MAX_VALUE).getString(0);
    int code = gerror.get(JAVA_INT, ERROR_CODE);
    call(errorFree, gerror);
    throw new IOException("Secret Service: " + message + " (code " + code + ")");
  }

  static String label(UUID key) {
    return "HooriDB " + key;
  }

  /** The secret in UTF-8 and ended by a zero, in memory of the arena. */
  static MemorySegment utf8(char[] secret, Arena arena) {
    for (char c : secret) {
      if (c == 0) {
        throw new IllegalArgumentException("A secret cannot hold a NUL character");
      }
    }
    // At most three bytes for each char; the rest stays zero and ends the string.
    MemorySegment out = arena.allocate(secret.length * 3L + 1);
    CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
    var bytes = out.asByteBuffer();
    CoderResult result = encoder.encode(CharBuffer.wrap(secret), bytes, true);
    if (result.isUnderflow()) {
      result = encoder.flush(bytes);
    }
    if (result.isError()) {
      out.fill((byte) 0);
      throw new IllegalArgumentException("A secret must not hold an unpaired surrogate");
    }
    return out;
  }

  /** The bytes of a string that ends with a zero, without the zero. */
  @SuppressWarnings("restricted") // the keyring reaches its native library through FFM
  static MemorySegment cString(MemorySegment pointer) {
    MemorySegment all = pointer.reinterpret(Long.MAX_VALUE);
    long length = 0;
    while (all.get(JAVA_BYTE, length) != 0) {
      length++;
    }
    return pointer.reinterpret(length);
  }

  static char[] fromUtf8(MemorySegment bytes) throws IOException {
    char[] buffer = new char[Math.toIntExact(bytes.byteSize())];
    CharBuffer chars = CharBuffer.wrap(buffer);
    try {
      if (StandardCharsets.UTF_8
          .newDecoder()
          .decode(bytes.asByteBuffer(), chars, true)
          .isError()) {
        throw new IOException("The stored secret is not valid UTF-8");
      }
      return Arrays.copyOf(buffer, chars.position());
    } finally {
      Arrays.fill(buffer, '\0');
    }
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
