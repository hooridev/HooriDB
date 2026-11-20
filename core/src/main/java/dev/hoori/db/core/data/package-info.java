/**
 * The rows of tables and views, a page at a time, with the number of rows estimated and counted,
 * and changes of a table's rows written back. Every call that talks to a server blocks and belongs
 * on a background thread; interrupting that thread cancels it.
 */
package dev.hoori.db.core.data;
