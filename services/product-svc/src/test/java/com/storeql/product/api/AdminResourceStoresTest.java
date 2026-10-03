package com.storeql.product.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.dto.Dtos.ProductStoresRequest;
import com.storeql.web.ApiException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code PUT /admin/products/{id}/stores} says about the ids in its body before any service is
 * asked: nothing here reaches the service or the tenant context, so a refusal is proved to come
 * from the boundary.
 *
 * <p>The ids were read with {@code UUID.fromString}, which takes {@code "1-1-1-1-1"} and every
 * version, and throws an {@code IllegalArgumentException} the platform answers as a {@code 500}.
 */
class AdminResourceStoresTest {

  private final AdminResource resource = new AdminResource();

  private ApiException refused(List<String> storeIds) {
    return assertThrows(
        ApiException.class,
        () -> resource.setProductStores(Ids.newId(), new ProductStoresRequest(storeIds)));
  }

  @Test
  @DisplayName("A store id that is not a UUIDv7 is a 400 INVALID_UUID naming the field")
  void aStoreIdThatIsNotAUuidV7IsInvalid() {
    for (String bad :
        new String[] {
          "not-an-id",
          "1-1-1-1-1",
          // A well-formed UUID of another version (4) names nothing StoreQL made.
          "123e4567-e89b-42d3-a456-426614174000",
          ""
        }) {
      ApiException e = refused(List.of(Ids.newId().toString(), bad));
      assertThat(bad, e.status(), is(400));
      assertThat(bad, e.code(), is("INVALID_UUID"));
      assertThat(bad, e.getMessage(), is("storeIds must be a UUIDv7"));
    }
  }

  @Test
  @DisplayName("A hole in the list is the platform's refusal, naming the place")
  void aHoleInTheListIsRefused() {
    ApiException e = refused(Arrays.asList(Ids.newId().toString(), null));

    assertThat(e.status(), is(400));
    assertThat(e.code(), is("VALIDATION_FAILED"));
    assertThat(e.details(), is(List.of("storeIds[1]: must not be null")));
  }
}
