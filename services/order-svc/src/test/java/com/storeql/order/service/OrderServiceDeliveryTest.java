package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.client.PricingClient;
import com.storeql.order.client.TenantClient;
import com.storeql.order.config.ServiceConfig;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.order.dto.Dtos.PlaceOrderRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.service.StoreStatusRepository;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** "Deliver to home" must capture a full address; other fulfilment types must not persist one. */
@ExtendWith(MockitoExtension.class)
class OrderServiceDeliveryTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  @Mock OrderRepository repo;
  @Mock ServiceConfig config;
  @Mock PricingClient pricing;
  @Mock com.storeql.order.client.InventoryClient inventory;
  @Mock TenantContext ctx;
  @Mock TenantStatusRepository tenantStatusRepo;
  @Mock StoreStatusRepository storeStatusRepo;
  @Mock TenantClient tenants;
  @Mock com.storeql.service.TenantProfiles profiles;
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
    // default null answer is exactly "no slot", so checkout behaves as it did before the feature.
    svc.windows = windows;
    // 09.16: no deposit scheme reaches these sales; a mock answers empty.
    svc.jurisdictions = org.mockito.Mockito.mock(com.storeql.service.Jurisdictions.class);
    // The tenant's declared currency, as tenant-svc would answer (SJ-D53).
    org.mockito.Mockito.lenient().when(profiles.requireCurrency(TENANT)).thenReturn("USD");
    when(ctx.requireTenantId()).thenReturn(TENANT);
    when(tenantStatusRepo.isActive(any())).thenReturn(true);
    org.mockito.Mockito.lenient().when(storeStatusRepo.isActive(any(), any())).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(tenants.resolveFulfilment(any(), any()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.lenient().when(config.pricingEnforce()).thenReturn(false);
  }

  private static PlaceOrderRequest request(
      String fulfilmentType,
      String line1,
      String city,
      String postalCode,
      String recipientName,
      String recipientPhone) {
    return new PlaceOrderRequest(
        STORE.toString(),
        null,
        "ONLINE",
        fulfilmentType,
        List.of(
            new OrderItemRequest(
                VARIANT.toString(), BigDecimal.ONE, BigDecimal.TEN, null, null, null)),
        null,
        null,
        null,
        "USD",
        null,
        null, // couponCodes
        null,
        null,
        null,
        line1,
        null,
        city,
        postalCode,
        recipientName,
        recipientPhone,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void deliveryWithoutAddressIsRejected() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.placeOrder(request("DELIVERY", null, null, null, null, null), ctx, null));
    assertEquals("ORDER_DELIVERY_ADDRESS_REQUIRED", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void deliveryWithPartialAddressIsRejected() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.placeOrder(
                    request("DELIVERY", "221B Baker St", "London", null, "Jane Doe", "555-0100"),
                    ctx,
                    null));
    assertEquals("ORDER_DELIVERY_ADDRESS_REQUIRED", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void deliveryWithFullAddressIsPersisted() {
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList()))
        .thenAnswer(inv -> inv.getArgument(0));

    Order order =
        svc.placeOrder(
            request("DELIVERY", "221B Baker St", "London", "NW1 6XE", "Jane Doe", "555-0100"),
            ctx,
            null);

    assertEquals("221B Baker St", order.deliveryLine1());
    assertEquals("London", order.deliveryCity());
    assertEquals("NW1 6XE", order.deliveryPostalCode());
    assertEquals("Jane Doe", order.deliveryRecipientName());
    assertEquals("555-0100", order.deliveryRecipientPhone());
  }

  @Test
  void pickupIgnoresAnySuppliedAddressFields() {
    when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList()))
        .thenAnswer(inv -> inv.getArgument(0));

    // A pickup order should never persist delivery details even if the client sends some
    // (e.g. stale client state from switching fulfilment type back and forth in the UI).
    Order order =
        svc.placeOrder(
            request("PICKUP", "221B Baker St", "London", "NW1 6XE", "Jane Doe", "555-0100"),
            ctx,
            null);

    assertEquals(null, order.deliveryLine1());
    assertEquals(null, order.deliveryCity());
  }
}
