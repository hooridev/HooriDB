package dev.hoori.db.app;

import dev.hoori.db.core.connection.ConnectionProfile;
import java.util.UUID;

/**
 * The dialogs of the main window that are open, a part of the state: one at a time, each null or
 * false while closed. Only the app's thread uses it.
 */
final class Dialogs {

  ConnectionDialog dialog; // the connection's, null while closed
  DriversDialog driverList; // the Drivers dialog, null while closed
  ConnectionProfile deleting; // the connection the delete dialog asks about, or null
  FolderDialog folderDialog; // a folder's name or the question before its delete; null while closed
  UUID placing; // the folder the new connection's dialog puts it in, or null for the top
  boolean about; // whether the About dialog is open
  FileRunDialog fileRun; // Run SQL file, shown or hidden while its file runs; null while closed

  /** Whether a dialog is open, which an act that opens one must not replace. */
  boolean anyOpen() {
    return dialog != null
        || deleting != null
        || folderDialog != null
        || about
        || driverList != null
        || fileRun != null && fileRun.shown();
  }
}
