package dev.hoori.db.core.data;

import java.util.List;

/**
 * Rows of a table or view, with their columns. Each row holds a value per field, in the fields'
 * order, of the Java type that the field's {@link Kind} says; the lists cannot be changed.
 */
public record Page(List<Field> fields, List<List<Object>> rows) {}
