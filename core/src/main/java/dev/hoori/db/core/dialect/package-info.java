/**
 * What each provider does its own way: its SQL, its driver's behavior beyond JDBC, its error codes,
 * its lexical rules. {@link dev.hoori.db.core.connection.Provider#dialect} hands out a provider's
 * {@link dev.hoori.db.core.dialect.Dialect}, which gives one object per concern; the code that uses
 * them names no provider.
 *
 * <p>One class per provider holds its concerns as nested classes: {@link
 * dev.hoori.db.core.dialect.PostgreSql}, and {@code MySqlFamily} with what MariaDB and MySQL share,
 * which {@link dev.hoori.db.core.dialect.MariaDb} and {@link dev.hoori.db.core.dialect.MySql}
 * complete. A concern's class sits beside the package it serves where it needs that package's
 * private members, as {@link dev.hoori.db.core.data.RowsDialect} and its classes sit beside {@code
 * Rows}; otherwise it is here. Where MariaDB and MySQL do not differ in a concern, the family's
 * class serves both, with a constructor parameter for a small difference. A trait is named for what
 * the server or driver does, never for which one it is, so that a new provider answers each.
 */
package dev.hoori.db.core.dialect;
