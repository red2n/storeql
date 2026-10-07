package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.ParkSaleRequest;
import com.storeql.order.dto.Dtos.ParkedSaleItemRequest;
import com.storeql.order.dto.Dtos.ParkedSaleItemResponse;
import com.storeql.order.repo.ParkedSaleRepository;
import com.storeql.service.TenantProfiles;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Parking a basket: its quantities are judged before anything about the caller or the business is
 * asked — a malformed one is 400 whoever parks it and whether or not tenant-svc answers — and a
 * label's finer weight is kept at the gram below, as a till sale's is.
 */
@ExtendWith(MockitoExtension.class)
class ParkedSaleServiceTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CASHIER = Ids.newId();
  private static final UUID STORE = Ids.newId();

  @Mock ParkedSaleRepository repo;
  @Mock TenantStatusRepository tenantStatusRepo;
  @Mock TenantProfiles profiles;
  @Mock TenantContext ctx;

  private ParkedSaleService svc;

  @BeforeEach
  void setUp() {
    svc = new ParkedSaleService();
    svc.repo = repo;
    svc.tenantStatusRepo = tenantStatusRepo;
    svc.profiles = profiles;
  }

  private static ParkSaleRequest basket(String... qty) {
    List<ParkedSaleItemRequest> lines =
        java.util.Arrays.stream(qty)
            .map(
                q ->
                    new ParkedSaleItemRequest(
                        Ids.newId().toString(),
                        new BigDecimal(q),
                        new BigDecimal("12.99"),
                        null,
                        null,
                        null))
            .toList();
    return new ParkSaleRequest(STORE.toString(), null, null, lines, null);
  }

  private static void validationFailed(ApiException e, String field) {
    assertEquals(400, e.status());
    assertEquals("VALIDATION_FAILED", e.code());
    assertEquals(true, e.details().get(0).startsWith(field + ":"), e.details().toString());
  }

  /** A cashier held to another store gets the 400 their basket earns, not a 403 over the store. */
  @Test
  void aMalformedQuantityIsRefusedBeforeTheStoreIsChecked() {
    lenient()
        .doThrow(ApiException.forbidden("STORE_ACCESS_DENIED", "not yours"))
        .when(ctx)
        .requireStoreAccess(any());

    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.park(TENANT, CASHIER, basket("1", "0.3755123"), ctx));

    validationFailed(e, "items[1].qty");
    verify(ctx, never()).requireStoreAccess(any());
    verifyNoInteractions(repo, tenantStatusRepo, profiles);
  }

  /** With tenant-svc unreachable a malformed quantity is still 400, not the outage's 503. */
  @Test
  void aMalformedQuantityIsRefusedBeforeTheCurrencyIsAskedFor() {
    lenient().when(tenantStatusRepo.findCurrency(TENANT)).thenReturn(Optional.empty());
    lenient()
        .when(profiles.requireCurrency(TENANT))
        .thenThrow(new ApiException(503, "TENANT_PROFILE_UNAVAILABLE", "down", List.of()));

    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.park(TENANT, CASHIER, basket("0.0004", "1"), ctx));

    validationFailed(e, "items[0].qty");
    verifyNoInteractions(repo, tenantStatusRepo, profiles);
  }

  /** The store is still checked before anything is written, once the basket is well formed. */
  @Test
  void aWellFormedBasketAtAnotherStoreIsStill403() {
    lenient().when(tenantStatusRepo.findCurrency(TENANT)).thenReturn(Optional.of("GBP"));
    org.mockito.Mockito.doThrow(ApiException.forbidden("STORE_ACCESS_DENIED", "not yours"))
        .when(ctx)
        .requireStoreAccess(STORE);

    ApiException e =
        assertThrows(ApiException.class, () -> svc.park(TENANT, CASHIER, basket("1"), ctx));

    assertEquals(403, e.status());
    assertEquals("STORE_ACCESS_DENIED", e.code());
    verifyNoInteractions(repo);
  }

  /**
   * A pack labelled 0.37512 kg (GS1 AI 3105) is parked at 0.375 kg, the gram below, and valued
   * there: 0.375 × 12.99 = 4.87125, £4.87 — what the resumed sale is charged.
   */
  @Test
  void aLabelWeightIsParkedAtTheGramBelow() {
    when(tenantStatusRepo.findCurrency(TENANT)).thenReturn(Optional.of("GBP"));
    when(repo.park(
            eq(TENANT),
            any(),
            eq(CASHIER),
            eq(STORE),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyList()))
        .thenReturn(null);

    svc.park(TENANT, CASHIER, basket("0.37512"), ctx);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ParkedSaleItemResponse>> lines = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<BigDecimal> subtotal = ArgumentCaptor.forClass(BigDecimal.class);
    verify(repo)
        .park(
            eq(TENANT),
            any(),
            eq(CASHIER),
            eq(STORE),
            any(),
            any(),
            subtotal.capture(),
            any(),
            any(),
            lines.capture());
    assertEquals(new BigDecimal("0.375"), lines.getValue().get(0).qty());
    assertEquals(new BigDecimal("4.87"), lines.getValue().get(0).lineTotal());
    assertEquals(new BigDecimal("4.87"), subtotal.getValue());
  }
}
