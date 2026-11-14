package dev.hoori.db.core.connect;

import java.sql.Connection;

/**
 * An open connection, with what was read of it while it opened, within the time limit of {@link
 * Connector#open}, so that nothing needs to ask the driver for it afterwards.
 *
 * @param server the server's product and version
 * @param database the database the connection is on, or null where the driver names none
 */
public record Opened(Connection connection, Server server, String database) {}
