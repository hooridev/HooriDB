package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ProfileStore;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.secret.SecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionsTest {

  static final String PASSWORD = "hunter2";

  @TempDir
  Path dir;

  final Secrets secrets = new Secrets();

  /** A keyring in memory, which can be told to fail, or to hold its calls until a gate opens. */
  static final class Secrets implements SecretStore {
    final Map<UUID, String> stored = new ConcurrentHashMap<>();
    volatile boolean fails;
    volatile CountDownLatch gate;

    @Override
    public void store(UUID key, char[] secret) throws IOException {
      check();
      stored.put(key, new String(secret));
    }

    @Override
    public Optional<char[]> read(UUID key) throws IOException {
      check();
      return Optional.ofNullable(stored.get(key)).map(String::toCharArray);
    }

    @Override
    public void delete(UUID key) throws IOException {
      check();
      stored.remove(key);
    }

    private void check() throws IOException {
      if (gate != null) {
        try {
          gate.await();
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }
      if (fails) {
        throw new IOException("The keyring is locked");
      }
    }
  }

  App<State> app(Keyring keyring) {
    return HooriDb.app(dir.resolve("config"), () -> keyring);
  }

  App<State> app() {
    return app(new Keyring.Available(secrets));
  }

  String run(App<State> app, String script) throws IOException {
    // The connections load, then the keyring is found.
    more(app, "size 1200 760\nframe\nawait\nframe\nawait\nframe\nadvance 1\nframe\n" + script);
    Path end = dir.resolve("out").resolve("end.tree.txt");
    return Files.exists(end) ? Files.readString(end) : null;
  }

  /** Goes on with an app that a script has run before. */
  void more(App<State> app, String script) throws IOException {
    Script.run(script.replace("snap end", "advance 1\nframe\nsnap end"), app, dir.resolve("out"));
  }

  String tree(String snap) throws IOException {
    return Files.readString(dir.resolve("out").resolve(snap + ".tree.txt"));
  }

  /** The first line of the tree that contains the text. */
  static String line(String tree, String text) {
    return tree.lines()
        .filter(l -> l.contains(text))
        .findFirst()
        .orElseThrow(() -> new AssertionError(text + " in\n" + tree));
  }

  void store(ConnectionProfile... profiles) throws IOException {
    new ProfileStore(dir.resolve("config")).save(List.of(profiles));
  }

  List<ConnectionProfile> stored() throws IOException {
    return new ProfileStore(dir.resolve("config")).load();
  }

  static ConnectionProfile shop() {
    return ConnectionProfile.of(Provider.POSTGRESQL)
        .withName("shop-prod")
        .withHost("db.example.com")
        .withDatabase("shop")
        .withUser("shop_app")
        .withTls(Tls.VERIFIED);
  }

  static final String NEW_SHOP = """
      press role=button label="New connection"
      frame
      advance 1
      frame
      press role=textbox label="Name"
      type "shop-prod"
      press role=textbox label="Host"
      type "db.example.com"
      press role=textbox label="Database"
      type "shop"
      press role=textbox label="User"
      type "shop_app"
      press role=textbox label="Password"
      type "hunter2"
      frame
      """;

  @Test
  void aNewConnectionIsListedAndSaved() throws IOException {
    App<State> app = app();
    String tree = run(app, NEW_SHOP + """
        snap typed
        key Enter
        frame
        snap saving
        await
        frame
        await
        frame
        snap end
        """);
    String typed = tree("typed");
    assertTrue(line(typed, "label=\"Save\"").endsWith("56x24"), "Save can go: " + typed);
    assertTrue(
        line(typed, "role=combobox").contains("label=\"Encrypted\""), "TLS followed the host");
    assertTrue(line(tree("saving"), "label=\"Save\"").contains("busy"), "the keyring answers");
    assertFalse(tree.contains("role=dialog"), tree);
    assertTrue(line(tree, "role=treeitem").contains("label=\"shop-prod\""), tree);
    var profiles = stored();
    assertEquals(1, profiles.size());
    var saved = profiles.getFirst();
    assertEquals("db.example.com", saved.host());
    assertEquals(5432, saved.port());
    assertEquals(Tls.ENCRYPTED, saved.tls());
    assertEquals(PASSWORD, secrets.stored.get(saved.id()));
    String file = Files.readString(dir.resolve("config").resolve(ProfileStore.FILE));
    assertFalse(file.contains(PASSWORD), file);
  }

  @Test
  void thePasswordIsInNoTree() throws IOException {
    run(app(), NEW_SHOP + """
        snap end
        """);
    String tree = tree("end");
    assertTrue(line(tree, "label=\"Password\"").contains("\"" + "•".repeat(7) + "\""), tree);
    assertFalse(tree.contains(PASSWORD), tree);
  }

  @Test
  void saveWaitsForAHostAndAPortThatCanConnect() throws IOException {
    run(app(), """
        press role=button label="New connection"
        frame
        advance 1
        frame
        snap empty
        key Enter
        frame
        snap tried
        press role=textbox label="Host"
        type "https://db.example.com"
        frame
        snap url
        key A control
        type "db.example.com"
        frame
        press role=textbox label="Port"
        key A control
        type "70000"
        frame
        snap port
        key Backspace
        key Backspace
        key Backspace
        key Backspace
        key Backspace
        frame
        snap end
        """);
    String empty = tree("empty");
    assertTrue(line(empty, "label=\"Save\"").endsWith("disabled"), empty);
    assertFalse(line(empty, "#host").contains("invalid"), "required, not an error yet: " + empty);
    assertTrue(empty.contains("text \"*\""), empty);
    String tried = tree("tried");
    assertTrue(line(tried, "#host").contains("invalid"), tried);
    assertTrue(tried.contains("\"Enter the server's name or address.\""), tried);
    String url = tree("url");
    assertTrue(line(url, "#host").contains("invalid"), url);
    assertTrue(
        url.contains("\"Enter one host name or IP address, without port or protocol.\""), url);
    assertTrue(line(tree("url"), "label=\"Save\"").endsWith("disabled"));
    String port = tree("port");
    assertFalse(line(port, "#host").contains("invalid"), port);
    assertTrue(line(port, "#port").contains("invalid"), port);
    assertTrue(port.contains("\"Enter a port from 1 to 65535.\""), port);
    assertTrue(line(port, "label=\"Save\"").endsWith("disabled"), port);
    String end = tree("end");
    assertTrue(line(end, "#port").contains("invalid error=\"A value is required\""), end);
    assertTrue(line(end, "label=\"Save\"").endsWith("disabled"), end);
  }

  @Test
  void aHostTypedAndDeletedIsAnErrorAtOnce() throws IOException {
    run(app(), """
        press role=button label="New connection"
        frame
        advance 1
        frame
        press role=textbox label="Host"
        type "d"
        key Backspace
        frame
        snap end
        """);
    String end = tree("end");
    assertTrue(line(end, "#host").contains("invalid"), end);
    assertTrue(end.contains("\"Enter the server's name or address.\""), end);
  }

  @Test
  void thePortFollowsTheProviderAndTheTlsTheHostUntilSetByHand() throws IOException {
    run(app(), """
        press role=button label="New connection"
        frame
        advance 1
        frame
        press role=radio label="MySQL"
        frame
        snap mysql
        press role=textbox label="Port"
        key A control
        type "3400"
        press role=radio label="MariaDB"
        frame
        snap mariadb
        press role=textbox label="Host"
        type "localhost"
        frame
        snap local
        press role=combobox label="Off"
        frame
        advance 1
        frame
        press role=menuitem label="Encrypted, server verified"
        frame
        press role=textbox label="Host"
        key A control
        type "db.example.com"
        frame
        snap end
        """);
    assertTrue(line(tree("mysql"), "#port").contains("\"3306\""));
    assertTrue(line(tree("mariadb"), "#port").contains("\"3400\""), "set by hand");
    assertTrue(line(tree("local"), "role=combobox").contains("label=\"Off\""));
    assertTrue(line(tree("end"), "role=combobox").contains("label=\"Encrypted, server verified\""));
  }

  @Test
  void aProfileOfAnEarlierRunIsEditedAndKeepsItsPassword() throws IOException {
    var shop = shop();
    store(shop);
    secrets.stored.put(shop.id(), PASSWORD);
    run(app(), """
        snap start
        press role=treeitem label="shop-prod" right
        frame
        advance 1
        frame
        press role=menuitem label="Edit…"
        frame
        advance 1
        frame
        await
        frame
        snap edit
        press role=textbox label="Name"
        key A control
        type "shop"
        key Enter
        frame
        await
        frame
        snap end
        """);
    assertTrue(line(tree("start"), "role=treeitem").contains("label=\"shop-prod\""));
    String edit = tree("edit");
    assertTrue(line(edit, "role=dialog").contains("label=\"Edit connection\""), edit);
    assertTrue(line(edit, "#host").contains("\"db.example.com\""), edit);
    assertTrue(line(edit, "role=combobox").contains("label=\"Encrypted, server verified\""));
    assertTrue(edit.contains("\"Leave it empty to keep the current password.\""), edit);
    assertTrue(line(edit, "label=\"Save password in the system keyring\"").endsWith(" checked"));
    assertTrue(line(edit, "label=\"Save\"").endsWith("disabled"), "nothing changed yet");
    String end = tree("end");
    assertFalse(end.contains("role=dialog"), end);
    assertTrue(line(end, "role=treeitem").contains("label=\"shop\""), end);
    assertEquals(List.of(shop.withName("shop")), stored());
    assertEquals(PASSWORD, secrets.stored.get(shop.id()), "an empty field keeps the password");
  }

  static final String TWO_DUPLICATES = """
      press role=treeitem label="shop-prod" right
      frame
      advance 1
      frame
      press role=menuitem label="Duplicate"
      frame
      press role=treeitem label="shop-prod" right
      frame
      advance 1
      frame
      press role=menuitem label="Duplicate"
      frame
      """;

  /** The second change comes while the first one's save still runs. */
  @Test
  void twoQuickChangesEndWithTheSecondOnDisk() throws IOException {
    store(shop());
    run(app(), TWO_DUPLICATES + """
        await
        frame
        await
        frame
        snap end
        """);
    assertEquals(3, stored().size());
  }

  @Test
  void aDuplicateHasANewIdAndDeletingTakesThePasswordAlong() throws IOException {
    var shop = shop();
    store(shop);
    secrets.stored.put(shop.id(), PASSWORD);
    run(app(), """
        press role=treeitem label="shop-prod" right
        frame
        advance 1
        frame
        press role=menuitem label="Duplicate"
        frame
        await
        frame
        snap duplicated
        press role=treeitem label="shop-prod" right
        frame
        advance 1
        frame
        press role=menuitem label="Delete…"
        frame
        advance 1
        frame
        snap asked
        press role=button label="Delete"
        frame
        await
        frame
        await
        frame
        snap end
        """);
    String duplicated = tree("duplicated");
    assertTrue(line(duplicated, "#row-1").contains("label=\"shop-prod (copy)\""), duplicated);
    assertTrue(line(duplicated, "#row-1").contains("selected"), duplicated);
    String asked = tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"Delete connection\""), asked);
    assertTrue(asked.contains("\"Delete the connection shop-prod?\""), asked);
    String end = tree("end");
    assertFalse(end.contains("role=dialog"), end);
    assertTrue(line(end, "role=treeitem").contains("label=\"shop-prod (copy)\""), end);
    assertFalse(secrets.stored.containsKey(shop.id()), "its password went with it");
    var left = stored();
    assertEquals(1, left.size());
    var copy = left.getFirst();
    assertFalse(copy.id().equals(shop.id()));
    assertEquals(
        shop.withName("shop-prod (copy)"),
        new ConnectionProfile(
            shop.id(),
            copy.name(),
            copy.provider(),
            copy.host(),
            copy.port(),
            copy.database(),
            copy.user(),
            copy.tls()));
  }

  @Test
  void withoutAKeyringThePasswordIsKeptForTheSession() throws IOException {
    App<State> app = app(new Keyring.Unavailable(Keyring.Reason.NO_SERVICE, "no answer"));
    run(app, NEW_SHOP + """
        snap typed
        key Enter
        frame
        await
        frame
        snap end
        """);
    String typed = tree("typed");
    assertTrue(line(typed, "label=\"Save password in the system keyring\"").contains("disabled"));
    assertTrue(
        typed.contains("\"No keyring service, such as GNOME Keyring or KWallet, is running."
            + " The password is kept until HooriDB closes.\""),
        typed);
    var saved = stored().getFirst();
    assertTrue(app.state().remembers(saved.id()));
    assertFalse(tree("end").contains(PASSWORD));
  }

  @Test
  void aFailingKeyringKeepsTheDialogAndWhatWasTyped() throws IOException {
    secrets.fails = true;
    run(app(), NEW_SHOP + """
        key Enter
        frame
        await
        frame
        snap end
        """);
    String end = tree("end");
    assertTrue(line(end, "role=note").contains("label=\"The system keyring reported a problem\""));
    assertTrue(end.contains("\"The keyring is locked\""), end);
    assertTrue(line(end, "#host").contains("\"db.example.com\""), end);
    assertTrue(line(end, "label=\"Password\"").contains("•".repeat(7)), end);
    assertTrue(stored().isEmpty());
  }

  @Test
  void changesAreDiscardedOnlyAfterAsking() throws IOException {
    App<State> app = app();
    run(app, """
        press role=button label="New connection"
        frame
        advance 1
        frame
        press role=textbox label="Host"
        type "db.example.com"
        key Escape
        frame
        advance 1
        frame
        snap asked
        press role=button label="Keep editing"
        frame
        advance 1
        frame
        snap kept
        key Escape
        frame
        advance 1
        frame
        press role=button label="Discard"
        frame
        snap end
        """);
    assertTrue(line(tree("asked"), "role=dialog label=\"Discard changes?\"").contains("dialog"));
    assertTrue(line(tree("kept"), "#host").contains("\"db.example.com\""));
    assertFalse(tree("kept").contains("Discard changes?"));
    assertFalse(tree("end").contains("role=dialog"));
    assertNull(app.state().dialog);
    assertTrue(app.state().profiles.isEmpty());
  }

  @Test
  void anUnreadableFileIsNotAnEmptyList() throws IOException {
    Files.createDirectories(dir.resolve("config"));
    Files.writeString(dir.resolve("config").resolve(ProfileStore.FILE), "{");
    String tree = run(app(), "snap end\n");
    assertFalse(tree.contains("No connections yet"), tree);
    assertTrue(line(tree, "role=note").contains("label=\"Connections could not be read\""), tree);
    assertTrue(tree.contains("it is not valid JSON"), tree);
  }

  static final String DELETE_SHOP = """
      press role=treeitem label="shop-prod" right
      frame
      advance 1
      frame
      press role=menuitem label="Delete…"
      frame
      advance 1
      frame
      press role=button label="Delete"
      frame
      """;

  static final String EDIT_SHOP = """
      press role=treeitem label="shop-prod" right
      frame
      advance 1
      frame
      press role=menuitem label="Edit…"
      frame
      advance 1
      frame
      await
      frame
      """;

  static final String SETTLE = """
      await
      frame
      await
      frame
      """;

  /** A file that is there but could not be read is not replaced, until a reload has read it. */
  @Test
  void aFileThatCouldNotBeReadIsNotReplaced() throws IOException {
    Files.createDirectories(dir.resolve("config").resolve(ProfileStore.FILE)); // no file to read
    App<State> app = app();
    run(app, "snap blocked\n");
    String blocked = tree("blocked");
    assertTrue(line(blocked, "role=note").contains("\"Connections could not be read\""), blocked);
    assertTrue(line(blocked, "label=\"Add connection\"").endsWith("disabled"), blocked);
    assertFalse(blocked.contains("No connections yet"), blocked);
    Files.delete(dir.resolve("config").resolve(ProfileStore.FILE));
    store(shop());
    more(app, """
        press role=button label="Try again"
        frame
        await
        frame
        snap end
        """);
    String end = tree("end");
    assertTrue(line(end, "role=treeitem").contains("label=\"shop-prod\""), end);
    assertFalse(line(end, "label=\"Add connection\"").endsWith("disabled"), end);
    assertFalse(end.contains("role=note"), end);
  }

  /** A delete whose keyring call runs cannot be cancelled: it ends, and then the list changes. */
  @Test
  void aDeleteUnderWayCannotBeCancelled() throws IOException {
    var shop = shop();
    store(shop);
    secrets.stored.put(shop.id(), PASSWORD);
    secrets.gate = new CountDownLatch(1);
    App<State> app = app();
    run(app, DELETE_SHOP + """
        key Escape
        frame
        advance 1
        frame
        snap waiting
        """);
    String waiting = tree("waiting");
    assertTrue(line(waiting, "role=dialog").contains("\"Delete connection\""), waiting);
    assertTrue(line(waiting, "label=\"Delete\"").contains("busy"), waiting);
    assertTrue(line(waiting, "label=\"Cancel\"").endsWith("disabled"), waiting);
    secrets.gate.countDown();
    more(app, SETTLE + "snap end\n");
    assertFalse(tree("end").contains("role=dialog"));
    assertFalse(secrets.stored.containsKey(shop.id()));
    assertTrue(stored().isEmpty());
  }

  /** A password on its way into the keyring cannot be discarded: the connection is saved. */
  @Test
  void aSaveUnderWayCannotBeDiscarded() throws IOException {
    secrets.gate = new CountDownLatch(1);
    App<State> app = app();
    run(app, NEW_SHOP + """
        key Enter
        frame
        key Escape
        frame
        advance 1
        frame
        snap waiting
        """);
    String waiting = tree("waiting");
    assertFalse(waiting.contains("Discard changes?"), waiting);
    assertTrue(line(waiting, "label=\"Save\"").contains("busy"), waiting);
    assertTrue(line(waiting, "label=\"Cancel\"").endsWith("disabled"), waiting);
    secrets.gate.countDown();
    more(app, SETTLE + "snap end\n");
    var saved = stored();
    assertEquals(1, saved.size());
    assertEquals(PASSWORD, secrets.stored.get(saved.getFirst().id()));
  }

  /** The window closes once a save under way has ended and the list is written. */
  @Test
  void closingWaitsForWhatWasBegun() throws IOException {
    secrets.gate = new CountDownLatch(1);
    App<State> app = app();
    run(app, NEW_SHOP + """
        key Enter
        frame
        close
        frame
        snap closing
        """);
    assertFalse(app.windows().all().isEmpty(), "still open while the keyring works");
    assertTrue(tree("closing").contains("\"HooriDB closes once the connections are saved.\""));
    secrets.gate.countDown();
    more(app, SETTLE);
    assertTrue(app.windows().all().isEmpty());
    var saved = stored();
    assertEquals(1, saved.size());
    assertEquals(PASSWORD, secrets.stored.get(saved.getFirst().id()));
  }

  /** Changing where a password is kept moves the one there is, the field left empty. */
  @Test
  void thePasswordMovesWhereTheUserWantsIt() throws IOException {
    var shop = shop();
    store(shop);
    secrets.stored.put(shop.id(), PASSWORD);
    App<State> app = app();
    run(app, EDIT_SHOP + """
        press role=checkbox label="Save password in the system keyring"
        frame
        press role=button label="Save"
        frame
        """ + SETTLE);
    assertFalse(secrets.stored.containsKey(shop.id()), "out of the keyring");
    assertTrue(app.state().remembers(shop.id()), "into the session");
    more(app, "advance 1\nframe\n" + EDIT_SHOP + """
        snap kept
        press role=checkbox label="Save password in the system keyring"
        frame
        press role=button label="Save"
        frame
        """ + SETTLE);
    assertTrue(tree("kept")
        .contains("\"Leave it empty to keep the current password. The password"
            + " is kept until HooriDB closes.\""));
    assertEquals(PASSWORD, secrets.stored.get(shop.id()), "back into the keyring");
    assertFalse(app.state().remembers(shop.id()));
  }

  /** The form cannot change while its save runs, so nothing typed then is lost. */
  @Test
  void theFormIsReadOnlyWhileItSaves() throws IOException {
    secrets.gate = new CountDownLatch(1);
    App<State> app = app();
    run(app, NEW_SHOP + """
        key Enter
        frame
        press role=textbox label="Host"
        key A control
        type "other.example.com"
        frame
        snap waiting
        """);
    String waiting = tree("waiting");
    assertTrue(line(waiting, "#host").contains("\"db.example.com\""), waiting);
    assertTrue(line(waiting, "#host").contains("disabled"), waiting);
    secrets.gate.countDown();
    more(app, SETTLE);
    assertEquals("db.example.com", stored().getFirst().host());
  }

  /** A failed save stays unsaved: it is shown with a retry, and the window does not just close. */
  @Test
  void aFailedSaveIsTriedAgain() throws IOException {
    App<State> app = app();
    run(app, "snap start\n");
    Path file = dir.resolve("config").resolve(ProfileStore.FILE);
    Files.createDirectories(file); // the store cannot write here
    more(app, NEW_SHOP + "key Enter\nframe\n" + SETTLE + """
        snap failed
        close
        frame
        await
        frame
        snap stayed
        """);
    String failed = tree("failed");
    assertTrue(line(failed, "role=note").contains("\"Connections could not be saved\""), failed);
    assertTrue(failed.contains("label=\"Try again\""), failed);
    assertFalse(app.windows().all().isEmpty(), "the window stays while the list is not written");
    assertTrue(tree("stayed").contains("Close it again to quit without them."));
    Files.delete(file);
    more(app, """
        press role=button label="Try again"
        frame
        await
        frame
        snap end
        """);
    assertFalse(tree("end").contains("role=note"));
    assertEquals(1, stored().size());
    more(app, "close\nframe\n");
    assertTrue(app.windows().all().isEmpty());
  }

  /** A delete before the keyring is found waits for it, and then takes the password along. */
  @Test
  void aDeleteWaitsForTheKeyring() throws IOException {
    var shop = shop();
    store(shop);
    secrets.stored.put(shop.id(), PASSWORD);
    var found = new CountDownLatch(1);
    App<State> app = HooriDb.app(dir.resolve("config"), () -> {
      found.await();
      return new Keyring.Available(secrets);
    });
    // The connections load; the keyring is not found yet.
    more(
        app,
        "size 1200 760\nframe\nawait\nframe\nadvance 1\nframe\n" + DELETE_SHOP + "snap waiting\n");
    String waiting = tree("waiting");
    assertTrue(line(waiting, "label=\"Delete\"").contains("busy"), waiting);
    assertTrue(line(waiting, "role=treeitem").contains("\"shop-prod\""), waiting);
    found.countDown();
    more(app, SETTLE + SETTLE); // the keyring is found, the password goes, the list is saved
    assertFalse(secrets.stored.containsKey(shop.id()));
    assertTrue(stored().isEmpty());
  }

  /** Without a keyring, a delete goes ahead and says what may be left. */
  @Test
  void aDeleteWithoutAKeyringSaysSo() throws IOException {
    store(shop());
    App<State> app = app(new Keyring.Unavailable(Keyring.Reason.NO_SERVICE, "no answer"));
    run(app, DELETE_SHOP + SETTLE + "snap end\n");
    assertTrue(tree("end").contains("a password saved for shop-prod could not be removed"));
    assertTrue(stored().isEmpty());
  }

  /** Text typed into the port counts as set by hand and as a change, also when it is no port. */
  @Test
  void aPortThatIsNoPortYetCountsAsTyped() throws IOException {
    store(shop());
    App<State> app = app();
    run(app, """
        press role=button label="Add connection"
        frame
        advance 1
        frame
        press role=textbox label="Port"
        key A control
        key Backspace
        press role=radio label="MySQL"
        frame
        snap emptied
        key Escape
        frame
        advance 1
        frame
        press role=button label="Discard"
        frame
        advance 1
        frame
        """ + EDIT_SHOP + """
        press role=textbox label="Port"
        key A control
        key Backspace
        key Escape
        frame
        advance 1
        frame
        snap end
        """);
    assertTrue(line(tree("emptied"), "#port").contains("\"\""), tree("emptied"));
    assertTrue(tree("end").contains("role=dialog label=\"Discard changes?\""), tree("end"));
  }

  /** A script names the folder it starts from, and has no keyring. */
  @Test
  void aScriptRunUsesTheFolderItIsGiven() throws Exception {
    Path script = dir.resolve("new.txt");
    Files.writeString(
        script,
        "size 1200 760\nframe\nawait\nframe\nadvance 1\nframe\n" + NEW_SHOP + "key Enter\nframe\n"
            + SETTLE);
    HooriDb.main(new String[] {
      "--script",
      script.toString(),
      "--out",
      dir.resolve("out").toString(),
      "--config",
      dir.resolve("config").toString()
    });
    assertEquals("shop-prod", stored().getFirst().name());
  }
}
