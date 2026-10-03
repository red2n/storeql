package com.storeql.order.repo;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.NoSaleResponse;
import com.storeql.order.dto.Dtos.ParkedSaleItemResponse;
import com.storeql.order.dto.Dtos.ParkedSaleResponse;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence for parked sales and no-sale log. */
@ApplicationScoped
public class ParkedSaleRepository extends BaseOutboxRepository {

  /**
   * Stores a parked sale with its lines — atomically.
   *
   * @param tenantId owning tenant
   * @param saleId the parked sale's id, already a UUIDv7
   * @param cashierId the cashier parking it
   * @param storeId the store the sale was rung in
   * @param customerId the customer, or {@code null} for an anonymous sale
   * @param customerName a name to show on the parked list, or {@code null}
   * @param subtotal the basket total after line discounts
   * @param discountTotal the discount taken off across all lines
   * @param notes free-text note, or {@code null}
   * @param items the lines being parked
   * @return the parked sale as stored
   */
  public ParkedSaleResponse park(
      UUID tenantId,
      UUID saleId,
      UUID cashierId,
      UUID storeId,
      String customerId,
      String customerName,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      String notes,
      List<ParkedSaleItemResponse> items) {
    return inTx(
        c -> {
          insertParkedSale(
              c,
              tenantId,
              saleId,
              cashierId,
              storeId,
              customerId,
              customerName,
              subtotal,
              discountAmount,
              notes);
          for (var item : items) {
            insertParkedItem(c, tenantId, saleId, item);
          }
          return buildResponse(tenantId, saleId, c, true);
        },
        "park sale");
  }

  /**
   * Reads one open parked sale with its lines.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param saleId the parked sale to read
   * @return the parked sale
   * @throws com.storeql.web.ApiException a 404 when no such open parked sale exists in this tenant
   */
  public ParkedSaleResponse findById(UUID tenantId, UUID saleId) {
    return inTx(c -> buildResponse(tenantId, saleId, c, true), "find parked sale");
  }

  /**
   * The still-open parked sales, newest first.
   *
   * <p>Resumed and discarded sales are excluded: this backs the till's "pick one back up" list.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeIds the stores to read, or {@code null} for the whole tenant
   * @return the open parked sales with their lines
   */
  public List<ParkedSaleResponse> listOpen(UUID tenantId, java.util.Set<UUID> storeIds) {
    return inTx(
        c -> {
          String sql =
              "SELECT "
                  + HEADER_COLUMNS
                  + " FROM parked_sales WHERE tenant_id=?"
                  + (storeIds == null ? "" : " AND store_id = ANY (?)")
                  + " AND resumed_at IS NULL AND discarded_at IS NULL ORDER BY parked_at DESC";
          List<ParkedSaleResponse> results = new ArrayList<>();
          List<UUID> ids = new ArrayList<>();
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            if (storeIds != null) {
              ps.setArray(2, c.createArrayOf("uuid", storeIds.toArray()));
            }
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                ids.add(rs.getObject("id", UUID.class));
                results.add(toResponse(rs, List.of()));
              }
            }
          }
          if (results.isEmpty()) {
            return results;
          }
          // Every sale's lines in one read, not one read per sale.
          Map<UUID, List<ParkedSaleItemResponse>> items = fetchItems(c, tenantId, ids);
          List<ParkedSaleResponse> withItems = new ArrayList<>(results.size());
          for (int i = 0; i < results.size(); i++) {
            withItems.add(withItems(results.get(i), items.getOrDefault(ids.get(i), List.of())));
          }
          return withItems;
        },
        "list parked sales");
  }

  /**
   * Picks a parked sale back up: it leaves the open list and records who did it and when. Only an
   * open sale can be picked up, and only once.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param saleId the parked sale
   * @param userId who is resuming it
   * @return the sale as resumed
   * @throws ApiException 404 {@code PARKED_SALE_NOT_FOUND} when there is no such sale (or it was
   *     discarded); 409 {@code PARKED_SALE_NOT_OPEN} when it was resumed already
   */
  public ParkedSaleResponse resume(UUID tenantId, UUID saleId, UUID userId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE parked_sales SET resumed_at = ?, resumed_by = ?"
                      + " WHERE tenant_id=? AND id=? AND resumed_at IS NULL"
                      + " AND discarded_at IS NULL")) {
            ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(2, userId);
            ps.setObject(3, tenantId);
            ps.setObject(4, saleId);
            if (ps.executeUpdate() == 0) {
              // Absent is a 404 (thrown by the read); present but finished is a lost race.
              buildResponse(tenantId, saleId, c, false);
              throw ApiException.conflict(
                  "PARKED_SALE_NOT_OPEN", "this parked sale was already resumed or discarded");
            }
          }
          return buildResponse(tenantId, saleId, c, false);
        },
        "resume parked sale");
  }

  /**
   * Throws a parked sale away: it leaves the open list and records who did it and when. A sale that
   * is already finished with is left as it is.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param saleId the parked sale to discard
   * @param userId who is discarding it
   */
  public void discard(UUID tenantId, UUID saleId, UUID userId) {
    exec(
        "UPDATE parked_sales SET discarded_at = now(), discarded_by = ?"
            + " WHERE tenant_id=? AND id=? AND resumed_at IS NULL AND discarded_at IS NULL",
        ps -> {
          ps.setObject(1, userId);
          ps.setObject(2, tenantId);
          ps.setObject(3, saleId);
        },
        "discard parked sale");
  }

  /**
   * Appends a no-sale / open-drawer audit entry.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose drawer was opened, or {@code null}
   * @param cashierId the cashier who opened it
   * @param tillSessionId the till session it happened in, or {@code null}
   * @param reason the stated reason
   * @param orderId an associated order, or {@code null} — a no-sale normally has none
   * @return the logged entry
   */
  public NoSaleResponse logNoSale(
      UUID tenantId,
      UUID storeId,
      UUID cashierId,
      UUID tillSessionId,
      String reason,
      UUID authorisedBy) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    exec(
        "INSERT INTO pos_no_sale_log (id, tenant_id, store_id, cashier_id, till_session_id,"
            + " reason, authorised_by, logged_at) VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, storeId);
          ps.setObject(4, cashierId);
          ps.setObject(5, tillSessionId);
          ps.setString(6, reason);
          ps.setObject(7, authorisedBy);
          ps.setObject(8, now.atOffset(ZoneOffset.UTC));
        },
        "log no-sale");
    return new NoSaleResponse(
        id.toString(), storeId == null ? null : storeId.toString(), reason, now.toString());
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    return dbError(what, e);
  }

  // ─────────────────────────────────────────── private helpers

  private void insertParkedSale(
      Connection c,
      UUID tenantId,
      UUID saleId,
      UUID cashierId,
      UUID storeId,
      String customerId,
      String customerName,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      String notes)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO parked_sales (id, tenant_id, store_id, cashier_id, customer_id,"
                + " customer_name, subtotal, discount_amount, notes, parked_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, saleId);
      ps.setObject(2, tenantId);
      ps.setObject(3, storeId);
      ps.setObject(4, cashierId);
      ps.setObject(5, customerId == null ? null : Ids.parse(customerId));
      ps.setString(6, customerName);
      ps.setBigDecimal(7, subtotal);
      ps.setBigDecimal(8, discountAmount);
      ps.setString(9, notes);
      ps.setObject(10, Instant.now().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertParkedItem(
      Connection c, UUID tenantId, UUID saleId, ParkedSaleItemResponse item) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO parked_sale_items (id, tenant_id, sale_id, variant_id, qty,"
                + " unit_price, line_total, discount_amount, notes, markdown_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, saleId);
      ps.setObject(4, Ids.parse(item.variantId()));
      ps.setBigDecimal(5, item.qty());
      ps.setBigDecimal(6, item.unitPrice());
      ps.setBigDecimal(7, item.lineTotal());
      ps.setBigDecimal(8, item.discountAmount() == null ? BigDecimal.ZERO : item.discountAmount());
      ps.setString(9, item.notes());
      ps.setObject(
          10, item.markdownId() == null ? null : Parsing.uuid(item.markdownId(), "markdownId"));
      ps.executeUpdate();
    }
  }

  private static final String HEADER_COLUMNS =
      "id, tenant_id, store_id, cashier_id, customer_id, customer_name, subtotal,"
          + " discount_amount, notes, parked_at, expires_at, resumed_at, resumed_by, order_id";

  private ParkedSaleResponse buildResponse(
      UUID tenantId, UUID saleId, Connection c, boolean openOnly) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + HEADER_COLUMNS
                + " FROM parked_sales WHERE tenant_id=? AND id=? AND discarded_at IS NULL"
                + (openOnly ? " AND resumed_at IS NULL" : ""))) {
      ps.setObject(1, tenantId);
      ps.setObject(2, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next())
          throw ApiException.notFound("PARKED_SALE_NOT_FOUND", "Parked sale not found");
        return toResponse(
            rs, fetchItems(c, tenantId, List.of(saleId)).getOrDefault(saleId, List.of()));
      }
    }
  }

  private static ParkedSaleResponse toResponse(ResultSet rs, List<ParkedSaleItemResponse> items)
      throws SQLException {
    UUID saleId = rs.getObject("id", UUID.class);
    UUID storeId = rs.getObject("store_id", UUID.class);
    UUID custId = rs.getObject("customer_id", UUID.class);
    UUID cashierId = rs.getObject("cashier_id", UUID.class);
    UUID resumedBy = rs.getObject("resumed_by", UUID.class);
    String parkedAt = rs.getObject("parked_at", OffsetDateTime.class).toInstant().toString();
    OffsetDateTime expiresOdt = rs.getObject("expires_at", OffsetDateTime.class);
    String expiresAt = expiresOdt == null ? null : expiresOdt.toInstant().toString();
    OffsetDateTime resumedOdt = rs.getObject("resumed_at", OffsetDateTime.class);
    return new ParkedSaleResponse(
        saleId.toString(),
        storeId == null ? null : storeId.toString(),
        custId == null ? null : custId.toString(),
        rs.getString("customer_name"),
        rs.getBigDecimal("subtotal"),
        rs.getBigDecimal("discount_amount"),
        items,
        rs.getString("notes"),
        parkedAt,
        expiresAt,
        cashierId == null ? null : cashierId.toString(),
        resumedOdt == null ? null : resumedOdt.toInstant().toString(),
        resumedBy == null ? null : resumedBy.toString());
  }

  private static ParkedSaleResponse withItems(
      ParkedSaleResponse r, List<ParkedSaleItemResponse> items) {
    return new ParkedSaleResponse(
        r.id(),
        r.storeId(),
        r.customerId(),
        r.customerName(),
        r.subtotal(),
        r.discountAmount(),
        items,
        r.notes(),
        r.parkedAt(),
        r.expiresAt(),
        r.parkedBy(),
        r.resumedAt(),
        r.resumedBy());
  }

  private Map<UUID, List<ParkedSaleItemResponse>> fetchItems(
      Connection c, UUID tenantId, List<UUID> saleIds) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT sale_id, variant_id, qty, unit_price, line_total, discount_amount, notes,"
                + " markdown_id FROM parked_sale_items WHERE tenant_id=? AND sale_id = ANY (?)")) {
      ps.setObject(1, tenantId);
      ps.setArray(2, c.createArrayOf("uuid", saleIds.toArray()));
      Map<UUID, List<ParkedSaleItemResponse>> bySale = new HashMap<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          UUID markdown = rs.getObject("markdown_id", UUID.class);
          bySale
              .computeIfAbsent(rs.getObject("sale_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new ParkedSaleItemResponse(
                      rs.getObject("variant_id", UUID.class).toString(),
                      rs.getBigDecimal("qty"),
                      rs.getBigDecimal("unit_price"),
                      rs.getBigDecimal("discount_amount"),
                      rs.getBigDecimal("line_total"),
                      rs.getString("notes"),
                      markdown == null ? null : markdown.toString()));
        }
      }
      return bySale;
    }
  }
}
