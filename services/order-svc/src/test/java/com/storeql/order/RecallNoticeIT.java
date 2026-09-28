package com.storeql.order;

import static com.storeql.test.Envelopes.created;
import static com.storeql.test.Envelopes.find;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.okArray;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.messaging.RecallSaleAffectedHandler;
import com.storeql.order.service.OrderService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
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
 * A recall's notices to buyers (05.10) against real Postgres: an order inventory-svc names becomes
 * a notice to whoever it identifies — a login, the shop's customer record, a guest's number — once,
 * however often the event arrives; a shopper sees only their own and chooses a remedy once; staff
 * see a recall's, settle them, and a refund through a return settles the notice in the return's
 * transaction. Kafka and Consul disabled: the handler is driven directly and events are read from
 * the outbox; product-svc is not running, so lines carry no name.
 */
@HelidonTest
class RecallNoticeIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String T = "01a090ae-611e-7060-8510-000000000001";
  private static final String RIVAL = "01a090ae-611e-7060-8510-000000000002";
  private static final String S = "01a090ae-611e-7060-8510-000000000011";
  private static final String V = "01a090ae-611e-7060-8510-000000000021";
  private static final String OWNER = "01a090ae-611e-7060-8510-000000000031";

  static {
    PG = PostgresSupport.start();
    TENANTS = TenantSvcStub.start().with(T, "GBP", "GB").with(RIVAL, "GBP", "GB");
    PG.wire("order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;
  @Inject RecallSaleAffectedHandler handler;
  @Inject OrderService orderService;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  // ── who is told ─────────────────────────────────────────────────────────────

  @Test
  void eachBuyerTheOrderIdentifiesIsToldOnceAndAnAnonymousTillSaleIsKeptForTheCount() {
    UUID login = Ids.newId();
    UUID customer = Ids.newId();
    String byLogin = placeAsShopper(login);
    String byRecord = placeAtTill(customer, null);
    String byPhone = placeAsGuest("+447700900123");
    String anonymous = placeAtTill(null, null);
    UUID recall = Ids.newId();

    String toLogin = saleAffected(recall, byLogin, "RECALL", "REFUND", "REPLACEMENT");
    assertThat(handler.handle(toLogin), is(true));
    assertThat(handler.handle(toLogin), is(false));
    assertThat(handler.handle(saleAffected(recall, byRecord, "RECALL", "REFUND")), is(true));
    assertThat(handler.handle(saleAffected(recall, byPhone, "RECALL", "REFUND")), is(true));
    assertThat(handler.handle(saleAffected(recall, anonymous, "RECALL", "REFUND")), is(true));
    // The same order named again under another event: the notice already exists.
    assertThat(handler.handle(saleAffected(recall, byLogin, "RECALL", "REFUND")), is(false));

    JsonArray listed = staffList(recall, null);
    assertThat(listed.size(), is(4));
    JsonObject mine = find(listed, "orderId", byLogin);
    assertThat(mine.getString("status"), is("ISSUED"));
    assertThat(mine.getString("loginId"), is(login.toString()));
    assertThat(mine.getBoolean("buyerIdentified"), is(true));
    assertThat(mine.getJsonArray("remedies").toString(), is("[\"REFUND\",\"REPLACEMENT\"]"));
    assertThat(mine.getString("contactPhone"), is("0800 100 200"));
    assertThat(mine.getString("customerNotice"), containsString("Do not eat"));
    JsonObject line = mine.getJsonArray("lines").getJsonObject(0);
    assertThat(line.getString("variantId"), is(V));
    assertThat(line.getString("batchNo"), is("L1"));
    assertThat(line.getString("match"), is("IN_SCOPE"));
    assertThat(line.containsKey("productName"), is(false));
    assertThat(find(listed, "orderId", byRecord).getString("customerId"), is(customer.toString()));
    assertThat(find(listed, "orderId", byPhone).getString("status"), is("ISSUED"));
    assertThat(find(listed, "orderId", byPhone).containsKey("buyerPhone"), is(false));
    JsonObject nobody = find(listed, "orderId", anonymous);
    assertThat(nobody.getString("status"), is("UNIDENTIFIED"));
    assertThat(nobody.getBoolean("buyerIdentified"), is(false));

    // Three announcements to notification-svc, none for the anonymous sale, none twice.
    assertThat(outboxCount("RecallNoticeIssued", mine.getString("id")), is("1"));
    assertThat(outboxCount("RecallNoticeIssued", nobody.getString("id")), is("0"));
    String payload = outboxPayload("RecallNoticeIssued", mine.getString("id"));
    assertThat(payload, containsString("\"loginId\":\"" + login + "\""));
    assertThat(payload, containsString("\"customerId\":null"));
    assertThat(payload, containsString("\"remedies\":[\"REFUND\",\"REPLACEMENT\"]"));
    assertThat(
        outboxPayload("RecallNoticeIssued", find(listed, "orderId", byPhone).getString("id")),
        containsString("\"buyerPhone\":\"+447700900123\""));

    JsonObject progress =
        ok(staff("GET", "/orders/recall-notices/progress?recallId=" + recall, null));
    assertThat(progress.getInt("notices"), is(4));
    assertThat(progress.getInt("identified"), is(3));
    assertThat(progress.getInt("unidentified"), is(1));
    assertThat(progress.getInt("remedyChosen"), is(0));
    assertThat(listed.size(), is(staffList(recall, "ISSUED").size() + 1));

    // The walk-in comes back with the receipt and wants nothing: settled, and still never told.
    JsonObject declined =
        ok(
            staff(
                "POST",
                "/orders/recall-notices/" + nobody.getString("id") + "/resolve",
                "{\"resolution\":\"DECLINED\"}"));
    assertThat(declined.getString("status"), is("RESOLVED"));
    assertThat(declined.getBoolean("buyerIdentified"), is(false));
    JsonObject after = ok(staff("GET", "/orders/recall-notices/progress?recallId=" + recall, null));
    assertThat(after.getInt("identified"), is(3));
    assertThat(after.getInt("unidentified"), is(1));
    assertThat(after.getInt("resolved"), is(1));
  }

  @Test
  void aWalkInsNumberTypedTheUsualWayIsWhatTheirRecallTextGoesTo() {
    // A phone at the till: the cashier typed each number the way people say it here, which an SMS
    // gateway does not take. The first order keeps it in international form from the moment it is
    // placed; the second stands for an order from before that was kept, read when the recall comes.
    String walkIn = placeAtTillWithPhone("07400 123456");
    String older = placeAtTillWithPhone("07400 654321");
    com.storeql.test.Envelopes.exec(
        PG, "UPDATE \"order\".orders SET contact_phone_e164 = NULL WHERE id = '" + older + "'");
    UUID recall = Ids.newId();
    assertThat(handler.handle(saleAffected(recall, walkIn, "RECALL", "REFUND")), is(true));
    assertThat(handler.handle(saleAffected(recall, older, "RECALL", "REFUND")), is(true));
    JsonArray listed = staffList(recall, null);
    assertThat(
        outboxPayload("RecallNoticeIssued", find(listed, "orderId", walkIn).getString("id")),
        containsString("\"buyerPhone\":\"+447400123456\""));
    assertThat(
        outboxPayload("RecallNoticeIssued", find(listed, "orderId", older).getString("id")),
        containsString("\"buyerPhone\":\"+447400654321\""));
  }

  @Test
  void aWithdrawalAMalformedEventAndAnOrderThisServiceNeverSawTellNobody() {
    UUID recall = Ids.newId();
    String order = placeAtTill(Ids.newId(), null);
    assertThat(handler.handle(saleAffected(recall, order, "WITHDRAWAL", "REFUND")), is(false));
    assertThat(
        handler.handle(saleAffected(recall, Ids.newId().toString(), "RECALL", "REFUND")),
        is(false));
    assertThat(handler.handle("{\"eventId\":\"nope\"}"), is(false));
    assertThat(handler.handle("not json"), is(false));
    assertThat(
        handler.handle(
            saleAffected(recall, order, "RECALL").replace("\"remedies\":[]", "\"remedies\":[]")),
        is(false));
    assertThat(staffList(recall, null).size(), is(0));
  }

  // ── the shopper ─────────────────────────────────────────────────────────────

  @Test
  void aShopperSeesOnlyTheirOwnNoticesAndChoosesFromWhatWasOfferedOnce() {
    UUID login = Ids.newId();
    UUID other = Ids.newId();
    UUID recall = Ids.newId();
    String mine = placeAsShopper(login);
    String theirs = placeAsShopper(other);
    handler.handle(saleAffected(recall, mine, "RECALL", "REFUND", "REPLACEMENT"));
    handler.handle(saleAffected(recall, theirs, "RECALL", "REFUND"));

    JsonArray own = okArray(shopper("GET", "/orders/recall-notices/mine", null, login, T));
    assertThat(own.size(), is(1));
    JsonObject notice = own.getJsonObject(0);
    assertThat(notice.getString("orderId"), is(mine));
    assertThat(
        okArray(shopper("GET", "/orders/recall-notices/mine", null, other, T)).size(), is(1));
    assertThat(shopper("GET", "/orders/recall-notices/mine", null, null, T).getStatus(), is(401));
    assertThat(
        okArray(shopper("GET", "/orders/recall-notices/mine", null, login, RIVAL)).size(), is(0));

    String id = notice.getString("id");
    Response wrong =
        shopper(
            "POST",
            "/orders/recall-notices/" + id + "/remedy",
            "{\"remedy\":\"REPAIR\"}",
            login,
            T);
    assertThat(wrong.getStatus(), is(409));
    assertThat(wrong.readEntity(String.class), containsString("RECALL_REMEDY_NOT_OFFERED"));
    assertThat(
        shopper(
                "POST",
                "/orders/recall-notices/" + id + "/remedy",
                "{\"remedy\":\"CASH\"}",
                login,
                T)
            .getStatus(),
        is(400));
    assertThat(
        shopper(
                "POST",
                "/orders/recall-notices/" + id + "/remedy",
                "{\"remedy\":\"REFUND\"}",
                other,
                T)
            .getStatus(),
        is(404));
    assertThat(
        shopper(
                "POST",
                "/orders/recall-notices/" + id + "/remedy",
                "{\"remedy\":\"REFUND\"}",
                login,
                RIVAL)
            .getStatus(),
        is(404));
    JsonObject chosen =
        ok(
            shopper(
                "POST",
                "/orders/recall-notices/" + id + "/remedy",
                "{\"remedy\":\"REFUND\"}",
                login,
                T));
    assertThat(chosen.getString("status"), is("REMEDY_CHOSEN"));
    assertThat(chosen.getString("remedy"), is("REFUND"));
    assertThat(chosen.getString("remedyChosenVia"), is("SHOPPER"));
    Response again =
        shopper(
            "POST",
            "/orders/recall-notices/" + id + "/remedy",
            "{\"remedy\":\"REPLACEMENT\"}",
            login,
            T);
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("RECALL_REMEDY_ALREADY_CHOSEN"));
    // A shopper reads no recall's list and settles nothing.
    assertThat(
        shopper("GET", "/orders/recall-notices?recallId=" + recall, null, login, T).getStatus(),
        is(403));
    assertThat(
        shopper(
                "POST",
                "/orders/recall-notices/" + id + "/resolve",
                "{\"resolution\":\"DECLINED\"}",
                login,
                T)
            .getStatus(),
        is(403));
    assertThat(
        ok(staff("GET", "/orders/recall-notices/progress?recallId=" + recall, null))
            .getJsonObject("chosen")
            .getInt("REFUND"),
        is(1));
  }

  @Test
  void twentyChoicesAtOnceRecordOne() throws Exception {
    UUID login = Ids.newId();
    UUID recall = Ids.newId();
    String order = placeAsShopper(login);
    handler.handle(saleAffected(recall, order, "RECALL", "REFUND", "REPLACEMENT", "REPAIR"));
    String id =
        okArray(shopper("GET", "/orders/recall-notices/mine", null, login, T))
            .getJsonObject(0)
            .getString("id");
    String[] remedies = {"REFUND", "REPLACEMENT", "REPAIR"};
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        String remedy = remedies[i % 3];
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return shopper(
                          "POST",
                          "/orders/recall-notices/" + id + "/remedy",
                          "{\"remedy\":\"" + remedy + "\"}",
                          login,
                          T)
                      .getStatus();
                }));
      }
      go.countDown();
      int ok = 0;
      int refused = 0;
      for (Future<Integer> f : results) {
        int status = f.get();
        if (status == 200) ok++;
        else if (status == 409) refused++;
      }
      assertThat(ok, is(1));
      assertThat(refused, is(19));
    } finally {
      pool.shutdownNow();
    }
  }

  // ── staff settle it ─────────────────────────────────────────────────────────

  @Test
  void aRefundThroughAReturnSettlesTheNoticeInTheReturnsTransactionAndStaffSettleTheRest() {
    UUID customer = Ids.newId();
    UUID recall = Ids.newId();
    String order = placeAtTill(customer, "10.00");
    String otherOrder = placeAtTill(customer, "10.00");
    handler.handle(saleAffected(recall, order, "RECALL", "REFUND", "REPLACEMENT"));
    handler.handle(saleAffected(recall, otherOrder, "RECALL", "REFUND", "REPLACEMENT"));
    JsonArray listed = staffList(recall, null);
    String notice = find(listed, "orderId", order).getString("id");
    String otherNotice = find(listed, "orderId", otherOrder).getString("id");

    // Staff choose for a buyer at the counter; a refund is not settled here but through a return.
    assertThat(
        ok(staff("POST", "/orders/recall-notices/" + notice + "/remedy", "{\"remedy\":\"REFUND\"}"))
            .getString("remedyChosenVia"),
        is("STAFF"));
    Response refundHere =
        staff(
            "POST",
            "/orders/recall-notices/" + notice + "/resolve",
            "{\"resolution\":\"REFUNDED\"}");
    assertThat(refundHere.getStatus(), is(400));
    assertThat(refundHere.readEntity(String.class), containsString("RECALL_REFUND_THROUGH_RETURN"));

    // A return naming a notice about another order records neither the return nor a settlement.
    Response mismatch = staff("POST", "/orders/" + order + "/returns", returnJson(otherNotice));
    assertThat(mismatch.getStatus(), is(409));
    assertThat(mismatch.readEntity(String.class), containsString("RECALL_NOTICE_ORDER_MISMATCH"));
    assertThat(okArray(staff("GET", "/orders/" + order + "/returns", null)).size(), is(0));
    assertThat(
        find(staffList(recall, null), "orderId", otherOrder).getString("status"), is("ISSUED"));

    JsonObject returned =
        created(staff("POST", "/orders/" + order + "/returns", returnJson(notice)));
    JsonObject settled = find(staffList(recall, null), "orderId", order);
    assertThat(settled.getString("status"), is("RESOLVED"));
    assertThat(settled.getString("resolution"), is("REFUNDED"));
    assertThat(settled.getString("returnId"), is(returned.getString("id")));
    Response twice = staff("POST", "/orders/" + order + "/returns", returnJson(notice));
    assertThat(twice.getStatus(), is(409));
    assertThat(twice.readEntity(String.class), containsString("RECALL_NOTICE_RESOLVED"));
    assertThat(
        staff("POST", "/orders/recall-notices/" + notice + "/remedy", "{\"remedy\":\"REPAIR\"}")
            .getStatus(),
        is(409));

    // The other buyer took a replacement: settled by staff, with the remedy that implies.
    JsonObject replaced =
        ok(
            staff(
                "POST",
                "/orders/recall-notices/" + otherNotice + "/resolve",
                "{\"resolution\":\"REPLACED\",\"notes\":\"New jar handed over\"}"));
    assertThat(replaced.getString("status"), is("RESOLVED"));
    assertThat(replaced.getString("remedy"), is("REPLACEMENT"));
    assertThat(replaced.getString("resolutionNotes"), is("New jar handed over"));
    assertThat(
        staff(
                "POST",
                "/orders/recall-notices/" + otherNotice + "/resolve",
                "{\"resolution\":\"DECLINED\"}")
            .getStatus(),
        is(409));
    assertThat(
        staff(
                "POST",
                "/orders/recall-notices/" + Ids.newId() + "/resolve",
                "{\"resolution\":\"DECLINED\"}")
            .getStatus(),
        is(404));
    assertThat(
        staff(
                "POST",
                "/orders/recall-notices/" + otherNotice + "/resolve",
                "{\"resolution\":\"LOST\"}")
            .getStatus(),
        is(400));
    JsonObject progress =
        ok(staff("GET", "/orders/recall-notices/progress?recallId=" + recall, null));
    assertThat(progress.getInt("resolved"), is(2));
    assertThat(progress.getInt("remedyChosen"), is(2));
    // Another business sees none of it.
    assertThat(
        okArray(
                request(
                    "GET",
                    "/orders/recall-notices?recallId=" + recall,
                    null,
                    RIVAL,
                    "OWNER",
                    OWNER))
            .size(),
        is(0));
    assertThat(
        request(
                "POST",
                "/orders/recall-notices/" + notice + "/resolve",
                "{\"resolution\":\"DECLINED\"}",
                RIVAL,
                "OWNER",
                OWNER)
            .getStatus(),
        is(404));
  }

  // ── SJ-D59 ──────────────────────────────────────────────────────────────────

  @Test
  void aTillSaleIsNeverFiledAsTheCashiersOwnPurchase() {
    // Found driving 05.10 live: every staff login also carries CUSTOMER, so the checkout link
    // filed an anonymous till sale under the cashier's own customer record — loyalty accrued to
    // the cashier, and the recall notice would have gone to them.
    UUID cashier = Ids.newId();
    JsonObject till = place("POS", "INSTORE", "\"currency\":\"GBP\",", "CUSTOMER,CASHIER", cashier);
    assertThat(till.containsKey("customerId") && !till.isNull("customerId"), is(false));
    assertThat(loginOf(till), is((String) null));
    // The same person shopping online is still themselves.
    JsonObject online = place("ONLINE", "PICKUP", "", "CUSTOMER,CASHIER", cashier);
    assertThat(loginOf(online), is(cashier.toString()));
  }

  private static String loginOf(JsonObject order) {
    return scalar(
        PG, "SELECT login_id FROM \"order\".orders WHERE id = '" + order.getString("id") + "'");
  }

  // ── orders ──────────────────────────────────────────────────────────────────

  /** Places an order of the variant, two at 5.00, as the given caller. */
  private JsonObject place(
      String channel, String fulfilment, String extra, String roles, UUID userId) {
    return created(
        request(
            "POST",
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\""
                + channel
                + "\",\"fulfilmentType\":\""
                + fulfilment
                + "\","
                + extra
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":5.00}]}",
            T,
            roles,
            userId == null ? null : userId.toString()));
  }

  private String placeAsShopper(UUID login) {
    return place("ONLINE", "PICKUP", "", "CUSTOMER", login).getString("id");
  }

  /** A walk-in's till sale, the number given as the cashier typed it. */
  private String placeAtTillWithPhone(String phone) {
    return place(
            "POS",
            "INSTORE",
            "\"contactPhone\":\"" + phone + "\",\"currency\":\"GBP\",",
            "OWNER",
            Ids.parse(OWNER))
        .getString("id");
  }

  private String placeAsGuest(String phone) {
    return place("ONLINE", "PICKUP", "\"contactPhone\":\"" + phone + "\",", null, null)
        .getString("id");
  }

  /** A till sale, paid for — and so handed over — when a price is given. */
  private String placeAtTill(UUID customer, String pay) {
    String id =
        place(
                "POS",
                "INSTORE",
                (customer == null ? "" : "\"customerId\":\"" + customer + "\",")
                    + "\"currency\":\"GBP\",",
                "OWNER",
                Ids.parse(OWNER))
            .getString("id");
    if (pay != null) {
      orderService.handlePaymentCaptured(
          Ids.parse(T), Ids.parse(id), Ids.newId(), new BigDecimal(pay));
    }
    return id;
  }

  private static String returnJson(String noticeId) {
    return "{\"reason\":\"Recalled\",\"recallNoticeId\":\""
        + noticeId
        + "\","
        + "\"items\":[{\"variantId\":\""
        + V
        + "\",\"qty\":1}]}";
  }

  /** The event inventory-svc publishes for one order a recall reached. */
  private static String saleAffected(UUID recall, String orderId, String kind, String... remedies) {
    var remedyArray = Json.createArrayBuilder();
    for (String r : remedies) remedyArray.add(r);
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "RecallSaleAffected")
            .add("tenantId", T)
            .add("aggregateId", orderId)
            .add("recallId", recall.toString())
            .add("reference", "FSA-PRIN-42")
            .add("kind", kind)
            .add("hazard", "ALLERGEN")
            .add("reason", "Undeclared peanut")
            .add("customerNotice", "Do not eat.\nBring it back to any store.")
            .add("remedies", remedyArray)
            .add("orderId", orderId)
            .add("storeId", S)
            .add("soldAt", "2026-09-12T10:15:00Z")
            .add(
                "lines",
                Json.createArrayBuilder()
                    .add(
                        Json.createObjectBuilder()
                            .add("variantId", V)
                            .add("batchId", Ids.newId().toString())
                            .add("batchNo", "L1")
                            .add("expiryDate", "2026-10-01")
                            .add("qty", new BigDecimal("2.000"))
                            .add("match", "IN_SCOPE")));
    b.addNull("singleRemedyReason");
    b.add("contactPhone", "0800 100 200");
    b.addNull("contactUrl");
    return b.build().toString();
  }

  // ── requests ────────────────────────────────────────────────────────────────

  private Response staff(String method, String path, String json) {
    return request(method, path, json, T, "OWNER", OWNER);
  }

  private Response shopper(String method, String path, String json, UUID login, String tenant) {
    return request(method, path, json, tenant, "CUSTOMER", login == null ? null : login.toString());
  }

  private Response request(
      String method, String path, String json, String tenant, String roles, String userId) {
    String[] parts = path.split("\\?", 2);
    WebTarget t = target.path(parts[0]);
    if (parts.length == 2) {
      for (String pair : parts[1].split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv[1]);
      }
    }
    Invocation.Builder b = t.request().header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (userId != null) b = b.header("X-User-Id", userId);
    if ("POST".equals(method) && parts[0].equals("/orders")) {
      b = b.header("Idempotency-Key", Ids.newId().toString());
    }
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private JsonArray staffList(UUID recall, String status) {
    return okArray(
        staff(
            "GET",
            "/orders/recall-notices?recallId="
                + recall
                + (status == null ? "" : "&status=" + status),
            null));
  }

  private static String outboxCount(String eventType, String aggregateId) {
    return scalar(
        PG,
        "SELECT COUNT(*) FROM \"order\".outbox WHERE event_type = '"
            + eventType
            + "' AND aggregate_id = '"
            + aggregateId
            + "'");
  }

  private static String outboxPayload(String eventType, String aggregateId) {
    return scalar(
        PG,
        "SELECT payload FROM \"order\".outbox WHERE event_type = '"
            + eventType
            + "' AND aggregate_id = '"
            + aggregateId
            + "'");
  }
}
