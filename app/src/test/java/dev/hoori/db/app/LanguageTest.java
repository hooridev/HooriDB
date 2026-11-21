package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.hoori.db.core.connection.Provider;
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
}
