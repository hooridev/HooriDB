package dev.hoori.db.core.document;

import static java.util.Map.entry;

import dev.hoori.db.core.document.Request.Shape;
import dev.hoori.db.core.sql.EveryRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
   * A statement of a text: where it starts and ends, as offsets of characters, its text, and
   * whether that ends in a semicolon that ends it, not one in a string or comment left open.
   */
  public record Piece(int start, int end, String text, boolean terminated) {}

  /**
   * A statement read once, before it runs: the request it makes, or the syntax error that keeps it
   * from making one.
   */
  public record Parsed(String statement, Request request, SyntaxException error) {

    /** The statement read. */
    public static Parsed of(String statement) {
      try {
        return new Parsed(statement, parse(statement), null);
      } catch (SyntaxException e) {
        return new Parsed(statement, null, e);
      }
    }

    /** The change of every document that it asks for, as {@link Commands#every} tells it. */
    public Optional<EveryRow> every() {
      return request == null || request.command() == null
          ? Optional.empty()
          : Commands.every(request.command());
    }

    /** Whether it is a cursor that only reads, as find and aggregate without $out give. */
    public boolean readsCursor() {
      return request != null && request.shape() == Shape.CURSOR && request.reads();
    }

    /** The database that it switches to with use, or null for any other statement. */
    public String use() {
      return request == null ? null : request.use();
    }
  }

  // The names of commands and of their fields that the methods share.
  private static final String ADMIN = "admin";
  private static final String AGGREGATE = "aggregate";
  private static final String BYPASS_DOCUMENT_VALIDATION = "bypassDocumentValidation";
  private static final String COLLATION = "collation";
  private static final String COMMENT = "comment";
  private static final String COUNT = "count";
  private static final String CURSOR = "cursor";
  private static final String EXPLAIN = "explain";
  private static final String FILTER = "filter";
  private static final String LIMIT = "limit";
  private static final String MAX_TIME_MS = "maxTimeMS";
  private static final String NAME_ONLY = "nameOnly";
  private static final String PIPELINE = "pipeline";
  private static final String PROJECTION = "projection";
  private static final String QUERY = "query";
  private static final String UPSERT = "upsert";
  private static final String WRITE_CONCERN = "writeConcern";

  // The options that findOneAndUpdate, findOneAndReplace and findOneAndDelete know.
  private static final List<String> FIND_AND_MODIFY = List.of(
      "returnDocument",
      "returnNewDocument",
      PROJECTION,
      "sort",
      UPSERT,
      "arrayFilters",
      COLLATION,
      "hint",
      MAX_TIME_MS,
      "let",
      COMMENT,
      WRITE_CONCERN,
      BYPASS_DOCUMENT_VALIDATION);

  /**
   * A call of a method: the reader after its arguments, what it is called on (the collection, or
   * for a method of db the database, null for the console's), its name, its arguments and where
   * its name starts.
   */
  private record Call(Shell.Reader r, String on, String method, List<Object> args, int at) {

    boolean has(int index) {
      return args.size() > index;
    }

    int at(int index) {
      return Script.at(args, index, r);
    }

    Document document(int index) throws SyntaxException {
      return Script.document(args, index, method, r);
    }

    String string(int index) throws SyntaxException {
      return Script.string(args, index, method, r);
    }

    // The document of options at the index, of the names that the method knows.
    Document known(int index, List<String> names) throws SyntaxException {
      var options = document(index);
      Script.known(options, names, method, at(index));
      return options;
    }

    // The options of the names at the index, where it has them, put into the document.
    Document options(Document into, int index, List<String> names) throws SyntaxException {
      return has(index) ? Script.options(into, known(index, names), names) : into;
    }

    SyntaxException error(String message) {
      return new SyntaxException(message, at);
    }
  }

  /** What a method of the shell sends. */
  @FunctionalInterface
  private interface Method {
    Request request(Call call) throws SyntaxException;
  }

  // The collection methods that only read.
  private static final Map<String, Method> READING = Map.ofEntries(
      entry("find", Script::find),
      entry("findOne", Script::findOne),
      entry(AGGREGATE, Script::aggregate),
      entry("countDocuments", Script::countDocuments),
      entry("estimatedDocumentCount", Script::estimatedDocumentCount),
      entry(COUNT, Script::count),
      entry("distinct", Script::distinct),
      entry(
          "getIndexes",
          c -> ended(
              c.r(),
              Request.of(
                  Document.of("listIndexes", c.on(), CURSOR, Document.EMPTY), Shape.CURSOR))),
      entry("stats", c -> reply(c.r(), Document.of("collStats", c.on()))));

  // The collection methods that write.
  private static final Map<String, Method> WRITING = Map.ofEntries(
      entry("insertOne", Script::insertOne),
      entry("insertMany", Script::insertMany),
      entry("insert", Script::insertMany),
      entry("updateOne", Script::update),
      entry("updateMany", Script::update),
      entry("replaceOne", Script::update),
      entry("deleteOne", Script::delete),
      entry("deleteMany", Script::delete),
      entry("findOneAndUpdate", Script::findAndModify),
      entry("findOneAndReplace", Script::findAndModify),
      entry("findOneAndDelete", Script::findAndModify),
      entry("createIndex", Script::createIndex),
      entry("createIndexes", Script::createIndexes),
      entry("dropIndex", Script::dropIndexes),
      entry("dropIndexes", Script::dropIndexes),
      entry("drop", c -> reply(c.r(), Document.of("drop", c.on()))));

  // The methods of db itself.
  private static final Map<String, Method> DATABASE = Map.ofEntries(
      entry("runCommand", c -> Request.of(command(c), Shape.REPLY)),
      entry("adminCommand", c -> new Request(ADMIN, command(c), Shape.REPLY, null)),
      entry(AGGREGATE, Script::aggregateDatabase),
      entry("getCollectionNames", Script::listCollections),
      entry("getCollectionInfos", Script::listCollections),
      entry("createCollection", Script::createCollection),
      entry("createView", Script::createView),
      entry("dropDatabase", _ -> Request.of(Document.of("dropDatabase", 1), Shape.REPLY)),
      entry("stats", _ -> Request.of(Document.of("dbStats", 1), Shape.REPLY)),
      entry("getName", _ -> Request.name()),
      entry("version", _ -> new Request(ADMIN, Document.of("buildInfo", 1), Shape.REPLY, null)),
      entry("serverStatus", _ -> Request.of(Document.of("serverStatus", 1), Shape.REPLY)),
      entry("hello", _ -> Request.of(Document.of("hello", 1), Shape.REPLY)),
      entry("getCollection", c -> collection(c.r(), c.on(), c.string(0))),
      entry("getSiblingDB", c -> database(c.r(), c.string(0))));

  /** The collection methods that only read. */
  public static final Set<String> READING_METHODS = Set.copyOf(READING.keySet());

  /** The collection methods that write. */
  public static final Set<String> WRITING_METHODS = Set.copyOf(WRITING.keySet());

  /** The methods of {@code db} itself. */
  public static final Set<String> DATABASE_METHODS = Set.copyOf(DATABASE.keySet());

  private Script() {}

  /**
   * The statements of the text, in their order. A statement ends at a semicolon, and at the end of
   * a line where its brackets and quotes are closed, unless the next line goes on with a dot, as a
   * chain of calls written over lines does. Empty statements and those of comments alone are left
   * out.
   */
  public static List<Piece> split(String text) {
    return new Splitter(text).pieces();
  }

  // Goes through the tokens of a text, and ends a statement where split says.
  private static final class Splitter {
    private final String text;
    private final List<Lexer.Token> tokens;
    private final List<Piece> pieces = new ArrayList<>();
    private int depth;
    private int start = -1; // where the statement's code starts, or -1 before it has any
    private int end; // where its last code ends
    private int broken = -1; // the first line break after that code, between tokens, or -1
    private int before; // where the token before ends

    Splitter(String text) {
      this.text = text;
      this.tokens = Lexer.tokens(text);
    }

    List<Piece> pieces() {
      for (int k = 0; k < tokens.size(); k++) {
        var token = tokens.get(k);
        lineBefore(k, token);
        if (token.kind() != Lexer.Kind.COMMENT) {
          code(token);
        } else if (start >= 0 && token.open() && block(text, token)) {
          end = token.end(); // not closed: the statement keeps it, which the parser refuses
        }
      }
      if (start >= 0) {
        add(false);
      }
      return pieces;
    }

    // A line break before the token ends the statement, unless the next line goes on with a dot.
    private void lineBefore(int k, Lexer.Token token) {
      if (start >= 0 && broken < 0) {
        broken = lineBreak(text, before, token.start());
      }
      before = token.end();
      if (start >= 0 && depth == 0 && broken >= 0) {
        if (continues(text, tokens, k, broken)) {
          broken = -1; // past this break: only one after the comments before the dot ends it
        } else {
          add(false);
        }
      }
    }

    private void code(Lexer.Token token) {
      if (start < 0) {
        start = token.start();
      }
      end = token.end();
      broken = -1;
      char c = token.kind() == Lexer.Kind.PUNCTUATION ? text.charAt(token.start()) : 0;
      depth = switch (c) {
        case '(', '[', '{' -> depth + 1;
        case ')', ']', '}' -> Math.max(0, depth - 1);
        default -> depth;
      };
      if (c == ';' && depth == 0) {
        add(true);
      }
    }

    private void add(boolean terminated) {
      if (!text.startsWith(";", start) || end - start > 1) {
        pieces.add(new Piece(start, end, text.substring(start, end), terminated));
      }
      start = -1;
    }

    // Whether the line after the break goes on with a dot, as in a chain of calls, also after a
    // block comment: the token at k is the first after the break.
    private static boolean continues(String text, List<Lexer.Token> tokens, int k, int broken) {
      int from = broken + 1;
      for (int j = k; j < tokens.size(); j++) {
        var token = tokens.get(j);
        if (lineBreak(text, from, token.start()) >= 0) {
          return false;
        }
        if (token.kind() != Lexer.Kind.COMMENT) {
          return token.is(text, '.');
        }
        if (token.open() || !block(text, token)) {
          return false;
        }
        from = token.end();
      }
      return false;
    }

    // Whether the comment is a block comment, not one to the end of its line.
    private static boolean block(String text, Lexer.Token comment) {
      return text.startsWith("/*", comment.start());
    }

    // The first line break from the one offset to the other, or -1.
    private static int lineBreak(String text, int from, int to) {
      for (int i = from; i < to; i++) {
        if (text.charAt(i) == '\n') {
          return i;
        }
      }
      return -1;
    }
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
    return switch (word) {
      case "use" -> use(r);
      case "show" -> show(r);
      case "db" -> database(r, null);
      default ->
        throw new SyntaxException(
            "Unknown name " + word + ": a statement starts with db, use or show", start);
    };
  }

  private static Request use(Shell.Reader r) throws SyntaxException {
    r.space();
    var name = r.peek() == '\'' || r.peek() == '"' ? r.string() : unquoted(r);
    if (name.isEmpty()) {
      throw r.error("use needs the name of a database");
    }
    return Request.use(name);
  }

  // A name without quotes: up to a space, a semicolon or a comment.
  private static String unquoted(Shell.Reader r) {
    int at = r.at;
    while (r.at < r.text.length()
        && !Lexer.whitespace(r.text.charAt(r.at))
        && r.text.charAt(r.at) != ';'
        && !r.text.startsWith("//", r.at)
        && !r.text.startsWith("/*", r.at)) {
      r.at++;
    }
    return r.text.substring(at, r.at);
  }

  private static Request show(Shell.Reader r) throws SyntaxException {
    int at = r.at;
    var what = r.identifier();
    if (what == null) {
      throw r.error("show needs dbs or collections");
    }
    return switch (what) {
      case "dbs", "databases" ->
        new Request(
            ADMIN, Document.of("listDatabases", 1, NAME_ONLY, false), Shape.DATABASES, null);
      case "collections", "tables" -> Request.of(listCollections(null, true), Shape.CURSOR);
      default -> throw new SyntaxException("show takes dbs or collections, not " + what, at);
    };
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
      collection = dotted(r, name);
    }
    return collection(r, database, collection);
  }

  // db.system.users: a name with dots, up to the method.
  private static String dotted(Shell.Reader r, String name) throws SyntaxException {
    var collection = new StringBuilder(name);
    while (true) {
      int dot = r.at;
      r.space();
      if (r.peek() != '.') {
        return collection.toString();
      }
      r.at++;
      var part = r.identifier();
      if (part == null) {
        throw r.error("A name is needed after the dot");
      }
      r.space();
      if (r.peek() == '(') {
        r.at = dot;
        return collection.toString();
      }
      collection.append('.').append(part);
    }
  }

  private static Request databaseMethod(Shell.Reader r, String database, String method, int at)
      throws SyntaxException {
    var args = arguments(r);
    // getSisterDB is the older name of getSiblingDB, which completions do not offer.
    var known = DATABASE.get(method.equals("getSisterDB") ? "getSiblingDB" : method);
    if (known == null) {
      throw new SyntaxException(
          "db has no method " + method + " that a console runs; as a collection, write"
              + " db.getCollection('" + method + "')",
          at);
    }
    var request = known.request(new Call(r, database, method, args, at));
    return database == null || request.database() != null ? request : request.on(database);
  }

  private static Request aggregateDatabase(Call c) throws SyntaxException {
    var command = aggregation(1, pipeline(c.args(), c.method(), c.r()));
    if (c.has(1) && c.args().get(0) instanceof List<?> && c.args().get(1) instanceof Document o) {
      command = merged(command, notReplacing(o, command, c.method(), c.at(1)));
    }
    return aggregate(c.r(), command);
  }

  private static Request listCollections(Call c) throws SyntaxException {
    var filter = c.has(0) ? c.document(0) : Document.EMPTY;
    return Request.of(
        listCollections(filter, c.method().equals("getCollectionNames")), Shape.CURSOR);
  }

  // The collections of the database that the user may read, with the filter where it has one.
  private static Document listCollections(Document filter, boolean nameOnly) {
    var command = Document.of("listCollections", 1);
    if (filter != null) {
      command = command.with(FILTER, filter);
    }
    return command
        .with(NAME_ONLY, nameOnly)
        .with("authorizedCollections", true)
        .with(CURSOR, Document.EMPTY);
  }

  private static Request createCollection(Call c) throws SyntaxException {
    var command = Document.of("create", c.string(0));
    if (c.has(1)) {
      command = merged(command, c.document(1));
    }
    return Request.of(command, Shape.REPLY);
  }

  private static Request createView(Call c) throws SyntaxException {
    var args = c.args();
    var command = Document.of(
        "create",
        c.string(0),
        "viewOn",
        c.string(1),
        PIPELINE,
        pipeline(
            args.subList(Math.min(2, args.size()), Math.min(3, args.size())), c.method(), c.r()));
    return Request.of(c.options(command, 3, List.of(COLLATION)), Shape.REPLY);
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
    if (method.equals(EXPLAIN)) {
      var verbosity = arguments(r);
      var explained = collection(r, null, collection);
      return explain(explained, verbosity, r).on(database);
    }
    r.space();
    if (r.peek() != '(') {
      throw r.error("( is missing after " + method);
    }
    var args = arguments(r);
    var known = READING.getOrDefault(method, WRITING.get(method));
    if (known == null) {
      throw new SyntaxException(
          "A collection has no method " + method + " that a console runs", at);
    }
    var request = known.request(new Call(r, collection, method, args, at));
    return database == null ? request : request.on(database);
  }

  // Collection methods

  private static Request find(Call c) throws SyntaxException {
    var command = found(c);
    if (c.has(2)) {
      command = merged(command, c.document(2));
    }
    return cursor(c.r(), command);
  }

  private static Request findOne(Call c) throws SyntaxException {
    var names = List.of("sort", "skip", "hint", MAX_TIME_MS, COLLATION, COMMENT, "let");
    var command = c.options(found(c), 2, names).with(LIMIT, 1).with("singleBatch", true);
    return ended(c.r(), Request.of(command, Shape.ONE));
  }

  // The find of a filter and a projection, where the call has them.
  private static Document found(Call c) throws SyntaxException {
    var command = Document.of("find", c.on());
    if (c.has(0)) {
      command = command.with(FILTER, c.document(0));
    }
    if (c.has(1)) {
      command = command.with(PROJECTION, c.document(1));
    }
    return command;
  }

  private static Request aggregate(Call c) throws SyntaxException {
    var command = aggregation(c.on(), pipeline(c.args(), c.method(), c.r()));
    if (c.has(1) && c.args().get(1) instanceof Document options) {
      command = merged(command, notReplacing(options, command, c.method(), c.at(1)));
    }
    return aggregate(c.r(), command);
  }

  // An aggregate of the collection, or of the database for 1, whose cursor is read.
  private static Document aggregation(Object on, List<Object> pipeline) {
    return Document.of(AGGREGATE, on, PIPELINE, pipeline, CURSOR, Document.EMPTY);
  }

  private static Request countDocuments(Call c) throws SyntaxException {
    var stages = new ArrayList<Object>();
    stages.add(Document.of("$match", c.has(0) ? c.document(0) : Document.EMPTY));
    Document counted = Document.EMPTY;
    if (c.has(1)) {
      var options = c.known(1, List.of("skip", LIMIT, "hint", MAX_TIME_MS, COLLATION, COMMENT));
      counted = options(counted, options, List.of("hint", MAX_TIME_MS, COLLATION, COMMENT));
      for (var stage : List.of("skip", LIMIT)) {
        if (options.get(stage) != null) {
          stages.add(Document.of("$" + stage, options.get(stage)));
        }
      }
    }
    stages.add(Document.of("$group", Document.of("_id", 1, "n", Document.of("$sum", 1))));
    var command = merged(aggregation(c.on(), Document.array(stages)), counted);
    return ended(c.r(), Request.of(command, Shape.COUNT));
  }

  private static Request estimatedDocumentCount(Call c) throws SyntaxException {
    var command = c.options(Document.of(COUNT, c.on()), 0, List.of(MAX_TIME_MS, COMMENT));
    return ended(c.r(), Request.of(command, Shape.ESTIMATE));
  }

  private static Request count(Call c) throws SyntaxException {
    var command = Document.of(COUNT, c.on());
    if (c.has(0)) {
      command = command.with(QUERY, c.document(0));
    }
    return ended(c.r(), Request.of(command, Shape.ESTIMATE));
  }

  private static Request distinct(Call c) throws SyntaxException {
    var command = Document.of("distinct", c.on(), "key", c.string(0));
    if (c.has(1)) {
      command = command.with(QUERY, c.document(1));
    }
    return ended(c.r(), Request.of(command, Shape.VALUES));
  }

  private static Request insertOne(Call c) throws SyntaxException {
    return insert(c, Document.array(withId(c.document(0))));
  }

  private static Request insertMany(Call c) throws SyntaxException {
    var documents = new ArrayList<Object>();
    if (c.has(0) && c.args().getFirst() instanceof List<?> list) {
      for (var item : list) {
        if (!(item instanceof Document d)) {
          throw c.error(c.method() + " needs an array of documents");
        }
        documents.add(withId(d));
      }
    } else if (c.method().equals("insertMany")) {
      throw c.error("insertMany needs an array of documents");
    } else {
      documents.add(withId(c.document(0)));
    }
    return insert(c, Document.array(documents));
  }

  // An insert of the documents, with the options it knows.
  private static Request insert(Call c, List<Object> documents) throws SyntaxException {
    var command = Document.of("insert", c.on(), "documents", documents);
    var names = List.of("ordered", WRITE_CONCERN, BYPASS_DOCUMENT_VALIDATION, COMMENT);
    return write(c.r(), c.options(command, 1, names));
  }

  // updateOne, updateMany and replaceOne.
  private static Request update(Call c) throws SyntaxException {
    var filter = c.document(0);
    var update = c.method().equals("replaceOne") ? replacement(c) : operatorUpdate(c);
    var statement = Document.of(
        "q", filter, "u", update, "multi", c.method().equals("updateMany"), UPSERT, false);
    var command = Document.of("update", c.on());
    if (c.has(2)) {
      var each = List.of(UPSERT, "arrayFilters", COLLATION, "hint", "sort");
      var all = List.of("let", COMMENT, WRITE_CONCERN, BYPASS_DOCUMENT_VALIDATION);
      var names = new ArrayList<>(each);
      names.addAll(all);
      var options = c.known(2, names);
      statement = options(statement, options, each);
      command = options(command, options, all);
    }
    return write(c.r(), command.with("updates", Document.array(statement)));
  }

  private static Document replacement(Call c) throws SyntaxException {
    var replacement = c.document(1);
    if (operators(replacement)) {
      throw new SyntaxException(
          "replaceOne takes a document without operators; to change fields, use updateOne",
          c.at(1));
    }
    return replacement;
  }

  private static Object operatorUpdate(Call c) throws SyntaxException {
    var update = second(c);
    if (!changes(update)) {
      throw new SyntaxException(
          c.method() + " needs an update of operators, such as { $set: { … } }, or a pipeline",
          c.at(1));
    }
    return update;
  }

  // The second argument, which an update needs.
  private static Object second(Call c) throws SyntaxException {
    if (!c.has(1)) {
      throw c.error(c.method() + " needs a filter and an update");
    }
    return c.args().get(1);
  }

  // Whether it is an update of operators, such as { $set: … }, or a pipeline.
  private static boolean changes(Object update) {
    return update instanceof Document d && !d.isEmpty() && d.first().startsWith("$")
        || update instanceof List<?>;
  }

  private static boolean operators(Document document) {
    return document.names().stream().anyMatch(name -> name.startsWith("$"));
  }

  // deleteOne and deleteMany.
  private static Request delete(Call c) throws SyntaxException {
    var statement = Document.of("q", c.document(0), LIMIT, c.method().equals("deleteOne") ? 1 : 0);
    var command = Document.of("delete", c.on());
    if (c.has(1)) {
      var options = c.known(1, List.of(COLLATION, "hint", "let", COMMENT, WRITE_CONCERN));
      statement = options(statement, options, List.of(COLLATION, "hint"));
      command = options(command, options, List.of("let", COMMENT, WRITE_CONCERN));
    }
    return write(c.r(), command.with("deletes", Document.array(statement)));
  }

  // findOneAndUpdate, findOneAndReplace and findOneAndDelete.
  private static Request findAndModify(Call c) throws SyntaxException {
    var command = Document.of("findAndModify", c.on(), QUERY, c.document(0));
    Document options;
    if (c.method().equals("findOneAndDelete")) {
      command = command.with("remove", true);
      options = c.has(1) ? c.document(1) : Document.EMPTY;
    } else {
      command = command.with("update", modification(c));
      options = c.has(2) ? c.document(2) : Document.EMPTY;
    }
    known(options, FIND_AND_MODIFY, c.method(), c.at(c.args().size() - 1));
    for (var entry : options.entries()) {
      command = switch (entry.name()) {
        case "returnDocument" -> command.with("new", "after".equals(entry.value()));
        case "returnNewDocument" -> command.with("new", entry.value());
        case PROJECTION -> command.with("fields", entry.value());
        default -> command.with(entry.name(), entry.value());
      };
    }
    return reply(c.r(), command);
  }

  // The update of findOneAndUpdate, or the document of findOneAndReplace.
  private static Object modification(Call c) throws SyntaxException {
    var update = second(c);
    if (c.method().equals("findOneAndUpdate") && !changes(update)) {
      throw new SyntaxException(
          c.method() + " needs an update of operators, such as { $set: { … } }, or a pipeline;"
              + " findOneAndReplace replaces the document",
          c.at(1));
    }
    if (c.method().equals("findOneAndReplace")
        && !(update instanceof Document d && !operators(d))) {
      throw new SyntaxException(c.method() + " needs a document without operators", c.at(1));
    }
    return update;
  }

  private static Request createIndex(Call c) throws SyntaxException {
    var index = index(c.document(0));
    if (c.has(1)) {
      index = merged(index, c.document(1));
    }
    return indexes(c, Document.array(index));
  }

  private static Request createIndexes(Call c) throws SyntaxException {
    if (!c.has(0) || !(c.args().getFirst() instanceof List<?> list)) {
      throw c.error("createIndexes needs an array of keys");
    }
    var indexes = new ArrayList<Object>();
    for (var keys : list) {
      if (!(keys instanceof Document d)) {
        throw c.error("createIndexes needs an array of keys");
      }
      var index = index(d);
      // The options, such as unique, are each index's, as the shell gives them.
      if (c.has(1)) {
        index = merged(index, c.document(1));
      }
      indexes.add(index);
    }
    return indexes(c, Document.array(indexes));
  }

  private static Request indexes(Call c, List<Object> indexes) throws SyntaxException {
    return reply(c.r(), Document.of("createIndexes", c.on(), "indexes", indexes));
  }

  // An index of the keys, with the name the server would give it.
  private static Document index(Document keys) {
    return Document.of("key", keys, "name", indexName(keys));
  }

  // dropIndex drops one index by its name or keys; dropIndexes all but _id's, or those given.
  private static Request dropIndexes(Call c) throws SyntaxException {
    var args = c.args();
    if (c.method().equals("dropIndex")
        && (args.isEmpty()
            || "*".equals(args.getFirst())
            || !(args.getFirst() instanceof String || args.getFirst() instanceof Document))) {
      throw c.error("dropIndex needs the name or the keys of an index; dropIndexes() drops all");
    }
    Object index = args.isEmpty() ? "*" : args.getFirst();
    return reply(c.r(), Document.of("dropIndexes", c.on(), "index", index));
  }

  // A find or aggregate, and the calls on its cursor: sort, limit, skip and the like.
  private static Request cursor(Shell.Reader r, Document command) throws SyntaxException {
    boolean find = command.first().equals("find");
    int dot = r.at;
    while (r.take('.')) {
      int at = r.at;
      var call = r.identifier();
      if (call == null) {
        throw r.error("A method is needed after the dot");
      }
      var args = arguments(r);
      switch (call) {
        case "pretty", "toArray", "readPref", "noCursorTimeout", "batchSize" -> {
          // Nothing that the console sends: it reads the cursor in portions of its own.
        }
        case "sort" -> {
          findOnly(find, call, at);
          command = command.with("sort", document(args, 0, call, r));
        }
        case LIMIT -> {
          findOnly(find, call, at);
          command = limit(command, whole(args, call, r), at);
        }
        case "skip" -> {
          findOnly(find, call, at);
          command = command.with("skip", whole(args, call, r));
        }
        case PROJECTION, "project" -> {
          findOnly(find, call, at);
          command = command.with(PROJECTION, document(args, 0, call, r));
        }
        case "hint" -> command = command.with("hint", first(args));
        case MAX_TIME_MS -> command = command.with(MAX_TIME_MS, whole(args, call, r));
        case COLLATION -> command = command.with(COLLATION, document(args, 0, call, r));
        case COMMENT -> command = command.with(COMMENT, first(args));
        case "allowDiskUse" ->
          command =
              command.with("allowDiskUse", args.isEmpty() || Boolean.TRUE.equals(args.get(0)));
        case COUNT, "size", "itcount" -> {
          findOnly(find, call, at);
          return ended(r, Request.of(counted(command, call), Shape.ESTIMATE));
        }
        case EXPLAIN -> {
          return ended(r, explain(Request.of(command, Shape.CURSOR), args, r));
        }
        default -> {
          r.at = dot;
          throw new SyntaxException("A cursor has no method " + call + " that a console runs", at);
        }
      }
      dot = r.at;
    }
    return Request.of(command, Shape.CURSOR);
  }

  // A negative limit is that many in one batch, as the shell has it; find wants it so.
  private static Document limit(Document command, Object limit, int at) throws SyntaxException {
    if (!(limit instanceof Number n) || n.longValue() >= 0) {
      return command.with(LIMIT, limit);
    }
    if (n.longValue() == Long.MIN_VALUE) {
      throw new SyntaxException("limit is out of range", at);
    }
    long magnitude = -n.longValue();
    return command
        .with(LIMIT, magnitude <= Integer.MAX_VALUE ? (Object) (int) magnitude : (Object) magnitude)
        .with("singleBatch", true);
  }

  // The count of a find's documents: skip and limit as the cursor has them, but for count(); and
  // what the cursor compares and reads by, which the count command takes too.
  private static Document counted(Document find, String call) {
    var count = Document.of(COUNT, find.get("find"));
    if (find.get(FILTER) != null) {
      count = count.with(QUERY, find.get(FILTER));
    }
    if (!call.equals(COUNT)) {
      count = options(count, find, List.of("skip", LIMIT));
    }
    return options(count, find, List.of(COLLATION, "hint", MAX_TIME_MS, COMMENT));
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

  // An aggregate, whose cursor is read, or with explain set the plan, which has none.
  private static Request aggregate(Shell.Reader r, Document command) throws SyntaxException {
    return Boolean.TRUE.equals(command.get(EXPLAIN)) ? reply(r, command) : cursor(r, command);
  }

  // Options that would replace what the command is, as its pipeline, are refused; a cursor of
  // its own is a document.
  private static Document notReplacing(Document options, Document command, String method, int at)
      throws SyntaxException {
    for (var name : List.of(command.first(), PIPELINE)) {
      if (options.has(name)) {
        throw new SyntaxException(method + " takes no option " + name, at);
      }
    }
    if (options.has(CURSOR) && !(options.get(CURSOR) instanceof Document)) {
      throw new SyntaxException(method + " takes a document as its cursor", at);
    }
    return options;
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

  private static Request reply(Shell.Reader r, Document command) throws SyntaxException {
    return ended(r, Request.of(command, Shape.REPLY));
  }

  private static Request explain(Request explained, List<Object> args, Shell.Reader r)
      throws SyntaxException {
    return new Request(
        explained.database(),
        Document.of(EXPLAIN, explained.command(), "verbosity", verbosity(args, r)),
        Shape.REPLY,
        null);
  }

  // As explain(true) or explain(false), or explain('executionStats'): queryPlanner without one.
  private static String verbosity(List<Object> args, Shell.Reader r) throws SyntaxException {
    if (args.isEmpty()) {
      return "queryPlanner";
    }
    if (args.getFirst() instanceof Boolean all) {
      return all.booleanValue() ? "allPlansExecution" : "queryPlanner";
    }
    return string(args, 0, EXPLAIN, r);
  }

  // The first argument, or null where there is none.
  private static Object first(List<Object> args) {
    return args.isEmpty() ? null : args.getFirst();
  }

  // The arguments of a call, with where each starts and where the call closes.
  private static final class Args extends ArrayList<Object> {
    private final transient List<Integer> starts = new ArrayList<>();
    private transient int end;

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

  private static Document command(Call c) throws SyntaxException {
    if (c.has(0) && c.args().getFirst() instanceof String name) {
      return Document.of(name, 1);
    }
    var command = c.document(0);
    if (command.isEmpty()) {
      throw c.r().error(c.method() + " needs a command, such as { ping: 1 }");
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
