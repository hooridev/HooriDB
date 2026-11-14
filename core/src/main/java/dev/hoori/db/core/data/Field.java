package dev.hoori.db.core.data;

/**
 * A column of a page.
 *
 * @param type the type as the server names it, as the catalogue read it
 */
public record Field(String name, String type, Kind kind) {}
