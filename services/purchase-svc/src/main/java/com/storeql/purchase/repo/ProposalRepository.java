package com.storeql.purchase.repo;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.ProposalRun;
import com.storeql.purchase.domain.Domain.SkippedItem;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What a proposal run reads from this service's own books, and the record it leaves (06.x). */
@ApplicationScoped
public class ProposalRepository extends BaseJdbcRepository {

  /** Orders committed to a supplier and not yet fully received: what is on order. */
  static final String OPEN_STATUSES =
      "('"
          + Domain.PO_SUBMITTED
          + "','"
          + Domain.PO_PENDING_APPROVAL
          + "','"
          + Domain.PO_PARTIALLY_RECEIVED
          + "')";

  /** The supplier an item was last bought from, with the price and VAT code that was paid. */
  public record SupplierChoice(UUID supplierId, BigDecimal unitPrice, String vatCode) {}

  private record VariantQty(UUID variantId, BigDecimal qty) {}

  private record LastLine(UUID variantId, UUID supplierId, BigDecimal unitPrice, String vatCode) {}

  /**
   * Quantity on order per variant at a store: ordered on open orders, less what has been received
   * against them, never below zero.
   *
   * @param tenantId owning tenant; the first condition of both queries
   * @param storeId the store
   * @return variant → quantity still to arrive
   */
  public Map<UUID, BigDecimal> onOrderByVariant(UUID tenantId, UUID storeId) {
    List<VariantQty> ordered =
        query(
            "SELECT l.variant_id, SUM(l.qty) AS qty FROM purchase_order_lines l"
                + " JOIN purchase_orders p ON p.id = l.po_id AND p.tenant_id = l.tenant_id"
                + " WHERE p.tenant_id = ? AND p.store_id = ? AND p.status IN "
                + OPEN_STATUSES
                + " GROUP BY l.variant_id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            ProposalRepository::mapVariantQty,
            "on order");
    List<VariantQty> received =
        query(
            "SELECT gl.variant_id, SUM(gl.qty_received) AS qty FROM goods_receipt_lines gl"
                + " JOIN goods_receipts g ON g.id = gl.gr_id AND g.tenant_id = gl.tenant_id"
                + " JOIN purchase_orders p ON p.id = g.po_id AND p.tenant_id = g.tenant_id"
                + " WHERE p.tenant_id = ? AND p.store_id = ? AND p.status IN "
                + OPEN_STATUSES
                + " GROUP BY gl.variant_id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            ProposalRepository::mapVariantQty,
            "received against open orders");
    Map<UUID, BigDecimal> out = new HashMap<>();
    for (VariantQty o : ordered) {
      out.put(o.variantId(), o.qty());
    }
    for (VariantQty r : received) {
      out.computeIfPresent(r.variantId(), (k, q) -> q.subtract(r.qty()).max(BigDecimal.ZERO));
    }
    return out;
  }

  /**
   * The supplier each variant was last bought from — the most recent order line that was not
   * cancelled and not itself a proposal still in draft — with the price and VAT code paid.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantIds the variants to look up
   * @return variant → the last supplier; absent when the business has never bought the item
   */
  public Map<UUID, SupplierChoice> lastSupplierByVariant(UUID tenantId, List<UUID> variantIds) {
    if (variantIds.isEmpty()) {
      return Map.of();
    }
    List<LastLine> rows =
        query(
            "SELECT DISTINCT ON (l.variant_id) l.variant_id, p.supplier_id, l.unit_price, l.vat_code"
                + " FROM purchase_order_lines l"
                + " JOIN purchase_orders p ON p.id = l.po_id AND p.tenant_id = l.tenant_id"
                + " WHERE p.tenant_id = ? AND l.variant_id = ANY(?)"
                + " AND p.status <> '"
                + Domain.PO_CANCELLED
                + "' AND NOT (p.status = '"
                + Domain.PO_DRAFT
                + "' AND p.source = '"
                + Domain.PO_SOURCE_PROPOSAL
                + "')"
                + " ORDER BY l.variant_id, l.created_at DESC",
            byTenantAndVariants(tenantId, variantIds),
            rs ->
                new LastLine(
                    rs.getObject("variant_id", UUID.class),
                    rs.getObject("supplier_id", UUID.class),
                    rs.getBigDecimal("unit_price"),
                    rs.getString("vat_code")),
            "last supplier by variant");
    Map<UUID, SupplierChoice> out = new HashMap<>();
    for (LastLine r : rows) {
      out.put(r.variantId(), new SupplierChoice(r.supplierId(), r.unitPrice(), r.vatCode()));
    }
    return out;
  }

  /**
   * Suppliers whose item codes name a variant (learned from their e-invoices), for items the
   * business has never ordered on the platform.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantIds the variants to look up
   * @return variant → a supplier that carries it
   */
  public Map<UUID, UUID> itemCodeSupplierByVariant(UUID tenantId, List<UUID> variantIds) {
    if (variantIds.isEmpty()) {
      return Map.of();
    }
    List<LastLine> rows =
        query(
            "SELECT DISTINCT ON (variant_id) variant_id, supplier_id FROM supplier_item_codes"
                + " WHERE tenant_id = ? AND variant_id = ANY(?) ORDER BY variant_id, created_at DESC",
            byTenantAndVariants(tenantId, variantIds),
            rs ->
                new LastLine(
                    rs.getObject("variant_id", UUID.class),
                    rs.getObject("supplier_id", UUID.class),
                    null,
                    null),
            "item code supplier by variant");
    Map<UUID, UUID> out = new HashMap<>();
    for (LastLine r : rows) {
      out.put(r.variantId(), r.supplierId());
    }
    return out;
  }

  /**
   * How many proposed orders for the store are still drafts.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @return the count
   */
  public int openProposalDrafts(UUID tenantId, UUID storeId) {
    List<Integer> n =
        query(
            "SELECT COUNT(*) AS n FROM purchase_orders WHERE tenant_id = ? AND store_id = ?"
                + " AND status = '"
                + Domain.PO_DRAFT
                + "' AND source = '"
                + Domain.PO_SOURCE_PROPOSAL
                + "'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs -> rs.getInt("n"),
            "open proposal drafts");
    return n.isEmpty() ? 0 : n.get(0);
  }

  /** Records what a run did. */
  public void insertRun(ProposalRun run) {
    exec(
        "INSERT INTO order_proposal_runs (id, tenant_id, store_id, ran_by, ran_at, cover_days,"
            + " considered, orders_raised, lines_raised, order_ids, skipped)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, run.id());
          ps.setObject(2, run.tenantId());
          ps.setObject(3, run.storeId());
          ps.setObject(4, run.ranBy());
          ps.setObject(5, OffsetDateTime.ofInstant(run.ranAt(), ZoneOffset.UTC));
          ps.setInt(6, run.coverDays());
          ps.setInt(7, run.considered());
          ps.setInt(8, run.ordersRaised());
          ps.setInt(9, run.linesRaised());
          ps.setArray(
              10, ps.getConnection().createArrayOf("uuid", run.orderIds().toArray(new UUID[0])));
          ps.setObject(11, skippedJson(run.skipped()), Types.OTHER);
        },
        "insert proposal run");
  }

  /**
   * The store's runs, latest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param limit at most this many
   * @return the runs
   */
  public List<ProposalRun> listRuns(UUID tenantId, UUID storeId, int limit) {
    return query(
        "SELECT id, tenant_id, store_id, ran_by, ran_at, cover_days, considered, orders_raised,"
            + " lines_raised, order_ids, skipped FROM order_proposal_runs"
            + " WHERE tenant_id = ? AND store_id = ? ORDER BY ran_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setInt(3, limit);
        },
        ProposalRepository::mapRun,
        "list proposal runs");
  }

  /** Binds the tenant first and the variants as one array, the way both supplier lookups ask. */
  private static Binder byTenantAndVariants(UUID tenantId, List<UUID> variantIds) {
    return ps -> {
      ps.setObject(1, tenantId);
      ps.setArray(2, ps.getConnection().createArrayOf("uuid", variantIds.toArray(new UUID[0])));
    };
  }

  private static VariantQty mapVariantQty(ResultSet rs) throws SQLException {
    return new VariantQty(rs.getObject("variant_id", UUID.class), rs.getBigDecimal("qty"));
  }

  private static ProposalRun mapRun(ResultSet rs) throws SQLException {
    Array ids = rs.getArray("order_ids");
    List<UUID> orderIds = new ArrayList<>();
    if (ids != null) {
      for (Object o : (Object[]) ids.getArray()) {
        orderIds.add((UUID) o);
      }
    }
    return new ProposalRun(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("ran_by", UUID.class),
        rs.getObject("ran_at", OffsetDateTime.class).toInstant(),
        rs.getInt("cover_days"),
        rs.getInt("considered"),
        rs.getInt("orders_raised"),
        rs.getInt("lines_raised"),
        orderIds,
        skippedFrom(rs.getString("skipped")));
  }

  private static String skippedJson(List<SkippedItem> skipped) {
    JsonArrayBuilder arr = Json.createArrayBuilder();
    for (SkippedItem s : skipped) {
      arr.add(
          Json.createObjectBuilder()
              .add("variantId", s.variantId().toString())
              .add("reason", s.reason()));
    }
    return arr.build().toString();
  }

  private static List<SkippedItem> skippedFrom(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    List<SkippedItem> out = new ArrayList<>();
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      for (JsonObject o : reader.readArray().getValuesAs(JsonObject.class)) {
        out.add(new SkippedItem(Ids.parse(o.getString("variantId")), o.getString("reason")));
      }
    }
    return out;
  }
}
