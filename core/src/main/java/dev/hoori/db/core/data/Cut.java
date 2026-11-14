package dev.hoori.db.core.data;

/**
 * A text too long to read whole: its first {@link Rows#LIMIT} characters, and its full length in
 * bytes, as the server stores it.
 */
public record Cut(String start, long length) {}
