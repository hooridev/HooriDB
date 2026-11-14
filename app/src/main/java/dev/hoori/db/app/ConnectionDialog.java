package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
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

import com.rethinkj.ui.El;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Editing;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Part;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
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
  // A change of the keyring runs: the dialog stays, unchanged, until its answer has come.
  boolean changing;
  boolean discarding; // the question whether to throw the changes away is open
  final Menu.State tlsMenu = new Menu.State();
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
    dialog.probe.lookUp(s, Provider.POSTGRESQL);
    return dialog;
  }

  static ConnectionDialog edit(State s, ConnectionProfile profile) {
    var dialog = new ConnectionDialog(profile, false);
    dialog.lookUp(s);
    dialog.probe.lookUp(s, profile.provider());
    return dialog;
  }

  /** Asks the keyring whether it holds a password for the profile edited, once there is one. */
  void lookUp(State s) {
    var secrets = s.secrets();
    if (stored != null || asking != null || secrets == null) {
      return;
    }
    var id = editing.original().id();
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
        },
        error -> {
          asking = null;
          keyringError = State.message(error);
        });
  }

  /** Whether the user changed anything, the password or whether it is saved included. */
  boolean changed() {
    return editing.changed()
        || portTyped()
        || !password.isEmpty()
        || !isNew && stored != null && savePassword != stored;
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
    var secrets = s.secrets();
    boolean keep = savePassword && secrets != null;
    boolean inKeyring = Boolean.TRUE.equals(stored);
    char[] typed = password.isEmpty() ? null : password.toCharArray();
    char[] put = !keep ? null : typed != null ? typed : inKeyring ? null : s.rememberedCopy(id);
    boolean takeOut = !keep && secrets != null && inKeyring;
    keyringError = null;
    if (put == null && !takeOut) {
      saved(s, profile, keep ? null : typed, false);
      return;
    }
    changing = true;
    s.app.background(
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
        old -> saved(s, profile, put != null ? null : typed != null ? typed : old, put != null),
        error -> {
          changing = false;
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
    password = "";
    probe.close();
    s.dialog = null;
    s.put(profile);
  }

  /** Cancel and Escape: closes, after asking when there are changes. */
  void cancel(State s) {
    if (changing) {
      return;
    }
    if (changed()) {
      discarding = true;
    } else {
      close(s);
    }
  }

  void close(State s) {
    if (asking != null) {
      asking.cancel();
    }
    probe.close();
    password = "";
    s.dialog = null;
  }

  // Writes that let another field follow, as long as the user has not set that one by hand.

  private void provider(State s, Provider provider) {
    if (provider == editing.value().provider()) {
      return;
    }
    editing.at(PROVIDER).set(provider);
    if (!portByHand && !portTyped()) {
      editing.at(PORT).set(provider.defaultPort());
    }
    probe.stop(); // a test or a download is for the driver of the other kind
    probe.lookUp(s, provider);
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
  private El marked(Theme t, Probe.At at, El field) {
    return probe.marks(at) ? TextField.invalid(t, field) : field;
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
      return asking != null ? "Looking for a saved password…" : null;
    }
    return savePassword ? null : "The password is kept until HooriDB closes.";
  }

  El view(Theme t, State s) {
    var e = editing;
    var p = e.value();
    var problems = p.problems();
    boolean open = !changing; // nothing changes while the keyring is being changed
    El save = button(
        t,
        PRIMARY,
        "Save",
        () -> save(s),
        Button.options().enabled(changing || ready()).busy(changing));
    El cancel = button(t, SECONDARY, "Cancel", open ? () -> cancel(s) : null);
    boolean canSave = s.secrets() != null && stored != null;
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
        t,
        Form.Labels.BESIDE,
        field(
            "Name",
            textField(t, locked(e.at(NAME)), p.host().isBlank() ? "Optional" : p.host())
                .key("name")
                .wFull()),
        field(
                "Type",
                Segmented.segmented(
                    t,
                    List.of(Provider.values()),
                    p.provider(),
                    Provider::displayName,
                    open ? v -> provider(s, v) : null))
            .help(probe.line(s)),
        field(
                "Host",
                marked(
                    t,
                    Probe.At.HOST,
                    textField(t, p.host(), "Name or address", open ? this::host : null)
                        .key("host")
                        .wFull()))
            .required()
            .error(error(problems, ConnectionProfile.Field.HOST)),
        field(
                "Port",
                valueField(
                        t,
                        Format.INTEGER,
                        p.port(),
                        fallback,
                        e.at(PORT).draft(),
                        open ? this::port : null)
                    .key("port")
                    .maxLength(5)
                    .w(3 * t.size().control()))
            .required()
            .error(or(error(problems, ConnectionProfile.Field.PORT), probe.error(Probe.At.PORT))),
        field(
                "Database",
                textField(t, locked(e.at(DATABASE)), "Optional").key("database").wFull())
            .error(or(
                error(problems, ConnectionProfile.Field.DATABASE), probe.error(Probe.At.DATABASE))),
        field(
                "User",
                marked(
                    t,
                    Probe.At.USER,
                    textField(t, locked(e.at(USER))).key("user").wFull()))
            .error(error(problems, ConnectionProfile.Field.USER)),
        field(
                "Password",
                col()
                    .wFull()
                    .gap(t.space().s())
                    .child(revealable(
                            t,
                            passwordField(
                                    t,
                                    Prop.of(() -> password, open ? this::password : null),
                                    inKeyring
                                        ? "Saved in the system keyring"
                                        : inSession ? "Kept until HooriDB closes" : null)
                                .key("password")
                                .wFull(),
                            revealed,
                            () -> revealed = !revealed)
                        .wFull())
                    .child(checkbox(t, keyring, "Save password in the system keyring")))
            .help(keepNote == null ? note : note == null ? keepNote : keepNote + " " + note)
            .error(probe.error(Probe.At.PASSWORD)),
        field(
                "TLS",
                Select.select(
                        t,
                        List.of(Tls.values()),
                        p.tls(),
                        ConnectionDialog::tlsLabel,
                        tlsMenu,
                        open ? this::tls : null)
                    .wFull())
            .help(help(p.tls()))
            .error(probe.error(Probe.At.TLS)));
    El content = col()
        // As wide as two fields, so that help and errors wrap below their field.
        .w(2 * t.size().field())
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
    if (keyringError != null) {
      content.child(Callout.callout(
          t, Callout.Kind.DANGER, "The system keyring reported a problem", keyringError));
    }
    El tried = probe.view(t, s);
    if (tried != null) {
      content.child(tried);
    }
    El test = button(
        t,
        SECONDARY,
        "Test connection",
        () -> probe.test(s),
        Button.options().enabled(probe.running() || probe.canTest(s)).busy(probe.running()));
    return Dialog.dialog(
        t,
        isNew ? "New connection" : "Edit connection",
        content,
        List.of(test, cancel, save),
        save,
        () -> cancel(s));
  }

  /** The prop, or one that cannot be written while the keyring is being changed. */
  private <T> Prop<T> locked(Prop<T> prop) {
    return changing ? Prop.of(prop::get, null) : prop;
  }

  /** The question whether to throw the changes away, or null while it is not asked. */
  El question(Theme t, State s) {
    if (!discarding) {
      return null;
    }
    return Dialog.dialog(
            t,
            "Discard changes?",
            label(t, "The changes to this connection have not been saved."),
            List.of(
                button(t, SECONDARY, "Keep editing", () -> discarding = false),
                button(
                    t,
                    PRIMARY,
                    "Discard",
                    () -> close(s),
                    Button.options().tone(Button.Tone.DANGER))),
            () -> discarding = false)
        .key("discard");
  }
}
