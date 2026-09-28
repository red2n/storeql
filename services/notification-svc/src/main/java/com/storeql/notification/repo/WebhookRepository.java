package com.storeql.notification.repo;

import com.storeql.notification.domain.Webhooks.Attempt;
import com.storeql.notification.domain.Webhooks.Delivery;
import com.storeql.notification.domain.Webhooks.Due;
import com.storeql.notification.domain.Webhooks.Endpoint;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The webhook tables (22.6): endpoints by business, deliveries by endpoint and event, attempts by
 * delivery. A delivery is made once per event and endpoint whatever Kafka redelivers ({@code
 * uq_webhook_deliveries_event}); a due delivery is claimed under a lease so two instances of this
 * service never send the same one; every attempt is a row of its own.
 */
@ApplicationScoped
public class WebhookRepository extends BaseJdbcRepository {

  private static final String ENDPOINT_COLUMNS =
      "e.id, e.tenant_id, e.url, e.description, e.secret_sealed, e.events, e.enabled,"
          + " e.disabled_reason, e.consecutive_failures, e.last_delivered_at, e.created_by,"
          + " e.created_at, e.updated_at";

  private static final String DELIVERY_COLUMNS =
      "d.id, d.tenant_id, d.endpoint_id, d.event_id, d.event_type, d.payload, d.status,"
          + " d.attempts, d.next_attempt_at, d.delivered_at, d.last_status, d.last_error,"
          + " d.created_at";

  private static final String INSERT_ENDPOINT =
      "INSERT INTO webhook_endpoints (id, tenant_id, url, description, secret_sealed, events,"
          + " enabled, disabled_reason, consecutive_failures, last_delivered_at, created_by,"
          + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String ENDPOINT =
      "SELECT " + ENDPOINT_COLUMNS + " FROM webhook_endpoints e WHERE e.tenant_id = ? AND e.id = ?";

  private static final String ENDPOINTS =
      "SELECT "
          + ENDPOINT_COLUMNS
          + " FROM webhook_endpoints e WHERE e.tenant_id = ? ORDER BY e.id";

  private static final String SUBSCRIBED =
      "SELECT "
          + ENDPOINT_COLUMNS
          + " FROM webhook_endpoints e WHERE e.tenant_id = ? AND e.enabled AND ? = ANY(e.events)"
          + " ORDER BY e.id";

  private static final String UPDATE_ENDPOINT =
      "UPDATE webhook_endpoints SET url = ?, description = ?, events = ?, enabled = ?,"
          + " disabled_reason = ?, consecutive_failures = ?, updated_at = ?"
          + " WHERE tenant_id = ? AND id = ?";

  private static final String ROTATE =
      "UPDATE webhook_endpoints SET secret_sealed = ?, updated_at = ? WHERE tenant_id = ? AND id = ?";

  private static final String DELETE_ENDPOINT =
      "DELETE FROM webhook_endpoints WHERE tenant_id = ? AND id = ?";

  private static final String INSERT_DELIVERY =
      "INSERT INTO webhook_deliveries (id, tenant_id, endpoint_id, event_id, event_type, payload,"
          + " status, attempts, next_attempt_at, delivered_at, last_status, last_error, created_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (endpoint_id, event_id) DO NOTHING";

  /** Due deliveries of endpoints still switched on, leased so another instance leaves them be. */
  private static final String CLAIM =
      "UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id IN (SELECT d.id FROM"
          + " webhook_deliveries d JOIN webhook_endpoints e ON e.id = d.endpoint_id WHERE d.status ="
          + " 'PENDING' AND d.next_attempt_at <= ? AND e.enabled ORDER BY d.next_attempt_at LIMIT ?"
          + " FOR UPDATE OF d SKIP LOCKED) RETURNING id";

  /** The claimed deliveries, then their endpoints: two reads, so no column is read for another. */
  private static final String CLAIMED =
      "SELECT " + DELIVERY_COLUMNS + " FROM webhook_deliveries d WHERE d.id = ANY(?) ORDER BY d.id";

  private static final String ENDPOINTS_BY_ID =
      "SELECT " + ENDPOINT_COLUMNS + " FROM webhook_endpoints e WHERE e.id = ANY(?)";

  private static final String INSERT_ATTEMPT =
      "INSERT INTO webhook_attempts (id, tenant_id, delivery_id, attempt, attempted_at, status_code,"
          + " error, response_snippet, duration_ms) VALUES (?,?,?,?,?,?,?,?,?)";

  private static final String UPDATE_DELIVERY =
      "UPDATE webhook_deliveries SET status = ?, attempts = ?, next_attempt_at = ?, delivered_at ="
          + " ?, last_status = ?, last_error = ? WHERE id = ?";

  private static final String ENDPOINT_SUCCEEDED =
      "UPDATE webhook_endpoints SET consecutive_failures = 0, last_delivered_at = ?, updated_at = ?"
          + " WHERE id = ?";

  private static final String ENDPOINT_FAILED =
      "UPDATE webhook_endpoints SET consecutive_failures = consecutive_failures + 1, updated_at = ?"
          + " WHERE id = ? RETURNING consecutive_failures, enabled";

  private static final String ENDPOINT_DISABLE =
      "UPDATE webhook_endpoints SET enabled = false, disabled_reason = ?, updated_at = ?"
          + " WHERE id = ?";

  private static final String DELIVERY =
      "SELECT "
          + DELIVERY_COLUMNS
          + " FROM webhook_deliveries d WHERE d.tenant_id = ? AND d.id = ?";

  private static final String ATTEMPTS =
      "SELECT id, tenant_id, delivery_id, attempt, attempted_at, status_code, error,"
          + " response_snippet, duration_ms FROM webhook_attempts WHERE delivery_id = ?"
          + " ORDER BY attempt";

  private static final String REDELIVER =
      "UPDATE webhook_deliveries SET status = 'PENDING', next_attempt_at = ? WHERE tenant_id = ?"
          + " AND id = ?";

  private static final String PRUNE =
      "DELETE FROM webhook_deliveries WHERE created_at < ? AND status <> 'PENDING'";

  // ── endpoints ──────────────────────────────────────────────────────────────

  public void insertEndpoint(Endpoint e) {
    exec(
        INSERT_ENDPOINT,
        ps -> {
          ps.setObject(1, e.id());
          ps.setObject(2, e.tenantId());
          ps.setString(3, e.url());
          ps.setString(4, e.description());
          ps.setString(5, e.secretSealed());
          ps.setArray(6, ps.getConnection().createArrayOf("text", e.events().toArray()));
          ps.setBoolean(7, e.enabled());
          ps.setString(8, e.disabledReason());
          ps.setInt(9, e.consecutiveFailures());
          ps.setObject(10, at(e.lastDeliveredAt()));
          ps.setObject(11, e.createdBy());
          ps.setObject(12, at(e.createdAt()));
          ps.setObject(13, at(e.updatedAt()));
        },
        "insert webhook endpoint");
  }

  public Optional<Endpoint> endpoint(UUID tenantId, UUID id) {
    return query(
            ENDPOINT,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            WebhookRepository::readEndpoint,
            "webhook endpoint")
        .stream()
        .findFirst();
  }

  public List<Endpoint> endpoints(UUID tenantId) {
    return query(
        ENDPOINTS,
        ps -> ps.setObject(1, tenantId),
        WebhookRepository::readEndpoint,
        "webhook endpoints");
  }

  /** The endpoints of a business switched on and asking for an event type. */
  public List<Endpoint> subscribed(UUID tenantId, String eventType) {
    return query(
        SUBSCRIBED,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, eventType);
        },
        WebhookRepository::readEndpoint,
        "subscribed webhook endpoints");
  }

  public boolean updateEndpoint(Endpoint e) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(UPDATE_ENDPOINT)) {
            ps.setString(1, e.url());
            ps.setString(2, e.description());
            ps.setArray(3, c.createArrayOf("text", e.events().toArray()));
            ps.setBoolean(4, e.enabled());
            ps.setString(5, e.disabledReason());
            ps.setInt(6, e.consecutiveFailures());
            ps.setObject(7, at(e.updatedAt()));
            ps.setObject(8, e.tenantId());
            ps.setObject(9, e.id());
            return ps.executeUpdate() == 1;
          }
        },
        "update webhook endpoint");
  }

  public boolean rotateSecret(UUID tenantId, UUID id, String sealed, Instant at) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(ROTATE)) {
            ps.setString(1, sealed);
            ps.setObject(2, at(at));
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            return ps.executeUpdate() == 1;
          }
        },
        "rotate webhook secret");
  }

  public boolean deleteEndpoint(UUID tenantId, UUID id) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(DELETE_ENDPOINT)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            return ps.executeUpdate() == 1;
          }
        },
        "delete webhook endpoint");
  }

  // ── deliveries ─────────────────────────────────────────────────────────────

  /** Queues a delivery per row; one already queued for its endpoint and event is left as it is. */
  public int fanout(List<Delivery> rows) {
    return inTx(
        c -> {
          int made = 0;
          for (Delivery d : rows) made += insertDeliveryTx(c, d);
          return made;
        },
        "queue webhook deliveries");
  }

  public void insertDelivery(Delivery d) {
    inTx(c -> insertDeliveryTx(c, d), "queue webhook delivery");
  }

  private static int insertDeliveryTx(Connection c, Delivery d) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_DELIVERY)) {
      ps.setObject(1, d.id());
      ps.setObject(2, d.tenantId());
      ps.setObject(3, d.endpointId());
      ps.setObject(4, d.eventId());
      ps.setString(5, d.eventType());
      ps.setString(6, d.payload());
      ps.setString(7, d.status());
      ps.setInt(8, d.attempts());
      ps.setObject(9, at(d.nextAttemptAt()));
      ps.setObject(10, at(d.deliveredAt()));
      ps.setObject(11, d.lastStatus());
      ps.setString(12, d.lastError());
      ps.setObject(13, at(d.createdAt()));
      return ps.executeUpdate();
    }
  }

  /** The deliveries due now, at most {@code limit}, each leased for {@code lease} from now. */
  public List<Due> claimDue(int limit, Instant now, Duration lease) {
    return inTx(
        c -> {
          List<UUID> ids = new ArrayList<>();
          try (PreparedStatement ps = c.prepareStatement(CLAIM)) {
            ps.setObject(1, at(now.plus(lease)));
            ps.setObject(2, at(now));
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) ids.add(rs.getObject("id", UUID.class));
            }
          }
          if (ids.isEmpty()) return List.of();
          List<Delivery> claimed = new ArrayList<>();
          try (PreparedStatement ps = c.prepareStatement(CLAIMED)) {
            ps.setArray(1, c.createArrayOf("uuid", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) claimed.add(readDelivery(rs));
            }
          }
          java.util.Map<UUID, Endpoint> endpoints = new java.util.HashMap<>();
          try (PreparedStatement ps = c.prepareStatement(ENDPOINTS_BY_ID)) {
            ps.setArray(
                1,
                c.createArrayOf(
                    "uuid", claimed.stream().map(Delivery::endpointId).distinct().toArray()));
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                Endpoint e = readEndpoint(rs);
                endpoints.put(e.id(), e);
              }
            }
          }
          List<Due> due = new ArrayList<>();
          for (Delivery d : claimed) {
            Endpoint e = endpoints.get(d.endpointId());
            if (e != null) due.add(new Due(d, e));
          }
          return due;
        },
        "claim due webhook deliveries");
  }

  /** One try recorded, and the delivery as it now stands, in one transaction. */
  public void recordAttempt(Attempt a, Delivery d) {
    inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(INSERT_ATTEMPT)) {
            ps.setObject(1, a.id());
            ps.setObject(2, a.tenantId());
            ps.setObject(3, a.deliveryId());
            ps.setInt(4, a.attempt());
            ps.setObject(5, at(a.attemptedAt()));
            ps.setObject(6, a.statusCode());
            ps.setString(7, a.error());
            ps.setString(8, a.responseSnippet());
            ps.setInt(9, a.durationMs());
            ps.executeUpdate();
          }
          try (PreparedStatement ps = c.prepareStatement(UPDATE_DELIVERY)) {
            ps.setString(1, d.status());
            ps.setInt(2, d.attempts());
            ps.setObject(3, at(d.nextAttemptAt()));
            ps.setObject(4, at(d.deliveredAt()));
            ps.setObject(5, d.lastStatus());
            ps.setString(6, d.lastError());
            ps.setObject(7, d.id());
            ps.executeUpdate();
          }
          return null;
        },
        "record webhook attempt");
  }

  public void endpointSucceeded(UUID endpointId, Instant at) {
    exec(
        ENDPOINT_SUCCEEDED,
        ps -> {
          ps.setObject(1, at(at));
          ps.setObject(2, at(at));
          ps.setObject(3, endpointId);
        },
        "webhook endpoint succeeded");
  }

  /**
   * One more failure in a row; the endpoint is switched off at the threshold.
   *
   * @return true when this failure switched it off
   */
  public boolean endpointFailed(UUID endpointId, int disableAfter, String reason, Instant at) {
    return inTx(
        c -> {
          int failures;
          boolean enabled;
          try (PreparedStatement ps = c.prepareStatement(ENDPOINT_FAILED)) {
            ps.setObject(1, at(at));
            ps.setObject(2, endpointId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return false;
              failures = rs.getInt("consecutive_failures");
              enabled = rs.getBoolean("enabled");
            }
          }
          if (!enabled || failures < disableAfter) return false;
          try (PreparedStatement ps = c.prepareStatement(ENDPOINT_DISABLE)) {
            ps.setString(1, reason);
            ps.setObject(2, at(at));
            ps.setObject(3, endpointId);
            ps.executeUpdate();
          }
          return true;
        },
        "webhook endpoint failed");
  }

  public Optional<Delivery> delivery(UUID tenantId, UUID id) {
    return query(
            DELIVERY,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            WebhookRepository::readDelivery,
            "webhook delivery")
        .stream()
        .findFirst();
  }

  public List<Attempt> attempts(UUID deliveryId) {
    return query(
        ATTEMPTS,
        ps -> ps.setObject(1, deliveryId),
        WebhookRepository::readAttempt,
        "webhook attempts");
  }

  /** A business's deliveries, newest first, narrowed to an endpoint and a status when asked. */
  public List<Delivery> deliveries(
      UUID tenantId, UUID endpointId, String status, UUID after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(DELIVERY_COLUMNS)
            .append(" FROM webhook_deliveries d WHERE d.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenantId);
    if (endpointId != null) {
      sql.append(" AND d.endpoint_id = ?");
      params.add(endpointId);
    }
    if (status != null) {
      sql.append(" AND d.status = ?");
      params.add(status);
    }
    if (after != null) {
      sql.append(" AND d.id < ?");
      params.add(after);
    }
    sql.append(" ORDER BY d.id DESC LIMIT ?");
    params.add(limit);
    return query(
        sql.toString(),
        ps -> {
          for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
        },
        WebhookRepository::readDelivery,
        "webhook deliveries");
  }

  public boolean redeliver(UUID tenantId, UUID id, Instant now) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(REDELIVER)) {
            ps.setObject(1, at(now));
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            return ps.executeUpdate() == 1;
          }
        },
        "redeliver webhook");
  }

  /** Settled deliveries older than a day are let go, attempts with them. */
  public int prune(Instant before) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(PRUNE)) {
            ps.setObject(1, at(before));
            return ps.executeUpdate();
          }
        },
        "prune webhook deliveries");
  }

  // ── rows ───────────────────────────────────────────────────────────────────

  private static OffsetDateTime at(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  private static Integer integer(ResultSet rs, String column) throws SQLException {
    int v = rs.getInt(column);
    return rs.wasNull() ? null : v;
  }

  private static Endpoint readEndpoint(ResultSet rs) throws SQLException {
    Array events = rs.getArray("events");
    List<String> types = new ArrayList<>();
    if (events != null) for (Object o : (Object[]) events.getArray()) types.add((String) o);
    return new Endpoint(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("url"),
        rs.getString("description"),
        rs.getString("secret_sealed"),
        types,
        rs.getBoolean("enabled"),
        rs.getString("disabled_reason"),
        rs.getInt("consecutive_failures"),
        instant(rs, "last_delivered_at"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Delivery readDelivery(ResultSet rs) throws SQLException {
    return new Delivery(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("endpoint_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("event_type"),
        rs.getString("payload"),
        rs.getString("status"),
        rs.getInt("attempts"),
        instant(rs, "next_attempt_at"),
        instant(rs, "delivered_at"),
        integer(rs, "last_status"),
        rs.getString("last_error"),
        instant(rs, "created_at"));
  }

  private static Attempt readAttempt(ResultSet rs) throws SQLException {
    return new Attempt(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("delivery_id", UUID.class),
        rs.getInt("attempt"),
        instant(rs, "attempted_at"),
        integer(rs, "status_code"),
        rs.getString("error"),
        rs.getString("response_snippet"),
        rs.getInt("duration_ms"));
  }
}
