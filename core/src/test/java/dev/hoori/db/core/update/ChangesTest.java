package dev.hoori.db.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.updater.Release;
import com.rethinkj.updater.Update;
import com.rethinkj.updater.Version;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangesTest {

  private static Release release(String version, String notes) {
    return new Release(
        Version.parse(version),
        "v" + version,
        "HooriDB " + version,
        Instant.parse("2026-11-23T06:00:00Z"),
        notes,
        false,
        URI.create("https://github.com/hooridev/HooriDB/releases/tag/v" + version),
        List.of());
  }

  private static Update update(String installed, Release... releases) {
    return Update.find(Version.parse(installed), List.of(releases)).orElseThrow();
  }

  @Test
  void extractsHighlightsCountAndContributorsFromGeneratedNotes() {
    var notes = """
        ## What's Changed
        * fix(app): give the About dialog room beside its longest line by @Alice in https://github.com/hooridev/HooriDB/pull/1
        * feat(app): page through a table from its status line by @alice in https://github.com/hooridev/HooriDB/pull/2
        * refactor(core): split what every provider has from its SQL dialect by @bob in https://github.com/hooridev/HooriDB/pull/3
        * feat!: drop legacy workspace format by @Alice in https://github.com/hooridev/HooriDB/pull/4
        * feat(core)!: enforce read-only sessions on the server by @dependabot[bot] in https://github.com/hooridev/HooriDB/pull/5
        * docs: add a README by @bob in https://github.com/hooridev/HooriDB/pull/6
        * fix!: prevent double commit on retry by @carol in https://github.com/hooridev/HooriDB/pull/7
        * feat: connect by `mongodb+srv://` address by @alice in https://github.com/hooridev/HooriDB/pull/8
        * fix(app): keep caret on undo by @bob in https://github.com/hooridev/HooriDB/pull/9
        * Update dependencies by @carol in https://github.com/hooridev/HooriDB/pull/10

        ## New Contributors
        * @bob made their first contribution in https://github.com/hooridev/HooriDB/pull/3

        **Full Changelog**: https://github.com/hooridev/HooriDB/compare/v0.1.0...v0.2.0
        """;
    var changes = Changes.of(update("0.1.0", release("0.2.0", notes)));
    assertFalse(changes.isEmpty());
    assertEquals(10, changes.count());
    assertEquals(4, changes.contributors());
    assertEquals(
        List.of(
            "Page through a table from its status line.",
            "Drop legacy workspace format.",
            "Enforce read-only sessions on the server.",
            "Connect by `mongodb+srv://` address.",
            "Give the About dialog room beside its longest line."),
        changes.highlights());
  }

  @Test
  void mergesReleasesNewerFirstWithFeaturesBeforeFixes() {
    var older = release("0.2.0", """
        ## What's Changed
        * feat: older feature by @alice in https://github.com/hooridev/HooriDB/pull/1
        * fix: older fix by @bob in https://github.com/hooridev/HooriDB/pull/2
        """);
    var newer = release("0.3.0", """
        ## What's Changed
        * fix: newer fix by @carol in https://github.com/hooridev/HooriDB/pull/3
        * feat: newer feature by @alice in https://github.com/hooridev/HooriDB/pull/4
        """);
    var changes = Changes.of(update("0.1.0", older, newer));
    assertEquals(4, changes.count());
    assertEquals(3, changes.contributors());
    assertEquals(
        List.of("Newer feature.", "Older feature.", "Newer fix.", "Older fix."),
        changes.highlights());
  }

  @Test
  void capsFeaturesAtFiveWithoutAddingFixes() {
    var notes = """
        ## What's Changed
        * feat: first by @alice in https://github.com/hooridev/HooriDB/pull/1
        * feat: second by @alice in https://github.com/hooridev/HooriDB/pull/2
        * feat: third by @alice in https://github.com/hooridev/HooriDB/pull/3
        * feat: fourth by @alice in https://github.com/hooridev/HooriDB/pull/4
        * feat: fifth by @alice in https://github.com/hooridev/HooriDB/pull/5
        * feat: sixth by @alice in https://github.com/hooridev/HooriDB/pull/6
        * fix: ignored because five features filled the list by @bob in https://github.com/hooridev/HooriDB/pull/7
        """;
    var changes = Changes.of(update("0.1.0", release("0.2.0", notes)));
    assertEquals(7, changes.count());
    assertEquals(2, changes.contributors());
    assertEquals(List.of("First.", "Second.", "Third.", "Fourth.", "Fifth."), changes.highlights());
  }

  @Test
  void preservesTerminalPunctuationAndInlineMarkdown() {
    var notes = """
        ## What's Changed
        * feat: already ends in a period. by @alice in https://github.com/hooridev/HooriDB/pull/1
        * feat: works fast! by @alice in https://github.com/hooridev/HooriDB/pull/2
        * fix: why did it hang? by @alice in https://github.com/hooridev/HooriDB/pull/3
        * feat: `WHERE` and `ORDER BY` fields by @alice in https://github.com/hooridev/HooriDB/pull/4
        * fix: auth outside admin (`authSource`) by @alice in https://github.com/hooridev/HooriDB/pull/5
        """;
    var changes = Changes.of(update("0.1.0", release("0.2.0", notes)));
    assertEquals(
        List.of(
            "Already ends in a period.",
            "Works fast!",
            "`WHERE` and `ORDER BY` fields.",
            "Why did it hang?",
            "Auth outside admin (`authSource`)."),
        changes.highlights());
  }

  @Test
  void countsChangesEvenWhenNoneAreHighlights() {
    var notes = """
        ## What's Changed
        * docs: describe updates on the website by @alice in https://github.com/hooridev/HooriDB/pull/1
        * chore: bump dependencies by @bob in https://github.com/hooridev/HooriDB/pull/2
        """;
    var changes = Changes.of(update("0.1.0", release("0.2.0", notes)));
    assertFalse(changes.isEmpty());
    assertEquals(List.of(), changes.highlights());
    assertEquals(2, changes.count());
    assertEquals(2, changes.contributors());
  }

  @Test
  void emptyOrUnmatchedNotesProduceEmptyChanges() {
    var empty = Changes.of(update("0.1.0", release("0.2.0", "")));
    assertTrue(empty.isEmpty());
    assertEquals(0, empty.count());
    assertEquals(0, empty.contributors());
    assertEquals(List.of(), empty.highlights());

    var prose = release("0.2.0", """
        Some introductory text without pull request entries.

        ## New Contributors
        * @alice made their first contribution in https://github.com/hooridev/HooriDB/pull/1

        **Full Changelog**: https://github.com/hooridev/HooriDB/compare/v0.1.0...v0.2.0
        """);
    var unmatched = Changes.of(update("0.1.0", prose));
    assertTrue(unmatched.isEmpty());
    assertEquals(0, unmatched.count());
    assertEquals(0, unmatched.contributors());
  }
}
