package dev.hoori.db.app;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import java.util.UUID;

/**
 * A row of the catalogue: a connection, or what its server holds, as the records core read. Nodes
 * compare with {@code equals}, so that the selection and what is open survive a new read.
 */
sealed interface Node {

  /** The connection's id. */
  UUID id();

  /** The node it is under, or null for a connection. */
  Node parent();

  /** Whether it is the node or under it. */
  default boolean within(Node node) {
    for (Node n = this; n != null; n = n.parent()) {
      if (n.equals(node)) {
        return true;
      }
    }
    return false;
  }

  record Connection(UUID id) implements Node {
    @Override
    public Node parent() {
      return null;
    }
  }

  record OfDatabase(UUID id, Database database) implements Node {
    @Override
    public Node parent() {
      return new Connection(id);
    }
  }

  record OfSchema(UUID id, Schema schema) implements Node {
    @Override
    public Node parent() {
      return new OfDatabase(id, schema.database());
    }
  }

  record OfTable(UUID id, Table table) implements Node {
    @Override
    public Node parent() {
      return switch (table.container()) {
        case Database d -> new OfDatabase(id, d);
        case Schema s -> new OfSchema(id, s);
      };
    }
  }

  record OfColumn(UUID id, Column column) implements Node {
    @Override
    public Node parent() {
      return new OfTable(id, column.table());
    }
  }

  /** The row under a node that says why it could not be opened or read. */
  record Problem(Node of) implements Node {
    @Override
    public UUID id() {
      return of.id();
    }

    @Override
    public Node parent() {
      return of;
    }
  }
}
