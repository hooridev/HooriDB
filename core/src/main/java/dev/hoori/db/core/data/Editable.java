package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.EditableQuery;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a console statement's result can be edited, and which permanent base table and columns
 * each result field edits.
 *
 * <p>A result is editable only when the statement's text passes {@link EditableQuery#singleTable},
 * all result fields with an {@link Origin} belong to one permanent base table with a primary key
 * resolved fresh on the writing connection, and every column of the primary key appears once as an
 * editable field. A result field is editable when its origin belongs to that table, appears once
 * among the result's fields, and has a kind compatible with how the table's own reads describe that
 * column (leaving expressions, repeated columns, system columns and ambiguous types such as MariaDB
 * and MySQL {@code timestamp} read-only).
 *
 * <p>Whether an individual cell's value was read whole or cut short ({@link Cut} or a {@link
 * Binary#cut} value) is a per-cell check for the caller, not part of this decision.
 *
 * @param table the permanent base table the result's rows come from
 * @param columns all the table's columns, in their order, as {@link Catalog#columns} read them
 * @param edited for each field of the result, in the result's order, the column of {@code table}
 *     that it edits, or null where that field is read-only
 */
public record Editable(Table table, List<Column> columns, List<Column> edited) {

  public Editable {
    Objects.requireNonNull(table, "table");
    columns = List.copyOf(columns);
    edited = Collections.unmodifiableList(new ArrayList<>(edited));
  }

  /**
   * Decides whether the result with the given fields, produced by {@code sql}, can be edited
   * through {@code writer}.
   *
   * @param writer the connection that will write the changes, on the console's database
   * @param provider the server's provider
   * @param sql the statement that produced the result
   * @param fields the result's fields, with their {@link Field#origin} as {@link RowReader} read
   *     them
   * @param createdTemporaryTable whether the console's session has run a statement for which {@link
   *     EditableQuery#createsTemporaryTable} was true
   * @return the target table and per-field column mapping, or empty where the result cannot be
   *     edited
   */
  public static Optional<Editable> of(
      Connection writer,
      Provider provider,
      String sql,
      List<Field> fields,
      boolean createdTemporaryTable)
      throws QueryException {
    if (!EditableQuery.singleTable(sql, provider)) {
      return Optional.empty();
    }
    var dialect = provider.dialect().rows();
    if (createdTemporaryTable && dialect.temporaryTablesShadow()) {
      return Optional.empty();
    }
    Origin sample = null;
    for (var field : fields) {
      var origin = field.origin();
      if (origin == null) {
        continue;
      }
      if (sample == null) {
        sample = origin;
      } else if (!sample.sameTable(origin)) {
        return Optional.empty();
      }
    }
    if (sample == null) {
      return Optional.empty();
    }
    var resolved = dialect.resolve(writer, provider, sample);
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    var table = resolved.get().table();
    List<Column> columns;
    try {
      columns = Catalog.columns(writer, provider, table);
    } catch (CatalogException e) {
      if (e.failure() == CatalogException.Failure.NOT_FOUND) {
        return Optional.empty();
      }
      throw queryFailure(e);
    }
    var keyCount = (int) columns.stream().filter(c -> c.keyPosition() > 0).count();
    if (keyCount == 0) {
      return Optional.empty();
    }
    Rows.Described described;
    try {
      described = dialect.describe(writer, provider, table, columns);
    } catch (QueryException e) {
      if (e.failure() == QueryException.Failure.NOT_FOUND
          || e.failure() == QueryException.Failure.COLUMNS_CHANGED) {
        return Optional.empty();
      }
      throw e;
    }
    var byName = new HashMap<String, Integer>();
    for (var i = 0; i < columns.size(); i++) {
      byName.put(columns.get(i).name(), i);
    }
    var columnIndex = new Integer[fields.size()];
    var seen = new int[columns.size()];
    var columnName = resolved.get().columnName();
    for (var i = 0; i < fields.size(); i++) {
      var origin = fields.get(i).origin();
      if (origin == null) {
        continue;
      }
      var name = columnName.apply(origin);
      var index = name == null ? null : byName.get(name);
      if (index != null) {
        columnIndex[i] = index;
        seen[index]++;
      }
    }
    var edited = new ArrayList<Column>(fields.size());
    var editableKeys = new HashSet<Column>();
    for (var i = 0; i < fields.size(); i++) {
      var index = columnIndex[i];
      if (index != null
          && seen[index] == 1
          && dialect.compatibleKind(
              fields.get(i).kind(), described.reads().get(index).field().kind())) {
        var column = columns.get(index);
        edited.add(column);
        if (column.keyPosition() > 0) {
          editableKeys.add(column);
        }
      } else {
        edited.add(null);
      }
    }
    if (editableKeys.size() != keyCount) {
      return Optional.empty();
    }
    return Optional.of(new Editable(table, columns, edited));
  }

  private static QueryException queryFailure(CatalogException e) {
    var failure = switch (e.failure()) {
      case CONNECTION_LOST -> QueryException.Failure.CONNECTION_LOST;
      case NOT_FOUND -> QueryException.Failure.NOT_FOUND;
      case ACCESS_DENIED -> QueryException.Failure.ACCESS_DENIED;
      case CANCELLED -> QueryException.Failure.CANCELLED;
      case ABORTED -> QueryException.Failure.ABORTED;
      case WRONG_DATABASE, OTHER -> QueryException.Failure.OTHER;
    };
    return QueryException.of(failure, e.detail());
  }
}
