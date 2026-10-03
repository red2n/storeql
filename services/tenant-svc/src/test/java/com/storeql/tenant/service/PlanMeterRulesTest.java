package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.tenant.domain.Meters.PlanMeter;
import com.storeql.tenant.dto.PlanDtos.PlanMeterRequest;
import com.storeql.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a plan includes of each meter, judged meter by meter (21.10). {@code PUT
 * /platform/plans/{id}/meters} has published {@code 400 PLAN_METER_INCLUDED_INVALID} for an
 * allowance below nothing since it shipped (API guide, {@code UsageIT}, k6 {@code
 * usage-metering-flow}); the request's {@code @Min(0)} made it unreachable as {@code
 * VALIDATION_FAILED} (2 Oct 2026), a changed refusal on {@code v1}. The rule is the service's alone
 * again, and proved here where the service is called as the route calls it.
 */
class PlanMeterRulesTest {

  private static ApiException refused(List<PlanMeterRequest> wanted, String code) {
    ApiException e = assertThrows(ApiException.class, () -> PlanService.meters(wanted));
    assertEquals(400, e.status(), e.getMessage());
    assertEquals(code, e.code(), e.getMessage());
    return e;
  }

  @Test
  @DisplayName("An allowance below nothing is PLAN_METER_INCLUDED_INVALID, however far below")
  void aNegativeAllowanceIsRefusedByItsOwnCode() {
    refused(List.of(new PlanMeterRequest("SMS", -1L, false)), "PLAN_METER_INCLUDED_INVALID");
    refused(
        List.of(new PlanMeterRequest("SMS", Long.MIN_VALUE, null)), "PLAN_METER_INCLUDED_INVALID");
    refused(
        List.of(new PlanMeterRequest("ORDERS", 5L, false), new PlanMeterRequest("sms", -1L, true)),
        "PLAN_METER_INCLUDED_INVALID");
  }

  @Test
  @DisplayName("Nothing included, and unlimited, are both allowances; a key is read in any case")
  void zeroAndUnlimitedAreAllowances() {
    assertEquals(
        List.of(new PlanMeter("ORDERS", 0L, false), new PlanMeter("SMS", null, false)),
        PlanService.meters(
            List.of(
                new PlanMeterRequest("orders", 0L, null),
                new PlanMeterRequest("SMS", null, false))));
    assertEquals(
        List.of(new PlanMeter("SMS", 0L, true)),
        PlanService.meters(List.of(new PlanMeterRequest("SMS", 0L, true))));
    assertEquals(List.of(), PlanService.meters(List.of()), "[] names no meter");
  }

  @Test
  @DisplayName("The other refusals keep their codes and their order")
  void theOtherRefusalsKeepTheirCodes() {
    refused(List.of(new PlanMeterRequest("API_CALLS", -1L, false)), "PLAN_METER_UNKNOWN");
    refused(
        List.of(new PlanMeterRequest("SMS", 1L, false), new PlanMeterRequest("sms", -1L, false)),
        "PLAN_METER_TWICE");
    refused(List.of(new PlanMeterRequest("ORDERS", -1L, true)), "PLAN_METER_NOT_REFUSABLE");
    refused(List.of(new PlanMeterRequest("SMS", null, true)), "PLAN_METER_HARD_UNLIMITED");
  }
}
