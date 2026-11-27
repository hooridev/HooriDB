/**
 * Comparing the rows of two tables and making the target's equal to the source's. A {@link
 * dev.hoori.db.core.sync.Pairing} matches their columns by name and pairs their rows by a key they
 * share; a {@link dev.hoori.db.core.sync.Merge} reads both in that key's order, each a portion at a
 * time from a {@link dev.hoori.db.core.sync.Cursor}, as {@link dev.hoori.db.core.data.KeyedRead}
 * gives them, and tells each {@link dev.hoori.db.core.sync.Difference}, never holding a table
 * whole; the pairing makes the {@link dev.hoori.db.core.data.Change}s of the target that a {@link
 * dev.hoori.db.core.sync.Choice} takes, which {@link dev.hoori.db.core.data.Changes.Writer} writes
 * in one transaction. Nothing here talks to a server itself or names a provider.
 */
package dev.hoori.db.core.sync;
