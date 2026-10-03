package com.storeql.tenant;

import static com.storeql.test.Envelopes.created;
import static com.storeql.test.Envelopes.find;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.okArray;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import com.storeql.ids.Ids;
import com.storeql.tenant.service.RetentionService;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Retention schedules (21.16): the floor follows the countries a business trades in; a period
 * longer than the law's is allowed and a shorter one refused by name; every decision is kept; a
 * hold is placed and released once; a purge is registered once per event; and the wrong caller and
 * the wrong input are refused at every step. Kafka and Consul disabled.
 */
@HelidonTest
class RetentionIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start().wire("tenant");
  }

  private static final String BASE = "/admin/tenant/retention";
  private static final String OWNER = Ids.newId().toString();

  @Inject WebTarget target;
  @Inject RetentionService service;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the floor ──────────────────────────────────────────────────────────────

  @Test
  void theFloorFollowsTheCountriesTradedIn() {
    String gb = onboard("GB", "GBP");
    JsonObject sheet = ok(as(BASE, gb, "OWNER").get());
    assertThat(sheet.getString("country"), is("GB"));
    assertThat(sheet.getJsonArray("countries").toString(), is("[\"GB\"]"));
    JsonArray classes = sheet.getJsonArray("classes");
    assertThat(classes.size(), is(4));
    JsonObject transactions = find(classes, "code", "TRANSACTIONS");
    assertThat(transactions.getInt("floorDays"), is(2190));
    assertThat(transactions.getString("floorScope"), is("GB"));
    assertThat(transactions.getString("floorCitation"), containsString("Value Added Tax Act 1994"));
    assertThat(transactions.getString("purgeKind"), is("KEEP"));
    assertThat(transactions.containsKey("periodDays"), is(false));
    JsonObject customers = find(classes, "code", "CUSTOMER_RECORDS");
    assertThat(customers.containsKey("floorDays"), is(false));
    assertThat(customers.getString("purgeKind"), is("ANONYMISE"));
    assertThat(
        find(classes, "code", "NOTIFICATION_LOG").getString("purgedBy"), is("notification-svc"));
    assertThat(find(classes, "code", "ORDER_PERSONAL_DATA").getString("purgedBy"), is("order-svc"));

    // A German shop brings Germany's floor with it: the highest of the countries traded in.
    addStore(gb, "DE");
    JsonObject withShop =
        find(ok(as(BASE, gb, "OWNER").get()).getJsonArray("classes"), "code", "TRANSACTIONS");
    assertThat(withShop.getInt("floorDays"), is(2920));
    assertThat(withShop.getString("floorScope"), is("DE"));
    assertThat(withShop.getString("floorCitation"), containsString("Abgabenordnung"));

    // A business trading nowhere the seed names has no floor, and sets what it decides.
    String us = onboard("US", "USD");
    assertThat(
        find(ok(as(BASE, us, "OWNER").get()).getJsonArray("classes"), "code", "TRANSACTIONS")
            .containsKey("floorDays"),
        is(false));
    assertThat(
        ok(as(BASE + "/TRANSACTIONS", us, "OWNER").put(json("{\"periodDays\":365}")))
            .getInt("periodDays"),
        is(365));
  }

  @Test
  void aPeriodIsNeverShorterThanTheLawAndEveryDecisionIsKept() {
    String gb = onboard("GB", "GBP");
    addStore(gb, "DE");
    Response under = as(BASE + "/TRANSACTIONS", gb, "OWNER").put(json("{\"periodDays\":2190}"));
    assertThat(under.getStatus(), is(400));
    String body = under.readEntity(String.class);
    assertThat(body, containsString("RETENTION_BELOW_LEGAL_MINIMUM"));
    assertThat(body, containsString("2920"));
    assertThat(body, containsString("Abgabenordnung"));
    assertThat(
        ok(as(BASE + "/TRANSACTIONS", gb, "OWNER").put(json("{\"periodDays\":2920}")))
            .getInt("periodDays"),
        is(2920));
    assertThat(
        ok(as(BASE + "/TRANSACTIONS", gb, "OWNER").put(json("{\"periodDays\":3650}")))
            .getInt("periodDays"),
        is(3650));
    assertThat(
        as(BASE + "/TRANSACTIONS", gb, "OWNER").put(json("{\"periodDays\":36501}")).getStatus(),
        is(400));
    assertThat(
        as(BASE + "/TRANSACTIONS", gb, "OWNER").put(json("{\"periodDays\":-1}")).getStatus(),
        is(400));
    assertThat(
        as(BASE + "/TRANSACTIONS", gb, "OWNER")
            .put(json("{\"periodDays\":\"ten years\"}"))
            .getStatus(),
        is(400));
    assertThat(as(BASE + "/TRANSACTIONS", gb, "OWNER").put(json("{}")).getStatus(), is(400));
    Response unknown = as(BASE + "/DIARIES", gb, "OWNER").put(json("{\"periodDays\":10}"));
    assertThat(unknown.getStatus(), is(400));
    assertThat(unknown.readEntity(String.class), containsString("RETENTION_CLASS_UNKNOWN"));
    // Personal data has no statutory floor: the business decides, and may decide "at once".
    assertThat(
        ok(as(BASE + "/CUSTOMER_RECORDS", gb, "OWNER").put(json("{\"periodDays\":0}")))
            .getInt("periodDays"),
        is(0));

    // The latest decision is in force; the earlier ones are kept, never rewritten.
    JsonObject sheet = ok(as(BASE, gb, "OWNER").get());
    assertThat(
        find(sheet.getJsonArray("classes"), "code", "TRANSACTIONS").getInt("periodDays"), is(3650));
    assertThat(
        scalar(
            PG,
            "SELECT COUNT(*) FROM tenant.retention_schedules WHERE tenant_id = '"
                + gb
                + "' AND data_class = 'TRANSACTIONS'"),
        is("2"));

    // Every staff role reads the sheet, because the purgers do; setting it is management.
    assertThat(as(BASE, gb, "STOREKEEPER").get().getStatus(), is(200));
    assertThat(
        as(BASE + "/TRANSACTIONS", gb, "STOREKEEPER")
            .put(json("{\"periodDays\":3650}"))
            .getStatus(),
        is(403));
    assertThat(
        as(BASE + "/TRANSACTIONS", gb, "CASHIER").put(json("{\"periodDays\":3650}")).getStatus(),
        is(403));
    assertThat(as(BASE, gb, "CUSTOMER").get().getStatus(), is(403));
    assertThat(as(BASE, gb, null).get().getStatus(), is(403));
    // Another business has its own sheet, with nothing set.
    String rival = onboard("GB", "GBP");
    assertThat(
        find(ok(as(BASE, rival, "OWNER").get()).getJsonArray("classes"), "code", "TRANSACTIONS")
            .containsKey("periodDays"),
        is(false));
  }

  // ── holds ──────────────────────────────────────────────────────────────────

  @Test
  void aHoldStopsAPurgeUntilItIsReleasedOnce() {
    String gb = onboard("GB", "GBP");
    UUID customer = Ids.newId();
    Response wrongShape =
        as(BASE + "/holds", gb, "OWNER").post(json(hold("ALL", customer.toString(), null)));
    assertThat(wrongShape.getStatus(), is(400));
    assertThat(wrongShape.readEntity(String.class), containsString("RETENTION_HOLD_SUBJECT"));
    assertThat(
        as(BASE + "/holds", gb, "OWNER").post(json(hold("CUSTOMER", null, null))).getStatus(),
        is(400));
    assertThat(
        as(BASE + "/holds", gb, "OWNER").post(json(hold("ORDER", "not-an-id", null))).getStatus(),
        is(400));
    assertThat(
        as(BASE + "/holds", gb, "OWNER")
            .post(json(hold("LOTS", customer.toString(), null)))
            .getStatus(),
        is(400));
    Response unknownClass =
        as(BASE + "/holds", gb, "OWNER")
            .post(json(hold("CUSTOMER", customer.toString(), "DIARIES")));
    assertThat(unknownClass.getStatus(), is(400));
    assertThat(unknownClass.readEntity(String.class), containsString("RETENTION_CLASS_UNKNOWN"));
    assertThat(
        as(BASE + "/holds", gb, "OWNER").post(json("{\"subjectKind\":\"ALL\"}")).getStatus(),
        is(400));
    assertThat(
        as(BASE + "/holds", gb, "CASHIER").post(json(hold("ALL", null, null))).getStatus(),
        is(403));

    JsonObject placed =
        created(
            as(BASE + "/holds", gb, "OWNER")
                .post(json(hold("CUSTOMER", customer.toString(), "CUSTOMER_RECORDS"))));
    assertThat(placed.getBoolean("active"), is(true));
    assertThat(placed.getString("dataClass"), is("CUSTOMER_RECORDS"));
    assertThat(placed.getString("subjectId"), is(customer.toString()));
    JsonObject everything =
        created(as(BASE + "/holds", gb, "OWNER").post(json(hold("ALL", null, null))));
    assertThat(everything.containsKey("dataClass"), is(false));
    assertThat(ok(as(BASE, gb, "OWNER").get()).getJsonArray("holds").size(), is(2));
    assertThat(okArray(as(BASE + "/holds?active=true", gb, "OWNER").get()).size(), is(2));

    String id = placed.getString("id");
    JsonObject released =
        ok(
            as(BASE + "/holds/" + id + "/release", gb, "OWNER")
                .post(json("{\"reason\":\"Dispute settled\"}")));
    assertThat(released.getBoolean("active"), is(false));
    assertThat(released.getString("releaseReason"), is("Dispute settled"));
    Response twice =
        as(BASE + "/holds/" + id + "/release", gb, "OWNER").post(json("{\"reason\":\"Again\"}"));
    assertThat(twice.getStatus(), is(409));
    assertThat(twice.readEntity(String.class), containsString("RETENTION_HOLD_RELEASED"));
    Response unknownHold =
        as(BASE + "/holds/" + Ids.newId() + "/release", gb, "OWNER")
            .post(json("{\"reason\":\"x\"}"));
    assertThat(unknownHold.getStatus(), is(404));
    assertThat(unknownHold.readEntity(String.class), containsString("RETENTION_HOLD_NOT_FOUND"));
    assertThat(
        as(BASE + "/holds/" + id + "/release", gb, "OWNER").post(json("{}")).getStatus(), is(400));
    String rival = onboard("GB", "GBP");
    Response rivalRelease =
        as(BASE + "/holds/" + everything.getString("id") + "/release", rival, "OWNER")
            .post(json("{\"reason\":\"x\"}"));
    assertThat(rivalRelease.getStatus(), is(404));
    assertThat(rivalRelease.readEntity(String.class), containsString("RETENTION_HOLD_NOT_FOUND"));
    assertThat(okArray(as(BASE + "/holds?active=true", gb, "OWNER").get()).size(), is(1));
    assertThat(okArray(as(BASE + "/holds?active=false", gb, "OWNER").get()).size(), is(2));
    assertThat(okArray(as(BASE + "/holds", rival, "OWNER").get()).size(), is(0));
  }

  // ── the register ───────────────────────────────────────────────────────────

  @Test
  void aPurgeIsRegisteredOncePerEventAndNonsenseIsNot() {
    String gb = onboard("GB", "GBP");
    String rival = onboard("GB", "GBP");
    String event = run(gb, "order-svc", "ORDER_PERSONAL_DATA", 3, 1);
    assertThat(service.recordRun(event), is(true));
    assertThat(service.recordRun(event), is(false));
    assertThat(service.recordRun(run(gb, "diary-svc", "DIARIES", 1, 0)), is(false));
    assertThat(service.recordRun(run(gb, "order-svc", "ORDER_PERSONAL_DATA", -1, 0)), is(false));
    assertThat(service.recordRun("{\"eventId\":\"nope\"}"), is(false));
    assertThat(service.recordRun("not json"), is(false));
    assertThat(service.recordRun(run(gb, "notification-svc", "NOTIFICATION_LOG", 0, 0)), is(true));

    JsonArray runs = okArray(as(BASE + "/runs", gb, "OWNER").get());
    assertThat(runs.size(), is(2));
    JsonObject first = find(runs, "dataClass", "ORDER_PERSONAL_DATA");
    assertThat(first.getString("service"), is("order-svc"));
    assertThat(first.getInt("rowsAffected"), is(3));
    assertThat(first.getInt("heldSkipped"), is(1));
    assertThat(okArray(as(BASE + "/runs", rival, "OWNER").get()).size(), is(0));
    assertThat(as(BASE + "/runs", gb, "STOREKEEPER").get().getStatus(), is(403));
    assertThat(okArray(as(BASE + "/runs?limit=1", gb, "OWNER").get()).size(), is(1));
  }

  @Test
  void twentyDecisionsAtOnceAreAllKeptAndOneIsInForce() throws Exception {
    String gb = onboard("GB", "GBP");
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        int days = i;
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return as(BASE + "/NOTIFICATION_LOG", gb, "OWNER")
                      .put(json("{\"periodDays\":" + days + "}"))
                      .getStatus();
                }));
      }
      go.countDown();
      int okCount = 0;
      for (Future<Integer> f : results) {
        if (f.get() == 200) okCount++;
      }
      assertThat(okCount, is(20));
    } finally {
      pool.shutdownNow();
    }
    assertThat(
        scalar(
            PG,
            "SELECT COUNT(*) FROM tenant.retention_schedules WHERE tenant_id = '"
                + gb
                + "' AND data_class = 'NOTIFICATION_LOG'"),
        is("20"));
    int inForce =
        find(ok(as(BASE, gb, "OWNER").get()).getJsonArray("classes"), "code", "NOTIFICATION_LOG")
            .getInt("periodDays");
    assertThat(inForce, lessThan(20));
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static String hold(String kind, String subjectId, String dataClass) {
    return "{\"subjectKind\":\""
        + kind
        + "\""
        + (subjectId == null ? "" : ",\"subjectId\":\"" + subjectId + "\"")
        + (dataClass == null ? "" : ",\"dataClass\":\"" + dataClass + "\"")
        + ",\"reason\":\"Open dispute\"}";
  }

  private static String run(String tenant, String svc, String dataClass, int rows, int held) {
    return Json.createObjectBuilder()
        .add("eventId", Ids.newId().toString())
        .add("eventType", "RetentionRunCompleted")
        .add("tenantId", tenant)
        .add("service", svc)
        .add("dataClass", dataClass)
        .add("cutoff", "2026-09-01T00:00:00Z")
        .add("rowsAffected", rows)
        .add("heldSkipped", held)
        .add("startedAt", "2026-09-14T03:00:00Z")
        .add("finishedAt", "2026-09-14T03:00:02Z")
        .build()
        .toString();
  }

  private static Entity<String> json(String body) {
    return Entity.entity(body, MediaType.APPLICATION_JSON);
  }

  private Invocation.Builder as(String path, String tenant, String roles) {
    String[] parts = path.split("\\?", 2);
    WebTarget t = target.path(parts[0]);
    if (parts.length == 2) {
      for (String pair : parts[1].split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv[1]);
      }
    }
    var b =
        t.request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", OWNER);
    if (roles != null) b = b.header("X-Roles", roles);
    return b;
  }

  private String onboard(String country, String currency) {
    return created(
            target
                .path("/onboarding/tenants")
                .request(MediaType.APPLICATION_JSON)
                .header("X-User-Id", Ids.newId().toString())
                .post(
                    json(
                        "{\"businessName\":\"Retention "
                            + country
                            + " "
                            + Ids.newId()
                            + "\",\"country\":\""
                            + country
                            + "\",\"currency\":\""
                            + currency
                            + "\"}")))
        .getString("id");
  }

  private void addStore(String tenant, String country) {
    created(
        as("/admin/stores", tenant, "OWNER")
            .post(
                json(
                    "{\"name\":\"Shop "
                        + country
                        + "\",\"code\":\"S-"
                        + Ids.newId()
                        + "\",\"country\":\""
                        + country
                        + "\",\"timezone\":\"Europe/Berlin\"}")));
  }
}
