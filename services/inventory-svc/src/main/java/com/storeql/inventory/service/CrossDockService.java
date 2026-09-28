package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.CrossDock.DockLine;
import com.storeql.inventory.domain.CrossDock.Expected;
import com.storeql.inventory.domain.DcReplenishment;
import com.storeql.inventory.repo.CrossDockRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-docking at the warehouse (intent/cross-docking.md): what a purchase order owes the shops,
 * as purchase-svc announces it, and the delivery that goes straight across the dock — shared fairly
 * among the shops when it comes short.
 */
@ApplicationScoped
public class CrossDockService {

  @Inject CrossDockRepository repo;
  @Inject NetworkService network;

  /** A delivered line, as the receipt says it. */
  public record Delivered(
      UUID variantId, BigDecimal qty, String batchNo, BigDecimal costPrice, LocalDate expiry) {}

  /** Replaces what the order owes the shops with purchase-svc's snapshot, once per event. */
  public boolean allocationsSet(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID poId,
      UUID warehouseId,
      List<Expected> rows) {
    return repo.setExpectedOnce(eventId, consumer, tenantId, poId, warehouseId, rows);
  }

  /** What the order still owes the shops the caller may see. */
  public List<com.storeql.inventory.domain.CrossDock.Owed> owed(
      com.storeql.web.TenantContext ctx, UUID poId) {
    return repo.owed(ctx.requireTenantId(), poId).stream()
        .filter(o -> ctx.hasStoreAccess(o.storeId()) || ctx.hasStoreAccess(o.warehouseId()))
        .toList();
  }

  /** The products the order still owes the shops at this warehouse: the lines that cross. */
  public Set<UUID> crossingVariants(UUID tenantId, UUID poId, UUID warehouseId) {
    return repo.expected(tenantId, poId, warehouseId).stream()
        .map(Expected::variantId)
        .collect(Collectors.toSet());
  }

  /**
   * Receives a delivery's allocated lines and sends them across the dock: each shop gets what the
   * order owes it, or — when the line came short — its fair share, the remainder to the least
   * cover. What was delivered beyond the allocations is put away at the warehouse.
   *
   * @return the transfers raised; empty when the event was already applied
   */
  public List<UUID> receive(
      UUID dedupeId,
      String consumer,
      UUID tenantId,
      UUID warehouseId,
      UUID poId,
      UUID receiptId,
      List<Delivered> lines) {
    Map<UUID, List<Expected>> owed = new LinkedHashMap<>();
    for (Expected e : repo.expected(tenantId, poId, warehouseId)) {
      owed.computeIfAbsent(e.variantId(), k -> new ArrayList<>()).add(e);
    }
    String ref = Ids.shortRef(poId);
    List<DockLine> dock = new ArrayList<>();
    for (Delivered d : lines) {
      List<Expected> claims = owed.getOrDefault(d.variantId(), List.of());
      BigDecimal total =
          claims.stream().map(Expected::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
      Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
      Map<UUID, String> reasons = new LinkedHashMap<>();
      if (d.qty().compareTo(total) >= 0) {
        for (Expected e : claims) {
          shares.put(e.storeId(), e.qty());
          reasons.put(
              e.storeId(),
              "cross-docked: " + plain(e.qty()) + " allocated to the shop on order " + ref);
        }
      } else {
        List<DcReplenishment.Need> needs = new ArrayList<>();
        for (Expected e : claims) {
          needs.add(
              network.claim(
                  tenantId,
                  e.storeId(),
                  d.variantId(),
                  e.qty(),
                  "cross-docked: " + plain(e.qty()) + " allocated to the shop on order " + ref));
        }
        for (DcReplenishment.Allocation a : DcReplenishment.share(d.qty(), needs)) {
          shares.put(a.storeId(), a.qty());
          reasons.put(
              a.storeId(),
              a.reason().replace("the warehouse is short", "the delivery came short")
                  + " ("
                  + plain(d.qty())
                  + " of "
                  + plain(total)
                  + " delivered)");
        }
      }
      dock.add(
          new DockLine(
              d.variantId(), d.qty(), d.batchNo(), d.costPrice(), d.expiry(), shares, reasons));
    }
    return repo.receiveOnce(
        dedupeId,
        consumer,
        tenantId,
        warehouseId,
        poId,
        receiptId,
        dock,
        InventoryService::stockReceivedEvent);
  }

  private static String plain(BigDecimal v) {
    BigDecimal s = v.stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
  }
}
