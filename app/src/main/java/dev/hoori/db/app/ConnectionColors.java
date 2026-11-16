package dev.hoori.db.app;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A connection's color as the theme draws it: the one place where core's colors become the
 * theme's hues. A color is always a line or a swatch, never the color of an icon, which says
 * whether the connection is connected.
 */
final class ConnectionColors {

  private ConnectionColors() {}

  /** The choices, none first. */
  static final List<Color> CHOICES = choices();

  private static List<Color> choices() {
    var all = new ArrayList<Color>();
    all.add(null);
    all.addAll(Arrays.asList(Color.values()));
    return all;
  }

  /** The theme's hue of the color; null for none. */
  static Theme.Hue hue(Color color) {
    return color == null
        ? null
        : switch (color) {
          case BLUE -> Theme.Hue.BLUE;
          case VIOLET -> Theme.Hue.VIOLET;
          case PURPLE -> Theme.Hue.PURPLE;
          case PINK -> Theme.Hue.PINK;
          case RED -> Theme.Hue.RED;
          case ORANGE -> Theme.Hue.ORANGE;
          case AMBER -> Theme.Hue.AMBER;
          case GREEN -> Theme.Hue.GREEN;
          case TEAL -> Theme.Hue.TEAL;
          case GRAY -> Theme.Hue.GRAY;
        };
  }

  /** The color of the theme's hue; null for none. */
  static Color color(Theme.Hue hue) {
    return hue == null
        ? null
        : switch (hue) {
          case BLUE -> Color.BLUE;
          case VIOLET -> Color.VIOLET;
          case PURPLE -> Color.PURPLE;
          case PINK -> Color.PINK;
          case RED -> Color.RED;
          case ORANGE -> Color.ORANGE;
          case AMBER -> Color.AMBER;
          case GREEN -> Color.GREEN;
          case TEAL -> Color.TEAL;
          case GRAY -> Color.GRAY;
        };
  }

  /** What a color is called: "None", "Red", as the kit's hue picker says. */
  static String label(Color color) {
    return color == null ? "None" : HuePicker.name(hue(color));
  }

  /** The color in a tree dump, as the kit's tabs say a hue: {@code value="red"}. */
  private static String value(Color color) {
    return color.name().toLowerCase(Locale.ROOT);
  }

  /** The line a color is drawn as, in its hue's edge, which stands out on every surface. */
  private static int edge(Color color) {
    Theme t = Theme.CURRENT.get();
    return t.withAccent(hue(color)).accent().edge();
  }

  /**
   * The mark before a connection's icon in the tree: an upright line in its color, or as much
   * room without one, so that the icons of all connections line up.
   */
  static El mark(Color color) {
    Theme t = Theme.CURRENT.get();
    float w = t.size().indicator();
    El mark = el().size(w, t.size().icon()).shrink(0).radius(w / 2);
    return color == null ? mark : mark.key("color").value(value(color)).fill(edge(color));
  }

  /**
   * The line under a tab's header in its connection's color, also in a window torn off; null for
   * none.
   */
  static El line(Color color) {
    Theme t = Theme.CURRENT.get();
    return color == null
        ? null
        : el().key("color")
            .value(value(color))
            .h(t.size().indicator())
            .wFull()
            .shrink(0)
            .fill(edge(color));
  }
}
