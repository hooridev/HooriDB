package dev.hoori.db.core.data;

import java.util.OptionalLong;

/**
 * A text too long to read whole: its first {@link Rows#LIMIT} characters, and its full length in
 * bytes as the server stores it, where that is known: it is of a table's column, and not of a
 * statement's result, which arrives whole in the connection's character set rather than the
 * column's.
 */
public record Cut(String start, OptionalLong length) {}
