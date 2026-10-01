package com.storeql.tenant.repo;

import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Broadcasts;
import com.storeql.tenant.domain.Broadcasts.Ack;
import com.storeql.tenant.domain.Broadcasts.Broadcast;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Notices from management to the shop floor, and who acknowledged them (store operations &
 * workforce).
 *
 * <p>A notice and its announcements are one transaction: a notice published but never announced is
 * one the store's devices never wake for, and one announced but not published is a phantom. One
 * acknowledgement per person per notice is a unique constraint, so a second tap is not a second
 * reading and a manager's count cannot exceed the staff.
 */
@ApplicationScoped
public class BroadcastRepository extends BaseOutboxRepository {

  private static final String COLUMNS =
      "id, tenant_id, title, body, priority, store_id, role, requires_ack, published_at, expires_at,"
          + " status, created_by, withdrawn_at, withdrawn_by, withdrawn_reason";

  /** A person on a store's staff and the roles and tiers they hold there. */
  public record Staff(UUID userId, UUID storeId, List<String> roles) {
    public Staff {
      roles = List.copyOf(roles);
    }
  }

  /** Publishes a notice and puts its announcements on the outbox, together. */
  public Broadcast publish(Broadcast b, List<OutboxRow> announcements) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO store_broadcasts ("
                      + COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, b.id());
            ps.setObject(2, b.tenantId());
            ps.setString(3, b.title());
            ps.setString(4, b.body());
            ps.setString(5, b.priority());
            ps.setObject(6, b.storeId());
            ps.setString(7, b.role());
            ps.setBoolean(8, b.requiresAck());
            ps.setObject(9, b.publishedAt().atOffset(ZoneOffset.UTC));
            ps.setObject(10, b.expiresAt() == null ? null : b.expiresAt().atOffset(ZoneOffset.UTC));
            ps.setString(11, b.status());
            ps.setObject(12, b.createdBy());
            ps.setObject(13, null);
            ps.setObject(14, null);
            ps.setString(15, null);
            ps.executeUpdate();
          }
          for (OutboxRow row : announcements) insertOutbox(c, row);
          return b;
        },
        "publish a notice");
  }

  /** Withdraws a notice; false when it was not published. Its acknowledgements stay. */
  public boolean withdraw(UUID tenantId, UUID id, UUID actorId, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE store_broadcasts SET status = ?, withdrawn_at = ?, withdrawn_by = ?,"
                      + " withdrawn_reason = ? WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, Broadcasts.WITHDRAWN);
            ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
            ps.setObject(3, actorId);
            ps.setString(4, reason);
            ps.setObject(5, tenantId);
            ps.setObject(6, id);
            ps.setString(7, Broadcasts.PUBLISHED);
            return ps.executeUpdate() > 0;
          }
        },
        "withdraw a notice");
  }

  public Optional<Broadcast> broadcast(UUID tenantId, UUID id) {
    return query(
            "SELECT " + COLUMNS + " FROM store_broadcasts WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            BroadcastRepository::map,
            "read a notice")
        .stream()
        .findFirst();
  }

  /** The business's notices, newest first; published only unless asked for all. */
  public List<Broadcast> broadcasts(UUID tenantId, boolean publishedOnly, int limit) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM store_broadcasts WHERE tenant_id = ?"
            + (publishedOnly ? " AND status = 'PUBLISHED'" : "")
            + " ORDER BY published_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        BroadcastRepository::map,
        "list notices");
  }

  /**
   * The published notices that may concern a store: its own and the business-wide ones, newest
   * first. Whether each is current and addressed to a person is the domain's call.
   */
  public List<Broadcast> publishedFor(UUID tenantId, UUID storeId) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM store_broadcasts WHERE tenant_id = ? AND status = 'PUBLISHED'"
            + " AND (store_id IS NULL OR store_id = ?) ORDER BY published_at DESC LIMIT 200",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        BroadcastRepository::map,
        "notices for a store");
  }

  /**
   * Records an acknowledgement.
   *
   * @throws ApiException 409 {@code BROADCAST_ALREADY_ACKNOWLEDGED} — the constraint decides
   */
  public Ack acknowledge(Ack a) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO store_broadcast_acks (id, tenant_id, broadcast_id, user_id, store_id,"
                      + " acked_at) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, a.id());
            ps.setObject(2, a.tenantId());
            ps.setObject(3, a.broadcastId());
            ps.setObject(4, a.userId());
            ps.setObject(5, a.storeId());
            ps.setObject(6, a.ackedAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return a;
        },
        "acknowledge a notice");
  }

  /** Who has acknowledged a notice, by person. */
  public Map<UUID, Instant> acks(UUID tenantId, UUID broadcastId) {
    Map<UUID, Instant> out = new LinkedHashMap<>();
    for (Ack a :
        query(
            "SELECT id, tenant_id, broadcast_id, user_id, store_id, acked_at FROM store_broadcast_acks"
                + " WHERE tenant_id = ? AND broadcast_id = ? ORDER BY acked_at",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, broadcastId);
            },
            rs ->
                new Ack(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("broadcast_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("store_id", UUID.class),
                    rs.getObject("acked_at", OffsetDateTime.class).toInstant()),
            "read acknowledgements")) {
      out.put(a.userId(), a.ackedAt());
    }
    return out;
  }

  /** When one person acknowledged each notice they have, by notice: one query for their feed. */
  public Map<UUID, Instant> ackTimesBy(UUID tenantId, UUID userId) {
    Map<UUID, Instant> out = new LinkedHashMap<>();
    for (Map.Entry<UUID, Instant> e :
        query(
            "SELECT broadcast_id, acked_at FROM store_broadcast_acks WHERE tenant_id = ? AND user_id = ?"
                + " ORDER BY acked_at",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
            },
            rs ->
                Map.entry(
                    rs.getObject("broadcast_id", UUID.class),
                    rs.getObject("acked_at", OffsetDateTime.class).toInstant()),
            "a person's acknowledgement times")) {
      out.put(e.getKey(), e.getValue());
    }
    return out;
  }

  /** The notices one person has acknowledged, for their own view. */
  public List<UUID> acknowledgedBy(UUID tenantId, UUID userId) {
    return query(
        "SELECT broadcast_id FROM store_broadcast_acks WHERE tenant_id = ? AND user_id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, userId);
        },
        rs -> rs.getObject("broadcast_id", UUID.class),
        "a person's acknowledgements");
  }

  /**
   * Everybody on a store's staff and what they hold there, or on every store's staff.
   *
   * @param storeId one store, or null for all of them
   */
  public List<Staff> staff(UUID tenantId, UUID storeId) {
    Map<String, UUID[]> people = new LinkedHashMap<>();
    Map<String, List<String>> roles = new LinkedHashMap<>();
    for (Object[] row :
        query(
            "SELECT user_id, store_id, role, base_tier FROM staff_assignments WHERE tenant_id = ?"
                + " AND store_id IS NOT NULL AND (CAST(? AS uuid) IS NULL OR store_id = CAST(? AS uuid)) ORDER BY store_id, user_id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, storeId);
            },
            rs ->
                new Object[] {
                  rs.getObject("user_id", UUID.class),
                  rs.getObject("store_id", UUID.class),
                  rs.getString("role"),
                  rs.getString("base_tier")
                },
            "a store's staff")) {
      String key = row[0] + "@" + row[1];
      people.putIfAbsent(key, new UUID[] {(UUID) row[0], (UUID) row[1]});
      List<String> held = roles.computeIfAbsent(key, k -> new ArrayList<>());
      if (row[2] != null) held.add((String) row[2]);
      if (row[3] != null && !held.contains(row[3])) held.add((String) row[3]);
    }
    List<Staff> out = new ArrayList<>(people.size());
    for (Map.Entry<String, UUID[]> e : people.entrySet()) {
      out.add(new Staff(e.getValue()[0], e.getValue()[1], roles.get(e.getKey())));
    }
    return List.copyOf(out);
  }

  /** One person's roles and tiers at a store. */
  public List<String> rolesAt(UUID tenantId, UUID userId, UUID storeId) {
    List<String> held = new ArrayList<>();
    for (String[] pair :
        query(
            "SELECT role, base_tier FROM staff_assignments WHERE tenant_id = ? AND user_id = ? AND store_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
              ps.setObject(3, storeId);
            },
            rs -> new String[] {rs.getString("role"), rs.getString("base_tier")},
            "a person's roles at a store")) {
      if (pair[0] != null) held.add(pair[0]);
      if (pair[1] != null && !held.contains(pair[1])) held.add(pair[1]);
    }
    return List.copyOf(held);
  }

  /** The stores a business-wide notice reaches: every open store. */
  public List<UUID> openStores(UUID tenantId) {
    return query(
        "SELECT id FROM stores WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY id",
        ps -> ps.setObject(1, tenantId),
        rs -> rs.getObject("id", UUID.class),
        "open stores");
  }

  private static Broadcast map(ResultSet rs) throws SQLException {
    return new Broadcast(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("title"),
        rs.getString("body"),
        rs.getString("priority"),
        rs.getObject("store_id", UUID.class),
        rs.getString("role"),
        rs.getBoolean("requires_ack"),
        instant(rs, "published_at"),
        instant(rs, "expires_at"),
        rs.getString("status"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "withdrawn_at"),
        rs.getObject("withdrawn_by", UUID.class),
        rs.getString("withdrawn_reason"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  /** The one race the database decides here, named so a second tap is told what it was. */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains("uq_broadcast_ack")) {
      return ApiException.conflict(
          "BROADCAST_ALREADY_ACKNOWLEDGED", "you have already acknowledged that notice");
    }
    return super.handleTxSqlException(what, e);
  }
}
