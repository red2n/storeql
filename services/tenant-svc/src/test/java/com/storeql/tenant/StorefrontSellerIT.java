package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seller a till prints on its receipts (intent/vat-inclusive-pricing.md): the business's VAT
 * number is public business data and rides on the store list the till already reads; a business
 * that has set none says null, and one business's number is never another's.
 */
@HelidonTest
class StorefrontSellerIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response stores(String tenant) {
    return target
        .path("/storefront/stores")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .get();
  }

  private void addStore(String tenant) {
    Response r =
        target
            .path("/admin/stores")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"name\":\"High Street\",\"code\":\"S-"
                        + Ids.newId()
                        + "\",\"country\":\"GB\",\"timezone\":\"Europe/London\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private void setVat(String tenant, String vat) {
    Response r =
        target
            .path("/admin/tenant")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"businessName\":\"Acme Foods\",\"vatNumber\":\"" + vat + "\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
  }

  @Test
  @DisplayName(
      "The store list carries the business's VAT number once it has one, and no one else's")
  void theStoreListCarriesTheVatNumber() {
    String acme = TenantOnboarding.onboard(target, "Acme Foods", "GB", "GBP");
    String other = TenantOnboarding.onboard(target, "Other Shop", "GB", "GBP");
    addStore(acme);
    addStore(other);

    String before = stores(acme).readEntity(String.class);
    assertThat(before, not(containsString("GB123456789")));

    setVat(acme, "GB123456789");

    Response mine = stores(acme);
    String body = mine.readEntity(String.class);
    assertThat(body, mine.getStatus(), is(200));
    assertThat(body, containsString("\"vatNumber\":\"GB123456789\""));
    assertThat(stores(other).readEntity(String.class), not(containsString("GB123456789")));
  }
}
