package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link Recorded.RecordedLink} wipes its copy of the password when it is aborted. */
class RecordedTest {

  // A link whose abort runs onAbort; the constructor asks it for its database, which is none.
  private static Servers.Link link(Runnable onAbort) {
    return (Servers.Link) Proxy.newProxyInstance(
        Servers.Link.class.getClassLoader(),
        new Class<?>[] {Servers.Link.class},
        (_, method, _) -> {
          if (method.getName().equals("abort")) {
            onAbort.run();
          } else if (!method.getName().equals("database")) {
            throw new UnsupportedOperationException(method.getName());
          }
          return null;
        });
  }

  @Test
  void abortWipesThePassword() {
    var password = "s3cret".toCharArray();
    new Recorded.RecordedLink(
            link(() -> {}), UUID.randomUUID(), new Recorded.Secret(password), null)
        .abort();
    assertArrayEquals(new char[password.length], password);
  }

  @Test
  void abortWipesThePasswordAlsoWhenItFails() {
    var password = "s3cret".toCharArray();
    var link = new Recorded.RecordedLink(
        link(() -> {
          throw new IllegalStateException("stuck");
        }),
        UUID.randomUUID(),
        new Recorded.Secret(password),
        null);
    assertThrows(IllegalStateException.class, link::abort);
    assertArrayEquals(new char[password.length], password);
  }
}
