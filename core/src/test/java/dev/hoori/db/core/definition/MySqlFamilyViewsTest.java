package dev.hoori.db.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.connection.Provider;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MySqlFamilyViewsTest {

  @Test
  void aDefinitionNamesTheObjectsOfTheDatabasesItQualifiesThemWith() {
    var named = MySqlFamilyViews.named(
        Provider.MYSQL,
        "select `shop`.`item`.`id` AS `id`,`shop`.`price`(`shop`.`item`.`id`) AS `p`"
            + " from (`shop`.`item` join `other db`.`kept` `k`)"
            + " where `k`.`note` <> '`text`.`not`'");
    assertEquals(
        Set.of(
            new MySqlFamilyViews.Named("shop", "item", false),
            new MySqlFamilyViews.Named("shop", "price", true),
            new MySqlFamilyViews.Named("other db", "kept", false),
            new MySqlFamilyViews.Named("k", "note", false)),
        named);
  }
}
