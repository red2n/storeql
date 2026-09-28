package com.storeql.order.repo;

import com.storeql.order.domain.Windows;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivery and collection slots: {@code fulfilment_windows}, and the one transactional operation
 * ({@link #claimTx}) that takes a place in one while an order is being written — called from {@link
 * OrderRepository} on the connection it already has open, the same way {@link DepositRepository}
 * writes deposit lines inside an order's own transaction.
 */
@ApplicationScoped
public class FulfilmentWindowRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, store_id, fulfilment_type, weekday, start_time, end_time, capacity, cutoff_minutes,"
          + " active, time_zone, updated_at, updated_by";

  /** How many places are already taken at one occurrence of one window. */
  public record TakenCount(UUID windowId, Instant startsAt, long taken) {}

  /**
   * Saves a new window. {@code updatedBy} is who set it — the same person the read shows until
   * somebody edits it.
   */
  public Windows.WindowRecord create(
      UUID tenantId, Windows.Window w, String timeZone, UUID updatedBy) {
    return query(
            "INSERT INTO fulfilment_windows"
                + " (id, tenant_id, store_id, fulfilment_type, weekday, start_time, end_time,"
                + "  capacity, cutoff_minutes, active, time_zone, updated_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                + " RETURNING "
                + COLUMNS,
            ps -> {
              ps.setObject(1, w.id());
              ps.setObject(2, tenantId);
              ps.setObject(3, w.storeId());
              ps.setString(4, w.fulfilmentType());
              ps.setInt(5, w.weekday());
              ps.setObject(6, w.startTime());
              ps.setObject(7, w.endTime());
              ps.setInt(8, w.capacity());
              ps.setInt(9, w.cutoffMinutes());
              ps.setBoolean(10, w.active());
              ps.setString(11, timeZone);
              ps.setObject(12, updatedBy);
            },
            FulfilmentWindowRepository::mapRow,
            "create fulfilment window")
        .get(0);
  }

  /**
   * Updates a window's shape — everything but the store and the fulfilment type, which never change
   * once set (a different store or type is a different window).
   *
   * @throws ApiException 404 {@code ORDER_SLOT_WINDOW_NOT_FOUND} when no such window stands in this
   *     tenant
   */
  public Windows.WindowRecord update(UUID tenantId, UUID id, Windows.Window shape, UUID updatedBy) {
    var rows =
        query(
            "UPDATE fulfilment_windows SET weekday=?, start_time=?, end_time=?, capacity=?,"
                + " cutoff_minutes=?, active=?, updated_at=now(), updated_by=?"
                + " WHERE tenant_id=? AND id=?"
                + " RETURNING "
                + COLUMNS,
            ps -> {
              ps.setInt(1, shape.weekday());
              ps.setObject(2, shape.startTime());
              ps.setObject(3, shape.endTime());
              ps.setInt(4, shape.capacity());
              ps.setInt(5, shape.cutoffMinutes());
              ps.setBoolean(6, shape.active());
              ps.setObject(7, updatedBy);
              ps.setObject(8, tenantId);
              ps.setObject(9, id);
            },
            FulfilmentWindowRepository::mapRow,
            "update fulfilment window");
    if (rows.isEmpty()) {
      throw ApiException.notFound(
          "ORDER_SLOT_WINDOW_NOT_FOUND", "no such fulfilment window in this tenant");
    }
    return rows.get(0);
  }

  /** One window by id, or empty when it is not this tenant's. */
  public Optional<Windows.WindowRecord> find(UUID tenantId, UUID id) {
    return query(
            "SELECT " + COLUMNS + " FROM fulfilment_windows WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            FulfilmentWindowRepository::mapRow,
            "find fulfilment window")
        .stream()
        .findFirst();
  }

  /** Every window of one store, of every type and weekday — what the admin screen lists. */
  public List<Windows.WindowRecord> listByStore(UUID tenantId, UUID storeId) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM fulfilment_windows WHERE tenant_id=? AND store_id=?"
            + " ORDER BY fulfilment_type, weekday, start_time",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        FulfilmentWindowRepository::mapRow,
        "list fulfilment windows by store");
  }

  /** One store's windows of one fulfilment type — what the storefront reads occurrences from. */
  public List<Windows.WindowRecord> listByStoreAndType(
      UUID tenantId, UUID storeId, String fulfilmentType) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM fulfilment_windows WHERE tenant_id=? AND store_id=? AND fulfilment_type=?"
            + " ORDER BY weekday, start_time",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, fulfilmentType);
        },
        FulfilmentWindowRepository::mapRow,
        "list fulfilment windows by store and type");
  }

  /** Whether the store offers at least one ACTIVE window of this type — whether a slot is owed. */
  public boolean hasActiveWindow(UUID tenantId, UUID storeId, String fulfilmentType) {
    return !query(
            "SELECT 1 FROM fulfilment_windows"
                + " WHERE tenant_id=? AND store_id=? AND fulfilment_type=? AND active=true LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setString(3, fulfilmentType);
            },
            rs -> Boolean.TRUE,
            "has active fulfilment window")
        .isEmpty();
  }

  /**
   * How many places are already taken at each occurrence, from now on, of a store's windows of one
   * type — read without locking, for display only ("N left" / "Full"); the decision to refuse a
   * checkout is {@link #claimTx}'s alone, re-counted under the window's own lock.
   */
  public List<TakenCount> takenCounts(
      UUID tenantId, UUID storeId, String fulfilmentType, Instant from) {
    return query(
        "SELECT o.slot_window_id AS window_id, o.slot_starts_at AS starts_at,"
            + " COUNT(DISTINCT COALESCE(o.group_id, o.id)) AS taken"
            + " FROM orders o"
            + " JOIN fulfilment_windows w ON w.tenant_id = o.tenant_id AND w.id = o.slot_window_id"
            + " WHERE o.tenant_id = ? AND w.store_id = ? AND w.fulfilment_type = ?"
            + " AND o.slot_starts_at >= ? AND o.status NOT IN ('CANCELLED', 'VOIDED')"
            + " GROUP BY o.slot_window_id, o.slot_starts_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, fulfilmentType);
          ps.setObject(4, from.atOffset(ZoneOffset.UTC));
        },
        rs ->
            new TakenCount(
                rs.getObject("window_id", UUID.class),
                toInstant(rs.getObject("starts_at", OffsetDateTime.class)),
                rs.getLong("taken")),
        "taken counts");
  }

  /**
   * Takes a place in {@code windowId}'s {@code startsAt} occurrence for the order (or the whole
   * checkout, for a split) about to be inserted on {@code c}'s own transaction: the window row is
   * locked first, so two checkouts racing for the last place cannot both win, then re-validated
   * against the lock (a concurrent edit is caught, not just the first, unlocked read), then the
   * places already taken are counted and compared to capacity.
   *
   * <p>Called at most once per checkout (order or group) — never once per part of a split — so the
   * count this reads never includes the checkout's own rows, which have not been inserted yet.
   *
   * @throws ApiException 400 {@code ORDER_SLOT_UNKNOWN} when the window is not this tenant's, not
   *     this store's or type's, not active, or {@code startsAt} is not one of its occurrences in
   *     the next seven days; 409 {@code ORDER_SLOT_CLOSED} past its cut-off; 409 {@code
   *     ORDER_SLOT_FULL} at capacity
   */
  static void claimTx(
      Connection c,
      UUID tenantId,
      UUID storeId,
      String fulfilmentType,
      UUID windowId,
      Instant startsAt,
      ZoneId zone)
      throws SQLException {
    Windows.Window w;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT weekday, start_time, end_time, capacity, cutoff_minutes, active"
                + " FROM fulfilment_windows"
                + " WHERE tenant_id=? AND id=? AND store_id=? AND fulfilment_type=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, windowId);
      ps.setObject(3, storeId);
      ps.setString(4, fulfilmentType);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.badRequest(
              "ORDER_SLOT_UNKNOWN", "no such window at this store and fulfilment type");
        }
        w =
            new Windows.Window(
                windowId,
                storeId,
                fulfilmentType,
                rs.getInt("weekday"),
                rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class),
                rs.getInt("capacity"),
                rs.getInt("cutoff_minutes"),
                rs.getBoolean("active"));
      }
    }
    Instant now = Instant.now();
    var occurrence = Windows.occurrenceForInstant(w, zone, now, startsAt);
    if (occurrence.isEmpty()) {
      throw ApiException.badRequest(
          "ORDER_SLOT_UNKNOWN", "not an occurrence of that window in the next seven days");
    }
    if (Windows.pastCutoff(w, occurrence.get().startsAt(), now)) {
      throw ApiException.conflict(
          "ORDER_SLOT_CLOSED", "this window has closed for new orders — its cut-off has passed");
    }
    long taken = 0L;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COUNT(DISTINCT COALESCE(group_id, id)) FROM orders"
                + " WHERE tenant_id=? AND slot_window_id=? AND slot_starts_at=?"
                + " AND status NOT IN ('CANCELLED', 'VOIDED')")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, windowId);
      ps.setObject(3, startsAt.atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        // COUNT always answers one row; none would mean nothing holds the occurrence.
        if (rs.next()) {
          taken = rs.getLong(1);
        }
      }
    }
    if (taken >= w.capacity()) {
      throw ApiException.conflict("ORDER_SLOT_FULL", "this window is fully booked");
    }
  }

  private static Windows.WindowRecord mapRow(ResultSet rs) throws SQLException {
    return new Windows.WindowRecord(
        new Windows.Window(
            rs.getObject("id", UUID.class),
            rs.getObject("store_id", UUID.class),
            rs.getString("fulfilment_type"),
            rs.getInt("weekday"),
            rs.getObject("start_time", LocalTime.class),
            rs.getObject("end_time", LocalTime.class),
            rs.getInt("capacity"),
            rs.getInt("cutoff_minutes"),
            rs.getBoolean("active")),
        rs.getString("time_zone"),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)),
        rs.getObject("updated_by", UUID.class));
  }

  private static Instant toInstant(OffsetDateTime odt) {
    return odt == null ? null : odt.toInstant();
  }
}
