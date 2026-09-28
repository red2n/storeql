package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
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
 * Fresh yield, preparation and butchery loss (readiness review, Fresh, perishable &amp; food
 * safety).
 *
 * <p>A yield template says what a primal should break into and what share is expected to be lost as
 * bone, fat and trim; a breakdown at a store consumes the primal, makes each cut a batch of its own
 * under the primal's lot with its cost apportioned by share, records the loss against what was
 * expected, and the period's runs are the butchery-loss report. Written before the code.
 */
@HelidonTest
class YieldIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a091ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a091ae-611e-702c-a97b-d1b8025478e2";
  private static final String STORE = "01a091ae-611e-703c-a378-a4972ea461e1";
  private static final String SIDE = "01a091ae-611e-7037-a4b7-c854f0266ae1";
  private static final String SIRLOIN = "01a091ae-611e-7037-a4b7-c854f0266ae2";
  private static final String MINCE = "01a091ae-611e-7037-a4b7-c854f0266ae3";
  private static final String BONES = "01a091ae-611e-7037-a4b7-c854f0266ae4";
  private static final String SUPPLIER = "01a091ae-611e-7040-a4b7-c854f0266ae5";

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
          "TRUNCATE TABLE inventory.yield_run_outputs, inventory.yield_runs,"
              + " inventory.yield_template_outputs, inventory.yield_templates,"
              + " inventory.lot_genealogy, inventory.stock_movements, inventory.reservations,"
              + " inventory.inventory_batches, inventory.processed_events, inventory.outbox"
              + " CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant, String roles) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", "01a091ae-611e-700b-bde4-50df0324c3e1")
            .header("X-Roles", roles);
    return "GET".equals(method) ? b.get() : b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
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

  private static final String TEMPLATE =
      "{\"name\":\"Side of beef\",\"inputVariantId\":\""
          + SIDE
          + "\",\"unit\":\"kg\",\"outputs\":[{\"variantId\":\""
          + SIRLOIN
          + "\",\"expectedPct\":35,\"costShare\":60,\"shelfLifeDays\":5},{\"variantId\":\""
          + MINCE
          + "\",\"expectedPct\":45,\"costShare\":40}]}";

  private String template() {
    return Envelopes.created(post("/admin/inventory/yield/templates", TEMPLATE)).getString("id");
  }

  private JsonObject receiveSide(int qty, String ownership) {
    return Envelopes.created(
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + STORE
                + "\",\"variantId\":\""
                + SIDE
                + "\",\"qty\":"
                + qty
                + ",\"batchNo\":\"LOT-A\",\"costPrice\":5.00,\"expiryDate\":\""
                + LocalDate.now().plusDays(10)
                + "\""
                + (ownership == null
                    ? ""
                    : ",\"ownership\":\"" + ownership + "\",\"supplierId\":\"" + SUPPLIER + "\"")
                + "}"));
  }

  private static String run(String templateId, int input, int sirloin, int mince, String extra) {
    return "{\"storeId\":\""
        + STORE
        + "\",\"templateId\":\""
        + templateId
        + "\",\"inputQty\":"
        + input
        + ",\"outputs\":[{\"variantId\":\""
        + SIRLOIN
        + "\",\"qty\":"
        + sirloin
        + "},{\"variantId\":\""
        + MINCE
        + "\",\"qty\":"
        + mince
        + "}]"
        + extra
        + "}";
  }

  private BigDecimal level(String variant, String field) {
    JsonArray levels = Envelopes.okArray(get("/admin/inventory/levels?store=" + STORE));
    for (int i = 0; i < levels.size(); i++) {
      JsonObject l = levels.getJsonObject(i);
      if (variant.equals(l.getString("variantId"))) {
        return l.getJsonNumber(field).bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  // ── the template: what a primal should yield ───────────────────────────────

  @Test
  void aTemplateSaysWhatAPrimalShouldYieldAndWhatIsExpectedToBeLost() {
    JsonObject t = Envelopes.created(post("/admin/inventory/yield/templates", TEMPLATE));
    assertThat(t.getString("name"), is("Side of beef"));
    assertThat(t.getString("inputVariantId"), is(SIDE));
    assertThat(t.getBoolean("active"), is(true));
    assertThat(
        t.getJsonNumber("expectedLossPct").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("20")));
    JsonArray outputs = t.getJsonArray("outputs");
    assertThat(outputs.size(), is(2));
    JsonObject sirloin = Envelopes.find(outputs, "variantId", SIRLOIN);
    assertThat(
        sirloin.getJsonNumber("expectedPct").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("35")));
    assertThat(sirloin.getInt("shelfLifeDays"), is(5));
    JsonObject mince = Envelopes.find(outputs, "variantId", MINCE);
    // A cost share left unsaid is the expected share: cost follows weight.
    assertThat(
        mince.getJsonNumber("costShare").bigDecimalValue(), comparesEqualTo(new BigDecimal("40")));

    // Refused by name: nothing to yield, more than the whole, the primal as its own cut, a cashier.
    assertThat(
        code(
            post(
                "/admin/inventory/yield/templates",
                "{\"name\":\"Empty\",\"inputVariantId\":\"" + SIDE + "\",\"outputs\":[]}"),
            400),
        is("INVENTORY_YIELD_OUTPUTS_REQUIRED"));
    assertThat(
        code(
            post(
                "/admin/inventory/yield/templates",
                "{\"name\":\"Too much\",\"inputVariantId\":\""
                    + SIDE
                    + "\",\"outputs\":[{\"variantId\":\""
                    + SIRLOIN
                    + "\",\"expectedPct\":70},{\"variantId\":\""
                    + MINCE
                    + "\",\"expectedPct\":40}]}"),
            400),
        is("INVENTORY_YIELD_SHARES_INVALID"));
    assertThat(
        code(
            post(
                "/admin/inventory/yield/templates",
                "{\"name\":\"Circular\",\"inputVariantId\":\""
                    + SIDE
                    + "\",\"outputs\":[{\"variantId\":\""
                    + SIDE
                    + "\",\"expectedPct\":90}]}"),
            400),
        is("INVENTORY_YIELD_OUTPUT_IS_INPUT"));
    assertThat(
        call("POST", "/admin/inventory/yield/templates", TEMPLATE, T, "CASHIER").getStatus(),
        is(403));

    // Listed for this business, unseen by another; ended, it breaks nothing more.
    assertThat(Envelopes.okArray(get("/admin/inventory/yield/templates")).size(), is(1));
    assertThat(
        Envelopes.okArray(call("GET", "/admin/inventory/yield/templates", null, T2, "OWNER"))
            .size(),
        is(0));
    String id = t.getString("id");
    assertThat(post("/admin/inventory/yield/templates/" + id + "/end", "{}").getStatus(), is(200));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/yield/templates"))
            .getJsonObject(0)
            .getBoolean("active"),
        is(false));
    receiveSide(100, null);
    assertThat(
        code(post("/admin/inventory/yield/runs", run(id, 100, 34, 44, "")), 409),
        is("INVENTORY_YIELD_TEMPLATE_ENDED"));
    assertThat(
        code(
            call("POST", "/admin/inventory/yield/runs", run(id, 100, 34, 44, ""), T2, "OWNER"),
            404),
        is("INVENTORY_YIELD_TEMPLATE_NOT_FOUND"));
  }

  // ── the breakdown: the primal consumed, the cuts made at cost, the loss known ─

  @Test
  void aBreakdownConsumesThePrimalMakesTheCutsAtCostAndRecordsTheLossAgainstExpected() {
    String templateId = template();
    JsonObject side = receiveSide(100, null);

    JsonObject run =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/yield/runs",
                run(templateId, 100, 34, 44, ",\"reference\":\"Monday side\""),
                T,
                "STOREKEEPER"));
    assertThat(
        run.getJsonNumber("inputQty").bigDecimalValue(), comparesEqualTo(new BigDecimal("100")));
    assertThat(
        run.getJsonNumber("inputCost").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("500.00")));
    assertThat(
        run.getJsonNumber("outputQty").bigDecimalValue(), comparesEqualTo(new BigDecimal("78")));
    assertThat(
        run.getJsonNumber("lossQty").bigDecimalValue(), comparesEqualTo(new BigDecimal("22")));
    assertThat(
        run.getJsonNumber("lossPct").bigDecimalValue(), comparesEqualTo(new BigDecimal("22")));
    assertThat(
        run.getJsonNumber("expectedLossQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("20")));
    assertThat(
        run.getJsonNumber("lossVariance").bigDecimalValue(), comparesEqualTo(new BigDecimal("2")));
    // The loss at the primal's cost: what the bin took, for the report; the cuts absorb it.
    assertThat(
        run.getJsonNumber("lossAtCost").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("110.00")));
    assertThat(run.getString("reference"), is("Monday side"));
    JsonArray outputs = run.getJsonArray("outputs");
    JsonObject sirloin = Envelopes.find(outputs, "variantId", SIRLOIN);
    assertThat(
        sirloin.getJsonNumber("expectedQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("35")));
    assertThat(
        sirloin.getJsonNumber("unitCost").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("8.82")));
    JsonObject mince = Envelopes.find(outputs, "variantId", MINCE);
    assertThat(
        mince.getJsonNumber("unitCost").bigDecimalValue(), comparesEqualTo(new BigDecimal("4.55")));

    // The primal is gone; the cuts are on the shelf.
    assertThat(level(SIDE, "onHand"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(level(SIRLOIN, "available"), comparesEqualTo(new BigDecimal("34")));
    assertThat(level(MINCE, "available"), comparesEqualTo(new BigDecimal("44")));

    // Each cut is a batch under the primal's lot: the sirloin dated by its own shelf life, the
    // mince by the side's date; both children of the side in the genealogy.
    JsonObject sirloinBatch =
        Envelopes.okArray(get("/admin/inventory/batches?store=" + STORE + "&variant=" + SIRLOIN))
            .getJsonObject(0);
    assertThat(sirloinBatch.getString("batchNo"), is("LOT-A"));
    assertThat(sirloinBatch.getString("expiryDate"), is(LocalDate.now().plusDays(5).toString()));
    assertThat(sirloinBatch.getString("id"), is(sirloin.getString("batchId")));
    JsonObject minceBatch =
        Envelopes.okArray(get("/admin/inventory/batches?store=" + STORE + "&variant=" + MINCE))
            .getJsonObject(0);
    assertThat(minceBatch.getString("expiryDate"), is(LocalDate.now().plusDays(10).toString()));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.lot_genealogy WHERE relation_type = 'TRANSFORM'"
                + " AND parent_batch_id = '"
                + side.getString("id")
                + "'"),
        is("2"));
    // One movement out of the primal, one into each cut: a story of its own.
    assertThat(
        Envelopes.scalar(PG, "SELECT count(*) FROM inventory.stock_movements WHERE type = 'YIELD'"),
        is("3"));
    // The stock projections elsewhere hear the primal go and the cuts arrive; the run itself is
    // announced.
    String adjusted =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type = 'StockAdjusted'");
    assertThat(adjusted, containsString("\"variantId\":\"" + SIDE + "\""));
    assertThat(adjusted, containsString("\"delta\":-100"));
    // Three arrivals were announced: the side's own receipt, then each cut as it was made.
    String received =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type = 'StockReceived'");
    assertThat(
        received, containsString("\"aggregateId\":\"" + sirloin.getString("batchId") + "\""));
    assertThat(received, containsString("\"aggregateId\":\"" + mince.getString("batchId") + "\""));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.outbox WHERE event_type = 'StockReceived'"),
        is("3"));
    String recorded =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type = 'YieldRecorded'");
    assertThat(recorded, containsString("\"lossQty\":22"));
    assertThat(recorded, containsString("\"lossAtCost\":110.00"));

    // The period's runs are the butchery-loss report.
    String today = LocalDate.now().toString();
    JsonObject report =
        Envelopes.ok(
            get("/admin/inventory/yield/runs?storeId=" + STORE + "&from=2026-01-01&to=" + today));
    assertThat(report.getJsonArray("runs").size(), is(1));
    JsonObject totals = report.getJsonObject("totals");
    assertThat(totals.getInt("runs"), is(1));
    assertThat(
        totals.getJsonNumber("inputQty").bigDecimalValue(), comparesEqualTo(new BigDecimal("100")));
    assertThat(
        totals.getJsonNumber("lossQty").bigDecimalValue(), comparesEqualTo(new BigDecimal("22")));
    assertThat(
        totals.getJsonNumber("expectedLossQty").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("20")));
    assertThat(
        totals.getJsonNumber("lossAtCost").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("110.00")));
    // Another business sees none of it.
    assertThat(
        Envelopes.ok(
                call(
                    "GET",
                    "/admin/inventory/yield/runs?from=2026-01-01&to=" + today,
                    null,
                    T2,
                    "OWNER"))
            .getJsonArray("runs")
            .size(),
        is(0));
  }

  // ── refused by name ────────────────────────────────────────────────────────

  @Test
  void aBreakdownIsRefusedWhenItMakesMoreThanItTookOrTakesWhatIsNotThere() {
    String templateId = template();
    receiveSide(50, null);

    // More out than in is not a yield.
    assertThat(
        code(post("/admin/inventory/yield/runs", run(templateId, 50, 30, 25, "")), 400),
        is("INVENTORY_YIELD_OUTPUT_EXCEEDS_INPUT"));
    // A cut the template never named.
    assertThat(
        code(
            post(
                "/admin/inventory/yield/runs",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"templateId\":\""
                    + templateId
                    + "\",\"inputQty\":50,\"outputs\":[{\"variantId\":\""
                    + BONES
                    + "\",\"qty\":10}]}"),
            400),
        is("INVENTORY_YIELD_OUTPUT_UNKNOWN"));
    // More primal than is on the shelf.
    assertThat(
        code(post("/admin/inventory/yield/runs", run(templateId, 80, 28, 36, "")), 422),
        is("INVENTORY_YIELD_INSUFFICIENT_INPUT"));
    // Nothing was drawn by the refusals.
    assertThat(level(SIDE, "onHand"), comparesEqualTo(new BigDecimal("50")));
    assertThat(
        Envelopes.scalar(PG, "SELECT count(*) FROM inventory.stock_movements WHERE type = 'YIELD'"),
        is("0"));

    // A primal the supplier still owns is not the business's to break down.
    receiveSide(100, "CONSIGNMENT");
    assertThat(
        code(post("/admin/inventory/yield/runs", run(templateId, 120, 40, 50, "")), 409),
        is("INVENTORY_YIELD_INPUT_NOT_OWNED"));
    assertThat(level(SIDE, "onHand"), comparesEqualTo(new BigDecimal("150")));
  }
}
