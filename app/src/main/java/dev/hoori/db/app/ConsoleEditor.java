package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.sql.Statement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a console's editor shows beyond the kit's: the place of the last run's error, underlined
 * in the text it was sent from while that text stays as it was, and completions from what the tree
 * read. Only the app's thread uses it.
 */
final class ConsoleEditor {

  private final ConsoleTab tab;
  // The statements of the last run, each the very string that was sent, and where it began in the
  // text: two statements of equal text are told apart.
  private final Map<String, Integer> sent = new IdentityHashMap<>();
  private String sentFrom; // the text they were taken from
  private String marked; // the text the underline is in, or null for none
  private Failure.Place underlined; // where in it, or null
  private El built; // the editor of the last build, and the app of its window
  private App<State> app;
  private final Completion.State completion = new Completion.State();
  private final Suggestions suggestions;

  ConsoleEditor(ConsoleTab tab) {
    this.tab = tab;
    suggestions = new Suggestions(tab);
  }

  /**
   * The statements to run, as the strings that go to the server, from the text where {@code from}
   * is where the statements' offsets count from; running drops the underline.
   */
  List<String> sent(String text, int from, List<Statement> statements) {
    clear();
    sent.clear();
    sentFrom = text;
    var sql = new ArrayList<String>(statements.size());
    for (var at : statements) {
      var one = text.substring(from + at.start(), from + at.end());
      sent.put(one, from + at.start());
      sql.add(one);
    }
    return sql;
  }

  /**
   * The statement failed: where the server says, the place is underlined and the caret goes
   * there, unless the text has changed since it was sent.
   */
  void failed(String sql, Failure failure) {
    Integer at = sent.get(sql);
    var place =
        at == null || !sentFrom.equals(tab.text) ? null : failure.place(sql).orElse(null);
    if (place == null) {
      return;
    }
    int from = at + place.start();
    underlined = new Failure.Place(from, at + place.end());
    tab.editor.error(from, underlined.end());
    marked = tab.text;
    if (built != null) {
      try {
        app.select(built, from, from);
      } catch (IllegalArgumentException e) {
        // shortcut: the editor is not in its window's last frame, as another tab is shown there,
        // so the caret stays; move it as the tab is shown once the kit can set it from the state.
      }
    }
  }

  /**
   * The editor as built, with its completions; it is kept for the caret. The underline goes where
   * the text has changed.
   */
  El editor(Theme t, State s, App<State> app, Provider provider, El editor) {
    if (marked != null && !marked.equals(tab.text)) {
      clear();
    }
    this.app = app;
    built = editor;
    return Completion.complete(
        t, app, editor, tab.editor.caret(), completion, word -> suggestions.at(s, provider, word));
  }

  /** The console closes: the reads for its completions are given up. */
  void close() {
    suggestions.close();
  }

  /** What the editor underlines, in the whole text, or null; the kit's state does not say. */
  Failure.Place underlined() {
    return underlined;
  }

  private void clear() {
    tab.editor.noError();
    marked = null;
    underlined = null;
  }
}
