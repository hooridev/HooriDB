/**
 * The rows of tables written to files and read from them: CSV and TSV, and JSON as an array or as
 * lines, with the options each format has. A table's rows come from {@link
 * dev.hoori.db.core.data.Rows#each} and go as they come, never all at once; a file's rows go into
 * a table through {@link dev.hoori.db.core.data.Inserts}. Every call that reads or writes a file
 * blocks and belongs on a background thread.
 */
package dev.hoori.db.core.file;
