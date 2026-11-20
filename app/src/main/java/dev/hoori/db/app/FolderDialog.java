package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import java.util.List;
import java.util.UUID;

/** The dialog of a folder: the name of a new one or of one renamed, or the question before a delete. */
final class FolderDialog {

  enum Kind {
    CREATE,
    RENAME,
    DELETE
  }

  final Kind kind;
  // The folder renamed or deleted; for a new one, the folder it goes in, or null for the top.
  final UUID folder;
  String name;

  private FolderDialog(Kind kind, UUID folder, String name) {
    this.kind = kind;
    this.folder = folder;
    this.name = name;
  }

  static FolderDialog create(UUID in) {
    return new FolderDialog(Kind.CREATE, in, "");
  }

  static FolderDialog rename(UUID folder, String name) {
    return new FolderDialog(Kind.RENAME, folder, name);
  }

  static FolderDialog delete(UUID folder, String name) {
    return new FolderDialog(Kind.DELETE, folder, name);
  }

  El view(State s) {
    Runnable close = () -> s.folderDialog = null;
    return kind == Kind.DELETE ? deleting(s, close) : naming(s, close);
  }

  private El naming(State s, Runnable close) {
    Theme t = Theme.CURRENT.get();
    boolean create = kind == Kind.CREATE;
    Runnable done = name.isBlank()
        ? null
        : create ? () -> s.createFolder(folder, name) : () -> s.renameFolder(folder, name);
    El ok = button(PRIMARY, create ? "Create" : "Rename", done);
    El field =
        TextField.textField(Prop.of(() -> name, v -> name = v)).key("name").wFull();
    return Dialog.dialog(
        create ? "New folder" : "Rename folder",
        col()
            .w(t.size().field())
            .child(Form.form(Form.Labels.ABOVE, Form.field("Name", field).required())),
        List.of(button(SECONDARY, "Cancel", close), ok),
        ok,
        close);
  }

  // shortcut: no choice yet to delete what it holds, whose button would join these.
  private El deleting(State s, Runnable close) {
    Theme t = Theme.CURRENT.get();
    El up = button(PRIMARY, "Move contents up", () -> s.dissolveFolder(folder));
    return Dialog.dialog(
        "Delete folder",
        col()
            .w(t.size().field())
            .gap(t.space().m())
            .child(Label.label("Delete the folder " + name + "?").wrapText())
            .child(Label.label(
                    Label.Variant.MUTED,
                    "What it holds moves up a level, to where the folder is. Connections stay"
                        + " connected, and their tabs stay open.")
                .wrapText()),
        List.of(button(SECONDARY, "Cancel", close), up),
        up,
        close);
  }
}
