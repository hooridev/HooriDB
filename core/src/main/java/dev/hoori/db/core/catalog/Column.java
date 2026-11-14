package dev.hoori.db.core.catalog;

/**
 * A column of a table or view. The type is as the server names it, with its length or precision,
 * such as {@code character varying(100)} on PostgreSQL or {@code decimal(12,2)} on MySQL.
 *
 * @param keyPosition where the column comes in the primary key, from 1, or 0 if it is not part of
 *     it
 */
public record Column(Table table, String name, String type, boolean nullable, int keyPosition) {}
