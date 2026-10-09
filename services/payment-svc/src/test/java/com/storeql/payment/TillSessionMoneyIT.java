package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.repo.CashManagementRepository;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.PaymentService;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A drawer counts its own money. Two tills open at one store, each on the SESSION basis, each
 * report only the tenders and refunds that name it: the other till's takings do not land in this
 * one's expected cash, a cash refund is counted in the drawer that paid it out, and money that
 * names no session is shown apart as "not at a till" and counted in no drawer. A drawer opened the
 * old way (WINDOW) still counts the store's money in its window. A session is judged by tenant,
 * store and whether it is open, and a refund is never refused over where it is counted.
 */
@HelidonTest
class TillSessionMoneyIT {

  private static final PostgresSupport PG;

  /** A business, its two stores and a rival business, so no test depends on another's money. */
  private record Biz(UUID id, UUID store, UUID elsewhere, UUID rival, UUID rivalStore) {}

  private static final List<Biz> POOL = new ArrayList<>();
  private static final AtomicInteger NEXT = new AtomicInteger();

  static {
    PG = PostgresSupport.start().wire("payment");
    TenantSvcStub stub = TenantSvcStub.start();
    for (int i = 0; i < 40; i++) {
      Biz b = new Biz(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId());
      stub.with(b.id().toString(), "GBP", "GB")
          .withStore(b.id().toString(), b.store().toString(), "GB")
          .withStore(b.id().toString(), b.elsewhere().toString(), "GB")
          .with(b.rival().toString(), "GBP", "GB")
          .withStore(b.rival().toString(), b.rivalStore().toString(), "GB");
      POOL.add(b);
    }
  }

  private static final String TILLS = "/admin/cash/till-sessions";

  @Inject WebTarget target;
  @Inject PaymentService service;
  @Inject PaymentRepository payments;
  @Inject CashManagementRepository tills;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // Helidon's extension keeps one instance for the class, so each test takes its own business here.
  private Biz mine;
  private UUID biz;
  private UUID store;
  private UUID elsewhere;

  @BeforeEach
  void pickABusiness() {
    mine = POOL.get(NEXT.getAndIncrement());
    biz = mine.id();
    store = mine.store();
    elsewhere = mine.elsewhere();
  }

  private Caller cashier() {
    return new Caller(biz, Ids.newId(), "CASHIER", store);
  }

  private Caller manager() {
    return new Caller(biz, Ids.newId(), "MANAGER");
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private UUID open(Caller who, UUID at, String basis) {
    Answer a =
        ItCalls.post(
            target,
            TILLS,
            who,
            "{\"storeId\":\""
                + at
                + "\",\"floatAmount\":100"
                + (basis == null ? "" : ",\"basis\":\"" + basis + "\"")
                + "}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(a.data().getString("id"));
  }

  private Answer tender(
      Caller who, UUID order, String method, String amount, UUID session, UUID at) {
    return tenderKeyed(who, order, method, amount, session, at, Ids.newId().toString());
  }

  private Answer tenderKeyed(
      Caller who, UUID order, String method, String amount, UUID session, UUID at, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments",
        who,
        "{\"orderId\":\""
            + order
            + "\",\"amount\":"
            + amount
            + ",\"method\":\""
            + method
            + "\""
            + (at == null ? "" : ",\"storeId\":\"" + at + "\"")
            + (session == null ? "" : ",\"tillSessionId\":\"" + session + "\"")
            + (method.equals("CARD") ? ",\"reference\":\"AUTH 1\"" : "")
            + "}",
        key);
  }

  private Answer refund(Caller who, UUID order, String paymentId, String amount, UUID session) {
    return refundKeyed(who, order, paymentId, amount, session, Ids.newId().toString());
  }

  private Answer refundKeyed(
      Caller who, UUID order, String paymentId, String amount, UUID session, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/by-order/" + order + "/refunds",
        who,
        "{\"paymentId\":\""
            + paymentId
            + "\",\"amount\":"
            + amount
            + ",\"method\":\"CASH\""
            + (session == null ? "" : ",\"tillSessionId\":\"" + session + "\"")
            + "}",
        key);
  }

  private Answer close(UUID session, String counted) {
    return ItCalls.post(
        target, TILLS + "/" + session + "/close", manager(), "{\"countedCash\":" + counted + "}");
  }

  private static String scalar(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  private String countOf(String table, UUID order) {
    return scalar(
        "SELECT count(*) FROM payment."
            + table
            + " WHERE tenant_id = '"
            + biz
            + "' AND order_id = '"
            + order
            + "'");
  }

  private PaymentTender cashTender(UUID order, String amount, UUID at) {
    return new PaymentTender(
        Ids.newId(),
        biz,
        order,
        new BigDecimal(amount),
        "CASH",
        null,
        null,
        PaymentTender.STATUS_CAPTURED,
        null,
        java.time.Instant.now(),
        at);
  }

  private static OutboxRow captured(PaymentTender t) {
    return new OutboxRow(
        "PaymentCaptured", "storeql.payment.payment-captured", t.tenantId(), t.id(), "{}");
  }

  private JsonObject x(Caller who, UUID session) {
    Answer a = ItCalls.get(target, TILLS + "/" + session + "/x-report", who);
    assertThat(a.body().toString(), a.status(), is(200));
    return a.data();
  }

  private static void eq(String what, JsonObject o, String key, String expected) {
    assertThat(
        what + " " + key + " in " + o,
        o.getJsonNumber(key).bigDecimalValue().compareTo(new BigDecimal(expected)),
        is(0));
  }

  private static BigDecimal cashIn(JsonObject map, String section) {
    JsonObject cash = map.getJsonObject(section);
    return cash == null || !cash.containsKey("CASH")
        ? BigDecimal.ZERO
        : cash.getJsonObject("CASH").getJsonNumber("sales").bigDecimalValue();
  }

  // ── two tills at one store ─────────────────────────────────────────────────

  @Test
  @DisplayName("two tills open at one store each count only their own cash, refunds and drops")
  void twoTillsEachCountTheirOwn() {
    Caller one = cashier();
    Caller two = cashier();
    UUID t1 = open(one, store, "SESSION");
    UUID t2 = open(two, store, "SESSION");
    UUID sale1 = Ids.newId();
    UUID sale2 = Ids.newId();

    Answer a = tender(one, sale1, "CASH", "20.00", t1, store);
    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(tender(two, sale2, "CASH", "35.00", t2, store).status(), is(201));
    assertThat(tender(two, Ids.newId(), "CARD", "12.00", t2, store).status(), is(201));

    JsonObject r1 = x(manager(), t1);
    JsonObject r2 = x(manager(), t2);
    assertThat(r1.getString("basis"), is("SESSION"));
    eq("till 1", r1, "cashSales", "20.00");
    eq("till 1", r1, "expectedCashInTill", "120.00");
    eq("till 2", r2, "cashSales", "35.00");
    eq("till 2", r2, "expectedCashInTill", "135.00");
    // the card is in till 2's report, by method, and not in till 1's
    assertThat(r2.getJsonObject("tenderSummary").containsKey("CARD"), is(true));
    assertThat(r1.getJsonObject("tenderSummary").containsKey("CARD"), is(false));

    // till 2 pays 5.00 back to the customer of till 1's sale: it comes out of till 2's drawer
    Answer back = refund(manager(), sale1, a.data().getString("id"), "5.00", t2);
    assertThat(back.body().toString(), back.status(), is(201));
    eq("till 2 after a refund", x(manager(), t2), "expectedCashInTill", "130.00");
    eq("till 1 after till 2's refund", x(manager(), t1), "expectedCashInTill", "120.00");

    // each closes counting what it should hold; till 2 is a pound short, and says why
    Answer c1 =
        ItCalls.post(target, TILLS + "/" + t1 + "/close", manager(), "{\"countedCash\":120.00}");
    assertThat(c1.body().toString(), c1.status(), is(200));
    eq("till 1 close", c1.data(), "overShort", "0.00");
    Answer c2 =
        ItCalls.post(
            target,
            TILLS + "/" + t2 + "/close",
            manager(),
            "{\"countedCash\":129.00,\"note\":\"gave 1.00 too little change\"}");
    eq("till 2 close", c2.data(), "overShort", "-1.00");
  }

  @Test
  @DisplayName("money that names no session is shown apart, and is in no drawer")
  void notAtATill() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    assertThat(tender(who, Ids.newId(), "CASH", "10.00", t, store).status(), is(201));
    // a back-office tender at the store, naming no session
    assertThat(tender(manager(), Ids.newId(), "CASH", "7.00", null, store).status(), is(201));

    JsonObject r = x(manager(), t);

    eq("the drawer", r, "expectedCashInTill", "110.00");
    assertThat(cashIn(r, "notAtTill"), is(new BigDecimal("7.0000")));
  }

  @Test
  @DisplayName("a drawer opened the old way still counts the store's money in its window")
  void windowBasisIsAsItWas() {
    Caller who = cashier();
    UUID legacy = open(who, store, null);
    assertThat(tender(who, Ids.newId(), "CASH", "9.00", null, store).status(), is(201));

    JsonObject r = x(manager(), legacy);

    assertThat(r.getString("basis"), is("WINDOW"));
    eq("window", r, "cashSales", "9.00");
    assertThat(r.get("notAtTill"), is(nullValue()));
  }

  // ── what a session must be ─────────────────────────────────────────────────

  @Test
  @DisplayName(
      "a tender naming a session that is closed, at another store, or someone else's is refused")
  void aSessionIsJudged() {
    Caller who = cashier();
    UUID open = open(who, store, "SESSION");
    UUID closed = open(who, store, "SESSION");
    ItCalls.post(target, TILLS + "/" + closed + "/close", manager(), "{\"countedCash\":100}");
    Caller other = new Caller(biz, Ids.newId(), "CASHIER", elsewhere);
    UUID atElsewhere = open(other, elsewhere, "SESSION");

    Answer isClosed = tender(who, Ids.newId(), "CASH", "1.00", closed, store);
    assertThat(isClosed.status(), is(409));
    assertThat(isClosed.code(), is("TILL_SESSION_NOT_OPEN"));

    Answer wrongStore = tender(manager(), Ids.newId(), "CASH", "1.00", atElsewhere, store);
    assertThat(wrongStore.status(), is(409));
    assertThat(wrongStore.code(), is("TILL_SESSION_OTHER_STORE"));

    // a cashier held to the store cannot use the other store's drawer
    Answer notTheirs = tender(who, Ids.newId(), "CASH", "1.00", atElsewhere, null);
    assertThat(notTheirs.status(), is(403));

    // another business's session does not exist for us
    Caller rival = new Caller(mine.rival(), Ids.newId(), "OWNER");
    Answer rivals = tender(rival, Ids.newId(), "CASH", "1.00", open, null);
    assertThat(rivals.status(), is(404));
    assertThat(rivals.code(), is("TILL_SESSION_NOT_FOUND"));

    // none of it was written, and the open drawer is as it was
    eq("open drawer", x(manager(), open), "expectedCashInTill", "100.00");
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT coalesce(string_agg(method || ' ' || amount || ' ' || coalesce(till_session_id::text, '-')"
                + " || ' ' || coalesce(store_id::text, '-') || ' ' || status, E'\\n'), 'none')"
                + " FROM payment.payment_tenders WHERE tenant_id = '"
                + biz
                + "'"),
        is("none"));
  }

  @Test
  @DisplayName("a session is named by a UUIDv7 and a basis by a known word")
  void malformedNames() {
    Caller who = cashier();
    Answer bad = tender(who, Ids.newId(), "CASH", "1.00", null, store);
    assertThat(bad.status(), is(201)); // no session named is fine
    Answer garbage =
        ItCalls.call(
            target,
            "POST",
            "/payments",
            who,
            "{\"orderId\":\""
                + Ids.newId()
                + "\",\"amount\":1.00,\"method\":\"CASH\",\"storeId\":\""
                + store
                + "\",\"tillSessionId\":\"not-an-id\"}",
            Ids.newId().toString());
    assertThat(garbage.status(), is(400));

    Answer basis =
        ItCalls.post(
            target,
            TILLS,
            who,
            "{\"storeId\":\"" + store + "\",\"floatAmount\":10,\"basis\":\"MAYBE\"}");
    assertThat(basis.status(), is(400));
    assertThat(basis.code(), is("TILL_BASIS_INVALID"));
  }

  @Test
  @DisplayName(
      "another business reads none of this drawer, and its own reports hold none of this money")
  void otherBusinessSeesNothing() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    tender(who, Ids.newId(), "CASH", "50.00", t, store);
    Caller rival = new Caller(mine.rival(), Ids.newId(), "OWNER");

    Answer read = ItCalls.get(target, TILLS + "/" + t + "/x-report", rival);

    assertThat(read.status(), is(404));
    UUID theirStore = mine.rivalStore();
    UUID theirs = open(rival, theirStore, "SESSION");
    eq("their drawer", x(rival, theirs), "expectedCashInTill", "100.00");
  }

  // ── a return the till gave ─────────────────────────────────────────────────

  @Test
  @DisplayName(
      "cash a return paid out is counted in the drawer that gave it; a wrong drawer is dropped, not the refund")
  void aReturnsCashIsCountedInItsDrawer() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID elsewhereSession =
        open(new Caller(biz, Ids.newId(), "CASHIER", elsewhere), elsewhere, "SESSION");
    UUID order = Ids.newId();
    assertThat(tender(who, order, "CASH", "30.00", t, store).status(), is(201));

    service.refundReturnForOrderEvent(
        Ids.newId(),
        "it",
        biz,
        order,
        new BigDecimal("4.00"),
        "return",
        new PaymentService.ReturnRefund("ORIGINAL", Ids.newId(), null, "GBP", null, t));
    // a return that names a drawer of another store, or none that exists, is still refunded
    service.refundReturnForOrderEvent(
        Ids.newId(),
        "it",
        biz,
        order,
        new BigDecimal("3.00"),
        "return",
        new PaymentService.ReturnRefund(
            "ORIGINAL", Ids.newId(), null, "GBP", null, elsewhereSession));
    service.refundReturnForOrderEvent(
        Ids.newId(),
        "it",
        biz,
        order,
        new BigDecimal("2.00"),
        "return",
        new PaymentService.ReturnRefund("ORIGINAL", Ids.newId(), null, "GBP", null, Ids.newId()));

    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT string_agg(amount || ':' || coalesce(till_session_id::text, '-'), ',' ORDER BY amount)"
                + " FROM payment.refund_tenders WHERE tenant_id = '"
                + biz
                + "' AND order_id = '"
                + order
                + "'"),
        is("2.0000:-,3.0000:-,4.0000:" + t));
    // only the first is in the drawer: 100 + 30 - 4; the others are apart, not lost
    JsonObject r = x(manager(), t);
    eq("the drawer", r, "expectedCashInTill", "126.00");
    assertThat(
        r.getJsonObject("notAtTill")
            .getJsonObject("CASH")
            .getJsonNumber("refunds")
            .bigDecimalValue()
            .compareTo(new BigDecimal("5.00")),
        is(0));
  }

  // ── a retry is answered, whatever became of the drawer ────────────────────

  @Test
  @DisplayName("a retried tender whose drawer has since closed answers the first tender, once")
  void aReplayAfterTheDrawerClosesAnswersTheFirstTender() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID order = Ids.newId();
    String key = Ids.newId().toString();

    Answer first = tenderKeyed(who, order, "CASH", "12.00", t, store, key);
    assertThat(first.body().toString(), first.status(), is(201));
    assertThat(close(t, "112.00").status(), is(200));

    // the response was lost; the cashier sends it again after the manager closed the drawer
    Answer again = tenderKeyed(who, order, "CASH", "12.00", t, store, key);
    assertThat(again.body().toString(), again.status(), is(201));
    assertThat(again.data().getString("id"), is(first.data().getString("id")));
    assertThat(countOf("payment_tenders", order), is("1"));
    assertThat(
        "one announcement, not two",
        scalar(
            "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
                + biz
                + "' AND event_type = 'PaymentCaptured' AND aggregate_id = '"
                + first.data().getString("id")
                + "'"),
        is("1"));

    // a NEW tender naming the closed drawer is still refused
    Answer fresh = tender(who, Ids.newId(), "CASH", "1.00", t, store);
    assertThat(fresh.status(), is(409));
    assertThat(fresh.code(), is("TILL_SESSION_NOT_OPEN"));
  }

  @Test
  @DisplayName("a retried refund whose drawer has since closed answers the first refund, once")
  void aRefundReplayAfterTheDrawerClosesAnswersTheFirstRefund() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID order = Ids.newId();
    Answer paid = tender(who, order, "CASH", "30.00", t, store);
    String key = Ids.newId().toString();

    Answer first = refundKeyed(manager(), order, paid.data().getString("id"), "5.00", t, key);
    assertThat(first.body().toString(), first.status(), is(201));
    assertThat(close(t, "125.00").status(), is(200));

    Answer again = refundKeyed(manager(), order, paid.data().getString("id"), "5.00", t, key);
    assertThat(again.body().toString(), again.status(), is(201));
    assertThat(again.data().getString("id"), is(first.data().getString("id")));
    assertThat("refunded once", countOf("refund_tenders", order), is("1"));

    Answer fresh = refund(manager(), order, paid.data().getString("id"), "1.00", t);
    assertThat(fresh.status(), is(409));
    assertThat(fresh.code(), is("TILL_SESSION_NOT_OPEN"));
    assertThat("and nothing more was written", countOf("refund_tenders", order), is("1"));
  }

  // ── a manager's refund naming a drawer is judged as a tender is ───────────

  @Test
  @DisplayName(
      "a refund naming a drawer that is closed, at another store, unknown or another business's is"
          + " refused and nothing is written")
  void aManualRefundIsJudgedLikeATender() {
    Caller who = cashier();
    UUID open = open(who, store, "SESSION");
    UUID closed = open(who, store, "SESSION");
    close(closed, "100");
    UUID atElsewhere =
        open(new Caller(biz, Ids.newId(), "CASHIER", elsewhere), elsewhere, "SESSION");
    UUID order = Ids.newId();
    String paymentId = tender(who, order, "CASH", "30.00", open, store).data().getString("id");

    Answer isClosed = refund(manager(), order, paymentId, "1.00", closed);
    assertThat(isClosed.status(), is(409));
    assertThat(isClosed.code(), is("TILL_SESSION_NOT_OPEN"));

    Answer wrongStore = refund(manager(), order, paymentId, "1.00", atElsewhere);
    assertThat(wrongStore.status(), is(409));
    assertThat(wrongStore.code(), is("TILL_SESSION_OTHER_STORE"));

    Answer unknown = refund(manager(), order, paymentId, "1.00", Ids.newId());
    assertThat(unknown.status(), is(404));
    assertThat(unknown.code(), is("TILL_SESSION_NOT_FOUND"));

    // a manager held to this store cannot pay out of the other store's drawer either
    Caller held = new Caller(biz, Ids.newId(), "MANAGER", store);
    Answer notTheirs = refund(held, order, paymentId, "1.00", atElsewhere);
    assertThat(notTheirs.status(), is(403));

    Caller rival = new Caller(mine.rival(), Ids.newId(), "OWNER");
    Answer rivals = refund(rival, order, paymentId, "1.00", open);
    assertThat(rivals.status(), is(404));
    assertThat(rivals.code(), is("TILL_SESSION_NOT_FOUND"));

    assertThat("nothing was written", countOf("refund_tenders", order), is("0"));
    eq("the open drawer", x(manager(), open), "expectedCashInTill", "130.00");

    // and the one a manager may name is counted
    Answer fine = refund(manager(), order, paymentId, "4.00", open);
    assertThat(fine.body().toString(), fine.status(), is(201));
    eq("the open drawer after", x(manager(), open), "expectedCashInTill", "126.00");
  }

  @Test
  @DisplayName(
      "a refund naming a drawer is that drawer's store's, even for a tender taken at no store, so"
          + " the drawer, the store's reports and the day agree")
  void aRefundOfAStorelessTenderTakesTheDrawersStore() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID order = Ids.newId();
    // a back-office cash tender recorded with no store at all
    String paymentId = tender(manager(), order, "CASH", "20.00", null, null).data().getString("id");

    Answer back = refund(manager(), order, paymentId, "10.00", t);

    assertThat(back.body().toString(), back.status(), is(201));
    assertThat(
        scalar(
            "SELECT store_id || ' ' || till_session_id FROM payment.refund_tenders"
                + " WHERE tenant_id = '"
                + biz
                + "' AND order_id = '"
                + order
                + "'"),
        is(store + " " + t));
    eq("the drawer paid it out", x(manager(), t), "expectedCashInTill", "90.00");
  }

  // ── an event never moves a closed drawer ──────────────────────────────────

  @Test
  @DisplayName(
      "a return or a void naming a drawer that has closed is refunded and counted at no drawer")
  void aClosedDrawersEventRefundsAreNotAtATill() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID other = open(cashier(), store, "SESSION");
    UUID order = Ids.newId();
    UUID voided = Ids.newId();
    assertThat(tender(who, order, "CASH", "30.00", t, store).status(), is(201));
    assertThat(tender(who, voided, "CASH", "10.00", t, store).status(), is(201));
    close(t, "140.00");

    service.refundReturnForOrderEvent(
        Ids.newId(),
        "it",
        biz,
        order,
        new BigDecimal("4.00"),
        "return",
        new PaymentService.ReturnRefund("ORIGINAL", Ids.newId(), null, "GBP", null, t));
    service.refundVoidForOrderEvent(Ids.newId(), "it", biz, voided, t);

    assertThat(
        "the rows hold no drawer",
        scalar(
            "SELECT string_agg(amount::numeric(10,2) || ':' || coalesce(till_session_id::text, '-'),"
                + " ',' ORDER BY amount) FROM payment.refund_tenders WHERE tenant_id = '"
                + biz
                + "'"),
        is("4.00:-,10.00:-"));
    eq("the closed drawer is as it was counted", x(manager(), t), "expectedCashInTill", "140.00");
    assertThat(
        "the open one at the store shows them apart",
        x(manager(), other)
            .getJsonObject("notAtTill")
            .getJsonObject("CASH")
            .getJsonNumber("refunds")
            .bigDecimalValue()
            .compareTo(new BigDecimal("14.00")),
        is(0));
  }

  @Test
  @DisplayName("the cash a void hands back is counted in the drawer that gave it")
  void aVoidsCashIsCountedInItsDrawer() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID atElsewhere =
        open(new Caller(biz, Ids.newId(), "CASHIER", elsewhere), elsewhere, "SESSION");
    UUID order = Ids.newId();
    UUID stray = Ids.newId();
    assertThat(tender(who, order, "CASH", "20.00", t, store).status(), is(201));
    assertThat(tender(who, stray, "CASH", "8.00", t, store).status(), is(201));

    service.refundVoidForOrderEvent(Ids.newId(), "it", biz, order, t);
    // a void naming a drawer of another store, or none that exists, is still refunded
    service.refundVoidForOrderEvent(Ids.newId(), "it", biz, stray, atElsewhere);

    assertThat(
        scalar(
            "SELECT string_agg(amount::numeric(10,2) || ':' || coalesce(till_session_id::text, '-'),"
                + " ',' ORDER BY amount) FROM payment.refund_tenders WHERE tenant_id = '"
                + biz
                + "'"),
        is("8.00:-,20.00:" + t));
    eq("the drawer", x(manager(), t), "expectedCashInTill", "108.00");
  }

  // ── a write is judged on the transaction that makes it ────────────────────

  @Test
  @DisplayName("a tender cannot land on a drawer that closed after the check, and writes nothing")
  void aTenderCannotLandOnADrawerThatClosedAfterTheCheck() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    close(t, "100");
    UUID order = Ids.newId();
    PaymentTender late = cashTender(order, "5.00", store);

    ApiException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> payments.createTender(late, captured(late), null, t));

    assertThat(refused.code(), is("TILL_SESSION_NOT_OPEN"));
    assertThat(countOf("payment_tenders", order), is("0"));
    assertThat(
        "and announced nothing",
        scalar("SELECT count(*) FROM payment.outbox WHERE aggregate_id = '" + late.id() + "'"),
        is("0"));
  }

  @Test
  @DisplayName("a refund cannot be paid out of a drawer that closed after the check")
  void aRefundCannotLandOnADrawerThatClosedAfterTheCheck() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID order = Ids.newId();
    PaymentTender paid = cashTender(order, "30.00", store);
    payments.createTender(paid, captured(paid));
    close(t, "100");
    RefundTender back =
        new RefundTender(
            Ids.newId(),
            biz,
            order,
            paid.id(),
            new BigDecimal("5.00"),
            "CASH",
            null,
            null,
            "late",
            java.time.Instant.now());

    ApiException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () ->
                payments.createRefundGuarded(
                    back,
                    new OutboxRow(
                        "PaymentRefunded",
                        "storeql.payment.payment-refunded",
                        biz,
                        back.id(),
                        "{}"),
                    storeId -> {},
                    t));

    assertThat(refused.code(), is("TILL_SESSION_NOT_OPEN"));
    assertThat(countOf("refund_tenders", order), is("0"));
  }

  @Test
  @DisplayName("a drop, a pay-in or a pay-out cannot be written against a closed drawer")
  void dropsAndMovementsAreJudgedOnTheirOwnTransaction() {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    close(t, "100");

    ApiException drop =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () ->
                tills.recordDrop(
                    new com.storeql.payment.domain.Domain.CashDrop(
                        Ids.newId(),
                        biz,
                        t,
                        new BigDecimal("5.00"),
                        Ids.newId(),
                        null,
                        java.time.Instant.now())));
    assertThat(drop.code(), is("TILL_CLOSED"));

    Answer in =
        ItCalls.post(
            target,
            "/admin/cash/movements",
            manager(),
            "{\"tillSessionId\":\""
                + t
                + "\",\"storeId\":\""
                + store
                + "\",\"direction\":\"PAY_IN\",\"amount\":5.00,\"reason\":\"late\"}");
    assertThat(in.body().toString(), in.status(), is(400));
    assertThat(in.code(), is("TILL_CLOSED"));
    assertThat(
        "nothing was written against the closed drawer",
        scalar(
            "SELECT (SELECT count(*) FROM payment.cash_drops WHERE tenant_id = '"
                + biz
                + "' AND till_session_id = '"
                + t
                + "') + (SELECT count(*) FROM payment.cash_movements WHERE tenant_id = '"
                + biz
                + "' AND till_session_id = '"
                + t
                + "')"),
        is("0"));
  }

  @Test
  @DisplayName(
      "a close waits for a tender already being written to its drawer, and counts it: what is"
          + " stored is what is answered and announced")
  void aCloseWaitsForATenderAlreadyInFlight() throws Exception {
    Caller who = cashier();
    UUID t = open(who, store, "SESSION");
    UUID order = Ids.newId();
    try (java.sql.Connection writer =
            java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = writer.createStatement()) {
      writer.setAutoCommit(false);
      // a writer that has passed its check holds the drawer's row shared, and its tender is in
      // flight: committed after the close would have read the drawer, it would be in no count
      st.execute(
          "SELECT status FROM payment.till_sessions WHERE tenant_id = '"
              + biz
              + "' AND id = '"
              + t
              + "' FOR SHARE");
      st.executeUpdate(
          "INSERT INTO payment.payment_tenders (id, tenant_id, order_id, amount, method, status,"
              + " created_at, store_id, till_session_id) VALUES ('"
              + Ids.newId()
              + "', '"
              + biz
              + "', '"
              + order
              + "', 50.00, 'CASH', 'CAPTURED', now(), '"
              + store
              + "', '"
              + t
              + "')");

      var closing = java.util.concurrent.CompletableFuture.supplyAsync(() -> close(t, "150.00"));
      org.junit.jupiter.api.Assertions.assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> closing.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS),
          "the close must wait for the tender being written to its drawer");
      writer.commit();

      Answer closed = closing.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(closed.body().toString(), closed.status(), is(200));
      eq("the answer counts the tender", closed.data(), "expectedCashInTill", "150.00");
      eq("so the count is right", closed.data(), "overShort", "0.00");
    }
    assertThat(
        "stored the same",
        scalar(
            "SELECT over_short::numeric(10,2) FROM payment.till_sessions WHERE tenant_id = '"
                + biz
                + "' AND id = '"
                + t
                + "'"),
        is("0.00"));
    assertThat(
        "announced the same",
        scalar(
            "SELECT (payload::jsonb ->> 'expectedCash')::numeric(10,2) || '/' ||"
                + " (payload::jsonb ->> 'overShort')::numeric(10,2) FROM payment.outbox"
                + " WHERE tenant_id = '"
                + biz
                + "' AND event_type = 'TillSessionClosed' AND aggregate_id = '"
                + t
                + "'"),
        is("150.00/0.00"));
    eq("and read again", x(manager(), t), "expectedCashInTill", "150.00");
  }

  // ── what a WINDOW drawer counts, and what the day settles ─────────────────

  @Test
  @DisplayName(
      "a drawer on the WINDOW basis counts the store's money in its window, naming a drawer or not")
  void aWindowDrawerCountsMoneyThatNamesAnotherDrawer() {
    Caller who = cashier();
    UUID legacy = open(who, store, null);
    UUID exact = open(cashier(), store, "SESSION");
    assertThat(tender(who, Ids.newId(), "CASH", "9.00", null, store).status(), is(201));
    assertThat(tender(who, Ids.newId(), "CASH", "11.00", exact, store).status(), is(201));
    assertThat(tender(who, Ids.newId(), "CASH", "6.00", exact, store).status(), is(201));

    JsonObject w = x(manager(), legacy);

    assertThat(w.getString("basis"), is("WINDOW"));
    // everything the store took while it was open, whichever drawer it named
    eq("window", w, "cashSales", "26.00");
    eq("the exact one", x(manager(), exact), "cashSales", "17.00");
  }

  @Test
  @DisplayName("the day report settles the store's whole day across SESSION drawers")
  void theDayReportSettlesTheStoresDayAcrossSessionDrawers() {
    Caller one = cashier();
    Caller two = cashier();
    UUID t1 = open(one, store, "SESSION");
    UUID t2 = open(two, store, "SESSION");
    UUID sale1 = Ids.newId();
    UUID sale2 = Ids.newId();
    String paid1 = tender(one, sale1, "CASH", "20.00", t1, store).data().getString("id");
    tender(two, sale2, "CASH", "35.00", t2, store);
    // money naming no drawer: a back-office cash sale, and a refund an event gave back
    tender(manager(), Ids.newId(), "CASH", "7.00", null, store);
    assertThat(refund(manager(), sale1, paid1, "5.00", t2).status(), is(201));
    service.refundReturnForOrderEvent(
        Ids.newId(),
        "it",
        biz,
        sale2,
        new BigDecimal("2.00"),
        "return",
        new PaymentService.ReturnRefund("ORIGINAL", Ids.newId(), null, "GBP", null, null));
    // drawer 1: 100 + 20; drawer 2: 100 + 35 - 5; apart: +7 - 2
    eq("drawer 1", x(manager(), t1), "expectedCashInTill", "120.00");
    eq("drawer 2", x(manager(), t2), "expectedCashInTill", "130.00");
    assertThat(close(t1, "120.00").status(), is(200));
    assertThat(close(t2, "130.00").status(), is(200));

    Answer day =
        ItCalls.post(
            target,
            "/admin/cash/z-report",
            manager(),
            "{\"storeId\":\"" + store + "\",\"countedCash\":255.00}");

    assertThat(day.body().toString(), day.status(), is(201));
    JsonObject z = day.data();
    eq("the floats of both drawers", z, "openingFloat", "200.00");
    eq("all the cash the store took", z, "cashSales", "62.00");
    eq("all the cash it gave back", z, "cashRefunds", "7.00");
    // the drawers' expectations added to what was in no drawer
    eq("the day's cash", z, "expectedCash", "255.00");
    eq("counted as expected", z, "overShort", "0.00");
  }
}
