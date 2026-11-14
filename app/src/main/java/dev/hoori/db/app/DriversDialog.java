package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Group;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Drivers dialog: per kind of database the versions of its driver that are downloaded, with
 * their sizes; a download of the recommended version or a chosen one; and deleting a version that
 * no open connection uses.
 */
final class DriversDialog {

  /** How far a download is: bytes so far and in all, or -1 while that is not known. */
  record Download(String version, long done, long total) {}

  /** What failed, in words; a download can be tried again. */
  record Problem(String title, String text, String download) {}

  /** The question before a delete. */
  record Deleting(Kind kind, String version) {}

  /** The driver of a kind of database. */
  static final class Kind {
    final Provider provider;
    final Versions versions;
    final ComboBox.State box = new ComboBox.State();
    String pick; // the version to download, null for the recommended one
    Download download; // the download under way, or null
    private Work work; // the download or the delete under way, or null
    Problem problem; // what failed last, or null

    Kind(Provider provider) {
      this.provider = provider;
      this.versions = new Versions(provider);
    }

    Driver driver() {
      return Driver.of(provider);
    }

    String name() {
      return Fetch.name(provider);
    }
  }

  final List<Kind> kinds = new ArrayList<>();
  Deleting asking; // the question before a delete, or null
  boolean removing; // the delete asked about runs

  private DriversDialog() {}

  /** Opens it and reads what is downloaded and what the repository has, of every kind. */
  static DriversDialog open(State s) {
    var dialog = new DriversDialog();
    for (var provider : Provider.values()) {
      var kind = new Kind(provider);
      kind.versions.read(s);
      dialog.kinds.add(kind);
    }
    return dialog;
  }

  /**
   * Close and Escape: a download is cancelled, as it can be done again; a delete runs to its end,
   * which gives the version free again.
   */
  void close(State s) {
    for (var kind : kinds) {
      kind.versions.close();
      if (kind.download != null) {
        cancel(kind);
      }
    }
    s.driverList = null;
  }

  /** The version Download would download, or null while it is not known. */
  private static String target(Kind kind) {
    return kind.pick != null ? kind.pick : kind.versions.recommended();
  }

  private static boolean downloaded(Kind kind, String version) {
    var read = kind.versions.read;
    return read != null && read.sizes().containsKey(version);
  }

  void download(State s, Kind kind) {
    var version = target(kind);
    if (version == null || kind.work != null || s.drivers == null) {
      return;
    }
    var store = s.drivers.store;
    var driver = kind.driver();
    kind.problem = null;
    kind.download = new Download(version, 0, -1);
    kind.work = s.app.background(
        "download a driver",
        (Work.Progress<Download> progress) -> {
          store.download(
              driver,
              version,
              (done, total) -> progress.report(new Download(version, done, total)));
          return version;
        },
        report -> kind.download = report,
        done -> {
          kind.work = null;
          kind.download = null;
          kind.versions.read(s);
        },
        error -> {
          kind.work = null;
          kind.download = null;
          kind.problem = new Problem(
              kind.name() + " " + version + " could not be downloaded",
              Fetch.text(error, s.drivers),
              version);
        });
  }

  /** Cancel download: nothing of it is left. */
  void cancel(Kind kind) {
    if (kind.work != null) {
      kind.work.cancel();
      kind.work = null;
    }
    kind.download = null;
  }

  /** Delete on a version: asks first. */
  void ask(Kind kind, String version) {
    asking = new Deleting(kind, version);
  }

  void cancelDelete() {
    if (!removing) {
      asking = null;
    }
  }

  /**
   * Deletes the version asked about, unless a connection began to use it meanwhile; from here until
   * the delete ends, no connection begins to.
   */
  void delete(State s) {
    var d = asking;
    if (d == null || removing || !s.claimForDelete(d.kind().driver(), d.version())) {
      asking = null;
      return;
    }
    var kind = d.kind();
    var driver = kind.driver();
    var version = d.version();
    var store = s.drivers.store;
    removing = true;
    kind.problem = null;
    kind.work = s.app.background(
        "delete a driver",
        () -> {
          store.delete(driver, version);
          return version;
        },
        done -> {
          removing = false;
          asking = null;
          kind.work = null;
          s.drivers.deleting(driver, version, false);
          s.drivers.forget(driver, version);
          kind.versions.read(s);
          s.tell(kind.name() + " " + version + " deleted");
        },
        error -> {
          removing = false;
          asking = null;
          kind.work = null;
          s.drivers.deleting(driver, version, false);
          kind.problem = new Problem(
              kind.name() + " " + version + " could not be deleted",
              notDeleted(s, driver, version, error),
              null);
          kind.versions.read(s);
        });
  }

  /** Why a delete failed, and what to do. */
  private static String notDeleted(State s, Driver driver, String version, Exception error) {
    if (s.drivers.loaded(driver, version) != null) {
      // Windows keeps a JAR open while its class loader lives, which is until HooriDB closes.
      return "HooriDB has used this version since it started, and the system keeps its file open"
          + " until HooriDB closes. It stays downloaded and usable; delete it after HooriDB was"
          + " restarted.";
    }
    if (error instanceof DriverException e && e.failure() == DriverException.Failure.STORAGE) {
      return "The drivers folder could not be changed" + Fetch.why(e)
          + ". Check that HooriDB may write to it, then try again.";
    }
    return Fetch.text(error, s.drivers);
  }

  El view(Theme t, State s) {
    El content = col().w(ConnectionDialog.width(t)).gap(t.space().xl());
    for (var kind : kinds) {
      content.child(kind(t, s, kind));
    }
    Runnable close = () -> close(s);
    return Dialog.dialog(
        t, "Drivers", content, List.of(button(t, SECONDARY, "Close", close)), close);
  }

  private El kind(Theme t, State s, Kind kind) {
    var versions = kind.versions;
    var read = versions.read;
    var rows = new ArrayList<El>();
    if (read == null && versions.failed == null) {
      rows.add(Group.box(
          t,
          row()
              .gap(t.space().s())
              .align(Style.Align.CENTER)
              .role("status")
              .label("Reading the versions of " + kind.name())
              .child(Progress.spinner(t))
              .child(label(t, Label.Variant.MUTED, "Reading…"))));
    } else if (read == null) {
      // The callout below says why.
    } else if (read.sizes().isEmpty()) {
      rows.add(Group.row(
          t,
          "None downloaded",
          "A connection downloads the version it needs, after asking.",
          null));
    } else {
      read.sizes().forEach((version, size) -> rows.add(version(t, s, kind, version, size)));
    }
    rows.add(kind.download != null ? downloading(t, kind) : downloadRow(t, s, kind));
    El section = col()
        .wFull()
        .gap(t.space().s())
        .child(label(t, Label.Variant.STRONG, kind.name()).role("heading").label(kind.name()))
        .child(Group.group(t, rows.toArray(El[]::new)));
    var problem = versions.failed != null ? versions.problem(s.drivers) : null;
    if (problem != null) {
      section.child(
          Callout.callout(t, Callout.Kind.DANGER, kind.name() + " could not be listed", problem));
    }
    var p = kind.problem;
    if (p != null) {
      section.child(
          p.download() == null
              ? Callout.callout(t, Callout.Kind.DANGER, p.title(), p.text())
              : Callout.callout(
                  t,
                  Callout.Kind.DANGER,
                  p.title(),
                  p.text(),
                  button(t, SECONDARY, "Try again", () -> {
                    kind.pick = p.download();
                    download(s, kind);
                  })));
    }
    return section;
  }

  /** A downloaded version: its size, what it is to HooriDB, and Delete. */
  private El version(Theme t, State s, Kind kind, String version, long size) {
    var notes = new ArrayList<String>();
    notes.add(Fetch.size(size));
    if (version.equals(kind.versions.recommended())) {
      notes.add("recommended");
    }
    var users = s.users(kind.driver(), version);
    if (!users.isEmpty()) {
      notes.add("in use by " + String.join(", ", users) + ". Disconnect to delete it.");
    }
    boolean free = users.isEmpty() && kind.work == null;
    return Group.row(
        t,
        version,
        String.join(", ", notes),
        Button.icon(
            t,
            Button.Variant.GHOST,
            Symbol.DELETE,
            "Delete " + kind.name() + " " + version,
            free ? () -> ask(kind, version) : null,
            Button.options().tone(Button.Tone.DANGER)));
  }

  /** The version to download, Recommended or one of the list, and Download. */
  private El downloadRow(Theme t, State s, Kind kind) {
    var versions = kind.versions;
    var target = target(kind);
    String note;
    if (versions.read != null && versions.read.error() != null) {
      note = versions.problem(s.drivers);
    } else if (target != null && downloaded(kind, target)) {
      note = target + " is downloaded.";
    } else if (versions.young(target)) {
      var age = versions.age(target);
      note = target + " is " + (age == null ? "too new" : age) + ", too new to be recommended.";
    } else {
      note = null;
    }
    El box = ComboBox.items(
            t, versions.values(kind.pick), kind.pick, versions::item, kind.box, v -> {
              kind.pick = v;
              versions.date(s, v);
            })
        .key("version-" + kind.provider.name())
        .label(kind.name() + " version")
        .w(t.size().field());
    boolean can = target != null && !downloaded(kind, target) && kind.work == null;
    El download = button(t, SECONDARY, "Download", can ? () -> download(s, kind) : null)
        .label("Download " + kind.name());
    return Group.row(
        t,
        "Download",
        note,
        row().gap(t.space().s()).align(Style.Align.CENTER).child(box).child(download));
  }

  private El downloading(Theme t, Kind kind) {
    var d = kind.download;
    return Group.box(
        t,
        Fetch.downloading(
            t,
            "Downloading " + kind.name() + " " + d.version(),
            d.done(),
            d.total(),
            () -> cancel(kind)));
  }

  /** The question before a delete, or null while none is asked. */
  El question(Theme t, State s) {
    if (asking == null) {
      return null;
    }
    var d = asking;
    var name = d.kind().name() + " " + d.version();
    var chosenBy = s.profiles.stream()
        .filter(p -> p.provider() == d.kind().provider && d.version().equals(p.driverVersion()))
        .map(State::name)
        .toList();
    El content = col()
        .w(t.size().field() * 2)
        .gap(t.space().m())
        .child(label(t, "Its file is removed from this computer.").wrapText())
        .child(label(
                t,
                Label.Variant.MUTED,
                chosenBy.isEmpty()
                    ? "A connection that needs it again downloads it again, after asking."
                    : String.join(", ", chosenBy)
                        + " chose this version, and will download it again, after asking.")
            .wrapText());
    El delete = button(
        t,
        PRIMARY,
        "Delete",
        () -> delete(s),
        Button.options().tone(Button.Tone.DANGER).busy(removing));
    return Dialog.dialog(
            t,
            "Delete " + name + "?",
            content,
            List.of(button(t, SECONDARY, "Cancel", removing ? null : this::cancelDelete), delete),
            this::cancelDelete)
        .key("delete-driver");
  }
}
