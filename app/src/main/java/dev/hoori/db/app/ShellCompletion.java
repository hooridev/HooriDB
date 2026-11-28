package dev.hoori.db.app;

import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Lexer;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.document.Shell;
import dev.hoori.db.core.document.SyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a console of the shell suggests, by the tokens before the word: after {@code db.} the
 * collections of the console's database and the methods of db, after a collection its methods,
 * after a call the methods of a cursor, after a dollar the operators, and else the fields of the
 * statement's collection and the values the shell makes. Nothing in a string, a regular
 * expression or a comment.
 */
record ShellCompletion() implements Suggestions.Strategy {

  // A server of documents compares names as they are written.
  @Override
  public boolean exactNames(Provider provider) {
    return true;
  }

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

  /** The operators of queries, updates and pipelines, offered after a dollar. */
  static final List<String> OPERATORS = List.of(
      "$eq",
      "$ne",
      "$gt",
      "$gte",
      "$lt",
      "$lte",
      "$in",
      "$nin",
      "$and",
      "$or",
      "$nor",
      "$not",
      "$exists",
      "$type",
      "$regex",
      "$options",
      "$expr",
      "$elemMatch",
      "$size",
      "$all",
      "$text",
      "$search",
      "$set",
      "$unset",
      "$inc",
      "$mul",
      "$min",
      "$max",
      "$rename",
      "$push",
      "$pull",
      "$addToSet",
      "$pop",
      "$currentDate",
      "$setOnInsert",
      "$match",
      "$group",
      "$project",
      "$sort",
      "$limit",
      "$skip",
      "$unwind",
      "$lookup",
      "$count",
      "$addFields",
      "$replaceRoot",
      "$facet",
      "$bucket",
      "$sortByCount",
      "$sample",
      "$sum",
      "$avg",
      "$first",
      "$last");

  /** The methods of a cursor that find gives. */
  static final List<String> CURSOR_METHODS = List.of(
      "sort",
      "limit",
      "skip",
      "projection",
      "count",
      "explain",
      "pretty",
      "toArray",
      "hint",
      "maxTimeMS",
      "collation",
      "comment");

  /** An operator's dollar is not matched, so that {@code $set} also finds {@code $addToSet}. */
  @Override
  public String matched(String word) {
    return word.startsWith("$") ? word.substring(1) : word;
  }

  @Override
  public List<Suggestions.Candidate> candidates(
      Suggestions.Known known, String statement, int caret, String word) {
    var before = statement.substring(0, Math.max(0, caret - word.length()));
    if (quoted(statement, caret)) {
      return List.of();
    }
    var code = Lexer.tokens(before).stream()
        .filter(t -> t.kind() != Lexer.Kind.COMMENT)
        .toList();
    int n = code.size();
    var last = n > 0 && code.getLast().end() == before.length() ? code.getLast() : null;
    if (word.startsWith("$")) {
      return OPERATORS.stream()
          .map(o -> new Suggestions.Candidate(o.substring(1), o, o, Symbol.CODE, "operator", false))
          .toList();
    }
    if (last != null && last.kind() != Lexer.Kind.PUNCTUATION) {
      return List.of(); // the word goes on a name or a number, as 1.
    }
    boolean dot = n > 0 && code.getLast().is(before, '.');
    if (dot && is(before, code, n - 2, "db")) {
      return collections(known);
    }
    if (dot && collectionBefore(before, code)) {
      var into = methods(Script.READING_METHODS, Symbol.CODE);
      into.addAll(methods(Script.WRITING_METHODS, Symbol.EDIT));
      return into;
    }
    if (dot && is(before, code, n - 2, ")")) {
      return methods(CURSOR_METHODS, Symbol.CODE);
    }
    return fields(known, firstCollection(before, code));
  }

  /**
   * Whether the caret is in a string, a comment or its opener, or a regular expression up to the
   * end of its flags, of the statement's tokens.
   */
  private static boolean quoted(String statement, int caret) {
    for (var token : Lexer.tokens(statement)) {
      boolean code = token.kind() == Lexer.Kind.NAME
          || token.kind() == Lexer.Kind.NUMBER
          || token.kind() == Lexer.Kind.PUNCTUATION;
      boolean end = caret == token.end() && (token.open() || token.kind() == Lexer.Kind.REGEX);
      if (!code && token.start() < caret && (caret < token.end() || end)) {
        return true;
      }
    }
    return false;
  }

  /** The collections of the console's database, and the methods of db. */
  private static List<Suggestions.Candidate> collections(Suggestions.Known known) {
    var into = new ArrayList<Suggestions.Candidate>();
    for (var table : known.tables()) {
      var name = table.name();
      var text = IDENTIFIER.matcher(name).matches() && !Script.DATABASE_METHODS.contains(name)
          ? name
          : "getCollection(" + Shell.quote(name) + ")";
      boolean view = table.kind() == Table.Kind.VIEW;
      into.add(new Suggestions.Candidate(
          name, name, text, view ? Symbol.EYE : Symbol.TABLE, view ? "view" : "collection", true));
    }
    into.addAll(methods(Script.DATABASE_METHODS, Symbol.CODE));
    return into;
  }

  private static List<Suggestions.Candidate> methods(Collection<String> methods, Symbol symbol) {
    var into = new ArrayList<Suggestions.Candidate>();
    for (var method : methods) {
      into.add(new Suggestions.Candidate(method, method, method, symbol, "method", false));
    }
    return into;
  }

  /** The fields of the collection, or null for none, and the values the shell makes. */
  private static List<Suggestions.Candidate> fields(Suggestions.Known known, String collection) {
    var into = new ArrayList<Suggestions.Candidate>();
    var table = collection == null ? null : known.table(null, collection);
    if (table != null) {
      for (var column : known.columns(table)) {
        var field = column.name();
        var text = IDENTIFIER.matcher(field).matches() ? field : Shell.quote(field);
        into.add(new Suggestions.Candidate(
            field, field, text, Symbol.COLUMN_SINGLE, column.type(), true));
      }
    }
    for (var constructor : Shell.OFFERED) {
      into.add(new Suggestions.Candidate(
          constructor, constructor, constructor, Symbol.CODE, "value", false));
    }
    return into;
  }

  /** Whether the code ends in a collection's name and a dot, as {@code db.orders.}. */
  private static boolean collectionBefore(String text, List<Lexer.Token> code) {
    for (int k = 0; k < code.size(); k++) {
      var named = collection(text, code, k);
      if (named != null && named.next() == code.size() - 1) {
        return true;
      }
    }
    return false;
  }

  /** The first collection that the code names, or null. */
  private static String firstCollection(String text, List<Lexer.Token> code) {
    for (int k = 0; k < code.size(); k++) {
      var named = collection(text, code, k);
      if (named != null) {
        return named.name();
      }
    }
    return null;
  }

  /** A collection that tokens name, and the index of the token after the name. */
  private record Named(String name, int next) {}

  /**
   * The collection that the code from the token at k names, as {@code db.orders}, {@code
   * db.system.users} up to its method, or {@code db.getCollection('orders')}; null for none.
   */
  private static Named collection(String text, List<Lexer.Token> code, int k) {
    if (!is(text, code, k, "db") || !is(text, code, k + 1, ".") || k + 2 >= code.size()) {
      return null;
    }
    var name = code.get(k + 2);
    if (name.kind() != Lexer.Kind.NAME) {
      return null;
    }
    if (is(text, code, k + 2, "getCollection")
        && is(text, code, k + 3, "(")
        && k + 5 < code.size()
        && code.get(k + 4).kind() == Lexer.Kind.STRING
        && !code.get(k + 4).open()) {
      var quoted = code.get(k + 4);
      int close = is(text, code, k + 5, ",") ? k + 6 : k + 5; // a comma may end its arguments
      if (is(text, code, close, ")")
          && name(text.substring(quoted.start(), quoted.end())) instanceof String named) {
        return new Named(named, close + 1);
      }
    }
    // shortcut: a name with dots is the collection's up to a name called after it.
    var dotted = new StringBuilder(text.substring(name.start(), name.end()));
    int j = k + 3;
    while (is(text, code, j, ".")
        && j + 1 < code.size()
        && code.get(j + 1).kind() == Lexer.Kind.NAME
        && !is(text, code, j + 2, "(")) {
      var part = code.get(j + 1);
      dotted.append('.').append(text, part.start(), part.end());
      j += 2;
    }
    return new Named(dotted.toString(), j);
  }

  // The string a closed string token holds, its escapes read, or null where it reads as none.
  private static Object name(String quoted) {
    try {
      return Shell.parse(quoted);
    } catch (SyntaxException _) {
      return null;
    }
  }

  // Whether the token at k is there and is the text.
  private static boolean is(String text, List<Lexer.Token> code, int k, String what) {
    if (k < 0 || k >= code.size()) {
      return false;
    }
    var token = code.get(k);
    return token.end() - token.start() == what.length() && text.startsWith(what, token.start());
  }
}
