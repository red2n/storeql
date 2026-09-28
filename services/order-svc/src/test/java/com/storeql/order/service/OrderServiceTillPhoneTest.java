package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A phone at the till (intent/phone-at-the-till.md), as a sale is placed: a Required store refuses
 * a till sale with neither a number nor a customer; a number is read in the store's own country,
 * then the business's, and kept in international form; one that is no phone where the business
 * trades is refused at the till and kept as typed online; and a store or a country that cannot be
 * read never refuses anything.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTillPhoneTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID SHOP = Ids.newId();
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
    svc.windows = windows;
    svc.jurisdictions = org.mockito.Mockito.mock(com.storeql.service.Jurisdictions.class);
    lenient().when(profiles.requireCurrency(TENANT)).thenReturn("INR");
    lenient().when(profiles.requireCountry(TENANT)).thenReturn("IN");
    when(ctx.requireTenantId()).thenReturn(TENANT);
    when(tenantStatusRepo.isActive(any())).thenReturn(true);
    lenient().when(storeStatusRepo.isActive(any(), any())).thenReturn(true);
    lenient().when(tenants.resolveFulfilment(any(), any())).thenReturn(Optional.empty());
    lenient().when(config.pricingEnforce()).thenReturn(false);
    lenient()
        .when(repo.createOrder(any(), anyList(), any(), any(), anyList(), anyList()))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  /** The shop, in {@code country}, whose till asks {@code tillPhone}. */
  private void shop(String country, String tillPhone) {
    lenient()
        .when(profiles.stores(TENANT, SHOP))
        .thenReturn(
            new TenantProfiles.Stores(
                Set.of(SHOP),
                Map.of(SHOP, country),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                tillPhone == null ? Map.of() : Map.of(SHOP, tillPhone)));
  }

  private static PlaceOrderRequest sale(String channel, UUID customerId, String phone) {
    return new PlaceOrderRequest(
        SHOP.toString(),
        customerId == null ? null : customerId.toString(),
        channel,
        "POS".equals(channel) ? "INSTORE" : "PICKUP",
        List.of(
            new OrderItemRequest(
                VARIANT.toString(), BigDecimal.ONE, BigDecimal.TEN, null, null, null)),
        null,
        null,
        null,
        "INR",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        phone,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName("A Required store refuses a till sale with neither a number nor a customer")
  void aRequiredStoreRefusesATillSaleWithNeither() {
    shop("IN", "REQUIRED");
    ApiException e =
        assertThrows(ApiException.class, () -> svc.placeOrder(sale("POS", null, null), ctx, null));
    assertEquals("ORDER_CONTACT_PHONE_REQUIRED", e.code());
    assertEquals(409, e.status());
    assertThrows(ApiException.class, () -> svc.placeOrder(sale("POS", null, "  "), ctx, null));
    verifyNoInteractions(repo);
  }

  @Test
  @DisplayName("A Required store takes a number, or a customer instead of one")
  void aRequiredStoreTakesANumberOrACustomer() {
    shop("IN", "REQUIRED");
    Order named = svc.placeOrder(sale("POS", Ids.newId(), null), ctx, null);
    assertNull(named.contactPhone());
    Order given = svc.placeOrder(sale("POS", null, "98860 21001"), ctx, null);
    assertEquals("98860 21001", given.contactPhone(), "kept as it was typed");
    assertEquals("+919886021001", given.contactPhoneE164(), "and in international form");
  }

  @Test
  @DisplayName("An Optional or Don't-ask store takes a till sale with no number")
  void optionalAndOffTakeNoNumber() {
    shop("IN", "OPTIONAL");
    assertNull(svc.placeOrder(sale("POS", null, null), ctx, null).contactPhoneE164());
    shop("IN", "OFF");
    assertNull(svc.placeOrder(sale("POS", null, null), ctx, null).contactPhone());
  }

  @Test
  @DisplayName("A number that is no phone where the business trades: refused at the till only")
  void aNumberThatIsNoPhoneIsRefusedAtTheTillOnly() {
    shop("IN", "OPTIONAL");
    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.placeOrder(sale("POS", null, "12345"), ctx, null));
    assertEquals("ORDER_CONTACT_PHONE_INVALID", e.code());
    assertEquals(400, e.status());
    Order online = svc.placeOrder(sale("ONLINE", null, "12345"), ctx, null);
    assertEquals("12345", online.contactPhone(), "online it is kept as typed");
    assertNull(online.contactPhoneE164());
  }

  @Test
  @DisplayName("An online number is read the same way")
  void anOnlineNumberIsReadTheSameWay() {
    shop("IN", "OPTIONAL");
    assertEquals(
        "+919845012345",
        svc.placeOrder(sale("ONLINE", null, "98450 12345"), ctx, null).contactPhoneE164());
  }

  @Test
  @DisplayName("The same digits read as the country of the shop the sale is made at")
  void theShopsOwnCountryComesFirst() {
    when(profiles.requireCountry(TENANT)).thenReturn("FR");
    shop("NL", "OPTIONAL");
    assertEquals(
        "+31612345678",
        svc.placeOrder(sale("POS", null, "06 12 34 56 78"), ctx, null).contactPhoneE164(),
        "a French business's Dutch shop reads a Dutch mobile");
  }

  @Test
  @DisplayName("When the store and the countries cannot be read, nothing is refused")
  void whatCannotBeReadRefusesNothing() {
    when(profiles.stores(TENANT, SHOP))
        .thenThrow(new ApiException(503, "TENANT_STORES_UNAVAILABLE", "down", List.of()));
    when(profiles.requireCountry(TENANT))
        .thenThrow(new ApiException(503, "TENANT_PROFILE_UNAVAILABLE", "down", List.of()));
    Order bare = svc.placeOrder(sale("POS", null, null), ctx, null);
    assertNull(bare.contactPhone(), "the choice counts as Optional");
    Order typed = svc.placeOrder(sale("POS", null, "98860 21001"), ctx, null);
    assertEquals("98860 21001", typed.contactPhone(), "kept as typed");
    assertNull(typed.contactPhoneE164(), "not read, and not refused for want of a country");
  }
}
