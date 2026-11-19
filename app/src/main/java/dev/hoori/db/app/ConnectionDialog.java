package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Form.field;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.revealable;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.ValueField.valueField;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Editing;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Part;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The dialog that creates a connection or edits one: a form over its profile, with its password
 * and whether the system keyring keeps it.
 */
final class ConnectionDialog {

  static final Part<ConnectionProfile, String> NAME =
      Part.of(ConnectionProfile::name, ConnectionProfile::withName);
  static final Part<ConnectionProfile, Provider> PROVIDER =
      Part.of(ConnectionProfile::provider, ConnectionProfile::withProvider);
  static final Part<ConnectionProfile, String> HOST =
      Part.of(ConnectionProfile::host, ConnectionProfile::withHost);
  static final Part<ConnectionProfile, Integer> PORT =
      Part.of(ConnectionProfile::port, ConnectionProfile::withPort);
  static final Part<ConnectionProfile, String> DATABASE =
      Part.of(ConnectionProfile::database, ConnectionProfile::withDatabase);
  static final Part<ConnectionProfile, String> USER =
      Part.of(ConnectionProfile::user, ConnectionProfile::withUser);
  static final Part<ConnectionProfile, Tls> TLS =
      Part.of(ConnectionProfile::tls, ConnectionProfile::withTls);
  static final Part<ConnectionProfile, Color> COLOR =
      Part.of(ConnectionProfile::color, ConnectionProfile::withColor);
  static final Part<ConnectionProfile, String> DRIVER_VERSION =
      Part.of(ConnectionProfile::driverVersion, ConnectionProfile::withDriverVersion);

  private static final List<Provider> PROVIDERS = List.of(Provider.values());

  final boolean isNew;
  final Editing<ConnectionProfile> editing;
  private boolean portByHand; // the port no longer follows the provider
  private boolean tlsByHand; // the TLS no longer follows the host
  private boolean hostTyped;
  boolean triedToSave; // Save or a test was tried: the fields say what keeps them
  // The kit's password field holds a String, which cannot be wiped; it goes with the dialog.
  String password = "";
  int passwordEdits; // tells a test's outcome that the password changed since
  boolean revealed;
  boolean savePassword;
  Boolean stored; // whether the keyring holds a password for the profile; null while unknown
  String keyringError; // what the keyring answered when it failed, or null
  Work asking; // the keyring's answer whether it holds a password, or null
  private int lookups; // counted, so that a late patience is dropped
  // Asked and not answered: whether the keyring holds a password for the profile is not known.
  private boolean unknown;
  // A change of the keyring runs: the dialog stays, unchanged, until its answer has come.
  boolean changing;
  boolean slow; // the keyring has not answered for a while: Cancel gives up waiting for it
  private Work moving; // the change of the keyring, while it runs
  private int moves; // counted, so that a late patience is dropped
  private char[] held; // the typed password the change keeps for this session, wiped if given up
  private final State.Act act = new State.Act("save", this);
  boolean discarding; // the question whether to throw the changes away is open
  final Menu.State tlsMenu = new Menu.State();
  final ComboBox.State providerBox = new ComboBox.State();
  final ComboBox.State versionBox = new ComboBox.State();
  Versions versions; // of the driver of the kind of database chosen
  final Probe probe = new Probe(this);

  private ConnectionDialog(ConnectionProfile profile, boolean isNew) {
    this.isNew = isNew;
    this.editing = Editing.of(profile);
    portByHand = profile.port() != profile.provider().defaultPort();
    tlsByHand = profile.tls() != ConnectionProfile.tlsByDefault(profile.host());
    if (isNew) {
      stored = false;
      savePassword = true;
    }
  }

  static ConnectionDialog create(State s) {
    var dialog = new ConnectionDialog(ConnectionProfile.of(Provider.POSTGRESQL), true);
    dialog.driver(s);
    return dialog;
  }

  static ConnectionDialog edit(State s, ConnectionProfile profile) {
    var dialog = new ConnectionDialog(profile, false);
    dialog.lookUp(s);
    dialog.driver(s);
    return dialog;
  }

  /** Reads the versions of the kind's driver, and looks up the one to use. */
  private void driver(State s) {
    var p = editing.value();
    if (versions != null) {
      versions.close();
    }
    versions = new Versions(p.provider());
    versions.read(s);
    versions.date(s, p.driverVersion());
    probe.lookUp(s, p.provider(), p.driverVersion());
  }

  private void version(State s, String version) {
    if (Objects.equals(version, editing.value().driverVersion())) {
      return;
    }
    editing.at(DRIVER_VERSION).set(version);
    versions.date(s, version);
    probe.stop(); // a test or a download is for the other version
    probe.lookUp(s, editing.value().provider(), version);
  }

  /** Asks the keyring whether it holds a password for the profile edited, once there is one. */
  void lookUp(State s) {
    var secrets = s.secrets();
    if (stored != null || asking != null || secrets == null) {
      return;
    }
    var id = editing.original().id();
    int at = ++lookups;
    s.app.after(State.KEYRING_SECONDS, () -> {
      if (asking != null && lookups == at) {
        asking.cancel(); // stops waiting: the keyring does not answer, and it stays unknown
        asking = null;
        unknown = true;
      }
    });
    asking = s.app.background(
        "look for a password",
        () -> {
          var secret = secrets.read(id);
          secret.ifPresent(p -> Arrays.fill(p, '\0'));
          return secret.isPresent();
        },
        found -> {
          asking = null;
          stored = found;
          savePassword = found;
          unknown = false;
        },
        error -> {
          asking = null;
          keyringError = State.message(error);
          unknown = true;
        });
  }

  /** Whether the user changed anything, the password or whether it is saved included. */
  boolean changed() {
    return editing.changed()
        || portTyped()
        || !password.isEmpty()
        || !isNew && stored != null && savePassword != stored
        // Not known to match: the choice is one to carry out.
        || !isNew && stored == null && unknown;
  }

  /** Whether Save can go: the profile can connect, and something changed. */
  boolean ready() {
    return editing.valid()
        && editing.value().problems().isEmpty()
        && changed()
        && asking == null
        && !changing;
  }

  /** Whether the form, the port's text included, holds a profile that can connect. */
  boolean formValid() {
    return editing.valid() && !portTyped() && editing.value().problems().isEmpty();
  }

  /** Whether the port field holds a text that is no port, which the user typed. */
  private boolean portTyped() {
    return editing.at(PORT).draft().error() != null;
  }

  /**
   * Saves the password where the user wants it, then the profile, and closes; a failure keeps it
   * open. A password already kept moves when the user changes where: into the keyring from this
   * session, or out of the keyring into this session.
   */
  void save(State s) {
    if (!ready()) {
      triedToSave = true;
      return;
    }
    var profile = editing.value();
    var id = profile.id();
    // The consoles ask first, before anything is written, so that Cancel leaves it all as it was;
    // then they start nothing until the save is done.
    if (ends(s, profile)
        && (s.asks(act, SAVED, s.consoles(id), () -> save(s))
            || !s.lock(act, State.name(profile) + " is being saved", id))) {
      return;
    }
    var secrets = s.secrets();
    boolean keep = savePassword && secrets != null;
    boolean inKeyring = Boolean.TRUE.equals(stored);
    char[] typed = password.isEmpty() ? null : password.toCharArray();
    char[] put = !keep ? null : typed != null ? typed : inKeyring ? null : s.rememberedCopy(id);
    // Unknown is not absent: where the keyring may hold one, it is asked to delete it.
    boolean takeOut = !keep && secrets != null && !Boolean.FALSE.equals(stored);
    keyringError = null;
    if (put == null && !takeOut) {
      saved(s, profile, keep ? null : typed, false);
      return;
    }
    changing = true;
    held = put == null ? typed : null;
    int move = ++moves;
    s.app.after(State.KEYRING_SECONDS, () -> {
      if (changing && moves == move) {
        slow = true;
        if (s.quitting()) {
          giveUp(s); // closing never waits for a keyring that does not answer
        }
      }
    });
    moving = s.app.background(
        "move a password",
        () -> {
          if (put != null) {
            try {
              secrets.store(id, put);
            } finally {
              Arrays.fill(put, '\0');
            }
            return null;
          }
          char[] old = typed == null ? secrets.read(id).orElse(null) : null;
          try {
            secrets.delete(id);
          } catch (IOException e) {
            if (old != null) {
              Arrays.fill(old, '\0');
            }
            throw e;
          }
          return old;
        },
        old -> {
          moving = null;
          held = null;
          slow = false;
          stored = put != null; // where the password is now, as was done
          unknown = false;
          saved(s, profile, put != null ? null : typed != null ? typed : old, put != null);
        },
        error -> {
          moving = null;
          held = null;
          slow = false;
          changing = false;
          s.drop(act);
          keyringError = State.message(error);
          if (put == null && typed != null) {
            Arrays.fill(typed, '\0');
          }
          s.keyringFailed();
        });
  }

  /**
   * @param session the password to keep for this session, or null
   * @param inKeyring whether the keyring has the password now
   */
  private void saved(State s, ConnectionProfile profile, char[] session, boolean inKeyring) {
    changing = false;
    if (session != null) {
      s.rememberPassword(profile.id(), session);
    } else if (inKeyring) {
      s.forgetPassword(profile.id());
    }
    apply(s, profile);
  }

  static final String GAVE_UP =
      "The system keyring did not answer, and the save was given up: whether it holds the password"
          + " now, the old one or the new, is not known. The connection was not saved. What is"
          + " asked of the keyring next waits until it has answered this.";

  static final String BEHIND =
      " An earlier request to it that was given up has not ended yet: this one waits behind it.";

  /**
   * Cancel once the keyring has not answered for a while: the change of the keyring is given up,
   * whatever it did, and so is the save; the dialog stays and says what that leaves.
   */
  void giveUp(State s) {
    if (!changing || !slow) {
      return;
    }
    moving.cancel();
    moving = null;
    moves++;
    changing = false;
    slow = false;
    stored = null; // not known now, and asked again before it counts
    unknown = true;
    if (held != null) {
      Arrays.fill(held, '\0');
      held = null;
    }
    keyringError = GAVE_UP;
    s.drop(act);
    s.keyringFailed();
    lookUp(s); // queued behind the change given up: what the keyring holds once it has answered
  }

  static final String SAVED = "the changed connection is saved";

  /** Puts the profile in place and closes; its consoles were locked since their answers. */
  private void apply(State s, ConnectionProfile profile) {
    s.drop(act);
    password = "";
    probe.close();
    versions.close();
    s.dialog = null;
    s.put(profile);
  }

  /** Whether saving the profile ends its consoles' sessions: a change but of the color. */
  private static boolean ends(State s, ConnectionProfile profile) {
    var before = s.profile(profile.id());
    return before != null && !before.withColor(profile.color()).equals(profile);
  }

  /** Cancel and Escape: closes, after asking when there are changes. */
  void cancel(State s) {
    if (changing) {
      giveUp(s); // only once the keyring has kept it waiting
      return;
    }
    if (changed()) {
      discarding = true;
    } else {
      close(s);
    }
  }

  void close(State s) {
    s.drop(act); // the way out a Save asked for goes with the dialog
    if (asking != null) {
      asking.cancel();
    }
    probe.close();
    versions.close();
    password = "";
    s.dialog = null;
  }

  // Writes that let another field follow, as long as the user has not set that one by hand.

  private void provider(State s, Provider provider) {
    if (provider == editing.value().provider()) {
      return;
    }
    editing.at(PROVIDER).set(provider);
    editing.at(DRIVER_VERSION).set(null); // a version of the other kind's driver
    if (!portByHand && !portTyped()) {
      editing.at(PORT).set(provider.defaultPort());
    }
    probe.stop(); // a test or a download is for the driver of the other kind
    driver(s);
  }

  private void port(Integer port) {
    portByHand = true;
    editing.at(PORT).set(port);
  }

  private void host(String host) {
    hostTyped = true;
    editing.at(HOST).set(host);
    if (!tlsByHand) {
      editing.at(TLS).set(ConnectionProfile.tlsByDefault(host));
    }
  }

  private void password(String password) {
    this.password = password;
    passwordEdits++;
  }

  /** The text field, with the look of an error where a test's failure concerns it too. */
  private El marked(Probe.At at, El field) {
    return probe.marks(at) ? TextField.invalid(field) : field;
  }

  private static String or(String first, String second) {
    return first != null ? first : second;
  }

  private void tls(Tls tls) {
    tlsByHand = true;
    editing.at(TLS).set(tls);
  }

  static String tlsLabel(Tls tls) {
    return switch (tls) {
      case OFF -> "Off";
      case ENCRYPTED -> "Encrypted";
      case VERIFIED -> "Encrypted, server verified";
    };
  }

  private static String help(Tls tls) {
    return switch (tls) {
      case OFF -> "Password and data go over the network as they are.";
      case ENCRYPTED -> "Any certificate is accepted.";
      case VERIFIED -> "The certificate must be trusted and name the host.";
    };
  }

  /** What keeps the profile from connecting, in words for the field it concerns. */
  private static String text(ConnectionProfile.Problem problem) {
    return switch (problem) {
      case HOST_MISSING -> "Enter the server's name or address.";
      case HOST_INVALID -> "Enter one host name or IP address, without port or protocol.";
      case PORT_OUT_OF_RANGE -> "Enter a port from 1 to 65535.";
      case DATABASE_HAS_NUL, USER_HAS_NUL -> "Remove the NUL character.";
    };
  }

  private String error(Set<ConnectionProfile.Problem> problems, ConnectionProfile.Field field) {
    for (var problem : problems) {
      if (problem.field() == field
          && (problem != ConnectionProfile.Problem.HOST_MISSING || hostTyped || triedToSave)) {
        return text(problem);
      }
    }
    return null;
  }

  /** Why the password cannot be saved, or what happens to it when it is not; null for nothing. */
  private String keyringNote(State s) {
    if (s.keyring == null) {
      return "Looking for the system keyring…";
    }
    if (s.keyring instanceof Keyring.Unavailable unavailable) {
      return switch (unavailable.reason()) {
            case UNSUPPORTED_PLATFORM -> "This system has no keyring that HooriDB can use.";
            case LIBRARY_NOT_FOUND -> "The keyring library, libsecret, is not installed.";
            case NO_SERVICE -> "No keyring service, such as GNOME Keyring or KWallet, is running.";
          }
          + " The password is kept until HooriDB closes.";
    }
    if (stored == null) {
      return asking != null
          ? "Looking for a saved password…"
          : "Whether the system keyring holds a password for this connection is not known.";
    }
    return savePassword ? null : "The password is kept until HooriDB closes.";
  }

  El view(State s) {
    Theme t = Theme.CURRENT.get();
    var e = editing;
    var p = e.value();
    var problems = p.problems();
    boolean open = !changing; // nothing changes while the keyring is being changed
    El save = button(
        PRIMARY,
        "Save",
        () -> save(s),
        Button.options()
            .state(
                changing
                    ? Button.State.BUSY
                    : ready() ? Button.State.READY : Button.State.DISABLED));
    El cancel = button(SECONDARY, "Cancel", open ? () -> cancel(s) : slow ? () -> giveUp(s) : null);
    // Also where it is not known whether the keyring holds one: the choice is the user's.
    boolean canSave = s.secrets() != null && (stored != null || asking == null);
    boolean inKeyring = Boolean.TRUE.equals(stored);
    boolean inSession = !isNew && s.remembers(e.original().id());
    String keepNote = password.isEmpty() && (inKeyring || inSession)
        ? "Leave it empty to keep the current password."
        : null;
    String note = keyringNote(s);
    var keyring =
        Prop.of(() -> canSave && savePassword, canSave && open ? v -> savePassword = v : null);
    String fallback = String.valueOf(p.provider().defaultPort());
    El form = Form.form(
        Form.Labels.BESIDE,
        field(
            "Name",
            textField(locked(e.at(NAME)), p.host().isBlank() ? "Optional" : p.host())
                .key("name")
                .wFull()),
        field(
            "Color",
            HuePicker.huePicker(
                    ConnectionColors.hue(p.color()),
                    ConnectionColors.label(null),
                    open ? v -> e.at(COLOR).set(ConnectionColors.color(v)) : null)
                .key("color")),
        Form.group(
                "Server",
                field(
                    "Type",
                    ComboBox.items(
                            PROVIDERS,
                            p.provider(),
                            ConnectionDialog::type,
                            providerBox,
                            open ? v -> provider(s, v) : null)
                        .key("type")
                        .label("Type")
                        .wFull()),
                field("Driver", driverVersion(s, open))
                    .help(driverHelp(s))
                    .warning(youngWarning(s)),
                Form.row(
                    field(
                            "Host",
                            marked(
                                Probe.At.HOST,
                                textField(p.host(), "Name or address", open ? this::host : null)
                                    .key("host")
                                    .wFull()))
                        .required()
                        .error(error(problems, ConnectionProfile.Field.HOST))
                        .grow(),
                    field(
                            "Port",
                            valueField(
                                    Format.INTEGER,
                                    p.port(),
                                    fallback,
                                    e.at(PORT).draft(),
                                    open ? this::port : null)
                                .key("port")
                                .maxLength(5)
                                .w(3 * t.size().control()))
                        .required()
                        .error(or(
                            error(problems, ConnectionProfile.Field.PORT),
                            probe.error(Probe.At.PORT)))),
                field(
                        "Database",
                        textField(locked(e.at(DATABASE)), "Optional")
                            .key("database")
                            .wFull())
                    .error(or(
                        error(problems, ConnectionProfile.Field.DATABASE),
                        probe.error(Probe.At.DATABASE))),
                field(
                        "TLS",
                        Select.select(
                                List.of(Tls.values()),
                                p.tls(),
                                ConnectionDialog::tlsLabel,
                                tlsMenu,
                                open ? this::tls : null)
                            .key("tls")
                            .wFull())
                    .help(help(p.tls()))
                    .error(probe.error(Probe.At.TLS)))
            .divided()
            .level(3),
        Form.group(
                "Login",
                field(
                        "User",
                        marked(
                            Probe.At.USER,
                            textField(locked(e.at(USER))).key("user").wFull()))
                    .error(error(problems, ConnectionProfile.Field.USER)),
                field(
                        "Password",
                        col()
                            .wFull()
                            .gap(t.space().s())
                            .child(revealable(
                                    passwordField(
                                            Prop.of(() -> password, open ? this::password : null),
                                            inKeyring
                                                ? "Saved in the system keyring"
                                                : inSession ? "Kept until HooriDB closes" : null)
                                        .key("password")
                                        .wFull(),
                                    revealed,
                                    () -> revealed = !revealed)
                                .wFull())
                            .child(checkbox(keyring, "Save password in the system keyring")))
                    .help(keepNote == null ? note : note == null ? keepNote : keepNote + " " + note)
                    .error(probe.error(Probe.At.PASSWORD)))
            .divided()
            .level(3));
    El content = col()
        .w(width())
        .gap(t.space().l())
        .child(form)
        // Enter while Save cannot go shows what keeps it, the empty host too.
        .onKey(k -> {
          if (k.name().equals("Enter") && k.modifiers() == 0 && !ready()) {
            triedToSave = true;
            return true;
          }
          return false;
        });
    if (slow) {
      content.child(Callout.callout(
          Callout.Kind.WARNING,
          "The system keyring does not answer",
          "Cancel gives up waiting: the keyring may then hold the password, the old one or the"
              + " new, or none, and the connection is not saved."
              + (s.keyringWorker.behind() ? BEHIND : "")));
    }
    if (keyringError != null) {
      content.child(Callout.callout(
          Callout.Kind.DANGER, "The system keyring reported a problem", keyringError));
    }
    El tried = probe.view(s);
    if (tried != null) {
      content.child(tried);
    }
    El test = button(
        SECONDARY,
        "Test connection",
        () -> probe.test(s),
        Button.options()
            .state(
                probe.running()
                    ? Button.State.BUSY
                    : probe.canTest(s) ? Button.State.READY : Button.State.DISABLED));
    var options = Dialog.options().start(test).defaultButton(save);
    if (p.color() != null) {
      options = options.leading(ConnectionColors.mark(p.color()));
    }
    return Dialog.dialog(
        isNew ? "New connection" : "Edit connection",
        content,
        List.of(cancel, save),
        () -> cancel(s),
        options);
  }

  /**
   * A kind of server with its logo, which a dark theme sets on a tile of the light theme, as the
   * logos' colors are made for a light ground.
   */
  private static ComboBox.Item type(Provider provider) {
    Image logo = Logos.of(provider);
    return ComboBox.item(provider.displayName()).leading(() -> {
      // Read where it is built: the kit keeps the items of an earlier build, theme and all.
      Theme t = Theme.CURRENT.get();
      float side = t.size().icon();
      El image = El.image(logo).size(side, side).fit(Image.Fit.CONTAIN);
      return Colors.luminance(t.surface().window()) >= 0.5
          ? image
          : image
              .padding(t.space().xs())
              .fill(Theme.LIGHT.surface().raised())
              .radius(t.radius().small());
    });
  }

  /** The version of the driver: Recommended or one of the list, which is read in the background. */
  private El driverVersion(State s, boolean open) {
    Theme t = Theme.CURRENT.get();
    var chosen = editing.value().driverVersion();
    El box = ComboBox.items(
            versions.values(chosen),
            chosen,
            versions::item,
            versionBox,
            open && s.drivers != null ? v -> version(s, v) : null)
        .key("driver-version")
        .label("Driver version")
        .grow(1);
    El line = row().wFull().gap(t.space().s()).align(Style.Align.CENTER).child(box);
    if (versions.reading() || versions.dating != null) {
      var waiting = versions.dating != null
          ? "Looking up when " + versions.dating + " was published"
          : "Reading the versions";
      line.child(col().role("status").label(waiting).child(Progress.spinner()));
    } else if (versions.problem(s.drivers) != null) {
      line.child(Button.icon(
          Button.Variant.GHOST,
          Symbol.ARROW_CLOCKWISE,
          "Read the versions again",
          () -> versions.read(s)));
    }
    return line;
  }

  /**
   * The driver's line with what to think of a version chosen that is too new to be recommended, as
   * the warning replaces the help; null where it is not too new, or where a lookup or the list
   * failed, which the help says instead.
   */
  private String youngWarning(State s) {
    var chosen = editing.value().driverVersion();
    if (!versions.young(chosen)
        || probe.fetch.lookup instanceof Fetch.Missing
        || s.drivers != null && versions.problem(s.drivers) != null) {
      return null;
    }
    var age = versions.age(chosen);
    return probe.line(s) + ". " + (age == null ? "Very new" : "Only " + age)
        + ": recommended after " + DriverStore.WAITING_PERIOD.toDays()
        + " days, once a bad release would be withdrawn.";
  }

  /** Which driver, and whether it is there; and what keeps the list of versions from showing. */
  private String driverHelp(State s) {
    var line = probe.line(s);
    var problem = s.drivers == null ? null : versions.problem(s.drivers);
    return problem == null ? line : line + "\n" + problem;
  }

  /**
   * The width of the dialog's content: the colors' swatches in one line, and help and errors
   * wrapping below their field.
   */
  static float width() {
    Theme t = Theme.CURRENT.get();
    return 2 * t.size().field() + 3 * t.size().control();
  }

  /** The prop, or one that cannot be written while the keyring is being changed. */
  private <T> Prop<T> locked(Prop<T> prop) {
    return changing ? Prop.of(prop::get, null) : prop;
  }

  /** The question whether to throw the changes away, or null while it is not asked. */
  El question(State s) {
    if (!discarding) {
      return null;
    }
    return Dialog.dialog(
            "Discard changes?",
            label("The changes to this connection have not been saved."),
            List.of(
                button(SECONDARY, "Keep editing", () -> discarding = false),
                button(
                    PRIMARY, "Discard", () -> close(s), Button.options().tone(Button.Tone.DANGER))),
            () -> discarding = false)
        .key("discard");
  }
}
