package com.storeql.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One service's tenant data as the database describes it (21.14): every table to export with its
 * columns, key and tenant predicate, in an order where a table comes after the tables it refers to,
 * so an import can load them front to back; and every table a tenant's rows sit in, exported or
 * not, in the order an erasure deletes them.
 *
 * <p>Built from the schema's catalog and the service's {@link TenantDataSpec}; pure, so every rule
 * is tested without a database. What cannot be exported safely is a {@link Problem}, never a table
 * dropped from the list: a table with no tenant column and no predicate, no primary key, a key
 * column excluded, an exclusion or predicate naming something the schema does not have, a name that
 * is not a plain identifier, or a cycle of references.
 */
public final class TenantDataCatalog {

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
  private static final String TENANT_COLUMN = "tenant_id";
  private static final String TENANT_PREDICATE = TENANT_COLUMN + " = ?";

  /** A column of a base table, as the catalog reads it. */
  public record RawColumn(String table, String column, String type, boolean generated) {}

  /** A reference from one table to another in the same schema. */
  public record Reference(String table, String referencedTable) {}

  /** An exported column. */
  public record Column(String name, String type, boolean generated) {}

  /**
   * An exported table.
   *
   * @param tenantPredicate the predicate tying a row to its tenant, one {@code ?}
   * @param importSkippedReason why an import does not load it, or {@code null}
   * @param dependsOn the exported tables this one refers to
   */
  public record Table(
      String name,
      List<Column> columns,
      List<String> key,
      String tenantPredicate,
      boolean hasTenantColumn,
      boolean derived,
      String importSkippedReason,
      Set<String> dependsOn) {

    /** The columns an import writes: every exported one the database does not compute. */
    public List<Column> insertable() {
      return columns.stream().filter(c -> !c.generated()).toList();
    }
  }

  /** A table a tenant's rows are erased from, and the predicate that finds them. */
  public record Erasable(String name, String predicate) {}

  /** Something the schema has that cannot be exported or erased as the spec says. */
  public record Problem(String table, String message) {}

  private final String schema;
  private final List<Table> ordered;
  private final Map<String, Table> byName;
  private final List<Erasable> erasure;
  private final Map<String, String> excludedTables;
  private final Map<String, String> excludedColumns;
  private final Map<String, String> keptAtErasure;
  private final List<Problem> problems;

  private TenantDataCatalog(
      String schema,
      List<Table> ordered,
      List<Erasable> erasure,
      Map<String, String> excludedTables,
      Map<String, String> excludedColumns,
      Map<String, String> keptAtErasure,
      List<Problem> problems) {
    this.schema = schema;
    this.ordered = List.copyOf(ordered);
    Map<String, Table> names = new LinkedHashMap<>();
    ordered.forEach(t -> names.put(t.name(), t));
    this.byName = Map.copyOf(names);
    this.erasure = List.copyOf(erasure);
    this.excludedTables = Map.copyOf(excludedTables);
    this.excludedColumns = Map.copyOf(excludedColumns);
    this.keptAtErasure = Map.copyOf(keptAtErasure);
    this.problems = List.copyOf(problems);
  }

  /**
   * Builds the catalog.
   *
   * @param spec what the service leaves out and how it ties tables to a tenant
   * @param columns every column of every base table in the schema
   * @param keys each table's primary key columns, in key order
   * @param references the foreign keys between tables of the schema
   * @return the catalog, with its problems
   */
  public static TenantDataCatalog build(
      TenantDataSpec spec,
      Collection<RawColumn> columns,
      Map<String, List<String>> keys,
      Collection<Reference> references) {
    List<Problem> problems = new ArrayList<>();
    Map<String, List<RawColumn>> tables = new TreeMap<>();
    for (RawColumn c : columns) {
      tables.computeIfAbsent(c.table(), k -> new ArrayList<>()).add(c);
    }
    Map<String, String> excludedTables = new TreeMap<>(TenantDataSpec.INFRASTRUCTURE);
    excludedTables.putAll(spec.excludedTables());
    Map<String, String> excludedColumns = new TreeMap<>(spec.excludedColumns());

    staleNames(spec.excludedTables().keySet(), tables.keySet(), "excluded table", problems);
    staleNames(spec.importSkipped().keySet(), tables.keySet(), "import-skipped table", problems);
    staleNames(spec.derivedTables(), tables.keySet(), "derived table", problems);
    staleNames(spec.tenantPredicates().keySet(), tables.keySet(), "tenant predicate", problems);
    staleNames(spec.erasurePredicates().keySet(), tables.keySet(), "erasure predicate", problems);
    staleNames(spec.keptAtErasure().keySet(), tables.keySet(), "table kept at erasure", problems);
    staleColumns(excludedColumns.keySet(), tables, problems);

    Map<String, Table> built = new TreeMap<>();
    Map<String, String> erasable = new TreeMap<>();
    for (var entry : tables.entrySet()) {
      String name = entry.getKey();
      boolean tenantColumn =
          entry.getValue().stream().anyMatch(c -> TENANT_COLUMN.equals(c.column()));
      if (!excludedTables.containsKey(name)) {
        table(spec, name, entry.getValue(), tenantColumn, keys.get(name), excludedColumns, problems)
            .ifPresent(
                t -> {
                  built.put(name, t);
                  // A table exported by one predicate may be erased by a wider one: iam-svc's
                  // sandbox pair is the live business's to export and goes with either business
                  // when erased. The spec's erasure predicate, where it names one, is the one
                  // used — declared and ignored, it left a deleted sandbox reachable.
                  exportedErasure(spec, name, tenantColumn, problems)
                      .ifPresentOrElse(
                          p -> erasable.put(name, p),
                          () -> erasable.put(name, t.tenantPredicate()));
                });
      } else if (!TenantDataSpec.INFRASTRUCTURE.containsKey(name)) {
        excludedErasure(spec, name, tenantColumn, problems).ifPresent(p -> erasable.put(name, p));
      }
    }

    List<Table> ordered = new ArrayList<>();
    Map<String, Set<String>> exportDeps = dependencies(built.keySet(), references);
    for (String name : order(exportDeps, problems)) {
      Table t = built.get(name);
      ordered.add(
          new Table(
              t.name(),
              t.columns(),
              t.key(),
              t.tenantPredicate(),
              t.hasTenantColumn(),
              t.derived(),
              t.importSkippedReason(),
              Set.copyOf(exportDeps.get(name))));
    }
    erasable.keySet().removeAll(spec.keptAtErasure().keySet());
    List<String> parentsFirst =
        order(dependencies(erasable.keySet(), references), new ArrayList<>());
    List<Erasable> erasure = new ArrayList<>();
    for (int i = parentsFirst.size() - 1; i >= 0; i--) {
      String name = parentsFirst.get(i);
      erasure.add(new Erasable(name, erasable.get(name)));
    }
    return new TenantDataCatalog(
        spec.schema(),
        ordered,
        erasure,
        excludedTables,
        excludedColumns,
        new TreeMap<>(spec.keptAtErasure()),
        problems);
  }

  private static Optional<Table> table(
      TenantDataSpec spec,
      String name,
      List<RawColumn> raw,
      boolean tenantColumn,
      List<String> key,
      Map<String, String> excludedColumns,
      List<Problem> problems) {
    int before = problems.size();
    if (!IDENTIFIER.matcher(name).matches()) {
      problems.add(new Problem(name, "not a plain identifier"));
    }
    List<Column> exported = new ArrayList<>();
    for (RawColumn c : raw) {
      if (!IDENTIFIER.matcher(c.column()).matches()) {
        problems.add(new Problem(name, "column " + c.column() + " is not a plain identifier"));
      }
      if (!excludedColumns.containsKey(name + "." + c.column())) {
        exported.add(new Column(c.column(), c.type(), c.generated()));
      }
    }
    String predicate = spec.tenantPredicates().get(name);
    if (tenantColumn && predicate != null) {
      problems.add(new Problem(name, "has tenant_id, so it needs no tenant predicate"));
    } else if (!tenantColumn && predicate == null) {
      problems.add(
          new Problem(
              name, "has no tenant_id and no tenant predicate: exclude it or tie it to a tenant"));
    } else if (predicate != null && !oneParameter(predicate)) {
      problems.add(new Problem(name, "its tenant predicate must take exactly one parameter"));
    }
    if (tenantColumn && excludedColumns.containsKey(name + "." + TENANT_COLUMN)) {
      problems.add(new Problem(name, "tenant_id cannot be excluded"));
    }
    if (key == null || key.isEmpty()) {
      problems.add(new Problem(name, "has no primary key, so its rows cannot be paged"));
    } else {
      for (String k : key) {
        if (excludedColumns.containsKey(name + "." + k)) {
          problems.add(new Problem(name, "key column " + k + " cannot be excluded"));
        }
      }
    }
    if (problems.size() > before) return Optional.empty();
    return Optional.of(
        new Table(
            name,
            List.copyOf(exported),
            List.copyOf(key),
            tenantColumn ? TENANT_PREDICATE : predicate,
            tenantColumn,
            spec.derivedTables().contains(name),
            spec.importSkipped().get(name),
            Set.of()));
  }

  /**
   * An exported table's own erasure predicate, when the spec names one: never beside a tenant_id
   * (the column says it all), and with exactly one parameter.
   */
  private static Optional<String> exportedErasure(
      TenantDataSpec spec, String name, boolean tenantColumn, List<Problem> problems) {
    String predicate = spec.erasurePredicates().get(name);
    if (predicate == null) return Optional.empty();
    if (tenantColumn) {
      problems.add(new Problem(name, "has tenant_id, so it needs no erasure predicate"));
      return Optional.empty();
    }
    if (!oneParameter(predicate)) {
      problems.add(new Problem(name, "its erasure predicate must take exactly one parameter"));
      return Optional.empty();
    }
    return Optional.of(predicate);
  }

  /** A left-out table is erased by its tenant_id, or by the spec's predicate, or not at all. */
  private static Optional<String> excludedErasure(
      TenantDataSpec spec, String name, boolean tenantColumn, List<Problem> problems) {
    String predicate = spec.erasurePredicates().get(name);
    if (tenantColumn && predicate != null) {
      problems.add(new Problem(name, "has tenant_id, so it needs no erasure predicate"));
      return Optional.empty();
    }
    if (predicate != null && !oneParameter(predicate)) {
      problems.add(new Problem(name, "its erasure predicate must take exactly one parameter"));
      return Optional.empty();
    }
    if (!IDENTIFIER.matcher(name).matches()) {
      problems.add(new Problem(name, "not a plain identifier"));
      return Optional.empty();
    }
    return tenantColumn ? Optional.of(TENANT_PREDICATE) : Optional.ofNullable(predicate);
  }

  private static Map<String, Set<String>> dependencies(
      Set<String> tables, Collection<Reference> references) {
    Map<String, Set<String>> deps = new TreeMap<>();
    for (String name : tables) deps.put(name, new TreeSet<>());
    for (Reference r : references) {
      if (tables.contains(r.table())
          && tables.contains(r.referencedTable())
          && !r.table().equals(r.referencedTable())) {
        deps.get(r.table()).add(r.referencedTable());
      }
    }
    return deps;
  }

  /** Kahn's order, ties broken by name, so the same schema always gives the same order. */
  private static List<String> order(Map<String, Set<String>> deps, List<Problem> problems) {
    Map<String, Integer> waiting = new TreeMap<>();
    Map<String, List<String>> dependents = new TreeMap<>();
    deps.forEach(
        (table, on) -> {
          waiting.put(table, on.size());
          on.forEach(
              parent -> dependents.computeIfAbsent(parent, k -> new ArrayList<>()).add(table));
        });
    Deque<String> ready =
        waiting.entrySet().stream()
            .filter(e -> e.getValue() == 0)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(ArrayDeque::new));
    List<String> out = new ArrayList<>();
    while (!ready.isEmpty()) {
      String next = ready.poll();
      out.add(next);
      List<String> freed = new ArrayList<>();
      for (String child : dependents.getOrDefault(next, List.of())) {
        if (waiting.merge(child, -1, Integer::sum) == 0) freed.add(child);
      }
      freed.stream().sorted().forEach(ready::add);
    }
    if (out.size() < deps.size()) {
      List<String> cycle = deps.keySet().stream().filter(t -> !out.contains(t)).toList();
      problems.add(new Problem(String.join(",", cycle), "tables refer to each other in a cycle"));
      out.addAll(cycle);
    }
    return out;
  }

  private static boolean oneParameter(String predicate) {
    return predicate.chars().filter(ch -> ch == '?').count() == 1;
  }

  private static void staleNames(
      Collection<String> named, Set<String> present, String what, List<Problem> problems) {
    named.stream()
        .filter(n -> !present.contains(n))
        .sorted()
        .forEach(n -> problems.add(new Problem(n, what + " is not in the schema")));
  }

  private static void staleColumns(
      Collection<String> qualified, Map<String, List<RawColumn>> tables, List<Problem> problems) {
    for (String q : qualified) {
      int dot = q.indexOf('.');
      String table = dot < 0 ? q : q.substring(0, dot);
      String column = dot < 0 ? "" : q.substring(dot + 1);
      boolean present =
          tables.getOrDefault(table, List.of()).stream().anyMatch(c -> c.column().equals(column));
      if (!present) {
        problems.add(new Problem(table, "excluded column " + q + " is not in the schema"));
      }
    }
  }

  /** The schema this catalog describes. */
  public String schema() {
    return schema;
  }

  /** The exported tables, each after the tables it refers to. */
  public List<Table> ordered() {
    return ordered;
  }

  /**
   * One exported table.
   *
   * @param name the table name as a caller gave it
   * @return the table, or empty when it is not exported
   */
  public Optional<Table> table(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /** Every table a tenant's rows sit in, each before the tables it refers to. */
  public List<Erasable> erasureOrder() {
    return erasure;
  }

  /** The tables left out, infrastructure included, with the reason. */
  public Map<String, String> excludedTables() {
    return excludedTables;
  }

  /** The columns left out, as {@code table.column}, with the reason. */
  public Map<String, String> excludedColumns() {
    return excludedColumns;
  }

  /** The tables kept when the business's data is erased, with the reason. */
  public Map<String, String> keptAtErasure() {
    return keptAtErasure;
  }

  /** What stops the catalog being exhaustive; empty when every table can be exported. */
  public List<Problem> problems() {
    return problems;
  }
}
