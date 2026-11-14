package dev.hoori.db.app;

import static com.rethinkj.ui.El.el;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Colour;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A connection's colour as the theme draws it: the one place where core's colours become the
 * theme's hues. A colour is always a line or a swatch, never the colour of an icon, which says
 * whether the connection is connected.
 */
final class Colours {

  private Colours() {}

  /** The choices, none first. */
  static final List<Colour> CHOICES = choices();

  private static List<Colour> choices() {
    var all = new ArrayList<Colour>();
    all.add(null);
    all.addAll(Arrays.asList(Colour.values()));
    return all;
  }

  /** The theme's hue of the colour; null for none. */
  static Theme.Hue hue(Colour colour) {
    return colour == null
        ? null
        : switch (colour) {
          case BLUE -> Theme.Hue.BLUE;
          case VIOLET -> Theme.Hue.VIOLET;
          case PURPLE -> Theme.Hue.PURPLE;
          case PINK -> Theme.Hue.PINK;
          case RED -> Theme.Hue.RED;
          case ORANGE -> Theme.Hue.ORANGE;
          case AMBER -> Theme.Hue.AMBER;
          case GREEN -> Theme.Hue.GREEN;
          case TEAL -> Theme.Hue.TEAL;
          case GREY -> Theme.Hue.GREY;
        };
  }

  /** The colour of the theme's hue; null for none. */
  static Colour colour(Theme.Hue hue) {
    return hue == null ? null : Colour.valueOf(hue.name());
  }

  /** What a colour is called: "None", "Red", as the kit's hue picker says. */
  static String label(Colour colour) {
    return colour == null ? "None" : HuePicker.name(hue(colour));
  }

  /** The colour in a tree dump, as the kit's tabs say a hue: {@code value="red"}. */
  private static String value(Colour colour) {
    return colour.name().toLowerCase(Locale.ROOT);
  }

  /** The line a colour is drawn as, in its hue's edge, which stands out on every surface. */
  private static int edge(Theme t, Colour colour) {
    return t.withAccent(hue(colour)).accent().edge();
  }

  /**
   * The mark before a connection's icon in the tree: an upright line in its colour, or as much
   * room without one, so that the icons of all connections line up.
   */
  static El mark(Theme t, Colour colour) {
    float w = t.size().indicator();
    El mark = el().size(w, t.size().icon()).shrink(0).radius(w / 2);
    return colour == null ? mark : mark.key("colour").value(value(colour)).fill(edge(t, colour));
  }

  /**
   * The line under a tab's header in its connection's colour, also in a window torn off; null for
   * none.
   */
  static El line(Theme t, Colour colour) {
    return colour == null
        ? null
        : el().key("colour")
            .value(value(colour))
            .h(t.size().indicator())
            .wFull()
            .shrink(0)
            .fill(edge(t, colour));
  }
}
