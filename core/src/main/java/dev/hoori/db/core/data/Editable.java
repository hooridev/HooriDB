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
   * Whether a session's temporary table can shadow a permanent table of the same name on this
   * provider, so that another connection cannot tell them apart.
   */
  public static boolean temporaryTablesShadow(Provider provider) {
    return !provider.keepsDocuments() && provider.dialect().rows().temporaryTablesShadow();
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
    var list = EditableQuery.singleTable(sql, provider).orElse(null);
    if (list == null) {
      return Optional.empty();
    }
    var dialect = provider.dialect().rows();
    // A * that stands for several columns moves the subqueries' columns from their positions.
    if (!list.subqueries().isEmpty() && list.items() != fields.size()
        || createdTemporaryTable && dialect.temporaryTablesShadow()) {
      return Optional.empty();
    }
    var origins = new ArrayList<Origin>(fields.size());
    for (var i = 0; i < fields.size(); i++) {
      origins.add(list.subqueries().contains(i) ? null : fields.get(i).origin());
    }
    var sample = oneTable(origins);
    var resolved =
        sample == null ? null : dialect.resolve(writer, provider, sample).orElse(null);
    var columns = resolved == null ? null : columns(writer, provider, resolved.table());
    if (columns == null || columns.stream().noneMatch(c -> c.keyPosition() > 0)) {
      return Optional.empty();
    }
    var kinds = kinds(writer, provider, resolved.table(), columns);
    if (kinds == null) {
      return Optional.empty();
    }
    var byName = new HashMap<String, Column>();
    columns.forEach(column -> byName.put(column.name(), column));
    var named = new ArrayList<Column>(fields.size());
    for (var origin : origins) {
      named.add(origin == null ? null : byName.get(resolved.columnName().apply(origin)));
    }
    var edited = new ArrayList<Column>(fields.size());
    for (var i = 0; i < fields.size(); i++) {
      var column = named.get(i);
      boolean editable = column != null
          && Collections.frequency(named, column) == 1
          && dialect.compatibleKind(fields.get(i).kind(), kinds.get(columns.indexOf(column)));
      edited.add(editable ? column : null);
    }
    boolean keyed = columns.stream().filter(c -> c.keyPosition() > 0).allMatch(edited::contains);
    return keyed ? Optional.of(new Editable(resolved.table(), columns, edited)) : Optional.empty();
  }

  // The origin of a field where those of all fields with one are of one table; null where none
  // has one or they are of several.
  private static Origin oneTable(List<Origin> origins) {
    Origin sample = null;
    for (var origin : origins) {
      if (sample == null) {
        sample = origin;
      } else if (origin != null && !sample.sameTable(origin)) {
        return null;
      }
    }
    return sample;
  }

  // The table's columns; null where it is gone.
  private static List<Column> columns(Connection writer, Provider provider, Table table)
      throws QueryException {
    try {
      return Catalog.columns(writer, provider, table);
    } catch (CatalogException e) {
      if (e.failure() == CatalogException.Failure.NOT_FOUND) {
        return null;
      }
      throw queryFailure(e);
    }
  }

  // The kinds the table's columns are read as; null where the table or its columns changed.
  private static List<Kind> kinds(
      Connection writer, Provider provider, Table table, List<Column> columns)
      throws QueryException {
    try {
      return provider
          .dialect()
          .rows()
          .describe(writer, provider, table, columns, false)
          .reads()
          .stream()
          .map(read -> read.field().kind())
          .toList();
    } catch (QueryException e) {
      if (e.failure() == QueryException.Failure.NOT_FOUND
          || e.failure() == QueryException.Failure.COLUMNS_CHANGED) {
        return null;
      }
      throw e;
    }
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
