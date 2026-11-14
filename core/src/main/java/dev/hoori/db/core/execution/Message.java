package dev.hoori.db.core.execution;

/**
 * Something the server said along the way: on PostgreSQL a {@code NOTICE} or {@code WARNING}, as
 * of {@code RAISE}, with its SQLSTATE, such as 00000 for a notice and 01000 for a warning; on
 * MariaDB and MySQL a note or warning, with its code, such as 1265 for data truncated.
 *
 * @param state the SQLSTATE, empty where the driver gives none
 * @param code the server's code, 0 where it has none
 */
public record Message(String text, String state, int code) {}
