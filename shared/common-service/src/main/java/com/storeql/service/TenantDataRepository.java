package com.storeql.service;

import com.storeql.service.TenantDataCatalog.Erasable;
import com.storeql.service.TenantDataCatalog.Table;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * A tenant's data in this service, read and written through its {@link TenantDataCatalog} (21.14):
 * the manifest a departing tenant is given, its rows page by page in key order, the import of those
 * pages into a fresh tenant, and the erasure when its retrieval period ends.
 *
 * <p>Every statement filters by the table's tenant predicate. Every identifier comes from the
 * database's own catalog, has been checked to be a plain identifier, and is quoted; nothing a
 * caller sends becomes SQL. An export is refused while the catalog has problems, so a table the
 * service cannot tie to a tenant is never quietly missing from a file served as complete.
 */
@ApplicationScoped
public class TenantDataRepository extends BaseOutboxRepository {

  /** The largest page a caller may read or send. */
  public static final int MAX_PAGE = 1000;

  /** What the rows are, for the register (art.26(b)). */
  public static final String FORMAT =
      "storeql-tenant-data/1: JSON objects keyed by column name, one per row, in key order;"
          + " timestamps ISO-8601, numbers exact, binary as \\x-prefixed hex";

  private static final String ERASING = "SELECT set_config('storeql.erasing_tenant', ?, true)";

  private static final String COLUMNS =
      "SELECT c.table_name, c.column_name, c.udt_name, c.is_generated"
          + " FROM information_schema.columns c"
          + " JOIN information_schema.tables t"
          + "   ON t.table_schema = c.table_schema AND t.table_name = c.table_name"
          + " WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'"
          + " ORDER BY c.table_name, c.ordinal_position";

  private static final String KEYS =
      "SELECT cl.relname AS table_name, a.attname AS column_name"
          + " FROM pg_index i"
          + " JOIN pg_class cl ON cl.oid = i.indrelid"
          + " JOIN pg_namespace n ON n.oid = cl.relnamespace"
          + " JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true"
          + " JOIN pg_attribute a ON a.attrelid = cl.oid AND a.attnum = k.attnum"
          + " WHERE n.nspname = current_schema() AND i.indisprimary"
          + " ORDER BY cl.relname, k.ord";

  private static final String REFERENCES =
      "SELECT DISTINCT c.relname AS table_name, r.relname AS referenced_table"
          + " FROM pg_constraint k"
          + " JOIN pg_class c ON c.oid = k.conrelid"
          + " JOIN pg_class r ON r.oid = k.confrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " JOIN pg_namespace rn ON rn.oid = r.relnamespace"
          + " WHERE n.nspname = current_schema() AND rn.nspname = current_schema()"
          + " AND k.contype = 'f'";

  /** A column in the manifest. */
  public record ColumnEntry(String name, String type, boolean generated) {}

  /** A table in the manifest, with how many rows the tenant has in it and their checksum. */
  public record TableEntry(
      String name,
      List<ColumnEntry> columns,
      List<String> key,
      boolean derived,
      String importSkippedReason,
      List<String> dependsOn,
      long rows,
      String checksum) {}

  /**
   * What this service holds for a tenant (art.25(2)(e), art.26).
   *
   * @param checksum per table, the md5 of the per-row md5s (each row without {@code tenant_id}) in
   *     key order, built incrementally so a big table is never one giant string, so an import into
   *     another tenant reads back the same figure
   */
  public record Manifest(
      String schema,
      String format,
      List<TableEntry> tables,
      Map<String, String> excludedTables,
      Map<String, String> excludedColumns,
      Map<String, String> keptAtErasure) {}

  /** One page of a table's rows. */
  public record Page(String table, JsonArray rows, String nextCursor) {}

  /** What an import page loaded. */
  public record Imported(String table, int rows) {}

  private record Stats(long rows, String checksum) {}

  @Inject Instance<TenantDataSpec> specs;

  private volatile TenantDataCatalog catalog;
  private final java.util.concurrent.locks.ReentrantLock catalogLock =
      new java.util.concurrent.locks.ReentrantLock();

  /**
   * The catalog, read from the schema once.
   *
   * @return the catalog, problems included
   * @throws ApiException 501 {@code TENANT_DATA_NOT_DECLARED} when the service declares no spec
   */
  public TenantDataCatalog catalog() {
    TenantDataCatalog c = catalog;
    if (c == null) {
      catalogLock.lock();
      try {
        if (catalog == null) {
          if (specs.isUnsatisfied()) {
            throw new ApiException(
                501,
                "TENANT_DATA_NOT_DECLARED",
                "this service does not declare what tenant data it holds",
                List.of());
          }
          catalog = load(specs.get());
        }
        c = catalog;
      } finally {
        catalogLock.unlock();
      }
    }
    return c;
  }

  private TenantDataCatalog load(TenantDataSpec spec) {
    List<TenantDataCatalog.RawColumn> columns =
        query(
            COLUMNS,
            ps -> {},
            rs ->
                new TenantDataCatalog.RawColumn(
                    rs.getString("table_name"),
                    rs.getString("column_name"),
                    rs.getString("udt_name"),
                    !"NEVER".equals(rs.getString("is_generated"))),
            "read tenant data columns");
    Map<String, List<String>> keys = new HashMap<>();
    query(
            KEYS,
            ps -> {},
            rs -> Map.entry(rs.getString("table_name"), rs.getString("column_name")),
            "read tenant data keys")
        .forEach(e -> keys.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    List<TenantDataCatalog.Reference> references =
        query(
            REFERENCES,
            ps -> {},
            rs ->
                new TenantDataCatalog.Reference(
                    rs.getString("table_name"), rs.getString("referenced_table")),
            "read tenant data references");
    return TenantDataCatalog.build(spec, columns, keys, references);
  }

  /**
   * The manifest of a tenant's data here.
   *
   * @throws ApiException 500 {@code TENANT_DATA_CATALOG_INCOMPLETE} naming what cannot be exported
   */
  public Manifest manifest(UUID tenantId) {
    TenantDataCatalog cat = complete();
    List<TableEntry> tables = new ArrayList<>();
    for (Table t : cat.ordered()) {
      Stats stats =
          query(
                  statsSql(t),
                  ps -> ps.setObject(1, tenantId),
                  rs -> new Stats(rs.getLong("n"), rs.getString("checksum")),
                  "count tenant data")
              .get(0);
      tables.add(
          new TableEntry(
              t.name(),
              t.columns().stream()
                  .map(c -> new ColumnEntry(c.name(), c.type(), c.generated()))
                  .toList(),
              t.key(),
              t.derived(),
              t.importSkippedReason(),
              t.dependsOn().stream().sorted().toList(),
              stats.rows(),
              stats.checksum()));
    }
    return new Manifest(
        cat.schema(),
        FORMAT,
        tables,
        cat.excludedTables(),
        cat.excludedColumns(),
        cat.keptAtErasure());
  }

  /**
   * One page of a table's rows for a tenant, in key order.
   *
   * @param cursor the {@code nextCursor} of the page before, or {@code null} for the first
   * @param limit 1 to {@link #MAX_PAGE}
   * @throws ApiException 404 {@code TENANT_DATA_TABLE_UNKNOWN}; 400 {@code
   *     TENANT_DATA_CURSOR_INVALID}
   */
  public Page page(UUID tenantId, String tableName, String cursor, int limit) {
    Table t = exported(tableName);
    String after = cursor == null || cursor.isBlank() ? null : decodeCursor(t, cursor);
    List<JsonObject> rows =
        query(
            pageSql(t, after != null),
            ps -> {
              int i = 1;
              ps.setObject(i++, tenantId);
              if (after != null) ps.setString(i++, after);
              ps.setInt(i, limit + 1);
            },
            rs -> parse(rs.getString("row")),
            "read tenant data page");
    boolean more = rows.size() > limit;
    List<JsonObject> served = more ? rows.subList(0, limit) : rows;
    JsonArrayBuilder out = Json.createArrayBuilder();
    served.forEach(out::add);
    return new Page(
        t.name(), out.build(), more ? encodeCursor(t, served.get(served.size() - 1)) : null);
  }

  /**
   * Loads one page of a table into a tenant, all or nothing. Rows keep their ids and take the
   * importing tenant as theirs, beside whatever the tenant already holds. A row already here, from
   * a page sent twice or a business that has it, is refused as a conflict; a row whose parent has
   * not been loaded is refused, so tables load in the manifest's order.
   *
   * @throws ApiException 404 {@code TENANT_DATA_TABLE_UNKNOWN}; 409 {@code
   *     TENANT_DATA_IMPORT_SKIPPED}, {@code TENANT_DATA_IMPORT_CONFLICT}, {@code
   *     TENANT_DATA_IMPORT_MISSING_PARENT}, {@code TENANT_DATA_IMPORT_NOT_THIS_TENANT}; 400 {@code
   *     TENANT_DATA_IMPORT_INVALID}, {@code TENANT_DATA_PAGE_TOO_LARGE}
   */
  public Imported importPage(UUID tenantId, String tableName, JsonArray rows) {
    Table t = exported(tableName);
    if (t.importSkippedReason() != null) {
      throw ApiException.conflict(
          "TENANT_DATA_IMPORT_SKIPPED", t.name() + " is not imported: " + t.importSkippedReason());
    }
    if (rows.size() > MAX_PAGE) {
      throw ApiException.badRequest(
          "TENANT_DATA_PAGE_TOO_LARGE", "a page holds at most " + MAX_PAGE + " rows");
    }
    if (rows.stream().anyMatch(v -> v.getValueType() != JsonValue.ValueType.OBJECT)) {
      throw ApiException.badRequest(
          "TENANT_DATA_IMPORT_INVALID", "every row is a JSON object keyed by column name");
    }
    if (rows.isEmpty()) return new Imported(t.name(), 0);
    String json = rows.toString();
    return inTx(
        c -> {
          int inserted;
          try (PreparedStatement ps = c.prepareStatement(insertSql(t))) {
            int i = 1;
            if (t.hasTenantColumn()) ps.setObject(i++, tenantId);
            ps.setString(i, json);
            inserted = ps.executeUpdate();
          }
          if (!t.hasTenantColumn() && count(c, strangersSql(t), json, tenantId) > 0) {
            throw ApiException.conflict(
                "TENANT_DATA_IMPORT_NOT_THIS_TENANT",
                "rows of " + t.name() + " belong to rows this tenant does not have");
          }
          return new Imported(t.name(), inserted);
        },
        "import tenant data page");
  }

  /**
   * Erases a tenant's rows from every table they sit in, children first, and writes the evidence
   * row in the same transaction. Erasing again finds nothing and says so.
   *
   * @param evidence the outbox row announcing the counts
   * @return rows deleted per table
   */
  public Map<String, Integer> erase(
      UUID tenantId, Function<Map<String, Integer>, OutboxRow> evidence) {
    TenantDataCatalog cat = complete();
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(ERASING)) {
            ps.setString(1, tenantId.toString());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw new SQLException("set_config returned no row");
            }
          }
          Map<String, Integer> counts = new LinkedHashMap<>();
          for (Erasable e : cat.erasureOrder()) {
            try (PreparedStatement ps = c.prepareStatement(deleteSql(e))) {
              ps.setObject(1, tenantId);
              counts.put(e.name(), ps.executeUpdate());
            }
          }
          insertOutbox(c, evidence.apply(counts));
          return counts;
        },
        "erase tenant data");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    String state = e.getSQLState();
    List<String> details = constraintOf(e);
    if (UNIQUE_VIOLATION.equals(state)) {
      return new ApiException(
          409,
          "TENANT_DATA_IMPORT_CONFLICT",
          "a row in the page is already here for this tenant",
          details,
          e);
    }
    if ("23503".equals(state)) {
      return new ApiException(
          409,
          "TENANT_DATA_IMPORT_MISSING_PARENT",
          "a row refers to one not imported yet: load the tables in the manifest's order",
          details,
          e);
    }
    if ("23502".equals(state) || "23514".equals(state) || state != null && state.startsWith("22")) {
      return new ApiException(
          400, "TENANT_DATA_IMPORT_INVALID", "a row does not fit the table", details, e);
    }
    return super.handleTxSqlException(what, e);
  }

  private static List<String> constraintOf(SQLException e) {
    if (e instanceof PSQLException p) {
      ServerErrorMessage m = p.getServerErrorMessage();
      if (m != null && m.getConstraint() != null) return List.of(m.getConstraint());
      if (m != null && m.getColumn() != null) return List.of(m.getColumn());
    }
    return List.of();
  }

  private TenantDataCatalog complete() {
    TenantDataCatalog cat = catalog();
    if (!cat.problems().isEmpty()) {
      throw new ApiException(
          500,
          "TENANT_DATA_CATALOG_INCOMPLETE",
          "this service holds data it cannot yet export completely, so none is served",
          cat.problems().stream().map(p -> p.table() + ": " + p.message()).toList());
    }
    return cat;
  }

  private Table exported(String name) {
    return complete()
        .table(name)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "TENANT_DATA_TABLE_UNKNOWN", "no exported table is called " + name));
  }

  private static long count(Connection c, String sql, String json, UUID tenantId)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, json);
      ps.setObject(2, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new SQLException("count returned no row");
        return rs.getLong(1);
      }
    }
  }

  // ── statements, from catalog identifiers only ───────────────────────────────

  private static String quote(String identifier) {
    return "\"" + identifier + "\"";
  }

  private static String columns(Table t, String prefix) {
    return t.columns().stream()
        .map(c -> prefix + quote(c.name()))
        .collect(Collectors.joining(", "));
  }

  private static String keys(Table t, String prefix) {
    return t.key().stream().map(k -> prefix + quote(k)).collect(Collectors.joining(", "));
  }

  private static String statsSql(Table t) {
    return "SELECT count(*) AS n, md5(coalesce(string_agg(md5((to_jsonb(r) - 'tenant_id')::text), ''"
        + " ORDER BY "
        + keys(t, "r.")
        + "), '')) AS checksum FROM (SELECT "
        + columns(t, "")
        + " FROM "
        + quote(t.name())
        + " WHERE "
        + t.tenantPredicate()
        + ") r";
  }

  private static String pageSql(Table t, boolean after) {
    String cursor =
        after
            ? " AND ("
                + keys(t, "")
                + ") > (SELECT "
                + keys(t, "a.")
                + " FROM jsonb_populate_record(NULL::"
                + quote(t.name())
                + ", ?::jsonb) a)"
            : "";
    return "SELECT to_jsonb(r)::text AS row FROM (SELECT "
        + columns(t, "")
        + " FROM "
        + quote(t.name())
        + " WHERE "
        + t.tenantPredicate()
        + cursor
        + " ORDER BY "
        + keys(t, "")
        + " LIMIT ?) r ORDER BY "
        + keys(t, "r.");
  }

  private static String strangersSql(Table t) {
    return "SELECT count(*) FROM jsonb_populate_recordset(NULL::"
        + quote(t.name())
        + ", ?::jsonb) p WHERE NOT EXISTS (SELECT 1 FROM "
        + quote(t.name())
        + " x WHERE "
        + t.tenantPredicate()
        + " AND ("
        + keys(t, "x.")
        + ") = ("
        + keys(t, "p.")
        + "))";
  }

  private static String insertSql(Table t) {
    String names =
        t.insertable().stream().map(c -> quote(c.name())).collect(Collectors.joining(", "));
    String values =
        t.insertable().stream()
            .map(c -> "tenant_id".equals(c.name()) ? "?::uuid" : "p." + quote(c.name()))
            .collect(Collectors.joining(", "));
    return "INSERT INTO "
        + quote(t.name())
        + " ("
        + names
        + ") SELECT "
        + values
        + " FROM jsonb_populate_recordset(NULL::"
        + quote(t.name())
        + ", ?::jsonb) p ORDER BY "
        + keys(t, "p.");
  }

  private static String deleteSql(Erasable e) {
    return "DELETE FROM " + quote(e.name()) + " WHERE " + e.predicate();
  }

  // ── cursors ─────────────────────────────────────────────────────────────────

  private static String encodeCursor(Table t, JsonObject row) {
    JsonObjectBuilder key = Json.createObjectBuilder();
    for (String k : t.key()) key.add(k, row.get(k));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(key.build().toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String decodeCursor(Table t, String cursor) {
    try {
      JsonObject key =
          parse(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
      if (!key.keySet().equals(new java.util.HashSet<>(t.key()))) {
        throw new IllegalArgumentException("not a key of " + t.name());
      }
      return key.toString();
    } catch (IllegalArgumentException | JsonException e) {
      throw new ApiException(
          400,
          "TENANT_DATA_CURSOR_INVALID",
          "the cursor is not one this table's pages gave",
          List.of(),
          e);
    }
  }

  private static JsonObject parse(String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      return reader.readObject();
    }
  }
}
