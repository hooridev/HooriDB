package dev.hoori.db.core.sql;

/**
 * A statement in a text of SQL, as offsets in {@code char}s: it runs from {@code start} up to,
 * not including, {@code end}. It starts at its first character that is neither whitespace nor a
 * comment and ends after its last such character, so it holds neither its terminator nor the
 * comments around it; {@code text.substring(start, end)} is what goes to the server.
 */
public record Statement(int start, int end) {}
