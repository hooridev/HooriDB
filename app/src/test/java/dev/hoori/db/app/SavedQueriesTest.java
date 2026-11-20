package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleHistoryTest.CLEAR;
import static dev.hoori.db.app.ConsoleTest.ORDERS;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.QueryStore;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Queries saved on purpose under a name, and put into a console again. */
class SavedQueriesTest {

  static final String SAVE = "press role=button label=\"Save query\"\nframe\nadvance 1\nframe\n";
  static final String SAVED =
      "press role=button label=\"Saved queries\"\nframe\nadvance 1\nframe\n";

  @TempDir
  Path dir;

  final ConsoleTest k = new ConsoleTest();
  CatalogTreeTest c;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  /** Types the name into the Save query dialog in place of the one it suggests, and saves. */
  static String named(String name) {
    return "press #query-name\nkey A control\nkey Delete\ntype \"" + name + "\"\nframe\n"
        + "press role=button label=\"Save\"\nframe\nadvance 1\nframe\n" + SETTLE;
  }

  @Test
  void aQuerySavedUnderANameIsKeptAndPutIntoTheEditorAgain() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + SAVE + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"Save query\""), asked);
    assertTrue(line(asked, "#query-name").contains(ORDERS), asked);
    c.run(app, named("All orders") + "snap saved\n");
    assertFalse(c.tree("saved").contains("role=dialog label=\"Save query\""), c.tree("saved"));
    assertEquals(1, app.state().queries.size());
    var saved = app.state().queries.getFirst();
    assertEquals("All orders", saved.name());
    assertEquals(ORDERS, saved.text());
    assertEquals(SHOP.id(), saved.connection());
    CatalogTreeTest.eventually(() -> {
      try {
        return new QueryStore(dir.resolve("config")).load().size() == 1;
      } catch (Exception e) {
        return false;
      }
    });

    // Once more under that name: it replaces the one saved, in any case.
    c.run(
        app,
        CLEAR + type("SELECT 2") + SAVE + "press #query-name\nkey A control\nkey Delete\n"
            + "type \"all ORDERS\"\nframe\nsnap again\n");
    assertTrue(
        c.tree("again").contains("A saved query of this name is replaced."), c.tree("again"));
    c.run(app, "press role=button label=\"Replace\"\nframe\nadvance 1\nframe\n" + SETTLE);
    assertEquals(1, app.state().queries.size());
    assertEquals("SELECT 2", app.state().queries.getFirst().text());
    assertEquals(saved.id(), app.state().queries.getFirst().id());

    c.run(app, CLEAR + SAVED + "snap listed\n");
    var listed = c.tree("listed");
    assertTrue(listed.contains("\"all ORDERS\""), listed);
    c.run(app, "press role=button label=\"Insert\"\nframe\nadvance 1\nframe\nsnap inserted\n");
    assertEquals("SELECT 2;", k.tab(app).text); // as one from the history goes in
  }

  @Test
  void aSavedQueryIsDeletedAfterAsking() throws Exception {
    var app = k.console(SHOP);
    c.run(
        app,
        type(ORDERS) + SAVE + named("Orders") + SAVED
            + "press role=button label=\"Delete\"\nframe\nadvance 1\nframe\nsnap asking\n");
    assertTrue(
        c.tree("asking").contains("label=\"Delete the saved query Orders?\""), c.tree("asking"));
    c.run(app, "press #delete-query-confirm\nframe\nadvance 1\nframe\n" + SETTLE + "snap gone\n");
    assertTrue(app.state().queries.isEmpty());
    assertTrue(c.tree("gone").contains("No saved queries yet"), c.tree("gone"));
  }
}
