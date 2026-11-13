package dev.hoori.db.core.secret;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Against the real keyring of this machine, so only with -Dhooridb.keyring.test=true: a locked
 * keyring opens a prompt on the desktop, and a plain build must neither wait for it nor leave
 * entries behind.
 */
@EnabledIfSystemProperty(named = "hooridb.keyring.test", matches = "true")
class PlatformKeyringTest {

  @Test
  @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void storesReadsOverwritesAndDeletes() throws IOException {
    Keyring keyring = Keyring.find();
    SecretStore store =
        assertInstanceOf(Keyring.Available.class, keyring, keyring::toString).store();
    UUID key = UUID.randomUUID();
    char[] first = "first pä$$wörd €𝄞".toCharArray();
    char[] second = "second".toCharArray();
    try {
      assertFalse(store.read(key).isPresent(), "a secret is there before it was stored");
      store.delete(key);
      store.store(key, first);
      assertSecret(first, store.read(key));
      store.store(key, second);
      assertSecret(second, store.read(key));
      store.delete(key);
      assertFalse(store.read(key).isPresent(), "a deleted secret is still there");
      store.delete(key);
    } finally {
      store.delete(key);
    }
  }

  // Compares without the arrays in the message, so that no secret reaches the output.
  private static void assertSecret(char[] expected, Optional<char[]> actual) {
    assertTrue(actual.isPresent(), "no secret read back");
    assertTrue(Arrays.equals(expected, actual.get()), "a different secret read back");
    Arrays.fill(actual.get(), '\0');
  }
}
