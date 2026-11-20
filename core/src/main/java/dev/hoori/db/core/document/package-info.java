/**
 * Servers of documents, such as MongoDB: their values as {@link dev.hoori.db.core.document.Document}
 * and {@link dev.hoori.db.core.document.Bson}, the syntax of their shell as {@link
 * dev.hoori.db.core.document.Shell} reads and writes it, and its statements as the server's commands
 * that {@link dev.hoori.db.core.document.Script} makes of them. A connection that is read-only sends
 * only what {@link dev.hoori.db.core.document.Commands#reads} lets through.
 */
package dev.hoori.db.core.document;
