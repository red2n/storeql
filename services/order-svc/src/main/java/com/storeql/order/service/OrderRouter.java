package com.storeql.order.service;

import com.storeql.order.client.StockClient;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Routing;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Order orchestration (intent/order-orchestration-and-split-fulfilment.md): which of the business's
 * shops fill an online delivery order, read from inventory-svc's stock by store and tenant-svc's
 * stores, decided by the pure {@link Routing} rule.
 *
 * <p>Only shops (type STORE) that are trading and that the caller may act at are candidates; a
 * warehouse serves its shops and never a shopper. A product a supplier ships per order (dropship)
 * is held nowhere and rides with the first part.
 */
@ApplicationScoped
public class OrderRouter {

  private static final System.Logger LOG = System.getLogger(OrderRouter.class.getName());

  @Inject StockClient stock;
  @Inject TenantProfiles profiles;
  @Inject StoreStatusRepository storeStatus;

  /**
   * Where the order goes.
   *
   * @param areaStore the delivery-area store the postcode resolved to
   * @return the stores and what each fills, the area store's part first; empty when the area store
   *     holds it all, or when the stock or the stores cannot be read — the order is then placed at
   *     the area store as it always was, and its holds decide
   * @throws ApiException 409 {@code ORDER_UNFULFILLABLE} when no combination of shops holds it
   */
  public Optional<Routing.Plan> route(
      TenantContext ctx, UUID tenantId, UUID areaStore, List<OrderItem> items) {
    Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
    for (OrderItem it : items) wanted.merge(it.variantId(), it.qty(), BigDecimal::add);
    Optional<StockClient.Stock> read = stock.stockByStore(tenantId, List.copyOf(wanted.keySet()));
    if (read.isEmpty()) return Optional.empty();
    Map<UUID, BigDecimal> dropship = new LinkedHashMap<>();
    for (UUID v : read.get().dropship()) {
      BigDecimal q = wanted.remove(v);
      if (q != null) dropship.put(v, q);
    }
    if (wanted.isEmpty() || holdsAll(read.get().available().get(areaStore), wanted)) {
      return Optional.empty();
    }
    TenantProfiles.Stores stores;
    try {
      stores = profiles.stores(tenantId, areaStore);
    } catch (ApiException e) {
      LOG.log(System.Logger.Level.WARNING, "stores unreadable; order not routed: {0}", e.code());
      return Optional.empty();
    }
    List<Routing.Candidate> candidates = new ArrayList<>();
    for (Map.Entry<UUID, Map<UUID, BigDecimal>> e : read.get().available().entrySet()) {
      UUID s = e.getKey();
      if (!s.equals(areaStore) && !shopFor(ctx, tenantId, stores, s)) continue;
      candidates.add(new Routing.Candidate(s, distance(stores, areaStore, s), e.getValue()));
    }
    Routing.Result result = Routing.route(wanted, areaStore, candidates);
    if (result instanceof Routing.Unfulfillable) {
      throw ApiException.conflict(
          "ORDER_UNFULFILLABLE",
          "no combination of the business's shops holds everything on this order");
    }
    Routing.Plan plan = (Routing.Plan) result;
    if (dropship.isEmpty()) return Optional.of(plan);
    List<Routing.Leg> legs = new ArrayList<>(plan.legs());
    Map<UUID, BigDecimal> first = new HashMap<>(legs.get(0).qty());
    dropship.forEach((v, q) -> first.merge(v, q, BigDecimal::add));
    legs.set(0, new Routing.Leg(legs.get(0).storeId(), first));
    return Optional.of(new Routing.Plan(legs));
  }

  private boolean shopFor(
      TenantContext ctx, UUID tenantId, TenantProfiles.Stores stores, UUID storeId) {
    return stores.has(storeId)
        && !stores.isWarehouse(storeId)
        && ctx.hasStoreAccess(storeId)
        && storeStatus.isActive(tenantId, storeId);
  }

  private static Double distance(TenantProfiles.Stores stores, UUID from, UUID to) {
    TenantProfiles.Point a = stores.where(from);
    TenantProfiles.Point b = stores.where(to);
    if (a == null || b == null) return null;
    return Routing.km(a.lat(), a.lng(), b.lat(), b.lng());
  }

  private static boolean holdsAll(Map<UUID, BigDecimal> has, Map<UUID, BigDecimal> wanted) {
    if (has == null) return false;
    for (Map.Entry<UUID, BigDecimal> w : wanted.entrySet()) {
      if (has.getOrDefault(w.getKey(), BigDecimal.ZERO).compareTo(w.getValue()) < 0) return false;
    }
    return true;
  }
}
