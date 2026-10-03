package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.CreateLayawayRequest;
import com.storeql.order.dto.Dtos.IssueGiftCardRequest;
import com.storeql.order.dto.Dtos.LayawayItemRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Financial write paths must resolve the tenant via {@link TenantContext#requireTenantId()} (fail
 * 401 when absent), never the nullable {@link TenantContext#tenantId()} — which would let a
 * tenant-less request persist a null-tenant layaway / gift card.
 *
 * <p>The mock's {@code requireTenantId()} throws NO_TENANT (what the real context does with no
 * tenant). If these methods regressed to {@code tenantId()}, the mock would return null instead of
 * throwing and execution would fall through to the repository — so these tests fail loudly on that
 * regression.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTenantGuardTest {

  private static final UUID STORE = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  @Mock OrderRepository repo;
  @Mock TenantContext ctx;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
    when(ctx.requireTenantId())
        .thenThrow(ApiException.unauthorized("NO_TENANT", "No tenant in request context"));
  }

  @Test
  void createLayawayWithoutTenantIsRejectedBeforeAnyWrite() {
    var req =
        new CreateLayawayRequest(
            STORE.toString(),
            null,
            List.of(new LayawayItemRequest(VARIANT.toString(), BigDecimal.ONE, BigDecimal.TEN)),
            BigDecimal.ONE,
            "CASH",
            null,
            null);

    ApiException e = assertThrows(ApiException.class, () -> svc.createLayaway(req, ctx));
    assertEquals("NO_TENANT", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void issueGiftCardWithoutTenantIsRejectedBeforeAnyWrite() {
    var req =
        new IssueGiftCardRequest(
            STORE.toString(), BigDecimal.TEN, "USD", null, null, "GOODWILL", null);

    ApiException e =
        assertThrows(ApiException.class, () -> svc.issueGiftCard(req, Ids.newId().toString(), ctx));
    assertEquals("NO_TENANT", e.code());
    verifyNoInteractions(repo);
  }
}
