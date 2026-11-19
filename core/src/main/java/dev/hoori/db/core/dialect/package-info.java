/**
 * What each provider does its own way: its SQL, its driver's behavior beyond JDBC, its error codes.
 * One class per provider implements {@link dev.hoori.db.core.dialect.Dialect}, which {@link
 * dev.hoori.db.core.connection.Provider#dialect} hands out; the code that uses it names no provider.
 */
package dev.hoori.db.core.dialect;
