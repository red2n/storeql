package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.TillSession;
import com.storeql.payment.dto.Dtos.TillSessionResponse;
import com.storeql.payment.repo.CashManagementRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The till a cashier is standing at: their own open session at the store they name, looked up only
 * once the store is known to be theirs to act at.
 */
@ExtendWith(MockitoExtension.class)
class CurrentTillSessionTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID CASHIER = Ids.newId();

  @Mock CashManagementRepository repo;
  @InjectMocks CashManagementService svc;

  @Test
  @DisplayName("A store the caller does not keep is refused before anything is looked up")
  void storeAccessComesFirst() {
    TenantContext ctx = mock(TenantContext.class);
    doThrow(ApiException.forbidden("STORE_ACCESS_DENIED", "Caller is not assigned to this store"))
        .when(ctx)
        .requireStoreAccess(STORE);

    ApiException e =
        assertThrows(ApiException.class, () -> svc.currentSession(TENANT, STORE, CASHIER, ctx));

    assertEquals(403, e.status());
    assertEquals("STORE_ACCESS_DENIED", e.code());
    verify(repo, never()).findOpenSession(any(), any(), any());
  }

  @Test
  @DisplayName("No open till of the caller's at the store is 404 TILL_SESSION_NOT_OPEN")
  void noOpenTillIsNotFound() {
    when(repo.findOpenSession(TENANT, STORE, CASHIER)).thenReturn(Optional.empty());

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.currentSession(TENANT, STORE, CASHIER, mock(TenantContext.class)));

    assertEquals(404, e.status());
    assertEquals("TILL_SESSION_NOT_OPEN", e.code());
  }

  @Test
  @DisplayName("The open till is answered as it is stored, asked for by tenant, store and opener")
  void openTillIsAnswered() {
    Instant opened = Instant.parse("2026-09-25T08:00:00Z");
    TillSession open =
        new TillSession(
            Ids.newId(),
            TENANT,
            STORE,
            CASHIER,
            new BigDecimal("150.0000"),
            TillSession.STATUS_OPEN,
            null,
            null,
            opened,
            null);
    when(repo.findOpenSession(TENANT, STORE, CASHIER)).thenReturn(Optional.of(open));

    TillSessionResponse r = svc.currentSession(TENANT, STORE, CASHIER, mock(TenantContext.class));

    assertEquals(open.id(), r.id());
    assertEquals(STORE, r.storeId());
    assertEquals(CASHIER, r.openedBy());
    assertEquals(new BigDecimal("150.0000"), r.floatAmount());
    assertEquals("OPEN", r.status());
    assertEquals(opened, r.openedAt());
    assertNull(r.closedAt());
  }
}
