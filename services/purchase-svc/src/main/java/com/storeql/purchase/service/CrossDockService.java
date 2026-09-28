package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.InventoryClient;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.LineAllocation;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.repo.CrossDockRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-docking (intent/cross-docking.md): a buyer allocates a line of a warehouse's purchase order
 * to the shops the warehouse serves, by hand or filled from the shops' current needs, while the
 * order is a draft. The allocations travel to inventory-svc when the order is submitted; there the
 * delivery goes straight across the dock.
 */
@ApplicationScoped
public class CrossDockService {

  private static final String[] BUYING = {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER"};

  @Inject PurchaseService purchases;
  @Inject PurchaseRepository orders;
  @Inject CrossDockRepository repo;
  @Inject InventoryClient inventory;
  @Inject TenantProfiles profiles;

  /** One shop and how much of the line is for it. */
  public record Allocation(UUID storeId, BigDecimal qty) {}

  /**
   * Replaces a line's allocations. An empty list clears them.
   *
   * @throws ApiException 404 {@code PURCHASE_LINE_NOT_FOUND}; 409 {@code
   *     PURCHASE_ALLOCATION_ORDER_NOT_DRAFT}, {@code PURCHASE_ALLOCATION_STOCK_NOT_OWNED}; 400
   *     {@code PURCHASE_ALLOCATION_NOT_A_WAREHOUSE}, {@code PURCHASE_ALLOCATION_NOT_SERVED}, {@code
   *     PURCHASE_ALLOCATION_EXCEEDS_LINE}, {@code PURCHASE_ALLOCATION_QTY_INVALID}; 503 {@code
   *     PURCHASE_NETWORK_UNAVAILABLE}
   */
  public List<LineAllocation> allocate(
      TenantContext ctx, UUID poId, UUID lineId, List<Allocation> requested) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = purchases.getPurchaseOrder(ctx, poId);
    PurchaseOrderLine line = lineOf(tenantId, po, lineId);
    requireAllocatable(tenantId, po);
    Map<UUID, BigDecimal> byShop = new LinkedHashMap<>();
    for (Allocation a : requested) {
      if (a.qty() == null || a.qty().signum() <= 0) {
        throw ApiException.badRequest(
            "PURCHASE_ALLOCATION_QTY_INVALID", "an allocated quantity must be greater than zero");
      }
      byShop.merge(a.storeId(), a.qty(), BigDecimal::add);
    }
    if (!byShop.isEmpty()) {
      InventoryClient.Sourcing sourcing =
          inventory
              .sourcing(tenantId, po.storeId())
              .orElseThrow(
                  () ->
                      new ApiException(
                          503,
                          "PURCHASE_NETWORK_UNAVAILABLE",
                          "inventory-svc could not say which shops the warehouse serves; nothing"
                              + " was allocated, try again",
                          List.of()));
      for (UUID shop : byShop.keySet()) {
        if (!sourcing.shops().contains(shop)) {
          throw ApiException.badRequest(
              "PURCHASE_ALLOCATION_NOT_SERVED",
              "store " + shop + " is not a shop this warehouse serves");
        }
      }
    }
    BigDecimal total = byShop.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(line.qty()) > 0) {
      throw ApiException.badRequest(
          "PURCHASE_ALLOCATION_EXCEEDS_LINE",
          "allocated "
              + total.stripTrailingZeros().toPlainString()
              + " of a line of "
              + line.qty().stripTrailingZeros().toPlainString());
    }
    List<LineAllocation> rows = new ArrayList<>();
    Instant now = Instant.now();
    for (Map.Entry<UUID, BigDecimal> e : byShop.entrySet()) {
      rows.add(
          new LineAllocation(
              Ids.newId(),
              tenantId,
              poId,
              lineId,
              line.variantId(),
              e.getKey(),
              e.getValue(),
              ctx.userId(),
              now));
    }
    return repo.replace(tenantId, poId, lineId, rows);
  }

  /**
   * Allocates the whole line by what the served shops need now: the same rule and the same fair
   * share inventory-svc's replenishment run uses. The buyer can change it afterwards.
   */
  public List<LineAllocation> fillFromNeeds(TenantContext ctx, UUID poId, UUID lineId) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = purchases.getPurchaseOrder(ctx, poId);
    PurchaseOrderLine line = lineOf(tenantId, po, lineId);
    requireAllocatable(tenantId, po);
    List<InventoryClient.NeedShare> shares =
        inventory
            .needShares(tenantId, po.storeId(), line.variantId(), line.qty())
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "PURCHASE_NETWORK_UNAVAILABLE",
                        "inventory-svc could not say what the shops need; nothing was allocated",
                        List.of()));
    List<Allocation> fill = new ArrayList<>();
    for (InventoryClient.NeedShare s : shares) {
      if (s.qty() != null && s.qty().signum() > 0) fill.add(new Allocation(s.storeId(), s.qty()));
    }
    return allocate(ctx, poId, lineId, fill);
  }

  /** The order's allocations, every line. */
  public List<LineAllocation> allocations(TenantContext ctx, UUID poId) {
    purchases.getPurchaseOrder(ctx, poId);
    return repo.byOrder(ctx.requireTenantId(), poId);
  }

  private PurchaseOrderLine lineOf(UUID tenantId, PurchaseOrder po, UUID lineId) {
    return orders.findPurchaseOrderLines(tenantId, po.id()).stream()
        .filter(l -> l.id().equals(lineId))
        .findFirst()
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PURCHASE_LINE_NOT_FOUND", "no line " + lineId + " on order " + po.id()));
  }

  private void requireAllocatable(UUID tenantId, PurchaseOrder po) {
    if (!Domain.PO_DRAFT.equals(po.status())) {
      throw ApiException.conflict(
          "PURCHASE_ALLOCATION_ORDER_NOT_DRAFT",
          "allocations are set while the order is a draft; this one is " + po.status());
    }
    if (Domain.PO_OWNERSHIP_CONSIGNMENT.equals(po.ownership())
        || Domain.PO_DUTY_SUSPENDED.equals(po.dutyStatus())) {
      throw ApiException.conflict(
          "PURCHASE_ALLOCATION_STOCK_NOT_OWNED",
          "consignment and bonded stock have their own transfer rules and are not cross-docked");
    }
    if (!profiles.stores(tenantId, po.storeId()).isWarehouse(po.storeId())) {
      throw ApiException.badRequest(
          "PURCHASE_ALLOCATION_NOT_A_WAREHOUSE",
          "only an order delivered to a warehouse is cross-docked to its shops");
    }
  }
}
