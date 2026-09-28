package com.storeql.order;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Ship-from-store and dark-store picking, against real Postgres: FULFILLED is picked and packed;
 * the handover after it — a delivery dispatched to a carrier, a pickup collected by its shopper —
 * is recorded once, only on a FULFILLED online order of the right kind, by staff at the order's
 * store; a store's queue is read by stage; the shopper sees their own handover and nobody else's.
 */
@HelidonTest
class HandoverIT {

  private static final String T = "01a0d920-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0d920-611e-702c-a97b-d1b8025478e2";
  // A third business that does hand things over, so what the second is spared stays spared.
  private static final String T3 = "01a0d920-611e-702c-a97b-d1b8025478e3";
  private static final String S = "01a0d920-611e-703c-a378-a4972ea461e1";
  private static final String S2 = "01a0d920-611e-703c-a378-a4972ea461e2";
  private static final String S3 = "01a0d920-611e-703c-a378-a4972ea461e3";
  private static final String V = "01a0d920-611e-7037-a4b7-c854f0266ae1";
  private static final String SHOPPER = "01a0d920-611e-700b-bde4-50df0324c3e1";
  private static final String STRANGER = "01a0d920-611e-700b-bde4-50df0324c3e2";
  private static final String STAFF = "01a0d920-611e-700b-bde4-50df0324c3e3";

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .with(T2, "GBP", "GB")
            .with(T3, "GBP", "GB")
            .withStoreAt(T, S, 53.8008, -1.5491)
            .withStoreAt(T, S2, 53.9600, -1.0873)
            .withStoreAt(T3, S3, 51.5072, -0.1276);
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String storeIds) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("Idempotency-Key", Ids.newId().toString());
    if (user != null) b = b.header("X-User-Id", user);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return "GET".equals(method)
        ? b.get()
        : b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
  }

  private Response asOwner(String method, String path, String json) {
    return call(method, path, json, T, STAFF, "OWNER", null);
  }

  private static String code(Response r, int status) {
    return Envelopes.parse(Envelopes.bodyOf(r, status)).getString("code");
  }

  /** The shopper places an online order at S: a delivery with an address, or a pickup. */
  private UUID place(String fulfilment) {
    return placeAt(T, S, fulfilment);
  }

  /** The shopper places an online order at a store of a business. */
  private UUID placeAt(String tenant, String store, String fulfilment) {
    boolean delivery = "DELIVERY".equals(fulfilment);
    String body =
        "{\"storeId\":\""
            + store
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\""
            + fulfilment
            + "\",\"currency\":\"GBP\",\"contactPhone\":\"07700900123\",\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":2,\"unitPrice\":10.00}]"
            + (delivery
                ? ",\"deliveryLine1\":\"1 Park Row\",\"deliveryCity\":\"Leeds\","
                    + "\"deliveryPostalCode\":\"LS1 5AB\",\"deliveryRecipientName\":\"Sam Shopper\","
                    + "\"deliveryRecipientPhone\":\"07700900123\""
                : "")
            + "}";
    JsonObject placed =
        Envelopes.created(call("POST", "/orders", body, tenant, SHOPPER, "CUSTOMER", null));
    return Ids.parse(placed.getString("id"));
  }

  private void confirm(UUID id) {
    assertThat(asOwner("POST", "/orders/" + id + "/confirm", "{}").getStatus(), is(200));
  }

  /** Picked and packed: FULFILLED, as a completed wave leaves an order. */
  private void pick(UUID id) {
    assertThat(asOwner("POST", "/orders/" + id + "/fulfil", "{}").getStatus(), is(200));
  }

  private UUID picked(String fulfilment) {
    UUID id = place(fulfilment);
    confirm(id);
    pick(id);
    return id;
  }

  private Response dispatch(UUID id, String json, String roles, String storeIds) {
    return call("POST", "/orders/" + id + "/dispatch", json, T, STAFF, roles, storeIds);
  }

  private Response collect(UUID id, String json, String roles, String storeIds) {
    return call("POST", "/orders/" + id + "/collect", json, T, STAFF, roles, storeIds);
  }

  private static String event(String type, UUID orderId) {
    return scalar(
        PG,
        "SELECT payload FROM \"order\".outbox WHERE event_type = '"
            + type
            + "' AND aggregate_id = '"
            + orderId
            + "'");
  }

  // ── the handover ───────────────────────────────────────────────────────────

  @Test
  void aPickedDeliveryIsDispatchedOnceWithACarrier() {
    UUID id = picked("DELIVERY");
    JsonObject order =
        Envelopes.ok(
            dispatch(
                id, "{\"carrier\":\"DPD\",\"reference\":\"1Z999\",\"parcels\":2}", "CASHIER", S));
    JsonObject h = order.getJsonObject("handover");
    assertThat(h.getString("kind"), is("DISPATCHED"));
    assertThat(h.getString("carrier"), is("DPD"));
    assertThat(h.getString("reference"), is("1Z999"));
    assertThat(h.getInt("parcels"), is(2));
    assertThat(h.getString("by"), is(STAFF));
    assertThat(order.getString("status"), is("FULFILLED"));
    // The history says so, and the shopper is told through the event, which names them.
    JsonArray history = Envelopes.okArray(asOwner("GET", "/orders/" + id + "/history", null));
    assertThat(history.toString(), containsString("dispatched: DPD, ref 1Z999"));
    String payload = event("OrderDispatched", id);
    assertThat(payload, containsString("\"carrier\":\"DPD\""));
    assertThat(payload, containsString("\"loginId\":\"" + SHOPPER + "\""));
    // Once: the second dispatch, the wrong kind, and a dispatch with no carrier are refused.
    assertThat(
        code(dispatch(id, "{\"carrier\":\"Evri\"}", "CASHIER", S), 409),
        is("ORDER_ALREADY_HANDED_OVER"));
    assertThat(code(collect(id, "{}", "CASHIER", S), 409), is("ORDER_HANDOVER_KIND_MISMATCH"));
    UUID another = picked("DELIVERY");
    assertThat(dispatch(another, "{}", "CASHIER", S).getStatus(), is(400));
    assertThat(dispatch(another, "{\"carrier\":\"   \"}", "CASHIER", S).getStatus(), is(400));
  }

  @Test
  void aPickedPickupIsCollectedOnceNamingWhoTookIt() {
    UUID id = picked("PICKUP");
    assertThat(
        code(dispatch(id, "{\"carrier\":\"DPD\"}", "CASHIER", S), 409),
        is("ORDER_HANDOVER_KIND_MISMATCH"));
    JsonObject order = Envelopes.ok(collect(id, "{\"collectedBy\":\"Sam Shopper\"}", "CASHIER", S));
    JsonObject h = order.getJsonObject("handover");
    assertThat(h.getString("kind"), is("COLLECTED"));
    assertThat(h.getString("collectedBy"), is("Sam Shopper"));
    assertThat(h.containsKey("carrier"), is(false));
    assertThat(event("OrderCollected", id), containsString("\"collectedBy\":\"Sam Shopper\""));
    assertThat(code(collect(id, "{}", "CASHIER", S), 409), is("ORDER_ALREADY_HANDED_OVER"));
    // Nobody noted: still collected, once.
    UUID quiet = picked("PICKUP");
    JsonObject q = Envelopes.ok(collect(quiet, null, "STOREKEEPER", S));
    assertThat(q.getJsonObject("handover").containsKey("collectedBy"), is(false));
  }

  @Test
  void onlyAnOrderPickedInFullIsHandedOverAndATillSaleNever() {
    UUID waiting = place("DELIVERY");
    confirm(waiting);
    assertThat(
        code(dispatch(waiting, "{\"carrier\":\"DPD\"}", "OWNER", null), 409),
        is("ORDER_NOT_PICKED"));
    // Part-picked: one of two handed over; the parcel is not complete.
    assertThat(
        asOwner(
                "POST",
                "/orders/" + waiting + "/fulfil",
                "{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}")
            .getStatus(),
        is(200));
    assertThat(
        code(dispatch(waiting, "{\"carrier\":\"DPD\"}", "OWNER", null), 409),
        is("ORDER_NOT_PICKED"));
    // A till sale is handed over when it is paid; it is neither dispatched nor collected.
    JsonObject till =
        Envelopes.created(
            asOwner(
                "POST",
                "/orders",
                "{\"storeId\":\""
                    + S
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"currency\":\"GBP\","
                    + "\"items\":[{\"variantId\":\""
                    + V
                    + "\",\"qty\":1,\"unitPrice\":10.00}]}"));
    UUID tillId = Ids.parse(till.getString("id"));
    confirm(tillId);
    assertThat(
        Envelopes.ok(asOwner("GET", "/orders/" + tillId, null)).getString("status"),
        is("FULFILLED"));
    assertThat(
        code(dispatch(tillId, "{\"carrier\":\"DPD\"}", "OWNER", null), 409),
        is("ORDER_HANDOVER_KIND_MISMATCH"));
    assertThat(code(collect(tillId, "{}", "OWNER", null), 409), is("ORDER_HANDOVER_KIND_MISMATCH"));
  }

  @Test
  void staffHandOverOnlyAtTheirOwnStoreWhateverTheirRole() {
    UUID id = picked("DELIVERY");
    assertThat(
        code(dispatch(id, "{\"carrier\":\"DPD\"}", "CASHIER", S2), 403), is("STORE_ACCESS_DENIED"));
    assertThat(
        code(dispatch(id, "{\"carrier\":\"DPD\"}", "STOREKEEPER", S2), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        call(
                "POST",
                "/orders/" + id + "/dispatch",
                "{\"carrier\":\"DPD\"}",
                T,
                SHOPPER,
                "CUSTOMER",
                null)
            .getStatus(),
        is(403));
    assertThat(dispatch(id, "{\"carrier\":\"DPD\"}", "CASHIER", S).getStatus(), is(200));
  }

  // ── the queue, and whose it is ─────────────────────────────────────────────

  @Test
  void theQueueIsReadByStageAndTheHandoverIsTheShoppersOwn() {
    UUID packed = picked("DELIVERY");
    UUID gone = picked("DELIVERY");
    UUID ready = picked("PICKUP");
    assertThat(
        dispatch(gone, "{\"carrier\":\"DPD\",\"reference\":\"1Z1\"}", "CASHIER", S).getStatus(),
        is(200));

    JsonArray awaitingCourier =
        Envelopes.okArray(
            asOwner(
                "GET",
                "/orders?store=" + S + "&status=FULFILLED&fulfilmentType=DELIVERY&handover=PENDING",
                null));
    assertThat(ids(awaitingCourier), org.hamcrest.Matchers.hasItem(packed.toString()));
    assertThat(ids(awaitingCourier), not(org.hamcrest.Matchers.hasItem(gone.toString())));
    assertThat(ids(awaitingCourier), not(org.hamcrest.Matchers.hasItem(ready.toString())));
    JsonArray awaitingShopper =
        Envelopes.okArray(
            asOwner(
                "GET",
                "/orders?store=" + S + "&status=FULFILLED&fulfilmentType=PICKUP&handover=PENDING",
                null));
    assertThat(ids(awaitingShopper), org.hamcrest.Matchers.hasItem(ready.toString()));
    JsonArray done =
        Envelopes.okArray(asOwner("GET", "/orders?store=" + S + "&handover=DONE", null));
    assertThat(ids(done), org.hamcrest.Matchers.hasItem(gone.toString()));
    assertThat(ids(done), not(org.hamcrest.Matchers.hasItem(packed.toString())));
    JsonObject goneRow =
        done.stream()
            .map(JsonValue::asJsonObject)
            .filter(o -> gone.toString().equals(o.getString("id")))
            .findFirst()
            .orElseThrow();
    assertThat(goneRow.getJsonObject("handover").getString("reference"), is("1Z1"));
    assertThat(
        code(asOwner("GET", "/orders?handover=SOMETIME", null), 400),
        is("ORDER_HANDOVER_FILTER_INVALID"));

    // The shopper sees their own handover, in the order and in their history; a stranger sees
    // nothing; another business reads none of it and hands nothing over.
    JsonObject mine =
        Envelopes.ok(call("GET", "/orders/" + gone, null, T, SHOPPER, "CUSTOMER", null));
    assertThat(mine.getJsonObject("handover").getString("carrier"), is("DPD"));
    JsonArray history =
        Envelopes.okArray(call("GET", "/orders/mine", null, T, SHOPPER, "CUSTOMER", null));
    JsonObject listed =
        history.stream()
            .map(JsonValue::asJsonObject)
            .filter(o -> gone.toString().equals(o.getString("id")))
            .findFirst()
            .orElseThrow();
    assertThat(listed.getJsonObject("handover").getString("kind"), is("DISPATCHED"));
    JsonObject unsent =
        history.stream()
            .map(JsonValue::asJsonObject)
            .filter(o -> packed.toString().equals(o.getString("id")))
            .findFirst()
            .orElseThrow();
    assertThat(unsent.get("handover"), nullValue());
    assertThat(
        call("GET", "/orders/" + gone, null, T, STRANGER, "CUSTOMER", null).getStatus(), is(404));
    assertThat(call("GET", "/orders/" + gone, null, T2, STAFF, "OWNER", null).getStatus(), is(404));
    assertThat(
        call(
                "POST",
                "/orders/" + packed + "/dispatch",
                "{\"carrier\":\"DPD\"}",
                T2,
                STAFF,
                "OWNER",
                null)
            .getStatus(),
        is(404));
    assertThat(
        Envelopes.okArray(call("GET", "/orders?handover=DONE", null, T2, STAFF, "OWNER", null)),
        hasSize(0));
  }

  @Test
  void anotherBusinessReadsNoneOfItAndHandsNothingOverEvenNamingOurStore() {
    UUID packed = picked("DELIVERY");
    UUID ready = picked("PICKUP");
    UUID gone = picked("DELIVERY");
    assertThat(dispatch(gone, "{\"carrier\":\"DPD\"}", "CASHIER", S).getStatus(), is(200));
    // Read by id, its history, and the store's queue: nothing, whoever they are over there.
    for (String roles : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER"}) {
      assertThat(
          roles, call("GET", "/orders/" + gone, null, T2, STAFF, roles, S).getStatus(), is(404));
      assertThat(
          roles,
          call("GET", "/orders/" + gone + "/history", null, T2, STAFF, roles, S).getStatus(),
          is(404));
    }
    assertThat(
        Envelopes.okArray(
            call("GET", "/orders?store=" + S + "&handover=DONE", null, T2, STAFF, "OWNER", null)),
        hasSize(0));
    assertThat(
        Envelopes.okArray(
            call(
                "GET",
                "/orders?store=" + S + "&status=FULFILLED&handover=PENDING",
                null,
                T2,
                STAFF,
                "OWNER",
                null)),
        hasSize(0));
    // Writes: a dispatch and a collection by the other business's staff, even one whose store
    // scope names our store's id, are refused before anything is judged (404, never 403 or 409).
    String dpd = "{\"carrier\":\"DPD\"}";
    assertThat(
        code(call("POST", "/orders/" + packed + "/dispatch", dpd, T2, STAFF, "OWNER", null), 404),
        is("ORDER_NOT_FOUND"));
    assertThat(
        code(call("POST", "/orders/" + packed + "/dispatch", dpd, T2, STAFF, "CASHIER", S), 404),
        is("ORDER_NOT_FOUND"));
    assertThat(
        code(call("POST", "/orders/" + ready + "/collect", "{}", T2, STAFF, "CASHIER", S), 404),
        is("ORDER_NOT_FOUND"));
    assertThat(
        code(call("POST", "/orders/" + gone + "/collect", "{}", T2, STAFF, "OWNER", null), 404),
        is("ORDER_NOT_FOUND"));
    // A shopper of the other business, and a stranger of ours, see nothing in their history.
    assertThat(
        Envelopes.okArray(call("GET", "/orders/mine", null, T2, SHOPPER, "CUSTOMER", null)),
        hasSize(0));
    assertThat(
        Envelopes.okArray(call("GET", "/orders/mine", null, T, STRANGER, "CUSTOMER", null)),
        hasSize(0));
    // And nothing of ours moved: the parcel still waits, the bag is still ready, and the one
    // dispatch stays one.
    assertThat(
        Envelopes.ok(asOwner("GET", "/orders/" + packed, null)).get("handover"), nullValue());
    assertThat(Envelopes.ok(asOwner("GET", "/orders/" + ready, null)).get("handover"), nullValue());
    assertThat(
        scalar(PG, "SELECT count(*) FROM \"order\".order_handovers WHERE tenant_id = '" + T2 + "'"),
        is("0"));
    assertThat(
        scalar(
            PG, "SELECT count(*) FROM \"order\".order_handovers WHERE order_id = '" + gone + "'"),
        is("1"));
  }

  // ── handed over today: when it was handed over, not when it was placed ─────

  @Test
  void handedOverTodayIsWhenItWasHandedOverNotWhenItWasPlaced() {
    // Yesterday's order dispatched today — the usual delivery; today's pickup collected today; one
    // of yesterday's still waiting; and one placed and dispatched yesterday.
    UUID lateDelivery = picked("DELIVERY");
    UUID todaysPickup = picked("PICKUP");
    UUID stillWaiting = picked("DELIVERY");
    UUID goneYesterday = picked("DELIVERY");
    Envelopes.exec(
        PG,
        "UPDATE \"order\".orders SET created_at = now() - interval '1 day' WHERE id IN ('"
            + lateDelivery
            + "','"
            + stillWaiting
            + "','"
            + goneYesterday
            + "')");
    assertThat(dispatch(lateDelivery, "{\"carrier\":\"DPD\"}", "CASHIER", S).getStatus(), is(200));
    assertThat(collect(todaysPickup, "{}", "CASHIER", S).getStatus(), is(200));
    assertThat(
        dispatch(goneYesterday, "{\"carrier\":\"Evri\"}", "CASHIER", S).getStatus(), is(200));
    Envelopes.exec(
        PG,
        "UPDATE \"order\".order_handovers SET handed_at = now() - interval '1 day' WHERE"
            + " order_id = '"
            + goneYesterday
            + "'");
    // The store's midnight, as the back office works it out: after yesterday, before today's.
    String since =
        java.time.Instant.now()
            .minus(java.time.Duration.ofHours(1))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString();

    // from= is still when the order was placed: yesterday's order is not among today's.
    java.util.List<String> placedToday =
        ids(
            Envelopes.okArray(
                asOwner("GET", "/orders?store=" + S + "&handover=DONE&from=" + since, null)));
    assertThat(placedToday, org.hamcrest.Matchers.hasItem(todaysPickup.toString()));
    assertThat(placedToday, not(org.hamcrest.Matchers.hasItem(lateDelivery.toString())));

    // handedFrom= is when it was handed over, and on its own it means handover=DONE.
    for (String query :
        new String[] {
          "/orders?store=" + S + "&handedFrom=" + since,
          "/orders?store=" + S + "&handover=DONE&handedFrom=" + since,
          "/orders?store=" + S + "&handover=done&handedFrom=" + since
        }) {
      java.util.List<String> handedToday = ids(Envelopes.okArray(asOwner("GET", query, null)));
      assertThat(query, handedToday, org.hamcrest.Matchers.hasItem(lateDelivery.toString()));
      assertThat(query, handedToday, org.hamcrest.Matchers.hasItem(todaysPickup.toString()));
      assertThat(query, handedToday, not(org.hamcrest.Matchers.hasItem(stillWaiting.toString())));
      assertThat(query, handedToday, not(org.hamcrest.Matchers.hasItem(goneYesterday.toString())));
    }
    // handedTo= is exclusive, the end of the window: yesterday's handover, not today's.
    java.util.List<String> handedBefore =
        ids(Envelopes.okArray(asOwner("GET", "/orders?store=" + S + "&handedTo=" + since, null)));
    assertThat(handedBefore, org.hamcrest.Matchers.hasItem(goneYesterday.toString()));
    assertThat(handedBefore, not(org.hamcrest.Matchers.hasItem(lateDelivery.toString())));
    assertThat(handedBefore, not(org.hamcrest.Matchers.hasItem(stillWaiting.toString())));
    // Both filters together: placed today AND handed over today.
    java.util.List<String> both =
        ids(
            Envelopes.okArray(
                asOwner(
                    "GET",
                    "/orders?store=" + S + "&handedFrom=" + since + "&from=" + since,
                    null)));
    assertThat(both, org.hamcrest.Matchers.hasItem(todaysPickup.toString()));
    assertThat(both, not(org.hamcrest.Matchers.hasItem(lateDelivery.toString())));

    // Refused: a handover window on the orders not yet handed over, and a date that is not one.
    assertThat(
        code(asOwner("GET", "/orders?handover=PENDING&handedFrom=" + since, null), 400),
        is("ORDER_HANDOVER_FILTER_INVALID"));
    assertThat(
        code(asOwner("GET", "/orders?handover=PENDING&handedTo=" + since, null), 400),
        is("ORDER_HANDOVER_FILTER_INVALID"));
    assertThat(code(asOwner("GET", "/orders?handedFrom=yesterday", null), 400), is("INVALID_DATE"));
    assertThat(
        code(asOwner("GET", "/orders?handedTo=2026-13-01T00:00:00Z", null), 400),
        is("INVALID_DATE"));
  }

  @Test
  void anotherBusinessesHandoversAreNeverListedEitherWay() {
    // Both businesses hand something over today.
    UUID ours = picked("DELIVERY");
    assertThat(dispatch(ours, "{\"carrier\":\"DPD\"}", "CASHIER", S).getStatus(), is(200));
    UUID theirs = placeAt(T3, S3, "PICKUP");
    assertThat(
        call("POST", "/orders/" + theirs + "/confirm", "{}", T3, STAFF, "OWNER", null).getStatus(),
        is(200));
    assertThat(
        call("POST", "/orders/" + theirs + "/fulfil", "{}", T3, STAFF, "OWNER", null).getStatus(),
        is(200));
    assertThat(
        call("POST", "/orders/" + theirs + "/collect", "{}", T3, STAFF, "OWNER", null).getStatus(),
        is(200));
    String since =
        java.time.Instant.now()
            .minus(java.time.Duration.ofHours(1))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString();

    // Ours lists ours and never theirs, with or without a store.
    for (String query :
        new String[] {
          "/orders?handedFrom=" + since, "/orders?store=" + S + "&handedFrom=" + since
        }) {
      java.util.List<String> listed = ids(Envelopes.okArray(asOwner("GET", query, null)));
      assertThat(query, listed, org.hamcrest.Matchers.hasItem(ours.toString()));
      assertThat(query, listed, not(org.hamcrest.Matchers.hasItem(theirs.toString())));
    }
    // Theirs, whatever the role and even naming our store, list none of ours.
    for (String roles : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER"}) {
      assertThat(
          roles,
          Envelopes.okArray(
              call(
                  "GET", "/orders?store=" + S + "&handedFrom=" + since, null, T3, STAFF, roles, S)),
          hasSize(0));
      assertThat(
          roles,
          Envelopes.okArray(
              call(
                  "GET",
                  "/orders?store=" + S + "&handover=DONE&handedTo=" + since,
                  null,
                  T3,
                  STAFF,
                  roles,
                  S)),
          hasSize(0));
    }
    java.util.List<String> theirList =
        ids(
            Envelopes.okArray(
                call("GET", "/orders?handedFrom=" + since, null, T3, STAFF, "OWNER", null)));
    assertThat(theirList, org.hamcrest.Matchers.hasItem(theirs.toString()));
    assertThat(theirList, not(org.hamcrest.Matchers.hasItem(ours.toString())));
  }

  private static java.util.List<String> ids(JsonArray orders) {
    return orders.stream().map(v -> v.asJsonObject().getString("id")).toList();
  }
}
