package com.storeql.iam.repo;

import com.storeql.iam.domain.SecurityEvent;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the append-only {@code audit_log}: a business's own security events, or all of them
 * for the platform. Never writes.
 */
@ApplicationScoped
public class SecurityEventRepository extends BaseJdbcRepository {

  /**
   * Events newest first.
   *
   * @param tenantId the business to read for (first condition), or {@code null} for the platform
   *     administrator, who reads every row
   * @param type exact action code, or null
   * @param userId only this login's events, or null
   * @param from inclusive lower bound on when, or null
   * @param to exclusive upper bound on when, or null
   * @param after only rows whose id sorts before this one (the previous page's last), or null
   * @param limit rows to return
   */
  public List<SecurityEvent> list(
      UUID tenantId, String type, UUID userId, Instant from, Instant to, UUID after, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT a.id, a.action, a.detail, a.created_at, a.user_id, u.email,"
                + " CASE WHEN u.id IS NOT NULL THEN u.tenant_id ELSE a.tenant_id END AS owner_tenant"
                + " FROM audit_log a LEFT JOIN users u ON u.id = a.user_id WHERE ");
    List<Object> args = new ArrayList<>();
    if (tenantId != null) {
      // A login's event belongs to the business its login does; an event with no login, to the
      // business it was recorded against. Nothing else — a shopper's or the platform's rows
      // (no business) are read by the platform administrator alone.
      sql.append("(CASE WHEN u.id IS NOT NULL THEN u.tenant_id ELSE a.tenant_id END) = ?");
      args.add(tenantId);
    } else {
      sql.append("true");
    }
    if (type != null) {
      sql.append(" AND a.action = ?");
      args.add(type);
    }
    if (userId != null) {
      sql.append(" AND a.user_id = ?");
      args.add(userId);
    }
    if (from != null) {
      sql.append(" AND a.created_at >= ?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" AND a.created_at < ?");
      args.add(Timestamp.from(to));
    }
    if (after != null) {
      sql.append(" AND a.id < ?");
      args.add(after);
    }
    sql.append(" ORDER BY a.id DESC LIMIT ?");
    args.add(limit);
    return query(
        sql.toString(),
        ps -> {
          for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
          }
        },
        SecurityEventRepository::map,
        "list security events");
  }

  private static SecurityEvent map(ResultSet rs) throws SQLException {
    String action = rs.getString("action");
    return new SecurityEvent(
        rs.getObject("id", UUID.class),
        action,
        rs.getObject("user_id", UUID.class),
        rs.getString("email"),
        rs.getObject("owner_tenant", UUID.class),
        SecurityEvent.visibleDetail(action, rs.getString("detail")),
        rs.getTimestamp("created_at").toInstant());
  }
}
