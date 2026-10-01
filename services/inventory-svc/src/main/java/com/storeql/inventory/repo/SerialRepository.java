package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.SerialMovement;
import com.storeql.inventory.domain.Domain.SerialNumber;
import com.storeql.inventory.domain.SerialNumbers;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Serial number persistence. Extracted from InventoryRepository (self-contained section). */
@ApplicationScoped
public class SerialRepository extends BaseOutboxRepository {

  /**
   * Bulk-inserts serial numbers, their first genealogy movement and the outbox row, atomically.
   * Each insert is {@code ON CONFLICT DO NOTHING RETURNING id}; a missing row is a clash and is
   * never dropped. A supplied number that clashes refuses the whole call (409
   * SERIAL_ALREADY_REGISTERED naming every clash, nothing written); a generated one is made again
   * up to {@link SerialNumbers#MAX_GENERATION_ATTEMPTS} times, then 409
   * SERIAL_GENERATION_EXHAUSTED.
   *
   * @param serials the serials to store, in the order the caller wants them back
   * @param fresh makes another number when a generated one clashes; {@code null} when the numbers
   *     were supplied
   * @param event the outbox row, written with the rest
   * @return exactly the rows stored
   */
  public List<SerialNumber> registerSerials(
      List<SerialNumber> serials, Supplier<String> fresh, OutboxRow event) {
    return inTx(
        c -> {
          SerialNumber[] stored = serials.toArray(new SerialNumber[0]);
          // Insert in number order so two overlapping calls take their locks in the same order.
          Integer[] order = new Integer[stored.length];
          for (int i = 0; i < order.length; i++) order[i] = i;
          if (fresh == null) {
            Arrays.sort(order, Comparator.comparing(i -> stored[i].serialNo()));
          }
          List<String> clashes = new ArrayList<>();
          for (int idx : order) {
            int attempts = 0;
            while (!insertOne(c, stored[idx])) {
              if (fresh == null) {
                clashes.add(stored[idx].serialNo());
                break;
              }
              if (++attempts >= SerialNumbers.MAX_GENERATION_ATTEMPTS) {
                throw new ApiException(
                    409,
                    "SERIAL_GENERATION_EXHAUSTED",
                    "could not generate a free serial number; nothing was registered",
                    List.of(),
                    null);
              }
              SerialNumber s = stored[idx];
              stored[idx] =
                  new SerialNumber(
                      s.id(),
                      s.tenantId(),
                      s.storeId(),
                      s.variantId(),
                      s.batchId(),
                      fresh.get(),
                      s.status(),
                      s.receivedAt(),
                      s.soldAt());
            }
          }
          if (!clashes.isEmpty()) {
            throw new ApiException(
                409,
                "SERIAL_ALREADY_REGISTERED",
                "serial numbers already registered in this business; nothing was registered",
                clashes,
                null);
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO serial_movements"
                      + " (id, tenant_id, serial_id, from_status, to_status, ref_type)"
                      + " VALUES (?,?,?,NULL,?,?)")) {
            for (SerialNumber s : stored) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, s.tenantId());
              ps.setObject(3, s.id());
              ps.setString(4, SerialNumber.IN_STOCK);
              ps.setString(5, "RECEIVE");
              ps.addBatch();
            }
            ps.executeBatch();
          }
          insertOutbox(c, event);
          return List.of(stored);
        },
        "register serials");
  }

  /** Inserts one serial; false when its number is already taken in the business. */
  private static boolean insertOne(Connection c, SerialNumber s) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO serial_numbers"
                + " (id, tenant_id, store_id, variant_id, batch_id, serial_no, status, received_at)"
                + " VALUES (?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (tenant_id, serial_no) DO NOTHING RETURNING id")) {
      ps.setObject(1, s.id());
      ps.setObject(2, s.tenantId());
      ps.setObject(3, s.storeId());
      ps.setObject(4, s.variantId());
      ps.setObject(5, s.batchId());
      ps.setString(6, s.serialNo());
      ps.setString(7, s.status());
      ps.setObject(8, s.receivedAt().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Lists the tenant's serials.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<SerialNumber> listSerials(
      UUID tenantId, UUID storeId, UUID variantId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY received_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        SerialRepository::mapSerial,
        "list serials");
  }

  /**
   * Looks a serial up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param serialId the serial id
   * @return the serial, or empty when it does not exist in this tenant
   */
  public Optional<SerialNumber> findSerial(UUID tenantId, UUID serialId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, serialId);
            },
            SerialRepository::mapSerial,
            "get serial");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Looks a serial by no up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param serialNo the serial no
   * @return the serial by no, or empty when it does not exist in this tenant
   */
  public Optional<SerialNumber> findSerialByNo(UUID tenantId, String serialNo) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ? AND serial_no = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, serialNo);
            },
            SerialRepository::mapSerial,
            "lookup serial by no");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** Transition serial to a new status; records genealogy movement + outbox, atomically. */
  public Optional<SerialNumber> updateSerialStatus(
      UUID tenantId, UUID serialId, String newStatus, OutboxRow event) {
    return inTx(
        c -> {
          String oldStatus;
          try (var ps =
              c.prepareStatement(
                  "SELECT status FROM serial_numbers"
                      + " WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, serialId);
            try (var rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<SerialNumber>empty();
              oldStatus = rs.getString("status");
            }
          }
          SerialNumber updated;
          try (var ps =
              c.prepareStatement(
                  "UPDATE serial_numbers"
                      + " SET status = ?,"
                      + " sold_at = CASE WHEN ? = 'SOLD' THEN now() ELSE sold_at END"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, store_id, variant_id, batch_id,"
                      + " serial_no, status, received_at, sold_at")) {
            ps.setString(1, newStatus);
            ps.setString(2, newStatus);
            ps.setObject(3, tenantId);
            ps.setObject(4, serialId);
            try (var rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<SerialNumber>empty();
              updated = mapSerial(rs);
            }
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO serial_movements"
                      + " (id, tenant_id, serial_id, from_status, to_status)"
                      + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, serialId);
            ps.setString(4, oldStatus);
            ps.setString(5, newStatus);
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return Optional.of(updated);
        },
        "update serial status");
  }

  /**
   * Lists the tenant's serial histories.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param serialId the serial id
   * @return the matching rows
   */
  public List<SerialMovement> listSerialHistory(UUID tenantId, UUID serialId) {
    return query(
        "SELECT id, tenant_id, serial_id, from_status, to_status, ref_type, ref_id, created_at"
            + " FROM serial_movements WHERE tenant_id = ? AND serial_id = ?"
            + " ORDER BY created_at ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, serialId);
        },
        SerialRepository::mapSerialMovement,
        "list serial history");
  }

  private static SerialNumber mapSerial(ResultSet rs) throws SQLException {
    OffsetDateTime soldOdt = rs.getObject("sold_at", OffsetDateTime.class);
    return new SerialNumber(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("serial_no"),
        rs.getString("status"),
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        soldOdt == null ? null : soldOdt.toInstant());
  }

  private static SerialMovement mapSerialMovement(ResultSet rs) throws SQLException {
    return new SerialMovement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("serial_id", UUID.class),
        rs.getString("from_status"),
        rs.getString("to_status"),
        rs.getString("ref_type"),
        rs.getObject("ref_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
