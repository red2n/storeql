package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.dto.Dtos.RecordRefundRequest;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Who may write a refund in the books against a tender ({@code POST
 * /payments/by-order/{orderId}/refunds}): a manager held to stores refunds only what was taken at
 * one of them, as they write only at their stores everywhere else; one held to none refunds
 * anywhere in the business. A cash refund lowers the expected cash of the store the tender was
 * taken at and moves that store's order, so it is that store's to make.
 */
class RefundStoreGuardTest {

  private final UUID tenant = Ids.newId();
  private final UUID order = Ids.newId();
  private final UUID here = Ids.newId();
  private final UUID elsewhere = Ids.newId();

  private static TenantContext heldTo(UUID tenantId, UUID... stores) {
    TenantContext ctx = mock(TenantContext.class);
    when(ctx.requireTenantId()).thenReturn(tenantId);
    when(ctx.storeIds()).thenReturn(Set.of(stores));
    return ctx;
  }

  /** The guard the service hands the repository for this caller, and the refund it is for. */
  private record Handed(PaymentRepository.StoreGuard guard, RefundTender refund) {}

  private Handed handedFor(TenantContext ctx) {
    PaymentService svc = new PaymentService();
    svc.profiles = TenantProfiles.forTest(id -> Optional.empty(), Clock.systemUTC());
    PaymentRepository repo = mock(PaymentRepository.class);
    when(repo.createRefundGuarded(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    svc.repo = repo;

    svc.recordRefund(
        ctx,
        order,
        new RecordRefundRequest(
            Ids.newId().toString(), new BigDecimal("5.00"), "CASH", null, null, "damaged", null),
        Ids.newId().toString());

    ArgumentCaptor<RefundTender> refund = ArgumentCaptor.forClass(RefundTender.class);
    ArgumentCaptor<PaymentRepository.StoreGuard> guard =
        ArgumentCaptor.forClass(PaymentRepository.StoreGuard.class);
    verify(repo).createRefundGuarded(refund.capture(), any(), guard.capture(), any());
    return new Handed(guard.getValue(), refund.getValue());
  }

  private static void assertDenied(PaymentRepository.StoreGuard guard, UUID store) {
    ApiException e = assertThrows(ApiException.class, () -> guard.requireMayActAt(store));
    assertEquals(403, e.status());
    assertEquals("STORE_ACCESS_DENIED", e.code());
  }

  @Test
  @DisplayName("A caller held to no store refunds a tender taken anywhere in the business")
  void heldToNoneRefundsAnywhere() {
    PaymentRepository.StoreGuard guard = handedFor(heldTo(tenant)).guard();

    assertDoesNotThrow(() -> guard.requireMayActAt(here));
    assertDoesNotThrow(() -> guard.requireMayActAt(elsewhere));
    assertDoesNotThrow(() -> guard.requireMayActAt(null));
  }

  @Test
  @DisplayName(
      "A manager held to a store refunds what was taken there, and nothing taken elsewhere")
  void heldToAStoreRefundsOnlyThere() {
    PaymentRepository.StoreGuard guard = handedFor(heldTo(tenant, here)).guard();

    assertDoesNotThrow(() -> guard.requireMayActAt(here));
    assertDenied(guard, elsewhere);
  }

  @Test
  @DisplayName("Held to several stores, any of them; a third is refused")
  void heldToSeveral() {
    UUID third = Ids.newId();
    PaymentRepository.StoreGuard guard = handedFor(heldTo(tenant, here, elsewhere)).guard();

    assertDoesNotThrow(() -> guard.requireMayActAt(here));
    assertDoesNotThrow(() -> guard.requireMayActAt(elsewhere));
    assertDenied(guard, third);
  }

  @Test
  @DisplayName(
      "A tender taken at no store is the whole business's: a manager held to stores does not"
          + " refund it")
  void aTenderWithNoStoreIsTheWholeBusinesss() {
    assertDenied(handedFor(heldTo(tenant, here)).guard(), null);
  }

  @Test
  @DisplayName("The refund is written for the caller's own business, whatever the request names")
  void theRefundIsTheCallersBusinesss() {
    assertEquals(tenant, handedFor(heldTo(tenant, here)).refund().tenantId());
  }
}
