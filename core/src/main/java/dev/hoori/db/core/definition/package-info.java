/**
 * The definitions of a server's objects, the statements of SQL that create them as they are now,
 * from one place: each provider's {@link dev.hoori.db.core.definition.DefinitionDialect} lists the
 * objects of a schema or database and builds their {@link
 * dev.hoori.db.core.definition.Definition}s in one snapshot, for an export to a file ({@link
 * dev.hoori.db.core.dump}), a view's tab ({@link dev.hoori.db.core.definition.Views}) and what
 * copies an object's structure. A view's definition is changed by a {@link
 * dev.hoori.db.core.definition.ViewChange}, whose statements are shown before they run. MongoDB has
 * none.
 */
package dev.hoori.db.core.definition;
