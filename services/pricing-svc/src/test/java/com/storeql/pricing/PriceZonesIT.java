package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Price zones and competitor-driven repricing (03.x).
 *
 * <p>A business groups its stores into price zones; a price list bound to a zone beats the
 * tenant-wide list for a store in that zone and is invisible to a store outside it. Competitor
 * prices are observed per variant (and, optionally, per zone); a repricing rule on a price list
 * turns the freshest, lowest rival price into a proposal, which management applies into that list
 * or dismisses. Written before the code.
 */
@HelidonTest
class PriceZonesIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478f1";
  private static final String RIVAL_TENANT = "01a090ae-611e-702c-a97b-d1b8025478f2";
  private static final String NORTH_STORE = "01a090ae-611e-703c-a378-a4972ea461d1";
  private static final String SOUTH_STORE = "01a090ae-611e-703c-a378-a4972ea461d2";
  private static final String RIVALS_STORE = "01a090ae-611e-703c-a378-a4972ea461d3";
  private static final String V = "01a090ae-611e-7037-a4b7-c854f0266acf";

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .withStore(T, NORTH_STORE, "GB")
            .withStore(T, SOUTH_STORE, "GB")
            .with(RIVAL_TENANT, "GBP", "GB")
            .withStore(RIVAL_TENANT, RIVALS_STORE, "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE pricing.repricing_proposals, pricing.repricing_rules,"
              + " pricing.competitor_prices, pricing.price_zone_stores, pricing.price_list_items,"
              + " pricing.price_lists, pricing.price_zones, pricing.vat_rates, pricing.outbox"
              + " CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant, String roles) {
    int q = path.indexOf('?');
    WebTarget t = target.path(q < 0 ? path : path.substring(0, q));
    if (q >= 0) {
      for (String param : path.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    var b = t.request().header("X-Tenant-Id", tenant).header("X-Roles", roles);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "OWNER");
  }

  private Response get(String path) {
    return call("GET", path, null, T, "OWNER");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private void standardVat() {
    assertThat(
        post(
                "/vat-rates",
                "{\"code\":\"T1\",\"name\":\"Standard Rate\",\"rate\":0.20,\"exempt\":false,"
                    + "\"description\":\"UK Standard VAT\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}")
            .getStatus(),
        is(201));
  }

  private String priceList(String name, String zoneId, String price) {
    JsonObject pl =
        Envelopes.created(
            post(
                "/admin/price-lists",
                "{\"name\":\""
                    + name
                    + "\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                    + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\""
                    + (zoneId == null ? "" : ",\"zoneId\":\"" + zoneId + "\"")
                    + "}"));
    if (zoneId != null) assertThat(pl.getString("zoneId"), is(zoneId));
    assertThat(
        post(
                "/admin/price-lists/" + pl.getString("id") + "/items",
                "{\"variantId\":\"" + V + "\",\"price\":" + price + ",\"minQty\":1}")
            .getStatus(),
        is(200));
    return pl.getString("id");
  }

  private String zone(String name, String... stores) {
    JsonObject z =
        Envelopes.created(
            post("/admin/price-zones", "{\"name\":\"" + name + "\",\"description\":\"up there\"}"));
    assertThat(z.getString("name"), is(name));
    if (stores.length > 0) {
      StringBuilder ids = new StringBuilder();
      for (String s : stores)
        ids.append(ids.length() == 0 ? "" : ",").append('"').append(s).append('"');
      Response r =
          call(
              "PUT",
              "/admin/price-zones/" + z.getString("id") + "/stores",
              "{\"storeIds\":[" + ids + "]}",
              T,
              "OWNER");
      JsonObject assigned = Envelopes.ok(r);
      assertThat(assigned.getJsonArray("storeIds").size(), is(stores.length));
    }
    return z.getString("id");
  }

  private BigDecimal resolvedAt(String storeId) {
    Response r =
        post(
            "/prices/resolve",
            "{\"variantId\":\""
                + V
                + "\",\"channel\":\"POS\",\"qty\":1"
                + (storeId == null ? "" : ",\"storeId\":\"" + storeId + "\"")
                + "}");
    return Envelopes.ok(r).getJsonNumber("unitPrice").bigDecimalValue();
  }

  // ── zones ──────────────────────────────────────────────────────────────────

  @Test
  void aZonesPriceListBeatsTheTenantWideOneForItsStoresOnly() {
    standardVat();
    priceList("Everywhere", null, "10.00");
    String north = zone("North", NORTH_STORE);
    priceList("North prices", north, "9.00");

    assertThat(resolvedAt(NORTH_STORE), comparesEqualTo(new BigDecimal("9.00")));
    assertThat(resolvedAt(SOUTH_STORE), comparesEqualTo(new BigDecimal("10.00")));
    // No store named: the tenant-wide price, never a zone's.
    assertThat(resolvedAt(null), comparesEqualTo(new BigDecimal("10.00")));

    // The zone lists its stores; a store moves when assigned to another zone, as it sits in one.
    JsonArray zones = Envelopes.okArray(get("/admin/price-zones"));
    assertThat(zones.size(), is(1));
    assertThat(zones.getJsonObject(0).getJsonArray("storeIds").getString(0), is(NORTH_STORE));
    String south = zone("South", NORTH_STORE, SOUTH_STORE);
    zones = Envelopes.okArray(get("/admin/price-zones"));
    JsonObject northNow = Envelopes.find(zones, "id", north);
    JsonObject southNow = Envelopes.find(zones, "id", south);
    assertThat(northNow.getJsonArray("storeIds").size(), is(0));
    assertThat(southNow.getJsonArray("storeIds").size(), is(2));
    // North's list no longer reaches the north store: it is in South now, which has no list.
    assertThat(resolvedAt(NORTH_STORE), comparesEqualTo(new BigDecimal("10.00")));
  }

  @Test
  void zonesAreRefusedByName() {
    zone("North", NORTH_STORE);
    assertThat(
        code(post("/admin/price-zones", "{\"name\":\"North\"}"), 409),
        is("PRICING_ZONE_NAME_EXISTS"));
    assertThat(code(post("/admin/price-zones", "{\"name\":\"  \"}"), 400), is("VALIDATION_FAILED"));
    String east = zone("East");
    // Another business's store is not ours to price.
    assertThat(
        code(
            call(
                "PUT",
                "/admin/price-zones/" + east + "/stores",
                "{\"storeIds\":[\"" + RIVALS_STORE + "\"]}",
                T,
                "OWNER"),
            400),
        is("PRICING_ZONE_STORE_UNKNOWN"));
    // A zone nobody made cannot take stores or a price list.
    String nobody = Ids.newId().toString();
    assertThat(
        code(
            call(
                "PUT",
                "/admin/price-zones/" + nobody + "/stores",
                "{\"storeIds\":[\"" + NORTH_STORE + "\"]}",
                T,
                "OWNER"),
            404),
        is("PRICING_ZONE_NOT_FOUND"));
    assertThat(
        code(
            post(
                "/admin/price-lists",
                "{\"name\":\"Nowhere\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                    + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\",\"zoneId\":\""
                    + nobody
                    + "\"}"),
            400),
        is("PRICING_ZONE_UNKNOWN"));
    // Another business sees none of it, and a cashier may not price.
    assertThat(
        Envelopes.okArray(call("GET", "/admin/price-zones", null, RIVAL_TENANT, "OWNER")).size(),
        is(0));
    assertThat(
        call("POST", "/admin/price-zones", "{\"name\":\"Till\"}", T, "CASHIER").getStatus(),
        is(403));
  }

  // ── competitor prices and repricing ────────────────────────────────────────

  @Test
  void aRivalsPriceBecomesAProposalThatAppliesIntoTheZonesListOnly() {
    standardVat();
    priceList("Everywhere", null, "10.00");
    String north = zone("North", NORTH_STORE);
    String northList = priceList("North prices", north, "9.00");

    // Two rivals seen in the north; the freshest price of each counts, the lowest of those wins.
    String today = LocalDate.now().toString();
    JsonObject seen =
        Envelopes.created(
            post(
                "/admin/competitor-prices",
                "{\"variantId\":\""
                    + V
                    + "\",\"competitor\":\"Rival A\",\"price\":8.50,\"zoneId\":\""
                    + north
                    + "\",\"observedOn\":\""
                    + today
                    + "\"}"));
    assertThat(seen.getString("competitor"), is("Rival A"));
    assertThat(seen.getString("currency"), is("GBP"));
    assertThat(seen.getString("source"), is("MANUAL"));
    JsonObject batch =
        Envelopes.ok(
            post(
                "/admin/competitor-prices/batch",
                "{\"observations\":[{\"variantId\":\""
                    + V
                    + "\",\"competitor\":\"Rival B\",\"price\":8.90,\"zoneId\":\""
                    + north
                    + "\",\"observedOn\":\""
                    + today
                    + "\"},{\"variantId\":\""
                    + V
                    + "\",\"competitor\":\"Rival A\",\"price\":7.00,\"observedOn\":\"2026-01-01\"}]}"));
    assertThat(batch.getInt("recorded"), is(2));
    JsonArray observations = Envelopes.okArray(get("/admin/competitor-prices?variantId=" + V));
    assertThat(observations.size(), is(3));

    // A rule on the zone's list: undercut the lowest fresh rival by 1%, to a .99, never below 80%.
    JsonObject rule =
        Envelopes.created(
            post(
                "/admin/repricing/rules",
                "{\"name\":\"North undercut\",\"priceListId\":\""
                    + northList
                    + "\",\"strategy\":\"UNDERCUT_PERCENT\",\"value\":1,\"floorPercent\":80,"
                    + "\"rounding\":\"ENDING_99\",\"maxAgeDays\":14}"));
    assertThat(rule.getString("zoneId"), is(north));
    JsonObject run =
        Envelopes.ok(post("/admin/repricing/rules/" + rule.getString("id") + "/run", "{}"));
    // 8.50 × 0.99 = 8.415 → 7.99; floor 7.20 holds. Rival A's January 7.00 is stale and ignored.
    assertThat(run.getInt("proposed"), is(1));
    JsonObject proposal = run.getJsonArray("proposals").getJsonObject(0);
    assertThat(proposal.getString("competitor"), is("Rival A"));
    assertThat(
        proposal.getJsonNumber("competitorPrice").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("8.50")));
    assertThat(
        proposal.getJsonNumber("currentPrice").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("9.00")));
    assertThat(
        proposal.getJsonNumber("proposedPrice").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("7.99")));
    assertThat(proposal.getString("status"), is("PROPOSED"));
    // Running again does not pile up proposals: the open one is refreshed.
    run = Envelopes.ok(post("/admin/repricing/rules/" + rule.getString("id") + "/run", "{}"));
    assertThat(run.getInt("proposed"), is(1));
    assertThat(Envelopes.okArray(get("/admin/repricing/proposals")).size(), is(1));

    // Applied: the north store's price moves, the south store's does not.
    JsonObject applied =
        Envelopes.ok(
            post("/admin/repricing/proposals/" + proposal.getString("id") + "/apply", "{}"));
    assertThat(applied.getString("status"), is("APPLIED"));
    assertThat(resolvedAt(NORTH_STORE), comparesEqualTo(new BigDecimal("7.99")));
    assertThat(resolvedAt(SOUTH_STORE), comparesEqualTo(new BigDecimal("10.00")));
    // Decided once.
    assertThat(
        code(
            post("/admin/repricing/proposals/" + proposal.getString("id") + "/dismiss", "{}"), 409),
        is("REPRICING_PROPOSAL_DECIDED"));
    // Now at 7.99 against a rival at 8.50: nothing left to propose.
    run = Envelopes.ok(post("/admin/repricing/rules/" + rule.getString("id") + "/run", "{}"));
    assertThat(run.getInt("proposed"), is(0));
    assertThat(Envelopes.okArray(get("/admin/repricing/proposals?status=APPLIED")).size(), is(1));
  }

  @Test
  void repricingIsRefusedByName() {
    standardVat();
    String everywhere = priceList("Everywhere", null, "10.00");
    String nobody = Ids.newId().toString();
    assertThat(
        code(
            post(
                "/admin/competitor-prices",
                "{\"variantId\":\""
                    + V
                    + "\",\"competitor\":\"Rival\",\"price\":8.50,\"currency\":\"USD\"}"),
            400),
        is("PRICING_COMPETITOR_CURRENCY_MISMATCH"));
    assertThat(
        code(
            post(
                "/admin/competitor-prices",
                "{\"variantId\":\""
                    + V
                    + "\",\"competitor\":\"Rival\",\"price\":8.50,\"zoneId\":\""
                    + nobody
                    + "\"}"),
            400),
        is("PRICING_ZONE_UNKNOWN"));
    assertThat(
        code(
            post(
                "/admin/competitor-prices",
                "{\"variantId\":\""
                    + V
                    + "\",\"competitor\":\"Rival\",\"price\":8.50,\"observedOn\":\"2999-01-01\"}"),
            400),
        is("PRICING_COMPETITOR_DATE_INVALID"));
    String rule =
        "{\"name\":\"r\",\"priceListId\":\"%s\",\"strategy\":\"%s\",\"value\":%s,\"floorPercent\":%s,"
            + "\"rounding\":\"NONE\",\"maxAgeDays\":14}";
    assertThat(
        code(
            post("/admin/repricing/rules", String.format(rule, nobody, "MATCH_LOWEST", 0, 80)),
            400),
        is("PRICING_LIST_UNKNOWN"));
    assertThat(
        code(post("/admin/repricing/rules", String.format(rule, everywhere, "GUESS", 0, 80)), 400),
        is("REPRICING_STRATEGY_INVALID"));
    assertThat(
        code(
            post(
                "/admin/repricing/rules",
                String.format(rule, everywhere, "UNDERCUT_PERCENT", 150, 80)),
            400),
        is("REPRICING_VALUE_INVALID"));
    assertThat(
        code(
            post("/admin/repricing/rules", String.format(rule, everywhere, "MATCH_LOWEST", 0, 0)),
            400),
        is("VALIDATION_FAILED"));
    // A dismissed proposal stays dismissed and a run with no fresh observation proposes nothing.
    Envelopes.created(
        post(
            "/admin/competitor-prices",
            "{\"variantId\":\"" + V + "\",\"competitor\":\"Rival\",\"price\":8.50}"));
    JsonObject made =
        Envelopes.created(
            post("/admin/repricing/rules", String.format(rule, everywhere, "MATCH_LOWEST", 0, 80)));
    assertThat(made.containsKey("zoneId") && !made.isNull("zoneId"), is(false));
    JsonObject run =
        Envelopes.ok(post("/admin/repricing/rules/" + made.getString("id") + "/run", "{}"));
    String proposalId = run.getJsonArray("proposals").getJsonObject(0).getString("id");
    assertThat(
        Envelopes.ok(post("/admin/repricing/proposals/" + proposalId + "/dismiss", "{}"))
            .getString("status"),
        is("DISMISSED"));
    assertThat(
        code(post("/admin/repricing/proposals/" + proposalId + "/apply", "{}"), 409),
        is("REPRICING_PROPOSAL_DECIDED"));
    assertThat(
        code(post("/admin/repricing/proposals/" + nobody + "/apply", "{}"), 404),
        is("REPRICING_PROPOSAL_NOT_FOUND"));
    assertThat(resolvedAt(null), comparesEqualTo(new BigDecimal("10.00")));
    // Nobody but management: a storekeeper reads nothing here and a cashier writes nothing.
    assertThat(
        call("GET", "/admin/repricing/proposals", null, T, "STOREKEEPER").getStatus(), is(403));
    assertThat(
        call(
                "POST",
                "/admin/competitor-prices",
                "{\"variantId\":\"" + V + "\",\"competitor\":\"x\",\"price\":1}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));
  }
}
