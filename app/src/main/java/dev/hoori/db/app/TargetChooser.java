package dev.hoori.db.app;

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
    var first = new ArrayList<Menu.Item>();
    var connected = new ArrayList<Menu.Item>();
    var others = new ArrayList<Menu.Item>();
    for (var profile : s.profiles) {
      if (Workspace.Language.of(profile.provider()) != language) {
        continue;
      }
      boolean live = s.catalog.connected(profile.id());
      var item = item(s, profile, live, choose).checked(profile.id().equals(last));
      (profile.id().equals(last) ? first : live ? connected : others).add(item);
    }
    var items = new ArrayList<>(first);
    items.addAll(connected);
    if (!items.isEmpty() && !others.isEmpty()) {
      items.add(Menu.separator());
    }
    items.addAll(others);
    return items;
  }

  private static Menu.Item item(
      State s, ConnectionProfile profile, boolean live, Consumer<UUID> choose) {
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
    var id = profile.id();
    var item = Menu.item(State.name(profile), () -> choose.accept(id))
        .leading(() -> ConnectionColors.mark(profile.color()));
    return detail.isEmpty() ? item : item.shortcut(String.join(" · ", detail));
  }
}
