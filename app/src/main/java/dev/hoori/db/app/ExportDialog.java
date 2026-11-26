package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.file.Encoding;
import dev.hoori.db.core.file.Export;
import dev.hoori.db.core.file.FileProblem;
import dev.hoori.db.core.file.Format;
import dev.hoori.db.core.file.LineEnd;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Export data: the rows of tables and views of a schema, or of a database without schemas, each
 * into a file of its own in a folder, in a format with its options. It lists the tables and
 * writes them on a session of its own, read-only at the server, never the tree's; each table is
 * read in one go and written as its rows come, with its progress, and Cancel gives up the table
 * that is written, whose file is not left behind, and those after it.
 */
final class ExportDialog {

  /** The formats a file can have. */
  enum Kind {
    CSV("CSV"),
    TSV("TSV"),
    JSON("JSON array"),
    LINES("JSON Lines");

    final String label;

    Kind(String label) {
      this.label = label;
    }

    boolean delimited() {
      return this == CSV || this == TSV;
    }
  }

  /** What a table's export came to. */
  sealed interface Outcome {
    Table table();

    record Written(Table table, String file, long rows) implements Outcome {}

    record Failed(Table table, String file, String why) implements Outcome {}
  }

  final UUID id;
  final Container container;
  private final Table first; // the table checked alone at first, or null for all
  private Lane lane; // the dialog's own session, or null once closed
  private char[] password; // the session's copy, wiped as it closes

  List<Table> tables; // as the session read them, null while it reads
  String problem; // why it cannot go on, or null
  final Set<Table> checked = new LinkedHashSet<>();

  Kind kind = Kind.CSV;
  final Options options = new Options();
  Path folder;

  // The columns of the one table checked, and those chosen, in the table's order; null while not
  // read or not one.
  private Table columnsOf;
  List<Column> columns;
  final SortedSet<Integer> chosen = new TreeSet<>();

  // The export as it runs, and what it came to.
  private Lane.Asked running;
  private int runs; // each export's number, so that what an earlier one posts is dropped
  private int at; // the table it writes, from 0
  private long rows; // the rows written of that table
  final List<Outcome> outcomes = new ArrayList<>();
  boolean cancelled;
  private List<Table> plan = List.of();

  final Menu.State kindMenu = new Menu.State();
  final Menu.State delimiterMenu = new Menu.State();
  final Menu.State quoteMenu = new Menu.State();
  final Menu.State quotingMenu = new Menu.State();
  final Menu.State encodingMenu = new Menu.State();
  final Menu.State lineEndMenu = new Menu.State();

  private ExportDialog(UUID id, Container container, Table first) {
    this.id = id;
    this.container = container;
    this.first = first;
  }

  /**
   * The dialog for the container's tables, with the given one checked alone, or all where it is
   * null; it opens its session and reads the tables at once.
   */
  static ExportDialog open(State s, UUID id, Container container, Table first) {
    var dialog = new ExportDialog(id, container, first);
    var session = s.catalog.session(s, id, container.database().name(), true);
    if (session == null) {
      dialog.problem = "The connection is not connected.";
      return dialog;
    }
    dialog.lane = session.lane();
    dialog.password = session.password();
    dialog.lane.ask(
        s.app,
        "read the tables to export",
        Lane.Priority.VIEW,
        link -> link.tables(container),
        read -> dialog.listed(s, read),
        e -> dialog.problem = "The tables could not be read: " + words(s, dialog.id, e));
    return dialog;
  }

  private void listed(State s, List<Table> read) {
    tables = List.copyOf(read);
    checked.clear();
    for (var table : tables) {
      if (first == null || table.equals(first)) {
        checked.add(table);
      }
    }
    columnsFor(s);
  }

  /** Reads the columns of the one table checked, where one is, for the choice of its columns. */
  private void columnsFor(State s) {
    var only = checked.size() == 1 ? checked.iterator().next() : null;
    if (only == null) {
      columnsOf = null;
      columns = null;
      chosen.clear();
      return;
    }
    if (only.equals(columnsOf) || lane == null) {
      return;
    }
    columnsOf = only;
    columns = null;
    chosen.clear();
    lane.ask(
        s.app,
        "read the columns to export",
        Lane.Priority.VIEW,
        link -> link.columns(only),
        read -> {
          if (only.equals(columnsOf)) {
            columns = List.copyOf(read);
            IntStream.range(0, read.size()).forEach(chosen::add);
          }
        },
        e -> {
          if (only.equals(columnsOf)) {
            problem = "The columns of " + only.name() + " could not be read: " + words(s, id, e);
          }
        });
  }

  private static String words(State s, UUID id, Exception e) {
    var detail = switch (e) {
      case QueryException q -> q.detail().isEmpty() ? q.getMessage() : q.detail();
      case CatalogException c -> c.getMessage();
      case IOException io -> FileProblem.of(io);
      default -> e.getMessage() == null ? e.toString() : e.getMessage();
    };
    return words(s, id, detail);
  }

  // The server's words, unless they hold the connection's password.
  private static String words(State s, UUID id, String detail) {
    return CatalogTree.words(s.catalog.live(id), detail);
  }

  /** The format with the options chosen. */
  Format format() {
    return switch (kind) {
      case CSV -> options.delimited(options.delimiter.character);
      case TSV -> options.delimited('\t');
      case JSON -> Format.Json.ARRAY.withLineEnd(options.lineEnd);
      case LINES -> Format.Json.LINES.withLineEnd(options.lineEnd);
    };
  }

  /** The tables to export, in the order listed. */
  private List<Table> planned() {
    return tables == null
        ? List.of()
        : tables.stream().filter(checked::contains).toList();
  }

  boolean running() {
    return running != null;
  }

  /** Why Export cannot start, or null where it can. */
  String missing() {
    if (tables == null || lane == null) {
      return "";
    }
    if (checked.isEmpty()) {
      return "Check a table to export.";
    }
    if (checked.size() == 1 && (columns == null || chosen.isEmpty())) {
      return columns == null ? "" : "Check a column to export.";
    }
    if (folder == null) {
      return "Choose the folder to export into.";
    }
    try {
      format();
    } catch (IllegalArgumentException e) {
      return "NULL as cannot hold the delimiter, the quote character or a line end.";
    }
    return null;
  }

  /** Chooses the folder in the system's dialog. */
  void chooseFolder(State s) {
    var dialog = FileDialog.chooseFolder("Export into folder");
    s.app.fileDialog(
        folder == null ? dialog : dialog.in(folder),
        choice -> {
          if (!choice.cancelled()) {
            folder = choice.path();
          }
        },
        error -> problem = "The folder could not be chosen: " + error);
  }

  /** Writes each table checked into its file, one after another, on the dialog's session. */
  void export(State s) {
    if (missing() != null || running()) {
      return;
    }
    plan = planned();
    var format = format();
    var names = Export.fileNames(plan, format);
    var into = folder;
    var single = plan.size() == 1 && columns != null ? List.copyOf(columns) : null;
    var picked = List.copyOf(chosen);
    var app = s.app;
    int run = ++runs;
    outcomes.clear();
    cancelled = false;
    at = 0;
    rows = 0;
    var todo = plan;
    running = lane.ask(
        app,
        "export tables",
        Lane.Priority.VIEW,
        link -> {
          for (int i = 0; i < todo.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
              throw new InterruptedException("The export was cancelled");
            }
            var table = todo.get(i);
            var name = names.get(i);
            int index = i;
            app.post(() -> started(run, index));
            Outcome outcome;
            try {
              var all = single != null ? single : link.columns(table);
              var these = single != null
                  ? picked
                  : IntStream.range(0, all.size()).boxed().toList();
              long written = Export.write(
                  into.resolve(name),
                  format,
                  each -> link.export(table, all, these, counting(app, run, each)));
              outcome = new Outcome.Written(table, name, written);
            } catch (QueryException e) {
              if (e.failure() == QueryException.Failure.CANCELLED
                  || e.failure() == QueryException.Failure.ABORTED) {
                throw e;
              }
              outcome = new Outcome.Failed(
                  table, name, e.detail().isEmpty() ? e.getMessage() : e.detail());
            } catch (CatalogException e) {
              if (e.failure() == CatalogException.Failure.CANCELLED
                  || e.failure() == CatalogException.Failure.ABORTED) {
                throw e;
              }
              outcome = new Outcome.Failed(table, name, e.getMessage());
            } catch (IOException e) {
              outcome = new Outcome.Failed(table, name, FileProblem.of(e));
            }
            var done = outcome;
            app.post(() -> finished(app.state(), run, done));
          }
          return null;
        },
        done -> {
          if (run == runs) {
            running = null;
          }
        },
        e -> {
          if (run == runs) {
            running = null;
            problem = "The export stopped: " + words(s, id, e);
          }
        });
  }

  // The rows as they go by, told now and then, not each one.
  private Rows.Each counting(App<State> app, int run, Rows.Each each) {
    return new Rows.Each() {
      private long count;
      private long told = System.nanoTime();

      @Override
      public void fields(List<Field> fields) throws IOException {
        each.fields(fields);
      }

      @Override
      public void row(List<Object> values) throws IOException {
        each.row(values);
        count++;
        var now = System.nanoTime();
        if (now - told > 100_000_000L) {
          told = now;
          var n = count;
          app.post(() -> {
            if (run == runs) {
              rows = n;
            }
          });
        }
      }
    };
  }

  private void started(int run, int index) {
    if (run == runs) {
      at = index;
      rows = 0;
    }
  }

  private void finished(State s, int run, Outcome outcome) {
    if (run == runs) {
      outcomes.add(
          switch (outcome) {
            case Outcome.Failed(var table, var file, var why) ->
              new Outcome.Failed(table, file, words(s, id, why));
            case Outcome.Written w -> w;
          });
    }
  }

  /** Gives up the export: the table it writes leaves no file, and those after it are not begun. */
  void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
      runs++;
      cancelled = true;
    }
  }

  /** Closes the dialog and its session, giving up what it runs. */
  void close(State s) {
    cancel();
    end(s);
    s.dialogs.export = null;
  }

  /** Ends its session, as Exit and the end of its connection do; the dialog stays as it was. */
  void end(State s) {
    if (running != null) {
      cancel();
    }
    if (lane != null) {
      s.closing();
      var closing = lane.close(ended -> {});
      s.app.background(
          "close the export's session",
          () -> {
            closing.join();
            return null;
          },
          done -> s.closed(),
          e -> s.closed());
      lane = null;
      if (problem == null) {
        problem = "The connection ended.";
      }
    }
    if (password != null) {
      Arrays.fill(password, '\0');
      password = null;
    }
  }

  // The view

  El view(State s) {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    var content = col().w(34 * unit).gap(t.space().m());
    if (problem != null) {
      content.child(
          Callout.callout(Callout.Kind.DANGER, "Cannot export", problem).key("problem"));
    }
    boolean busy = running();
    if (busy || !outcomes.isEmpty() || cancelled) {
      content.child(progress());
    }
    content.child(tablesList(s, unit));
    content.child(Form.form(Form.Labels.BESIDE, entries(s, busy)));
    var missing = missing();
    El export = button(PRIMARY, "Export", missing == null && !busy ? () -> export(s) : null)
        .key("export");
    El close = busy
        ? button(SECONDARY, "Cancel", this::cancel).key("export-cancel")
        : button(SECONDARY, "Close", () -> close(s)).key("export-close");
    return Dialog.dialog(
            "Export data",
            content,
            List.of(close, export),
            () -> close(s),
            Dialog.options().defaultButton(export))
        .key("export-dialog");
  }

  private El tablesList(State s, float unit) {
    Theme t = Theme.CURRENT.get();
    var heading =
        row().gap(t.space().s()).child(label("Tables of " + container.name()).grow(1));
    if (tables == null) {
      return col()
          .gap(t.space().s())
          .child(heading)
          .child(row()
              .gap(t.space().s())
              .role("status")
              .label("Reading the tables")
              .child(Progress.spinner())
              .child(label(Label.Variant.MUTED, "Reading the tables…")));
    }
    boolean free = !running();
    heading
        .child(button(SECONDARY, "All", free ? () -> all(s, true) : null).key("tables-all"))
        .child(button(SECONDARY, "None", free ? () -> all(s, false) : null).key("tables-none"));
    var list = col().gap(t.space().xs()).padding(t.space().s());
    if (tables.isEmpty()) {
      list.child(label(Label.Variant.MUTED, "It has no tables or views."));
    }
    for (var table : tables) {
      var name = table.kind() == Table.Kind.VIEW ? table.name() + " (view)" : table.name();
      list.child(checkbox(checked.contains(table), name, free ? () -> toggle(s, table) : null));
    }
    return col()
        .gap(t.space().s())
        .child(heading)
        .child(Card.flush(list.scroll().key("tables")).h(6 * unit));
  }

  private void all(State s, boolean on) {
    checked.clear();
    if (on) {
      checked.addAll(tables);
    }
    columnsFor(s);
  }

  private void toggle(State s, Table table) {
    if (!checked.remove(table)) {
      checked.add(table);
    }
    columnsFor(s);
  }

  private Form.Entry[] entries(State s, boolean busy) {
    var entries = new ArrayList<Form.Entry>();
    entries.add(Form.field(
        "Format",
        Select.select(
                List.of(Kind.values()), kind, k -> k.label, kindMenu, busy ? null : k -> kind(k))
            .key("format")
            .label("Format")));
    if (kind.delimited()) {
      entries.addAll(options.delimitedFields(this, busy, kind == Kind.CSV));
    } else {
      entries.add(options.lineEndField(this, busy));
    }
    entries.add(columnsField(busy));
    entries.add(folderField(s, busy));
    return entries.toArray(Form.Entry[]::new);
  }

  // A new format takes the line ends its files usually have.
  private void kind(Kind k) {
    if (k.delimited() != kind.delimited()) {
      options.lineEnd = k.delimited() ? LineEnd.CRLF : LineEnd.LF;
    }
    kind = k;
  }

  private Form.Field columnsField(boolean busy) {
    Theme t = Theme.CURRENT.get();
    if (checked.size() != 1) {
      return Form.field(
          "Columns", label(Label.Variant.MUTED, "All columns of each table").key("columns"));
    }
    if (columns == null) {
      return Form.field(
          "Columns",
          row()
              .gap(t.space().s())
              .role("status")
              .label("Reading the columns")
              .child(Progress.spinner())
              .child(label(Label.Variant.MUTED, "Reading the columns…")));
    }
    var list = col().gap(t.space().xs()).padding(t.space().s());
    for (int i = 0; i < columns.size(); i++) {
      int index = i;
      var column = columns.get(i);
      list.child(checkbox(
          chosen.contains(i),
          column.name(),
          busy
              ? null
              : () -> {
                if (!chosen.remove(index)) {
                  chosen.add(index);
                }
              }));
    }
    return Form.field(
        "Columns",
        Card.flush(list.scroll().key("columns")).h(4 * t.size().control()).wFull());
  }

  private Form.Field folderField(State s, boolean busy) {
    Theme t = Theme.CURRENT.get();
    var names = planned().isEmpty() ? List.<String>of() : Export.fileNames(planned(), formatOr());
    var help = names.isEmpty()
        ? null
        : "Writes " + String.join(", ", names.size() > 3 ? names.subList(0, 3) : names)
            + (names.size() > 3 ? " and " + (names.size() - 3) + " more" : "")
            + ". A file of the same name is replaced once the new one is whole.";
    var field = Form.field(
        "Folder",
        row()
            .wFull()
            .gap(t.space().s())
            .child(label(folder == null ? "None chosen" : folder.toString())
                .key("folder")
                .grow(1))
            .child(button(SECONDARY, "Choose…", busy ? null : () -> chooseFolder(s))
                .key("choose-folder")
                .label("Choose folder")));
    return help == null ? field : field.help(help);
  }

  // The format, or CSV's where the options cannot make one, for the names of the files.
  private Format formatOr() {
    try {
      return format();
    } catch (IllegalArgumentException _) {
      return Format.Delimited.CSV;
    }
  }

  private El progress() {
    Theme t = Theme.CURRENT.get();
    var box = col().wFull().gap(t.space().s()).key("export-progress");
    int total = plan.size();
    if (running()) {
      var table = at < total ? plan.get(at).name() : "";
      box.child(Progress.labeled(
              "Exporting " + table + " (" + (at + 1) + " of " + total + ")",
              total == 0 ? 0 : (double) outcomes.size() / total,
              ConsoleTab.rows(rows) + " written")
          .wFull());
    }
    long written = 0;
    var failed = new ArrayList<Outcome.Failed>();
    int files = 0;
    for (var outcome : outcomes) {
      switch (outcome) {
        case Outcome.Written w -> {
          written += w.rows();
          files++;
        }
        case Outcome.Failed f -> failed.add(f);
      }
    }
    if (!running()) {
      var where = folder == null ? "" : " in " + folder;
      var summary = files + (files == 1 ? " table" : " tables") + ", " + ConsoleTab.rows(written)
          + ", written" + where + ".";
      if (cancelled) {
        box.child(Callout.callout(
                Callout.Kind.WARNING,
                "Export cancelled",
                (files == 0 ? "No table was written." : summary)
                    + " The table it was writing left no file.")
            .key("export-outcome"));
      } else {
        box.child(Callout.callout(
                failed.isEmpty() ? Callout.Kind.SUCCESS : Callout.Kind.WARNING,
                failed.isEmpty() ? "Exported" : "Exported with failures",
                summary)
            .key("export-outcome"));
      }
    }
    for (var f : failed) {
      box.child(
          Callout.callout(Callout.Kind.DANGER, f.table().name() + " was not exported", f.why()));
    }
    return box;
  }

  /** The options of delimited text and line ends, which the import's dialog shares in part. */
  static final class Options {

    /** The delimiters of CSV that can be chosen; TSV has the tab. */
    enum Delimiter {
      COMMA(',', "Comma"),
      SEMICOLON(';', "Semicolon"),
      PIPE('|', "Pipe"),
      TAB('\t', "Tab");

      final char character;
      final String label;

      Delimiter(char character, String label) {
        this.character = character;
        this.label = label;
      }
    }

    Delimiter delimiter = Delimiter.COMMA;
    char quote = '"';
    Format.Quoting quoting = Format.Quoting.AS_NEEDED;
    boolean header = true;
    String nullText = "";
    Encoding encoding = Encoding.UTF_8;
    LineEnd lineEnd = LineEnd.CRLF;

    Format.Delimited delimited(char delimiter) {
      return new Format.Delimited(delimiter, quote, quoting, header, nullText, encoding, lineEnd);
    }

    private List<Form.Entry> delimitedFields(ExportDialog d, boolean busy, boolean csv) {
      var fields = new ArrayList<Form.Entry>();
      if (csv) {
        fields.add(Form.field(
            "Delimiter",
            Select.select(
                    List.of(Delimiter.COMMA, Delimiter.SEMICOLON, Delimiter.PIPE),
                    delimiter == Delimiter.TAB ? Delimiter.COMMA : delimiter,
                    x -> x.label,
                    d.delimiterMenu,
                    busy ? null : x -> delimiter = x)
                .key("delimiter")
                .label("Delimiter")));
      }
      fields.add(Form.field(
          "Header",
          checkbox(header, "First row names the columns", busy ? null : () -> header = !header)
              .key("header")));
      fields.add(Form.field(
          "Quote",
          Select.select(
                  List.of('"', '\''),
                  quote,
                  c -> c == '"' ? "Double quote \"" : "Single quote '",
                  d.quoteMenu,
                  busy ? null : c -> quote = c)
              .key("quote")
              .label("Quote")));
      fields.add(Form.field(
          "Quoting",
          Select.select(
                  List.of(Format.Quoting.values()),
                  quoting,
                  q -> q == Format.Quoting.ALL ? "Every value but NULL" : "Where needed",
                  d.quotingMenu,
                  busy ? null : q -> quoting = q)
              .key("quoting")
              .label("Quoting")));
      fields.add(Form.field(
              "NULL as",
              TextField.textField(nullText, "Empty", busy ? null : v -> nullText = v)
                  .key("null-text")
                  .label("NULL as")
                  .wFull())
          .help("An unquoted value of this text is NULL; a text equal to it is quoted."));
      fields.add(Form.field(
          "Encoding",
          Select.select(
                  List.of(Encoding.values()),
                  encoding,
                  Encoding::label,
                  d.encodingMenu,
                  busy ? null : e -> encoding = e)
              .key("encoding")
              .label("Encoding")));
      fields.add(lineEndField(d, busy));
      return fields;
    }

    private Form.Field lineEndField(ExportDialog d, boolean busy) {
      return Form.field(
          "Line ends",
          Select.select(
                  List.of(LineEnd.values()),
                  lineEnd,
                  e -> e == LineEnd.CRLF ? "CRLF (Windows)" : "LF (Linux, macOS)",
                  d.lineEndMenu,
                  busy ? null : e -> lineEnd = e)
              .key("line-ends")
              .label("Line ends"));
    }
  }
}
