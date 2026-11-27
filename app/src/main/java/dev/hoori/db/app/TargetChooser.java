package dev.hoori.db.app;

import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.Menu;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The items of the menu at the caret that asks which connection a workspace query runs on: the
 * connections of its language, the last target first and checked, connected or not, so that Enter
 * never moves the query elsewhere; then the other connected ones, then those not connected, each
 * with its color mark, and its folder path, read-only and connected state at the right.
 */
final class TargetChooser {

  static final String LABEL = "Run on";

  private TargetChooser() {}

  static List<Menu.Item> items(
      State s, Workspace.Language language, UUID last, Consumer<UUID> choose) {
    var items = new ArrayList<Menu.Item>();
    var choices = choices(s, language, last);
    for (var choice : choices) {
      if (choice.after() && !items.isEmpty()) {
        items.add(Menu.separator());
      }
      var id = choice.profile().id();
      var item = Menu.item(State.name(choice.profile()), () -> choose.accept(id))
          .leading(() -> ConnectionColors.mark(choice.profile().color()))
          .checked(id.equals(last));
      items.add(choice.detail().isEmpty() ? item : item.shortcut(choice.detail()));
    }
    return items;
  }

  /**
   * The items of a combo box of the connections, in the order and with the marks of {@link
   * #items}, the one given first: as Run SQL file chooses where a file runs.
   */
  static ComboBox.Item item(State s, UUID id) {
    var profile = s.profile(id);
    if (profile == null) {
      return ComboBox.item("A deleted connection");
    }
    var item =
        ComboBox.item(State.name(profile)).leading(() -> ConnectionColors.mark(profile.color()));
    var detail = detail(s, profile, s.catalog.connected(id));
    return detail.isEmpty() ? item : item.detail(detail);
  }

  /** The connections of the language in the order of {@link #items}, the last target first. */
  static List<UUID> order(State s, Workspace.Language language, UUID last) {
    return choices(s, language, last).stream().map(c -> c.profile().id()).toList();
  }

  /**
   * A connection as the chooser lists it: what shows at its right, and whether a separator goes
   * before it, as before the first that is not connected.
   */
  private record Choice(ConnectionProfile profile, String detail, boolean after) {}

  private static List<Choice> choices(State s, Workspace.Language language, UUID last) {
    var first = new ArrayList<Choice>();
    var connected = new ArrayList<Choice>();
    var others = new ArrayList<Choice>();
    for (var profile : s.profiles) {
      if (Workspace.Language.of(profile.provider()) != language) {
        continue;
      }
      boolean live = s.catalog.connected(profile.id());
      var detail = detail(s, profile, live);
      if (profile.id().equals(last)) {
        first.add(new Choice(profile, detail, false));
      } else if (live) {
        connected.add(new Choice(profile, detail, false));
      } else {
        others.add(new Choice(profile, detail, others.isEmpty()));
      }
    }
    var all = new ArrayList<>(first);
    all.addAll(connected);
    all.addAll(others);
    return all;
  }

  // Its folder path, and whether it is read-only and connected.
  private static String detail(State s, ConnectionProfile profile, boolean live) {
    var detail = new ArrayList<String>();
    var path = s.workspace().path(profile.id());
    if (path != null && !path.isEmpty()) {
      detail.add(String.join(" / ", path.stream().map(Workspace.Folder::name).toList()));
    }
    if (profile.readOnly()) {
      detail.add("Read-only");
    }
    if (!live) {
      detail.add("Not connected");
    }
    return String.join(" · ", detail);
  }
}
