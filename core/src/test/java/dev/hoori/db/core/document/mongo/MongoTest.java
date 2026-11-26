package dev.hoori.db.core.document.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterConnectionMode;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bson.BsonBinary;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDbPointer;
import org.bson.BsonRegularExpression;
import org.bson.BsonTimestamp;
import org.bson.RawBsonDocument;
import org.bson.io.BasicOutputBuffer;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/** Driver settings and raw BSON decoding in {@link Mongo}, without talking to a server. */
class MongoTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static RawBsonDocument raw(Consumer<BsonBinaryWriter> write) {
    var buffer = new BasicOutputBuffer();
    try (var writer = new BsonBinaryWriter(buffer)) {
      writer.writeStartDocument();
      write.accept(writer);
      writer.writeEndDocument();
    }
    return new RawBsonDocument(buffer.toByteArray());
  }

  @Test
  void anSrvProfileNamesTheRecordAndTakesEveryServerItLists() {
    var profile = ConnectionProfile.of(Provider.MONGODB)
        .withHost("Cluster0.Example.NET")
        .withPort(1)
        .withSrv(true);
    var settings = Mongo.settings(DocumentConnector.target(profile, TIMEOUT)).build();
    var cluster = settings.getClusterSettings();
    assertEquals("cluster0.example.net", cluster.getSrvHost());
    assertEquals(ClusterConnectionMode.MULTIPLE, cluster.getMode());
    assertTrue(settings.getSslSettings().isEnabled());
    var rooted = Mongo.settings(DocumentConnector.target(profile.withHost("example.net."), TIMEOUT))
        .build()
        .getClusterSettings();
    assertEquals("example.net", rooted.getSrvHost());
  }

  @Test
  void aHostProfileConnectsToThatServerAlone() {
    var profile = ConnectionProfile.of(Provider.MONGODB).withHost("db.example").withPort(27018);
    var cluster =
        Mongo.settings(DocumentConnector.target(profile, TIMEOUT)).build().getClusterSettings();
    assertNull(cluster.getSrvHost());
    assertEquals(List.of(new ServerAddress("db.example", 27018)), cluster.getHosts());
    assertEquals(ClusterConnectionMode.SINGLE, cluster.getMode());
  }

  @Test
  void rawDecodingKeepsDuplicateFieldNamesInOrderAtEveryNestingLevel() {
    var raw = raw(w -> {
      w.writeInt32("dup", 1);
      w.writeString("dup", "two");
      w.writeInt64("dup", 3L);

      w.writeStartDocument("nested");
      w.writeString("k", "first");
      w.writeBoolean("k", true);
      w.writeEndDocument();

      w.writeStartArray("arr");
      w.writeStartDocument();
      w.writeDouble("x", 1.5);
      w.writeNull("x");
      w.writeEndDocument();
      w.writeInt32(99);
      w.writeEndArray();

      w.writeJavaScriptWithScope("code", "function() { return a; }");
      w.writeStartDocument();
      w.writeInt32("a", 10);
      w.writeInt32("a", 20);
      w.writeEndDocument();
    });

    var expected = new Document(List.of(
        new Document.Entry("dup", 1),
        new Document.Entry("dup", "two"),
        new Document.Entry("dup", 3L),
        new Document.Entry(
            "nested",
            new Document(List.of(new Document.Entry("k", "first"), new Document.Entry("k", true)))),
        new Document.Entry(
            "arr",
            Document.array(
                new Document(List.of(new Document.Entry("x", 1.5), new Document.Entry("x", null))),
                99)),
        new Document.Entry(
            "code",
            new Bson.Code(
                "function() { return a; }",
                new Document(List.of(new Document.Entry("a", 10), new Document.Entry("a", 20)))))));

    assertEquals(expected, Mongo.document(raw));
  }

  @Test
  void rawDecodingPreservesEveryBsonType() {
    var idHex = "64b7f0c2a1b2c3d4e5f60718";
    var id = new Bson.ObjectId(idHex);
    var uuid = UUID.fromString("3b241101-e2bb-4255-8caf-4136c566a962");
    var uuidBin = Bson.Binary.of(uuid);
    var instant = Instant.parse("2024-01-02T03:04:05.678Z");

    var raw = raw(w -> {
      w.writeDouble("double", 3.5);
      w.writeString("string", "hello");
      w.writeStartDocument("document");
      w.writeInt32("inner", 1);
      w.writeEndDocument();
      w.writeStartArray("array");
      w.writeString("a");
      w.writeNull();
      w.writeInt64(2L);
      w.writeEndArray();
      w.writeBinaryData("binary", new BsonBinary((byte) 128, new byte[] {0x7f, (byte) 0x80}));
      w.writeBinaryData("uuid", new BsonBinary((byte) uuidBin.subtype(), uuidBin.bytes()));
      w.writeUndefined("undefined");
      w.writeObjectId("objectId", new ObjectId(idHex));
      w.writeBoolean("boolean", true);
      w.writeDateTime("dateTime", instant.toEpochMilli());
      w.writeNull("null");
      w.writeRegularExpression("regex", new BsonRegularExpression("^abc$", "im"));
      w.writeDBPointer("dbPointer", new BsonDbPointer("hoori.items", new ObjectId(idHex)));
      w.writeJavaScript("javascript", "function() { return 1; }");
      w.writeSymbol("symbol", "legacy");
      w.writeJavaScriptWithScope("javascriptWithScope", "function() { return x; }");
      w.writeStartDocument();
      w.writeInt32("x", 7);
      w.writeEndDocument();
      w.writeInt32("int32", 42);
      // Unsigned 32-bit values above Integer.MAX_VALUE to verify unsigned conversion.
      w.writeTimestamp("timestamp", new BsonTimestamp((int) 0x8000_0001L, (int) 0xffff_ffffL));
      w.writeInt64("int64", 9_000_000_000L);
      w.writeDecimal128("decimal128", Decimal128.parse("19.90"));
      w.writeMinKey("minKey");
      w.writeMaxKey("maxKey");
    });

    var expected = Document.of(
        "double",
        3.5,
        "string",
        "hello",
        "document",
        Document.of("inner", 1),
        "array",
        Document.array("a", null, 2L),
        "binary",
        new Bson.Binary(128, new byte[] {0x7f, (byte) 0x80}),
        "uuid",
        uuidBin,
        "undefined",
        Bson.Special.UNDEFINED,
        "objectId",
        id,
        "boolean",
        true,
        "dateTime",
        instant,
        "null",
        null,
        "regex",
        new Bson.Regex("^abc$", "im"),
        "dbPointer",
        new Bson.DbPointer("hoori.items", id),
        "javascript",
        new Bson.Code("function() { return 1; }", null),
        "symbol",
        new Bson.Symbol("legacy"),
        "javascriptWithScope",
        new Bson.Code("function() { return x; }", Document.of("x", 7)),
        "int32",
        42,
        "timestamp",
        new Bson.Timestamp(0x8000_0001L, 0xffff_ffffL),
        "int64",
        9_000_000_000L,
        "decimal128",
        new Bson.Decimal128("19.90"),
        "minKey",
        Bson.Special.MIN_KEY,
        "maxKey",
        Bson.Special.MAX_KEY);

    assertEquals(expected, Mongo.document(raw));
  }
}
