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
  QueryDialog queryDialog; // a query's name, save or delete dialog; null while closed
  ExportDialog export; // Export data…, null while closed
  ImportDialog importing; // Import data…, null while closed
  UUID placing; // the folder the new connection's dialog puts it in, or null for the top
  boolean about; // whether the About dialog is open
  // The modeless settings window, null while closed; not a modal dialog, so anyOpen() skips it.
  SettingsWindow settings;

  /** Whether a dialog is open, which an act that opens one must not replace. */
  boolean anyOpen() {
    return dialog != null
        || deleting != null
        || folderDialog != null
        || queryDialog != null
        || about
        || driverList != null
        || export != null
        || importing != null;
  }

  /** Ends the sessions of the dialogs that have one, of the connection or of all where null. */
  void end(State s, UUID id) {
    if (export != null && (id == null || export.id.equals(id))) {
      export.end(s);
    }
    if (importing != null && (id == null || importing.id.equals(id))) {
      importing.end(s);
    }
  }
}
