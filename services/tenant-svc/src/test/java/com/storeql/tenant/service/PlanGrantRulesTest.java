package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.tenant.domain.Plans.Grant;
import com.storeql.tenant.dto.PlanDtos.GrantRequest;
import com.storeql.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a plan includes, judged grant by grant before anything is written (21.8). {@code PUT
 * /platform/plans/{id}/includes} with one key named twice reached {@code plan_entitlements}'
 * primary key {@code (plan_id, key)} in one batch and answered {@code 500 DB_ERROR} where every
 * other bad body on the route is a 400 (2 Oct 2026). A key is judged as the platform reads it
 * ({@code Plans.entitlement} strips it), so a padded copy is the same key named twice.
 */
class PlanGrantRulesTest {

  private static ApiException refused(List<GrantRequest> wanted, String code) {
    ApiException e = assertThrows(ApiException.class, () -> PlanService.grants(wanted));
    assertEquals(400, e.status(), e.getMessage());
    assertEquals(code, e.code(), e.getMessage());
    return e;
  }

  @Test
  @DisplayName("A key named twice is PLAN_ENTITLEMENT_TWICE, padded or not, limit or feature")
  void aKeyNamedTwiceIsRefusedByItsOwnCode() {
    ApiException e =
        refused(
            List.of(
                new GrantRequest("stores.max", 1L, null), new GrantRequest("stores.max", 2L, null)),
            "PLAN_ENTITLEMENT_TWICE");
    assertEquals("stores.max is named twice", e.getMessage());
    refused(
        List.of(
            new GrantRequest("stores.max", 1L, null), new GrantRequest(" stores.max ", 2L, null)),
        "PLAN_ENTITLEMENT_TWICE");
    refused(
        List.of(
            new GrantRequest("feature.storefront", null, true),
            new GrantRequest("staff.max", 3L, null),
            new GrantRequest("feature.storefront\t", null, false)),
        "PLAN_ENTITLEMENT_TWICE");
    refused(
        List.of(
            new GrantRequest("stores.max", null, null), new GrantRequest("stores.max", null, null)),
        "PLAN_ENTITLEMENT_TWICE");
  }

  @Test
  @DisplayName("Each key once is taken as the platform names it; [] names nothing")
  void eachKeyOnceIsTaken() {
    assertEquals(
        List.of(
            new Grant("stores.max", 2L, null),
            new Grant("staff.max", null, null),
            new Grant("feature.storefront", null, true),
            new Grant("products.max", 0L, null)),
        PlanService.grants(
            List.of(
                new GrantRequest(" stores.max", 2L, null),
                new GrantRequest("staff.max", null, null),
                new GrantRequest("feature.storefront", null, true),
                new GrantRequest("products.max", 0L, null))));
    assertEquals(
        List.of(new Grant("feature.storefront", null, false)),
        PlanService.grants(List.of(new GrantRequest("feature.storefront", null, null))),
        "a feature left unsaid is not included");
    assertEquals(List.of(), PlanService.grants(List.of()), "[] names nothing");
  }

  @Test
  @DisplayName("The other refusals keep their codes")
  void theOtherRefusalsKeepTheirCodes() {
    refused(List.of(new GrantRequest("support.priority", null, true)), "PLAN_ENTITLEMENT_UNKNOWN");
    refused(List.of(new GrantRequest("sms.per-month", 10L, null)), "PLAN_ENTITLEMENT_NOT_ENFORCED");
    refused(List.of(new GrantRequest("stores.max", null, true)), "PLAN_ENTITLEMENT_SHAPE");
    refused(List.of(new GrantRequest("feature.storefront", 1L, null)), "PLAN_ENTITLEMENT_SHAPE");
    // The first copy is judged on its own before the second is seen as a repeat.
    refused(
        List.of(
            new GrantRequest("stores.max", null, true), new GrantRequest("stores.max", 1L, null)),
        "PLAN_ENTITLEMENT_SHAPE");
  }
}
