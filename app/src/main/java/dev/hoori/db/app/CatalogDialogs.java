package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import java.util.List;

/**
 * The dialogs the catalog asks with, which {@link CatalogTree#dialog} picks one of: the driver a
 * connection needs, the question before an edit or a delete of a connected connection, and the
 * password of one that has none.
 */
final class CatalogDialogs {

  private static final String CANCEL = "Cancel";

  private CatalogDialogs() {}

  /** The driver that connections of a kind wait for: its download, which stop ends. */
  static El driver(State s, Fetch fetch, CatalogTree.Want want) {
    Runnable stop = () -> s.catalog.stopFetch(s, want);
    List<El> buttons =
        fetch.step instanceof Fetch.Failed ? List.of(button(SECONDARY, CANCEL, stop)) : List.of();
    // As wide as the connection dialog, so that the question wraps.
    El content = col().w(ConnectionDialog.width()).child(fetch.view(s, stop));
    return Dialog.dialog("Driver needed", content, buttons, stop);
  }

  /** Whether to disconnect before the edit or the delete. */
  static El disconnect(State s, CatalogTree.Disconnecting d) {
    String name = State.name(d.profile());
    String what = switch (d.then()) {
      case EDIT ->
        "To edit it, HooriDB disconnects it first. If its server or database changes, the"
            + " tabs of its tables close.";
      case DELETE -> "To delete it, HooriDB disconnects it first.";
    };
    El disconnect = button(PRIMARY, "Disconnect", () -> s.catalog.disconnectAndGo(s));
    return Dialog.dialog(
        "Disconnect " + name + "?",
        Label.label(name + " is connected. " + what).wrapText(),
        List.of(button(SECONDARY, CANCEL, () -> s.catalog.disconnecting = null), disconnect),
        disconnect,
        () -> s.catalog.disconnecting = null);
  }

  /** The password of the connection, which the user types. */
  static El password(State s, CatalogTree.Live live) {
    Theme t = Theme.CURRENT.get();
    var p = live.profile;
    String who = p.user().isBlank() ? p.host() : p.user() + " on " + p.host();
    El connect = button(PRIMARY, "Connect", () -> s.catalog.connectWith(s, live));
    El content = col()
        .w(t.size().field())
        .gap(t.space().m())
        .child(Label.label("Enter the password of " + who + ".").wrapText())
        .child(TextField.revealable(
                TextField.passwordField(Prop.of(() -> live.typed, v -> live.typed = v), null)
                    .key("password")
                    .label("Password")
                    .wFull(),
                live.revealed,
                () -> live.revealed = !live.revealed)
            .wFull())
        .child(Checkbox.checkbox(
            Prop.of(() -> live.keep, v -> live.keep = v), "Keep it until HooriDB closes"))
        .child(Label.label(
                Label.Variant.MUTED,
                live.phase instanceof CatalogTree.ForPassword(var silent) && silent
                    ? "The system keyring did not answer, so the password typed here is used for"
                        + " this session only."
                    : "It is not saved in the system keyring.")
            .wrapText());
    return Dialog.dialog(
        "Password for " + State.name(p),
        content,
        List.of(button(SECONDARY, CANCEL, () -> s.catalog.drop(s, live.id())), connect),
        connect,
        () -> s.catalog.drop(s, live.id()));
  }
}
