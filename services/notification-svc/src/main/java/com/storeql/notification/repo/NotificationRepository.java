package com.storeql.notification.repo;

import com.storeql.ids.Ids;
import com.storeql.notification.domain.Domain.NotificationLog;
import com.storeql.notification.domain.Domain.PushDevice;
import com.storeql.notification.domain.Domain.ShortageAlert;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * JDBC access to notification-svc's alert and notification-log tables.
 *
 * <p>The {@code *Once} and {@code alreadyNotified} methods are the send-once guards: they fold the
 * dedupe check into the write so a redelivered Kafka event cannot raise a duplicate alert or send a
 * second copy of the same message. The {@code redact*} methods back GDPR erasure.
 */
@ApplicationScoped
public class NotificationRepository extends BaseJdbcRepository {

  /**
   * Insert a shortage alert, deduped on its eventId: the processed_events mark and the insert
   * commit in ONE transaction so a redelivered event is skipped and a crashed write is retried —
   * never duplicated and never lost. Returns false if the event was already processed.
   */
  public boolean insertAlertOnce(String consumerName, ShortageAlert alert) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, alert.eventId(), consumerName)) {
            return false;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO shortage_alerts"
                      + " (id, tenant_id, store_id, variant_id, available, threshold, event_id)"
                      + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, alert.id());
            ps.setObject(2, alert.tenantId());
            ps.setObject(3, alert.storeId());
            ps.setObject(4, alert.variantId());
            ps.setBigDecimal(5, alert.available());
            ps.setBigDecimal(6, alert.threshold());
            ps.setObject(7, alert.eventId());
            ps.executeUpdate();
          }
          return true;
        },
        "insert shortage alert");
  }

  /**
   * Lists a store's shortage alerts, newest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store whose alerts to list
   * @param limit maximum rows to return; the caller is expected to have capped this
   * @return the matching alerts, newest first
   */
  public List<ShortageAlert> listAlerts(UUID tenantId, UUID storeId, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, available, threshold, event_id, alerted_at"
                + " FROM shortage_alerts WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    sb.append(" ORDER BY alerted_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
          ps.setInt(storeId != null ? 3 : 2, limit);
        },
        NotificationRepository::mapAlert,
        "list shortage alerts");
  }

  /**
   * Lists one variant's shortage alerts across every store, newest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant whose alerts to list
   * @param limit maximum rows to return; the caller is expected to have capped this
   * @return the matching alerts, newest first
   */
  public List<ShortageAlert> listAlertsByVariant(UUID tenantId, UUID variantId, int limit) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, available, threshold, event_id, alerted_at"
            + " FROM shortage_alerts WHERE tenant_id = ? AND variant_id = ?"
            + " ORDER BY alerted_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
          ps.setInt(3, limit);
        },
        NotificationRepository::mapAlert,
        "list shortage alerts by variant");
  }

  // ── Outbound notification log (N1) ────────────────────────────────────────

  /** True if this event already produced a notification of this type (send-once guard). */
  public boolean alreadyNotified(UUID eventId, String type) {
    return !query(
            "SELECT 1 FROM notification_log WHERE event_id = ? AND type = ?",
            ps -> {
              ps.setObject(1, eventId);
              ps.setString(2, type);
            },
            rs -> Boolean.TRUE,
            "check notification log")
        .isEmpty();
  }

  /**
   * Record a delivered notification. {@code ON CONFLICT (event_id, type) DO NOTHING} makes a
   * concurrent/redelivered send a no-op even if the {@link #alreadyNotified} pre-check raced.
   */
  public void recordNotification(
      UUID tenantId,
      UUID subjectId,
      UUID eventId,
      String type,
      String channel,
      String recipient,
      String subject,
      String body,
      String status) {
    recordNotification(
        tenantId, subjectId, eventId, type, channel, recipient, subject, body, status, null, null);
  }

  /**
   * Records a send, with what it was written in.
   *
   * @param language the language the message was written in; null when not known
   * @param template {@code default}, or the business's version, e.g. {@code v3}
   */
  public void recordNotification(
      UUID tenantId,
      UUID subjectId,
      UUID eventId,
      String type,
      String channel,
      String recipient,
      String subject,
      String body,
      String status,
      String language,
      String template) {
    exec(
        "INSERT INTO notification_log"
            + " (id, tenant_id, subject_id, event_id, type, channel, recipient, subject, body,"
            + " status, language, template)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (event_id, type) DO NOTHING",
        ps -> {
          ps.setObject(1, Ids.newId());
          ps.setObject(2, tenantId);
          ps.setObject(3, subjectId);
          ps.setObject(4, eventId);
          ps.setString(5, type);
          ps.setString(6, channel);
          ps.setString(7, recipient);
          ps.setString(8, subject);
          ps.setString(9, body);
          ps.setString(10, status);
          ps.setString(11, language);
          ps.setString(12, template);
        },
        "record notification");
  }

  /**
   * Erases the messages one shop sent about one customer (SJ-D43). The row stays, so the send is
   * still accounted for; who it went to and what it said do not. Idempotent: a redacted row is not
   * touched again.
   */
  public int redactForCustomer(UUID tenantId, UUID customerId) {
    return redact(
        "UPDATE notification_log SET recipient = '[erased]', subject = '[erased]', body = '',"
            + " redacted_at = now()"
            + " WHERE tenant_id = ? AND subject_id = ? AND redacted_at IS NULL",
        tenantId,
        customerId);
  }

  /**
   * Erases the platform's own messages about a deleted account — the ones sent with no shop, such
   * as WELCOME. Messages a shop sent stay with that shop, which erases them itself.
   */
  public int redactForAccount(UUID userId) {
    return redact(
        "UPDATE notification_log SET recipient = '[erased]', subject = '[erased]', body = '',"
            + " redacted_at = now()"
            + " WHERE tenant_id IS NULL AND subject_id = ? AND redacted_at IS NULL",
        userId);
  }

  /**
   * Deletes password-reset rows older than the cutoff: a platform rule, not any business's
   * retention schedule — the row belongs to no tenant, so no tenant's own period ever reaches it,
   * and there is no hold to check (nobody's account can keep this one back). Called by {@link
   * com.storeql.notification.service.RetentionPurgeService} on its own daily sweep, once, never per
   * tenant.
   */
  public int purgePasswordResetsBefore(Instant cutoff) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "DELETE FROM notification_log WHERE type = 'PASSWORD_RESET' AND tenant_id IS"
                      + " NULL AND created_at < ?")) {
            ps.setObject(1, cutoff.atOffset(ZoneOffset.UTC));
            return ps.executeUpdate();
          }
        },
        "purge password reset log");
  }

  private int redact(String sql, UUID... params) {
    return inTx(
        c -> {
          try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
              ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
          }
        },
        "redact notifications");
  }

  /** Recent in-app notifications for a tenant, newest first, optionally filtered by recipient. */
  public List<NotificationLog> listRecent(UUID tenantId, String recipient, int limit) {
    return listRecent(tenantId, recipient, null, limit);
  }

  /** The feed, optionally narrowed to one recipient and one channel (13.7). */
  public List<NotificationLog> listRecent(
      UUID tenantId, String recipient, String channel, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, event_id, type, channel, recipient, subject, body, status,"
                + " created_at, language, template FROM notification_log WHERE tenant_id = ?");
    if (recipient != null) sb.append(" AND recipient = ?");
    if (channel != null) sb.append(" AND channel = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (recipient != null) ps.setString(i++, recipient);
          if (channel != null) ps.setString(i++, channel);
          ps.setInt(i, limit);
        },
        NotificationRepository::mapNotification,
        "list notifications");
  }

  // ── push devices (13.7) ───────────────────────────────────────────────────

  /** How many devices one login holds here — the cap is decided by the caller. */
  public long countDevices(UUID tenantId, UUID userId) {
    return query(
            "SELECT count(*) FROM push_devices WHERE tenant_id = ? AND user_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
            },
            rs -> rs.getLong(1),
            "count devices")
        .get(0);
  }

  /**
   * Registers a device, or refreshes it when the same token is registered again: a token belongs to
   * one device, and the login that last presented it is the one it reaches.
   */
  public PushDevice registerDevice(PushDevice d) {
    exec(
        "INSERT INTO push_devices (id, tenant_id, user_id, platform, token)"
            + " VALUES (?,?,?,?,?)"
            + " ON CONFLICT (tenant_id, token) DO UPDATE SET user_id = EXCLUDED.user_id,"
            + " platform = EXCLUDED.platform, last_seen_at = now()",
        ps -> {
          ps.setObject(1, d.id());
          ps.setObject(2, d.tenantId());
          ps.setObject(3, d.userId());
          ps.setString(4, d.platform());
          ps.setString(5, d.token());
        },
        "register device");
    return query(
            "SELECT id, tenant_id, user_id, platform, token, registered_at, last_seen_at"
                + " FROM push_devices WHERE tenant_id = ? AND token = ?",
            ps -> {
              ps.setObject(1, d.tenantId());
              ps.setString(2, d.token());
            },
            NotificationRepository::mapDevice,
            "read device")
        .get(0);
  }

  public List<PushDevice> devicesFor(UUID tenantId, UUID userId) {
    return query(
        "SELECT id, tenant_id, user_id, platform, token, registered_at, last_seen_at"
            + " FROM push_devices WHERE tenant_id = ? AND user_id = ? ORDER BY last_seen_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, userId);
        },
        NotificationRepository::mapDevice,
        "list devices");
  }

  /** Removes one of a login's own devices; another login's is not found. */
  public boolean deleteDevice(UUID tenantId, UUID userId, UUID id) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "DELETE FROM push_devices WHERE tenant_id = ? AND user_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            ps.setObject(3, id);
            return ps.executeUpdate() > 0;
          }
        },
        "delete device");
  }

  /** Forgets a device the provider no longer knows, whoever registered it. */
  public void forgetDevice(UUID tenantId, UUID id) {
    exec(
        "DELETE FROM push_devices WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        "forget device");
  }

  private static PushDevice mapDevice(ResultSet rs) throws SQLException {
    return new PushDevice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("platform"),
        rs.getString("token"),
        rs.getObject("registered_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("last_seen_at", java.time.OffsetDateTime.class).toInstant());
  }

  private static NotificationLog mapNotification(ResultSet rs) throws SQLException {
    return new NotificationLog(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("type"),
        rs.getString("channel"),
        rs.getString("recipient"),
        rs.getString("subject"),
        rs.getString("body"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getString("language"),
        rs.getString("template"));
  }

  private static ShortageAlert mapAlert(ResultSet rs) throws SQLException {
    return new ShortageAlert(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("available"),
        rs.getBigDecimal("threshold"),
        rs.getObject("event_id", UUID.class),
        rs.getObject("alerted_at", OffsetDateTime.class).toInstant());
  }
}
