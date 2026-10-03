package com.storeql.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Domain.ExpiryRun;
import com.storeql.customer.dto.Dtos.SetLoyaltyProgrammeRequest;
import com.storeql.customer.dto.Dtos.TierRequest;
import com.storeql.customer.repo.LoyaltyProgrammeRepository;
import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The loyalty programme covers every store's customers, so what changes it for the whole business —
 * setting it, or running its expiry and re-tiering sweep by hand — is refused to a caller held to
 * stores before anything is read or written; a caller held to none sweeps its own business alone.
 */
@ExtendWith(MockitoExtension.class)
class LoyaltyProgrammeServiceTest {

  private static final UUID TENANT = Ids.newId();

  @Mock LoyaltyProgrammeRepository repo;
  @Mock TenantContext ctx;
  private LoyaltyProgrammeService service;

  @BeforeEach
  void setUp() {
    service = new LoyaltyProgrammeService();
    service.repo = repo;
  }

  @Test
  @DisplayName("A caller held to a store cannot run the sweep by hand: 403, nothing touched")
  void aStoreHeldCallerCannotRunTheSweep() {
    when(ctx.storeIds()).thenReturn(Set.of(Ids.newId()));

    ApiException e = assertThrows(ApiException.class, () -> service.sweep(ctx));

    assertEquals(403, e.status());
    assertEquals("BUSINESS_WIDE_ONLY", e.code());
    // Refused before anything runs: no business is read, nothing expires, nobody is re-tiered.
    verifyNoInteractions(repo);
  }

  @Test
  @DisplayName("A caller held to no store sweeps its own business, and only its own")
  void aBusinessWideCallerSweepsItsOwnBusinessAlone() {
    when(ctx.storeIds()).thenReturn(Set.of());
    when(ctx.requireTenantId()).thenReturn(TENANT);
    when(repo.sweep(eq(TENANT), any(), any(), any()))
        .thenReturn(new ExpiryRun(1, new BigDecimal("30.00"), 2));

    ExpiryRun run = service.sweep(ctx);

    assertEquals(1, run.customers());
    assertEquals(0, new BigDecimal("30").compareTo(run.points()));
    assertEquals(2, run.retiered());
    verify(repo).sweep(eq(TENANT), any(), any(), any());
    // The hand-run sweep is never the hourly one over every business.
    verify(repo, never()).tenantsWithRules();
  }

  @Test
  @DisplayName("Setting the programme takes the same refusal, before the shape is judged")
  void aStoreHeldCallerCannotSetTheProgrammeEither() {
    when(ctx.storeIds()).thenReturn(Set.of(Ids.newId()));
    var req =
        new SetLoyaltyProgrammeRequest(
            12,
            12,
            List.of(new TierRequest("BRONZE", BigDecimal.ZERO, BigDecimal.ONE)),
            "the autumn scheme");

    ApiException e = assertThrows(ApiException.class, () -> service.set(ctx, req));

    assertEquals(403, e.status());
    assertEquals("BUSINESS_WIDE_ONLY", e.code());
    verifyNoInteractions(repo);
  }
}
