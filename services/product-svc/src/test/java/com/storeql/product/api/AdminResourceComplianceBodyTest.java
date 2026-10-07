package com.storeql.product.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.dto.Dtos.VariantComplianceRequest;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT …/variants/{variantId}/compliance} judges its body before anything else is asked (no
 * tenant context is wired here, so a refusal is proved to come from the boundary): a number that
 * its column {@code NUMERIC(18, 4)} cannot keep is a 400, never a database error.
 */
class AdminResourceComplianceBodyTest {

  private final AdminResource resource = new AdminResource();

  private static VariantComplianceRequest withNumbers(BigDecimal netContent, BigDecimal tare) {
    return new VariantComplianceRequest(
        null, null, null, null, null, "WEIGHT", netContent, "KG", tare, false, null, null, null);
  }

  private ApiException refused(VariantComplianceRequest req) {
    return assertThrows(ApiException.class, () -> resource.setCompliance(Ids.newId(), req));
  }

  @Test
  @DisplayName("A missing body is a 400")
  void aMissingBodyIsRefused() {
    assertThat(refused(null).status(), is(400));
  }

  @Test
  @DisplayName("A number past the column, or past the shared 32-digit walk, is VALIDATION_FAILED")
  void anAbsurdNumberIsRefused() {
    for (BigDecimal bad :
        new BigDecimal[] {
          new BigDecimal("100000000000000"), // 15 whole digits: past NUMERIC(18, 4)
          new BigDecimal("1.23456"), // a fifth decimal the column would round
          new BigDecimal("1" + "0".repeat(33)) // past the shared walk
        }) {
      ApiException e = refused(withNumbers(bad, null));
      assertThat(bad.toPlainString(), e.status(), is(400));
      assertThat(bad.toPlainString(), e.code(), is("VALIDATION_FAILED"));
      ApiException t = refused(withNumbers(new BigDecimal("1"), bad));
      assertThat(bad.toPlainString(), t.code(), is("VALIDATION_FAILED"));
    }
  }
}
