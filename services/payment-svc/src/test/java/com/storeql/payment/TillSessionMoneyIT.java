package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.service.PaymentService;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
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
    for (int i = 0; i < 12; i++) {
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
        Ids.newId().toString());
  }

  private Answer refund(Caller who, UUID order, String paymentId, String amount, UUID session) {
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
        Ids.newId().toString());
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
}
