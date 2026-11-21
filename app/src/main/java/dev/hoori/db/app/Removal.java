package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import dev.hoori.db.core.connection.ConnectionProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The deletion of connections, a part of the state: their passwords go out of the system keyring
 * one after another, once it is found, and with each its connection; a folder deleted with what it
 * holds goes last, where nothing stayed in it. A keyring that keeps it waiting is given up by
 * Cancel and by closing. Only the app's thread uses it.
 */
final class Removal {

  static final String DELETE_GAVE_UP =
      "The system keyring did not answer, and the deletion was given up: whether the password is"
          + " still in it is not known. The connection was not deleted. What is asked of the"
          + " keyring next waits until it has answered this.";

  static final String FOLDER_GAVE_UP =
      "The system keyring did not answer, and the deletion was given up: whether the password it"
          + " was deleting is still in it is not known. What is asked of the keyring next waits"
          + " until it has answered this.";

  // It runs: it waits for the keyring to be found, then for each password to go.
  boolean running;
  boolean slow; // the keyring has not answered for a while: Cancel gives up waiting
  private Work work; // the removal of the password, while it runs
  private int patience; // counted, so that a late patience is dropped
  String error; // why a password could not be removed, or null
  // The connections it takes out, one after another; the act that locks their consoles; the
  // folder it deletes once all are out, or null for one connection; and those that stayed.
  private final List<ConnectionProfile> doomed = new ArrayList<>();
  private State.Act act;
  private UUID folder;
  final List<ConnectionProfile> kept = new ArrayList<>();

  /** Begins to take the connections out, whose consoles the act has locked. */
  void start(State s, State.Act act, List<ConnectionProfile> profiles, UUID folder) {
    running = true;
    error = null;
    this.act = act;
    this.folder = folder;
    doomed.clear();
    doomed.addAll(profiles);
    kept.clear();
    next(s);
  }

  /** Deletes the password of the next connection, and then the connection; once the keyring is. */
  void next(State s) {
    if (doomed.isEmpty()) {
      done(s);
      return;
    }
    var profile = doomed.getFirst();
    // Patience from the start, also while the keyring is still being found.
    int at = ++patience;
    s.app.after(State.KEYRING_SECONDS, () -> {
      if (running && patience == at) {
        slow = true;
        if (s.quitting()) {
          giveUp(s); // closing never waits for a keyring that does not answer
        }
      }
    });
    if (s.keyring == null) {
      return; // keyringFound goes on
    }
    var secrets = s.secrets();
    if (secrets == null) {
      var names = String.join(", ", doomed.stream().map(State::name).toList());
      doomed.forEach(s::discard);
      doomed.clear();
      done(s);
      s.tell(
          "Without the system keyring, a password saved for " + names + " could not be removed.");
      return;
    }
    var id = profile.id();
    work = s.app.background(
        "delete a password",
        () -> {
          secrets.delete(id);
          return id;
        },
        done -> {
          work = null;
          slow = false;
          doomed.removeFirst();
          s.discard(profile);
          next(s);
        },
        failure -> {
          work = null;
          slow = false;
          doomed.removeFirst();
          kept.add(profile); // and the next ones are tried
          error = State.message(failure);
          next(s);
        });
  }

  /**
   * It is over: the consoles start again, and its dialog closes, or stays and says which
   * connections stayed.
   */
  private void done(State s) {
    running = false;
    slow = false;
    s.drop(act);
    if (!kept.isEmpty()) {
      s.keyringFailed();
      return;
    }
    s.deleted(folder);
  }

  /**
   * Cancel once the keyring has not answered for a while: the removal of the password is given
   * up, whatever it did, and so is the deletion; the dialog stays and says what that leaves.
   */
  void giveUp(State s) {
    if (work != null) {
      work.cancel(); // stops waiting; the removal itself runs on, before any later call
      work = null;
    }
    patience++;
    running = false;
    slow = false;
    error = folder == null ? DELETE_GAVE_UP : FOLDER_GAVE_UP;
    kept.addAll(doomed);
    doomed.clear();
    s.drop(act);
    s.keyringFailed();
  }
}
