package com.storeql.order.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.client.StockClient;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Order orchestration's reading of the world: which shops are candidates for an online delivery
 * order, how far they are, and when the order is left where the postcode put it.
 */
@ExtendWith(MockitoExtension.class)
class OrderRouterTest {

  private static final UUID T = Ids.newId();
  private static final UUID LEEDS = Ids.newId();
  private static final UUID YORK = Ids.newId();
  private static final UUID HULL = Ids.newId();
  private static final UUID DC = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();
  private static final UUID CRATES = Ids.newId();

  @Mock StockClient stock;
  @Mock TenantProfiles profiles;
  @Mock StoreStatusRepository storeStatus;
  @Mock TenantContext ctx;

  private OrderRouter router;

  @BeforeEach
  void setUp() {
    router = new OrderRouter();
    router.stock = stock;
    router.profiles = profiles;
    router.storeStatus = storeStatus;
    lenient()
        .when(profiles.stores(T, LEEDS))
        .thenReturn(
            new TenantProfiles.Stores(
                Set.of(LEEDS, YORK, HULL, DC),
                Map.of(),
                Set.of(DC),
                Map.of(
                    LEEDS, new TenantProfiles.Point(53.8008, -1.5491),
                    YORK, new TenantProfiles.Point(53.9600, -1.0873),
                    HULL, new TenantProfiles.Point(53.7676, -0.3274),
                    DC, new TenantProfiles.Point(53.8000, -1.5400))));
    lenient().when(storeStatus.isActive(any(), any())).thenReturn(true);
    lenient().when(ctx.hasStoreAccess(any())).thenReturn(true);
  }

  private static OrderItem line(UUID variant, String qty) {
    return new OrderItem(
        Ids.newId(),
        T,
        Ids.newId(),
        variant,
        new BigDecimal(qty),
        BigDecimal.ONE,
        new BigDecimal(qty),
        null,
        null,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null);
  }

  private void stock(Map<UUID, Map<UUID, BigDecimal>> available, Set<UUID> dropship) {
    when(stock.stockByStore(any(), any()))
        .thenReturn(Optional.of(new StockClient.Stock(available, dropship)));
  }

  private static Map<UUID, BigDecimal> has(UUID v, String q) {
    return Map.of(v, new BigDecimal(q));
  }

  @Test
  void theAreaStoreHoldingItAllIsLeftAlone() {
    stock(Map.of(LEEDS, Map.of(APPLES, BigDecimal.TEN, PEARS, BigDecimal.TEN)), Set.of());
    assertThat(
        router.route(ctx, T, LEEDS, List.of(line(APPLES, "2"), line(PEARS, "1"))).isEmpty(),
        is(true));
    verifyNoInteractions(profiles);
  }

  @Test
  void whatTheAreaStoreLacksComesFromTheNearestShopThatHoldsIt() {
    stock(Map.of(LEEDS, has(APPLES, "5"), YORK, has(PEARS, "5"), HULL, has(PEARS, "5")), Set.of());
    var plan = router.route(ctx, T, LEEDS, List.of(line(APPLES, "2"), line(PEARS, "3"))).get();
    assertThat(plan.stores(), contains(LEEDS, YORK));
    assertThat(plan.byStore().get(YORK).get(PEARS), comparesEqualTo(new BigDecimal("3")));
  }

  @Test
  void aWarehouseNeverFillsAShoppersOrder() {
    stock(Map.of(LEEDS, has(APPLES, "1"), DC, has(APPLES, "100")), Set.of());
    ApiException e =
        assertThrows(
            ApiException.class, () -> router.route(ctx, T, LEEDS, List.of(line(APPLES, "5"))));
    assertThat(e.code(), is("ORDER_UNFULFILLABLE"));
    assertThat(e.status(), is(409));
  }

  @Test
  void aClosedShopOrOneTheCallerMayNotActAtIsPassedOver() {
    stock(Map.of(YORK, has(PEARS, "5"), HULL, has(PEARS, "5")), Set.of());
    when(storeStatus.isActive(T, YORK)).thenReturn(false);
    var plan = router.route(ctx, T, LEEDS, List.of(line(PEARS, "3"))).get();
    assertThat(plan.stores(), contains(HULL));

    // Trading again, but the caller may not act there: access is asked first.
    lenient().when(storeStatus.isActive(T, YORK)).thenReturn(true);
    when(ctx.hasStoreAccess(YORK)).thenReturn(false);
    assertThat(
        router.route(ctx, T, LEEDS, List.of(line(PEARS, "3"))).get().stores(), contains(HULL));
  }

  @Test
  void stockOrStoresThatCannotBeReadLeaveTheOrderAtTheAreaStore() {
    when(stock.stockByStore(any(), any())).thenReturn(Optional.empty());
    assertThat(router.route(ctx, T, LEEDS, List.of(line(APPLES, "2"))).isEmpty(), is(true));

    stock(Map.of(YORK, has(APPLES, "5")), Set.of());
    when(profiles.stores(T, LEEDS))
        .thenThrow(new ApiException(503, "TENANT_STORES_UNAVAILABLE", "down", List.of()));
    assertThat(router.route(ctx, T, LEEDS, List.of(line(APPLES, "2"))).isEmpty(), is(true));
  }

  @Test
  void aProductASupplierShipsRidesWithTheFirstPart() {
    stock(Map.of(LEEDS, has(APPLES, "5"), YORK, has(PEARS, "5")), Set.of(CRATES));
    var plan =
        router
            .route(ctx, T, LEEDS, List.of(line(APPLES, "2"), line(PEARS, "1"), line(CRATES, "4")))
            .get();
    assertThat(plan.stores(), contains(LEEDS, YORK));
    assertThat(plan.byStore().get(LEEDS).get(CRATES), comparesEqualTo(new BigDecimal("4")));
  }

  @Test
  void anOrderOfOnlyDropshipProductsIsLeftAlone() {
    stock(Map.of(), Set.of(CRATES));
    assertThat(router.route(ctx, T, LEEDS, List.of(line(CRATES, "4"))).isEmpty(), is(true));
  }

  @Test
  void aDarkStoreIsAShopToRoutingAndAWarehouseIsNot() {
    // A dark store fills online orders for delivery: exactly what routing is looking for.
    when(profiles.stores(T, LEEDS))
        .thenReturn(
            new TenantProfiles.Stores(
                Set.of(LEEDS, YORK, DC),
                Map.of(),
                Set.of(DC),
                Map.of(
                    LEEDS, new TenantProfiles.Point(53.8008, -1.5491),
                    YORK, new TenantProfiles.Point(53.9600, -1.0873),
                    DC, new TenantProfiles.Point(53.8000, -1.5400)),
                Set.of(YORK)));
    stock(Map.of(YORK, has(PEARS, "5"), DC, has(PEARS, "50")), Set.of());
    var plan = router.route(ctx, T, LEEDS, List.of(line(PEARS, "3"))).get();
    assertThat(plan.stores(), contains(YORK));
  }
}
