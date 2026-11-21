package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.NumberField;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Radio;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.dump.Dump;
import dev.hoori.db.core.dump.Item;
import dev.hoori.db.core.dump.Listed;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Export to SQL: the objects of a database or schema, as a list to check, with what of them goes
 * (structure and data, structure or data), how, and the file it goes to. It reads and exports on a
 * session of its own, which it opens as it opens and closes as it closes, so that neither the tree
 * nor a tab waits for it; an export shows how far it is and can be cancelled, and a cancelled or
 * failed one leaves the chosen file as it was, as core writes it.
 */
final class ExportDialog {

  /** The most rows an {@code INSERT} may hold here. */
  static final int MOST_ROWS_PER_INSERT = 100_000;

  // How often an export's progress reaches the view.
  private static final long TOLD_EVERY = 100_000_000; // nanoseconds

  private static final String EXPORT = "Export";

  // Where an export is: Cancel wins until the file is whole, and once that replaces the chosen
  // one, nothing stops it.
  private enum Stage {
    RUNNING,
    CANCELLED,
    REPLACING
  }

  final UUID id;
  final Container on;
  final Provider provider;
  private final Lane lane;
  private final char[] password; // a copy, for keeping it out of what is shown; wiped at the close

  Lane.Asked listing; // the objects being read, or null
  List<Listed> objects; // as read, or null before
  String unread; // why they could not be read, or null
  final Set<Item> unchecked = new HashSet<>(); // all are checked at first
  String filter = "";
  Dump.Content content = Dump.Content.STRUCTURE_AND_DATA;
  boolean drop;
  boolean create;
  boolean keysAfterData = true;
  boolean transaction;
  boolean noForeignKeyChecks;
  int rowsPerInsert = Dump.ROWS_PER_INSERT;
  Path file; // the file chosen, or null

  Lane.Asked exporting; // the export under way, or null
  Item at; // what it writes now
  long atRows; // and how many of its rows so far
  long rowsBefore; // the rows of the tables before it
  int objectsBefore; // and how many objects
  private AtomicReference<Stage> stage = new AtomicReference<>(Stage.CANCELLED); // the last one's
  Dump.Written written; // what the last export wrote, or null
  String failed; // why it failed, or null
  boolean cancelled; // the last export was cancelled

  private ExportDialog(UUID id, Container on, Provider provider, Lane lane, char[] password) {
    this.id = id;
    this.on = on;
    this.provider = provider;
    this.lane = lane;
    this.password = password;
  }

  /**
   * The dialog of the connection's database or schema, with a session of its own on its database,
   * reading its objects; null while the connection is not connected.
   */
  static ExportDialog open(State s, UUID id, Container on) {
    var profile = s.profile(id);
    var session = s.catalog.session(id, on.database().name());
    if (profile == null || session == null) {
      return null;
    }
    var dialog = new ExportDialog(id, on, profile.provider(), session.lane(), session.password());
    dialog.list(s);
    return dialog;
  }

  /** Reads the objects, anew where they were read. */
  void list(State s) {
    if (listing != null) {
      return;
    }
    unread = null;
    listing = lane.ask(
        s.app,
        "read the objects to export",
        Lane.Priority.VIEW,
        link -> link.exporter().list(on),
        got -> {
          listing = null;
          objects = got;
        },
        e -> {
          listing = null;
          unread = words(e);
        });
  }

  /** The name of what it exports, as the title says it. */
  String name() {
    return on instanceof Schema schema
        ? schema.database().name() + "." + schema.name()
        : on.database().name();
  }

  private boolean schemas() {
    return provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN);
  }

  /** The objects checked, in the order listed. */
  List<Item> checked() {
    if (objects == null) {
      return List.of();
    }
    return objects.stream()
        .map(Listed::item)
        .filter(item -> !unchecked.contains(item))
        .toList();
  }

  /** The objects the filter shows: those whose name holds it, in any case. */
  List<Listed> shown() {
    if (objects == null) {
      return List.of();
    }
    var part = filter.strip().toLowerCase(Locale.ROOT);
    return objects.stream()
        .filter(l -> l.item().name().toLowerCase(Locale.ROOT).contains(part))
        .toList();
  }

  /** Checks or unchecks all of the schema's objects that the filter shows. */
  void checkAll(Container container, boolean check) {
    for (var listed : shown()) {
      if (listed.item().container().equals(container)) {
        if (check) {
          unchecked.remove(listed.item());
        } else {
          unchecked.add(listed.item());
        }
      }
    }
  }

  private void toggle(Item item) {
    if (!unchecked.remove(item)) {
      unchecked.add(item);
    }
  }

  /** Opens the system's dialog to choose the file. */
  void choose(State s) {
    var suggested = (on instanceof Schema schema
            ? schema.database().name() + "-" + schema.name()
            : on.database().name())
        + ".sql";
    var dialog = FileDialog.save("Export to SQL").fileName(suggested).filter("SQL", "sql");
    if (file != null && file.getParent() != null) {
      dialog = dialog.in(file.getParent());
    }
    s.app.fileDialog(
        dialog,
        choice -> {
          if (!choice.cancelled()) {
            var path = choice.path();
            var name = path.getFileName().toString();
            file = name.contains(".") ? path : path.resolveSibling(name + ".sql");
            written = null;
          }
        },
        error -> s.tell("The system's file dialog did not open: " + error));
  }

  /** Whether Export can go: something is checked, a file is chosen, and nothing runs. */
  boolean ready() {
    return objects != null && !checked().isEmpty() && file != null && exporting == null;
  }

  /** Writes what is checked to the file chosen. */
  void export(State s) {
    if (!ready()) {
      return;
    }
    var choice = new Dump.Choice(
        on.database(),
        checked(),
        content,
        new Dump.Options(
            drop, create, rowsPerInsert, transaction, noForeignKeyChecks, keysAfterData));
    var target = file;
    written = null;
    failed = null;
    cancelled = false;
    at = null;
    atRows = 0;
    rowsBefore = 0;
    objectsBefore = 0;
    var app = s.app;
    var progress = new Told(app, new AtomicReference<>(Stage.RUNNING));
    stage = progress.where;
    exporting = lane.ask(
        app,
        "export to SQL",
        Lane.Priority.VIEW,
        link -> link.exporter().write(choice, target, progress),
        done -> {
          exporting = null;
          written = done;
          s.tell("Exported " + objects(done.objects()) + " to " + target.getFileName());
        },
        e -> {
          exporting = null;
          failed = words(e);
        });
  }

  /**
   * An export's progress, told one call at a time from the lane's thread: an object's last count
   * goes before the next object, the counts between at most every {@link #TOLD_EVERY}.
   */
  private final class Told implements Dump.Progress {
    private final App<?> app;
    private final AtomicReference<Stage> where;
    private Item last;
    private long lastRows;
    private long toldAt;

    Told(App<?> app, AtomicReference<Stage> stage) {
      this.app = app;
      this.where = stage;
    }

    @Override
    public void at(Item item, long rows) {
      var now = System.nanoTime();
      if (!item.equals(last)) {
        var was = last;
        var wasRows = lastRows;
        toldAt = now;
        app.post(() -> {
          if (was != null) {
            step(was, wasRows);
          }
          step(item, rows);
        });
      } else if (now - toldAt >= TOLD_EVERY) {
        toldAt = now;
        app.post(() -> step(item, rows));
      }
      last = item;
      lastRows = rows;
    }

    @Override
    public boolean replacing() {
      return where.compareAndSet(Stage.RUNNING, Stage.REPLACING);
    }
  }

  // The export writes this object now, with so many of its rows.
  private void step(Item item, long rows) {
    if (exporting == null) {
      return;
    }
    if (!item.equals(at)) {
      if (at != null) {
        objectsBefore++;
        rowsBefore += atRows;
      }
      at = item;
    }
    atRows = rows;
  }

  /**
   * Cancels the export under way: the chosen file stays as it was. Once the file is whole and
   * replaces it, the export ends as it would have.
   */
  void cancel(State s) {
    if (exporting != null && stage.compareAndSet(Stage.RUNNING, Stage.CANCELLED)) {
      exporting.cancel();
      exporting = null;
      cancelled = true;
      s.tell("Export cancelled: " + file.getFileName() + " was not written");
    }
  }

  /** Closes it: what runs is given up and its session closed, which Exit waits for. */
  void close(State s) {
    if (listing != null) {
      listing.cancel();
      listing = null;
    }
    if (exporting != null) {
      stage.compareAndSet(Stage.RUNNING, Stage.CANCELLED);
      exporting.cancel();
      exporting = null;
    }
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
    Arrays.fill(password, '\0');
    if (s.dialogs.export == this) {
      s.dialogs.export = null;
    }
  }

  // What failed, without the connection's password.
  private String words(Exception e) {
    var detail = switch (e) {
      case QueryException q -> q.detail().isEmpty() ? q.failure().toString() : q.detail();
      case ConnectionException c -> c.detail();
      default -> State.messageOf(e);
    };
    return Probe.contains(detail, password) ? ConnectionException.WITHHELD : detail;
  }

  private static String objects(int n) {
    return String.format(Locale.ROOT, "%,d", n) + (n == 1 ? " object" : " objects");
  }

  // The view

  // A line of the list: a schema's, with All and None; a kind's within it; or an object's.
  private sealed interface Line {}

  private record Heading(Container container) implements Line {}

  private record Kind(Item.Kind kind) implements Line {}

  private record Entry(Listed listed) implements Line {}

  private List<Line> lines() {
    var lines = new ArrayList<Line>();
    Container container = null;
    Item.Kind kind = null;
    for (var listed : shown()) {
      var item = listed.item();
      if (!item.container().equals(container)) {
        container = item.container();
        kind = null;
        lines.add(new Heading(container));
      }
      if (item.kind() != kind) {
        kind = item.kind();
        lines.add(new Kind(kind));
      }
      lines.add(new Entry(listed));
    }
    return lines;
  }

  private static String plural(Item.Kind kind) {
    return switch (kind) {
      case TABLE -> "Tables";
      case VIEW -> "Views";
      case SEQUENCE -> "Sequences";
      case FUNCTION -> "Functions";
      case PROCEDURE -> "Procedures";
      case TRIGGER -> "Triggers";
    };
  }

  El view(State s) {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    boolean running = exporting != null;
    El body = col().gap(t.space().m());
    if (objects == null) {
      body.w(36 * unit);
      if (unread != null) {
        body.child(Callout.callout(
            Callout.Kind.DANGER,
            "The objects could not be read",
            unread,
            button(SECONDARY, "Try again", () -> list(s)).key("export-retry")));
      } else {
        body.child(row()
            .gap(t.space().s())
            .align(Style.Align.CENTER)
            .role("status")
            .label("Reading the objects")
            .child(Progress.spinner())
            .child(label(Label.Variant.MUTED, "Reading what " + name() + " holds…")));
      }
    } else {
      body.child(row()
          .gap(t.space().l())
          .child(objectsView(objects, unit, running))
          .child(optionsView(s, unit, running)));
      body.w(41 * unit).child(statusView(objects));
    }
    El export = button(
            PRIMARY,
            EXPORT,
            ready() ? () -> export(s) : null,
            Button.options().state(running ? Button.State.BUSY : Button.State.READY))
        .key("export-go");
    Runnable dismiss = running ? () -> cancel(s) : () -> close(s);
    El close = button(SECONDARY, running ? "Cancel" : "Close", dismiss).key("export-close");
    return Dialog.dialog(
            EXPORT + " " + name() + " to SQL",
            body,
            List.of(close, export),
            dismiss,
            Dialog.options().defaultButton(export))
        .key("export-dialog");
  }

  private El objectsView(List<Listed> all, float unit, boolean running) {
    Theme t = Theme.CURRENT.get();
    var lines = lines();
    float height = t.size().control();
    El list;
    if (lines.isEmpty()) {
      list = col()
          .padding(t.space().m())
          .child(label(
                  Label.Variant.MUTED,
                  all.isEmpty()
                      ? name() + " holds nothing to export."
                      : "No object's name holds “" + filter + "”.")
              .wrapText());
    } else {
      list = col()
          .key("export-objects")
          .role("list")
          .label("Objects")
          .wFull()
          .grow(1)
          .scrollbars(t.scrollbars())
          .rows(lines.size(), height, i -> line(lines.get(i), i, height, running));
    }
    var checked = Set.copyOf(checked());
    var tables = all.stream()
        .filter(l -> l.item().kind() == Item.Kind.TABLE && checked.contains(l.item()))
        .toList();
    var summary = new StringBuilder(String.format(
        Locale.ROOT,
        "%,d of %s checked, %,d %s",
        checked.size(),
        objects(all.size()),
        tables.size(),
        tables.size() == 1 ? "table" : "tables"));
    if (content.data() && !tables.isEmpty()) {
      long rows = 0;
      long bytes = 0;
      boolean known = true;
      for (var table : tables) {
        known &= table.rows().isPresent();
        rows += table.rows().orElse(0);
        bytes += table.bytes().orElse(0);
      }
      summary
          .append(known ? ", about " : ", at least about ")
          .append(ConsoleTab.rows(rows))
          .append(" in ")
          .append(Format.BYTES.text(bytes));
    }
    return col()
        .w(22 * unit)
        .gap(t.space().s())
        .child(TextField.withIcon(
            TextField.textField(filter, "Filter by name", v -> filter = v)
                .key("export-filter")
                .label("Filter")
                .w(22 * unit),
            Symbol.SEARCH))
        .child(Card.flush(list).wFull().h(14 * unit))
        .child(label(Label.Variant.MUTED, summary.toString()).wrapText().key("export-summary"));
  }

  private El line(Line line, int index, float height, boolean running) {
    Theme t = Theme.CURRENT.get();
    El row = row()
        .key("line-" + index)
        .role("listitem")
        .h(height)
        .wFull()
        .align(Style.Align.CENTER)
        .gap(t.space().s())
        .padding(0, t.space().s());
    return switch (line) {
      case Heading(var container) -> {
        var name = container.name();
        yield row.child(Label.label(Label.Variant.STRONG, name).ellipsis().grow(1))
            .child(button(GHOST, "All", running ? null : () -> checkAll(container, true))
                .label("Check all of " + name))
            .child(button(GHOST, "None", running ? null : () -> checkAll(container, false))
                .label("Check none of " + name));
      }
      case Kind(var kind) ->
        row.padding(0, t.space().l()).child(label(Label.Variant.MUTED, plural(kind)));
      case Entry(var listed) -> {
        var item = listed.item();
        var box = Checkbox.checkbox(
                !unchecked.contains(item), item.shown(), running ? null : () -> toggle(item))
            .grow(1);
        row.padding(0, t.space().l()).child(box);
        detail(listed).ifPresent(detail -> row.child(label(Label.Variant.MUTED, detail)));
        yield row;
      }
    };
  }

  // A table's estimates, as the list shows them beside it.
  private static Optional<String> detail(Listed listed) {
    var bytes = listed.bytes().stream().mapToObj(Format.BYTES::text).findFirst();
    if (listed.rows().isEmpty()) {
      return bytes;
    }
    var rows = "~" + ConsoleTab.rows(listed.rows().getAsLong());
    return Optional.of(bytes.map(b -> rows + ", " + b).orElse(rows));
  }

  private El optionsView(State s, float unit, boolean running) {
    var what = Form.field(
        EXPORT,
        Radio.radioGroup(
                List.of(Dump.Content.values()),
                content,
                ExportDialog::title,
                running ? null : v -> content = v)
            .key("export-content")
            .label(EXPORT));
    var rows = Form.field(
        "Rows per INSERT",
        NumberField.numberField(
                rowsPerInsert,
                1,
                MOST_ROWS_PER_INSERT,
                10,
                running || !content.data() ? null : v -> rowsPerInsert = v)
            .key("export-rows-per-insert")
            .label("Rows per INSERT"));
    return col()
        .w(18 * unit)
        .child(Form.form(
            Form.Labels.ABOVE,
            Form.group(null, what, howField(running), rows, fileField(s, running))));
  }

  // The options, each of them disabled where what goes has no use for it.
  private Form.Field howField(boolean running) {
    Theme t = Theme.CURRENT.get();
    boolean structure = content.structure();
    boolean data = content.data();
    var options = new ArrayList<El>();
    options.add(Checkbox.checkbox(
            drop && structure,
            "Drop each object first",
            running || !structure ? null : () -> drop = !drop)
        .key("export-drop"));
    options.add(Checkbox.checkbox(
            create,
            schemas() ? "Create the schemas" : "Create the database",
            running ? null : () -> create = !create)
        .key("export-create"));
    options.add(Checkbox.checkbox(
            keysAfterData && structure && data,
            "Indexes and foreign keys after the rows",
            running || !(structure && data) ? null : () -> keysAfterData = !keysAfterData)
        .key("export-keys-after"));
    options.add(Checkbox.checkbox(
            transaction && data,
            "One transaction around the rows",
            running || !data ? null : () -> transaction = !transaction)
        .key("export-transaction"));
    if (provider.dialect().dump().foreignKeyChecks()) {
      options.add(Checkbox.checkbox(
              noForeignKeyChecks,
              "No foreign key checks (FOREIGN_KEY_CHECKS = 0)",
              running ? null : () -> noForeignKeyChecks = !noForeignKeyChecks)
          .key("export-no-checks"));
    }
    return Form.field("Options", col().gap(t.space().s()).children(options.toArray(El[]::new)));
  }

  // The file's name, and its folder below, each cut short where it is long.
  private Form.Field fileField(State s, boolean running) {
    Theme t = Theme.CURRENT.get();
    El chosen = col().grow(1).minSize(0, 0);
    if (file == null) {
      chosen.child(label(Label.Variant.MUTED, "No file chosen"));
    } else {
      chosen
          .key("export-file")
          .label(file.toString())
          .child(label(String.valueOf(file.getFileName())).ellipsis().wFull())
          .child(label(Label.Variant.MUTED, String.valueOf(file.getParent()))
              .ellipsis()
              .wFull());
    }
    return Form.field(
            "File",
            row()
                .wFull()
                .gap(t.space().s())
                .align(Style.Align.CENTER)
                .child(chosen)
                .child(button(SECONDARY, "Choose…", running ? null : () -> choose(s))
                    .key("export-choose")
                    .label("Choose the file")))
        .required();
  }

  private static String title(Dump.Content content) {
    return switch (content) {
      case STRUCTURE_AND_DATA -> "Structure and data";
      case STRUCTURE -> "Structure only";
      case DATA -> "Data only, of the tables";
    };
  }

  // How far the export is, or how it ended; and what of the snapshot an engine cannot keep.
  private El statusView(List<Listed> all) {
    Theme t = Theme.CURRENT.get();
    El status = col().wFull().gap(t.space().s());
    var checked = Set.copyOf(checked());
    var tables = all.stream()
        .filter(l -> l.item().kind() == Item.Kind.TABLE && checked.contains(l.item()))
        .toList();
    long outside = tables.stream().filter(l -> !l.consistent()).count();
    if (content.data() && outside > 0) {
      status.child(outsideView(outside));
    }
    if (exporting != null) {
      status.child(progressView(tables, checked.size()));
    } else {
      status.child(endedView());
    }
    return status;
  }

  // Tables of an engine without transactions, as MyISAM.
  private El outsideView(long outside) {
    var text = "Their engine keeps no transactions, as MyISAM: their rows are read as they are at"
        + " that moment, not as of the export's start";
    return Callout.callout(
            Callout.Kind.WARNING,
            outside == 1
                ? "A table is read outside the snapshot"
                : outside + " tables are read outside the snapshot",
            transaction
                ? text + ", and where the file is read, a failure does not undo their rows."
                : text + ".")
        .key("export-inconsistent");
  }

  private El progressView(List<Listed> tables, int checked) {
    var total = tables.stream().mapToLong(l -> l.rows().orElse(0)).sum();
    long rows = rowsBefore + atRows;
    var counted = content.data() && total > 0;
    double fraction =
        counted ? (double) rows / total : (double) objectsBefore / Math.max(1, checked);
    var what = at == null ? "Starting the snapshot" : "Exporting " + at.shown();
    var detail = counted
        ? ConsoleTab.rows(rows) + " of about " + ConsoleTab.rows(total)
        : objects(objectsBefore) + " of " + objects(checked);
    return Progress.labeled(what, Math.min(1, fraction), detail).wFull().key("export-progress");
  }

  // How the last export ended; null before one.
  private El endedView() {
    if (written != null) {
      return Callout.callout(
              Callout.Kind.SUCCESS,
              "Exported",
              objects(written.objects())
                  + (content.data() ? " and " + ConsoleTab.rows(written.rows()) : "")
                  + " to " + written.file() + ", " + Format.BYTES.text(written.bytes()) + ".")
          .key("export-done");
    }
    if (failed != null) {
      return Callout.callout(
              Callout.Kind.DANGER,
              "The export failed",
              failed + "\nThe chosen file was left as it was.")
          .key("export-failed");
    }
    return cancelled
        ? Callout.callout(Callout.Kind.INFO, "Cancelled", "The chosen file was left as it was.")
            .key("export-cancelled")
        : null;
  }
}
