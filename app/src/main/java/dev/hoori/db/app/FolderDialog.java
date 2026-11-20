package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
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
    return kind == Kind.DELETE ? deleting(s) : naming(s, () -> s.folderDialog = null);
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

  /**
   * The question before a delete: what the folder holds moves up, or goes with it, its connections
   * as each would alone. While they go, it says so, and once the keyring keeps it waiting, Cancel
   * gives up the rest; it stays where some could not go, and names them.
   */
  private El deleting(State s) {
    Theme t = Theme.CURRENT.get();
    var held = s.folder(folder);
    int n = held == null ? 0 : held.connections().size();
    boolean busy = s.removing;
    El up = button(PRIMARY, "Move contents up", busy ? null : () -> s.dissolveFolder(folder));
    El contents = button(
        SECONDARY,
        "Delete contents",
        () -> s.deleteFolder(folder),
        Button.options()
            .tone(Button.Tone.DANGER)
            .state(busy ? Button.State.BUSY : Button.State.READY));
    El cancel = button(SECONDARY, "Cancel", busy && !s.removingSlow ? null : s::cancelFolderDialog);
    // As wide as the connection dialog, as its questions are, so that the text wraps below.
    El content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label("Delete the folder " + name + "?").wrapText())
        .child(Label.label(
                Label.Variant.MUTED,
                n == 0
                    ? "It holds folders, and no connections."
                    : "It holds " + n + (n == 1 ? " connection" : " connections")
                        + ", its folders included.")
            .wrapText())
        .child(Label.label(
                Label.Variant.MUTED,
                "Move contents up puts what it holds where the folder is, and connections stay"
                    + " connected. Delete contents deletes them with their passwords in the system"
                    + " keyring, their consoles and their history.")
            .wrapText());
    if (s.removingSlow) {
      content.child(Callout.callout(
          Callout.Kind.WARNING,
          "The system keyring does not answer",
          "Cancel gives up waiting: the password being deleted may then still be in the keyring or"
              + " not, and the connections not deleted yet stay."
              + (s.keyringWorker.behind() ? ConnectionDialog.BEHIND : "")));
    }
    if (!s.kept.isEmpty()) {
      var names = String.join(", ", s.kept.stream().map(State::name).toList());
      content.child(Callout.callout(
          Callout.Kind.DANGER,
          "Not all connections were deleted",
          "These stayed, and the folder with them: " + names + ".\n" + s.removeError));
    }
    return Dialog.dialog(
        "Delete folder", content, List.of(cancel, contents, up), up, s::cancelFolderDialog);
  }
}
