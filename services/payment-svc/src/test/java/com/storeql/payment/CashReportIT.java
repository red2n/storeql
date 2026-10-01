package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The till's X report and close count only their own session's store, and every term of the one
 * formula (float + cash sales - cash refunds + pay-ins - pay-outs - drops): a second store trading
 * in the same window changes nothing, another business sees none of it, a manager held to one store
 * cannot read or close another's till, and the close answers over/short, keeps the closer's note
 * and announces {@code TillSessionClosed} once.
 */
@HelidonTest
class CashReportIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");
  private static final String TILLS = "/admin/cash/till-sessions";

  @Inject WebTarget target;
  @Inject PaymentRepository payments;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private UUID open(Caller who, UUID store, String floatAmount) {
    Answer a =
        ItCalls.post(
            target,
            TILLS,
            who,
            "{\"storeId\":\"" + store + "\",\"floatAmount\":" + floatAmount + "}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(a.data().getString("id"));
  }

  private void sale(UUID tenant, UUID store, String method, String amount, Instant at) {
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenant,
            Ids.newId(),
            new BigDecimal(amount),
            method,
            null,
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            at,
            store),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenant, id, "{}"));
  }

  private void refund(UUID tenant, UUID store, String method, String amount) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO payment.refund_tenders"
                    + " (id, tenant_id, order_id, payment_id, amount, method, created_at, store_id)"
                    + " VALUES (?,?,?,?,?,?, now(), ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenant);
      ps.setObject(3, Ids.newId());
      ps.setObject(4, Ids.newId());
      ps.setBigDecimal(5, new BigDecimal(amount));
      ps.setString(6, method);
      ps.setObject(7, store);
      ps.executeUpdate();
    }
  }

  private Answer movement(Caller who, UUID store, UUID session, String direction, String amount) {
    return ItCalls.post(
        target,
        "/admin/cash/movements",
        who,
        "{\"tillSessionId\":\""
            + session
            + "\",\"storeId\":\""
            + store
            + "\",\"direction\":\""
            + direction
            + "\",\"amount\":"
            + amount
            + ",\"reason\":\"it\"}");
  }

  private Answer drop(Caller who, UUID session, String amount) {
    return ItCalls.post(
        target, TILLS + "/" + session + "/drops", who, "{\"amount\":" + amount + "}");
  }

  private Answer xReport(Caller who, UUID session) {
    return ItCalls.get(target, TILLS + "/" + session + "/x-report", who);
  }

  private static BigDecimal num(JsonObject o, String key) {
    return o.getJsonNumber(key).bigDecimalValue();
  }

  private static void eq(String what, JsonObject o, String key, String expected) {
    assertThat(
        what + " " + key + " in " + o, num(o, key).compareTo(new BigDecimal(expected)), is(0));
  }

  private String outbox(UUID tenant, UUID session) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
            + tenant
            + "' AND event_type = 'TillSessionClosed' AND aggregate_id = '"
            + session
            + "'");
  }

  // ── the report's shape and every term ──────────────────────────────────────

  @Test
  @DisplayName(
      "Mixed sales, refunds, a drop, a pay-in and a pay-out: every term shown, one formula")
  void mixedSalesRefundsDropsAndMovements() throws Exception {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    Caller manager = new Caller(tenant, Ids.newId(), "MANAGER");
    UUID tillA = open(manager, storeA, "100.00");
    UUID tillB = open(manager, storeB, "10.00");
    Instant now = Instant.now();

    sale(tenant, storeA, "CASH", "50.00", now);
    sale(tenant, storeA, "CARD", "30.00", now);
    sale(
        tenant, storeA, "CASH", "400.00", now.minus(2, ChronoUnit.HOURS)); // before the till opened
    sale(tenant, storeB, "CASH", "999.00", now);
    refund(tenant, storeA, "CASH", "10.00");
    refund(tenant, storeA, "CARD", "5.00");
    refund(tenant, storeB, "CASH", "777.00");
    assertThat(drop(manager, tillA, "20.00").status(), is(201));
    assertThat(movement(manager, storeA, tillA, "PAY_IN", "15.00").status(), is(201));
    assertThat(movement(manager, storeA, tillA, "PAY_OUT", "8.00").status(), is(201));

    Answer x = xReport(manager, tillA);

    assertThat(x.body().toString(), x.status(), is(200));
    JsonObject r = x.data();
    eq("A", r, "floatAmount", "100");
    eq("A", r, "cashSales", "50");
    eq("A", r, "cashRefunds", "10");
    eq("A", r, "payIns", "15");
    eq("A", r, "payOuts", "8");
    eq("A", r, "cashDropsTotal", "20");
    eq("A", r, "expectedCashInTill", "127"); // 100 + 50 - 10 + 15 - 8 - 20
    eq("A", r, "grossSales", "80");
    eq("A", r, "totalRefunds", "15");
    eq("A", r, "netSales", "65");
    assertThat(r.getString("basis"), is("WINDOW"));
    assertThat(r.containsKey("overShort") && !r.isNull("overShort"), is(false));

    Answer closed =
        ItCalls.post(
            target,
            TILLS + "/" + tillA + "/close",
            manager,
            "{\"countedCash\":130.00,\"note\":\"a coin bag miscounted\"}");
    assertThat(closed.body().toString(), closed.status(), is(200));
    eq("close", closed.data(), "expectedCashInTill", "127");
    eq("close", closed.data(), "overShort", "3");
    assertThat(closed.data().getString("note"), is("a coin bag miscounted"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT note || '|' || closed_by || '|' || over_short FROM payment.till_sessions"
                + " WHERE tenant_id = '"
                + tenant
                + "' AND id = '"
                + tillA
                + "'"),
        is("a coin bag miscounted|" + manager.userId() + "|3.0000"));

    // Store B's drawer, untouched by A: 10 + 999 - 777.
    Answer b = xReport(manager, tillB);
    eq("B", b.data(), "expectedCashInTill", "232");
    eq("B", b.data(), "cashRefunds", "777");
  }

  @Test
  @DisplayName("A second store trading in the same window is not in this drawer")
  void anotherStoresSalesAndRefundsAreNotInThisDrawer() throws Exception {
    UUID tenant = Ids.newId();
    UUID mine = Ids.newId();
    UUID theirs = Ids.newId();
    Caller manager = new Caller(tenant, Ids.newId(), "MANAGER");
    UUID till = open(manager, mine, "10.00");
    Instant now = Instant.now();
    sale(tenant, theirs, "CASH", "500.00", now);
    sale(tenant, theirs, "CARD", "60.00", now);
    refund(tenant, theirs, "CASH", "40.00");

    JsonObject r = xReport(manager, till).data();

    eq("mine", r, "expectedCashInTill", "10");
    eq("mine", r, "grossSales", "0");
    eq("mine", r, "totalRefunds", "0");
    assertThat(r.getJsonObject("tenderSummary").isEmpty(), is(true));
  }

  // ── the close ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "The close announces TillSessionClosed once, with the figures, and cannot be repeated")
  void publishedOnce() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller owner = Caller.owner(tenant);
    UUID till = open(owner, store, "80.00");
    sale(tenant, store, "CASH", "20.00", Instant.now());

    Answer closed =
        ItCalls.post(target, TILLS + "/" + till + "/close", owner, "{\"countedCash\":95.00}");
    Answer again =
        ItCalls.post(target, TILLS + "/" + till + "/close", owner, "{\"countedCash\":95.00}");

    assertThat(closed.body().toString(), closed.status(), is(200));
    eq("close", closed.data(), "overShort", "-5"); // expected 100
    assertThat(again.status(), is(400));
    assertThat(again.code(), is("TILL_CLOSED"));
    assertThat(outbox(tenant, till), is("1"));
    String payload =
        Envelopes.scalar(
            PG,
            "SELECT payload FROM payment.outbox WHERE tenant_id = '"
                + tenant
                + "' AND event_type = 'TillSessionClosed'");
    assertThat(payload, containsString("\"sessionId\":\"" + till + "\""));
    assertThat(payload, containsString("\"storeId\":\"" + store + "\""));
    assertThat(payload, containsString("\"closedBy\":\"" + owner.userId() + "\""));
    assertThat(payload, containsString("\"expectedCash\":100"));
    assertThat(payload, containsString("\"countedCash\":95.00"));
    assertThat(payload, containsString("\"overShort\":-5"));
    assertThat(payload, containsString("\"eventId\":\""));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT topic FROM payment.outbox WHERE tenant_id = '"
                + tenant
                + "' AND event_type = 'TillSessionClosed'"),
        is("storeql.payment.till-session-closed"));
  }

  @Test
  @DisplayName("A note is optional, kept trimmed, and no longer than 500 characters")
  void theNoteIsBounded() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller owner = Caller.owner(tenant);
    UUID till = open(owner, store, "5.00");

    Answer tooLong =
        ItCalls.post(
            target,
            TILLS + "/" + till + "/close",
            owner,
            "{\"countedCash\":5.00,\"note\":\"" + "x".repeat(501) + "\"}");
    assertThat(tooLong.status(), is(400));
    assertThat(
        "nothing closed",
        Envelopes.scalar(
            PG,
            "SELECT status FROM payment.till_sessions WHERE tenant_id = '"
                + tenant
                + "' AND id = '"
                + till
                + "'"),
        is("OPEN"));

    Answer none =
        ItCalls.post(target, TILLS + "/" + till + "/close", owner, "{\"countedCash\":5.00}");
    assertThat(none.body().toString(), none.status(), is(200));
    assertThat(none.data().containsKey("note") && !none.data().isNull("note"), is(false));
    eq("balanced", none.data(), "overShort", "0");
  }

  // ── who may read and close ─────────────────────────────────────────────────

  @Test
  @DisplayName("Another business sees none of our money, and cannot read or close our till")
  void otherBusinessSeesNothing() throws Exception {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller ours = new Caller(tenant, Ids.newId(), "MANAGER");
    UUID till = open(ours, store, "10.00");
    UUID stranger = Ids.newId();
    // The rival trades at a store carrying the same id and takes money there in the same window.
    UUID theirs = open(new Caller(stranger, Ids.newId(), "MANAGER"), store, "1.00");
    sale(stranger, store, "CASH", "300.00", Instant.now());
    refund(stranger, store, "CASH", "20.00");

    eq("ours", xReport(ours, till).data(), "expectedCashInTill", "10");
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Caller rival = new Caller(stranger, Ids.newId(), role, store);
      Answer x = xReport(rival, till);
      assertThat(role + " " + x.body(), x.status(), is(404));
      assertThat(x.code(), is("TILL_SESSION_NOT_FOUND"));
      Answer close =
          ItCalls.post(target, TILLS + "/" + till + "/close", rival, "{\"countedCash\":1}");
      assertThat(role + " " + close.body(), close.status(), is(404));
      assertThat(drop(rival, till, "1.00").status(), is(404));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Caller rival = new Caller(stranger, Ids.newId(), role, store);
      assertThat(role, xReport(rival, till).status(), is(403));
      assertThat(
          role,
          ItCalls.post(target, TILLS + "/" + till + "/close", rival, "{\"countedCash\":1}")
              .status(),
          is(403));
    }
    assertThat(
        "still open, nothing announced",
        Envelopes.scalar(
            PG,
            "SELECT status FROM payment.till_sessions WHERE tenant_id = '"
                + tenant
                + "' AND id = '"
                + till
                + "'"),
        is("OPEN"));
    assertThat(outbox(tenant, till), is("0"));
    // Their own report for the same store id counts only their money.
    eq(
        "theirs",
        xReport(new Caller(stranger, Ids.newId(), "MANAGER"), theirs).data(),
        "expectedCashInTill",
        "281"); // 1 + 300 - 20
  }

  @Test
  @DisplayName("A manager held to store A cannot read, drop into or close store B's till")
  void aManagerHeldToOneStoreIsRefusedTheOther() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    UUID tillB = open(Caller.owner(tenant), storeB, "10.00");
    Caller heldToA = new Caller(tenant, Ids.newId(), "MANAGER", storeA);

    Answer x = xReport(heldToA, tillB);
    assertThat(x.body().toString(), x.status(), is(403));
    assertThat(x.code(), is("STORE_ACCESS_DENIED"));
    assertThat(drop(heldToA, tillB, "1.00").status(), is(403));
    assertThat(
        ItCalls.post(target, TILLS + "/" + tillB + "/close", heldToA, "{\"countedCash\":1}")
            .status(),
        is(403));
    assertThat(outbox(tenant, tillB), is("0"));
    // Their own store reads.
    UUID tillA = open(heldToA, storeA, "3.00");
    assertThat(xReport(heldToA, tillA).status(), is(200));
  }

  @Test
  @DisplayName("A movement that is neither in nor out is 400 INVALID_DIRECTION and is not kept")
  void aMovementThatIsNeitherInNorOutIsRefused() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller manager = new Caller(tenant, Ids.newId(), "MANAGER");
    UUID till = open(manager, store, "10.00");

    for (String direction : new String[] {"SIDEWAYS", "pay_in", "Pay_Out"}) {
      Answer a = movement(manager, store, till, direction, "5.00");
      assertThat(direction + " " + a.body(), a.status(), is(400));
      assertThat(direction, a.code(), is("INVALID_DIRECTION"));
    }
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM payment.cash_movements WHERE tenant_id = '"
                + tenant
                + "' AND till_session_id = '"
                + till
                + "'"),
        is("0"));
    eq("drawer", xReport(manager, till).data(), "expectedCashInTill", "10");
    // A proper direction is taken, so the refusal was the direction's.
    assertThat(movement(manager, store, till, "PAY_IN", "5.00").status(), is(201));
  }

  @Test
  @DisplayName("Closes arriving together close the till once and announce it once")
  void twoClosesAtOnceCloseTheTillOnce() throws Exception {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller owner = Caller.owner(tenant);
    UUID till = open(owner, store, "80.00");
    int n = 4;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Answer>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return ItCalls.post(
                    target, TILLS + "/" + till + "/close", owner, "{\"countedCash\":95.00}");
              }));
    }
    ready.await();
    go.countDown();
    int ok = 0;
    for (Future<Answer> f : futures) {
      Answer a = f.get();
      if (a.status() == 200) {
        ok++;
      } else if (a.status() == 409) {
        assertThat(a.body().toString(), a.code(), is("TILL_ALREADY_CLOSED"));
      } else {
        assertThat(a.body().toString(), a.status(), is(400));
        assertThat(a.code(), is("TILL_CLOSED"));
      }
    }
    pool.shutdown();

    assertThat(ok, is(1));
    assertThat(outbox(tenant, till), is("1"));
  }
}
