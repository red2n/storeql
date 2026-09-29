package com.storeql.inventory;

import static com.storeql.test.Envelopes.created;
import static com.storeql.test.Envelopes.find;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.okArray;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Recalls against real Postgres: opening one takes every pack in scope off sale at every store,
 * holds a pack it cannot rule out, and holds stock that arrives later; a store's final disposition
 * takes the stock off the books; a recall closes only when no store still holds recalled stock;
 * cancelling puts stock back only where nothing else holds it. A recall finds the sales that drew
 * on its packs and announces each order to whoever knows the buyer (05.10), and offers a remedy and
 * a contact — two remedies and plain words where GPSR binds. Kafka and Consul disabled, so events
 * are asserted in the outbox; tenant-svc is a stub.
 */
@HelidonTest
class RecallIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  static {
    PG = PostgresSupport.start();
    // A British business and a German one: GPSR's recall-notice rule reaches the German.
    TENANTS =
        TenantSvcStub.start()
            .with(RecallIT.T, "GBP", "GB")
            .with(RecallIT.OTHER, "GBP", "GB")
            .with(RecallIT.DE, "EUR", "DE")
            .withObligation("DE", "GPSR_RECALL_NOTICE", "EU", "2024-12-13", null);
    PG.wire("inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.inventory.food-safety.overdue-sweeper.enabled", "false");
  }

  private static final String T = "01a090ae-611e-700f-b645-a14095230b77";
  private static final String OTHER = "01a090ae-611e-701d-9d60-a9d7516ed03b";
  private static final String DE = "01a090ae-611e-701e-8f3a-2c1d6b7e9a10";
  private static final String MANAGER = "01a090ae-611e-7031-ace6-811d51dcecba";
  private static final String STAFF = "01a090ae-611e-7032-8c9c-1dc9a2769104";

  @Inject WebTarget target;
  @Inject InventoryService inventoryService;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  // ── the buyers (05.10) ─────────────────────────────────────────────────────

  @Test
  void aRecallFindsEveryBuyerOfThePacksInScopeAndAnnouncesEachOrderOnce() {
    String storeA = uuid();
    String storeB = uuid();
    String storeC = uuid();
    String variant = uuid();
    receive(storeA, variant, "10", "L1", "2026-10-01");
    receive(storeB, variant, "5", "L2", "2026-10-01");
    receive(storeC, variant, "4", null, "2026-10-01");
    String orderA = sell(storeA, variant, "3");
    String orderB = sell(storeB, variant, "2");
    String orderC = sell(storeC, variant, "1");

    JsonObject recall = created(open("BUY-1", "RECALL", lotLine(variant, "L1")));
    // The order that drew lot L1 and the one that drew a pack with no lot; not the one from L2.
    assertThat(recall.getInt("ordersAffected"), is(2));
    assertThat(recall.getJsonNumber("qtySold").bigDecimalValue().toPlainString(), is("4.000"));
    assertThat(recall.getJsonArray("remedies").toString(), is("[\"REFUND\",\"REPLACEMENT\"]"));
    assertThat(recall.getString("contactPhone"), is("0800 100 200"));

    String toA = outboxPayload("RecallSaleAffected", orderA);
    assertThat(toA, containsString("\"recallId\":\"" + recall.getString("id") + "\""));
    assertThat(toA, containsString("\"reference\":\"BUY-1\""));
    assertThat(toA, containsString("\"customerNotice\":\"Do not eat."));
    assertThat(toA, containsString("\"remedies\":[\"REFUND\",\"REPLACEMENT\"]"));
    assertThat(toA, containsString("\"contactPhone\":\"0800 100 200\""));
    assertThat(toA, containsString("\"storeId\":\"" + storeA + "\""));
    assertThat(toA, containsString("\"batchNo\":\"L1\""));
    assertThat(toA, containsString("\"qty\":3.000"));
    assertThat(toA, containsString("\"match\":\"IN_SCOPE\""));
    String toC = outboxPayload("RecallSaleAffected", orderC);
    assertThat(toC, containsString("\"match\":\"LOT_UNKNOWN\""));
    assertThat(toC, containsString("\"batchNo\":null"));
    assertThat(outboxPayload("RecallSaleAffected", orderB), is((String) null));
    assertThat(
        scalar(
            PG,
            "SELECT COUNT(*) FROM inventory.recall_sales WHERE recall_id = '"
                + recall.getString("id")
                + "'"),
        is("2"));

    // A withdrawal records who was reached, for the manager weighing whether to recall, and
    // announces nobody: the L2 sale, and again the sale from the pack with no lot, which no lot
    // can rule out. A recall of sales from tomorrow finds none.
    JsonObject withdrawal = created(open("BUY-2", "WITHDRAWAL", lotLine(variant, "L2")));
    assertThat(withdrawal.getInt("ordersAffected"), is(2));
    assertThat(withdrawal.getJsonArray("remedies").size(), is(0));
    assertThat(outboxPayload("RecallSaleAffected", orderB), is((String) null));
    JsonObject later =
        created(
            send(
                "POST",
                "/admin/recalls",
                openJson("BUY-3", "RECALL", lotLine(variant, "L1"), "Do not eat.")
                    .replace("\"items\"", "\"soldFrom\":\"2099-01-01\",\"items\""),
                T,
                "OWNER",
                MANAGER,
                null));
    assertThat(later.getInt("ordersAffected"), is(0));
    assertThat(later.getString("soldFrom"), is("2099-01-01"));

    JsonArray listed =
        okArray(send("GET", "/admin/inventory/recalls", null, T, "OWNER", MANAGER, null));
    assertThat(find(listed, "id", recall.getString("id")).getInt("ordersAffected"), is(2));
  }

  @Test
  void aRecallNeedsARemedyAndAContactAndWhereGpsrBindsTwoRemediesAndPlainWords() {
    String variant = uuid();
    String line = lotLine(variant, "L1");
    String base =
        "{\"reference\":\"%s\",\"kind\":\"RECALL\",\"hazard\":\"ALLERGEN\","
            + "\"reason\":\"Undeclared peanut\",\"source\":\"FSA\","
            + "\"customerNotice\":\"%s\",%s\"items\":["
            + line
            + "]}";
    String plain = "Do not eat. Bring it back.";

    // A British business: one remedy and a number suffice, but not none of either.
    refused(T, base.formatted("GB-1", plain, ""), "RECALL_REMEDIES_REQUIRED");
    refused(
        T, base.formatted("GB-2", plain, "\"remedies\":[\"REFUND\"],"), "RECALL_CONTACT_REQUIRED");
    refused(
        T,
        base.formatted("GB-3", plain, "\"remedies\":[\"REFUND\"],\"contactUrl\":\"ftp://x\","),
        "RECALL_CONTACT_URL_INVALID");
    refused(
        T,
        base.formatted("GB-4", plain, "\"remedies\":[\"CASH\"],\"contactPhone\":\"0800\","),
        "VALIDATION_FAILED");
    refused(
        T,
        base.formatted("GB-5", plain, "\"remedies\":[\"REFUND\"],\"contactPhone\":\"call me\","),
        "VALIDATION_FAILED");
    JsonObject gb =
        created(
            send(
                "POST",
                "/admin/recalls",
                base.formatted(
                    "GB-6",
                    "A precautionary recall. Do not eat.",
                    "\"remedies\":[\"REFUND\"],\"contactPhone\":\"0800 100 200\","),
                T,
                "OWNER",
                MANAGER,
                null));
    assertThat(gb.getJsonArray("remedies").toString(), is("[\"REFUND\"]"));

    // A German business is bound by GPSR: two remedies unless a reason, and no soft words.
    refused(
        DE,
        base.formatted(
            "DE-1", plain, "\"remedies\":[\"REFUND\"],\"contactPhone\":\"0800 100 200\","),
        "RECALL_REMEDIES_INSUFFICIENT");
    Response soft =
        send(
            "POST",
            "/admin/recalls",
            base.formatted(
                "DE-2",
                "A precautionary recall. Do not eat.",
                "\"remedies\":[\"REFUND\",\"REPLACEMENT\"],\"contactPhone\":\"0800 100 200\","),
            DE,
            "OWNER",
            MANAGER,
            null);
    assertThat(soft.getStatus(), is(400));
    String softBody = soft.readEntity(String.class);
    assertThat(softBody, containsString("RECALL_NOTICE_MINIMISES_RISK"));
    assertThat(softBody, containsString("precautionary"));
    JsonObject one =
        created(
            send(
                "POST",
                "/admin/recalls",
                base.formatted(
                    "DE-3",
                    plain,
                    "\"remedies\":[\"REFUND\"],\"singleRemedyReason\":\"Food cannot be repaired or replaced once opened\",\"contactUrl\":\"https://recall.example.de\","),
                DE,
                "OWNER",
                MANAGER,
                null));
    assertThat(one.getString("singleRemedyReason"), containsString("cannot be repaired"));
    assertThat(one.getString("contactUrl"), is("https://recall.example.de"));
    // A withdrawal offers nothing and needs nothing.
    assertThat(created(open("DE-4", "WITHDRAWAL", line)).getJsonArray("remedies").size(), is(0));
    // Nobody but management opens one.
    assertThat(
        send(
                "POST",
                "/admin/recalls",
                base.formatted(
                    "GB-7", plain, "\"remedies\":[\"REFUND\"],\"contactPhone\":\"0800\","),
                T,
                "CASHIER",
                STAFF,
                null)
            .getStatus(),
        is(403));
  }

  @Test
  void twentyOpensOfOneNoticeMakeOneRecallAndItsBuyersAreAnnouncedOnce() throws Exception {
    String store = uuid();
    String variant = uuid();
    receive(store, variant, "10", "L9", "2026-10-01");
    String order = sell(store, variant, "2");
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return open("RACE-1", "RECALL", lotLine(variant, "L9")).getStatus();
                }));
      }
      go.countDown();
      int opened = 0;
      int refused = 0;
      for (Future<Integer> f : results) {
        int status = f.get();
        if (status == 201) opened++;
        else if (status == 409) refused++;
      }
      assertThat(opened, is(1));
      assertThat(refused, is(19));
    } finally {
      pool.shutdownNow();
    }
    assertThat(
        scalar(
            PG,
            "SELECT COUNT(*) FROM inventory.outbox WHERE event_type = 'RecallSaleAffected'"
                + " AND aggregate_id = '"
                + order
                + "'"),
        is("1"));
  }

  private void refused(String tenant, String json, String code) {
    Response r = send("POST", "/admin/recalls", json, tenant, "OWNER", MANAGER, null);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(400));
    assertThat(body, containsString(code));
  }

  /** A till sale of the variant at the store, as the OrderFulfilled consumer records it. */
  private String sell(String store, String variant, String qty) {
    UUID order = Ids.newId();
    inventoryService.deductSaleFromOrderOnce(
        Ids.newId(),
        "it",
        Ids.parse(T),
        Ids.parse(store),
        Ids.parse(variant),
        new BigDecimal(qty),
        order);
    return order.toString();
  }

  // ── opening ────────────────────────────────────────────────────────────────

  @Test
  void aRecallTakesEveryPackInScopeOffSaleAtEveryStoreAndTheTillSeesIt() {
    String storeA = uuid();
    String storeB = uuid();
    String variant = uuid();
    String aL1 = receive(storeA, variant, "10", "L1", "2026-10-01");
    String aL2 = receive(storeA, variant, "5", "L2", "2026-10-01");
    String bL1 = receive(storeB, variant, "3", "l1", null);
    String bUnknown = receive(storeB, variant, "4", null, "2026-10-01");

    JsonObject recall = created(open("FSA-PRIN-01", "RECALL", lotLine(variant, "L1")));
    Map<String, JsonObject> held = heldByBatch(recall);
    assertThat(held.size(), is(3));
    assertThat(held.get(aL1).getString("match"), is("IN_SCOPE"));
    assertThat(held.get(bL1).getString("match"), is("IN_SCOPE"));
    assertThat(held.get(bUnknown).getString("match"), is("LOT_UNKNOWN"));
    assertThat(held.containsKey(aL2), is(false));

    assertThat(materialStatus(aL1), is("RECALLED"));
    assertThat(materialStatus(bUnknown), is("RECALLED"));
    assertThat(materialStatus(aL2), is("AVAILABLE"));
    assertThat(onHand(storeA, variant), is("5.000"));
    assertThat(onHand(storeB, variant), is("0"));

    String payload = outboxPayload("RecallOpened", recall.getString("id"));
    assertThat(payload, containsString(storeA));
    assertThat(payload, containsString(storeB));
    assertThat(payload, containsString("\"reference\":\"FSA-PRIN-01\""));

    JsonArray active =
        okArray(send("GET", "/admin/inventory/recalls/active", null, T, "CASHIER", STAFF, null));
    JsonObject line = find(active, "recallId", recall.getString("id"));
    assertThat(line.getString("variantId"), is(variant));
    assertThat(line.getString("batchNo"), is("L1"));
    assertThat(line.getString("kind"), is("RECALL"));
    assertThat(line.getString("customerNotice"), containsString("Do not eat"));
    // When it opened: order-svc judges a sale replayed from an offline till by the recalls open
    // when it was rung up.
    assertThat(line.getString("openedAt"), is(recall.getString("openedAt")));
  }

  /**
   * order-svc judges a till sale replayed from an offline queue against every recall open when it
   * was rung up, one closed or cancelled since included, so the read it makes carries those too;
   * the till's own list is the open ones, unchanged. Another business's recalls never appear.
   */
  @Test
  void aReadSinceAMomentAlsoCarriesTheRecallsEndedSinceThen() {
    Instant before = Instant.now().minusSeconds(1);
    String variant = uuid();
    String line = "{\"variantId\":\"" + variant + "\"}";
    String closed = created(open("ENDED-CLOSED", "WITHDRAWAL", line)).getString("id");
    assertThat(close(closed).getStatus(), is(200));
    String cancelled = created(open("ENDED-CANCELLED", "WITHDRAWAL", line)).getString("id");
    assertThat(cancel(cancelled).getStatus(), is(200));
    String stillOpen = created(open("STILL-OPEN", "WITHDRAWAL", line)).getString("id");

    JsonArray tills =
        okArray(send("GET", "/admin/inventory/recalls/active", null, T, "CASHIER", STAFF, null));
    assertThat("the till's list: open recalls only", ids(tills).contains(closed), is(false));
    assertThat(ids(tills).contains(cancelled), is(false));
    JsonObject openLine = find(tills, "recallId", stillOpen);
    assertThat("and no end on it", openLine.containsKey("endedAt"), is(false));
    assertThat(openLine.containsKey("endedAs"), is(false));

    JsonArray since = okArray(activeSince(before.toString(), T));
    JsonObject closedLine = find(since, "recallId", closed);
    assertThat(closedLine.getString("endedAs"), is("CLOSED"));
    assertThat(closedLine.getString("endedAt"), is(get(closed).getString("endedAt")));
    assertThat(closedLine.getString("openedAt"), is(get(closed).getString("openedAt")));
    assertThat(closedLine.getString("variantId"), is(variant));
    JsonObject cancelledLine = find(since, "recallId", cancelled);
    assertThat(cancelledLine.getString("endedAs"), is("CANCELLED"));
    assertThat(cancelledLine.getString("endedAt"), is(get(cancelled).getString("endedAt")));
    assertThat(find(since, "recallId", stillOpen).containsKey("endedAt"), is(false));

    Instant afterBoth = Instant.parse(get(cancelled).getString("endedAt")).plusMillis(1);
    JsonArray later = okArray(activeSince(afterBoth.toString(), T));
    assertThat("ended before the moment asked about", ids(later).contains(closed), is(false));
    assertThat(ids(later).contains(cancelled), is(false));
    assertThat("still open: always there", ids(later).contains(stillOpen), is(true));

    List<String> theirs = ids(okArray(activeSince(before.toString(), OTHER)));
    assertThat(
        "another business asking since the same moment sees none of it",
        theirs.contains(closed) || theirs.contains(cancelled) || theirs.contains(stillOpen),
        is(false));
    Response notAnInstant = activeSince("yesterday", T);
    assertThat(notAnInstant.getStatus(), is(400));
    assertThat(notAnInstant.readEntity(String.class), containsString("RECALL_ENDED_SINCE_INVALID"));
  }

  @Test
  void stockArrivingUnderAnOpenRecallIsHeldAsItArrives() {
    String store = uuid();
    String variant = uuid();
    receive(store, variant, "2", "L7", "2026-10-15");
    String recallId =
        created(
                open(
                    "SUP-7",
                    "WITHDRAWAL",
                    "{\"variantId\":\""
                        + variant
                        + "\",\"expiryFrom\":\"2026-10-01\",\"expiryTo\":\"2026-10-31\"}"))
            .getString("id");

    JsonObject late =
        created(
            send(
                "POST",
                "/admin/inventory/receive",
                receiveJson(store, variant, "6", "L8", "2026-10-20"),
                T,
                "STOREKEEPER",
                STAFF,
                null));
    assertThat(late.getString("materialStatus"), is("RECALLED"));
    assertThat(late.getString("materialStatusReason"), is("Recall SUP-7"));
    JsonObject outOfScope =
        created(
            send(
                "POST",
                "/admin/inventory/receive",
                receiveJson(store, variant, "6", "L9", "2026-11-20"),
                T,
                "STOREKEEPER",
                STAFF,
                null));
    assertThat(outOfScope.getString("materialStatus"), is("AVAILABLE"));

    JsonObject arrived = heldByBatch(get(recallId)).get(late.getString("id"));
    assertThat(arrived.getString("quarantinedOn"), is("ARRIVAL"));
    assertThat(arrived.getString("match"), is("IN_SCOPE"));
  }

  // ── what stores do ─────────────────────────────────────────────────────────

  @Test
  void anUncertainBatchCanBeReleasedAndOneInScopeCannot() {
    String store = uuid();
    String variant = uuid();
    String inScope = receive(store, variant, "3", "L1", null);
    String unknown = receive(store, variant, "4", null, null);
    String recallId = created(open("REL-1", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");

    Response certain = release(recallId, inScope, "STOREKEEPER", null);
    assertThat(certain.getStatus(), is(409));
    assertThat(certain.readEntity(String.class), containsString("RECALL_BATCH_IN_SCOPE"));

    assertThat(release(recallId, unknown, "STOREKEEPER", store).getStatus(), is(200));
    assertThat(materialStatus(unknown), is("AVAILABLE"));
    assertThat(onHand(store, variant), is("4.000"));

    Response again = release(recallId, unknown, "STOREKEEPER", null);
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("RECALL_BATCH_ALREADY_RELEASED"));

    Response otherStore = release(recallId, unknown, "STOREKEEPER", uuid());
    assertThat(otherStore.getStatus(), is(403));
  }

  @Test
  void aRecallClosesOnlyWhenNoStoreStillHoldsRecalledStock() throws Exception {
    String storeA = uuid();
    String storeB = uuid();
    String variant = uuid();
    String aBatch = receive(storeA, variant, "10", "L1", null);
    receive(storeB, variant, "3", "L1", null);
    String recallId = created(open("CLOSE-1", "RECALL", lotLine(variant, "L1"))).getString("id");

    Response early = close(recallId);
    assertThat(early.getStatus(), is(409));
    String earlyBody = early.readEntity(String.class);
    assertThat(earlyBody, containsString("RECALL_STORES_OUTSTANDING"));
    assertThat(earlyBody, containsString(storeA));
    assertThat(earlyBody, containsString(storeB));

    JsonObject held = created(action(recallId, storeA, "10", "HELD_FOR_COLLECTION", storeA));
    assertThat(held.getJsonNumber("systemQty").bigDecimalValue().toPlainString(), is("10.000"));
    assertThat(remainingQty(aBatch), is("10.000"));

    JsonObject destroyed = created(action(recallId, storeA, "9", "DESTROYED", storeA));
    assertThat(
        destroyed.getJsonNumber("systemQty").bigDecimalValue().toPlainString(), is("10.000"));
    assertThat(remainingQty(aBatch), is("0.000"));
    assertThat(
        scalar(
            PG,
            "SELECT qty || '|' || ref_type || '|' || reason_code || '|' || actor_id"
                + " FROM inventory.stock_movements WHERE batch_id = '"
                + aBatch
                + "' AND type = 'ADJUST'"),
        is("-10.000|RECALL|RECALL_WITHDRAWAL|" + STAFF));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM inventory.outbox WHERE event_type = 'StockAdjusted'"
                + " AND payload LIKE '%"
                + storeA
                + "%' AND payload LIKE '%\"delta\":-10.000%'"),
        is("1"));

    Response stillB = close(recallId);
    assertThat(stillB.getStatus(), is(409));
    assertThat(stillB.readEntity(String.class), containsString(storeB));

    created(action(recallId, storeB, "3", "RETURNED_TO_SUPPLIER", null));
    JsonObject closed = ok(close(recallId));
    assertThat(closed.getString("status"), is("CLOSED"));
    JsonObject progressA = find(closed.getJsonArray("stores"), "storeId", storeA);
    assertThat(progressA.getJsonNumber("qtyFound").bigDecimalValue().toPlainString(), is("9.000"));
    assertThat(progressA.getBoolean("outstanding"), is(false));

    JsonArray active =
        okArray(send("GET", "/admin/inventory/recalls/active", null, T, "CASHIER", STAFF, null));
    assertThat(
        active.stream().anyMatch(v -> recallId.equals(v.asJsonObject().getString("recallId"))),
        is(false));

    Response afterClose = action(recallId, storeA, "0", "DESTROYED", null);
    assertThat(afterClose.getStatus(), is(409));
    assertThat(afterClose.readEntity(String.class), containsString("RECALL_NOT_OPEN"));
  }

  // ── cancelling ─────────────────────────────────────────────────────────────

  @Test
  void cancellingPutsStockBackOnlyWhereNoOtherRecallHoldsIt() {
    String store = uuid();
    String variant = uuid();
    String batch = receive(store, variant, "8", "L1", null);
    String every =
        created(open("CANCEL-EVERY", "WITHDRAWAL", "{\"variantId\":\"" + variant + "\"}"))
            .getString("id");
    String byLot =
        created(open("CANCEL-LOT", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");

    assertThat(cancel(every).getStatus(), is(200));
    assertThat(materialStatus(batch), is("RECALLED"));
    assertThat(ok(cancel(byLot)).getString("status"), is("CANCELLED"));
    assertThat(materialStatus(batch), is("AVAILABLE"));
    assertThat(onHand(store, variant), is("8.000"));
  }

  @Test
  void aRecallUnderWhichStockWasDisposedOfCannotBeCancelled() {
    String store = uuid();
    String variant = uuid();
    receive(store, variant, "2", "L1", null);
    String recallId =
        created(open("NO-UNDO", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");
    created(action(recallId, store, "2", "DESTROYED", null));

    Response cancel = cancel(recallId);
    assertThat(cancel.getStatus(), is(409));
    assertThat(cancel.readEntity(String.class), containsString("RECALL_ALREADY_ACTIONED"));
  }

  // ── who may do what ────────────────────────────────────────────────────────

  @Test
  void openingIsManagementWorkAndABadNoticeIsRefused() {
    String variant = uuid();
    Response cashier =
        send(
            "POST",
            "/admin/recalls",
            openJson("ROLE-1", "WITHDRAWAL", lotLine(variant, "L1"), null),
            T,
            "CASHIER",
            STAFF,
            null);
    assertThat(cashier.getStatus(), is(403));
    Response storekeeper =
        send(
            "POST",
            "/admin/recalls",
            openJson("ROLE-1", "WITHDRAWAL", lotLine(variant, "L1"), null),
            T,
            "STOREKEEPER",
            STAFF,
            null);
    assertThat(storekeeper.getStatus(), is(403));

    created(open("Dup-Ref", "WITHDRAWAL", lotLine(variant, "L1")));
    Response duplicate = open("dup-ref", "WITHDRAWAL", lotLine(variant, "L2"));
    assertThat(duplicate.getStatus(), is(409));
    assertThat(duplicate.readEntity(String.class), containsString("RECALL_REFERENCE_TAKEN"));

    Response noNotice =
        send(
            "POST",
            "/admin/recalls",
            openJson("NOTICE-1", "RECALL", lotLine(variant, "L1"), null),
            T,
            "OWNER",
            MANAGER,
            null);
    assertThat(noNotice.getStatus(), is(400));
    assertThat(noNotice.readEntity(String.class), containsString("RECALL_NOTICE_REQUIRED"));

    Response inverted =
        open(
            "DATES-1",
            "WITHDRAWAL",
            "{\"variantId\":\""
                + variant
                + "\",\"expiryFrom\":\"2026-10-31\",\"expiryTo\":\"2026-10-01\"}");
    assertThat(inverted.getStatus(), is(400));
    assertThat(inverted.readEntity(String.class), containsString("RECALL_DATES_INVERTED"));
  }

  @Test
  void aTenantNeitherSeesNorHoldsAnotherTenantsStock() {
    String store = uuid();
    String variant = uuid();
    String batch = receive(store, variant, "5", "L1", null);
    String recallId =
        created(open("TENANT-1", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");

    Response foreignRead =
        send("GET", "/admin/inventory/recalls/" + recallId, null, OTHER, "OWNER", MANAGER, null);
    assertThat(foreignRead.getStatus(), is(404));
    JsonArray foreignActive =
        okArray(
            send("GET", "/admin/inventory/recalls/active", null, OTHER, "CASHIER", STAFF, null));
    assertThat(foreignActive.size(), is(0));

    String foreignBatch =
        created(
                send(
                    "POST",
                    "/admin/inventory/receive",
                    receiveJson(store, variant, "5", "L1", null),
                    OTHER,
                    "STOREKEEPER",
                    STAFF,
                    null))
            .getString("id");
    assertThat(materialStatus(foreignBatch), is("AVAILABLE"));
    assertThat(materialStatus(batch), is("RECALLED"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private Response open(String reference, String kind, String line) {
    return send(
        "POST",
        "/admin/recalls",
        openJson(reference, kind, line, "Do not eat. Return it to the store for a full refund."),
        T,
        "OWNER",
        MANAGER,
        null);
  }

  private static String openJson(String reference, String kind, String line, String notice) {
    return "{\"reference\":\""
        + reference
        + "\",\"kind\":\""
        + kind
        + "\",\"hazard\":\"ALLERGEN\",\"reason\":\"Undeclared peanut\",\"source\":\"FSA\""
        + (notice == null ? "" : ",\"customerNotice\":\"" + notice + "\"")
        + ("RECALL".equals(kind)
            ? ",\"remedies\":[\"REFUND\",\"REPLACEMENT\"],\"contactPhone\":\"0800 100 200\""
            : "")
        + ",\"items\":["
        + line
        + "]}";
  }

  private static String lotLine(String variant, String lot) {
    return "{\"variantId\":\"" + variant + "\",\"batchNo\":\"" + lot + "\"}";
  }

  /** The active read as order-svc makes it for a replayed till sale, as a storekeeper. */
  private Response activeSince(String endedSince, String tenant) {
    return target
        .path("/admin/inventory/recalls/active")
        .queryParam("endedSince", endedSince)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "STOREKEEPER")
        .header("X-User-Id", STAFF)
        .get();
  }

  private static List<String> ids(JsonArray lines) {
    return lines.getValuesAs(JsonObject.class).stream().map(o -> o.getString("recallId")).toList();
  }

  private JsonObject get(String recallId) {
    return ok(
        send("GET", "/admin/inventory/recalls/" + recallId, null, T, "STOREKEEPER", STAFF, null));
  }

  private Response action(
      String recallId, String store, String qty, String disposition, String storeIds) {
    return send(
        "POST",
        "/admin/inventory/recalls/" + recallId + "/stores/" + store + "/actions",
        "{\"qtyFound\":"
            + qty
            + ",\"disposition\":\""
            + disposition
            + "\",\"noticeDisplayed\":true,\"notes\":\"Pulled from aisle 4\"}",
        T,
        "STOREKEEPER",
        STAFF,
        storeIds);
  }

  private Response release(String recallId, String batch, String role, String storeIds) {
    return send(
        "POST",
        "/admin/inventory/recalls/" + recallId + "/batches/" + batch + "/release",
        "{\"reason\":\"Pack shows lot L4, not L1\"}",
        T,
        role,
        STAFF,
        storeIds);
  }

  private Response close(String recallId) {
    return send(
        "POST",
        "/admin/recalls/" + recallId + "/close",
        "{\"notes\":\"Recovered 12 of 13\"}",
        T,
        "MANAGER",
        MANAGER,
        null);
  }

  private Response cancel(String recallId) {
    return send(
        "POST",
        "/admin/recalls/" + recallId + "/cancel",
        "{\"reason\":\"Opened against the wrong product\"}",
        T,
        "OWNER",
        MANAGER,
        null);
  }

  private String receive(String store, String variant, String qty, String lot, String expiry) {
    return created(
            send(
                "POST",
                "/admin/inventory/receive",
                receiveJson(store, variant, qty, lot, expiry),
                T,
                "STOREKEEPER",
                STAFF,
                null))
        .getString("id");
  }

  private static String receiveJson(
      String store, String variant, String qty, String lot, String expiry) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"costPrice\":1.20"
        + (lot == null ? "" : ",\"batchNo\":\"" + lot + "\"")
        + (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
        + "}";
  }

  private static Map<String, JsonObject> heldByBatch(JsonObject recall) {
    Map<String, JsonObject> out = new HashMap<>();
    for (var v : recall.getJsonArray("batches")) {
      out.put(v.asJsonObject().getString("batchId"), v.asJsonObject());
    }
    return out;
  }

  private Response send(
      String method,
      String path,
      String json,
      String tenant,
      String roles,
      String userId,
      String storeIds) {
    Invocation.Builder b =
        target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", roles);
    b = b.header("X-User-Id", userId);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String uuid() {
    return Ids.newId().toString();
  }

  private static String materialStatus(String batchId) {
    return scalar(
        PG, "SELECT material_status FROM inventory.inventory_batches WHERE id = '" + batchId + "'");
  }

  private static String remainingQty(String batchId) {
    return scalar(
        PG, "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '" + batchId + "'");
  }

  private static String onHand(String store, String variant) {
    return scalar(
        PG,
        "SELECT COALESCE(SUM(remaining_qty), 0) FROM inventory.inventory_batches"
            + " WHERE store_id = '"
            + store
            + "' AND variant_id = '"
            + variant
            + "' AND material_status = 'AVAILABLE'");
  }

  private static String outboxPayload(String eventType, String aggregateId) {
    return scalar(
        PG,
        "SELECT payload FROM inventory.outbox WHERE event_type = '"
            + eventType
            + "' AND aggregate_id = '"
            + aggregateId
            + "'");
  }
}
