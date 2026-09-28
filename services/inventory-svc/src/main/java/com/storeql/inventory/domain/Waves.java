package com.storeql.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Planning a wave, pure: the waiting orders' lines allocated to the batches the picking rule puts
 * first, merged into one pick line per batch that names the orders it serves, and walked zone by
 * zone. Nothing here reads or writes stock; the caller brings the batches in the rule's order and
 * the zones in walk order.
 */
public final class Waves {

  private Waves() {}

  /** One thing one order still needs. */
  public record Want(UUID orderId, Instant confirmedAt, UUID variantId, BigDecimal qty) {}

  /** One batch on the shelf, in the order the picking rule would draw it. */
  public record Stock(
      UUID batchId, UUID variantId, UUID zoneId, String batchNo, BigDecimal available) {}

  /** How much of a line one order gets. */
  public record Allocation(UUID orderId, BigDecimal qty) {}

  /** What no batch could cover. */
  public record Unplaced(UUID orderId, UUID variantId, BigDecimal qty) {}

  /** One pick line: one batch, what to take from it, and whom it serves in confirmation order. */
  public record Line(
      int walkOrder,
      UUID zoneId,
      UUID batchId,
      String batchNo,
      UUID variantId,
      BigDecimal directedQty,
      List<Allocation> allocations) {}

  public record Plan(List<Line> lines, List<Unplaced> unplaced) {}

  /**
   * Allocates the wants to the stock, earliest-confirmed order first, each want walking its
   * variant's batches in the given order; merges the allocations into one line per batch; orders
   * the lines by the walk — the zones in {@code zoneWalk} first in that order, then other zones by
   * id, then stock with no zone — and, within a zone, by the order the lines were first touched.
   */
  public static Plan plan(
      List<Want> wants, Map<UUID, List<Stock>> stockByVariant, List<UUID> zoneWalk) {
    List<Want> ordered = new ArrayList<>(wants);
    ordered.sort(
        Comparator.comparing(Want::confirmedAt).thenComparing(w -> w.orderId().toString()));
    Map<UUID, BigDecimal> remaining = new HashMap<>();
    Map<UUID, Stock> stockById = new HashMap<>();
    for (List<Stock> list : stockByVariant.values()) {
      for (Stock s : list) {
        remaining.put(s.batchId(), s.available());
        stockById.put(s.batchId(), s);
      }
    }
    Map<UUID, List<Allocation>> byBatch = new LinkedHashMap<>();
    List<Unplaced> unplaced = new ArrayList<>();
    for (Want w : ordered) {
      BigDecimal left = w.qty();
      for (Stock s : stockByVariant.getOrDefault(w.variantId(), List.of())) {
        if (left.signum() <= 0) break;
        BigDecimal room = remaining.getOrDefault(s.batchId(), BigDecimal.ZERO);
        if (room.signum() <= 0) continue;
        BigDecimal take = room.min(left);
        remaining.put(s.batchId(), room.subtract(take));
        byBatch
            .computeIfAbsent(s.batchId(), k -> new ArrayList<>())
            .add(new Allocation(w.orderId(), take));
        left = left.subtract(take);
      }
      if (left.signum() > 0) unplaced.add(new Unplaced(w.orderId(), w.variantId(), left));
    }
    List<Line> lines = new ArrayList<>();
    int touched = 0;
    Map<UUID, Integer> touchOrder = new HashMap<>();
    for (Map.Entry<UUID, List<Allocation>> e : byBatch.entrySet()) {
      Stock s = stockById.get(e.getKey());
      BigDecimal total =
          e.getValue().stream().map(Allocation::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
      touchOrder.put(e.getKey(), touched++);
      lines.add(
          new Line(
              0,
              s.zoneId(),
              s.batchId(),
              s.batchNo(),
              s.variantId(),
              total,
              List.copyOf(e.getValue())));
    }
    lines.sort(
        Comparator.comparingInt((Line l) -> zoneRank(l.zoneId(), zoneWalk))
            .thenComparing(l -> l.zoneId() == null ? "" : l.zoneId().toString())
            .thenComparingInt(l -> touchOrder.get(l.batchId())));
    List<Line> walked = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      walked.add(
          new Line(
              i + 1,
              l.zoneId(),
              l.batchId(),
              l.batchNo(),
              l.variantId(),
              l.directedQty(),
              l.allocations()));
    }
    return new Plan(walked, unplaced);
  }

  /** Named zones in their walk order, then any other zone, then no zone at all. */
  private static int zoneRank(UUID zoneId, List<UUID> zoneWalk) {
    if (zoneId == null) return zoneWalk.size() + 2;
    int at = zoneWalk.indexOf(zoneId);
    return at >= 0 ? at : zoneWalk.size() + 1;
  }

  /**
   * Shares what was actually picked across a line's orders, the earliest-confirmed first, never
   * more than each was allocated; the quantities returned sum to at most {@code picked}.
   */
  public static List<Allocation> share(List<Allocation> allocations, BigDecimal picked) {
    List<Allocation> out = new ArrayList<>(allocations.size());
    BigDecimal left = picked == null ? BigDecimal.ZERO : picked;
    for (Allocation a : allocations) {
      BigDecimal take = left.signum() <= 0 ? BigDecimal.ZERO : a.qty().min(left);
      out.add(new Allocation(a.orderId(), take));
      left = left.subtract(take);
    }
    return out;
  }
}
