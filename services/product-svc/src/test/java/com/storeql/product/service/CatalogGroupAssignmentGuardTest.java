package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Variant;
import com.storeql.product.dto.Dtos.AssignCatalogGroupRequest;
import com.storeql.product.repo.CatalogGroupRepository;
import com.storeql.product.repo.ProductRepository;
import com.storeql.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A variant is assigned to a catalog group only by naming the group. The request's own validation
 * answers a missing {@code groupId} first over HTTP; this holds the service to the same rule for
 * any caller that does not come through that boundary, and shows nothing is assigned.
 */
class CatalogGroupAssignmentGuardTest {

  private static final UUID TENANT = Ids.newId();

  private final List<String> writes = new ArrayList<>();

  private final ProductService svc = new ProductService();

  CatalogGroupAssignmentGuardTest() {
    svc.repo =
        new ProductRepository() {
          @Override
          public Optional<Variant> findVariant(UUID tenantId, UUID variantId) {
            return Optional.of(
                new Variant(
                    variantId,
                    tenantId,
                    Ids.newId(),
                    "SKU-1",
                    null,
                    null,
                    "{}",
                    "EA",
                    Variant.STATUS_ACTIVE,
                    Instant.now(),
                    Instant.now()));
          }
        };
    svc.catalogGroupRepo =
        new CatalogGroupRepository() {
          @Override
          public com.storeql.product.domain.Domain.VariantCatalogAssignment createCatalogAssignment(
              com.storeql.product.domain.Domain.VariantCatalogAssignment a) {
            writes.add("createCatalogAssignment");
            return a;
          }
        };
  }

  @Test
  @DisplayName("A variant is not assigned to a catalog group that is not named")
  void aVariantIsNotAssignedToNoGroup() {
    for (String groupId : new String[] {null, "", "  "}) {
      ApiException refused =
          assertThrows(
              ApiException.class,
              () ->
                  svc.assignCatalogGroup(
                      TENANT, Ids.newId(), new AssignCatalogGroupRequest(groupId, null)));
      assertThat(refused.status(), is(400));
      assertThat(refused.code(), is("INVALID_GROUP_ID"));
    }
    assertThat("nothing was assigned", writes.isEmpty(), is(true));
  }
}
