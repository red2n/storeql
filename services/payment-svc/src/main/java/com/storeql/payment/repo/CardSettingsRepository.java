package com.storeql.payment.repo;

import com.storeql.ids.Ids;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a store may take cards on a standalone machine (the till's card rule), and the
 * append-only record of who changed it and when. Every query filters {@code tenant_id} first.
 */
@ApplicationScoped
public class CardSettingsRepository extends BaseJdbcRepository {

  /** The setting as it stands. */
  public record Setting(
      UUID storeId, boolean standaloneAllowed, UUID changedBy, Instant changedAt) {}

  /** One change, kept for good. */
  public record Change(boolean standaloneAllowed, UUID changedBy, Instant changedAt) {}

  /** CARD tenders at a store since a moment, and how many were typed from a standalone machine. */
  public record Tenders(long card, long standalone) {}

  public boolean standaloneAllowed(UUID tenantId, UUID storeId) {
    return find(tenantId, storeId).map(Setting::standaloneAllowed).orElse(false);
  }

  public Optional<Setting> find(UUID tenantId, UUID storeId) {
    return query(
            "SELECT store_id, standalone_allowed, changed_by, changed_at FROM store_card_settings"
                + " WHERE tenant_id = ? AND store_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs ->
                new Setting(
                    rs.getObject("store_id", UUID.class),
                    rs.getBoolean("standalone_allowed"),
                    rs.getObject("changed_by", UUID.class),
                    rs.getObject("changed_at", OffsetDateTime.class).toInstant()),
            "find a store's card setting")
        .stream()
        .findFirst();
  }

  /** Sets it and keeps the change, on one transaction. */
  public Setting set(UUID tenantId, UUID storeId, boolean allowed, UUID actor) {
    Instant now = Instant.now();
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO store_card_settings"
                      + " (tenant_id, store_id, standalone_allowed, changed_by, changed_at)"
                      + " VALUES (?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id) DO UPDATE SET"
                      + " standalone_allowed = EXCLUDED.standalone_allowed,"
                      + " changed_by = EXCLUDED.changed_by, changed_at = EXCLUDED.changed_at")) {
            bind(ps, tenantId, storeId, allowed, actor, now, false);
            ps.executeUpdate();
          }
          log(c, tenantId, storeId, allowed, actor, now);
          return null;
        },
        "set a store's card setting");
    return new Setting(storeId, allowed, actor, now);
  }

  private static void log(
      Connection c, UUID tenantId, UUID storeId, boolean allowed, UUID actor, Instant now)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO store_card_setting_changes"
                + " (tenant_id, store_id, standalone_allowed, changed_by, changed_at, id)"
                + " VALUES (?,?,?,?,?,?)")) {
      bind(ps, tenantId, storeId, allowed, actor, now, true);
      ps.executeUpdate();
    }
  }

  private static void bind(
      PreparedStatement ps,
      UUID tenantId,
      UUID storeId,
      boolean allowed,
      UUID actor,
      Instant now,
      boolean withId)
      throws SQLException {
    ps.setObject(1, tenantId);
    ps.setObject(2, storeId);
    ps.setBoolean(3, allowed);
    ps.setObject(4, actor);
    ps.setObject(5, now.atOffset(ZoneOffset.UTC));
    if (withId) ps.setObject(6, Ids.newId());
  }

  /** The changes to a store's setting, newest first. */
  public List<Change> changes(UUID tenantId, UUID storeId, int limit) {
    return query(
        "SELECT standalone_allowed, changed_by, changed_at FROM store_card_setting_changes"
            + " WHERE tenant_id = ? AND store_id = ? ORDER BY changed_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setInt(3, limit);
        },
        rs ->
            new Change(
                rs.getBoolean("standalone_allowed"),
                rs.getObject("changed_by", UUID.class),
                rs.getObject("changed_at", OffsetDateTime.class).toInstant()),
        "list a store's card setting changes");
  }

  /**
   * CARD tenders at the store since a moment: all of them, and those typed from a standalone
   * machine.
   */
  public Tenders tendersSince(UUID tenantId, UUID storeId, Instant since) {
    return query(
            "SELECT count(*) AS card, count(*) FILTER (WHERE entry_mode = 'STANDALONE') AS standalone"
                + " FROM payment_tenders WHERE tenant_id = ? AND store_id = ? AND method = 'CARD'"
                + " AND status = 'CAPTURED' AND created_at >= ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, since.atOffset(ZoneOffset.UTC));
            },
            rs -> new Tenders(rs.getLong("card"), rs.getLong("standalone")),
            "count card tenders at a store")
        .get(0);
  }
}
