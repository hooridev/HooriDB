/**
 * What a server does now, as its monitor shows it: the {@link
 * dev.hoori.db.core.monitor.ServerSession}s of its clients, read by a {@link
 * dev.hoori.db.core.monitor.Monitor} on a connection of the monitor's own. What each provider
 * tells and how is its dialect's {@link dev.hoori.db.core.dialect.MonitorDialect}, or for a server
 * of documents its {@link dev.hoori.db.core.dialect.DocumentMonitorDialect}; the code here names
 * no provider. Statements of other sessions may hold credentials, which {@link
 * dev.hoori.db.core.monitor.Credentials} masks before they leave core.
 */
package dev.hoori.db.core.monitor;
