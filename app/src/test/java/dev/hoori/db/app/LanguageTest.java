package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Workspace;
import org.junit.jupiter.api.Test;

class LanguageTest {

  @Test
  void aServerOfDocumentsSpeaksTheShellAndEveryOtherSql() {
    for (var provider : Provider.values()) {
      var language = Language.of(provider);
      if (provider == Provider.MONGODB) {
        assertInstanceOf(Language.Shell.class, language);
        assertInstanceOf(ShellCompletion.class, language.completion());
      } else {
        assertInstanceOf(Language.Sql.class, language, provider.name());
        assertInstanceOf(SqlCompletion.class, language.completion());
      }
      assertEquals(provider.keepsDocuments(), language.documents(), provider.name());
      assertEquals(!provider.keepsDocuments(), language.transactions(), provider.name());
      assertEquals(!provider.keepsDocuments(), language.estimates(), provider.name());
    }
  }

  @Test
  void untargetedLanguagesUseDialectNeutralColoringAndSplitting() {
    var sql = Language.of(Workspace.Language.SQL);
    assertInstanceOf(StandardSyntax.class, Syntax.of(sql));
    // Standard SQL does not treat PostgreSQL dollar quotes or MySQL backticks specially.
    assertEquals(2, sql.statements("SELECT $$a;b$$", null).statements().size());
    assertEquals(2, sql.statements("SELECT `a;b`", null).statements().size());

    var shell = Language.of(Workspace.Language.SHELL);
    assertInstanceOf(ShellSyntax.class, Syntax.of(shell));
    assertEquals(
        2, shell.statements("db.a.find({});\ndb.b.find({})", null).statements().size());
  }
}
