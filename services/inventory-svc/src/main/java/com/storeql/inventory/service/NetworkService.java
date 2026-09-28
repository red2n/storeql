package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.DcReplenishment;
import com.storeql.inventory.domain.Domain.DemandForecast;
import com.storeql.inventory.domain.Domain.DirectPurchase;
import com.storeql.inventory.domain.Domain.Level;
import com.storeql.inventory.domain.Domain.ReorderPointPlan;
import com.storeql.inventory.domain.Domain.Serving;
import com.storeql.inventory.domain.Domain.TransferOrder;
import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.inventory.domain.Domain.TransferProposalRun;
import com.storeql.inventory.repo.NetworkRepository;
import com.storeql.inventory.service.InventoryService.TransferOrderWithLines;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Depot / DC replenishment (intent/depot-dc-replenishment.md): which warehouse serves which shop,
 * the transfer proposals a warehouse raises for its shops, and the sourcing purchase-svc reads so
 * that it buys for the warehouse on the shops' behalf and never for a shop the warehouse serves.
 *
 * <p>The store types are tenant-svc's, read through {@link TenantProfiles} and never joined: only a
 * WAREHOUSE serves, and it serves shops only.
 */
@ApplicationScoped
public class NetworkService {

  private static final String[] MANAGEMENT = {"PLATFORM_ADMIN", "OWNER", "MANAGER"};
  static final int DEFAULT_COVER_DAYS = 7;
  static final int MAX_COVER_DAYS = 60;
  static final int MAX_LEAD_TIME_DAYS = 90;
  private static final int FORECAST_DAYS = 28;

  @Inject NetworkRepository repo;
  @Inject InventoryService inventory;
  @Inject ForecastService forecasts;
  @Inject TenantProfiles profiles;

  /** The network as a business sees it: each shop's warehouse and what it buys direct. */
  public record ShopServing(Serving serving, List<UUID> direct) {
    public ShopServing {
      direct = List.copyOf(direct);
    }
  }

  /** A shop's need for a product now, and its share of a quantity the warehouse would send. */
  public record NeedShare(UUID storeId, BigDecimal need, BigDecimal qty) {}

  /** A run and the DRAFT transfers it raised. */
  public record RunResult(TransferProposalRun run, List<TransferOrderWithLines> transfers) {
    public RunResult {
      transfers = List.copyOf(transfers);
    }
  }

  /** What a warehouse's shops are expected to need of one product, for its purchase proposal. */
  public record ServedDemand(
      UUID variantId,
      BigDecimal next28,
      BigDecimal avgDailyDemand,
      BigDecimal committed,
      int shops) {}

  /**
   * How a store is supplied, for purchase-svc.
   *
   * @param servedBy the shop's warehouse, or null when the store buys everything direct
   * @param direct the products a served shop buys direct
   * @param shops the shops a warehouse serves; empty for a shop
   * @param demand what a warehouse's shops are expected to need; empty for a shop
   */
  public record Sourcing(
      UUID storeId,
      boolean warehouse,
      UUID servedBy,
      Integer leadTimeDays,
      List<UUID> direct,
      List<UUID> shops,
      List<ServedDemand> demand) {
    public Sourcing {
      direct = List.copyOf(direct);
      shops = List.copyOf(shops);
      demand = List.copyOf(demand);
    }
  }

  // ── The network ────────────────────────────────────────────────────────────

  /**
   * Sets which warehouse serves a shop, and how many days a delivery takes.
   *
   * @throws ApiException 400 {@code INVENTORY_SERVING_STORE_UNKNOWN}, {@code
   *     INVENTORY_SERVING_SELF}, {@code INVENTORY_SERVING_NOT_A_WAREHOUSE}, {@code
   *     INVENTORY_SERVING_WAREHOUSE_TO_WAREHOUSE}, {@code INVENTORY_SERVING_LEAD_TIME_INVALID}
   */
  public Serving setServing(TenantContext ctx, UUID storeId, UUID warehouseId, int leadTimeDays) {
    ctx.requireAnyRole(MANAGEMENT);
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    if (storeId.equals(warehouseId)) {
      throw ApiException.badRequest("INVENTORY_SERVING_SELF", "a store cannot serve itself");
    }
    TenantProfiles.Stores stores = profiles.stores(tenantId, warehouseId);
    if (!stores.has(storeId) || !stores.has(warehouseId)) {
      throw ApiException.badRequest(
          "INVENTORY_SERVING_STORE_UNKNOWN", "both stores must be the business's own");
    }
    if (!stores.isWarehouse(warehouseId)) {
      throw ApiException.badRequest(
          "INVENTORY_SERVING_NOT_A_WAREHOUSE",
          "only a store of type WAREHOUSE serves shops; " + warehouseId + " is a shop");
    }
    if (stores.isWarehouse(storeId)) {
      throw ApiException.badRequest(
          "INVENTORY_SERVING_WAREHOUSE_TO_WAREHOUSE",
          "a warehouse serves shops, not another warehouse");
    }
    if (leadTimeDays < 0 || leadTimeDays > MAX_LEAD_TIME_DAYS) {
      throw ApiException.badRequest(
          "INVENTORY_SERVING_LEAD_TIME_INVALID",
          "leadTimeDays must be between 0 and " + MAX_LEAD_TIME_DAYS);
    }
    Instant now = Instant.now();
    return repo.upsertServing(
        new Serving(
            Ids.newId(), tenantId, storeId, warehouseId, leadTimeDays, ctx.userId(), now, now));
  }

  /**
   * Takes a shop out of the network: it buys everything direct again.
   *
   * @throws ApiException 404 {@code INVENTORY_SERVING_NOT_FOUND}
   */
  public void removeServing(TenantContext ctx, UUID storeId) {
    ctx.requireAnyRole(MANAGEMENT);
    ctx.requireStoreAccess(storeId);
    if (!repo.deleteServing(ctx.requireTenantId(), storeId)) {
      throw notServed(storeId);
    }
  }

  /**
   * Marks a product a served shop buys direct from its supplier.
   *
   * @throws ApiException 404 {@code INVENTORY_SERVING_NOT_FOUND} when no warehouse serves the shop
   */
  public void buyDirect(TenantContext ctx, UUID storeId, UUID variantId) {
    ctx.requireAnyRole(MANAGEMENT);
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    repo.servingOf(tenantId, storeId).orElseThrow(() -> notServed(storeId));
    repo.addException(
        new DirectPurchase(Ids.newId(), tenantId, storeId, variantId, ctx.userId(), Instant.now()));
  }

  /**
   * The product comes from the warehouse again.
   *
   * @throws ApiException 404 {@code INVENTORY_SERVING_EXCEPTION_NOT_FOUND}
   */
  public void fromWarehouse(TenantContext ctx, UUID storeId, UUID variantId) {
    ctx.requireAnyRole(MANAGEMENT);
    ctx.requireStoreAccess(storeId);
    if (!repo.removeException(ctx.requireTenantId(), storeId, variantId)) {
      throw ApiException.notFound(
          "INVENTORY_SERVING_EXCEPTION_NOT_FOUND",
          "the shop does not buy " + variantId + " direct");
    }
  }

  /** The network: each shop the caller may see, its warehouse and what it buys direct. */
  public List<ShopServing> network(TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    Map<UUID, Set<UUID>> direct = repo.directByShop(tenantId);
    List<ShopServing> out = new ArrayList<>();
    for (Serving s : repo.servings(tenantId)) {
      if (!ctx.hasStoreAccess(s.storeId()) && !ctx.hasStoreAccess(s.warehouseId())) continue;
      out.add(new ShopServing(s, List.copyOf(direct.getOrDefault(s.storeId(), Set.of()))));
    }
    return out;
  }

  /** How a store is supplied: its warehouse and exceptions, or a warehouse's shops and demand. */
  public Sourcing sourcing(TenantContext ctx, UUID storeId) {
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    Map<UUID, Set<UUID>> direct = repo.directByShop(tenantId);
    Optional<Serving> served = repo.servingOf(tenantId, storeId);
    List<Serving> shops = repo.shopsOf(tenantId, storeId);
    if (shops.isEmpty()) {
      return new Sourcing(
          storeId,
          false,
          served.map(Serving::warehouseId).orElse(null),
          served.map(Serving::leadTimeDays).orElse(null),
          List.copyOf(direct.getOrDefault(storeId, Set.of())),
          List.of(),
          List.of());
    }
    Map<UUID, BigDecimal> next28 = new LinkedHashMap<>();
    Map<UUID, BigDecimal> avg = new HashMap<>();
    Map<UUID, Integer> count = new HashMap<>();
    for (Serving shop : shops) {
      Set<UUID> buysDirect = direct.getOrDefault(shop.storeId(), Set.of());
      Map<UUID, DemandForecast> forecast = latestForecasts(tenantId, shop.storeId());
      for (ReorderPointPlan plan : inventory.listRopPlans(tenantId, shop.storeId())) {
        UUID v = plan.variantId();
        if (buysDirect.contains(v)) continue;
        BigDecimal daily = plan.avgDailyDemand() == null ? BigDecimal.ZERO : plan.avgDailyDemand();
        DemandForecast f = forecast.get(v);
        BigDecimal expected =
            f != null && f.forecast() != null
                ? f.forecast().expectedOver(FORECAST_DAYS)
                : daily.multiply(BigDecimal.valueOf(FORECAST_DAYS));
        next28.merge(v, expected, BigDecimal::add);
        avg.merge(v, daily, BigDecimal::add);
        count.merge(v, 1, Integer::sum);
      }
    }
    Map<UUID, BigDecimal> committed = repo.committedByVariant(tenantId, storeId);
    List<ServedDemand> demand = new ArrayList<>();
    for (Map.Entry<UUID, BigDecimal> e : next28.entrySet()) {
      demand.add(
          new ServedDemand(
              e.getKey(),
              e.getValue(),
              avg.get(e.getKey()),
              committed.getOrDefault(e.getKey(), BigDecimal.ZERO),
              count.get(e.getKey())));
    }
    return new Sourcing(
        storeId,
        true,
        null,
        null,
        List.of(),
        shops.stream().map(Serving::storeId).toList(),
        demand);
  }

  // ── Transfer proposals ─────────────────────────────────────────────────────

  /**
   * Proposes transfers from a warehouse to the shops it serves: per shop and product, the
   * reorder-point rule; per product, a fair share when the warehouse is short; one DRAFT transfer
   * per shop with a reason on every line. Idempotent on the key, which is looked up first.
   *
   * @throws ApiException 400 {@code INVENTORY_SERVING_NOT_A_WAREHOUSE}, {@code
   *     INVENTORY_PROPOSAL_COVER_INVALID}; 409 {@code INVENTORY_PROPOSAL_NOTHING_SERVED}, {@code
   *     INVENTORY_PROPOSAL_OPEN}
   */
  public RunResult run(
      TenantContext ctx, UUID warehouseId, Integer coverDays, String idempotencyKey) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    ctx.requireStoreAccess(warehouseId);
    UUID tenantId = ctx.requireTenantId();
    if (idempotencyKey != null) {
      Optional<TransferProposalRun> done = repo.findRunByKey(tenantId, idempotencyKey);
      if (done.isPresent()) return result(tenantId, done.get());
    }
    int cover = coverDays == null ? DEFAULT_COVER_DAYS : coverDays;
    if (cover < 1 || cover > MAX_COVER_DAYS) {
      throw ApiException.badRequest(
          "INVENTORY_PROPOSAL_COVER_INVALID", "coverDays must be between 1 and " + MAX_COVER_DAYS);
    }
    if (!profiles.stores(tenantId, warehouseId).isWarehouse(warehouseId)) {
      throw ApiException.badRequest(
          "INVENTORY_SERVING_NOT_A_WAREHOUSE", "only a warehouse proposes transfers to shops");
    }
    List<Serving> shops = repo.shopsOf(tenantId, warehouseId);
    if (shops.isEmpty()) {
      throw ApiException.conflict(
          "INVENTORY_PROPOSAL_NOTHING_SERVED",
          "the warehouse serves no shop: set which shops it serves first");
    }
    if (repo.openDrafts(tenantId, warehouseId) > 0) {
      throw ApiException.conflict(
          "INVENTORY_PROPOSAL_OPEN",
          "a proposed transfer from this warehouse is still a draft — release or cancel it first");
    }

    Map<UUID, Set<UUID>> direct = repo.directByShop(tenantId);
    Map<UUID, List<DcReplenishment.Need>> needsByVariant = new LinkedHashMap<>();
    for (Serving shop : shops) {
      Set<UUID> buysDirect = direct.getOrDefault(shop.storeId(), Set.of());
      Map<UUID, BigDecimal> available = available(tenantId, shop.storeId());
      Map<UUID, BigDecimal> inbound = repo.inboundByVariant(tenantId, shop.storeId());
      Map<UUID, DemandForecast> forecast = latestForecasts(tenantId, shop.storeId());
      int days = shop.leadTimeDays() + cover;
      for (ReorderPointPlan plan : inventory.listRopPlans(tenantId, shop.storeId())) {
        UUID v = plan.variantId();
        if (buysDirect.contains(v)) continue;
        DemandForecast f = forecast.get(v);
        BigDecimal expected =
            f != null && f.forecast() != null ? f.forecast().expectedOver(days) : null;
        DcReplenishment.Result r =
            DcReplenishment.need(
                new DcReplenishment.Shop(
                    shop.storeId(),
                    plan.rop(),
                    available.getOrDefault(v, BigDecimal.ZERO),
                    inbound.getOrDefault(v, BigDecimal.ZERO),
                    expected,
                    plan.avgDailyDemand(),
                    shop.leadTimeDays(),
                    cover));
        if (r instanceof DcReplenishment.Need need) {
          needsByVariant.computeIfAbsent(v, k -> new ArrayList<>()).add(need);
        }
      }
    }

    Map<UUID, BigDecimal> atWarehouse = available(tenantId, warehouseId);
    Map<UUID, BigDecimal> committed = repo.committedByVariant(tenantId, warehouseId);
    Map<UUID, List<TransferOrderLine>> linesByShop = new LinkedHashMap<>();
    int shortLines = 0;
    UUID runId = Ids.newId();
    for (Map.Entry<UUID, List<DcReplenishment.Need>> e : needsByVariant.entrySet()) {
      UUID v = e.getKey();
      BigDecimal free =
          atWarehouse
              .getOrDefault(v, BigDecimal.ZERO)
              .subtract(committed.getOrDefault(v, BigDecimal.ZERO));
      for (DcReplenishment.Allocation a : DcReplenishment.share(free, e.getValue())) {
        if (a.qty().compareTo(a.need()) < 0) shortLines++;
        if (a.qty().signum() <= 0) continue;
        linesByShop
            .computeIfAbsent(a.storeId(), k -> new ArrayList<>())
            .add(
                new TransferOrderLine(
                    Ids.newId(), tenantId, null, v, a.qty(), null, null, a.reason()));
      }
    }

    Instant now = Instant.now();
    Map<TransferOrder, List<TransferOrderLine>> transfers = new LinkedHashMap<>();
    int lineCount = 0;
    for (Map.Entry<UUID, List<TransferOrderLine>> e : linesByShop.entrySet()) {
      UUID transferId = Ids.newId();
      TransferOrder order =
          new TransferOrder(
              transferId,
              tenantId,
              warehouseId,
              e.getKey(),
              TransferOrder.TYPE_INTRANSIT,
              TransferOrder.DRAFT,
              "proposed by the warehouse's replenishment run",
              now,
              null,
              null,
              TransferOrder.SOURCE_PROPOSAL,
              runId);
      List<TransferOrderLine> lines = new ArrayList<>();
      for (TransferOrderLine l : e.getValue()) {
        lines.add(
            new TransferOrderLine(
                l.id(),
                tenantId,
                transferId,
                l.variantId(),
                l.requestedQty(),
                null,
                null,
                l.reason()));
      }
      lineCount += lines.size();
      transfers.put(order, lines);
    }
    TransferProposalRun run =
        new TransferProposalRun(
            runId,
            tenantId,
            warehouseId,
            ctx.userId(),
            now,
            cover,
            shops.size(),
            transfers.size(),
            lineCount,
            shortLines,
            transfers.keySet().stream().map(TransferOrder::id).toList());
    return result(tenantId, repo.saveRun(run, idempotencyKey, transfers));
  }

  /**
   * How a quantity of a product arriving at the warehouse would be shared among its shops by what
   * they need now — the replenishment rule and its fair share — for cross-docking's fill helper.
   */
  public List<NeedShare> needShares(
      TenantContext ctx, UUID warehouseId, UUID variantId, BigDecimal qty) {
    ctx.requireStoreAccess(warehouseId);
    UUID tenantId = ctx.requireTenantId();
    Map<UUID, Set<UUID>> direct = repo.directByShop(tenantId);
    List<DcReplenishment.Need> needs = new ArrayList<>();
    for (Serving shop : repo.shopsOf(tenantId, warehouseId)) {
      if (direct.getOrDefault(shop.storeId(), Set.of()).contains(variantId)) continue;
      needOf(tenantId, shop, variantId, DEFAULT_COVER_DAYS).ifPresent(needs::add);
    }
    List<NeedShare> out = new ArrayList<>();
    for (DcReplenishment.Allocation a :
        DcReplenishment.share(qty == null ? BigDecimal.ZERO : qty, needs)) {
      out.add(new NeedShare(a.storeId(), a.need(), a.qty()));
    }
    return out;
  }

  /**
   * A shop's claim on a delivery, as the fair share weighs it: what it is owed, its position now
   * and how fast it sells — so that a short delivery's remainder goes to the least cover.
   */
  public DcReplenishment.Need claim(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal owed, String reason) {
    BigDecimal position =
        available(tenantId, storeId)
            .getOrDefault(variantId, BigDecimal.ZERO)
            .add(repo.inboundByVariant(tenantId, storeId).getOrDefault(variantId, BigDecimal.ZERO));
    BigDecimal daily =
        inventory.listRopPlans(tenantId, storeId).stream()
            .filter(p -> p.variantId().equals(variantId))
            .map(ReorderPointPlan::avgDailyDemand)
            .findFirst()
            .orElse(null);
    return new DcReplenishment.Need(storeId, owed, position, daily, reason);
  }

  private Optional<DcReplenishment.Need> needOf(
      UUID tenantId, Serving shop, UUID variantId, int cover) {
    Optional<ReorderPointPlan> plan =
        inventory.listRopPlans(tenantId, shop.storeId()).stream()
            .filter(p -> p.variantId().equals(variantId))
            .findFirst();
    if (plan.isEmpty()) return Optional.empty();
    DemandForecast f = latestForecasts(tenantId, shop.storeId()).get(variantId);
    int days = shop.leadTimeDays() + cover;
    DcReplenishment.Result r =
        DcReplenishment.need(
            new DcReplenishment.Shop(
                shop.storeId(),
                plan.get().rop(),
                available(tenantId, shop.storeId()).getOrDefault(variantId, BigDecimal.ZERO),
                repo.inboundByVariant(tenantId, shop.storeId())
                    .getOrDefault(variantId, BigDecimal.ZERO),
                f != null && f.forecast() != null ? f.forecast().expectedOver(days) : null,
                plan.get().avgDailyDemand(),
                shop.leadTimeDays(),
                cover));
    return r instanceof DcReplenishment.Need n ? Optional.of(n) : Optional.empty();
  }

  /** What each store holds of some products, and which of them the supplier fulfils per order. */
  public record StockByStore(List<Level> levels, Set<UUID> dropship) {
    public StockByStore {
      levels = List.copyOf(levels);
      dropship = Set.copyOf(dropship);
    }
  }

  /**
   * What each store of the business can give of the products named — the order router's read (order
   * orchestration). Quantities are the business's own; a shopper never sees them.
   */
  public StockByStore stockByStore(TenantContext ctx, List<UUID> variantIds) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER");
    UUID tenantId = ctx.requireTenantId();
    if (variantIds.isEmpty()) return new StockByStore(List.of(), Set.of());
    Set<UUID> named = Set.copyOf(variantIds);
    Set<UUID> dropship = new java.util.HashSet<>();
    for (UUID v : inventory.dropshipVariants(tenantId)) if (named.contains(v)) dropship.add(v);
    return new StockByStore(inventory.levelsForVariants(tenantId, variantIds), dropship);
  }

  /** The warehouse's runs, newest first. */
  public List<TransferProposalRun> runs(TenantContext ctx, UUID warehouseId) {
    ctx.requireStoreAccess(warehouseId);
    return repo.runs(ctx.requireTenantId(), warehouseId);
  }

  /**
   * Releases a proposed transfer: DRAFT → PENDING, for the warehouse to ship.
   *
   * @throws ApiException 404 {@code TRANSFER_ORDER_NOT_FOUND}; 409 {@code
   *     INVENTORY_TRANSFER_NOT_DRAFT}
   */
  public TransferOrderWithLines release(TenantContext ctx, UUID transferId) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    UUID tenantId = ctx.requireTenantId();
    ctx.requireStoreAccess(inventory.getTransferOrder(tenantId, transferId).order().fromStoreId());
    repo.release(tenantId, transferId);
    return inventory.getTransferOrder(tenantId, transferId);
  }

  private RunResult result(UUID tenantId, TransferProposalRun run) {
    List<TransferOrderWithLines> out = new ArrayList<>();
    for (UUID id : run.transferIds()) out.add(inventory.getTransferOrder(tenantId, id));
    return new RunResult(run, out);
  }

  private Map<UUID, BigDecimal> available(UUID tenantId, UUID storeId) {
    Map<UUID, BigDecimal> out = new HashMap<>();
    for (Level l : inventory.levels(tenantId, storeId)) {
      out.merge(
          l.variantId(), l.available() == null ? BigDecimal.ZERO : l.available(), BigDecimal::add);
    }
    return out;
  }

  private Map<UUID, DemandForecast> latestForecasts(UUID tenantId, UUID storeId) {
    Map<UUID, DemandForecast> out = new HashMap<>();
    for (DemandForecast f : forecasts.list(tenantId, storeId, null, 1000)) {
      out.putIfAbsent(f.variantId(), f);
    }
    return out;
  }

  private static ApiException notServed(UUID storeId) {
    return ApiException.notFound(
        "INVENTORY_SERVING_NOT_FOUND", "no warehouse serves store " + storeId);
  }
}
