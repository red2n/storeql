package com.storeql.order.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Order orchestration (intent/order-orchestration-and-split-fulfilment.md), pure: which store fills
 * which line of a delivery order.
 *
 * <p>The delivery-area store takes every line it holds in full. What is left goes, whole, to the
 * nearest other store that holds all of it; failing one, to the fewest stores — each time the store
 * that holds the most of what is left in full, the nearer on a tie. A line is split across stores
 * only when no one store holds it: the area store's share first, then the nearest. Nothing that
 * adds up is unfulfillable, and says by how much.
 */
public final class Routing {

  private static final double EARTH_KM = 6371.0088;

  private Routing() {}

  /**
   * A store that could fill part of the order.
   *
   * @param distanceKm from the delivery-area store; null when either records no coordinates
   * @param available what it can give of each product
   */
  public record Candidate(UUID storeId, Double distanceKm, Map<UUID, BigDecimal> available) {}

  /** The rule's answer. */
  public sealed interface Result permits Plan, Unfulfillable {}

  /**
   * Which store fills what: store → product → quantity, the area store first when it takes part,
   * then in the order the stores were chosen.
   */
  public record Plan(List<Leg> legs) implements Result {
    public Plan {
      legs = List.copyOf(legs);
    }

    public List<UUID> stores() {
      return legs.stream().map(Leg::storeId).toList();
    }

    /** Store → product → quantity, in the parts' order; a fresh map on every call. */
    public Map<UUID, Map<UUID, BigDecimal>> byStore() {
      Map<UUID, Map<UUID, BigDecimal>> out = new LinkedHashMap<>();
      for (Leg leg : legs) out.put(leg.storeId(), leg.qty());
      return out;
    }
  }

  /** What one store fills: product → quantity. */
  public record Leg(UUID storeId, Map<UUID, BigDecimal> qty) {
    public Leg {
      qty = Map.copyOf(qty);
    }
  }

  /** No combination of stores holds the order; short by this much of each product. */
  public record Unfulfillable(Map<UUID, BigDecimal> shortBy) implements Result {}

  /** Applies the rule. */
  public static Result route(
      Map<UUID, BigDecimal> wanted, UUID areaStore, List<Candidate> candidates) {
    Map<UUID, Map<UUID, BigDecimal>> stock = new HashMap<>();
    for (Candidate c : candidates) stock.put(c.storeId(), new HashMap<>(c.available()));
    stock.putIfAbsent(areaStore, new HashMap<>());
    List<UUID> others =
        candidates.stream()
            .filter(c -> !c.storeId().equals(areaStore))
            .sorted(
                Comparator.comparing(
                        Candidate::distanceKm, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Candidate::storeId))
            .map(Candidate::storeId)
            .toList();
    Map<UUID, BigDecimal> left = new LinkedHashMap<>(wanted);
    Map<UUID, Map<UUID, BigDecimal>> plan = new LinkedHashMap<>();

    // 1. The area store takes every line it holds in full.
    takeWhole(plan, stock, areaStore, left);
    // 2. The nearest store that holds all of what is left.
    if (!left.isEmpty()) {
      for (UUID s : others) {
        if (holdsAll(stock.get(s), left)) {
          takeWhole(plan, stock, s, left);
          break;
        }
      }
    }
    // 3. The fewest stores: each time the one holding most of what is left in full, nearer first.
    while (!left.isEmpty()) {
      UUID best = null;
      int most = 0;
      for (UUID s : others) {
        int n = wholeLines(stock.get(s), left);
        if (n > most) {
          most = n;
          best = s;
        }
      }
      if (best == null) break;
      takeWhole(plan, stock, best, left);
    }
    // 4. A line no one store holds is split: the area store's share, then the nearest.
    if (!left.isEmpty()) {
      List<UUID> order = new ArrayList<>();
      order.add(areaStore);
      order.addAll(others);
      Map<UUID, BigDecimal> shortBy = new LinkedHashMap<>();
      for (Map.Entry<UUID, BigDecimal> line : new ArrayList<>(left.entrySet())) {
        BigDecimal need = line.getValue();
        for (UUID s : order) {
          if (need.signum() <= 0) break;
          BigDecimal has = stock.get(s).getOrDefault(line.getKey(), BigDecimal.ZERO);
          if (has.signum() <= 0) continue;
          BigDecimal take = has.min(need);
          plan.computeIfAbsent(s, k -> new LinkedHashMap<>())
              .merge(line.getKey(), take, BigDecimal::add);
          stock.get(s).put(line.getKey(), has.subtract(take));
          need = need.subtract(take);
        }
        if (need.signum() > 0) shortBy.put(line.getKey(), need);
      }
      if (!shortBy.isEmpty()) return new Unfulfillable(shortBy);
    }
    // The area store first when it takes part: the parts in the order the shopper reads them.
    List<Leg> legs = new ArrayList<>();
    if (plan.containsKey(areaStore)) legs.add(new Leg(areaStore, plan.get(areaStore)));
    plan.forEach(
        (store, qty) -> {
          if (!store.equals(areaStore)) legs.add(new Leg(store, qty));
        });
    return new Plan(legs);
  }

  /** Straight-line distance in kilometres between two points, in degrees. */
  public static double km(double latA, double lngA, double latB, double lngB) {
    double dLat = Math.toRadians(latB - latA);
    double dLng = Math.toRadians(lngB - lngA);
    double h =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(latA))
                * Math.cos(Math.toRadians(latB))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(h)));
  }

  private static boolean holdsAll(Map<UUID, BigDecimal> has, Map<UUID, BigDecimal> left) {
    return wholeLines(has, left) == left.size();
  }

  private static int wholeLines(Map<UUID, BigDecimal> has, Map<UUID, BigDecimal> left) {
    int n = 0;
    for (Map.Entry<UUID, BigDecimal> e : left.entrySet()) {
      if (has.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) >= 0) n++;
    }
    return n;
  }

  private static void takeWhole(
      Map<UUID, Map<UUID, BigDecimal>> plan,
      Map<UUID, Map<UUID, BigDecimal>> stock,
      UUID store,
      Map<UUID, BigDecimal> left) {
    Map<UUID, BigDecimal> has = stock.get(store);
    for (Map.Entry<UUID, BigDecimal> e : new ArrayList<>(left.entrySet())) {
      if (has.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) >= 0) {
        plan.computeIfAbsent(store, k -> new LinkedHashMap<>()).put(e.getKey(), e.getValue());
        has.put(e.getKey(), has.get(e.getKey()).subtract(e.getValue()));
        left.remove(e.getKey());
      }
    }
  }
}
