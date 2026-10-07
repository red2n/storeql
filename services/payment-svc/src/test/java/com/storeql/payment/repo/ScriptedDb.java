package com.storeql.payment.repo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.sql.DataSource;

/**
 * A database that keeps every statement it is asked, in order, and answers each read from a script:
 * enough to prove in a unit test <em>which rows a transaction takes before it reads or writes
 * others</em> — the order of its locks — which is what keeps two transactions from counting the
 * same money twice. It says nothing about what the SQL means; the integration tests do that against
 * Postgres.
 *
 * <p>A read is answered by the first rule whose words are all in the statement (rules are tried in
 * the order they were given); a statement no rule matches reads nothing. Every write changes one
 * row.
 */
final class ScriptedDb {

  private record Rule(Predicate<String> matches, List<Map<String, Object>> rows) {}

  private final List<Rule> rules = new ArrayList<>();
  private final List<String> asked = new ArrayList<>();
  private boolean committed;
  private boolean rolledBack;

  /** Answers a read whose statement has every one of {@code words} in it with {@code rows}. */
  @SafeVarargs
  final ScriptedDb answer(List<String> words, Map<String, Object>... rows) {
    rules.add(
        new Rule(sql -> words.stream().allMatch(sql::contains), java.util.Arrays.asList(rows)));
    return this;
  }

  /** A row as its columns: name, value, name, value … (a value may be null). */
  static Map<String, Object> row(Object... columns) {
    Map<String, Object> row = new java.util.HashMap<>();
    for (int i = 0; i < columns.length; i += 2) row.put((String) columns[i], columns[i + 1]);
    return row;
  }

  /** Every statement prepared, in order. */
  List<String> asked() {
    return List.copyOf(asked);
  }

  /** Where the first statement with every one of {@code words} in it stands, or -1 for none. */
  int firstOf(String... words) {
    for (int i = 0; i < asked.size(); i++) {
      String sql = asked.get(i);
      if (List.of(words).stream().allMatch(sql::contains)) return i;
    }
    return -1;
  }

  /** How many statements have every one of {@code words} in them. */
  long count(String... words) {
    return asked.stream().filter(sql -> List.of(words).stream().allMatch(sql::contains)).count();
  }

  boolean committed() {
    return committed;
  }

  boolean rolledBack() {
    return rolledBack;
  }

  DataSource dataSource() {
    return proxy(
        DataSource.class,
        (p, m, a) -> "getConnection".equals(m.getName()) ? connection() : fallback(m.getName()));
  }

  private Connection connection() {
    Connection[] self = new Connection[1];
    self[0] =
        proxy(
            Connection.class,
            (p, m, a) ->
                switch (m.getName()) {
                  case "prepareStatement" -> {
                    asked.add((String) a[0]);
                    yield statement((String) a[0], self[0]);
                  }
                  case "commit" -> {
                    committed = true;
                    yield null;
                  }
                  case "rollback" -> {
                    rolledBack = true;
                    yield null;
                  }
                  default -> fallback(m.getName());
                });
    return self[0];
  }

  private PreparedStatement statement(String sql, Connection connection) {
    return proxy(
        PreparedStatement.class,
        (p, m, a) ->
            switch (m.getName()) {
              case "executeQuery" -> results(rowsFor(sql));
              case "executeUpdate" -> 1;
              case "getConnection" -> connection;
              default -> fallback(m.getName());
            });
  }

  private List<Map<String, Object>> rowsFor(String sql) {
    for (Rule rule : rules) {
      if (rule.matches().test(sql)) return rule.rows();
    }
    return List.of();
  }

  private static ResultSet results(List<Map<String, Object>> rows) {
    Iterator<Map<String, Object>> it = rows.iterator();
    Object[] row = new Object[1];
    return proxy(
        ResultSet.class,
        (p, m, a) -> {
          switch (m.getName()) {
            case "next":
              if (!it.hasNext()) return false;
              row[0] = it.next();
              return true;
            case "getObject":
            case "getString":
              return column(row[0], (String) a[0]);
            case "getBigDecimal":
              Object money = column(row[0], (String) a[0]);
              return money == null ? null : new BigDecimal(money.toString());
            default:
              return fallback(m.getName());
          }
        });
  }

  @SuppressWarnings("unchecked")
  private static Object column(Object row, String name) {
    return ((Map<String, Object>) row).get(name);
  }

  /** What a call nobody scripted answers: nothing, or "no" for a question. */
  private static Object fallback(String method) {
    return switch (method) {
      case "getAutoCommit", "isClosed", "wasNull" -> false;
      case "hashCode" -> 0;
      case "toString" -> "ScriptedDb";
      default -> null;
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
