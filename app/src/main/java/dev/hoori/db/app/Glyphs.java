package dev.hoori.db.app;

import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.text.Font;
import dev.hoori.db.core.connection.Workspace;

/** Fluent's regular glyphs of a database tool, which the kit's symbols do not carry. */
final class Glyphs {
  private static final Font FONT = Font.resource(Glyphs.class, "regular-icons.ttf");

  /** A schema's tables, apart from the workspace's folders. */
  static final Icon SCHEMA = Icon.glyph(FONT, 0xE6C3, "schema");

  private static final Icon SHELL = Icon.glyph(FONT, 0xF0A26, "MongoDB query");

  private Glyphs() {}

  /** A workspace query of the language: SQL's a document of a table, the shell's of code. */
  static Icon query(Workspace.Language language) {
    return switch (language) {
      case SQL -> Symbol.DOCUMENT_TABLE;
      case SHELL -> SHELL;
    };
  }
}
