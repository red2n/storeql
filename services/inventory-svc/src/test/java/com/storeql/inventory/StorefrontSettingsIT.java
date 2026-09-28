package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Only N left" on the storefront: a business-wide threshold, off until an owner — or a manager
 * held to no store — sets one, and read by {@code GET /inventory/availability} as {@code onlyLeft}.
 * Every business keeps its own: a fresh tenant per test method, never shared, proves that on its
 * own — no two methods can pass by accident sharing one row.
 */
@HelidonTest
class StorefrontSettingsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── request helpers ─────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String tenant, String role, String stores) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role);
    return stores == null ? b : b.header("X-Store-Ids", stores);
  }

  private Response getSettings(String tenant, String role) {
    return as("/admin/inventory/storefront-settings", tenant, role, null).get();
  }

  private Response putSettings(String tenant, String role, String stores, Integer threshold) {
    String body = "{\"lowStockThreshold\":" + (threshold == null ? "null" : threshold) + "}";
    return as("/admin/inventory/storefront-settings", tenant, role, stores)
        .put(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private Response availability(String tenant, String store) {
    return as("/inventory/availability?store=" + store, tenant, "OWNER", null).get();
  }

  private void receive(String tenant, String store, String variant, int qty) {
    Response r =
        as("/admin/inventory/receive", tenant, "OWNER", null)
            .post(
                Entity.entity(
                    "{\"storeId\":\""
                        + store
                        + "\",\"variantId\":\""
                        + variant
                        + "\",\"qty\":"
                        + qty
                        + "}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private static JsonObject variant(Response availabilityResponse, String variantId) {
    return Envelopes.find(Envelopes.okArray(availabilityResponse), "variantId", variantId);
  }

  // ── off by default ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Off by default: no threshold set, and onlyLeft is null everywhere")
  void offByDefaultEverywhere() {
    String tenant = Ids.newId().toString();
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    receive(tenant, store, variant, 3);

    Response getResp = getSettings(tenant, "OWNER");
    JsonObject settings = Envelopes.ok(getResp);
    assertThat("no threshold has ever been set", blank(settings, "lowStockThreshold"), is(true));
    assertThat(blank(settings, "updatedAt"), is(true));
    assertThat(blank(settings, "updatedBy"), is(true));

    JsonObject v = variant(availability(tenant, store), variant);
    assertThat("3 is at or under any threshold, but none is set", blank(v, "onlyLeft"), is(true));
  }

  // ── the threshold, per store, per variant ───────────────────────────────

  @Test
  @DisplayName(
      "OWNER sets 5: a variant with 3 at store S1 shows 3, one with 7 shows null, and the same"
          + " variant at S2 with 2 shows 2 there")
  void thresholdAppliesPerStoreOnceSet() {
    String tenant = Ids.newId().toString();
    String s1 = Ids.newId().toString();
    String s2 = Ids.newId().toString();
    String vLow = Ids.newId().toString();
    String vHigh = Ids.newId().toString();

    Response put = putSettings(tenant, "OWNER", null, 5);
    JsonObject putBody = Envelopes.ok(put);
    assertThat(putBody.getInt("lowStockThreshold"), is(5));
    assertThat(blank(putBody, "updatedAt"), is(false));
    assertThat(blank(putBody, "updatedBy"), is(false));

    receive(tenant, s1, vLow, 3);
    receive(tenant, s1, vHigh, 7);
    receive(tenant, s2, vLow, 2);

    JsonObject atS1Low = variant(availability(tenant, s1), vLow);
    assertThat("3 of 5", atS1Low.getInt("onlyLeft"), is(3));
    JsonObject atS1High = variant(availability(tenant, s1), vHigh);
    assertThat("7 is above the threshold of 5", blank(atS1High, "onlyLeft"), is(true));
    JsonObject atS2Low = variant(availability(tenant, s2), vLow);
    assertThat("2 of 5, at the other store", atS2Low.getInt("onlyLeft"), is(2));

    // No store named: never a count, whatever the threshold.
    Response noStore = as("/inventory/availability", tenant, "OWNER", null).get();
    JsonObject noStoreLow = variant(noStore, vLow);
    assertThat(blank(noStoreLow, "onlyLeft"), is(true));
  }

  // ── multi-tenant isolation ───────────────────────────────────────────────

  @Test
  @DisplayName(
      "Business B sets its own threshold; A's is unchanged, and neither storefront reflects the"
          + " other's stock or threshold")
  void everyBusinessKeepsItsOwnThresholdAndStock() {
    String tenantA = Ids.newId().toString();
    String tenantB = Ids.newId().toString();
    String storeA = Ids.newId().toString();
    String storeB = Ids.newId().toString();
    String variantA = Ids.newId().toString();
    String variantB = Ids.newId().toString();

    assertThat(putSettings(tenantA, "OWNER", null, 5).getStatus(), is(200));
    assertThat(putSettings(tenantB, "OWNER", null, 10).getStatus(), is(200));

    // Each business reads its own number back, never the other's.
    assertThat(Envelopes.ok(getSettings(tenantA, "OWNER")).getInt("lowStockThreshold"), is(5));
    assertThat(Envelopes.ok(getSettings(tenantB, "OWNER")).getInt("lowStockThreshold"), is(10));

    // 8 units: over A's threshold of 5 (would be null there), under B's of 10.
    receive(tenantA, storeA, variantA, 8);
    receive(tenantB, storeB, variantB, 8);

    JsonObject aReadsItsOwn = variant(availability(tenantA, storeA), variantA);
    assertThat("8 is above A's own threshold of 5", blank(aReadsItsOwn, "onlyLeft"), is(true));
    JsonObject bReadsItsOwn = variant(availability(tenantB, storeB), variantB);
    assertThat("8 is at or under B's own threshold of 10", bReadsItsOwn.getInt("onlyLeft"), is(8));

    // Neither business's storefront ever lists the other's stock at all.
    assertThat(
        "A's read of A's store never lists B's variant",
        Envelopes.okArray(availability(tenantA, storeA)).getValuesAs(JsonObject.class).stream()
            .anyMatch(o -> variantB.equals(o.getString("variantId", null))),
        is(false));
    assertThat(
        "B's read of B's store never lists A's variant",
        Envelopes.okArray(availability(tenantB, storeB)).getValuesAs(JsonObject.class).stream()
            .anyMatch(o -> variantA.equals(o.getString("variantId", null))),
        is(false));
  }

  @Test
  @DisplayName(
      "Every role of business B reads and writes only B's own setting; there is no way to name"
          + " A's")
  void everyRoleOfAnotherBusinessOnlyEverSeesItsOwn() {
    String tenantA = Ids.newId().toString();
    String tenantB = Ids.newId().toString();
    assertThat(putSettings(tenantA, "OWNER", null, 7).getStatus(), is(200));

    // The endpoint carries no target-tenant field at all — every one of B's roles that can reach
    // it at all reaches only the row keyed on their own X-Tenant-Id, never A's.
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      JsonObject read = Envelopes.ok(getSettings(tenantB, role));
      assertThat(
          role + " sees no threshold of its own yet", blank(read, "lowStockThreshold"), is(true));
    }
    assertThat(putSettings(tenantB, "OWNER", null, 3).getStatus(), is(200));
    int bThreshold = Envelopes.ok(getSettings(tenantB, "OWNER")).getInt("lowStockThreshold");
    assertThat("B's own write, not A's 7", bThreshold, is(3));
    assertThat("A's setting is untouched by B's write", bThreshold, not(is(7)));
    assertThat(Envelopes.ok(getSettings(tenantA, "OWNER")).getInt("lowStockThreshold"), is(7));
  }

  // ── PUT is a business-wide, management-only write ───────────────────────

  @Test
  @DisplayName("A store-held manager, a storekeeper and a cashier are refused on PUT")
  void putIsRefusedToEveryoneButOwnerAndABusinessWideManager() {
    String tenant = Ids.newId().toString();
    String store = Ids.newId().toString();

    Response byStoreHeldManager = putSettings(tenant, "MANAGER", store, 5);
    assertThat(
        byStoreHeldManager.readEntity(String.class), byStoreHeldManager.getStatus(), is(403));

    Response byStorekeeper = putSettings(tenant, "STOREKEEPER", null, 5);
    assertThat(byStorekeeper.readEntity(String.class), byStorekeeper.getStatus(), is(403));
    Response byStorekeeperOfAStore = putSettings(tenant, "STOREKEEPER", store, 5);
    assertThat(byStorekeeperOfAStore.getStatus(), is(403));

    Response byCashier = putSettings(tenant, "CASHIER", null, 5);
    assertThat(byCashier.readEntity(String.class), byCashier.getStatus(), is(403));

    // Nothing was written by any of the refused attempts.
    assertThat(blank(Envelopes.ok(getSettings(tenant, "OWNER")), "lowStockThreshold"), is(true));

    // OWNER, and a MANAGER held to no store, may.
    assertThat(putSettings(tenant, "OWNER", null, 5).getStatus(), is(200));
    assertThat(putSettings(tenant, "MANAGER", null, 6).getStatus(), is(200));
    assertThat(Envelopes.ok(getSettings(tenant, "OWNER")).getInt("lowStockThreshold"), is(6));
  }

  // ── range validation, and turning it off again ──────────────────────────

  @Test
  @DisplayName("0 and 1001 are refused; a valid value is stored; null turns it off again")
  void rangeIsEnforcedAndNullSwitchesItOff() {
    String tenant = Ids.newId().toString();

    Response tooLow = putSettings(tenant, "OWNER", null, 0);
    String tooLowBody = tooLow.readEntity(String.class);
    assertThat(tooLowBody, tooLow.getStatus(), is(400));
    assertThat(tooLowBody, containsString("INVENTORY_LOW_STOCK_THRESHOLD_INVALID"));

    Response tooHigh = putSettings(tenant, "OWNER", null, 1001);
    String tooHighBody = tooHigh.readEntity(String.class);
    assertThat(tooHighBody, tooHigh.getStatus(), is(400));
    assertThat(tooHighBody, containsString("INVENTORY_LOW_STOCK_THRESHOLD_INVALID"));

    // Neither refused attempt changed anything: still off.
    assertThat(blank(Envelopes.ok(getSettings(tenant, "OWNER")), "lowStockThreshold"), is(true));

    assertThat(putSettings(tenant, "OWNER", null, 1000).getStatus(), is(200));
    assertThat(Envelopes.ok(getSettings(tenant, "OWNER")).getInt("lowStockThreshold"), is(1000));

    Response cleared = putSettings(tenant, "OWNER", null, null);
    JsonObject clearedBody = Envelopes.ok(cleared);
    assertThat("null switches it off again", blank(clearedBody, "lowStockThreshold"), is(true));
    assertThat(blank(Envelopes.ok(getSettings(tenant, "OWNER")), "lowStockThreshold"), is(true));
  }

  /**
   * Whether a field is absent or JSON null: this service's answers leave a null field out, so
   * {@code isNull} alone would throw on it.
   */
  private static boolean blank(JsonObject o, String key) {
    return !o.containsKey(key) || o.isNull(key);
  }
}
