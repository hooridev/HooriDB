/**
 * JDBC drivers, which HooriDB does not ship: each is downloaded from a Maven repository when a
 * connection needs it, checked, kept in a folder of the user's data and loaded in a class loader
 * of its own. Every call that touches the network or the disk blocks and belongs on a background
 * thread; interrupting that thread cancels it.
 */
package dev.hoori.db.core.driver;
