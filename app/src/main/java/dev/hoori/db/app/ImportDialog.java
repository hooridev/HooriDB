package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Radio;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.ValueField;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Inserts;
import dev.hoori.db.core.file.Encoding;
import dev.hoori.db.core.file.FileProblem;
import dev.hoori.db.core.file.Format;
import dev.hoori.db.core.file.Import;
import dev.hoori.db.core.file.LineEnd;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Import data: the rows of a file of CSV or TSV into a table that exists, with a preview of its
 * first rows as the options read them and each column of the table given a column of the file or
 * left to its default. The rows go in batches on a session of its own, never the tree's, as the
 * options say; a read-only connection refuses the import before anything is sent.
 */
final class ImportDialog {

  /** How many rows of the file the preview shows. */
  static final int PREVIEWED = 20;

  /** A column of the table that no column of the file goes into. */
  static final int NONE = -1;

  final UUID id;
  final Table table;
  final boolean readOnly;
  private Lane lane;
  private char[] password;

  List<Column> columns; // the table's, null while they are read
  String problem;

  Path file;
  final ExportDialog.Options options = new ExportDialog.Options();
  Import.Preview preview; // of the file as the options read it, null while none
  String unreadable; // why the preview could not be read
  private int previews; // each preview's number, so that an earlier one is dropped
  private int previewed; // the number of the preview shown, or of the one that failed
  int[] mapping = new int[0]; // per column of the table, the column of the file, or NONE

  Inserts.Options writing = Inserts.Options.DEFAULT;
  final ValueField.Draft batchDraft = new ValueField.Draft();

  private Lane.Stoppable running;
  Inserts.Count count = Inserts.Count.NONE;
  Inserts.Report report;
  boolean cancelled;

  final Menu.State delimiterMenu = new Menu.State();
  final Menu.State quoteMenu = new Menu.State();
  final Menu.State encodingMenu = new Menu.State();
  private final Map<Integer, Menu.State> mappingMenus = new HashMap<>();
  final DataGrid.Selection previewSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection failedSelection = new DataGrid.Selection(DataGrid.Mode.ROWS);

  private ImportDialog(UUID id, Table table, boolean readOnly) {
    this.id = id;
    this.table = table;
    this.readOnly = readOnly;
    options.lineEnd = LineEnd.CRLF;
  }

  /**
   * The dialog for the table: on a read-only connection one that only says so; else it opens its
   * session and reads the table's columns at once.
   */
  static ImportDialog open(State s, UUID id, Table table) {
    var profile = s.profile(id);
    var dialog = new ImportDialog(id, table, profile != null && profile.readOnly());
    if (dialog.readOnly) {
      return dialog;
    }
    var session = s.catalog.session(s, id, table.container().database().name());
    if (session == null) {
      dialog.problem = "The connection is not connected.";
      return dialog;
    }
    dialog.lane = session.lane();
    dialog.password = session.password();
    dialog.lane.ask(
        s.app,
        "read the columns to import into",
        Lane.Priority.VIEW,
        link -> link.columns(table),
        read -> {
          dialog.columns = List.copyOf(read);
          dialog.mapping = new int[read.size()];
          Arrays.fill(dialog.mapping, NONE);
          dialog.map();
        },
        e -> dialog.problem = "The columns could not be read: " + words(s, id, e));
    return dialog;
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

  /** The file's format, as the options say; null where they cannot make one. */
  Format.Delimited format() {
    try {
      return options.delimited(options.delimiter.character);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /** Chooses the file in the system's dialog, and reads its preview. */
  void chooseFile(State s) {
    var dialog = FileDialog.open("Import from file")
        .filter("Delimited text", "csv", "tsv", "txt")
        .filter("All files", "*");
    s.app.fileDialog(
        file == null ? dialog : dialog.in(file.toAbsolutePath().getParent()),
        choice -> {
          if (!choice.cancelled()) {
            file = choice.path();
            var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            options.delimiter = name.endsWith(".tsv")
                ? ExportDialog.Options.Delimiter.TAB
                : options.delimiter == ExportDialog.Options.Delimiter.TAB
                    ? ExportDialog.Options.Delimiter.COMMA
                    : options.delimiter;
            preview(s);
          }
        },
        error -> problem = "The file could not be chosen: " + error);
  }

  /**
   * Reads the first rows of the file as the options read them now, off the app's thread; the
   * preview shown, and the mapping made for it, stay until it comes, but Import waits for it.
   */
  void preview(State s) {
    var format = format();
    int number = ++previews;
    if (file == null || format == null) {
      previewed = number;
      preview = null;
      unreadable = format == null && file != null
          ? "NULL as cannot hold the delimiter, the quote character or a line end."
          : null;
      return;
    }
    var at = file;
    s.app.background(
        "preview a file to import",
        () -> Import.preview(at, format, PREVIEWED),
        read -> {
          if (number == previews) {
            previewed = number;
            var before = preview == null ? null : preview.names();
            preview = read;
            unreadable = null;
            if (!read.names().equals(before)) {
              map();
            }
          }
        },
        e -> {
          if (number == previews) {
            previewed = number;
            preview = null;
            unreadable = e instanceof IOException io
                ? FileProblem.of(io)
                : e.getMessage() == null ? e.toString() : e.getMessage();
          }
        });
  }

  /** Whether the preview shown is not yet of the options as they are now. */
  boolean previewing() {
    return previewed != previews;
  }

  // Each column of the table takes the file's column of its name, in any case, where the file
  // names them, else the one at its position.
  private void map() {
    if (columns == null) {
      return;
    }
    Arrays.fill(mapping, NONE);
    if (preview == null) {
      return;
    }
    var names = preview.names();
    for (int i = 0; i < columns.size(); i++) {
      var name = columns.get(i).name();
      if (options.header) {
        for (int f = 0; f < names.size(); f++) {
          if (names.get(f).strip().equalsIgnoreCase(name)) {
            mapping[i] = f;
            break;
          }
        }
      } else if (i < names.size()) {
        mapping[i] = i;
      }
    }
  }

  boolean running() {
    return running != null;
  }

  /** Why Import cannot start, or null where it can. */
  String missing() {
    if (readOnly || lane == null || columns == null) {
      return "";
    }
    if (file == null) {
      return "Choose the file to import.";
    }
    if (previewing()) {
      return "";
    }
    if (preview == null) {
      return unreadable == null ? "" : unreadable;
    }
    if (Arrays.stream(mapping).allMatch(f -> f == NONE)) {
      return "Give a column of the table a column of the file.";
    }
    return null;
  }

  /** Writes the file's rows into the table on the dialog's session. */
  void start(State s) {
    if (missing() != null || running()) {
      return;
    }
    var format = format();
    var targets = IntStream.range(0, mapping.length)
        .filter(i -> mapping[i] != NONE)
        .boxed()
        .toList();
    var from = targets.stream().map(i -> mapping[i]).toList();
    var all = columns;
    var at = file;
    var how = writing;
    var app = s.app;
    var into = table;
    report = null;
    cancelled = false;
    count = Inserts.Count.NONE;
    // What it posts arrives before its outcome, and a new import begins only after that.
    running = lane.askStoppable(
        app,
        "import a file",
        Lane.Priority.VIEW,
        link -> {
          try (var rows = Import.open(at, format, from)) {
            return link.insert(into, all, targets, rows, how, now -> app.post(() -> count = now));
          }
        },
        done -> {
          running = null;
          report = done;
          count = done.count();
          cancelled = done.cancelled();
          imported(s);
        },
        e -> {
          var stopped = running.stopped();
          running = null;
          if (stopped && cancelling(e)) {
            cancelled = true;
          } else {
            problem = "The import stopped: " + words(s, id, e)
                + (count.committed() == 0
                    ? " Nothing was imported."
                    : " Earlier batches committed " + ConsoleTab.rows(count.committed())
                        + ", which stay.");
          }
          imported(s);
        });
  }

  // Whether the failure is how a cancel ended.
  private static boolean cancelling(Exception e) {
    return e instanceof InterruptedException
        || e instanceof QueryException q
            && (q.failure() == QueryException.Failure.CANCELLED
                || q.failure() == QueryException.Failure.ABORTED);
  }

  // The tabs of the table read it anew where rows were committed.
  private void imported(State s) {
    if (count.committed() > 0) {
      s.imported(id, table);
    }
  }

  /** Whether Cancel was pressed, and the import has not yet said what it came to. */
  boolean cancelling() {
    return running != null && running.stopped();
  }

  /**
   * Cancels the import: what its transaction wrote is rolled back, and once that is done it says
   * what it committed, as a commit that the cancel came too late for.
   */
  void cancel() {
    if (running != null) {
      running.stop();
    }
  }

  void close(State s) {
    cancel();
    end(s);
    s.dialogs.importing = null;
  }

  /** Ends its session, as Exit and the end of its connection do. */
  void end(State s) {
    cancel();
    if (lane != null) {
      s.closing();
      var closing = lane.close(ended -> {});
      s.app.background(
          "close the import's session",
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
    var content = col().w(36 * unit).gap(t.space().m());
    Runnable close = () -> close(s);
    if (readOnly) {
      content.child(Callout.callout(
              Callout.Kind.WARNING,
              "The connection is read-only",
              "Nothing can be imported into " + table.name() + " on it. Edit the connection to"
                  + " make it writable.")
          .key("read-only"));
      El ok = button(PRIMARY, "Close", close).key("import-close");
      return Dialog.dialog("Import into " + table.name(), content, List.of(ok), ok, close)
          .key("import-dialog");
    }
    if (problem != null) {
      content.child(
          Callout.callout(Callout.Kind.DANGER, "Cannot import", problem).key("problem"));
    }
    boolean busy = running();
    if (busy || report != null || cancelled) {
      content.child(progress(s, unit));
    }
    var file = Form.field(
        "File",
        row()
            .wFull()
            .gap(t.space().s())
            .child(label(this.file == null ? "None chosen" : this.file.toString())
                .key("file")
                .grow(1))
            .child(button(SECONDARY, "Choose…", busy ? null : () -> chooseFile(s))
                .key("choose-file")
                .label("Choose file")));
    // One width of labels, so that the forms line up.
    var reading = readingFields(s, file, busy);
    var mapped = columns != null && preview != null ? mappingFields(busy) : new Form.Entry[0];
    var written = columns != null && preview != null ? writingFields(busy) : new Form.Entry[0];
    var all = new ArrayList<Form.Entry>(List.of(reading));
    all.addAll(List.of(mapped));
    all.addAll(List.of(written));
    float labels = Form.labelWidth(all.toArray(Form.Entry[]::new));
    content.child(Form.form(Form.Labels.BESIDE, labels, reading));
    content.child(previewView(unit));
    if (mapped.length > 0) {
      content.child(label("Columns of " + table.name()));
      content.child(Form.form(Form.Labels.BESIDE, labels, mapped));
      content.child(Form.form(Form.Labels.BESIDE, labels, written));
    }
    var missing = missing();
    El start = button(PRIMARY, "Import", missing == null && !busy ? () -> start(s) : null)
        .key("import");
    El end = busy
        ? button(SECONDARY, "Cancel", cancelling() ? null : this::cancel).key("import-cancel")
        : button(SECONDARY, "Close", close).key("import-close");
    return Dialog.dialog(
            "Import into " + table.name(),
            content,
            List.of(end, start),
            close,
            Dialog.options().defaultButton(start))
        .key("import-dialog");
  }

  private Form.Entry[] readingFields(State s, Form.Field file, boolean busy) {
    Runnable again = () -> preview(s);
    return new Form.Entry[] {
      file,
      Form.field(
          "Delimiter",
          Select.select(
                  List.of(ExportDialog.Options.Delimiter.values()),
                  options.delimiter,
                  d -> d.label,
                  delimiterMenu,
                  busy
                      ? null
                      : d -> {
                        options.delimiter = d;
                        again.run();
                      })
              .key("delimiter")
              .label("Delimiter")),
      Form.field(
          "Header",
          checkbox(
                  options.header,
                  "First row names the columns",
                  busy
                      ? null
                      : () -> {
                        options.header = !options.header;
                        again.run();
                      })
              .key("header")),
      Form.field(
          "Quote",
          Select.select(
                  List.of('"', '\''),
                  options.quote,
                  c -> c == '"' ? "Double quote \"" : "Single quote '",
                  quoteMenu,
                  busy
                      ? null
                      : c -> {
                        options.quote = c;
                        again.run();
                      })
              .key("quote")
              .label("Quote")),
      Form.field(
          "Encoding",
          Select.select(
                  List.of(Encoding.values()),
                  options.encoding,
                  Encoding::label,
                  encodingMenu,
                  busy
                      ? null
                      : e -> {
                        options.encoding = e;
                        again.run();
                      })
              .key("encoding")
              .label("Encoding")),
      Form.field(
              "NULL as",
              TextField.textField(
                      options.nullText,
                      "Empty",
                      busy
                          ? null
                          : v -> {
                            options.nullText = v;
                            again.run();
                          })
                  .key("null-text")
                  .label("NULL as")
                  .wFull())
          .help("An unquoted value of this text is NULL; a quoted one is the text.")
    };
  }

  private El previewView(float unit) {
    if (unreadable != null) {
      return Callout.callout(Callout.Kind.DANGER, "The file cannot be read so", unreadable)
          .key("unreadable");
    }
    if (preview == null) {
      return label(
              Label.Variant.MUTED,
              file == null ? "Choose a file to see its first rows." : "Reading the file…")
          .key("preview-note");
    }
    var names = preview.names();
    var rows = preview.rows();
    var shown = new ArrayList<DataGrid.Column>();
    for (int c = 0; c < names.size(); c++) {
      int column = c;
      shown.add(new DataGrid.Column(
              names.get(c).isEmpty() ? "Column " + (c + 1) : names.get(c),
              5 * unit,
              DataGrid.DataType.TEXT,
              r -> {
                var values = rows.get(r);
                if (column >= values.size()) {
                  return "";
                }
                return values.get(column) == null ? "NULL" : values.get(column);
              })
          .sortable(false));
    }
    var grid = DataGrid.dataGrid(shown, rows.size(), DataGrid.options().select(previewSelection))
        .key("preview")
        .label("Preview of the file")
        .grow(1);
    return Card.flush(grid).wFull().h(6 * unit);
  }

  private Form.Entry[] mappingFields(boolean busy) {
    var names = preview.names();
    var choices = new ArrayList<Integer>();
    choices.add(NONE);
    IntStream.range(0, names.size()).forEach(choices::add);
    var fields = new Form.Entry[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      int index = i;
      var column = columns.get(i);
      var menu = mappingMenus.computeIfAbsent(i, k -> new Menu.State());
      fields[i] = Form.field(
          column.name(),
          Select.select(
                  choices,
                  mapping[i] < names.size() ? mapping[i] : NONE,
                  f -> f == NONE
                      ? "Not imported: its default"
                      : names.get(f).isEmpty() ? "Column " + (f + 1) : names.get(f),
                  menu,
                  busy ? null : f -> mapping[index] = f)
              .key("map-" + i)
              .label(column.name())
              .w(12 * Theme.CURRENT.get().size().control()));
    }
    return fields;
  }

  private Form.Entry[] writingFields(boolean busy) {
    return new Form.Entry[] {
      Form.field(
          "Before",
          Radio.radioGroup(
                  List.of(false, true),
                  writing.empty(),
                  empty -> empty ? "Empty the table first" : "Keep its rows",
                  busy ? null : empty -> writing = writing.withEmpty(empty))
              .key("before")),
      Form.field(
          "Transaction",
          Radio.radioGroup(
                  List.of(Inserts.Commit.values()),
                  writing.commit(),
                  c -> c == Inserts.Commit.ONCE ? "All rows or none" : "Commit each batch",
                  busy ? null : c -> writing = writing.withCommit(c))
              .key("transaction")),
      Form.field(
              "Batch",
              ValueField.valueField(
                      com.rethinkj.ui.kit.Format.INTEGER,
                      writing.batch(),
                      batchDraft,
                      busy
                          ? null
                          : (Integer n) -> {
                            if (n != null && n >= 1 && n <= 100_000) {
                              writing = writing.withBatch(n);
                            }
                          })
                  .key("batch")
                  .label("Batch"))
          .help("Rows of each INSERT, from 1 to 100,000."),
      Form.field(
          "On error",
          Radio.radioGroup(
                  List.of(Inserts.OnError.values()),
                  writing.onError(),
                  e -> e == Inserts.OnError.STOP ? "Stop at the row" : "Skip the row and go on",
                  busy ? null : e -> writing = writing.withOnError(e))
              .key("on-error"))
    };
  }

  private El progress(State s, float unit) {
    Theme t = Theme.CURRENT.get();
    var box = col().gap(t.space().s()).key("import-progress");
    if (running()) {
      var counted = ConsoleTab.rows(count.read()) + " read, " + ConsoleTab.rows(count.written())
          + " written, " + ConsoleTab.rows(count.committed()) + " committed";
      box.child(row()
          .gap(t.space().s())
          .role("status")
          .label(cancelling() ? "Cancelling the import" : "Importing")
          .child(Progress.spinner())
          .child(label(cancelling() ? "Cancelling… " + counted : counted)));
      return box;
    }
    var c = count;
    if (cancelled) {
      box.child(Callout.callout(
              Callout.Kind.WARNING,
              "Import cancelled",
              c.committed() == 0
                  ? "Nothing was imported."
                  : "Earlier batches committed " + ConsoleTab.rows(c.committed()) + ".")
          .key("import-outcome"));
    } else if (report.stopped()) {
      var stop = report.stoppedAt();
      box.child(Callout.callout(
              Callout.Kind.DANGER,
              "Stopped at line " + stop.line(),
              words(s, id, stop.message()) + "\n"
                  + (c.committed() == 0
                      ? "Nothing was imported."
                      : "Earlier batches committed " + ConsoleTab.rows(c.committed()) + "."))
          .key("import-outcome"));
    } else {
      box.child(Callout.callout(
              c.failed() == 0 ? Callout.Kind.SUCCESS : Callout.Kind.WARNING,
              "Imported",
              ConsoleTab.rows(c.committed()) + " written into " + table.name()
                  + (c.failed() == 0 ? "." : ", " + ConsoleTab.rows(c.failed()) + " skipped."))
          .key("import-outcome"));
    }
    var failed = report == null ? List.<Inserts.Failed>of() : report.failed();
    if (!failed.isEmpty() && !(report.stopped() && failed.size() == 1)) {
      var columns = List.of(
          new DataGrid.Column(
                  "Line",
                  3 * unit,
                  DataGrid.DataType.INTEGER,
                  r -> Long.toString(failed.get(r).line()))
              .sortable(false),
          new DataGrid.Column(
                  "Why",
                  26 * unit,
                  DataGrid.DataType.TEXT,
                  r -> words(s, id, failed.get(r).message()))
              .sortable(false));
      box.child(label(
          c.failed() > failed.size()
              ? "The first " + failed.size() + " of " + c.failed() + " rows that failed"
              : "The rows that failed"));
      box.child(Card.flush(
              DataGrid.dataGrid(columns, failed.size(), DataGrid.options().select(failedSelection))
                  .key("failed")
                  .label("Rows that failed")
                  .grow(1))
          .wFull()
          .h(5 * unit));
    }
    return box;
  }
}
