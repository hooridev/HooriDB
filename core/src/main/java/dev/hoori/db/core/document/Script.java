package dev.hoori.db.core.document;

import dev.hoori.db.core.document.Request.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The statements of the MongoDB shell that a console runs, read without JavaScript: {@code use
 * shop}, {@code show collections}, and calls on {@code db}, such as {@code
 * db.orders.find({}).sort({ total: -1 }).limit(10)}, {@code db.orders.aggregate([…])}, {@code
 * db.orders.updateMany({…}, { $set: {…} })} or {@code db.runCommand({…})}. Each becomes the {@link
 * Request} of the server's command that the shell's method sends.
 */
public final class Script {

  /**
   * A statement of a text: where it starts and ends, as offsets of characters, and its text, which
   * may end in a semicolon.
   */
  public record Piece(int start, int end, String text) {}

  /** The collection methods that only read. */
  public static final Set<String> READING_METHODS = Set.of(
      "find",
      "findOne",
      "aggregate",
      "countDocuments",
      "estimatedDocumentCount",
      "count",
      "distinct",
      "getIndexes",
      "stats");

  /** The collection methods that write. */
  public static final Set<String> WRITING_METHODS = Set.of(
      "insertOne",
      "insertMany",
      "insert",
      "updateOne",
      "updateMany",
      "replaceOne",
      "deleteOne",
      "deleteMany",
      "findOneAndUpdate",
      "findOneAndReplace",
      "findOneAndDelete",
      "createIndex",
      "createIndexes",
      "dropIndex",
      "dropIndexes",
      "drop");

  /** The methods of {@code db} itself. */
  public static final Set<String> DATABASE_METHODS = Set.of(
      "runCommand",
      "adminCommand",
      "aggregate",
      "getCollectionNames",
      "getCollectionInfos",
      "createCollection",
      "createView",
      "dropDatabase",
      "stats",
      "getName",
      "version",
      "serverStatus",
      "hello",
      "getCollection",
      "getSiblingDB");

  private Script() {}

  /**
   * The statements of the text, in their order. A statement ends at a semicolon, and at the end of
   * a line where its brackets and quotes are closed, unless the next line goes on with a dot, as a
   * chain of calls written over lines does. Empty statements and those of comments alone are left
   * out.
   */
  public static List<Piece> split(String text) {
    var pieces = new ArrayList<Piece>();
    int depth = 0;
    int start = 0;
    int i = 0;
    int n = text.length();
    char last = 0; // the last character of code before, past space and comments; 0 for none
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        i = skipString(text, i);
        last = c;
        continue;
      }
      if (text.startsWith("//", i)) {
        while (i < n && text.charAt(i) != '\n') {
          i++;
        }
        continue;
      }
      if (text.startsWith("/*", i)) {
        int close = text.indexOf("*/", i + 2);
        i = close < 0 ? n : close + 2;
        continue;
      }
      if (c == '/' && depth > 0 && regexMayStart(last)) {
        i = skipRegex(text, i);
        last = '/';
        continue;
      }
      if (!Character.isWhitespace(c)) {
        last = c;
      }
      if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth = Math.max(0, depth - 1);
      } else if (c == ';' && depth == 0) {
        add(pieces, text, start, i + 1);
        start = i + 1;
      } else if (c == '\n' && depth == 0 && !continues(text, i + 1)) {
        add(pieces, text, start, i);
        start = i + 1;
      }
      i++;
    }
    add(pieces, text, start, n);
    return pieces;
  }

  // Whether the line after the break goes on with a dot, as in a chain of calls.
  private static boolean continues(String text, int from) {
    int i = from;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\n' || !Character.isWhitespace(c)) {
        return c == '.' && !(i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)));
      }
      i++;
    }
    return false;
  }

  private static void add(List<Piece> pieces, String text, int start, int end) {
    // Trimmed of space and of comments around it, so that its offsets are its words'.
    int s = start;
    int e = end;
    while (true) {
      while (s < e && Character.isWhitespace(text.charAt(s))) {
        s++;
      }
      if (text.startsWith("//", s) && s < e) {
        int line = text.indexOf('\n', s);
        s = line < 0 || line > e ? e : line + 1;
        continue;
      }
      if (text.startsWith("/*", s) && s < e) {
        int close = text.indexOf("*/", s + 2);
        s = close < 0 || close + 2 > e ? e : close + 2;
        continue;
      }
      break;
    }
    while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
      e--;
    }
    if (e > s && !(e - s == 1 && text.charAt(s) == ';')) {
      pieces.add(new Piece(s, e, text.substring(s, e)));
    }
  }

  private static int skipString(String text, int at) {
    char quote = text.charAt(at);
    int i = at + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote || c == '\n' && quote != '`') {
        return i + 1;
      }
      i++;
    }
    return text.length();
  }

  // A slash after a value divides; after punctuation it starts a regular expression.
  private static boolean regexMayStart(char last) {
    return last == 0 || "(,:[{!&|?;=".indexOf(last) >= 0;
  }

  private static int skipRegex(String text, int at) {
    int i = at + 1;
    boolean inClass = false;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '\n') {
        return i;
      }
      if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '/' && !inClass) {
        return i + 1;
      }
      i++;
    }
    return text.length();
  }

  /** The request of one statement, which may end in a semicolon. */
  public static Request parse(String statement) throws SyntaxException {
    var reader = new Shell.Reader(statement, 0);
    var request = statement(reader);
    reader.take(';');
    reader.space();
    if (!reader.end()) {
      throw reader.error("The statement ends before this: a semicolon or a line break is missing");
    }
    return request;
  }

  private static Request statement(Shell.Reader r) throws SyntaxException {
    r.space();
    int start = r.at;
    var word = r.identifier();
    if (word == null) {
      throw r.error("A statement starts with db, use or show");
    }
    switch (word) {
      case "use" -> {
        r.space();
        int at = r.at;
        String name;
        if (r.peek() == '\'' || r.peek() == '"') {
          name = r.string();
        } else {
          while (r.at < r.text.length()
              && !Character.isWhitespace(r.text.charAt(r.at))
              && r.text.charAt(r.at) != ';'
              && !r.text.startsWith("//", r.at)
              && !r.text.startsWith("/*", r.at)) {
            r.at++;
          }
          name = r.text.substring(at, r.at);
        }
        if (name.isEmpty()) {
          throw r.error("use needs the name of a database");
        }
        return Request.use(name);
      }
      case "show" -> {
        int at = r.at;
        var what = r.identifier();
        if (what == null) {
          throw r.error("show needs dbs or collections");
        }
        return switch (what) {
          case "dbs", "databases" ->
            new Request(
                "admin", Document.of("listDatabases", 1, "nameOnly", false), Shape.DATABASES, null);
          case "collections", "tables" ->
            Request.of(
                Document.of(
                    "listCollections",
                    1,
                    "nameOnly",
                    true,
                    "authorizedCollections",
                    true,
                    "cursor",
                    Document.EMPTY),
                Shape.CURSOR);
          default -> throw new SyntaxException("show takes dbs or collections, not " + what, at);
        };
      }
      case "db" -> {
        return database(r, null);
      }
      default -> {
        throw new SyntaxException(
            "Unknown name " + word + ": a statement starts with db, use or show", start);
      }
    }
  }

  // After db, or db.getSiblingDB('…'): a method of the database, or a collection.
  private static Request database(Shell.Reader r, String database) throws SyntaxException {
    String collection;
    if (r.take('[')) {
      collection = r.string();
      r.expect(']', "] is missing");
    } else {
      r.expect('.', "A dot and a collection or method are missing after db");
      int at = r.at;
      var name = r.identifier();
      if (name == null) {
        throw r.error("A collection or a method of db is needed after the dot");
      }
      r.space();
      if (r.peek() == '(') {
        return databaseMethod(r, database, name, at);
      }
      collection = name;
      // db.system.users: a name with dots, up to the method.
      while (true) {
        int dot = r.at;
        r.space();
        if (r.peek() != '.') {
          break;
        }
        r.at++;
        var part = r.identifier();
        if (part == null) {
          throw r.error("A name is needed after the dot");
        }
        r.space();
        if (r.peek() == '(') {
          r.at = dot;
          break;
        }
        collection += "." + part;
      }
    }
    return collection(r, database, collection);
  }

  private static Request databaseMethod(Shell.Reader r, String database, String method, int at)
      throws SyntaxException {
    var args = arguments(r);
    var request = switch (method) {
      case "getSiblingDB", "getSisterDB" -> {
        var name = string(args, 0, method, r);
        yield database(r, name);
      }
      case "getCollection" -> collection(r, database, string(args, 0, method, r));
      case "runCommand" -> Request.of(command(args, method, r), Shape.REPLY);
      case "adminCommand" -> new Request("admin", command(args, method, r), Shape.REPLY, null);
      case "aggregate" ->
        cursor(
            r,
            Document.of(
                "aggregate", 1, "pipeline", pipeline(args, method, r), "cursor", Document.EMPTY));
      case "getCollectionNames", "getCollectionInfos" ->
        Request.of(
            Document.of(
                "listCollections",
                1,
                "filter",
                args.isEmpty() ? Document.EMPTY : document(args, 0, method, r),
                "nameOnly",
                method.equals("getCollectionNames"),
                "authorizedCollections",
                true,
                "cursor",
                Document.EMPTY),
            Shape.CURSOR);
      case "createCollection" -> {
        var command = Document.of("create", string(args, 0, method, r));
        if (args.size() > 1) {
          command = merged(command, document(args, 1, method, r));
        }
        yield Request.of(command, Shape.REPLY);
      }
      case "createView" ->
        Request.of(
            Document.of(
                "create",
                string(args, 0, method, r),
                "viewOn",
                string(args, 1, method, r),
                "pipeline",
                pipeline(args.subList(Math.min(2, args.size()), args.size()), method, r)),
            Shape.REPLY);
      case "dropDatabase" -> Request.of(Document.of("dropDatabase", 1), Shape.REPLY);
      case "stats" -> Request.of(Document.of("dbStats", 1), Shape.REPLY);
      case "serverStatus" -> Request.of(Document.of("serverStatus", 1), Shape.REPLY);
      case "hello" -> Request.of(Document.of("hello", 1), Shape.REPLY);
      case "version" -> new Request("admin", Document.of("buildInfo", 1), Shape.REPLY, null);
      case "getName" -> Request.name();
      default ->
        throw new SyntaxException(
            "db has no method " + method + " that a console runs; as a collection, write"
                + " db.getCollection('" + method + "')",
            at);
    };
    return database == null || request.database() != null ? request : request.on(database);
  }

  // After the collection: its method, and calls on the cursor it gives.
  private static Request collection(Shell.Reader r, String database, String collection)
      throws SyntaxException {
    r.expect('.', "A dot and a method are missing after the collection " + collection);
    int at = r.at;
    var method = r.identifier();
    if (method == null) {
      throw r.error("A method is needed after the dot");
    }
    if (method.equals("explain")) {
      var verbosity = arguments(r);
      var explained = collection(r, null, collection);
      return explain(explained, verbosity, r).on(database);
    }
    r.space();
    if (r.peek() != '(') {
      throw r.error("( is missing after " + method);
    }
    var args = arguments(r);
    var request = method(r, collection, method, args, at);
    return database == null ? request : request.on(database);
  }

  private static Request method(
      Shell.Reader r, String collection, String method, List<Object> args, int at)
      throws SyntaxException {
    return switch (method) {
      case "find" -> {
        var command = Document.of("find", collection);
        if (!args.isEmpty()) {
          command = command.with("filter", document(args, 0, method, r));
        }
        if (args.size() > 1) {
          command = command.with("projection", document(args, 1, method, r));
        }
        if (args.size() > 2) {
          command = merged(command, document(args, 2, method, r));
        }
        yield cursor(r, command);
      }
      case "findOne" -> {
        var command = Document.of("find", collection);
        if (!args.isEmpty()) {
          command = command.with("filter", document(args, 0, method, r));
        }
        if (args.size() > 1) {
          command = command.with("projection", document(args, 1, method, r));
        }
        command = command.with("limit", 1).with("singleBatch", true);
        yield ended(r, Request.of(command, Shape.ONE));
      }
      case "aggregate" -> {
        var command = Document.of(
            "aggregate",
            collection,
            "pipeline",
            pipeline(args, method, r),
            "cursor",
            Document.EMPTY);
        if (args.size() > 1 && args.get(1) instanceof Document options) {
          command = merged(command, options);
        }
        yield cursor(r, command);
      }
      case "countDocuments" -> {
        var stages = new ArrayList<Object>();
        stages.add(
            Document.of("$match", args.isEmpty() ? Document.EMPTY : document(args, 0, method, r)));
        if (args.size() > 1) {
          var options = document(args, 1, method, r);
          if (options.get("skip") != null) {
            stages.add(Document.of("$skip", options.get("skip")));
          }
          if (options.get("limit") != null) {
            stages.add(Document.of("$limit", options.get("limit")));
          }
        }
        stages.add(Document.of("$group", Document.of("_id", 1, "n", Document.of("$sum", 1))));
        yield ended(
            r,
            Request.of(
                Document.of(
                    "aggregate",
                    collection,
                    "pipeline",
                    Document.array(stages),
                    "cursor",
                    Document.EMPTY),
                Shape.COUNT));
      }
      case "estimatedDocumentCount" ->
        ended(r, Request.of(Document.of("count", collection), Shape.ESTIMATE));
      case "count" -> {
        var command = Document.of("count", collection);
        if (!args.isEmpty()) {
          command = command.with("query", document(args, 0, method, r));
        }
        yield ended(r, Request.of(command, Shape.ESTIMATE));
      }
      case "distinct" -> {
        var command = Document.of("distinct", collection, "key", string(args, 0, method, r));
        if (args.size() > 1) {
          command = command.with("query", document(args, 1, method, r));
        }
        yield ended(r, Request.of(command, Shape.VALUES));
      }
      case "getIndexes" ->
        ended(
            r,
            Request.of(
                Document.of("listIndexes", collection, "cursor", Document.EMPTY), Shape.CURSOR));
      case "stats" -> ended(r, Request.of(Document.of("collStats", collection), Shape.REPLY));
      case "insertOne" ->
        write(
            r,
            Document.of(
                "insert",
                collection,
                "documents",
                Document.array(withId(document(args, 0, method, r)))));
      case "insertMany", "insert" -> {
        var documents = new ArrayList<Object>();
        if (!args.isEmpty() && args.getFirst() instanceof List<?> list) {
          for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Document d)) {
              throw new SyntaxException(method + " needs an array of documents", at);
            }
            documents.add(withId(d));
          }
        } else {
          documents.add(withId(document(args, 0, method, r)));
        }
        var command = Document.of("insert", collection, "documents", Document.array(documents));
        if (args.size() > 1 && args.get(1) instanceof Document options && options.has("ordered")) {
          command = command.with("ordered", options.get("ordered"));
        }
        yield write(r, command);
      }
      case "updateOne", "updateMany", "replaceOne" -> {
        var filter = document(args, 0, method, r);
        Object update;
        if (method.equals("replaceOne")) {
          var replacement = document(args, 1, method, r);
          if (replacement.names().stream().anyMatch(name -> name.startsWith("$"))) {
            throw new SyntaxException(
                "replaceOne takes a document without operators; to change fields, use updateOne",
                at(args, 1, r));
          }
          update = replacement;
        } else {
          if (args.size() < 2) {
            throw new SyntaxException(method + " needs a filter and an update", at);
          }
          update = args.get(1);
          if (!(update instanceof Document d && !d.isEmpty() && d.first().startsWith("$"))
              && !(update instanceof List<?>)) {
            throw new SyntaxException(
                method + " needs an update of operators, such as { $set: { … } }, or a pipeline",
                at(args, 1, r));
          }
        }
        var statement = Document.of(
            "q", filter, "u", update, "multi", method.equals("updateMany"), "upsert", false);
        var command = Document.of("update", collection);
        if (args.size() > 2) {
          var options = document(args, 2, method, r);
          var each = List.of("upsert", "arrayFilters", "collation", "hint", "sort");
          var all = List.of("let", "comment", "writeConcern", "bypassDocumentValidation");
          var names = new ArrayList<>(each);
          names.addAll(all);
          known(options, names, method, at(args, 2, r));
          statement = options(statement, options, each);
          command = options(command, options, all);
        }
        yield write(r, command.with("updates", Document.array(statement)));
      }
      case "deleteOne", "deleteMany" -> {
        var statement = Document.of(
            "q", document(args, 0, method, r), "limit", method.equals("deleteOne") ? 1 : 0);
        var command = Document.of("delete", collection);
        if (args.size() > 1) {
          var options = document(args, 1, method, r);
          known(
              options,
              List.of("collation", "hint", "let", "comment", "writeConcern"),
              method,
              at(args, 1, r));
          statement = options(statement, options, List.of("collation", "hint"));
          command = options(command, options, List.of("let", "comment", "writeConcern"));
        }
        yield write(r, command.with("deletes", Document.array(statement)));
      }
      case "findOneAndUpdate", "findOneAndReplace", "findOneAndDelete" -> {
        var command =
            Document.of("findAndModify", collection, "query", document(args, 0, method, r));
        Document options = Document.EMPTY;
        if (method.equals("findOneAndDelete")) {
          command = command.with("remove", true);
          if (args.size() > 1) {
            options = document(args, 1, method, r);
          }
        } else {
          if (args.size() < 2) {
            throw new SyntaxException(method + " needs a filter and an update", at);
          }
          var update = args.get(1);
          if (method.equals("findOneAndUpdate")
              && !(update instanceof Document d && !d.isEmpty() && d.first().startsWith("$"))
              && !(update instanceof List<?>)) {
            throw new SyntaxException(
                method + " needs an update of operators, such as { $set: { … } }, or a pipeline;"
                    + " findOneAndReplace replaces the document",
                at(args, 1, r));
          }
          if (method.equals("findOneAndReplace")
              && !(update instanceof Document d
                  && d.names().stream().noneMatch(n -> n.startsWith("$")))) {
            throw new SyntaxException(
                method + " needs a document without operators", at(args, 1, r));
          }
          command = command.with("update", update);
          if (args.size() > 2) {
            options = document(args, 2, method, r);
          }
        }
        for (var entry : options.entries()) {
          switch (entry.name()) {
            case "returnDocument" -> command = command.with("new", "after".equals(entry.value()));
            case "returnNewDocument" -> command = command.with("new", entry.value());
            case "projection" -> command = command.with("fields", entry.value());
            default -> command = command.with(entry.name(), entry.value());
          }
        }
        yield ended(r, Request.of(command, Shape.REPLY));
      }
      case "createIndex" -> {
        var keys = document(args, 0, method, r);
        var index = Document.of("key", keys, "name", indexName(keys));
        if (args.size() > 1) {
          index = merged(index, document(args, 1, method, r));
        }
        yield ended(
            r,
            Request.of(
                Document.of("createIndexes", collection, "indexes", Document.array(index)),
                Shape.REPLY));
      }
      case "createIndexes" -> {
        if (args.isEmpty() || !(args.getFirst() instanceof List<?> list)) {
          throw new SyntaxException("createIndexes needs an array of keys", at);
        }
        var indexes = new ArrayList<Object>();
        for (var keys : list) {
          if (!(keys instanceof Document d)) {
            throw new SyntaxException("createIndexes needs an array of keys", at);
          }
          indexes.add(Document.of("key", d, "name", indexName(d)));
        }
        yield ended(
            r,
            Request.of(
                Document.of("createIndexes", collection, "indexes", Document.array(indexes)),
                Shape.REPLY));
      }
      case "dropIndex", "dropIndexes" -> {
        // One index by its name or keys; all but _id's only as dropIndexes asks.
        if (method.equals("dropIndex")
            && (args.isEmpty() || "*".equals(args.getFirst()) || args.getFirst() == null)) {
          throw new SyntaxException(
              "dropIndex needs the name or the keys of an index; dropIndexes() drops all", at);
        }
        Object index = args.isEmpty() ? "*" : args.getFirst();
        if (index instanceof Document keys) {
          index = keys;
        }
        yield ended(
            r, Request.of(Document.of("dropIndexes", collection, "index", index), Shape.REPLY));
      }
      case "drop" -> ended(r, Request.of(Document.of("drop", collection), Shape.REPLY));
      default ->
        throw new SyntaxException(
            "A collection has no method " + method + " that a console runs", at);
    };
  }

  // A find or aggregate, and the calls on its cursor: sort, limit, skip and the like.
  private static Request cursor(Shell.Reader r, Document command) throws SyntaxException {
    boolean find = command.first().equals("find");
    while (true) {
      int dot = r.at;
      if (!r.take('.')) {
        break;
      }
      int at = r.at;
      var call = r.identifier();
      if (call == null) {
        throw r.error("A method is needed after the dot");
      }
      var args = arguments(r);
      switch (call) {
        case "pretty", "toArray", "readPref", "noCursorTimeout" -> {}
        case "sort" -> {
          findOnly(find, call, at);
          command = command.with("sort", document(args, 0, call, r));
        }
        case "limit" -> {
          findOnly(find, call, at);
          var limit = whole(args, call, r);
          // A negative limit is that many in one batch, as the shell has it; find wants it so.
          if (limit instanceof Number n && n.longValue() < 0) {
            command = command
                .with("limit", n instanceof Long ? (Object) (-n.longValue()) : -n.intValue())
                .with("singleBatch", true);
          } else {
            command = command.with("limit", limit);
          }
        }
        case "skip" -> {
          findOnly(find, call, at);
          command = command.with("skip", whole(args, call, r));
        }
        case "projection", "project" -> {
          findOnly(find, call, at);
          command = command.with("projection", document(args, 0, call, r));
        }
        case "hint" -> command = command.with("hint", args.isEmpty() ? null : args.getFirst());
        case "maxTimeMS" -> command = command.with("maxTimeMS", whole(args, call, r));
        case "collation" -> command = command.with("collation", document(args, 0, call, r));
        case "comment" ->
          command = command.with("comment", args.isEmpty() ? null : args.getFirst());
        case "allowDiskUse" ->
          command =
              command.with("allowDiskUse", args.isEmpty() || Boolean.TRUE.equals(args.get(0)));
        case "batchSize" -> {}
        case "count", "size", "itcount" -> {
          findOnly(find, call, at);
          var count = Document.of("count", command.get("find"));
          if (command.get("filter") != null) {
            count = count.with("query", command.get("filter"));
          }
          if (!call.equals("count")) {
            for (var option : List.of("skip", "limit")) {
              if (command.has(option)) {
                count = count.with(option, command.get(option));
              }
            }
          }
          return ended(r, Request.of(count, Shape.ESTIMATE));
        }
        case "explain" -> {
          return ended(r, explain(Request.of(command, Shape.CURSOR), args, r));
        }
        default -> {
          r.at = dot;
          throw new SyntaxException("A cursor has no method " + call + " that a console runs", at);
        }
      }
    }
    return Request.of(command, Shape.CURSOR);
  }

  private static void findOnly(boolean find, String call, int at) throws SyntaxException {
    if (!find) {
      throw new SyntaxException(
          call + " is a method of find's cursor; in a pipeline, use the stage $" + call, at);
    }
  }

  // Nothing more may follow on the statement but a call that changes nothing, as pretty().
  private static Request ended(Shell.Reader r, Request request) throws SyntaxException {
    while (true) {
      int dot = r.at;
      if (!r.take('.')) {
        return request;
      }
      int at = r.at;
      var call = r.identifier();
      if (!"pretty".equals(call) && !"toArray".equals(call)) {
        r.at = dot;
        throw new SyntaxException("Nothing more can be called on this", at);
      }
      arguments(r);
    }
  }

  // The options of the names that are given, put into the document.
  private static Document options(Document into, Document options, List<String> names) {
    for (var name : names) {
      if (options.has(name)) {
        into = into.with(name, options.get(name));
      }
    }
    return into;
  }

  // An option that the console does not send is refused, rather than the write run without it.
  private static void known(Document options, List<String> names, String method, int at)
      throws SyntaxException {
    for (var name : options.names()) {
      if (!names.contains(name)) {
        throw new SyntaxException(method + " has no option " + name + " that a console sends", at);
      }
    }
  }

  private static Request write(Shell.Reader r, Document command) throws SyntaxException {
    return ended(r, Request.of(command, Shape.WRITE));
  }

  private static Request explain(Request explained, List<Object> args, Shell.Reader r)
      throws SyntaxException {
    var verbosity = args.isEmpty()
        ? "queryPlanner"
        : args.getFirst() instanceof Boolean b
            ? b ? "allPlansExecution" : "queryPlanner"
            : string(args, 0, "explain", r);
    return new Request(
        explained.database(),
        Document.of("explain", explained.command(), "verbosity", verbosity),
        Shape.REPLY,
        null);
  }

  // The arguments of a call, with where each starts and where the call closes.
  private static final class Args extends ArrayList<Object> {
    final List<Integer> starts = new ArrayList<>();
    int end;

    int at(int index) {
      return index < starts.size() ? starts.get(index) : end;
    }
  }

  // Where an argument is, or where the reader is for arguments that are not a call's own.
  private static int at(List<Object> args, int index, Shell.Reader r) {
    return args instanceof Args a ? a.at(index) : r.at;
  }

  // Its arguments in parentheses, read as values.
  private static Args arguments(Shell.Reader r) throws SyntaxException {
    r.expect('(', "( is missing");
    var args = new Args();
    while (true) {
      r.space();
      args.end = r.at;
      if (r.take(')')) {
        return args;
      }
      args.starts.add(r.at);
      args.add(r.value());
      r.space();
      args.end = r.at;
      if (r.take(')')) {
        return args;
      }
      if (!r.take(',')) {
        throw r.error("A comma or ) is missing");
      }
    }
  }

  // The fields of the second document added to the first, which keeps its first field.
  private static Document merged(Document command, Document options) {
    for (var entry : options.entries()) {
      command = command.with(entry.name(), entry.value());
    }
    return command;
  }

  // As the shell does, so that the server's reply and the document agree on it.
  private static Document withId(Document document) {
    if (document.has("_id")) {
      return document;
    }
    var entries = new ArrayList<Document.Entry>();
    entries.add(new Document.Entry("_id", Bson.ObjectId.next()));
    entries.addAll(document.entries());
    return new Document(entries);
  }

  // The name the server would give an index of the keys: a_1_b_-1.
  private static String indexName(Document keys) {
    var parts = new ArrayList<String>();
    for (var entry : keys.entries()) {
      var value = entry.value();
      parts.add(entry.name() + "_" + (value instanceof String s ? s : Shell.scalar(value)));
    }
    return String.join("_", parts);
  }

  private static Document command(List<Object> args, String method, Shell.Reader r)
      throws SyntaxException {
    if (!args.isEmpty() && args.getFirst() instanceof String name) {
      return Document.of(name, 1);
    }
    var command = document(args, 0, method, r);
    if (command.isEmpty()) {
      throw r.error(method + " needs a command, such as { ping: 1 }");
    }
    return command;
  }

  private static List<Object> pipeline(List<Object> args, String method, Shell.Reader r)
      throws SyntaxException {
    if (args.isEmpty()) {
      return Document.array();
    }
    if (args.getFirst() instanceof List<?> stages) {
      for (var stage : stages) {
        if (!(stage instanceof Document)) {
          throw r.error(method + " needs an array of stages, each a document");
        }
      }
      return Document.array(stages);
    }
    // aggregate({ $match: … }, { $group: … }), as older shells took it.
    var stages = new ArrayList<Object>();
    for (var arg : args) {
      if (!(arg instanceof Document)) {
        throw r.error(method + " needs an array of stages, each a document");
      }
      stages.add(arg);
    }
    return Document.array(stages);
  }

  private static Document document(List<Object> args, int index, String method, Shell.Reader r)
      throws SyntaxException {
    if (args.size() <= index || !(args.get(index) instanceof Document d)) {
      throw new SyntaxException(
          method + " needs a document in braces as its " + ordinal(index) + " argument",
          at(args, index, r));
    }
    return d;
  }

  private static String string(List<Object> args, int index, String method, Shell.Reader r)
      throws SyntaxException {
    if (args.size() <= index || !(args.get(index) instanceof String s)) {
      throw new SyntaxException(
          method + " needs a string in quotes as its " + ordinal(index) + " argument",
          at(args, index, r));
    }
    return s;
  }

  private static Object whole(List<Object> args, String method, Shell.Reader r)
      throws SyntaxException {
    if (args.isEmpty()
        || !(args.getFirst() instanceof Integer || args.getFirst() instanceof Long)) {
      throw new SyntaxException(method + " needs a whole number", at(args, 0, r));
    }
    return args.getFirst();
  }

  private static String ordinal(int index) {
    return switch (index) {
      case 0 -> "first";
      case 1 -> "second";
      case 2 -> "third";
      default -> (index + 1) + "th";
    };
  }

  /** The method a statement calls on a collection or on db, for words about it; empty for none. */
  public static String method(String statement) {
    var trimmed = statement.strip();
    int paren = trimmed.indexOf('(');
    var head = paren < 0 ? trimmed : trimmed.substring(0, paren);
    int dot = head.lastIndexOf('.');
    return dot < 0 ? head.toLowerCase(Locale.ROOT) : head.substring(dot + 1).strip();
  }
}
