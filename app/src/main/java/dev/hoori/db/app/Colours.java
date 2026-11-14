package dev.hoori.db.app;

import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.connection.Colour;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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

  /** What a colour is called: "None", "Red". */
  static String label(Colour colour) {
    if (colour == null) {
      return "None";
    }
    var name = colour.name().toLowerCase(Locale.ROOT);
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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

  /**
   * The colours as a row of swatches, one of them chosen; one stop for Tab, whose arrow keys choose
   * the one before or after, Home and End the first and the last. In a tree dump a radio group with
   * a radio per colour.
   *
   * @param onSelect told the colour the user chooses; null disables the choice
   */
  // shortcut: the kit's choices show texts only; use the kit's once one can show colours.
  static El choice(Theme t, Colour selected, Consumer<Colour> onSelect) {
    boolean enabled = onSelect != null;
    El group = row()
        .role("radiogroup")
        .disabled(!enabled)
        .gap(t.space().xs())
        .align(Style.Align.CENTER)
        .padding(t.space().xs())
        .margin(-t.space().xs())
        .radius(t.radius().medium());
    group.style().width = Len.FIT_CONTENT;
    int at = CHOICES.indexOf(selected);
    for (var colour : CHOICES) {
      group.child(
          swatch(t, colour, colour == selected, enabled ? () -> onSelect.accept(colour) : null));
    }
    if (!enabled) {
      return group;
    }
    int last = CHOICES.size() - 1;
    return group.focusable().focusRing(t.accent().edge()).onKey(k -> {
      if (k.modifiers() != 0) {
        return false;
      }
      int next = switch (k.name()) {
        case "ArrowLeft", "ArrowUp" -> at <= 0 ? last : at - 1;
        case "ArrowRight", "ArrowDown" -> at == last ? 0 : at + 1;
        case "Home" -> 0;
        case "End" -> last;
        default -> -1;
      };
      if (next < 0) {
        return false;
      }
      if (next != at) {
        onSelect.accept(CHOICES.get(next));
      }
      return true;
    });
  }

  // A round swatch inside a ring, which shows while it is chosen, and while hovered quieter.
  private static El swatch(Theme t, Colour colour, boolean on, Runnable onPress) {
    float side = t.size().control() - 2 * t.space().xs();
    float ring = 2 * t.size().stroke();
    El dot = el().size(side, side).radius(side / 2);
    if (colour == null) {
      dot.border(t.size().stroke(), t.border().strong());
    } else {
      dot.fill(t.withAccent(hue(colour)).accent().color());
    }
    float outer = side + 2 * (ring + t.size().stroke());
    El swatch = el().role("radio")
        .label(label(colour))
        .checked(on)
        .disabled(onPress == null)
        .size(outer, outer)
        .center()
        .radius(outer / 2)
        .border(ring, on ? t.text().primary() : 0)
        .motion(t.motion().feedback())
        .child(dot);
    if (onPress == null) {
      return swatch;
    }
    swatch.cursor(Cursor.POINTER).onPress(onPress);
    if (!on) {
      swatch.whenHovered(e -> e.border(ring, t.border().strong()));
    }
    return Tooltip.tooltip(t, swatch, label(colour));
  }
}
