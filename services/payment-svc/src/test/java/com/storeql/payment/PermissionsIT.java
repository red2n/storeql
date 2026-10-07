package com.storeql.payment;

import com.storeql.test.PermissionGate;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Granular permissions (20.10) at this service's decision points, driven by {@link PermissionGate}:
 * the tier gate by path still admits a MANAGER; the permission check then refuses one whose custom
 * role was narrowed out of the decision, with the permission named, before any argument is looked
 * at; a token carrying no claim is judged by the tier's defaults as before.
 */
@HelidonTest
class PermissionsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String USER = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String ID = "01a090ae-611e-703c-a378-a4972ea461c8";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private PermissionGate gate() {
    return new PermissionGate(target, T, USER);
  }

  @Test
  @DisplayName("Refunds need sales.refund; the till's money needs till.manage")
  void paymentDecisionsAreGated() {
    gate()
        .assertGated(
            "POST",
            "/payments/by-order/" + ID + "/refunds",
            "{\"paymentId\":\""
                + ID
                + "\",\"amount\":1.00,\"method\":\"CASH\",\"reference\":\"x\"}",
            "sales.refund");
    String till = "/admin/cash/till-sessions/" + ID;
    gate().assertGated("POST", till + "/close", "{\"countedCash\":0}", "till.manage");
    gate().assertGated("POST", till + "/drops", "{\"amount\":5.00}", "till.manage");
    gate().assertGated("GET", till + "/x-report", null, "till.manage");
    gate().assertGated("GET", "/admin/cash/movements?tillSessionId=" + ID, null, "till.manage");
  }

  @Test
  @DisplayName(
      "Recording, answering, accepting and resolving a chargeback need sales.refund, like a"
          + " refund; reading the register stays with management's tier")
  void disputeDecisionsAreGated() {
    String base = "/admin/disputes";
    gate()
        .assertGated(
            "POST",
            base,
            "{\"paymentId\":\""
                + ID
                + "\",\"reason\":\"FRAUDULENT\",\"caseReference\":\"C-1\","
                + "\"evidenceDueBy\":\"2999-01-01T00:00:00Z\"}",
            "sales.refund");
    gate().assertGated("POST", base + "/" + ID + "/evidence", "{\"notes\":\"x\"}", "sales.refund");
    gate().assertGated("POST", base + "/" + ID + "/accept", "{}", "sales.refund");
    gate()
        .assertGated("POST", base + "/" + ID + "/resolve", "{\"outcome\":\"WON\"}", "sales.refund");
    // The register is read, not decided: a manager narrowed out of refunds still sees it.
    try (var r = gate().send("GET", base, null, "MANAGER", "-")) {
      org.hamcrest.MatcherAssert.assertThat(r.getStatus(), org.hamcrest.Matchers.is(200));
    }
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.storeql.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
