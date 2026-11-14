package dev.hoori.db.core.catalog;

/**
 * A database of a server. A system database is one the server keeps for its own use, such as
 * MySQL's {@code mysql} or PostgreSQL's templates. On MariaDB and MySQL a database is also what
 * other servers call a schema, and holds the tables itself.
 */
public record Database(String name, boolean system) implements Container {

  @Override
  public Database database() {
    return this;
  }
}
