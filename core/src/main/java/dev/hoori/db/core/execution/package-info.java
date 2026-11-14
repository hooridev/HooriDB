/**
 * Running one statement of any kind as the user wrote it, with its results, messages and failure
 * as values, its rows read a portion at a time. Every call blocks and belongs on a background
 * thread; interrupting that thread cancels it.
 */
package dev.hoori.db.core.execution;
