package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.connection.WorkspaceStore;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.secret.SecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    final Set<UUID> refused = ConcurrentHashMap.newKeySet(); // their deletes fail
    volatile CountDownLatch gate;
    volatile CountDownLatch late; // holds the next call alone, which ignores its interrupt
    final List<char[]> given =
        new java.util.concurrent.CopyOnWriteArrayList<>(); // the passwords read
    final List<char[]> received =
        new java.util.concurrent.CopyOnWriteArrayList<>(); // the arrays it was given to store

    @Override
    public void store(UUID key, char[] secret) throws IOException {
      received.add(secret);
      check();
      stored.put(key, new String(secret));
    }

    @Override
    public Optional<char[]> read(UUID key) throws IOException {
      check();
      var password = Optional.ofNullable(stored.get(key)).map(String::toCharArray);
      password.ifPresent(given::add);
      return password;
    }

    @Override
    public void delete(UUID key) throws IOException {
      check();
      if (refused.contains(key)) {
        throw new IOException("The keyring refused to delete it");
      }
      stored.remove(key);
    }

    private void check() throws IOException {
      var hold = late;
      if (hold != null) {
        // As a native call that takes no cancellation: it waits on, and then does what it was
        // asked, however long ago its caller gave up.
        late = null;
        boolean interrupted = false;
        while (true) {
          try {
            hold.await();
            break;
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
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
    new WorkspaceStore(dir.resolve("config")).save(new Workspace(List.of(profiles)));
  }

  List<ConnectionProfile> stored() throws IOException {
    return new WorkspaceStore(dir.resolve("config")).load().connections();
  }

  static ConnectionProfile shop() {
    return ConnectionProfile.of(Provider.POSTGRESQL)
        .withName("shop-prod")
        .withHost("db.example.com")
        .withDatabase("shop")
        .withUser("shop_app")
        .withTls(Tls.VERIFIED);
  }

  /** The sidebar's "+" and its New connection…, which opens the dialog. */
  static final String ADD_CONNECTION = """
      press role=button label="Add"
      frame
      advance 1
      frame
      press role=menuitem label="New connection…"
      """;

  /** The script with its press of the empty state's New connection made through the "+". */
  static String viaAdd(String script) {
    return script.replace("press role=button label=\"New connection\"\n", ADD_CONNECTION);
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

  /** Chooses the kind of server from the connection dialog's Type. */
  static String provider(String name) {
    return """
        press role=combobox label="Type"
        frame
        advance 1
        frame
        press role=option label="%s"
        frame
        advance 1
        frame
        """.formatted(name);
  }

  @Test
  void everyProviderHasALogo() {
    for (var provider : Provider.values()) {
      assertNotNull(Logos.of(provider), provider.name());
    }
  }

  /** Escape and Enter go to the open list of types, not to the dialog around it. */
  @Test
  void theListOfTypesTakesEscapeAndEnter() throws IOException {
    App<State> app = app();
    run(app, NEW_SHOP + """
        press role=combobox label="Type"
        frame
        advance 1
        frame
        snap open
        key Escape
        frame
        advance 1
        frame
        snap escaped
        press role=combobox label="Type"
        frame
        advance 1
        frame
        key ArrowDown
        key Enter
        frame
        advance 1
        frame
        """ + SETTLE + """
        snap end
        """);
    String open = tree("open");
    assertTrue(line(open, "role=combobox label=\"Type\"").endsWith(" expanded"), open);
    assertTrue(open.contains("role=option label=\"MariaDB\""), open);
    for (var tree : List.of(tree("escaped"), tree("end"))) {
      assertTrue(line(tree, "role=combobox label=\"Type\"").contains("expanded=false"), tree);
      assertTrue(line(tree, "role=dialog").contains("label=\"New connection\""), tree);
      assertFalse(tree.contains("Discard changes?"), tree);
    }
    assertTrue(line(tree("escaped"), "#port").contains("\"5432\""), tree("escaped"));
    assertTrue(line(tree("end"), "#port").contains("\"3306\""), tree("end"));
    assertEquals(Provider.MARIADB, app.state().dialog.editing.value().provider());
    assertEquals(List.of(), stored());
    assertTrue(secrets.stored.isEmpty(), "nothing saved");
  }

  /** Every connection may be read-only, and switching its type keeps that. */
  @Test
  void aConnectionCanBeReadOnly() throws IOException {
    App<State> app = app();
    run(app, NEW_SHOP + provider("MongoDB") + """
        snap mongo
        press role=checkbox label="Read-only"
        frame
        snap checked
        """ + provider("PostgreSQL") + """
        snap sql
        press role=button label="Save"
        frame
        """ + SETTLE + """
        advance 1
        frame
        snap saved
        """);
    String mongo = tree("mongo");
    assertTrue(line(mongo, "#port").contains("\"27017\""), mongo);
    assertTrue(line(mongo, "role=checkbox label=\"Read-only\"").contains("checked=false"), mongo);
    String checked = tree("checked");
    assertFalse(
        line(checked, "role=checkbox label=\"Read-only\"").contains("checked=false"), checked);
    String sql = tree("sql");
    assertFalse(line(sql, "role=checkbox label=\"Read-only\"").contains("checked=false"), sql);
    assertTrue(sql.contains("The server keeps the session read-only"), sql);
    assertFalse(tree("saved").contains("role=dialog"), tree("saved"));
    var saved = stored().getFirst();
    assertEquals(Provider.POSTGRESQL, saved.provider());
    assertTrue(saved.readOnly());
  }

  /**
   * A connection to MongoDB may name an SRV record, as mongodb+srv:// does: it has no port then,
   * and TLS is on as the driver has it; a server of SQL does not offer it.
   */
  @Test
  void aMongoDbConnectionCanUseAnSrvRecord() throws IOException {
    App<State> app = app();
    run(
        app,
        NEW_SHOP + provider("MongoDB") + """
        press role=textbox label="Host"
        key A control
        type "localhost"
        frame
        snap local
        press role=textbox label="Port"
        key A control
        key Backspace
        frame
        press role=checkbox label="Servers from DNS"
        frame
        advance 1
        frame
        snap srv
        """ + provider("PostgreSQL") + """
        snap sql
        """ + provider("MongoDB")
            + """
        snap again
        press role=checkbox label="Servers from DNS"
        frame
        advance 1
        frame
        press role=button label="Save"
        frame
        """ + SETTLE + """
        advance 1
        frame
        snap saved
        """);
    String local = tree("local");
    assertTrue(line(local, "#port").contains("\"27017\""), local);
    assertTrue(line(local, "#tls").contains("label=\"Off\""), local);
    String srv = tree("srv");
    assertFalse(srv.contains("#port"), srv);
    assertFalse(line(srv, "role=checkbox label=\"Servers from DNS\"").contains("checked=false"));
    assertTrue(line(srv, "#tls").contains("label=\"Encrypted, any certificate\""), srv);
    // The port cleared before is hidden now and keeps nothing back.
    assertFalse(line(srv, "label=\"Save\"").contains("disabled"), srv);
    assertFalse(tree("sql").contains("Servers from DNS"), tree("sql"));
    assertTrue(tree("sql").contains("#port"), tree("sql"));
    String again = tree("again");
    assertTrue(line(again, "role=checkbox label=\"Servers from DNS\"").contains("checked=false"));
    assertTrue(line(again, "#tls").contains("label=\"Off\""), again);
    assertFalse(tree("saved").contains("role=dialog"), tree("saved"));
    var saved = stored().getFirst();
    assertTrue(saved.srv());
    assertEquals(Tls.ENCRYPTED, saved.tls());
  }

  /**
   * A connection to MongoDB may name the database its user is defined in, admin where it names
   * none; a server of SQL does not offer it.
   */
  @Test
  void aMongoDbConnectionCanNameItsAuthDatabase() throws IOException {
    App<State> app = app();
    run(
        app,
        NEW_SHOP + provider("MongoDB") + """
        snap mongo
        press role=textbox label="Auth database"
        type "a.b"
        frame
        snap invalid
        """ + provider("PostgreSQL") + """
        snap sql
        """ + provider("MongoDB")
            + """
        snap again
        press role=textbox label="Auth database"
        type "shop"
        frame
        press role=button label="Save"
        frame
        """ + SETTLE + """
        advance 1
        frame
        snap saved
        """);
    String mongo = tree("mongo");
    assertTrue(line(mongo, "#auth-database").contains("placeholder=\"admin\""), mongo);
    String invalid = tree("invalid");
    assertTrue(invalid.contains("Use a name of fewer than 64 bytes"), invalid);
    assertFalse(tree("sql").contains("Auth database"), tree("sql"));
    String again = tree("again");
    assertFalse(line(again, "#auth-database").contains("a.b"), again);
    assertFalse(tree("saved").contains("role=dialog"), tree("saved"));
    assertEquals("shop", stored().getFirst().authDatabase());
  }

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
    // It fits, also with the checkbox at its end: no scrollbar.
    assertFalse(line(typed, "#dialog-content").contains("thumb-y"), typed);
    assertFalse(typed.contains("role=heading \"Server\""), "no headings: " + typed);
    assertTrue(
        line(typed, "#tls").contains("label=\"Encrypted, any certificate\""),
        "TLS followed the host");
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
    assertEquals(1, secrets.received.size());
    assertEquals("\0".repeat(PASSWORD.length()), new String(secrets.received.getFirst()), "wiped");
    assertEquals("", app.state().dialog == null ? "" : app.state().dialog.password);
    String file = Files.readString(dir.resolve("config").resolve(WorkspaceStore.FILE));
    assertFalse(file.contains(PASSWORD), file);
  }

  /** Neither hidden nor shown: a tree has the mask, and says whether the password shows. */
  @Test
  void thePasswordIsInNoTree() throws IOException {
    run(app(), NEW_SHOP + """
        snap hidden
        press role=button label="Show password"
        frame
        snap shown
        press role=button label="Hide password"
        frame
        snap end
        """);
    for (var snap : List.of("hidden", "shown", "end")) {
      String tree = tree(snap);
      var field = line(tree, "label=\"Password\"");
      assertTrue(field.contains("\"" + "•".repeat(7) + "\""), tree);
      assertEquals(snap.equals("shown"), field.contains("password revealed"), field);
      assertFalse(tree.contains(PASSWORD), tree);
    }
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

  /** TLS off is warned of only where the host would have it on: on a network, not this machine. */
  @Test
  void tlsOffIsWarnedOfOnARemoteHostOnly() throws IOException {
    run(app(), """
        press role=button label="New connection"
        frame
        advance 1
        frame
        press role=combobox label="Encrypted, any certificate"
        frame
        advance 1
        frame
        press role=menuitem label="Off"
        frame
        advance 1
        frame
        snap empty
        press role=textbox label="Host"
        type "db.example.com"
        frame
        snap remote
        press role=textbox label="Host"
        key A control
        type "localhost"
        frame
        snap local
        """);
    var warning = "warning=\"Password and data go over the network unencrypted.\"";
    assertFalse(line(tree("empty"), "#tls").contains("warning="), "no host yet");
    assertTrue(line(tree("remote"), "#tls").contains(warning), tree("remote"));
    assertTrue(line(tree("local"), "#tls").contains("label=\"Off\""), "set by hand");
    assertFalse(line(tree("local"), "#tls").contains("warning="), tree("local"));
  }

  @Test
  void thePortFollowsTheProviderAndTheTlsTheHostUntilSetByHand() throws IOException {
    run(app(), """
        press role=button label="New connection"
        frame
        advance 1
        frame
        """ + provider("MySQL") + """
        snap mysql
        press role=textbox label="Port"
        key A control
        type "3400"
        """ + provider("MariaDB") + """
        snap mariadb
        press role=textbox label="Host"
        type "localhost"
        frame
        snap local
        press role=combobox label="Off"
        frame
        advance 1
        frame
        press role=menuitem label="Encrypted and verified"
        frame
        press role=textbox label="Host"
        key A control
        type "db.example.com"
        frame
        snap end
        """);
    assertTrue(line(tree("mysql"), "#port").contains("\"3306\""));
    assertTrue(line(tree("mariadb"), "#port").contains("\"3400\""), "set by hand");
    assertTrue(line(tree("local"), "#tls").contains("label=\"Off\""));
    assertTrue(line(tree("end"), "#tls").contains("label=\"Encrypted and verified\""));
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
    assertTrue(line(edit, "#tls").contains("label=\"Encrypted and verified\""));
    // The placeholder says that an empty field keeps it.
    assertTrue(
        line(edit, "#password").contains("placeholder=\"Unchanged, saved in the keyring\""), edit);
    assertFalse(edit.contains("Leave it empty"), edit);
    assertTrue(line(edit, "label=\"Save in system keyring\"").endsWith(" checked"));
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

  /**
   * The tree shows the workspace's folders with their connections, and every change of a
   * connection keeps the folders in the file, an emptied one too.
   */
  @Test
  void foldersInTheFileOutlastEveryChange() throws IOException {
    var shop = shop();
    var crm = shop().withName("crm");
    var analytics = shop().withName("analytics");
    var inner = new Workspace.Folder(UUID.randomUUID(), "inner", true, List.of(crm));
    var reports = new Workspace.Folder(UUID.randomUUID(), "reports", true, List.of(analytics));
    var outer = new Workspace.Folder(UUID.randomUUID(), "outer", true, List.of(inner, reports));
    var empty = new Workspace.Folder(UUID.randomUUID(), "empty", false, List.of());
    var config = new WorkspaceStore(dir.resolve("config"));
    config.save(new Workspace(List.of(shop, outer, empty)));
    App<State> app = app();
    run(app, """
        snap start
        press role=treeitem label="crm" right
        frame
        advance 1
        frame
        press role=menuitem label="Duplicate"
        frame
        """ + SETTLE);
    assertEquals(
        List.of("shop-prod", "outer", "inner", "crm", "reports", "analytics", "empty"),
        roots(tree("start")));
    app.state().put(crm.withHost("crm.example.com"));
    app.state().put(shop.withColor(Color.GREEN));
    more(app, SETTLE + DELETE_SHOP.replace("shop-prod", "analytics") + SETTLE + "snap end\n");
    assertEquals(
        List.of("shop-prod", "outer", "inner", "crm", "crm (copy)", "reports", "empty"),
        roots(tree("end")));
    var saved = config.load();
    var copy = saved.connections().get(2);
    assertEquals("crm (copy)", copy.name());
    assertEquals(crm.host(), copy.host(), "made before the edit");
    assertFalse(copy.id().equals(crm.id()));
    assertEquals(
        new Workspace(List.of(
            shop.withColor(Color.GREEN),
            new Workspace.Folder(
                outer.id(),
                "outer",
                true,
                List.of(
                    new Workspace.Folder(
                        inner.id(), "inner", true, List.of(crm.withHost("crm.example.com"), copy)),
                    new Workspace.Folder(reports.id(), "reports", true, List.of()))),
            empty)),
        saved);
  }

  /** The labels of the tree's items, from the top down. */
  static List<String> roots(String tree) {
    return tree.lines()
        .filter(l -> l.contains("role=treeitem"))
        .map(l -> l.replaceAll(".*role=treeitem label=\"([^\"]*)\".*", "$1"))
        .toList();
  }

  @Test
  void aDuplicateHasANewIdAndDeletingTakesThePasswordAlong() throws IOException {
    var shop = shop().withColor(Color.RED);
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
            copy.tls(),
            copy.color(),
            copy.driverVersion(),
            copy.readOnly(),
            copy.srv(),
            copy.authDatabase()));
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
    assertTrue(line(typed, "label=\"Save in system keyring\"").contains("disabled"));
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
    Files.writeString(dir.resolve("config").resolve(WorkspaceStore.FILE), "{");
    String tree = run(app(), "snap end\n");
    assertFalse(tree.contains("No connections yet"), tree);
    assertTrue(line(tree, "role=note").contains("label=\"Connections could not be read\""), tree);
    assertTrue(tree.contains("it is not valid JSON"), tree);
  }

  /**
   * The list of an older HooriDB is taken only where there is no workspace; one that cannot be
   * read stays as it is, as no save writes it, and the user is told so.
   */
  @Test
  void anOlderListThatCannotBeReadStaysAsItIs() throws IOException {
    var older = dir.resolve("config").resolve(WorkspaceStore.CONNECTIONS);
    Files.createDirectories(older.getParent());
    Files.writeString(older, "{");
    App<State> app = app();
    run(app, "snap blocked\n");
    String blocked = tree("blocked");
    assertTrue(line(blocked, "role=note").contains("\"Connections could not be read\""), blocked);
    assertFalse(line(blocked, "label=\"Add\"").endsWith("disabled"), blocked);
    more(app, viaAdd(NEW_SHOP) + "key Enter\nframe\n" + SETTLE);
    assertEquals(
        "The connections that could not be read were kept in connections.json",
        app.state().message);
    assertEquals("{", Files.readString(older));
    assertEquals(
        List.of("shop-prod"), stored().stream().map(ConnectionProfile::name).toList());
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
    Files.createDirectories(dir.resolve("config").resolve(WorkspaceStore.FILE)); // no file to read
    App<State> app = app();
    run(app, "snap blocked\n");
    String blocked = tree("blocked");
    assertTrue(line(blocked, "role=note").contains("\"Connections could not be read\""), blocked);
    assertTrue(line(blocked, "label=\"Add\"").endsWith("disabled"), blocked);
    assertFalse(blocked.contains("No connections yet"), blocked);
    Files.delete(dir.resolve("config").resolve(WorkspaceStore.FILE));
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
    assertFalse(line(end, "label=\"Add\"").endsWith("disabled"), end);
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
        press role=checkbox label="Save in system keyring"
        frame
        press role=button label="Save"
        frame
        """ + SETTLE);
    assertFalse(secrets.stored.containsKey(shop.id()), "out of the keyring");
    assertTrue(app.state().remembers(shop.id()), "into the session");
    more(app, "advance 1\nframe\n" + EDIT_SHOP + """
        snap kept
        press role=checkbox label="Save in system keyring"
        frame
        press role=button label="Save"
        frame
        """ + SETTLE);
    assertTrue(tree("kept").contains("\"The password is kept until HooriDB closes.\""));
    assertTrue(line(tree("kept"), "#password")
        .contains("placeholder=\"Unchanged, kept until HooriDB closes\""));
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
    assertTrue(
        line(waiting, "role=checkbox label=\"Save in system keyring\"")
            .endsWith("disabled checked"),
        "keeps its tick");
    secrets.gate.countDown();
    more(app, SETTLE);
    assertEquals("db.example.com", stored().getFirst().host());
  }

  /** A failed save stays unsaved: it is shown with a retry, and the window does not just close. */
  @Test
  void aFailedSaveIsTriedAgain() throws IOException {
    App<State> app = app();
    run(app, "snap start\n");
    Path file = dir.resolve("config").resolve(WorkspaceStore.FILE);
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

  /** Host and port in one row, both wrong: each is marked, and each error reads whole. */
  @Test
  void aWrongHostAndPortAreEachMarkedAndSaid() throws IOException {
    run(app(), """
        press role=button label="New connection"
        frame
        advance 1
        frame
        press role=textbox label="Host"
        type "d"
        key Backspace
        press role=textbox label="Port"
        key A control
        type "70000"
        frame
        advance 1
        frame
        snap both
        """);
    String both = tree("both");
    var host = line(both, "#host");
    var port = line(both, "#port");
    assertTrue(host.contains("invalid") && host.contains("error=\""), host);
    assertTrue(port.contains("invalid") && port.contains("error=\""), port);
    var hostError = host.replaceAll(".*error=\"([^\"]*)\".*", "$1");
    var portError = port.replaceAll(".*error=\"([^\"]*)\".*", "$1");
    float hostText = TestConnectionTest.top(both, "text \"" + hostError);
    float portText = TestConnectionTest.top(both, "text \"" + portError);
    // Under the row, one below the other, each on a line of the row's width.
    assertTrue(TestConnectionTest.top(both, "#port") < hostText && hostText < portText, both);
    assertTrue(portText < TestConnectionTest.top(both, "#database"), both);
    // At the start of the host's field.
    var start = " " + host.replaceAll(".* (\\d+),\\d+ \\d+x\\d+.*", "$1") + ",";
    for (var error : List.of(hostError, portError)) {
      assertTrue(line(both, "text \"" + error).contains(start), start + " " + both);
    }
  }

  /** Text typed into the port counts as set by hand and as a change, also when it is no port. */
  @Test
  void aPortThatIsNoPortYetCountsAsTyped() throws IOException {
    store(shop());
    App<State> app = app();
    run(app, ADD_CONNECTION + """
        frame
        advance 1
        frame
        press role=textbox label="Port"
        key A control
        key Backspace
        """ + provider("MySQL") + """
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

  /** Each kind of database has its own mark in the list, which a tree dump names. */
  @Test
  void theListTellsTheProvidersApart() throws IOException {
    store(
        shop(),
        ConnectionProfile.of(Provider.MARIADB).withName("analytics").withHost("db.example.com"),
        ConnectionProfile.of(Provider.MYSQL).withName("legacy").withHost("localhost"));
    String tree = run(app(), "snap end\n");
    assertTrue(after(tree, "label=\"shop-prod\"").contains("value=\"PostgreSQL\""), tree);
    assertTrue(after(tree, "label=\"analytics\"").contains("value=\"MariaDB\""), tree);
    assertTrue(after(tree, "label=\"legacy\"").contains("value=\"MySQL\""), tree);
  }

  /** The lines of the tree from the first that contains the text to the next row. */
  static String after(String tree, String text) {
    int at = tree.indexOf(text);
    int next = tree.indexOf("role=treeitem", at + text.length());
    return tree.substring(at, next < 0 ? tree.length() : next);
  }
}
