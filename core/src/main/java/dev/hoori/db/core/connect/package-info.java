/**
 * Connecting to a database server: a profile, its password and a loaded driver become an open
 * connection, or a typed failure. Every call blocks and belongs on a background thread;
 * interrupting that thread cancels it.
 */
package dev.hoori.db.core.connect;
