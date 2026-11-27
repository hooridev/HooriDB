/**
 * A server's functions and procedures beyond reading them from the catalog: what changing,
 * dropping and calling one sends, and sending a change on a connection of its own. Every call that
 * reaches a server blocks and belongs on a background thread; interrupting that thread cancels it.
 */
package dev.hoori.db.core.routine;
