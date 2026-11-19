package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.Completion;
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
  private final Completion.State completion = new Completion.State().openAfter(".");
  final Suggestions suggestions;

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
   * The statement failed: where the server says, the place is underlined, with the server's
   * {@code words} (withheld where they hold the password) at its mark, and the caret goes there,
   * unless the text has changed since it was sent.
   */
  void failed(String sql, Failure failure, Provider provider, String words) {
    Integer at = sent.get(sql);
    var place = at == null || provider == null || !sentFrom.equals(tab.text)
        ? null
        : failure.place(sql, provider).orElse(null);
    if (place == null) {
      return;
    }
    int from = at + place.start();
    var error = new CodeEditor.Diagnostic(from, at + place.end(), CodeEditor.Severity.ERROR, words);
    tab.editor.diagnostics(tab.text, List.of(error)).select(from, from);
    marked = tab.text;
  }

  /** The editor as built, with its completions. The underline goes where the text has changed. */
  El editor(State s, App<State> app, Provider provider, El editor) {
    if (marked != null && !marked.equals(tab.text)) {
      clear();
    }
    if (!completion.isOpen()) {
      suggestions.forget();
    }
    El completing = Completion.complete(
        app, editor, tab.editor.caret(), completion, word -> suggestions.at(s, provider, word));
    // Control+Space asks anew, with what was read since the list was made.
    var keys = completing.onKey();
    return completing.onKey(k -> {
      if (k.name().equals("Space") && k.modifiers() == Key.CONTROL) {
        suggestions.forget();
      }
      return keys != null && keys.test(k);
    });
  }

  /** The console closes: the reads for its completions are given up. */
  void close() {
    suggestions.close();
  }

  private void clear() {
    tab.editor.noDiagnostics();
    marked = null;
  }
}
