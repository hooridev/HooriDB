package dev.hoori.db.core.document.mongo;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterConnectionMode;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentClient;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.DocumentException.Kind;
import dev.hoori.db.core.document.Target;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonJavaScript;
import org.bson.BsonJavaScriptWithScope;
import org.bson.BsonMaxKey;
import org.bson.BsonMinKey;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
import org.bson.BsonSymbol;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.BsonValue;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/**
 * What {@link MongoBridge} does with the MongoDB Java driver, apart from it, so that the bridge
 * loads and links without the driver's classes and this class only once it connects.
 */
final class Mongo {

  private Mongo() {}

  static DocumentClient connect(Target target, char[] password) throws DocumentException {
    var settings = settings(target);
    if (!target.user().isEmpty()) {
      settings.credential(
          MongoCredential.createCredential(target.user(), target.authDatabase(), password.clone()));
    }
    MongoClient client;
    try {
      client = MongoClients.create(settings.build());
    } catch (RuntimeException e) {
      throw failure(e);
    }
    try {
      var session = client.startSession(
          ClientSessionOptions.builder().causallyConsistent(false).build());
      return new Client(client, session);
    } catch (RuntimeException e) {
      client.close();
      throw failure(e);
    }
  }

  /** How the client reaches the target, without the login. */
  static MongoClientSettings.Builder settings(Target target) {
    var timeout = target.timeout().toMillis();
    return MongoClientSettings.builder()
        .applicationName("HooriDB")
        .applyToClusterSettings(cluster -> {
          // The driver lowercases a name in the JVM's locale, which in Turkish turns I into a
          // dotless ı: in the root locale here first.
          var host = target.host().toLowerCase(Locale.ROOT);
          if (target.srv()) {
            // The servers the record lists, as mongodb+srv:// finds them. shortcut: the options
            // of its TXT record (replicaSet, loadBalanced) are not read, as discovery finds the
            // set; read them once a cluster needs one.
            // Without the root's dot, which the driver counts as a label: of "example.net." it
            // takes ".net" for the domain the servers' names must end in, and refuses that.
            var name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            cluster.srvHost(name).mode(ClusterConnectionMode.MULTIPLE);
          } else {
            // As the shell connects to one host: to that server alone, also to a member of a
            // replica set whose other members are known by names that do not resolve here.
            cluster
                .hosts(List.of(new ServerAddress(host, target.port())))
                .mode(ClusterConnectionMode.SINGLE);
          }
          cluster.serverSelectionTimeout(timeout, TimeUnit.MILLISECONDS);
        })
        .applyToSocketSettings(
            socket -> socket.connectTimeout((int) timeout, TimeUnit.MILLISECONDS))
        .applyToSslSettings(ssl -> {
          ssl.enabled(target.tls() != Tls.OFF);
          if (target.tls() == Tls.ENCRYPTED) {
            // Encrypted, but any certificate for any name, as the JDBC drivers' "encrypted"
            // (sslmode=require, trustServerCertificate) on the user's choice: Verified checks it.
            // The driver has no option for it, so it gets a trust manager that takes all.
            ssl.invalidHostNameAllowed(true).context(trustingAll());
          }
        });
  }

  private static SSLContext trustingAll() {
    try {
      var context = SSLContext.getInstance("TLS");
      context.init(
          null,
          new TrustManager[] {
            new X509TrustManager() {
              @Override
              public void checkClientTrusted(X509Certificate[] chain, String type) { // NOSONAR
                // HooriDB is the client; no server asks it to be trusted here.
              }

              @Override
              public void checkServerTrusted(X509Certificate[] chain, String type) { // NOSONAR
                // Any certificate: Encrypted, as the user chose it, does not check the server's.
              }

              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            }
          },
          null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Client(MongoClient client, ClientSession session) implements DocumentClient {

    @Override
    public Document command(String database, Document command) throws DocumentException {
      if (Thread.currentThread().isInterrupted()) {
        throw DocumentException.of(Kind.CANCELLED, "");
      }
      try {
        var reply =
            client.getDatabase(database).runCommand(session, document(command), BsonDocument.class);
        return document(reply);
      } catch (RuntimeException e) {
        throw failure(e);
      }
    }

    @Override
    public void close() {
      try {
        session.close();
      } catch (RuntimeException e) {
        // Ended with the client either way.
      }
      client.close();
    }
  }

  /** What the driver threw, as core's failure. */
  static DocumentException failure(RuntimeException e) {
    var detail = e.getMessage() == null ? "" : e.getMessage();
    if (e instanceof MongoInterruptedException || Thread.currentThread().isInterrupted()) {
      return DocumentException.of(Kind.CANCELLED, "");
    }
    if (e instanceof MongoCommandException c) {
      return DocumentException.server(c.getErrorCode(), c.getErrorCodeName(), c.getErrorMessage());
    }
    if (e instanceof MongoSecurityException) {
      return DocumentException.of(Kind.LOGIN_REFUSED, causes(e));
    }
    if (e instanceof MongoTimeoutException) {
      // Server selection gave up; the monitor's last failure says why, in the message.
      return DocumentException.of(kind(e, detail), detail);
    }
    if (e instanceof MongoSocketReadTimeoutException) {
      return DocumentException.of(Kind.TIMED_OUT, detail);
    }
    if (e instanceof MongoSocketOpenException) {
      return DocumentException.of(kind(e, detail), causes(e));
    }
    if (e instanceof MongoSocketException) {
      return DocumentException.of(Kind.CONNECTION_LOST, causes(e));
    }
    if (e instanceof IllegalStateException && detail.contains("open")) {
      return DocumentException.of(Kind.CANCELLED, "The client was closed");
    }
    if (e instanceof MongoException m && m.getCode() > 0) {
      return DocumentException.server(m.getCode(), "", detail);
    }
    return DocumentException.of(Kind.OTHER, detail);
  }

  // Why a server could not be reached, from the causes, or the words that say them.
  private static Kind kind(Throwable thrown, String detail) {
    // A certificate refused is one also inside the handshake's failure that wraps it.
    int depth = 0;
    for (var t = thrown; t != null && depth < 32; t = t.getCause(), depth++) {
      if (t instanceof CertificateException
          || t instanceof CertPathValidatorException
          || t instanceof CertPathBuilderException
          || t instanceof SSLPeerUnverifiedException) {
        return Kind.CERTIFICATE_REJECTED;
      }
    }
    depth = 0;
    for (var t = thrown; t != null && depth < 32; t = t.getCause(), depth++) {
      if (t instanceof MongoSecurityException) {
        return Kind.LOGIN_REFUSED;
      }
      if (t instanceof SSLException) {
        return Kind.TLS_UNAVAILABLE;
      }
      if (t instanceof UnknownHostException
          || t instanceof ConnectException
          || t instanceof NoRouteToHostException) {
        return Kind.UNREACHABLE;
      }
      if (t instanceof SocketTimeoutException) {
        return Kind.TIMED_OUT;
      }
    }
    // A timeout of server selection names the monitor's failure only in its words.
    if (detail.contains("MongoSecurityException")) {
      return Kind.LOGIN_REFUSED;
    }
    if (detail.contains("PKIX") || detail.contains("CertPath") || detail.contains("certificate")) {
      return Kind.CERTIFICATE_REJECTED;
    }
    if (detail.contains("SSLException") || detail.contains("SSLHandshakeException")) {
      return Kind.TLS_UNAVAILABLE;
    }
    if (detail.contains("UnknownHostException")
        || detail.contains("Connection refused")
        || detail.contains("ConnectException")) {
      return Kind.UNREACHABLE;
    }
    return Kind.TIMED_OUT;
  }

  private static String causes(Throwable thrown) {
    var words = new ArrayList<String>();
    int depth = 0;
    for (var t = thrown; t != null && depth < 32; t = t.getCause(), depth++) {
      if (t.getMessage() != null && !t.getMessage().isBlank() && !words.contains(t.getMessage())) {
        words.add(t.getMessage());
      }
    }
    return String.join(": ", words);
  }

  /** Core's document as the driver's. */
  static BsonDocument document(Document document) {
    var bson = new BsonDocument();
    for (var entry : document.entries()) {
      bson.append(entry.name(), value(entry.value()));
    }
    return bson;
  }

  static BsonValue value(Object value) {
    return switch (value) {
      case null -> BsonNull.VALUE;
      case Boolean b -> BsonBoolean.valueOf(b);
      case Integer n -> new BsonInt32(n);
      case Long n -> new BsonInt64(n);
      case Double n -> new BsonDouble(n);
      case Bson.Decimal128 n -> new BsonDecimal128(Decimal128.parse(n.text()));
      case String s -> new BsonString(s);
      case Instant i -> new BsonDateTime(i.toEpochMilli());
      case Bson.ObjectId id -> new BsonObjectId(new ObjectId(id.hex()));
      case Bson.Binary b -> new BsonBinary((byte) b.subtype(), b.bytes());
      case Bson.Timestamp t -> new BsonTimestamp((int) t.seconds(), (int) t.increment());
      case Bson.Regex r -> new BsonRegularExpression(r.pattern(), r.options());
      case Bson.Code c ->
        c.scope() == null
            ? new BsonJavaScript(c.code())
            : new BsonJavaScriptWithScope(c.code(), document(c.scope()));
      case Bson.Symbol s -> new BsonSymbol(s.symbol());
      case Bson.Special s ->
        switch (s) {
          case MIN_KEY -> new BsonMinKey();
          case MAX_KEY -> new BsonMaxKey();
          case UNDEFINED -> new BsonUndefined();
        };
      case Document d -> document(d);
      case List<?> list -> {
        var array = new BsonArray();
        for (var item : list) {
          array.add(value(item));
        }
        yield array;
      }
      default -> throw new IllegalArgumentException("Not a value of a document");
    };
  }

  /** The driver's document as core's. */
  static Document document(BsonDocument bson) {
    var entries = new ArrayList<Document.Entry>(bson.size());
    for (var entry : bson.entrySet()) {
      entries.add(new Document.Entry(entry.getKey(), value(entry.getValue())));
    }
    return new Document(entries);
  }

  static Object value(BsonValue value) {
    return switch (value.getBsonType()) {
      case DOUBLE -> value.asDouble().getValue();
      case STRING -> value.asString().getValue();
      case DOCUMENT -> document(value.asDocument());
      case ARRAY -> {
        var items = new ArrayList<Object>();
        for (var item : value.asArray()) {
          items.add(value(item));
        }
        yield Document.array(items);
      }
      case BINARY -> {
        var binary = value.asBinary();
        yield new Bson.Binary(binary.getType() & 0xff, binary.getData());
      }
      case UNDEFINED -> Bson.Special.UNDEFINED;
      case OBJECT_ID -> Bson.ObjectId.of(value.asObjectId().getValue().toByteArray());
      case BOOLEAN -> value.asBoolean().getValue();
      case DATE_TIME -> Instant.ofEpochMilli(value.asDateTime().getValue());
      case NULL -> null;
      case REGULAR_EXPRESSION -> {
        var regex = value.asRegularExpression();
        yield new Bson.Regex(regex.getPattern(), regex.getOptions());
      }
      case DB_POINTER -> {
        var pointer = value.asDBPointer();
        yield Document.of(
            "$ref",
            pointer.getNamespace(),
            "$id",
            Bson.ObjectId.of(pointer.getId().toByteArray()));
      }
      case JAVASCRIPT -> new Bson.Code(value.asJavaScript().getCode(), null);
      case SYMBOL -> new Bson.Symbol(value.asSymbol().getSymbol());
      case JAVASCRIPT_WITH_SCOPE -> {
        var code = value.asJavaScriptWithScope();
        yield new Bson.Code(code.getCode(), document(code.getScope()));
      }
      case INT32 -> value.asInt32().getValue();
      case TIMESTAMP -> {
        var timestamp = value.asTimestamp();
        yield new Bson.Timestamp(
            Integer.toUnsignedLong(timestamp.getTime()),
            Integer.toUnsignedLong(timestamp.getInc()));
      }
      case INT64 -> value.asInt64().getValue();
      case DECIMAL128 -> new Bson.Decimal128(value.asDecimal128().getValue().toString());
      case MIN_KEY -> Bson.Special.MIN_KEY;
      case MAX_KEY -> Bson.Special.MAX_KEY;
      case END_OF_DOCUMENT -> throw new IllegalArgumentException("Not a value");
    };
  }
}
