package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.InventoryClient;
import com.storeql.purchase.client.InventoryClient.ForecastGlance;
import com.storeql.purchase.client.PricingClient;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.ProposalRun;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.Domain.SkippedItem;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.OrderProposal;
import com.storeql.purchase.domain.OrderProposal.Order;
import com.storeql.purchase.domain.OrderProposal.Plan;
import com.storeql.purchase.domain.OrderProposal.Position;
import com.storeql.purchase.domain.OrderProposal.Skipped;
import com.storeql.purchase.domain.Totals;
import com.storeql.purchase.repo.ProposalRepository;
import com.storeql.purchase.repo.ProposalRepository.SupplierChoice;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The automatic order proposal (06.x): the store's stock position read from inventory-svc, what is
 * already on order read from this service's own books, and for every item at or below its reorder
 * point a DRAFT purchase order on the supplier the business last bought it from — one order per
 * supplier, every line carrying the arithmetic that produced it. A person submits the draft; a
 * machine never commits money to a supplier.
 *
 * <p>Refusals fail closed where a wrong proposal would cost money: with no stock position there is
 * no proposal (503), and while a proposed order for the store is still a draft there is no second
 * one (409), so a run never doubles up on itself. The forecast alone fails open — without it the
 * plan's average daily demand stands in, and the line says so.
 */
@ApplicationScoped
public class ProposalService {

  public static final int DEFAULT_COVER_DAYS = 28;
  public static final int MAX_COVER_DAYS = 365;

  /** The forecast the client reads is the next twenty-eight days; a cover period scales it. */
  private static final BigDecimal FORECAST_DAYS = BigDecimal.valueOf(28);

  @Inject ProposalRepository repo;
  @Inject PurchaseRepository purchases;
  @Inject InventoryClient inventory;
  @Inject com.storeql.purchase.repo.CrossDockRepository crossDock;
  @Inject PricingClient pricing;

  /** One draft order a run raised, described for the reply. */
  public record ProposedOrder(
      UUID poId,
      UUID supplierId,
      String supplierName,
      String currency,
      int lines,
      BigDecimal totalNet) {}

  /** A run and its orders, described. */
  public record RunResult(ProposalRun run, List<ProposedOrder> orders) {
    public RunResult {
      orders = List.copyOf(orders);
    }
  }

  /**
   * One item the run judges: its plan and position, and what the reason starts with. A warehouse's
   * item stands for the shops it serves (depot / DC replenishment), so its reason says so.
   */
  private record Item(
      Plan plan,
      BigDecimal available,
      BigDecimal onOrder,
      BigDecimal next28,
      Integer shelfLife,
      String prefix) {}

  private record ProposedLine(
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      String reason,
      int leadTimeDays) {}

  /**
   * A warehouse's item, standing for the shops it serves: the reorder point is their daily demand
   * over the warehouse's lead time, the forecast is theirs, and what is already promised to them is
   * taken off what the warehouse holds. The warehouse's own plan, when it has one, still gives the
   * lead time and the supplier's order modifiers; its own demand history does not count — a
   * warehouse sells nothing itself.
   */
  private static Item servedItem(
      UUID v,
      InventoryClient.ServedDemand d,
      Plan own,
      int lead,
      Map<UUID, BigDecimal> available,
      Map<UUID, BigDecimal> onOrder) {
    BigDecimal daily = d.avgDailyDemand() == null ? BigDecimal.ZERO : d.avgDailyDemand();
    BigDecimal committed = d.committed() == null ? BigDecimal.ZERO : d.committed();
    Plan plan =
        new Plan(
            v,
            daily.multiply(BigDecimal.valueOf(lead)).setScale(3, RoundingMode.HALF_UP),
            own == null ? null : own.eoq(),
            own == null ? null : own.minOrderQty(),
            own == null ? null : own.maxOrderQty(),
            own == null ? null : own.lotMultiplier(),
            daily,
            lead);
    String prefix =
        "for the "
            + d.shops()
            + (d.shops() == 1 ? " shop" : " shops")
            + " it serves ("
            + OrderProposal.plain(daily)
            + "/day; "
            + OrderProposal.plain(committed)
            + " committed to them): ";
    return new Item(
        plan,
        available.getOrDefault(v, BigDecimal.ZERO).subtract(committed),
        onOrder.getOrDefault(v, BigDecimal.ZERO),
        d.next28(),
        null,
        prefix);
  }

  /** The supplier's quoted lead time, for the supplier the business would buy the item from. */
  private Integer supplierQuote(UUID tenantId, SupplierChoice lastBought, UUID coded) {
    UUID supplierId = lastBought != null ? lastBought.supplierId() : coded;
    if (supplierId == null) return null;
    return purchases.findSupplier(tenantId, supplierId).map(Supplier::leadTimeDays).orElse(null);
  }

  /**
   * Proposes orders for a store.
   *
   * @param ctx the caller, held to their stores
   * @param storeId the store
   * @param coverDays days an order without an EOQ should cover; null for 28
   * @return the run and the drafts it raised
   * @throws ApiException 400 PURCHASE_PROPOSAL_COVER_INVALID, 409 PURCHASE_PROPOSAL_OPEN, 503
   *     PURCHASE_PROPOSAL_STOCK_UNAVAILABLE
   */
  public RunResult run(TenantContext ctx, UUID storeId, Integer coverDays) {
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    int cover = coverDays == null ? DEFAULT_COVER_DAYS : coverDays;
    if (cover < 1 || cover > MAX_COVER_DAYS) {
      throw ApiException.badRequest(
          "PURCHASE_PROPOSAL_COVER_INVALID", "coverDays must be between 1 and " + MAX_COVER_DAYS);
    }
    if (repo.openProposalDrafts(tenantId, storeId) > 0) {
      throw ApiException.conflict(
          "PURCHASE_PROPOSAL_OPEN",
          "A proposed order for this store is still a draft — submit or cancel it before proposing again");
    }
    List<Plan> plans =
        inventory.reorderPlans(tenantId, storeId).orElseThrow(ProposalService::stockUnavailable);
    // Depot / DC replenishment: a shop a warehouse serves buys only what it buys direct; a
    // warehouse buys for the shops it serves.
    InventoryClient.Sourcing sourcing =
        inventory.sourcing(tenantId, storeId).orElseThrow(ProposalService::stockUnavailable);
    Map<UUID, BigDecimal> available =
        inventory
            .availableByVariant(tenantId, storeId)
            .orElseThrow(ProposalService::stockUnavailable);
    Map<UUID, ForecastGlance> forecast =
        inventory.forecastGlances(tenantId, storeId).orElse(Map.of());
    Map<UUID, BigDecimal> onOrder = new LinkedHashMap<>(repo.onOrderByVariant(tenantId, storeId));
    if (sourcing.warehouse()) {
      // Cross-docking: what is allocated to shops on the warehouse's open orders is theirs, on
      // its way to them — not the warehouse's to count against its own reorder point.
      crossDock
          .allocatedOnOrder(tenantId, storeId)
          .forEach(
              (v, q) -> onOrder.computeIfPresent(v, (k, o) -> o.subtract(q).max(BigDecimal.ZERO)));
    }
    List<UUID> variants = new ArrayList<>(plans.stream().map(Plan::variantId).toList());
    for (UUID v : sourcing.demand().keySet()) if (!variants.contains(v)) variants.add(v);
    Map<UUID, SupplierChoice> lastBought = repo.lastSupplierByVariant(tenantId, variants);
    Map<UUID, UUID> coded = repo.itemCodeSupplierByVariant(tenantId, variants);

    List<SkippedItem> skipped = new ArrayList<>();
    List<Item> items = new ArrayList<>();
    int unleaded = 0;
    Map<UUID, Plan> planByVariant = new LinkedHashMap<>();
    for (Plan plan : plans) {
      if (sourcing.fromWarehouse(plan.variantId())) continue;
      planByVariant.put(plan.variantId(), plan);
    }
    for (Map.Entry<UUID, InventoryClient.ServedDemand> e : sourcing.demand().entrySet()) {
      UUID v = e.getKey();
      Plan own = planByVariant.remove(v);
      Integer quote = supplierQuote(tenantId, lastBought.get(v), coded.get(v));
      Integer lead = own != null ? Integer.valueOf(own.leadTimeDays()) : quote;
      if (lead == null) {
        unleaded++;
        skipped.add(
            new SkippedItem(
                v,
                "no lead time for the warehouse: set a reorder plan at the warehouse or the"
                    + " supplier's quoted lead time"));
        continue;
      }
      items.add(servedItem(v, e.getValue(), own, lead, available, onOrder));
    }
    for (Plan plan : planByVariant.values()) {
      UUID v = plan.variantId();
      ForecastGlance glance = forecast.get(v);
      items.add(
          new Item(
              plan,
              available.getOrDefault(v, BigDecimal.ZERO),
              onOrder.getOrDefault(v, BigDecimal.ZERO),
              glance == null ? null : glance.next28(),
              glance == null ? null : glance.maxCoverDays(),
              ""));
    }

    Map<UUID, List<ProposedLine>> bySupplier = new LinkedHashMap<>();
    for (Item item : items) {
      Plan plan = item.plan();
      UUID v = plan.variantId();
      Integer shelfLife = item.shelfLife();
      // The cover this line will get: what was asked for, or the shelf life when that is shorter.
      int lineCover = shelfLife != null && shelfLife < cover ? shelfLife : cover;
      BigDecimal expected = item.next28();
      if (expected != null && lineCover != FORECAST_DAYS.intValue()) {
        expected =
            expected
                .multiply(BigDecimal.valueOf(lineCover))
                .divide(FORECAST_DAYS, 3, RoundingMode.HALF_UP);
      }
      OrderProposal.Result result =
          OrderProposal.propose(
              plan, new Position(item.available(), item.onOrder(), expected, shelfLife), cover);
      if (result instanceof Skipped s) {
        skipped.add(new SkippedItem(v, item.prefix() + s.reason()));
        continue;
      }
      if (!(result instanceof Order order)) {
        continue;
      }
      SupplierChoice choice = lastBought.get(v);
      if (choice == null && coded.containsKey(v)) {
        choice = new SupplierChoice(coded.get(v), null, null);
      }
      if (choice == null) {
        skipped.add(
            new SkippedItem(
                v,
                "no supplier: the business has not bought this item on the platform and no supplier's item code names it"));
        continue;
      }
      bySupplier
          .computeIfAbsent(choice.supplierId(), k -> new ArrayList<>())
          .add(
              new ProposedLine(
                  v,
                  order.qty(),
                  choice.unitPrice() == null ? BigDecimal.ZERO : choice.unitPrice(),
                  choice.vatCode() == null ? "T1" : choice.vatCode(),
                  item.prefix() + order.reason(),
                  plan.leadTimeDays()));
    }

    Instant now = Instant.now();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Map<String, BigDecimal> vatRates =
        bySupplier.isEmpty() ? Map.of() : pricing.findVatRates(tenantId);
    List<UUID> orderIds = new ArrayList<>();
    List<ProposedOrder> orders = new ArrayList<>();
    int lines = 0;
    for (Map.Entry<UUID, List<ProposedLine>> e : bySupplier.entrySet()) {
      Supplier supplier =
          purchases
              .findSupplier(tenantId, e.getKey())
              .orElseThrow(
                  () ->
                      ApiException.notFound(
                          "PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + e.getKey()));
      int lead = e.getValue().stream().mapToInt(ProposedLine::leadTimeDays).max().orElse(7);
      PurchaseOrder po =
          new PurchaseOrder(
              Ids.newId(),
              tenantId,
              supplier.id(),
              storeId,
              Domain.PO_DRAFT,
              supplier.currency(),
              Totals.zero(supplier.currency()).net(),
              Totals.zero(supplier.currency()).vat(),
              Totals.zero(supplier.currency()).gross(),
              today.plusDays(lead),
              now,
              now,
              null,
              null,
              null,
              null,
              ctx.userId(),
              null,
              null,
              Domain.PO_SOURCE_PROPOSAL);
      purchases.createPurchaseOrder(po, Events.purchaseOrderCreated(tenantId, po.id()));
      for (ProposedLine l : e.getValue()) {
        purchases.addPurchaseOrderLine(
            new PurchaseOrderLine(
                Ids.newId(),
                tenantId,
                po.id(),
                l.variantId(),
                l.qty(),
                l.unitPrice(),
                l.vatCode(),
                now,
                l.reason()),
            supplier.currency(),
            vatRates);
        lines++;
      }
      PurchaseOrder restated = purchases.findPurchaseOrder(tenantId, po.id()).orElse(po);
      orderIds.add(po.id());
      orders.add(
          new ProposedOrder(
              po.id(),
              supplier.id(),
              supplier.name(),
              supplier.currency(),
              e.getValue().size(),
              restated.totalNet()));
    }
    ProposalRun run =
        new ProposalRun(
            Ids.newId(),
            tenantId,
            storeId,
            ctx.userId(),
            now,
            cover,
            items.size() + unleaded,
            orderIds.size(),
            lines,
            orderIds,
            skipped);
    repo.insertRun(run);
    return new RunResult(run, orders);
  }

  /**
   * The store's runs, latest first, each with its orders as they stand now.
   *
   * @param ctx the caller, held to their stores
   * @param storeId the store
   * @param limit at most this many runs
   * @return the runs
   */
  public List<RunResult> list(TenantContext ctx, UUID storeId, int limit) {
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    List<RunResult> out = new ArrayList<>();
    for (ProposalRun run : repo.listRuns(tenantId, storeId, limit)) {
      List<ProposedOrder> orders = new ArrayList<>();
      for (UUID poId : run.orderIds()) {
        purchases
            .findPurchaseOrder(tenantId, poId)
            .ifPresent(
                po ->
                    orders.add(
                        new ProposedOrder(
                            po.id(),
                            po.supplierId(),
                            purchases
                                .findSupplier(tenantId, po.supplierId())
                                .map(Supplier::name)
                                .orElse(null),
                            po.currency(),
                            purchases.findPurchaseOrderLines(tenantId, po.id()).size(),
                            po.totalNet())));
      }
      out.add(new RunResult(run, orders));
    }
    return out;
  }

  private static ApiException stockUnavailable() {
    return new ApiException(
        503,
        "PURCHASE_PROPOSAL_STOCK_UNAVAILABLE",
        "inventory-svc could not be read; nothing is proposed without the stock position",
        List.of(),
        null);
  }
}
