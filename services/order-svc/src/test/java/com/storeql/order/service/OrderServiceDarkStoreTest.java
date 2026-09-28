package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.client.PricingClient;
import com.storeql.order.client.TenantClient;
import com.storeql.order.config.ServiceConfig;
import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.order.dto.Dtos.PlaceOrderRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.service.TenantStatusRepository;
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
 * A dark store has no shop floor (ship-from-store and dark-store picking): it fills online orders
 * for delivery; nobody is there to hand a collection over, and no till rings there. When the store
 * types cannot be read, the store is taken for a shop and nothing is refused.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceDarkStoreTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID DARK = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  @Mock OrderRepository repo;
  @Mock ServiceConfig config;
  @Mock PricingClient pricing;
  @Mock com.storeql.order.client.InventoryClient inventory;
  @Mock TenantContext ctx;
  @Mock TenantStatusRepository tenantStatusRepo;
  @Mock StoreStatusRepository storeStatusRepo;
  @Mock TenantClient tenants;
  @Mock TenantProfiles profiles;
  @Mock FulfilmentWindowService windows;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
    svc.config = config;
    svc.pricing = pricing;
    svc.inventory = inventory;
    svc.tenantStatusRepo = tenantStatusRepo;
    svc.storeStatusRepo = storeStatusRepo;
    svc.tenants = tenants;
    svc.profiles = profiles;
    // Delivery and collection slots: no window is named or offered in this fixture — the mock's
    // default null answer is exactly "no slot".
    svc.windows = windows;
    svc.jurisdictions = org.mockito.Mockito.mock(com.storeql.service.Jurisdictions.class);
    lenient().when(profiles.requireCurrency(TENANT)).thenReturn("USD");
    when(ctx.requireTenantId()).thenReturn(TENANT);
    when(tenantStatusRepo.isActive(any())).thenReturn(true);
    lenient().when(storeStatusRepo.isActive(any(), any())).thenReturn(true);
    lenient().when(tenants.resolveFulfilment(any(), any())).thenReturn(Optional.empty());
    lenient().when(config.pricingEnforce()).thenReturn(false);
    lenient()
        .when(profiles.stores(TENANT, DARK))
        .thenReturn(
            new TenantProfiles.Stores(Set.of(DARK), Map.of(), Set.of(), Map.of(), Set.of(DARK)));
  }

  private static PlaceOrderRequest request(String channel, String fulfilmentType) {
    boolean delivery = "DELIVERY".equals(fulfilmentType);
    return new PlaceOrderRequest(
        DARK.toString(),
        null,
        channel,
        fulfilmentType,
        List.of(
            new OrderItemRequest(
                VARIANT.toString(), BigDecimal.ONE, BigDecimal.TEN, null, null, null)),
        null,
        null,
        null,
        "USD",
        null,
        null,
        null,
        null,
        null,
        delivery ? "1 Park Row" : null,
        null,
        delivery ? "Leeds" : null,
        delivery ? "LS1 5AB" : null,
        delivery ? "Sam Shopper" : null,
        delivery ? "07700900123" : null,
        "07700900123",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void aCollectionIsNotOfferedAtADarkStore() {
    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.placeOrder(request("ONLINE", "PICKUP"), ctx, null));
    assertEquals("ORDER_PICKUP_NOT_OFFERED", e.code());
    assertEquals(409, e.status());
    verifyNoInteractions(repo);
  }

  @Test
  void noTillRingsAtADarkStore() {
    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.placeOrder(request("POS", "INSTORE"), ctx, null));
    assertEquals("ORDER_NO_TILL_AT_DARK_STORE", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void aDeliveryIsPlacedAtADarkStoreAsAtAnyShop() {
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList()))
        .thenAnswer(inv -> inv.getArgument(0));
    var order = svc.placeOrder(request("ONLINE", "DELIVERY"), ctx, null);
    assertEquals(DARK, order.storeId());
    verify(repo, times(1)).createOrder(any(), anyList(), any(), any(), anyList(), anyList());
  }

  @Test
  void storeTypesThatCannotBeReadRefuseNothing() {
    when(profiles.stores(TENANT, DARK))
        .thenThrow(new ApiException(503, "TENANT_STORES_UNAVAILABLE", "down", List.of()));
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList()))
        .thenAnswer(inv -> inv.getArgument(0));
    var order = svc.placeOrder(request("ONLINE", "PICKUP"), ctx, null);
    assertEquals("PICKUP", order.fulfilmentType());
  }
}
