package com.storeql.tenant.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Audit;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The business's admin change log: append-only. Entries are written by the repository that makes
 * the change, on the change's own transaction ({@link #insert}); this class only reads them back,
 * tenant first in every query.
 */
@ApplicationScoped
public class AuditRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, tenant_id, type, actor_id, store_id, subject_id, subject_code, from_value, to_value,"
          + " occurred_at";

  /**
   * Appends an entry on the caller's open transaction. No-op for null, so a repository method that
   * sometimes has nothing to record can pass a possibly-null entry.
   */
  static void insert(Connection c, Audit.Entry e) throws SQLException {
    if (e == null) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO tenant_admin_audit (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setString(3, e.type());
      ps.setObject(4, e.actorId());
      ps.setObject(5, e.storeId());
      ps.setObject(6, e.subjectId());
      ps.setString(7, e.subjectCode());
      ps.setString(8, e.fromValue());
      ps.setString(9, e.toValue());
      ps.setObject(10, e.occurredAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /**
   * A page of the business's entries, newest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param heldStores the caller's stores; empty means unrestricted, otherwise only entries at
   *     those stores and business-wide ones ({@code store_id IS NULL}) are read
   * @param f the narrowing filters; null fields are not applied
   * @param afterAt cursor timestamp (exclusive, going back in time), or null for the first page
   * @param afterId cursor id, breaking ties on identical timestamps
   * @param limit maximum rows; callers pass one more than the page size
   */
  public List<Audit.Entry> list(
      UUID tenantId,
      Set<UUID> heldStores,
      Audit.Filter f,
      Instant afterAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM tenant_admin_audit WHERE tenant_id = ?");
    if (!heldStores.isEmpty()) sql.append(" AND (store_id = ANY(?) OR store_id IS NULL)");
    if (f.type() != null) sql.append(" AND type = ?");
    if (f.actorId() != null) sql.append(" AND actor_id = ?");
    if (f.storeId() != null) sql.append(" AND store_id = ?");
    if (f.from() != null) sql.append(" AND occurred_at >= ?");
    if (f.to() != null) sql.append(" AND occurred_at <= ?");
    if (afterAt != null && afterId != null) sql.append(" AND (occurred_at, id) < (?, ?)");
    sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (!heldStores.isEmpty()) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", heldStores.toArray()));
          }
          if (f.type() != null) ps.setString(i++, f.type());
          if (f.actorId() != null) ps.setObject(i++, f.actorId());
          if (f.storeId() != null) ps.setObject(i++, f.storeId());
          if (f.from() != null) ps.setObject(i++, f.from().atOffset(ZoneOffset.UTC));
          if (f.to() != null) ps.setObject(i++, f.to().atOffset(ZoneOffset.UTC));
          if (afterAt != null && afterId != null) {
            ps.setObject(i++, afterAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        AuditRepository::map,
        "list admin audit");
  }

  private static Audit.Entry map(ResultSet rs) throws SQLException {
    return new Audit.Entry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("type"),
        rs.getObject("actor_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("subject_id", UUID.class),
        rs.getString("subject_code"),
        rs.getString("from_value"),
        rs.getString("to_value"),
        rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
  }
}
