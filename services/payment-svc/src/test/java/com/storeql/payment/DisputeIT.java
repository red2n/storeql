package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.provider.PaymentProvider.DisputeNotice;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.DisputeService;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Chargebacks over HTTP and Postgres (11.9): a chargeback recorded from the acquirer's notice,
 * answered once and in time, won, lost or accepted, each step in its history and announced for the
 * ledger; a provider's dispute applied from its webhook, idempotently; and what must not work — a
 * cash payment charged back, two disputes open on one payment, an answer given twice or late, a
 * provider's dispute decided by hand, another business's dispute read, a cashier anywhere near it.
 */
@HelidonTest
class DisputeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  @Inject WebTarget target;
  @Inject PaymentRepository payments;
  @Inject DisputeService disputes;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Caller owner(UUID tenantId) {
    return Caller.owner(tenantId);
  }

  private Answer call(String method, String path, Caller who, String json, String idempotencyKey) {
    return ItCalls.call(target, method, path, who, json, idempotencyKey);
  }

  private Answer post(String path, Caller who, String json) {
    return ItCalls.post(target, path, who, json);
  }

  private Answer get(String path, Caller who) {
    return ItCalls.get(target, path, who);
  }

  private UUID tender(UUID tenantId, UUID orderId, UUID storeId, String method, String amount) {
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenantId,
            orderId,
            new BigDecimal(amount),
            method,
            "auth-1234",
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            storeId),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenantId, id, "{}"));
    return id;
  }

  private static String chargeback(UUID paymentId, String amount, String dueBy) {
    return "{\"paymentId\":\""
        + paymentId
        + "\""
        + (amount == null ? "" : ",\"amount\":" + amount)
        + ",\"feeAmount\":15.00,\"currency\":\"GBP\",\"reason\":\"product_not_received\","
        + "\"networkReasonCode\":\"13.1\",\"caseReference\":\"CB-2026-0042\",\"evidenceDueBy\":\""
        + dueBy
        + "\"}";
  }

  private static String inTenDays() {
    return Instant.now().plusSeconds(864_000).toString();
  }

  private int outboxCount(UUID disputeId, String eventType) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM payment.outbox WHERE aggregate_id = ? AND event_type = ?")) {
      ps.setObject(1, disputeId);
      ps.setString(2, eventType);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  // ── recorded from the acquirer's notice ────────────────────────────────────

  @Test
  @DisplayName("A chargeback is recorded, answered once, and won: each step kept and announced")
  void aChargebackRecordedAnsweredAndWon() throws Exception {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller me = owner(tenant);
    UUID payment = tender(tenant, Ids.newId(), store, "CARD", "45.99");

    Answer recorded = post("/admin/disputes", me, chargeback(payment, null, inTenDays()));
    assertThat(recorded.body().toString(), recorded.status(), is(201));
    JsonObject d = recorded.data();
    UUID id = Ids.parse(d.getString("id"));
    assertThat(d.getString("status"), is("NEEDS_RESPONSE"));
    assertThat(d.getString("provider"), is("MANUAL"));
    assertThat(d.getString("reference"), is("CB-2026-0042"));
    assertThat(d.getString("reason"), is("PRODUCT_NOT_RECEIVED"));
    assertThat(
        "the whole tender when no amount is given",
        d.getJsonNumber("amount").bigDecimalValue().compareTo(new BigDecimal("45.99")),
        is(0));
    assertThat(
        "the acquirer usually has the money already", d.getBoolean("fundsWithdrawn"), is(true));
    assertThat(d.getBoolean("overdue"), is(false));
    assertThat(d.getString("storeId"), is(store.toString()));
    assertThat(outboxCount(id, "PaymentDisputeOpened"), is(1));

    Answer empty = post("/admin/disputes/" + id + "/evidence", me, "{}");
    assertThat(empty.code(), is("DISPUTE_EVIDENCE_EMPTY"));
    Answer answered =
        post(
            "/admin/disputes/" + id + "/evidence",
            me,
            "{\"productDescription\":\"Two crates of oranges\",\"customerName\":\"A. Shopper\","
                + "\"receiptReference\":\"R-1042\",\"fulfilmentProof\":\"Collected in store 12 Sep, signed\"}");
    assertThat(answered.body().toString(), answered.status(), is(200));
    assertThat(answered.data().getJsonObject("dispute").getString("status"), is("UNDER_REVIEW"));
    assertThat(
        answered.data().getJsonObject("evidence").getString("receiptReference"), is("R-1042"));
    assertThat(
        "a scheme takes evidence once",
        post("/admin/disputes/" + id + "/evidence", me, "{\"notes\":\"and another thing\"}").code(),
        is("DISPUTE_NOT_AWAITING_RESPONSE"));

    assertThat(
        post("/admin/disputes/" + id + "/resolve", me, "{\"outcome\":\"MAYBE\"}").code(),
        is("DISPUTE_OUTCOME_UNKNOWN"));
    Answer won =
        post(
            "/admin/disputes/" + id + "/resolve",
            me,
            "{\"outcome\":\"won\",\"note\":\"Acquirer letter 3 Oct\"}");
    assertThat(won.body().toString(), won.status(), is(200));
    assertThat(won.data().getJsonObject("dispute").getString("status"), is("WON"));
    assertThat(won.data().getJsonObject("dispute").isNull("closedAt"), is(false));
    JsonArray history = won.data().getJsonArray("history");
    assertThat(
        history.getValuesAs(JsonObject.class).stream()
            .map(h -> h.getString("kind"))
            .toList()
            .toString(),
        is("[OPENED, FUNDS_WITHDRAWN, EVIDENCE_SUBMITTED, WON, FUNDS_REINSTATED]"));
    assertThat(outboxCount(id, "PaymentDisputeClosed"), is(1));
    assertThat(
        post("/admin/disputes/" + id + "/resolve", me, "{\"outcome\":\"LOST\"}").code(),
        is("DISPUTE_CLOSED"));
    assertThat(post("/admin/disputes/" + id + "/accept", me, null).code(), is("DISPUTE_CLOSED"));
    assertThat("closed once, announced once", outboxCount(id, "PaymentDisputeClosed"), is(1));
  }

  @Test
  @DisplayName("What cannot be charged back, recorded twice, or recorded wrongly is refused")
  void whatIsNotAChargebackIsRefused() {
    UUID tenant = Ids.newId();
    Caller me = owner(tenant);
    UUID order = Ids.newId();
    UUID card = tender(tenant, order, null, "CARD", "20.00");
    UUID cash = tender(tenant, order, null, "CASH", "5.00");

    assertThat(
        post("/admin/disputes", me, chargeback(cash, null, inTenDays())).code(),
        is("DISPUTE_NOT_DISPUTABLE"));
    assertThat(
        post("/admin/disputes", me, chargeback(Ids.newId(), null, inTenDays())).status(), is(404));
    assertThat(
        post("/admin/disputes", me, chargeback(card, "20.01", inTenDays())).code(),
        is("DISPUTE_AMOUNT_EXCEEDS_PAYMENT"));
    assertThat(
        post(
                "/admin/disputes",
                me,
                chargeback(card, null, Instant.now().minusSeconds(60).toString()))
            .code(),
        is("DISPUTE_DUE_DATE_PAST"));
    assertThat(
        post(
                "/admin/disputes",
                me,
                chargeback(card, null, inTenDays())
                    .replace("product_not_received", "buyers_remorse"))
            .code(),
        is("DISPUTE_REASON_UNKNOWN"));
    assertThat(post("/admin/disputes", me, "{\"paymentId\":\"" + card + "\"}").status(), is(400));
    assertThat(post("/admin/disputes", me, chargeback(card, "0", inTenDays())).status(), is(400));
    assertThat(post("/admin/disputes", me, "{not json").status(), is(400));

    // Another business's payment is not there to be disputed.
    assertThat(
        post("/admin/disputes", owner(Ids.newId()), chargeback(card, null, inTenDays())).status(),
        is(404));
    // Nor is this anybody's but management's.
    for (String roles : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(
          post(
                  "/admin/disputes",
                  new Caller(tenant, Ids.newId(), roles),
                  chargeback(card, null, inTenDays()))
              .status(),
          is(403));
      assertThat(get("/admin/disputes", new Caller(tenant, Ids.newId(), roles)).status(), is(403));
    }

    // The same request again is the same chargeback; another request is refused while it is open.
    String key = Ids.newId().toString();
    Answer first = call("POST", "/admin/disputes", me, chargeback(card, "12.50", inTenDays()), key);
    assertThat(first.body().toString(), first.status(), is(201));
    Answer replay =
        call("POST", "/admin/disputes", me, chargeback(card, "12.50", inTenDays()), key);
    assertThat(replay.data().getString("id"), is(first.data().getString("id")));
    Answer second = post("/admin/disputes", me, chargeback(card, "12.50", inTenDays()));
    assertThat(second.status(), is(409));
    assertThat(second.code(), is("DISPUTE_ALREADY_OPEN"));

    // Accepted: lost by the business's own decision — and the payment can be disputed again later,
    // as a scheme's second presentment would be.
    String id = first.data().getString("id");
    Answer accepted = post("/admin/disputes/" + id + "/accept", me, null);
    assertThat(accepted.data().getJsonObject("dispute").getString("status"), is("ACCEPTED"));
    assertThat(
        post("/admin/disputes/" + id + "/evidence", me, "{\"notes\":\"too late\"}").code(),
        is("DISPUTE_NOT_AWAITING_RESPONSE"));
    assertThat(
        post("/admin/disputes", me, chargeback(card, "12.50", inTenDays())).status(), is(201));
  }

  @Test
  @DisplayName("An answer after its date is refused, and the register says the dispute is overdue")
  void anAnswerAfterItsDateIsRefused() throws Exception {
    UUID tenant = Ids.newId();
    Caller me = owner(tenant);
    UUID payment = tender(tenant, Ids.newId(), null, "CARD", "30.00");
    Answer recorded =
        post(
            "/admin/disputes",
            me,
            chargeback(payment, null, Instant.now().plusMillis(1200).toString()));
    assertThat(recorded.body().toString(), recorded.status(), is(201));
    String id = recorded.data().getString("id");

    Thread.sleep(1500);
    Answer late =
        post("/admin/disputes/" + id + "/evidence", me, "{\"notes\":\"Here is the receipt\"}");
    assertThat(late.status(), is(409));
    assertThat(late.code(), is("DISPUTE_EVIDENCE_LATE"));
    assertThat(
        get("/admin/disputes/" + id, me).data().getJsonObject("dispute").getBoolean("overdue"),
        is(true));
    // What is left is to say how it ended.
    assertThat(
        post("/admin/disputes/" + id + "/resolve", me, "{\"outcome\":\"LOST\"}")
            .data()
            .getJsonObject("dispute")
            .getString("status"),
        is("LOST"));
  }

  @Test
  @DisplayName(
      "The register: by status, a page at a time, one business's own, with the ratio the schemes watch")
  void theRegister() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller me = owner(tenant);
    UUID order = Ids.newId();
    String[] ids = new String[3];
    for (int i = 0; i < 3; i++) {
      Answer a =
          post(
              "/admin/disputes",
              me,
              chargeback(tender(tenant, order, store, "CARD", "10.00"), null, inTenDays()));
      assertThat(a.body().toString(), a.status(), is(201));
      ids[i] = a.data().getString("id");
    }
    tender(tenant, order, store, "CARD", "10.00");
    tender(tenant, order, store, "CASH", "10.00");
    post("/admin/disputes/" + ids[0] + "/resolve", me, "{\"outcome\":\"LOST\"}");

    Answer all = get("/admin/disputes?limit=2", me);
    JsonArray firstPage = all.body().getJsonArray("data");
    assertThat(firstPage.size(), is(2));
    assertThat("newest first", firstPage.getJsonObject(0).getString("id"), is(ids[2]));
    String next = all.body().getJsonObject("meta").getString("nextCursor");
    JsonArray secondPage =
        get("/admin/disputes?limit=2&after=" + next, me).body().getJsonArray("data");
    assertThat(secondPage.size(), is(1));
    assertThat(secondPage.getJsonObject(0).getString("id"), is(ids[0]));

    assertThat(get("/admin/disputes?status=lost", me).body().getJsonArray("data").size(), is(1));
    assertThat(
        get("/admin/disputes?status=NEEDS_RESPONSE&storeId=" + store, me)
            .body()
            .getJsonArray("data")
            .size(),
        is(2));
    assertThat(
        get("/admin/disputes?storeId=" + Ids.newId(), me).body().getJsonArray("data").size(),
        is(0));
    assertThat(get("/admin/disputes?status=PENDING", me).code(), is("DISPUTE_STATUS_UNKNOWN"));
    assertThat(get("/admin/disputes?after=not-a-cursor", me).status(), is(400));

    // Another business sees none of it, by list or by id.
    Caller stranger = owner(Ids.newId());
    assertThat(get("/admin/disputes", stranger).body().getJsonArray("data").size(), is(0));
    assertThat(get("/admin/disputes/" + ids[1], stranger).status(), is(404));
    assertThat(post("/admin/disputes/" + ids[1] + "/accept", stranger, null).status(), is(404));

    String from = Instant.now().minusSeconds(3600).toString();
    String to = Instant.now().plusSeconds(3600).toString();
    JsonObject s = get("/admin/disputes/summary?from=" + from + "&to=" + to, me).data();
    assertThat(s.getInt("opened"), is(3));
    assertThat(s.getInt("lost"), is(1));
    assertThat(s.getInt("needsResponse"), is(2));
    assertThat("cash is not a card payment", s.getInt("cardPayments"), is(4));
    assertThat(
        s.getJsonNumber("disputeRatio").bigDecimalValue().compareTo(new BigDecimal("0.75")), is(0));
    assertThat(s.getBoolean("aboveMonitoringThreshold"), is(true));
    assertThat(
        s.getJsonNumber("amountLost").bigDecimalValue().compareTo(new BigDecimal("10")), is(0));
    assertThat(
        s.getJsonNumber("feesCharged").bigDecimalValue().compareTo(new BigDecimal("45")), is(0));
    assertThat(
        get("/admin/disputes/summary?from=" + to + "&to=" + from, me).code(),
        is("DISPUTE_PERIOD_INVALID"));
    assertThat(get("/admin/disputes/summary?from=yesterday&to=" + to, me).status(), is(400));
  }

  // ── told by the provider ───────────────────────────────────────────────────

  @Test
  @DisplayName("A provider's dispute is applied from its webhook, once, and never decided by hand")
  void aProvidersDispute() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    Caller me = owner(tenant);
    // A provider's capture is written as CARD (PaymentIntentService.writeCapture), never under the
    // provider's name: chk_payment_tenders_method holds the set.
    UUID payment = tender(tenant, order, null, "CARD", "80.00");
    PaymentIntent intent =
        new PaymentIntent(
            Ids.newId(),
            tenant,
            order,
            null,
            "STRIPE",
            "pi_it_1",
            new BigDecimal("80.00"),
            new BigDecimal("80.00"),
            "EUR",
            PaymentIntent.STATUS_CAPTURED,
            null,
            null,
            null,
            payment,
            null,
            Instant.now(),
            Instant.now());
    Instant due = Instant.now().plusSeconds(600_000);
    DisputeNotice opened =
        new DisputeNotice(
            "dp_it_1",
            DisputeNotice.PHASE_OPENED,
            null,
            new BigDecimal("80.00"),
            BigDecimal.ZERO,
            "EUR",
            "FRAUDULENT",
            "10.4",
            due);

    disputes.fromProvider("STRIPE", intent, opened);
    disputes.fromProvider("STRIPE", intent, opened); // redelivered
    JsonArray mine = get("/admin/disputes", me).body().getJsonArray("data");
    assertThat("one dispute however often it is told", mine.size(), is(1));
    JsonObject d = mine.getJsonObject(0);
    UUID id = Ids.parse(d.getString("id"));
    assertThat(d.getString("provider"), is("STRIPE"));
    assertThat(d.getString("reference"), is("dp_it_1"));
    assertThat(d.getString("currency"), is("EUR"));
    assertThat("opened before the money moved", d.getBoolean("fundsWithdrawn"), is(false));
    assertThat(d.getString("paymentId"), is(payment.toString()));

    DisputeNotice withdrawn =
        new DisputeNotice(
            "dp_it_1",
            DisputeNotice.PHASE_FUNDS_WITHDRAWN,
            null,
            new BigDecimal("80.00"),
            new BigDecimal("20.00"),
            "EUR",
            "FRAUDULENT",
            "10.4",
            due);
    disputes.fromProvider("STRIPE", intent, withdrawn);
    disputes.fromProvider("STRIPE", intent, withdrawn);
    JsonObject after = get("/admin/disputes/" + id, me).data().getJsonObject("dispute");
    assertThat(after.getBoolean("fundsWithdrawn"), is(true));
    assertThat(
        after.getJsonNumber("feeAmount").bigDecimalValue().compareTo(new BigDecimal("20")), is(0));
    assertThat("the money moves once", outboxCount(id, "PaymentDisputeFundsWithdrawn"), is(1));

    Answer byHand = post("/admin/disputes/" + id + "/resolve", me, "{\"outcome\":\"WON\"}");
    assertThat(byHand.status(), is(409));
    assertThat(byHand.code(), is("DISPUTE_DECIDED_BY_PROVIDER"));

    DisputeNotice lost =
        new DisputeNotice(
            "dp_it_1",
            DisputeNotice.PHASE_CLOSED,
            "LOST",
            new BigDecimal("80.00"),
            new BigDecimal("20.00"),
            "EUR",
            "FRAUDULENT",
            "10.4",
            due);
    disputes.fromProvider("STRIPE", intent, lost);
    disputes.fromProvider("STRIPE", intent, lost);
    Answer file = get("/admin/disputes/" + id, me);
    assertThat(file.data().getJsonObject("dispute").getString("status"), is("LOST"));
    assertThat(file.data().get("evidence"), is(nullValue()));
    assertThat(outboxCount(id, "PaymentDisputeClosed"), is(1));
    assertThat(file.body().toString(), not(containsString("idempotency")));

    // A dispute about a payment this service does not hold creates nothing.
    disputes.fromProvider(
        "STRIPE",
        null,
        new DisputeNotice(
            "dp_unknown",
            DisputeNotice.PHASE_OPENED,
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "EUR",
            "GENERAL",
            null,
            due));
    assertThat(get("/admin/disputes", me).body().getJsonArray("data").size(), is(1));
  }

  @Test
  @DisplayName(
      "A provider's dispute fee keeps the currency it was charged in: a yen charge disputed on an"
          + " account paid out in pounds costs a fee in pounds, said so on the register and the"
          + " event")
  void aDisputeFeeKeepsItsOwnCurrency() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    Caller me = owner(tenant);
    UUID payment = tender(tenant, order, null, "CARD", "5000");
    PaymentIntent intent =
        new PaymentIntent(
            Ids.newId(),
            tenant,
            order,
            null,
            "STRIPE",
            "pi_it_fx",
            new BigDecimal("5000"),
            new BigDecimal("5000"),
            "JPY",
            PaymentIntent.STATUS_CAPTURED,
            null,
            null,
            null,
            payment,
            null,
            Instant.now(),
            Instant.now());
    Instant due = Instant.now().plusSeconds(600_000);
    // Opened before any fee was charged: none yet, in the charge's own currency.
    disputes.fromProvider(
        "STRIPE",
        intent,
        new DisputeNotice(
            "dp_it_fx",
            DisputeNotice.PHASE_OPENED,
            null,
            new BigDecimal("5000"),
            BigDecimal.ZERO,
            "JPY",
            "FRAUDULENT",
            null,
            due,
            "JPY"));
    JsonObject opened = get("/admin/disputes", me).body().getJsonArray("data").getJsonObject(0);
    UUID id = Ids.parse(opened.getString("id"));
    assertThat(opened.getString("feeCurrency"), is("JPY"));

    // Stripe takes the money and its fee, in pounds.
    DisputeNotice withdrawn =
        new DisputeNotice(
            "dp_it_fx",
            DisputeNotice.PHASE_FUNDS_WITHDRAWN,
            null,
            new BigDecimal("5000"),
            new BigDecimal("15.00"),
            "JPY",
            "FRAUDULENT",
            null,
            due,
            "GBP");
    disputes.fromProvider("STRIPE", intent, withdrawn);
    disputes.fromProvider("STRIPE", intent, withdrawn);
    JsonObject after = get("/admin/disputes/" + id, me).data().getJsonObject("dispute");
    assertThat(after.getString("currency"), is("JPY"));
    assertThat(
        after.getJsonNumber("amount").bigDecimalValue().compareTo(new BigDecimal("5000")), is(0));
    assertThat(
        after.getJsonNumber("feeAmount").bigDecimalValue().compareTo(new BigDecimal("15")), is(0));
    assertThat(after.getString("feeCurrency"), is("GBP"));
    assertThat(outboxCount(id, "PaymentDisputeFundsWithdrawn"), is(1));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT payload FROM payment.outbox WHERE tenant_id = '"
                + tenant
                + "' AND event_type = 'PaymentDisputeFundsWithdrawn'"),
        containsString("\"feeAmount\":15.00"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT payload FROM payment.outbox WHERE tenant_id = '"
                + tenant
                + "' AND event_type = 'PaymentDisputeFundsWithdrawn'"),
        containsString("\"feeCurrency\":\"GBP\""));

    // A later notice whose fee could not be read keeps what was known, in its own currency.
    disputes.fromProvider(
        "STRIPE",
        intent,
        new DisputeNotice(
            "dp_it_fx",
            DisputeNotice.PHASE_UPDATED,
            null,
            new BigDecimal("5000"),
            null,
            "JPY",
            "FRAUDULENT",
            null,
            due,
            null));
    JsonObject kept = get("/admin/disputes/" + id, me).data().getJsonObject("dispute");
    assertThat(kept.getString("feeCurrency"), is("GBP"));
    // Another business finds none of it.
    assertThat(get("/admin/disputes/" + id, owner(Ids.newId())).status(), is(404));
  }
}
