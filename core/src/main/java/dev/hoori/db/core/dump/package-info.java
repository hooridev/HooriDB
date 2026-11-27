/**
 * Exports a database's objects to a file of SQL: their definitions ({@link
 * dev.hoori.db.core.definition.Definition}), as the provider's {@link
 * dev.hoori.db.core.definition.DefinitionDialect} reads them, in the order that creates them
 * ({@code Order}), and the rows of its tables, as its {@link dev.hoori.db.core.dump.DumpDialect}
 * reads them, all read in one snapshot ({@link dev.hoori.db.core.dump.Dump}). MongoDB has none.
 */
package dev.hoori.db.core.dump;
