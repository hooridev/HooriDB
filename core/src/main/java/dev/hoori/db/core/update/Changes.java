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

  // GitHub generated entry: "<title> by @<login> in <url>".
  private static final Pattern ENTRY =
      Pattern.compile("(.+)\\s+by\\s+@(\\S+)\\s+in\\s+https?://\\S+");
  // Conventional Commit header for a highlight: feat or fix, optional scope, optional '!'.
  private static final Pattern HIGHLIGHT =
      Pattern.compile("(feat|fix)(?:\\([^()]+\\))?!?:\\s+(.+)");

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
        Matcher m = ENTRY.matcher(entry.strip());
        if (!m.matches()) {
          continue;
        }
        count++;
        logins.add(m.group(2).toLowerCase(Locale.ROOT));
        Matcher h = HIGHLIGHT.matcher(m.group(1).strip());
        if (h.matches()) {
          String line = sentence(h.group(2));
          if (!line.isEmpty()) {
            if (h.group(1).equals("feat")) {
              if (feats.size() < MAX_HIGHLIGHTS) {
                feats.add(line);
              }
            } else if (fixes.size() < MAX_HIGHLIGHTS) {
              fixes.add(line);
            }
          }
        }
      }
    }
    List<String> highlights =
        new ArrayList<>(Math.min(MAX_HIGHLIGHTS, feats.size() + fixes.size()));
    highlights.addAll(feats);
    for (String fix : fixes) {
      if (highlights.size() >= MAX_HIGHLIGHTS) {
        break;
      }
      highlights.add(fix);
    }
    return new Changes(highlights, count, logins.size());
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
