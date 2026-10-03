package com.storeql.product.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Product;
import com.storeql.product.service.ProductService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /admin/products/{id}/discontinue} asks for management itself (2 Oct 2026), as the
 * range and merchandising doors do, rather than leaning on the shared filter alone: below an owner
 * or a manager it is {@code 403 FORBIDDEN} and the service is never asked.
 */
class AdminResourceDiscontinueTest {

  private static final UUID TENANT = Ids.newId();

  /** The service, noting who reached it. */
  private static final class Lifecycle extends ProductService {
    final List<TenantContext> asked = new ArrayList<>();

    @Override
    public Product discontinueProduct(TenantContext ctx, UUID productId) {
      asked.add(ctx);
      Instant now = Instant.now();
      return new Product(
          productId,
          ctx.requireTenantId(),
          "Old cola",
          null,
          null,
          null,
          Product.STATUS_DISCONTINUED,
          true,
          true,
          now,
          now,
          null,
          now);
    }
  }

  private final Lifecycle service = new Lifecycle();

  private AdminResource as(String role, Set<UUID> heldTo) {
    AdminResource resource = new AdminResource();
    resource.service = service;
    resource.ctx = caller(role, heldTo);
    return resource;
  }

  /** A caller as the gateway describes one, through the setter the shared filter uses. */
  private static TenantContext caller(String role, Set<UUID> heldTo) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, TENANT, Ids.newId(), Set.of(role), heldTo, null, "req-1");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  @Test
  @DisplayName("Below management it is refused at the door, and the service is never asked")
  void belowManagementIsRefusedAtTheDoor() {
    UUID store = Ids.newId();
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      for (Set<UUID> heldTo : List.of(Set.<UUID>of(), Set.of(store))) {
        AdminResource resource = as(role, heldTo);
        ApiException e =
            assertThrows(ApiException.class, () -> resource.discontinueProduct(Ids.newId()));
        assertThat(role, e.status(), is(403));
        assertThat(role, e.code(), is("FORBIDDEN"));
      }
    }
    assertThat(service.asked, is(empty()));
  }

  @Test
  @DisplayName("An owner or a manager reaches the service, which judges whether they are held")
  void managementReachesTheService() {
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      var answer = as(role, Set.of()).discontinueProduct(Ids.newId());
      assertThat(answer.data().status(), is(Product.STATUS_DISCONTINUED));
    }
    assertThat(service.asked.size(), is(2));
  }
}
