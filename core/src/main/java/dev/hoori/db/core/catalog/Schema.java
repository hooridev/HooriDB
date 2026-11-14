package dev.hoori.db.core.catalog;

/**
 * A schema of a PostgreSQL database. A system schema is one the server keeps for its own use, such
 * as {@code pg_catalog}, {@code information_schema} or {@code pg_toast}.
 */
public record Schema(Database database, String name, boolean system) implements Container {}
