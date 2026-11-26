package dev.hoori.db.core.data;

import java.util.OptionalLong;

/**
 * A text kept only in part: its first {@link Rows#LIMIT} characters, and its full length in bytes
 * as the server stores it, where that is known: it is of a table's column, and not of a
 * statement's result, which keeps long texts whole up to {@link Rows#WHOLE_LIMIT} each and
 * {@link RowReader#BUDGET} in all, and arrives in the connection's character set rather than the
 * column's.
 */
public record Cut(String start, OptionalLong length) {}
