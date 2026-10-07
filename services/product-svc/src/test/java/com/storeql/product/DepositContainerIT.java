package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drinks container a variant is sold in (09.16): the material and volume a deposit return
 * scheme reads to price the deposit. The catalogue records the container; the scheme, held as
 * jurisdiction data in tenant-svc, decides whether it takes that container back and for how much.
 */
@HelidonTest
class DepositContainerIT {

  // Declared before the static block, which registers them with the tenant-svc stub.
  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "product");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
    TenantSvcStub.start().with(T, "GBP", "GB").with(RIVAL, "GBP", "GB");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  private Response post(String path, String json, String tenant, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response compliance(String tenant, String variant, String json, String roles) {
    return target
        .path("/admin/products/variants/" + variant + "/compliance")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String path, String tenant, String roles, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    Response r = t.request().header("X-Tenant-Id", tenant).header("X-Roles", roles).get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  private static String id(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  /** A variant with a barcode, so the till can scan it. */
  private String[] variant(String tenant) {
    String product =
        id(post("/admin/products", "{\"name\":\"Cola " + Ids.newId() + "\"}", tenant, "OWNER"));
    String body = String.format("4%011d", Math.abs(System.nanoTime()) % 100_000_000_000L);
    String barcode = body + com.storeql.gs1.Gtin.checkDigit(body);
    return new String[] {
      id(
          post(
              "/admin/products/" + product + "/variants",
              "{\"sku\":\"C-" + Ids.newId() + "\",\"barcode\":\"" + barcode + "\"}",
              tenant,
              "OWNER")),
      barcode
    };
  }

  @Test
  @DisplayName("The owner records the container; the till's scan and resolve carry it")
  void theContainerIsRecordedAndCarriedToTheTill() {
    String[] v = variant(T);
    Response set =
        compliance(T, v[0], "{\"depositMaterial\":\"pet\",\"depositVolumeMl\":500}", "OWNER");
    String body = set.readEntity(String.class);
    assertThat(body, set.getStatus(), is(200));
    assertThat(body, containsString("\"depositMaterial\":\"PET\""));
    assertThat(body, containsString("\"depositVolumeMl\":500"));

    String scanned = get("/catalog/variants/by-barcode/" + v[1], T, "CASHIER");
    assertThat(scanned, containsString("\"depositMaterial\":\"PET\""));
    assertThat(scanned, containsString("\"depositVolumeMl\":500"));
    String resolved = get("/admin/products/variants/resolve", T, "CASHIER", "ids", v[0]);
    assertThat(resolved, containsString("\"depositMaterial\":\"PET\""));

    // Both or neither: clearing the container clears both.
    Response cleared = compliance(T, v[0], "{\"soldBy\":\"EACH\"}", "MANAGER");
    String after = cleared.readEntity(String.class);
    assertThat(after, cleared.getStatus(), is(200));
    assertThat(after, not(containsString("\"depositMaterial\"")));
    assertThat(
        get("/catalog/variants/by-barcode/" + v[1], T, "CASHIER"),
        not(containsString("\"depositMaterial\"")));
  }

  @Test
  @DisplayName(
      "A material without a volume, an unknown material or an impossible volume is refused")
  void anIncompleteOrImpossibleContainerIsRefused() {
    String v = variant(T)[0];
    Response half = compliance(T, v, "{\"depositMaterial\":\"PET\"}", "OWNER");
    assertThat(half.getStatus(), is(400));
    assertThat(
        half.readEntity(String.class), containsString("PRODUCT_DEPOSIT_CONTAINER_INCOMPLETE"));
    Response other = compliance(T, v, "{\"depositVolumeMl\":500}", "OWNER");
    assertThat(other.getStatus(), is(400));
    assertThat(
        other.readEntity(String.class), containsString("PRODUCT_DEPOSIT_CONTAINER_INCOMPLETE"));
    Response carton =
        compliance(T, v, "{\"depositMaterial\":\"CARDBOARD\",\"depositVolumeMl\":500}", "OWNER");
    assertThat(carton.getStatus(), is(400));
    assertThat(carton.readEntity(String.class), containsString("PRODUCT_DEPOSIT_MATERIAL_UNKNOWN"));
    for (String volume : new String[] {"0", "-5", "20000"}) {
      Response odd =
          compliance(
              T, v, "{\"depositMaterial\":\"PET\",\"depositVolumeMl\":" + volume + "}", "OWNER");
      assertThat(volume, odd.getStatus(), is(400));
      assertThat(
          odd.readEntity(String.class), containsString("PRODUCT_DEPOSIT_VOLUME_OUT_OF_RANGE"));
    }
    assertThat(
        "nothing was recorded",
        get("/admin/products/variants/resolve", T, "OWNER", "ids", v),
        not(containsString("\"depositMaterial\"")));
  }

  @Test
  @DisplayName("A cashier does not set the container, and another business does not see it")
  void onlyManagementSetsItAndOnlyTheOwnerSeesIt() {
    String v = variant(T)[0];
    assertThat(
        compliance(T, v, "{\"depositMaterial\":\"PET\",\"depositVolumeMl\":500}", "CASHIER")
            .getStatus(),
        is(403));
    assertThat(
        compliance(T, v, "{\"depositMaterial\":\"PET\",\"depositVolumeMl\":500}", "OWNER")
            .getStatus(),
        is(200));
    assertThat(
        "the rival resolving our id gets nothing",
        get("/admin/products/variants/resolve", RIVAL, "OWNER", "ids", v),
        not(containsString(v)));
    assertThat(
        compliance(RIVAL, v, "{\"depositMaterial\":\"GLASS\",\"depositVolumeMl\":330}", "OWNER")
            .getStatus(),
        is(404));
  }
}
