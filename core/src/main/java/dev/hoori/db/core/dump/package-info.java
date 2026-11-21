/**
 * Exports a database's objects to a file of SQL: their definitions ({@link
 * dev.hoori.db.core.dump.Definition}), built for each provider by its {@link
 * dev.hoori.db.core.dump.DumpDialect}, in the order that creates them ({@code Order}), and the rows
 * of its tables, all read in one snapshot ({@link dev.hoori.db.core.dump.Dump}). MongoDB has none.
 */
package dev.hoori.db.core.dump;
