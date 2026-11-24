package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTransactions.OPENED;
import static dev.hoori.db.app.TableTabTest.cell;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentEdit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Editing, inserting, copying, and deleting MongoDB documents in a collection tab against
 * {@link FakeServers} across Grid, JSON, and Tree views, including previews, conflicts, validation,
 * round-trip refusal, and read-only/view refusal.
 */
class DocumentEditTest {

  private static final String MONGO = "mongo.example.com";
  private static final String ITEMS = MONGO + "/hoori.items";
  private static final String EDIT_ITEMS = "edit " + ITEMS;
  private static final ConnectionProfile DOCS = ConnectionProfile.of(Provider.MONGODB)
      .withName("docs")
      .withHost(MONGO)
      .withDatabase("hoori")
      .withUser("app");
  private static final Bson.ObjectId ID_ONE = new Bson.ObjectId("64b7f0c2a1b2c3d4e5f60718");
  private static final Bson.ObjectId ID_TWO = new Bson.ObjectId("64b7f0c2a1b2c3d4e5f60719");

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.serve(MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          items
            *_id: objectId
            name: string
            count: long
            createdAt: date
            price: decimal
            meta: object
            tags: array
            blob: binData
          @active_items
            _id: objectId
            name: string
        """);
    c.servers.documents(
        ITEMS,
        List.of(
            Document.of(
                "_id",
                ID_ONE,
                "name",
                "Ada",
                "count",
                42L,
                "createdAt",
                Instant.parse("2024-01-02T03:04:05Z"),
                "price",
                new Bson.Decimal128("19.90"),
                "meta",
                Document.of("city", "London", "active", true),
                "tags",
                Document.array("alpha", "beta"),
                "blob",
                new Bson.Binary(0, new byte[] {1, 2, 3, 4})),
            Document.of(
                "_id",
                ID_TWO,
                "name",
                "Grace",
                "count",
                7L,
                "createdAt",
                Instant.parse("2024-02-03T04:05:06Z"),
                "price",
                new Bson.Decimal128("5.00"),
                "meta",
                Document.of("city", "New York", "active", false),
                "tags",
                Document.array("navy"),
                "blob",
                Bson.Binary.of(UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962")))));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  private App<State> openItems(ConnectionProfile profile) throws Exception {
    var app = c.app(profile);
    c.ready(app, profile);
    c.run(
        app,
        START + open(profile.name()) + SETTLE + open("hoori") + SETTLE + table("items") + SETTLE);
    return app;
  }

  private static String replaceEditor(String text) {
    var escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    return "press #document-editor\nkey A control\nclipboard \"" + escaped
        + "\"\nkey V control\nframe\n";
  }

  private static String rightClickCell(int row, int col) {
    return "press #cell-" + row + "-" + col + " right\nframe\nadvance 1\nframe\n";
  }

  private void saveSnap(String name) throws Exception {
    var out = Path.of("target", "document-edit-snaps");
    Files.createDirectories(out);
    Files.copy(
        dir.resolve("out").resolve(name + ".png"),
        out.resolve(name + ".png"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  @Test
  void editPreviewConflictAndSaveWorkAndProduceSnaps() throws Exception {
    var app = openItems(DOCS);

    // Before selecting a row, Edit/Copy/Delete are disabled while Insert is enabled.
    c.run(app, "snap initial\n");
    var initial = c.tree("initial");
    assertFalse(line(initial, "#insert-document").contains("disabled"), initial);
    assertTrue(line(initial, "#edit-document").contains("disabled"), initial);
    assertTrue(line(initial, "#copy-document").contains("disabled"), initial);
    assertTrue(line(initial, "#delete-document").contains("disabled"), initial);

    // Select row 0 and open Edit document: shows mixed BSON types in shell notation.
    c.run(
        app,
        "press #cell-0-1\nframe\npress #edit-document\nframe\n" + OPENED + "snap edit-dialog\n");
    saveSnap("edit-dialog");
    var dialogTree = c.tree("edit-dialog");
    assertTrue(dialogTree.contains("role=dialog label=\"Edit document\""), dialogTree);
    var editorLine = line(dialogTree, "#document-editor");
    assertTrue(editorLine.contains("ObjectId('64b7f0c2a1b2c3d4e5f60718')"), editorLine);
    assertTrue(editorLine.contains("Binary.createFromBase64('AQIDBA==', 0)"), editorLine);
    var tab = app.state().tables(DOCS.id()).getFirst();
    assertTrue(tab.documentDialog.text.contains("Long('42')"), tab.documentDialog.text);
    assertTrue(
        tab.documentDialog.text.contains("ISODate('2024-01-02T03:04:05.000Z')"),
        tab.documentDialog.text);
    assertTrue(tab.documentDialog.text.contains("Decimal128('19.90')"), tab.documentDialog.text);

    // Edit the name field and press Save… to open Command Preview.
    var editedText = tab.documentDialog.text.replace("name: 'Ada'", "name: 'Alicia'");
    c.run(
        app,
        replaceEditor(editedText) + "press #document-save\nframe\n" + OPENED
            + "snap edit-preview\n");
    saveSnap("edit-preview");
    var previewTree = c.tree("edit-preview");
    assertTrue(previewTree.contains("role=dialog label=\"Command Preview\""), previewTree);
    var previewCmd = line(previewTree, "#preview-command");
    assertTrue(previewCmd.contains("db.getCollection('items').replaceOne("), previewCmd);
    var fullPreview = tab.documentDialog.pending.preview();
    assertTrue(fullPreview.contains("$expr"), fullPreview);
    assertTrue(fullPreview.contains("Alicia"), fullPreview);

    // Simulate a server-side conflict (n == 0): dialog returns to Edit, shows conflict callout,
    // and keeps the user's edited text.
    c.servers.missNext(EDIT_ITEMS);
    c.run(
        app, "press #document-preview-confirm\nframe\n" + SETTLE + OPENED + "snap edit-conflict\n");
    saveSnap("edit-conflict");
    var conflictTree = c.tree("edit-conflict");
    assertTrue(conflictTree.contains("role=dialog label=\"Edit document\""), conflictTree);
    assertTrue(conflictTree.contains("Document changed"), conflictTree);
    assertTrue(
        conflictTree.contains("changed or was deleted on the server since it was read"),
        conflictTree);
    assertTrue(tab.documentDialog.text.contains("Alicia"), tab.documentDialog.text);
    // Original on server was NOT overwritten.
    assertEquals("Ada", c.servers.documentsOf(ITEMS).getFirst().get("name"));

    // Save again without conflict: writes and reloads the tab so Grid shows 'Alicia'.
    c.run(
        app,
        "press #document-save\nframe\n" + OPENED + "press #document-preview-confirm\nframe\n"
            + SETTLE + OPENED + "snap saved\n");
    assertNull(tab.documentDialog);
    assertEquals("Alicia", c.servers.documentsOf(ITEMS).getFirst().get("name"));
    assertEquals(42L, c.servers.documentsOf(ITEMS).getFirst().get("count"));
    assertTrue(cell(c.tree("saved"), 0, 1).contains("\"Alicia\""), c.tree("saved"));
  }

  @Test
  void insertCopyAndDeleteWorkThroughPreviewAndConfirm() throws Exception {
    var app = openItems(DOCS);

    // 1. Insert a new document (without _id) via toolbar button.
    c.run(app, "press #insert-document\nframe\n" + OPENED + "snap insert-open\n");
    assertTrue(c.tree("insert-open").contains("role=dialog label=\"Insert document\""));
    c.run(
        app,
        replaceEditor("{ name: 'Edsger', count: Long('9') }") + "press #document-save\nframe\n"
            + OPENED + "snap insert-preview\n");
    var insertPreview = c.tree("insert-preview");
    assertTrue(insertPreview.contains("db.getCollection('items').insertOne("), insertPreview);
    c.run(app, "press #document-preview-confirm\nframe\n" + SETTLE + OPENED + "snap inserted\n");
    assertEquals(3, c.servers.documentsOf(ITEMS).size());
    assertEquals("Edsger", c.servers.documentsOf(ITEMS).get(2).get("name"));
    assertEquals(9L, c.servers.documentsOf(ITEMS).get(2).get("count"));
    assertTrue(cell(c.tree("inserted"), 2, 1).contains("\"Edsger\""), c.tree("inserted"));

    // 2. Copy row 0 via grid context menu: dialog opens without _id.
    c.run(
        app,
        rightClickCell(0, 1) + "press role=menuitem label=\"Copy document…\"\nframe\n" + OPENED
            + "snap copy-open\n");
    var tab = app.state().tables(DOCS.id()).getFirst();
    assertFalse(tab.documentDialog.text.contains("_id"), tab.documentDialog.text);
    assertTrue(tab.documentDialog.text.contains("Ada"), tab.documentDialog.text);
    var copyText = tab.documentDialog.text.replace("name: 'Ada'", "name: 'Ada Copy'");
    c.run(
        app,
        replaceEditor(copyText) + "press #document-save\nframe\n" + OPENED
            + "press #document-preview-confirm\nframe\n" + SETTLE + OPENED);
    assertEquals(4, c.servers.documentsOf(ITEMS).size());
    assertEquals("Ada Copy", c.servers.documentsOf(ITEMS).get(3).get("name"));

    // 3. Delete row 1 via toolbar: confirmation names _id and previews deleteOne.
    c.run(
        app,
        "press #cell-1-1\nframe\npress #delete-document\nframe\n" + OPENED
            + "snap delete-confirm\n");
    var deleteConfirm = c.tree("delete-confirm");
    assertTrue(deleteConfirm.contains("role=dialog label=\"Delete document\""), deleteConfirm);
    assertTrue(deleteConfirm.contains("ObjectId('64b7f0c2a1b2c3d4e5f60719')"), deleteConfirm);
    assertTrue(deleteConfirm.contains("db.getCollection('items').deleteOne("), deleteConfirm);

    // Conflict on delete reports Document changed and deletes nothing.
    c.servers.missNext(EDIT_ITEMS);
    c.run(
        app,
        "press #document-preview-confirm\nframe\n" + SETTLE + OPENED + "snap delete-conflict\n");
    assertTrue(c.tree("delete-conflict").contains("Document changed"), c.tree("delete-conflict"));
    assertEquals(4, c.servers.documentsOf(ITEMS).size());
    c.run(app, "press #document-preview-cancel\nframe\n" + OPENED);

    // Delete without conflict removes the document and reloads the tab.
    c.run(
        app,
        "press #cell-1-1\nframe\npress #delete-document\nframe\n" + OPENED
            + "press #document-preview-confirm\nframe\n" + SETTLE + OPENED);
    assertEquals(3, c.servers.documentsOf(ITEMS).size());
    assertTrue(
        c.servers.documentsOf(ITEMS).stream().noneMatch(d -> ID_TWO.equals(d.get("_id"))),
        c.servers.documentsOf(ITEMS).toString());
  }

  @Test
  void syntaxErrorChangedIdAndOmittedIdAreHandledProperly() throws Exception {
    var app = openItems(DOCS);
    c.run(app, "press #cell-0-1\nframe\npress #edit-document\nframe\n" + OPENED);

    // 1. Syntax error marks error and sends nothing.
    int callsBefore = c.servers.calls.size();
    c.run(
        app,
        replaceEditor("{ _id: ObjectId('64b7f0c2a1b2c3d4e5f60718'), name: }")
            + "press #document-save\nframe\nsnap syntax-error\n");
    var syntaxTree = c.tree("syntax-error");
    assertTrue(syntaxTree.contains("Invalid document"), syntaxTree);
    assertTrue(syntaxTree.contains("role=dialog label=\"Edit document\""), syntaxTree);
    assertEquals(callsBefore, c.servers.calls.size());

    // 2. Changed _id is refused and sends nothing.
    c.run(
        app,
        replaceEditor("{ _id: ObjectId('64b7f0c2a1b2c3d4e5f60719'), name: 'Ada' }")
            + "press #document-save\nframe\nsnap changed-id\n");
    var changedId = c.tree("changed-id");
    assertTrue(changedId.contains("_id cannot be changed"), changedId);
    assertTrue(changedId.contains("role=dialog label=\"Edit document\""), changedId);
    assertEquals(callsBefore, c.servers.calls.size());

    // 3. Omitting _id in Edit is allowed and preserves the original _id on save.
    c.run(
        app,
        replaceEditor("{ name: 'Ada NoId', count: Long('42') }") + "press #document-save\nframe\n"
            + OPENED + "press #document-preview-confirm\nframe\n" + SETTLE + OPENED);
    var saved = c.servers.documentsOf(ITEMS).getFirst();
    assertEquals(ID_ONE, saved.get("_id"));
    assertEquals("Ada NoId", saved.get("name"));
  }

  @Test
  void editWorksFromJsonCaretAndTreeNestedSelection() throws Exception {
    var app = openItems(DOCS);

    // 1. Switch to JSON view, move caret to second document ('Grace'), edit it.
    c.run(
        app,
        "press role=radio label=\"JSON\"\nframe\nadvance 1\nframe\n"
            + "press #json\nkey End control\nkey ArrowUp\nkey ArrowUp\nframe\n"
            + "press #edit-document\nframe\n" + OPENED + "snap json-edit\n");
    var tab = app.state().tables(DOCS.id()).getFirst();
    assertTrue(tab.documentDialog.text.contains("Grace"), tab.documentDialog.text);
    c.run(
        app,
        replaceEditor(tab.documentDialog.text.replace("Grace", "Grace Hopper"))
            + "press #document-save\nframe\n" + OPENED + "press #document-preview-confirm\nframe\n"
            + SETTLE + OPENED);
    assertEquals("Grace Hopper", c.servers.documentsOf(ITEMS).get(1).get("name"));

    // 2. Switch to Tree view, expand first document, select nested 'meta' node, open Edit.
    c.run(
        app,
        "press role=radio label=\"Tree\"\nframe\nadvance 1\nframe\n"
            + "press role=treeitem label=\"ObjectId('64b7f0c2a1b2c3d4e5f60718')\"\n"
            + "key ArrowRight\nframe\n"
            + "press role=treeitem label=\"meta\"\nframe\n"
            + "press #edit-document\nframe\n" + OPENED);
    assertNotNull(tab.documentDialog);
    assertTrue(tab.documentDialog.text.contains("Ada"), tab.documentDialog.text);
    c.run(
        app,
        replaceEditor(tab.documentDialog.text.replace("Ada", "Ada Tree"))
            + "press #document-save\nframe\n" + OPENED + "press #document-preview-confirm\nframe\n"
            + SETTLE + OPENED);
    assertEquals("Ada Tree", c.servers.documentsOf(ITEMS).getFirst().get("name"));
  }

  @Test
  void aTreeSelectionDoesNotMoveToTheDocumentReadInItsPlace() throws Exception {
    var app = openItems(DOCS);
    var tab = app.state().tables(DOCS.id()).getFirst();
    c.run(
        app,
        "press role=radio label=\"Tree\"\nframe\nadvance 1\nframe\n"
            + "press role=treeitem label=\"ObjectId('64b7f0c2a1b2c3d4e5f60718')\"\nframe\n");
    assertEquals(ID_ONE, tab.currentDocument().get("_id"));

    // Delete the selected document from Grid, which reads the page anew, and go back to Tree.
    c.run(
        app,
        "press role=radio label=\"Grid\"\nframe\nadvance 1\nframe\n"
            + "press #cell-0-1\nframe\npress #delete-document\nframe\n"
            + OPENED
            + "press #document-preview-confirm\nframe\n"
            + SETTLE
            + OPENED
            + "press role=radio label=\"Tree\"\nframe\nadvance 1\nframe\nsnap tree-after\n");
    assertEquals(1, c.servers.documentsOf(ITEMS).size());
    assertNull(tab.currentDocument());
    assertTrue(
        line(c.tree("tree-after"), "#delete-document").contains("disabled"), c.tree("tree-after"));
  }

  @Test
  void exitAsksBeforeDroppingAnEditedDocument() throws Exception {
    var app = openItems(DOCS);
    c.run(app, "press #cell-0-1\nframe\npress #edit-document\nframe\n" + OPENED);
    var tab = app.state().tables(DOCS.id()).getFirst();
    assertFalse(tab.atStake(), "An untouched dialog drops nothing");
    c.run(app, replaceEditor(tab.documentDialog.text.replace("Ada", "Ada Draft")));
    assertTrue(tab.atStake());

    // The dialog holds the menus; the window's close button or the system still asks to close,
    // in a frame. The question takes the dialog's place, which fades out first.
    String close = "await\nframe\n" + OPENED + OPENED;
    app.post(app.state()::requestClose);
    c.run(app, close + "snap exit-asked\n");
    assertTrue(c.tree("exit-asked").contains("Discard 1 change?"), c.tree("exit-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    assertTrue(tab.documentDialog.text.contains("Ada Draft"), tab.documentDialog.text);
    app.post(app.state()::requestClose);
    c.run(app, close + "press #answer-discard\nframe\n" + OPENED + SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertEquals("Ada", c.servers.documentsOf(ITEMS).getFirst().get("name"));
  }

  @Test
  void aWriteTheServerCouldNotConfirmKeepsTheTextAndSaysSo() throws Exception {
    var app = openItems(DOCS);
    c.run(app, "press #cell-0-1\nframe\npress #edit-document\nframe\n" + OPENED);
    var tab = app.state().tables(DOCS.id()).getFirst();
    c.servers.nextEdit(
        EDIT_ITEMS, new DocumentEdit.Outcome.Unconfirmed("waiting for replication timed out"));
    c.run(
        app,
        replaceEditor(tab.documentDialog.text.replace("Ada", "Ada Unsure"))
            + "press #document-save\nframe\n"
            + OPENED
            + "press #document-preview-confirm\nframe\n"
            + SETTLE
            + OPENED
            + "snap unconfirmed\n");
    var tree = c.tree("unconfirmed");
    assertTrue(tree.contains("could not confirm the write"), tree);
    assertTrue(tree.contains("waiting for replication timed out"), tree);
    assertTrue(tree.contains("role=dialog label=\"Edit document\""), tree);
    assertTrue(tab.documentDialog.text.contains("Ada Unsure"), tab.documentDialog.text);

    // The write may have taken effect: going to the preview and back leaves it at stake.
    c.run(
        app,
        "press #document-save\nframe\n" + OPENED + "press #document-preview-back\nframe\n"
            + OPENED);
    assertNull(tab.documentDialog.problem);
    assertTrue(tab.atStake());
  }

  @Test
  void aRetryWithoutTheIdSentKeepsThatId() throws Exception {
    var app = openItems(DOCS);
    var tab = app.state().tables(DOCS.id()).getFirst();
    c.servers.nextEdit(EDIT_ITEMS, new DocumentEdit.Outcome.Unconfirmed("timed out"));
    c.run(
        app,
        "press #insert-document\nframe\n"
            + OPENED
            + replaceEditor("{ _id: 42, name: 'Edsger' }")
            + "press #document-save\nframe\n"
            + OPENED
            + "press #document-preview-confirm\nframe\n"
            + SETTLE
            + OPENED
            + replaceEditor("{ name: 'Edsger' }")
            + "press #document-save\nframe\n"
            + OPENED);
    assertEquals(
        42, ((DocumentEdit.Insert) tab.documentDialog.pending).document().get("_id"));
  }

  @Test
  void aNewDocumentKeepsItsIdAcrossRetries() throws Exception {
    var app = openItems(DOCS);
    var tab = app.state().tables(DOCS.id()).getFirst();
    c.run(
        app,
        "press #insert-document\nframe\n"
            + OPENED
            + replaceEditor("{ name: 'Edsger' }")
            + "press #document-save\nframe\n"
            + OPENED);
    var id = ((DocumentEdit.Insert) tab.documentDialog.pending).document().get("_id");
    assertTrue(id instanceof Bson.ObjectId, String.valueOf(id));

    // The server could not confirm it: saving again sends the same _id, which a server that had
    // taken the first refuses as a duplicate instead of keeping two.
    c.servers.nextEdit(EDIT_ITEMS, new DocumentEdit.Outcome.Unconfirmed("timed out"));
    c.run(
        app,
        "press #document-preview-confirm\nframe\n"
            + SETTLE
            + OPENED
            + "press #document-save\nframe\n"
            + OPENED);
    assertEquals(
        id, ((DocumentEdit.Insert) tab.documentDialog.pending).document().get("_id"));
    c.run(app, "press #document-preview-confirm\nframe\n" + SETTLE + OPENED);
    assertEquals(id, c.servers.documentsOf(ITEMS).getLast().get("_id"));

    // ObjectId() in the text is read once: the same text sends the same _id again.
    c.run(
        app,
        "press #insert-document\nframe\n"
            + OPENED
            + replaceEditor("{ _id: ObjectId(), name: 'Barbara' }")
            + "press #document-save\nframe\n"
            + OPENED);
    var made = ((DocumentEdit.Insert) tab.documentDialog.pending).document().get("_id");
    c.servers.nextEdit(EDIT_ITEMS, new DocumentEdit.Outcome.Unconfirmed("timed out"));
    c.run(
        app,
        "press #document-preview-confirm\nframe\n"
            + SETTLE
            + OPENED
            + "press #document-save\nframe\n"
            + OPENED);
    assertEquals(
        made, ((DocumentEdit.Insert) tab.documentDialog.pending).document().get("_id"));

    // The text holds that _id now, so an edited retry keeps it too.
    c.run(
        app,
        "press #document-preview-back\nframe\n"
            + OPENED
            + replaceEditor(tab.documentDialog.text.replace("Barbara", "Barbara L"))
            + "press #document-save\nframe\n"
            + OPENED);
    assertEquals(
        made, ((DocumentEdit.Insert) tab.documentDialog.pending).document().get("_id"));
  }

  @Test
  void nonRoundTrippingDocumentIsRefusedBeforeOpeningDialog() throws Exception {
    // Duplicate field names collapse on Shell.document(), so roundTripped returns empty.
    var badDoc = new Document(List.of(
        new Document.Entry("_id", ID_ONE),
        new Document.Entry("name", "first"),
        new Document.Entry("name", "second")));
    c.servers.documents(ITEMS, List.of(badDoc));
    var app = openItems(DOCS);

    c.run(app, "press #cell-0-1\nframe\npress #edit-document\nframe\nsnap refused-edit\n");
    var tab = app.state().tables(DOCS.id()).getFirst();
    assertNull(tab.documentDialog);
    assertTrue(
        c.tree("refused-edit").contains("Document cannot be edited"), c.tree("refused-edit"));

    c.run(app, "press #copy-document\nframe\nsnap refused-copy\n");
    assertNull(tab.documentDialog);
    assertTrue(
        c.tree("refused-copy").contains("Document cannot be copied"), c.tree("refused-copy"));

    c.run(app, "press #delete-document\nframe\nsnap refused-delete\n");
    assertNull(tab.documentDialog);
    assertTrue(
        c.tree("refused-delete").contains("Document cannot be deleted"), c.tree("refused-delete"));
  }

  @Test
  void exitWaitingOnADeleteShowsItsUnknownOutcomeInsteadOfClosing() throws Exception {
    var app = openItems(DOCS);
    var tab = app.state().tables(DOCS.id()).getFirst();
    var gate = c.servers.hold(EDIT_ITEMS);
    c.run(
        app,
        "press #cell-0-1\nframe\npress #delete-document\nframe\n"
            + OPENED
            + "press #document-preview-confirm\nframe\n");
    eventually(() -> c.servers.calls.contains(EDIT_ITEMS));
    // Not posted: await would wait for the held write.
    app.state().requestClose();
    c.run(app, "frame\n" + OPENED + OPENED + "snap waiting\n");
    assertTrue(c.tree("waiting").contains("#answer-cancel-commit"), c.tree("waiting"));

    c.servers.nextEdit(EDIT_ITEMS, new DocumentEdit.Outcome.Unconfirmed("timed out"));
    gate.countDown();
    c.run(app, SETTLE + OPENED + OPENED + "snap shown\n");
    assertFalse(app.windows().all().isEmpty());
    assertNull(app.state().guard.pending);
    var shown = c.tree("shown");
    assertTrue(shown.contains("could not confirm the write"), shown);
    assertTrue(shown.contains("role=dialog label=\"Delete document\""), shown);
    assertNotNull(tab.documentDialog);
  }

  @Test
  void readOnlyConnectionAndViewsHideEditActionsAndShowReason() throws Exception {
    // 1. Read-only connection: edit buttons absent, status shows Read-only connection.
    var roProfile = DOCS.withName("docs-ro").withReadOnly(true);
    var roApp = openItems(roProfile);
    c.run(roApp, "snap ro\n");
    var roTree = c.tree("ro");
    assertFalse(roTree.contains("#insert-document"), roTree);
    assertFalse(roTree.contains("#edit-document"), roTree);
    assertFalse(roTree.contains("#copy-document"), roTree);
    assertFalse(roTree.contains("#delete-document"), roTree);
    assertTrue(line(roTree, "#edit-status").contains(ResultEdits.READ_ONLY_CONNECTION), roTree);
    c.run(roApp, rightClickCell(0, 1) + "snap ro-menu\n");
    var roMenu = c.tree("ro-menu");
    assertTrue(roMenu.contains("role=menuitem label=\"Refresh\""), roMenu);
    for (var write : List.of("Edit", "Copy", "Delete", "Insert")) {
      assertFalse(roMenu.contains("role=menuitem label=\"" + write + " document…\""), roMenu);
    }

    // 2. View on writable connection: edit buttons absent, status shows views cannot be edited.
    var app = c.app(DOCS);
    c.ready(app, DOCS);
    c.run(
        app,
        START + open(DOCS.name()) + SETTLE + open("hoori") + SETTLE + table("active_items") + SETTLE
            + "snap view\n");
    var viewTree = c.tree("view");
    assertFalse(viewTree.contains("#insert-document"), viewTree);
    assertFalse(viewTree.contains("#edit-document"), viewTree);
    assertFalse(viewTree.contains("#copy-document"), viewTree);
    assertFalse(viewTree.contains("#delete-document"), viewTree);
    assertTrue(line(viewTree, "#edit-status").contains(ResultEdits.VIEW_READ_ONLY), viewTree);
  }

  @Test
  void heldWriteShowsBusyStateAndDisablesBackUntilFinished() throws Exception {
    var app = openItems(DOCS);
    var gate = c.servers.hold(EDIT_ITEMS);
    try {
      c.run(
          app,
          "press #cell-0-1\nframe\npress #edit-document\nframe\n" + OPENED
              + "press #document-save\nframe\n" + OPENED
              + "press #document-preview-confirm\nframe\n");
      eventually(() -> c.servers.calls.contains(EDIT_ITEMS));
      c.run(app, "frame\nsnap writing\n");
      var writing = c.tree("writing");
      assertTrue(line(writing, "#document-preview-back").contains("disabled"), writing);
      assertTrue(line(writing, "#document-preview-confirm").contains("busy"), writing);
      assertTrue(app.state().tables(DOCS.id()).getFirst().writingChanges());
    } finally {
      gate.countDown();
    }
    c.run(app, SETTLE + OPENED);
    assertNull(app.state().tables(DOCS.id()).getFirst().documentDialog);
  }
}
