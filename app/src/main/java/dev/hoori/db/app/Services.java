package dev.hoori.db.app;

import dev.hoori.db.core.secret.Keyring;
import java.util.concurrent.Callable;

/**
 * What the app reaches outside itself through, each off the app's thread: the real ones in a
 * window, made-up ones or none in a test or a script run.
 *
 * @param keyring finds the system keyring
 * @param drivers where drivers are kept and come from, or null for none
 * @param tester tests a connection
 * @param servers opens connections and reads their catalog
 * @param updater updates HooriDB, or null for none
 */
record Services(
    Callable<Keyring> keyring, Drivers drivers, Tester tester, Servers servers, Updater updater) {}
