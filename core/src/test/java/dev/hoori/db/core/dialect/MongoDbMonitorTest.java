package dev.hoori.db.core.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import java.util.List;
import org.junit.jupiter.api.Test;

class MongoDbMonitorTest {

  private static Object op(String operation) {
    return Provider.MONGODB.documents().monitor().cancel(operation).get("op");
  }

  @Test
  void anOperationIsCancelledByItsNumberOrThroughMongosByItsShardAndNumber() {
    assertEquals(7, op("7"));
    assertEquals(1_000_000_000, op("1000000000"));
    assertEquals(Integer.MAX_VALUE, op(String.valueOf(Integer.MAX_VALUE)));
    assertEquals("shard-a:12345", op("shard-a:12345"));
  }

  @Test
  void theSessionsAreTheRoutersOwnThroughMongos() {
    var command = Provider.MONGODB.documents().monitor().sessions(true, "mark");
    var stage = (Document) ((List<?>) command.get("pipeline")).getFirst();
    assertEquals(true, ((Document) stage.get("$currentOp")).get("localOps"));
  }
}
