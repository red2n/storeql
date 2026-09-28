package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.AwaitingLine;
import com.storeql.inventory.domain.Domain.AwaitingOrder;
import com.storeql.inventory.domain.Domain.PickWave;
import com.storeql.inventory.domain.Waves;
import com.storeql.inventory.dto.WaveDtos.PickLineRequest;
import com.storeql.inventory.dto.WaveDtos.RecordPicksRequest;
import com.storeql.inventory.repo.WaveRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.ErrorCodes;
import com.storeql.web.Parsing;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Wave picking: the confirmed online orders waiting at a store gathered into one walk through the
 * zones, directed to the batches the picking rule chooses; what was picked deducted from exactly
 * those batches and the orders fulfilled through order-svc. Building, picking and completing a wave
 * is warehouse work: it needs {@code stock.transfer}, as a move order does.
 */
@ApplicationScoped
public class WaveService {

  private static final Set<String> WAVED_FULFILMENTS = Set.of("PICKUP", "DELIVERY");

  @Inject WaveRepository repo;

  // ── The projection, fed by order-svc's events ──────────────────────────────

  /**
   * An online order confirmed for pickup or delivery waits at its store; anything else does not.
   */
  public boolean awaitConfirmedOnce(
      UUID eventId,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      String fulfilmentType,
      Instant confirmedAt,
      Map<UUID, BigDecimal> lines) {
    if (!waits(channel, fulfilmentType) || lines.isEmpty()) {
      return false;
    }
    return repo.awaitOnce(
        eventId,
        tenantId,
        orderId,
        storeId,
        fulfilmentType.toUpperCase(Locale.ROOT),
        confirmedAt,
        lines);
  }

  /**
   * The order is done (cancelled, or handed over in full): it waits no more. {@code
   * couldHaveWaited} says whether it is the kind of order that waits at all — an online pickup or
   * delivery — so a confirmation of it arriving late meets a tombstone; a till sale leaves none.
   */
  public void forget(UUID tenantId, UUID orderId, boolean couldHaveWaited) {
    repo.forget(tenantId, orderId, couldHaveWaited);
  }

  /** Whether an order of this kind waits to be picked: online, for pickup or delivery. */
  public static boolean waits(String channel, String fulfilmentType) {
    return "ONLINE".equalsIgnoreCase(channel)
        && fulfilmentType != null
        && WAVED_FULFILMENTS.contains(fulfilmentType.toUpperCase(Locale.ROOT));
  }

  public void fulfilledByHand(UUID tenantId, UUID orderId, UUID variantId, BigDecimal qty) {
    repo.fulfilledByHand(tenantId, orderId, variantId, qty);
  }

  /** What order-svc says the line still has outstanding after a handover: it waits for no more. */
  public void outstandingKnown(
      UUID tenantId, UUID orderId, UUID variantId, BigDecimal outstanding) {
    repo.outstandingKnown(tenantId, orderId, variantId, outstanding);
  }

  /**
   * What a wave already drew for this order line (zero when none did), acknowledged on the
   * fulfilment's dedupe id with the line's revenue recorded for the whole; the caller deducts only
   * what is left.
   */
  public BigDecimal pickedByWave(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      BigDecimal netAmount) {
    return repo.pickedByWave(
        dedupeId, consumerName, tenantId, storeId, variantId, qty, orderId, netAmount);
  }

  // ── Reads ──────────────────────────────────────────────────────────────────

  public List<AwaitingOrder> awaiting(TenantContext ctx, UUID storeId) {
    // A keeper of one store reads that store when none is named; of several, must say which.
    return repo.awaiting(ctx.requireTenantId(), ctx.scopeStore(storeId));
  }

  /**
   * @throws ApiException 404 {@code INVENTORY_WAVE_NOT_FOUND}
   */
  public PickWave get(TenantContext ctx, UUID id) {
    PickWave w =
        repo.find(ctx.requireTenantId(), id)
            .orElseThrow(() -> ApiException.notFound("INVENTORY_WAVE_NOT_FOUND", "no wave " + id));
    ctx.requireStoreAccess(w.storeId());
    return w;
  }

  public List<PickWave> list(TenantContext ctx, UUID storeId, String status) {
    UUID store = ctx.scopeStore(storeId);
    String code = null;
    if (status != null && !status.isBlank()) {
      code = status.trim().toUpperCase(Locale.ROOT);
      if (!PickWave.OPEN.equals(code)
          && !PickWave.COMPLETED.equals(code)
          && !PickWave.CANCELLED.equals(code)) {
        throw ApiException.badRequest(
            "INVENTORY_WAVE_STATUS_INVALID",
            "status must be OPEN, COMPLETED or CANCELLED; got " + status);
      }
    }
    return repo.list(ctx.requireTenantId(), store, code);
  }

  // ── Build, pick, complete, cancel ──────────────────────────────────────────

  /**
   * Gathers the orders waiting at the store (all of them, or the ones named) into one wave.
   *
   * @throws ApiException 409 {@code INVENTORY_WAVE_NOTHING_TO_PICK}, {@code
   *     INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE}
   */
  public PickWave build(
      TenantContext ctx, UUID storeId, List<String> orderIds, String idempotencyKey) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    if (idempotencyKey != null) {
      // The key first, before the waiting list is judged: a retried build must find its wave even
      // once the orders it took are no longer waiting, which is exactly the retry the key is for.
      Optional<PickWave> built = repo.findByIdempotencyKey(tenantId, idempotencyKey);
      if (built.isPresent()) return built.get();
    }
    Set<UUID> named = new HashSet<>();
    if (orderIds != null) for (String s : orderIds) named.add(Parsing.uuid(s, "orderIds"));
    List<AwaitingOrder> chosen = new ArrayList<>();
    for (AwaitingOrder o : repo.awaiting(tenantId, storeId)) {
      if (!named.isEmpty() && !named.contains(o.orderId())) continue;
      if (o.waveId() != null) {
        if (named.contains(o.orderId())) {
          throw ApiException.conflict(
              "INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE",
              "order " + o.orderId() + " is already in wave " + o.waveId());
        }
        continue;
      }
      if (!o.lines().isEmpty()) chosen.add(o);
    }
    if (chosen.isEmpty()) {
      throw ApiException.conflict(
          "INVENTORY_WAVE_NOTHING_TO_PICK", "no order is waiting to be picked at store " + storeId);
    }
    List<Waves.Want> wants = new ArrayList<>();
    Map<UUID, List<Waves.Stock>> stock = new LinkedHashMap<>();
    List<UUID> variants = new ArrayList<>();
    for (AwaitingOrder o : chosen) {
      for (AwaitingLine l : o.lines()) {
        wants.add(new Waves.Want(o.orderId(), o.confirmedAt(), l.variantId(), l.qtyOutstanding()));
        if (!stock.containsKey(l.variantId())) {
          stock.put(l.variantId(), repo.stockInRuleOrder(tenantId, storeId, l.variantId()));
          variants.add(l.variantId());
        }
      }
    }
    Waves.Plan plan = Waves.plan(wants, stock, repo.zoneWalk(tenantId, storeId, variants));
    if (plan.lines().isEmpty()) {
      throw ApiException.conflict(
          "INVENTORY_WAVE_NOTHING_TO_PICK",
          "nothing on the shelf at store " + storeId + " for the orders waiting");
    }
    PickWave draft =
        new PickWave(
            Ids.newId(),
            tenantId,
            storeId,
            PickWave.OPEN,
            ctx.userId(),
            Instant.now(),
            null,
            null,
            null,
            0,
            List.of());
    return repo.create(
        draft, plan, chosen.stream().map(AwaitingOrder::orderId).toList(), idempotencyKey);
  }

  public PickWave picks(TenantContext ctx, UUID id, RecordPicksRequest req) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    PickWave w = get(ctx, id);
    Map<UUID, BigDecimal> picks = new LinkedHashMap<>();
    for (PickLineRequest l : req.lines()) {
      if (l == null) {
        throw ApiException.badRequest(
            ErrorCodes.VALIDATION_FAILED, "lines: a line must not be null");
      }
      picks.put(Parsing.uuid(l.lineId(), "lines.lineId"), l.pickedQty());
    }
    return repo.recordPicks(w.tenantId(), id, picks);
  }

  public PickWave complete(TenantContext ctx, UUID id) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    PickWave w = get(ctx, id);
    return repo.complete(w.tenantId(), id, ctx.userId(), WaveService::events);
  }

  public PickWave cancel(TenantContext ctx, UUID id) {
    ctx.requirePermission(Permissions.STOCK_TRANSFER);
    PickWave w = get(ctx, id);
    return repo.cancel(w.tenantId(), id);
  }

  /** order-svc hears which orders to fulfil for what; the stock projections hear each deduction. */
  static List<OutboxRow> events(PickWave wave, Map<UUID, Map<UUID, BigDecimal>> picked) {
    List<OutboxRow> out = new ArrayList<>();
    if (!picked.isEmpty()) {
      out.add(
          new OutboxRow(
              "WavePicked",
              "storeql.inventory.wave-picked",
              wave.tenantId(),
              wave.id(),
              Events.wavePicked(wave, picked)));
    }
    for (Map.Entry<UUID, Map<UUID, BigDecimal>> o : picked.entrySet()) {
      for (Map.Entry<UUID, BigDecimal> v : o.getValue().entrySet()) {
        out.add(
            new OutboxRow(
                "StockDeducted",
                "storeql.inventory.stock-deducted",
                wave.tenantId(),
                o.getKey(),
                Events.stockDeducted(
                    wave.tenantId(), wave.storeId(), v.getKey(), o.getKey(), v.getValue())));
      }
    }
    return out;
  }
}
