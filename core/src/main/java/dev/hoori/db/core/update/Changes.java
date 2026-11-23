package dev.hoori.db.core.update;

import com.rethinkj.updater.Section;
import com.rethinkj.updater.Update;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Summary of what an {@link Update} brings, read from GitHub's generated release notes across every
 * release in the update.
 *
 * @param highlights at most {@link #MAX_HIGHLIGHTS} feature and fix descriptions, features first,
 *     in the order of {@link Update#sections()}, each capitalized and ending in a period
 * @param count total number of change entries matched across the update
 * @param contributors distinct contributor logins across those change entries
 */
public record Changes(List<String> highlights, int count, int contributors) {

  public static final int MAX_HIGHLIGHTS = 5;

  // A Conventional Commit title that is a highlight: feat or fix, an optional scope and '!'.
  private static final Pattern HIGHLIGHT = Pattern.compile("(feat|fix)(?:\\([^()]+\\))?!?:\\s");

  public Changes {
    highlights = List.copyOf(highlights);
  }

  /** Whether the update's notes held no recognized change entries. */
  public boolean isEmpty() {
    return count == 0;
  }

  /** Extracts highlights, change count and contributor count from an update's merged sections. */
  public static Changes of(Update update) {
    List<String> feats = new ArrayList<>();
    List<String> fixes = new ArrayList<>();
    Set<String> logins = new HashSet<>();
    int count = 0;
    for (Section section : update.sections()) {
      for (String entry : section.entries()) {
        Change change = change(entry.strip());
        if (change == null) {
          continue;
        }
        count++;
        logins.add(change.login().toLowerCase(Locale.ROOT));
        Matcher h = HIGHLIGHT.matcher(change.title());
        if (h.lookingAt() && !change.title().substring(h.end()).isBlank()) {
          (h.group(1).equals("feat") ? feats : fixes)
              .add(sentence(change.title().substring(h.end())));
        }
      }
    }
    var highlights =
        Stream.concat(feats.stream(), fixes.stream()).limit(MAX_HIGHLIGHTS).toList();
    return new Changes(highlights, count, logins.size());
  }

  private record Change(String title, String login) {}

  /**
   * GitHub's generated entry "{@code <title> by @<login> in <url>}" as its title and login, or
   * null for another entry.
   */
  private static Change change(String entry) {
    int by = entry.lastIndexOf(" by @");
    if (by <= 0) {
      return null;
    }
    String rest = entry.substring(by + " by @".length());
    int in = rest.indexOf(" in ");
    if (in <= 0) {
      return null;
    }
    String login = rest.substring(0, in);
    String url = rest.substring(in + " in ".length());
    if (login.chars().anyMatch(Character::isWhitespace)
        || !url.startsWith("http")
        || url.chars().anyMatch(Character::isWhitespace)) {
      return null;
    }
    return new Change(entry.substring(0, by).strip(), login);
  }

  private static String sentence(String text) {
    String s = text.strip();
    if (s.isEmpty()) {
      return s;
    }
    int first = s.codePointAt(0);
    int upper = Character.toUpperCase(first);
    if (upper != first) {
      s = new String(Character.toChars(upper)) + s.substring(Character.charCount(first));
    }
    if (!s.endsWith(".") && !s.endsWith("!") && !s.endsWith("?")) {
      s += ".";
    }
    return s;
  }
}
