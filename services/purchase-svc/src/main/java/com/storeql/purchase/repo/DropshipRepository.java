package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.DropshipArrangement;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dropship arrangements — which supplier fulfils a variant per order, at what cost — and the one
 * status move a dropship purchase order makes that no goods receipt does. Every statement filters
 * by {@code tenant_id} first.
 */
@ApplicationScoped
public class DropshipRepository extends BaseOutboxRepository {

  private static final String COLUMNS =
      "SELECT id, tenant_id, variant_id, supplier_id, unit_cost, vat_code, active, created_by,"
          + " created_at, ended_at FROM dropship_arrangements WHERE tenant_id = ?";

  /**
   * Makes an arrangement and tells inventory-svc, on one transaction.
   *
   * @throws ApiException 409 {@code PURCHASE_DROPSHIP_ARRANGEMENT_EXISTS} when the variant already
   *     has a live one
   */
  public DropshipArrangement create(DropshipArrangement a, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO dropship_arrangements (id, tenant_id, variant_id, supplier_id,"
                      + " unit_cost, vat_code, active, created_by, created_at)"
                      + " VALUES (?,?,?,?,?,?,TRUE,?,?)")) {
            ps.setObject(1, a.id());
            ps.setObject(2, a.tenantId());
            ps.setObject(3, a.variantId());
            ps.setObject(4, a.supplierId());
            ps.setBigDecimal(5, a.unitCost());
            ps.setString(6, a.vatCode());
            ps.setObject(7, a.createdBy());
            ps.setObject(8, a.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
              throw new ApiException(
                  409,
                  "PURCHASE_DROPSHIP_ARRANGEMENT_EXISTS",
                  "variant " + a.variantId() + " already has a live dropship arrangement",
                  List.of(),
                  sqle);
            }
            throw sqle;
          }
          insertOutbox(c, event);
          return a;
        },
        "create dropship arrangement");
  }

  /**
   * Ends a live arrangement and tells inventory-svc the variant is stocked again.
   *
   * @throws ApiException 409 {@code PURCHASE_DROPSHIP_ARRANGEMENT_ENDED} when it already was
   */
  public void end(UUID tenantId, UUID id, OutboxRow event) {
    inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE dropship_arrangements SET active = FALSE, ended_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND active")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            throw ApiException.conflict(
                "PURCHASE_DROPSHIP_ARRANGEMENT_ENDED", "arrangement " + id + " was already ended");
          }
          insertOutbox(c, event);
          return null;
        },
        "end dropship arrangement");
  }

  public List<DropshipArrangement> findAll(UUID tenantId, int limit) {
    return query(
        COLUMNS + " ORDER BY active DESC, created_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        DropshipRepository::map,
        "list dropship arrangements");
  }

  public Optional<DropshipArrangement> find(UUID tenantId, UUID id) {
    var rows =
        query(
            COLUMNS + " AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            DropshipRepository::map,
            "find dropship arrangement");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** The live arrangements among these variants: which of a confirmed order's lines drop-ship. */
  public List<DropshipArrangement> activeFor(UUID tenantId, Collection<UUID> variantIds) {
    if (variantIds.isEmpty()) return List.of();
    return query(
        COLUMNS + " AND active AND variant_id = ANY (?) ORDER BY supplier_id, variant_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", variantIds.toArray()));
        },
        DropshipRepository::map,
        "find live dropship arrangements");
  }

  /**
   * Marks a submitted dropship order delivered to the customer — the move a goods receipt makes for
   * stock that arrives here — and posts the cost of goods the business never held, on one
   * transaction.
   *
   * @throws ApiException 409 {@code PURCHASE_PO_NOT_DELIVERABLE} unless the order is a SUBMITTED
   *     dropship order
   */
  public void markDelivered(UUID tenantId, UUID poId, List<NominalLedgerEntry> posting) {
    inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE purchase_orders SET status = ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND status = ? AND source = ?")) {
            ps.setString(1, Domain.PO_RECEIVED);
            ps.setObject(2, tenantId);
            ps.setObject(3, poId);
            ps.setString(4, Domain.PO_SUBMITTED);
            ps.setString(5, Domain.PO_SOURCE_DROPSHIP);
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            throw ApiException.conflict(
                "PURCHASE_PO_NOT_DELIVERABLE",
                "only a SUBMITTED dropship order is delivered to the customer; anything else is"
                    + " received");
          }
          LedgerWriter.insert(c, posting);
          return null;
        },
        "mark dropship order delivered");
  }

  private static DropshipArrangement map(ResultSet rs) throws SQLException {
    OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
    return new DropshipArrangement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getBigDecimal("unit_cost"),
        rs.getString("vat_code"),
        rs.getBoolean("active"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        ended == null ? null : ended.toInstant());
  }

  static Instant now() {
    return Instant.now();
  }
}
