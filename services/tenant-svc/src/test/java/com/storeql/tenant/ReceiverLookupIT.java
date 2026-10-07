package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Which business a network's delivery lands with (07.13, the transport seam): the platform-wide
 * lookup purchase-svc asks before a delivered e-invoice is read into anyone's inbox.
 */
@HelidonTest
class ReceiverLookupIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private String business(String name, String vat, String scheme, String id) {
    String tenant = TenantOnboarding.onboard(target, name, "GB", "GBP");
    String json =
        "{\"businessName\":\""
            + name
            + "\",\"vatNumber\":\""
            + vat
            + "\""
            + (scheme == null
                ? ""
                : ",\"einvoiceScheme\":\"" + scheme + "\",\"einvoiceId\":\"" + id + "\"")
            + "}";
    Response r =
        target
            .path("/admin/tenant")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", "OWNER")
            .put(Entity.entity(json, MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    return tenant;
  }

  private Response lookup(String query, String role) {
    return WebTargets.at(target, "/platform/tenants/by-einvoice-address" + query)
        .request(MediaType.APPLICATION_JSON)
        .header("X-User-Id", Ids.newId().toString())
        .header("X-Roles", role)
        .get();
  }

  private String found(String query) {
    Response r = lookup(query, "PLATFORM_ADMIN");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  private void refused(String query, String role, int status, String code) {
    Response r = lookup(query, role);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, containsString("\"code\":\"" + code + "\""));
  }

  private int suspend(String tenant) {
    try {
      var req =
          java.net.http.HttpRequest.newBuilder(
                  target.getUri().resolve("/platform/tenants/" + tenant + "/status"))
              .header("Content-Type", MediaType.APPLICATION_JSON)
              .header("X-User-Id", Ids.newId().toString())
              .header("X-Roles", "PLATFORM_ADMIN")
              .method(
                  "PATCH",
                  java.net.http.HttpRequest.BodyPublishers.ofString(
                      "{\"status\":\"INACTIVE\",\"reason\":\"test suspension\"}"))
              .build();
      return java.net.http.HttpClient.newHttpClient()
          .send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
          .statusCode();
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void theBusinessHoldingAnAddressIsFoundByItAndByItsVatNumber() {
    String run = String.format("%09d", System.nanoTime() % 1_000_000_000L);
    String gln = "57900" + run.substring(0, 7);
    gln = gln + checkDigit(gln);
    String vat = "GB" + run;
    String harbour = business("Harbour " + run, vat, "0088", gln);
    business(
        "Unrelated " + run, "GB" + (100000000 + Integer.parseInt(run.substring(0, 6))), null, null);

    String byAddress = found("?scheme=0088&id=" + gln);
    assertThat(byAddress, containsString("\"id\":\"" + harbour + "\""));
    String byVat =
        found(
            "?vatNumber="
                + vat.toLowerCase(java.util.Locale.ROOT).substring(0, 2)
                + vat.substring(2, 5)
                + "%20"
                + vat.substring(5));
    assertThat("spaces and case aside", byVat, containsString("\"id\":\"" + harbour + "\""));
  }

  @Test
  void whatNobodyHoldsHalfAnAddressAndNothingNamedAreRefused() {
    refused("?scheme=0088&id=5790000000006", "PLATFORM_ADMIN", 404, "TENANT_NOT_FOUND");
    refused("?vatNumber=GB000000000", "PLATFORM_ADMIN", 404, "TENANT_NOT_FOUND");
    refused("?scheme=0088", "PLATFORM_ADMIN", 400, "TENANT_EINVOICE_ADDRESS_INVALID");
    refused("?id=5790000000006", "PLATFORM_ADMIN", 400, "TENANT_EINVOICE_ADDRESS_INVALID");
    refused("", "PLATFORM_ADMIN", 400, "TENANT_RECEIVER_UNNAMED");
    refused("?vatNumber=%20", "PLATFORM_ADMIN", 400, "TENANT_RECEIVER_UNNAMED");
  }

  @Test
  void onlyThePlatformAsksAndASuspendedOrSharedBusinessReceivesNothing() {
    String run = String.format("%09d", System.nanoTime() % 1_000_000_000L);
    String vat = "GB" + (200000000 + Integer.parseInt(run.substring(0, 6)));
    String scheme = "9932";
    String first = business("First " + run, vat, scheme, vat);

    refused("?scheme=" + scheme + "&id=" + vat, "OWNER", 403, "FORBIDDEN");
    refused("?scheme=" + scheme + "&id=" + vat, "MANAGER", 403, "FORBIDDEN");
    assertThat(found("?scheme=" + scheme + "&id=" + vat), containsString(first));
    assertThat(
        "the case of an identifier does not matter",
        found("?scheme=" + scheme + "&id=" + vat.toLowerCase(java.util.Locale.ROOT)),
        containsString(first));

    // A second business claiming the same address makes it nobody's until one gives it up.
    String second = business("Second " + run, vat, scheme, vat);
    refused(
        "?scheme=" + scheme + "&id=" + vat,
        "PLATFORM_ADMIN",
        409,
        "TENANT_EINVOICE_ADDRESS_SHARED");
    refused("?vatNumber=" + vat, "PLATFORM_ADMIN", 409, "TENANT_EINVOICE_ADDRESS_SHARED");

    // Suspended, the second no longer receives, so the first does again.
    assertThat(suspend(second), is(200));
    assertThat(found("?scheme=" + scheme + "&id=" + vat), containsString(first));
    assertThat(suspend(first), is(200));
    refused("?scheme=" + scheme + "&id=" + vat, "PLATFORM_ADMIN", 404, "TENANT_NOT_FOUND");
  }

  /** GS1's check digit for a 12-digit GTIN body. */
  private static char checkDigit(String first12) {
    int sum = 0;
    for (int i = 0; i < 12; i++) {
      sum += (first12.charAt(11 - i) - '0') * (i % 2 == 0 ? 3 : 1);
    }
    return (char) ('0' + (10 - sum % 10) % 10);
  }
}
