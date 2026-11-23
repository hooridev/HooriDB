package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Workspace;
import java.util.List;
import java.util.UUID;

/**
 * The dialog of a workspace query: the name and language of a new one, saving a console's text to
 * the workspace, renaming a query, or the question before deleting one.
 */
final class QueryDialog {

  enum Kind {
    CREATE,
    SAVE,
    RENAME,
    DELETE
  }

  final Kind kind;
  // For CREATE/SAVE: the folder it goes into, or null for the top. For RENAME/DELETE: the query id.
  final UUID target;
  final String text;
  String name;
  Workspace.Language language;

  private QueryDialog(
      Kind kind, UUID target, String name, String text, Workspace.Language language) {
    this.kind = kind;
    this.target = target;
    this.name = name;
    this.text = text;
    this.language = language;
  }

  static QueryDialog create(UUID in, Workspace.Language language) {
    return new QueryDialog(Kind.CREATE, in, "", "", language);
  }

  static QueryDialog save(UUID in, String text, Workspace.Language language) {
    var first = first(text);
    var suggested = first.length() > 60 ? first.substring(0, 60) : first;
    return new QueryDialog(Kind.SAVE, in, suggested, text, language);
  }

  static QueryDialog rename(UUID query, String name) {
    return new QueryDialog(Kind.RENAME, query, name, "", Workspace.Language.SQL);
  }

  static QueryDialog delete(UUID query, String name) {
    return new QueryDialog(Kind.DELETE, query, name, "", Workspace.Language.SQL);
  }

  static String languageLabel(Workspace.Language language) {
    return switch (language) {
      case SQL -> "SQL";
      case SHELL -> "MongoDB";
    };
  }

  static String first(String text) {
    return text.strip().lines().findFirst().orElse("");
  }

  El view(State s) {
    return kind == Kind.DELETE ? deleting(s) : naming(s);
  }

  private El naming(State s) {
    Theme t = Theme.CURRENT.get();
    Runnable close = () -> s.dialogs.queryDialog = null;
    Runnable done = null;
    if (!name.isBlank()) {
      done = switch (kind) {
        case CREATE -> () -> s.createQuery(target, name.strip(), language);
        case SAVE -> () -> s.createSavedQuery(target, name.strip(), text, language);
        case RENAME -> () -> s.renameQuery(target, name.strip());
        case DELETE -> null;
      };
    }
    String title = switch (kind) {
      case CREATE -> "New query";
      case SAVE -> "Save to workspace";
      case RENAME -> "Rename query";
      case DELETE -> "";
    };
    String action = switch (kind) {
      case CREATE -> "Create";
      case SAVE -> "Save";
      case RENAME -> "Rename";
      case DELETE -> "";
    };
    El ok = button(PRIMARY, action, done);
    El field = TextField.textField(Prop.of(() -> name, v -> name = v))
        .key("query-name")
        .label("Name")
        .wFull();
    El body = switch (kind) {
      case CREATE -> {
        El lang = Segmented.segmented(
                List.of(Workspace.Language.values()),
                language,
                QueryDialog::languageLabel,
                l -> language = l)
            .key("query-language")
            .label("Language");
        yield col()
            .w(t.size().field())
            .child(Form.form(
                Form.Labels.ABOVE,
                Form.field("Name", field).required(),
                Form.field("Language", lang)));
      }
      case SAVE ->
        col()
            .w(ConnectionDialog.width())
            .gap(t.space().m())
            .child(Form.form(Form.Labels.ABOVE, Form.field("Name", field).required()))
            .child(label(Label.Variant.MUTED, first(text)).ellipsis().key("query-preview"));
      case RENAME, DELETE ->
        col()
            .w(t.size().field())
            .child(Form.form(Form.Labels.ABOVE, Form.field("Name", field).required()));
    };
    return Dialog.dialog(title, body, List.of(button(SECONDARY, "Cancel", close), ok), ok, close);
  }

  private El deleting(State s) {
    Theme t = Theme.CURRENT.get();
    Runnable close = () -> {
      s.guard.drop(State.deleteQueryAct(target));
      s.dialogs.queryDialog = null;
    };
    El delete = button(
        PRIMARY, "Delete", () -> s.deleteQuery(target), Button.options().tone(Button.Tone.DANGER));
    El content = col()
        .gap(t.space().m())
        .child(label("Delete the query " + name + "?"))
        .child(
            s.queryTab(target) == null ? null : label(Label.Variant.MUTED, "Its open tab closes."));
    return Dialog.dialog(
        "Delete query", content, List.of(button(SECONDARY, "Cancel", close), delete), close);
  }
}
