package com.storeql.product.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Product;
import com.storeql.product.domain.Domain.Variant;
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
 * {@code DELETE /admin/products/{id}/variants/{variantId}} and {@code POST
 * /admin/products/{id}/launch} ask for management themselves (3 Oct 2026), as the discontinue, the
 * delist and the reinstate beside them do: below an owner or a manager — the platform operator
 * included, whom the shared filter's management tier lets through — it is {@code 403 FORBIDDEN} and
 * the service is never asked. The service then judges whether the caller is held to stores.
 */
class AdminResourceLaunchAndVariantDelistTest {

  private static final UUID TENANT = Ids.newId();

  /** The service, noting who reached it and for what. */
  private static final class Lifecycle extends ProductService {
    final List<String> asked = new ArrayList<>();

    @Override
    public Variant delistVariant(TenantContext ctx, UUID productId, UUID variantId) {
      asked.add("variant-delist");
      Instant now = Instant.now();
      return new Variant(
          variantId,
          ctx.requireTenantId(),
          productId,
          "COLA-330",
          null,
          null,
          null,
          "EA",
          Variant.STATUS_INACTIVE,
          now,
          now);
    }

    @Override
    public Product launchProduct(TenantContext ctx, UUID productId) {
      asked.add("launch");
      Instant now = Instant.now();
      return new Product(
          productId,
          ctx.requireTenantId(),
          "Spring cola",
          null,
          null,
          null,
          Product.STATUS_ACTIVE,
          true,
          true,
          now,
          now,
          null,
          null);
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
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER", "PLATFORM_ADMIN"}) {
      for (Set<UUID> heldTo : List.of(Set.<UUID>of(), Set.of(store))) {
        AdminResource resource = as(role, heldTo);
        ApiException delist =
            assertThrows(
                ApiException.class, () -> resource.delistVariant(Ids.newId(), Ids.newId()));
        assertThat(role, delist.status(), is(403));
        assertThat(role, delist.code(), is("FORBIDDEN"));
        ApiException launch =
            assertThrows(ApiException.class, () -> resource.launchProduct(Ids.newId()));
        assertThat(role, launch.status(), is(403));
        assertThat(role, launch.code(), is("FORBIDDEN"));
      }
    }
    assertThat(service.asked, is(empty()));
  }

  @Test
  @DisplayName("An owner or a manager reaches the service, which judges whether they are held")
  void managementReachesTheService() {
    UUID store = Ids.newId();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (Set<UUID> heldTo : List.of(Set.<UUID>of(), Set.of(store))) {
        AdminResource resource = as(role, heldTo);
        assertThat(
            resource.delistVariant(Ids.newId(), Ids.newId()).data().status(),
            is(Variant.STATUS_INACTIVE));
        assertThat(resource.launchProduct(Ids.newId()).data().status(), is(Product.STATUS_ACTIVE));
      }
    }
    assertThat(service.asked.size(), is(8));
  }
}
