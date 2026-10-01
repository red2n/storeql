package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Date-code markdown through the API (05.4, 03.9): the ladder, the sticker, what the till reads off
 * it, the basket priced at it with no promotion on top, the redemption that counts it down, and
 * every refusal around each. inventory-svc is not running here, so the plan reports it unreachable;
 * the plan's arithmetic is in {@code MarkdownServiceTest}.
 */
@HelidonTest
class MarkdownIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a090ae-7d1e-702c-a97b-d1b8025478e1";
  private static final String OTHER_T = "01a090ae-7d1e-702c-a97b-d1b8025478e2";
  private static final String V = "01a090ae-7d1e-7037-a4b7-c854f0266ace";
  private static final String V2 = "01a090ae-7d1e-7037-a4b7-c854f0266acf";
  private static final String S = "01a090ae-7d1e-703c-a378-a4972ea461c8";
  private static final String S2 = "01a090ae-7d1e-703c-a378-a4972ea461c9";
  private static final String ORDER = "01a090ae-7d1e-7056-8f30-ecdbb48160eb";
  private static final String ORDER2 = "01a090ae-7d1e-7056-8f30-ecdbb48160ec";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void truncate() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE pricing.markdown_redemptions, pricing.markdowns, pricing.markdown_ladders,"
              + " pricing.markdown_label_series, pricing.promotion_redemptions,"
              + " pricing.promotion_items, pricing.promotions, pricing.promotion_status_changes,"
              + " pricing.price_list_items, pricing.price_lists, pricing.product_vat_categories,"
              + " pricing.vat_rates, pricing.outbox CASCADE");
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private Invocation.Builder at(
      String path, String tenant, String roles, String storeIds, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i + 1 < params.length; i += 2) t = t.queryParam(params[i], params[i + 1]);
    Invocation.Builder b = t.request().header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return b;
  }

  private Response post(String path, String json, String roles) {
    return at(path, T, roles, null).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json, String roles) {
    return at(path, T, roles, null).put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String roles, String... params) {
    return at(path, T, roles, null, params).get();
  }

  private static JsonObject json(String body) {
    try (JsonReader r = Json.createReader(new StringReader(body))) {
      return r.readObject();
    }
  }

  /** The stable code of a refused answer, after checking its status. */
  private static String codeOf(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return json(body).getString("code");
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus() / 100, is(2));
    return json(body).getJsonObject("data");
  }

  private void seedPosPrice(String variant, String price) {
    post(
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,\"exempt\":false,"
            + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        "OWNER");
    Response pl =
        post(
            "/admin/price-lists",
            "{\"name\":\"POS "
                + variant
                + "\",\"channel\":\"POS\",\"currency\":\"GBP\","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            "OWNER");
    String plId = data(pl).getString("id");
    assertThat(
        post(
                "/admin/price-lists/" + plId + "/items",
                "{\"variantId\":\"" + variant + "\",\"price\":" + price + ",\"minQty\":1}",
                "OWNER")
            .getStatus(),
        is(200));
  }

  private static String create(
      String store, String variant, String qty, String amount, String reason, LocalDate expiry) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"batchNo\":\"B-7\","
        + "\"expiryDate\":\""
        + expiry
        + "\",\"qty\":"
        + qty
        + ","
        + amount
        + ",\"reason\":\""
        + reason
        + "\"}";
  }

  private JsonObject sticker(String qty, String percent) {
    return data(
        post(
            "/markdowns",
            create(
                S, V, qty, "\"percentOff\":" + percent, "SHORT_DATED", LocalDate.now().plusDays(2)),
            "STOREKEEPER"));
  }

  // ── the ladder (03.9) ──────────────────────────────────────────────────────

  @Test
  void theLadderDefaultsThenTheTenantsThenTheStores() {
    JsonObject dflt = data(get("/markdowns/ladder", "STOREKEEPER"));
    assertThat(dflt.getString("source"), is("DEFAULT"));
    assertThat(dflt.getJsonArray("steps").size(), is(3));

    Response r =
        put(
            "/markdowns/ladder",
            "{\"steps\":[{\"daysToExpiry\":5,\"percentOff\":20},{\"daysToExpiry\":1,\"percentOff\":60}]}",
            "MANAGER");
    assertThat(data(r).getString("source"), is("TENANT"));

    JsonObject atStore = data(get("/markdowns/ladder", "STOREKEEPER", "storeId", S));
    assertThat(
        "a store with no ladder of its own takes the tenant's",
        atStore.getString("source"),
        is("TENANT"));
    assertThat(atStore.getJsonArray("steps").size(), is(2));

    put(
        "/markdowns/ladder",
        "{\"storeId\":\"" + S + "\",\"steps\":[{\"daysToExpiry\":2,\"percentOff\":40}]}",
        "OWNER");
    JsonObject own = data(get("/markdowns/ladder", "STOREKEEPER", "storeId", S));
    assertThat(own.getString("source"), is("STORE"));
    assertThat(own.getJsonArray("steps").size(), is(1));
    assertThat(
        "another store still reads the tenant's",
        data(get("/markdowns/ladder", "STOREKEEPER", "storeId", S2)).getString("source"),
        is("TENANT"));
    assertThat(
        "another tenant reads the default",
        data(at("/markdowns/ladder", OTHER_T, "OWNER", null).get()).getString("source"),
        is("DEFAULT"));
  }

  @Test
  void theLadderIsManagementsToSet() {
    String body = "{\"steps\":[{\"daysToExpiry\":2,\"percentOff\":40}]}";
    assertThat(put("/markdowns/ladder", body, "STOREKEEPER").getStatus(), is(403));
    assertThat(put("/markdowns/ladder", body, "CASHIER").getStatus(), is(403));
    assertThat(put("/markdowns/ladder", body, null).getStatus(), is(403));
    Response dup =
        put(
            "/markdowns/ladder",
            "{\"steps\":[{\"daysToExpiry\":2,\"percentOff\":40},{\"daysToExpiry\":2,\"percentOff\":50}]}",
            "MANAGER");
    assertThat(dup.getStatus(), is(400));
    assertThat(dup.readEntity(String.class), containsString("PRICING_LADDER_DUPLICATE_STEP"));
    assertThat(
        "a percentage over 100 is not a price",
        put("/markdowns/ladder", "{\"steps\":[{\"daysToExpiry\":2,\"percentOff\":120}]}", "MANAGER")
            .getStatus(),
        is(400));
    assertThat(
        "an empty ladder is not a ladder",
        put("/markdowns/ladder", "{\"steps\":[]}", "MANAGER").getStatus(),
        is(400));
  }

  @Test
  void thePlanSaysWhenInventoryCannotBeRead() {
    JsonObject plan = data(get("/markdowns/plan", "STOREKEEPER", "storeId", S));
    assertThat(plan.getBoolean("inventoryReachable"), is(false));
    assertThat(plan.getJsonArray("suggestions").size(), is(0));
    assertThat(plan.getInt("withinDays"), is(7));
    assertThat(plan.getString("ladderSource"), is("DEFAULT"));
    assertThat(get("/markdowns/plan", "CASHIER", "storeId", S).getStatus(), is(403));
    assertThat(get("/markdowns/plan", "STOREKEEPER").getStatus(), is(400));
    assertThat(
        get("/markdowns/plan", "STOREKEEPER", "storeId", S, "withinDays", "0").getStatus(),
        is(400));
    assertThat(
        "a storekeeper of another store cannot plan this one",
        at("/markdowns/plan", T, "STOREKEEPER", S2, "storeId", S).get().getStatus(),
        is(403));
  }

  // ── the sticker (05.4) ─────────────────────────────────────────────────────

  @Test
  void aStickerCarriesTheReducedPriceInItsCode() {
    seedPosPrice(V, "2.99");
    JsonObject m = sticker("6", "25");
    assertThat(m.getString("status"), is("ACTIVE"));
    assertThat(
        m.getJsonNumber("originalPrice").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("2.99")));
    assertThat(
        m.getJsonNumber("markdownPrice").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("2.24")));
    assertThat(
        m.getJsonNumber("remainingQty").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("6")));
    String code = m.getString("labelCode");
    assertThat(code.length(), is(13));
    assertThat(code, startsWith("2100001"));
    assertThat("the pence are in the code", code.substring(7, 12), is("00224"));
    assertThat(m.getString("currency"), is("GBP"));

    JsonObject second = sticker("1", "50");
    assertThat("the item number moves on", second.getString("labelCode"), startsWith("2100002"));

    assertThat(get("/markdowns/" + m.getString("id"), "STOREKEEPER").getStatus(), is(200));
    assertThat(
        "another tenant's is 404",
        codeOf(at("/markdowns/" + m.getString("id"), OTHER_T, "OWNER", null).get(), 404),
        is("PRICING_MARKDOWN_NOT_FOUND"));
    assertThat(
        json(get("/markdowns", "MANAGER", "storeId", S).readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(2));
    assertThat(
        json(get("/markdowns", "MANAGER", "storeId", S2).readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(0));
  }

  @Test
  void whatAStickerIsRefusedFor() {
    LocalDate soon = LocalDate.now().plusDays(1);
    assertThat(
        "no POS price to reduce from",
        post("/markdowns", create(S, V, "1", "\"percentOff\":25", "SHORT_DATED", soon), "OWNER")
            .getStatus(),
        is(404));
    seedPosPrice(V, "4.00");
    Response bad =
        post("/markdowns", create(S, V, "1", "\"percentOff\":25", "BORED", soon), "OWNER");
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("PRICING_MARKDOWN_REASON_UNKNOWN"));
    assertThat(
        post(
                "/markdowns",
                create(S, V, "1", "\"percentOff\":25,\"markdownPrice\":1.00", "CLEARANCE", soon),
                "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        post("/markdowns", create(S, V, "1", "\"markdownPrice\":4.00", "CLEARANCE", soon), "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        post(
                "/markdowns",
                create(S, V, "1", "\"percentOff\":25", "CLEARANCE", LocalDate.now().minusDays(1)),
                "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        post("/markdowns", create(S, V, "0", "\"percentOff\":25", "CLEARANCE", soon), "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        post("/markdowns", create(S, V, "1", "\"percentOff\":0", "CLEARANCE", soon), "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        post(
                "/markdowns",
                create(S, "not-a-uuid", "1", "\"percentOff\":25", "CLEARANCE", soon),
                "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        "the till does not sticker",
        post("/markdowns", create(S, V, "1", "\"percentOff\":25", "CLEARANCE", soon), "CASHIER")
            .getStatus(),
        is(403));
    assertThat(
        post("/markdowns", create(S, V, "1", "\"percentOff\":25", "CLEARANCE", soon), null)
            .getStatus(),
        is(403));
    assertThat(
        "a storekeeper of another store cannot sticker this one",
        at("/markdowns", T, "STOREKEEPER", S2, new String[0])
            .post(
                Entity.entity(
                    create(S, V, "1", "\"percentOff\":25", "CLEARANCE", soon),
                    MediaType.APPLICATION_JSON))
            .getStatus(),
        is(403));
  }

  // ── the till ───────────────────────────────────────────────────────────────

  @Test
  void theTillReadsTheStickerPricesTheBasketAtItAndCountsItDown() {
    seedPosPrice(V, "4.00");
    seedPosPrice(V2, "10.00");
    // A 10 % promotion on everything, to prove the sticker line stands outside it.
    Response promo =
        post(
            "/admin/promotions",
            "{\"name\":\"Ten off\",\"type\":\"PERCENT\",\"value\":10,"
                + "\"channel\":\"ALL\",\"startsAt\":\"2024-01-01T00:00:00Z\"}",
            "OWNER");
    String promoId = data(promo).getString("id");
    assertThat(
        post("/admin/promotions/" + promoId + "/items", "{\"scopeType\":\"ALL\"}", "OWNER")
            .getStatus(),
        is(201));

    JsonObject m = sticker("2", "50");
    String code = m.getString("labelCode");
    String id = m.getString("id");

    JsonObject label = data(get("/prices/markdown-labels/" + code, "CASHIER"));
    assertThat(label.getString("markdownId"), is(id));
    assertThat(label.getString("variantId"), is(V));
    assertThat(
        label.getJsonNumber("markdownPrice").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("2.00")));
    assertThat(
        label.getJsonNumber("remainingQty").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("2")));
    assertThat(
        "nobody without a role reads a sticker",
        get("/prices/markdown-labels/" + code, null).getStatus(),
        is(403));
    assertThat(
        "another tenant does not know the code",
        at("/prices/markdown-labels/" + code, OTHER_T, "CASHIER", null).get().getStatus(),
        is(404));
    assertThat(get("/prices/markdown-labels/2100009000009", "CASHIER").getStatus(), is(404));
    assertThat(
        "not a sticker at all",
        get("/prices/markdown-labels/5012345678900", "CASHIER").getStatus(),
        is(404));

    // The basket: one stickered line, one ordinary line.
    String basket =
        "{\"storeId\":\""
            + S
            + "\",\"channel\":\"POS\",\"lines\":[{\"variantId\":\""
            + V
            + "\",\"qty\":2,\"markdownId\":\""
            + id
            + "\"},"
            + "{\"variantId\":\""
            + V2
            + "\",\"qty\":1}]}";
    JsonObject q = data(post("/prices/quote", basket, "CASHIER"));
    var lines = q.getJsonArray("lines");
    JsonObject stickered = lines.getJsonObject(0);
    assertThat(
        stickered.getJsonNumber("unitPrice").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("2.00")));
    assertThat(
        stickered.getJsonNumber("discount").bigDecimalValue(),
        comparesEqualTo(java.math.BigDecimal.ZERO));
    assertThat(stickered.getString("markdownId"), is(id));
    JsonObject ordinary = lines.getJsonObject(1);
    assertThat(
        ordinary.getJsonNumber("discount").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("1.00")));
    assertThat(ordinary.containsKey("markdownId"), is(false));
    assertThat(
        q.getJsonNumber("subtotal").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("14.00")));
    assertThat(
        q.getJsonNumber("totalDiscount").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("1.00")));

    // Wrong product, wrong store, too many.
    Response mismatch =
        post(
            "/prices/quote",
            "{\"storeId\":\""
                + S
                + "\",\"lines\":[{\"variantId\":\""
                + V2
                + "\",\"qty\":1,\"markdownId\":\""
                + id
                + "\"}]}",
            "CASHIER");
    assertThat(mismatch.getStatus(), is(400));
    assertThat(
        mismatch.readEntity(String.class), containsString("PRICING_MARKDOWN_VARIANT_MISMATCH"));
    Response elsewhere =
        post(
            "/prices/quote",
            "{\"storeId\":\""
                + S2
                + "\",\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"markdownId\":\""
                + id
                + "\"}]}",
            "CASHIER");
    assertThat(elsewhere.getStatus(), is(400));
    assertThat(
        elsewhere.readEntity(String.class), containsString("PRICING_MARKDOWN_STORE_MISMATCH"));
    Response tooMany =
        post(
            "/prices/quote",
            "{\"storeId\":\""
                + S
                + "\",\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":3,\"markdownId\":\""
                + id
                + "\"}]}",
            "CASHIER");
    assertThat(tooMany.getStatus(), is(409));
    assertThat(tooMany.readEntity(String.class), containsString("PRICING_MARKDOWN_EXHAUSTED"));
    assertThat(
        "a made-up markdown",
        codeOf(
            post(
                "/prices/quote",
                "{\"storeId\":\""
                    + S
                    + "\",\"lines\":[{\"variantId\":\""
                    + V
                    + "\",\"qty\":1,\"markdownId\":\""
                    + ORDER
                    + "\"}]}",
                "CASHIER"),
            404),
        is("PRICING_MARKDOWN_NOT_FOUND"));
    assertThat(
        "an id nobody issued",
        codeOf(
            post(
                "/prices/quote",
                "{\"storeId\":\""
                    + S
                    + "\",\"lines\":[{\"variantId\":\""
                    + V
                    + "\",\"qty\":1,\"markdownId\":\""
                    + Ids.newId()
                    + "\"}]}",
                "CASHIER"),
            404),
        is("PRICING_MARKDOWN_NOT_FOUND"));

    // The sale is recorded once, and counts the sticker down.
    String redemption =
        "{\"orderId\":\"" + ORDER + "\",\"lines\":[{\"markdownId\":\"" + id + "\",\"qty\":1}]}";
    assertThat(
        data(post("/prices/markdown-redemptions", redemption, "CASHIER")).getInt("recorded"),
        is(1));
    assertThat(
        "a replay records nothing",
        data(post("/prices/markdown-redemptions", redemption, "CASHIER")).getInt("recorded"),
        is(0));
    assertThat(
        data(get("/prices/markdown-labels/" + code, "CASHIER"))
            .getJsonNumber("remainingQty")
            .bigDecimalValue(),
        comparesEqualTo(java.math.BigDecimal.ONE));
    assertThat(
        "two more is now too many",
        post(
                "/prices/quote",
                "{\"storeId\":\""
                    + S
                    + "\",\"lines\":[{\"variantId\":\""
                    + V
                    + "\",\"qty\":2,\"markdownId\":\""
                    + id
                    + "\"}]}",
                "CASHIER")
            .getStatus(),
        is(409));
    String last =
        "{\"orderId\":\"" + ORDER2 + "\",\"lines\":[{\"markdownId\":\"" + id + "\",\"qty\":1}]}";
    assertThat(
        data(post("/prices/markdown-redemptions", last, "CASHIER")).getInt("recorded"), is(1));
    Response sold = get("/prices/markdown-labels/" + code, "CASHIER");
    assertThat(sold.getStatus(), is(409));
    assertThat(sold.readEntity(String.class), containsString("PRICING_MARKDOWN_EXHAUSTED"));
    JsonObject after = data(get("/markdowns/" + id, "MANAGER"));
    assertThat(
        after.getJsonNumber("redeemedQty").bigDecimalValue(),
        comparesEqualTo(new java.math.BigDecimal("2")));
    assertThat(
        after.getJsonNumber("remainingQty").bigDecimalValue(),
        comparesEqualTo(java.math.BigDecimal.ZERO));
    assertThat(
        "another tenant cannot count this one down",
        at("/prices/markdown-redemptions", OTHER_T, "CASHIER", null)
            .post(Entity.entity(last, MediaType.APPLICATION_JSON))
            .getStatus(),
        is(200));
    assertThat(
        post(
                "/prices/markdown-redemptions",
                "{\"orderId\":\"" + ORDER + "\",\"lines\":[]}",
                "CASHIER")
            .getStatus(),
        is(400));
  }

  @Test
  void takingTheStickersOffStopsTheCodeScanning() {
    seedPosPrice(V, "4.00");
    JsonObject m = sticker("3", "50");
    String id = m.getString("id");
    String code = m.getString("labelCode");
    assertThat(
        post("/markdowns/" + id + "/cancel", "{\"reason\":\"\"}", "STOREKEEPER").getStatus(),
        is(400));
    assertThat(
        post("/markdowns/" + id + "/cancel", "{\"reason\":\"wrong shelf\"}", "CASHIER").getStatus(),
        is(403));
    JsonObject cancelled =
        data(post("/markdowns/" + id + "/cancel", "{\"reason\":\"wrong shelf\"}", "STOREKEEPER"));
    assertThat(cancelled.getString("status"), is("CANCELLED"));
    assertThat(cancelled.getString("cancelReason"), is("wrong shelf"));
    Response again = post("/markdowns/" + id + "/cancel", "{\"reason\":\"again\"}", "STOREKEEPER");
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("PRICING_MARKDOWN_NOT_ACTIVE"));
    assertThat(
        "the sticker no longer scans",
        get("/prices/markdown-labels/" + code, "CASHIER").getStatus(),
        is(404));
    Response quote =
        post(
            "/prices/quote",
            "{\"storeId\":\""
                + S
                + "\",\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"markdownId\":\""
                + id
                + "\"}]}",
            "CASHIER");
    assertThat(quote.getStatus(), is(409));
    assertThat(quote.readEntity(String.class), containsString("PRICING_MARKDOWN_CANCELLED"));
    assertThat(
        json(get("/markdowns", "MANAGER", "storeId", S, "status", "CANCELLED")
                .readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(1));
    assertThat(
        json(get("/markdowns", "MANAGER", "storeId", S, "status", "ACTIVE")
                .readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(0));
    assertThat(
        codeOf(get("/markdowns", "MANAGER", "storeId", S, "status", "LOST"), 400),
        is("PRICING_MARKDOWN_STATUS_UNKNOWN"));
    assertThat(
        "the code is free for a new sticker",
        sticker("1", "50").getString("labelCode").equals(code),
        is(false));
    assertThat(
        "another tenant cannot cancel it",
        codeOf(
            at("/markdowns/" + id + "/cancel", OTHER_T, "OWNER", null)
                .post(Entity.entity("{\"reason\":\"x\"}", MediaType.APPLICATION_JSON)),
            404),
        is("PRICING_MARKDOWN_NOT_FOUND"));
  }

  @Test
  void aStickerPastItsDateIsExpiredNotSellable() throws Exception {
    seedPosPrice(V, "4.00");
    JsonObject m = sticker("3", "50");
    String id = m.getString("id");
    String code = m.getString("labelCode");
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "UPDATE pricing.markdowns SET expiry_date = CURRENT_DATE - 1 WHERE id = '" + id + "'");
    }
    assertThat(data(get("/markdowns/" + id, "MANAGER")).getString("status"), is("EXPIRED"));
    Response label = get("/prices/markdown-labels/" + code, "CASHIER");
    assertThat(label.getStatus(), is(409));
    assertThat(label.readEntity(String.class), containsString("PRICING_MARKDOWN_EXPIRED"));
    Response quote =
        post(
            "/prices/quote",
            "{\"storeId\":\""
                + S
                + "\",\"lines\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"markdownId\":\""
                + id
                + "\"}]}",
            "CASHIER");
    assertThat(quote.getStatus(), is(409));
    assertThat(
        json(get("/markdowns", "MANAGER", "storeId", S, "status", "EXPIRED")
                .readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(1));
    assertThat(
        json(get("/markdowns", "MANAGER", "storeId", S, "status", "ACTIVE")
                .readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(0));
    assertThat(
        "expired can still be cancelled to tidy up",
        post("/markdowns/" + id + "/cancel", "{\"reason\":\"binned\"}", "STOREKEEPER").getStatus(),
        is(200));
  }

  @Test
  void aStickerCodeStillLiveIsNeverIssuedTwice() throws Exception {
    seedPosPrice(V, "2.99");
    JsonObject first = sticker("6", "25");
    String code = first.getString("labelCode");
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      // The tenant's series has gone round: the next number is 100001, which reads as 1 again.
      st.execute(
          "UPDATE pricing.markdown_label_series SET next_number = 100001 WHERE tenant_id = '"
              + T
              + "'");
    }
    Response again =
        post(
            "/markdowns",
            create(S, V, "6", "\"percentOff\":25", "SHORT_DATED", LocalDate.now().plusDays(2)),
            "STOREKEEPER");
    assertThat(codeOf(again, 409), is("PRICING_MARKDOWN_LABEL_COLLISION"));
    assertThat(
        "the refused sticker was not kept",
        json(get("/markdowns", "MANAGER", "storeId", S).readEntity(String.class))
            .getJsonArray("data")
            .size(),
        is(1));
    assertThat(
        "the first sticker still scans",
        data(get("/prices/markdown-labels/" + code, "CASHIER")).getString("markdownId"),
        is(first.getString("id")));
  }
}
