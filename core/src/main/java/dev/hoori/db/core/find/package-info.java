/**
 * Searches of a database or schema: the names and definitions of its objects, which {@link
 * dev.hoori.db.core.find.Finder} reads for a server of SQL and {@link
 * dev.hoori.db.core.find.DocumentFinder} for one of documents, each on a connection of the
 * search's own, and the values in its tables, a table at a time, counted up to {@link
 * dev.hoori.db.core.find.Search#LIMIT} rows each. What each provider reads and how is its
 * dialect's {@link dev.hoori.db.core.dialect.FindDialect}; the code here names no provider.
 */
package dev.hoori.db.core.find;
