package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.service.PaymentService;
import com.storeql.payment.service.TerminalService;
import com.storeql.test.Concurrency;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.web.ApiException;
import io.helidon.microprofile.testing.AddBean;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A till cannot charge a card twice, whatever it remembers: the server refuses a new sale on a card
 * machine while one is still at it, took money that is neither recorded on its order nor put back,
 * or timed out with nobody's word on it — and money owed back to a card a machine took goes back
 * through that machine, never as a refund in the books alone.
 *
 * <p>The simulator picks its outcome from the amount's last two minor units: {@code .01} declines,
 * {@code .03} times out; a refund declines on {@code .01} and fails on {@code .04}. The {@link
 * GatedTerminal} holds a card at the machine until the test answers for the cardholder, so what
 * happens while it waits — a cancel, a sale given up, a person's word — is raced for real.
 */
@HelidonTest
@AddBean(GatedTerminal.class)
class TerminalSettlementIT {

  private static final PostgresSupport PG;

  private static final UUID BIZ = Ids.newId();
  private static final UUID HERE = Ids.newId();
  private static final UUID ELSEWHERE = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID RIVAL_STORE = Ids.newId();
  private static final String CONSUMER = "payment-svc/order-refund";

  /**
   * Stores of the business with no card machine but the ones a test registers there, one per test:
   * a card goes back through another machine of its vendor at its store, so what a retired machine
   * leaves behind is only seen at a store no other test's machines stand in.
   */
  private static final List<UUID> LONE =
      List.of(
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId(),
          Ids.newId());

  static {
    PG = PostgresSupport.start().wire("payment");
    TenantSvcStub tenants =
        TenantSvcStub.start()
            .with(BIZ.toString(), "GBP", "GB")
            .withStore(BIZ.toString(), HERE.toString(), "GB")
            .withStore(BIZ.toString(), ELSEWHERE.toString(), "GB")
            .with(RIVAL.toString(), "GBP", "GB")
            .withStore(RIVAL.toString(), RIVAL_STORE.toString(), "GB");
    for (UUID lone : LONE) tenants.withStore(BIZ.toString(), lone.toString(), "GB");
  }

  @Inject TerminalService terminals;
  @Inject PaymentService payments;
  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private final UUID actor = Ids.newId();
  private final Caller cashier = new Caller(BIZ, Ids.newId(), "CASHIER", HERE);
  private final Caller manager = new Caller(BIZ, Ids.newId(), "MANAGER", HERE);
  private final Caller owner = Caller.owner(BIZ);
  private final Caller elsewhere = Caller.heldTo(BIZ, "MANAGER", ELSEWHERE);
  private final Caller elsewhereCashier = Caller.heldTo(BIZ, "CASHIER", ELSEWHERE);

  private static List<Caller> rivals() {
    return List.of(
        new Caller(RIVAL, Ids.newId(), "OWNER"),
        new Caller(RIVAL, Ids.newId(), "MANAGER", RIVAL_STORE),
        new Caller(RIVAL, Ids.newId(), "CASHIER", RIVAL_STORE));
  }

  // ── calls ───────────────────────────────────────────────────────────────────

  private Terminals.Terminal machine() {
    return machineAt(HERE);
  }

  private Terminals.Terminal machineAt(UUID store) {
    return terminals.register(BIZ, store, "Till " + Ids.newId(), "SIMULATED", null, actor);
  }

  private static Caller cashierAt(UUID store) {
    return new Caller(BIZ, Ids.newId(), "CASHIER", store);
  }

  private static Caller managerAt(UUID store) {
    return new Caller(BIZ, Ids.newId(), "MANAGER", store);
  }

  /** A card sale of {@code amount} taken on a machine and recorded: the attempt and its tender. */
  private String[] recordedAt(Terminals.Terminal t, UUID order, String amount) {
    Caller till = cashierAt(t.storeId());
    Answer a = sale(till, t.id(), order, amount, Ids.newId().toString());
    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(a.data().getString("state"), is("APPROVED"));
    String attempt = a.data().getString("id");
    Answer tender = record(till, order, amount, "CARD", attempt, Ids.newId().toString());
    assertThat(tender.body().toString(), tender.status(), is(201));
    return new String[] {attempt, tender.data().getString("id")};
  }

  private Answer anotherWay(
      Caller who, String due, String method, String reference, String reason, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/terminal/refund-dues/" + due + "/another-way",
        who,
        "{\"method\":"
            + (method == null ? "null" : "\"" + method + "\"")
            + (reference == null ? "" : ",\"reference\":\"" + reference + "\"")
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}",
        key);
  }

  private Answer retryDue(Caller who, String due) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/terminal/refund-dues/" + due + "/retry",
        who,
        "{}",
        Ids.newId().toString());
  }

  private static String dueIdOf(UUID order) {
    return scalar(
        "SELECT id FROM payment.card_refund_dues WHERE tenant_id = '"
            + BIZ
            + "' AND order_id = '"
            + order
            + "'");
  }

  private static String closuresOf(String due) {
    return scalar(
        "SELECT count(*) || '/' || coalesce(string_agg(method, ','), '-')"
            + " FROM payment.card_refund_due_closures WHERE tenant_id = '"
            + BIZ
            + "' AND due_id = '"
            + due
            + "'");
  }

  private Answer sale(Caller who, UUID terminal, UUID order, String amount, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/terminal",
        who,
        "{\"terminalId\":\""
            + terminal
            + "\",\"orderId\":\""
            + order
            + "\",\"amount\":\""
            + amount
            + "\",\"currency\":\"GBP\"}",
        key);
  }

  /** Takes a card that the simulator approves, and returns the attempt. */
  private JsonObject approved(UUID terminal, UUID order, String amount) {
    Answer a = sale(cashier, terminal, order, amount, Ids.newId().toString());
    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(a.data().getString("state"), is("APPROVED"));
    return a.data();
  }

  /**
   * The owner lets {@code store} record a card taken on a standalone machine, as the owner does.
   */
  private void allowStandalone(UUID store) {
    Answer a =
        ItCalls.call(
            target,
            "PUT",
            "/admin/payments/stores/" + store + "/standalone-card",
            owner,
            "{\"allowed\":true}",
            null);
    assertThat(a.body().toString(), a.status(), is(200));
  }

  /** As {@link #record}, a typed card carrying the machine's receipt reference. */
  private Answer recordTyped(Caller who, UUID order, String amount, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments",
        who,
        "{\"orderId\":\""
            + order
            + "\",\"amount\":"
            + amount
            + ",\"method\":\"CARD\",\"reference\":\"AUTH 4821\"}",
        key);
  }

  private Answer record(
      Caller who, UUID order, String amount, String method, String attemptId, String key) {
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
            + (attemptId == null ? "" : ",\"terminalPaymentId\":\"" + attemptId + "\"")
            + "}",
        key);
  }

  private Answer settle(Caller who, String attempt, String outcome, String reason, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/terminal/" + attempt + "/settle",
        who,
        "{\"outcome\":"
            + (outcome == null ? "null" : "\"" + outcome + "\"")
            + ",\"reason\":"
            + (reason == null ? "null" : "\"" + reason + "\"")
            + "}",
        key);
  }

  private Answer refund(Caller who, String attempt, String amount, String reason, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/terminal/" + attempt + "/refunds",
        who,
        "{\"amount\":\""
            + amount
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}",
        key);
  }

  private Answer unsettled(Caller who, UUID terminal) {
    return ItCalls.get(target, "/payments/terminal/unsettled?terminalId=" + terminal, who);
  }

  private static String scalar(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  private static String attemptsOn(UUID terminal) {
    return scalar(
        "SELECT count(*) FROM payment.terminal_payments WHERE tenant_id = '"
            + BIZ
            + "' AND terminal_id = '"
            + terminal
            + "'");
  }

  private static String refundsOf(UUID order) {
    return scalar(
        "SELECT count(*) || '/' || coalesce(sum(amount), 0)::numeric(10,2) || '/'"
            + " || coalesce(string_agg(DISTINCT method, ','), '-')"
            + " FROM payment.refund_tenders WHERE tenant_id = '"
            + BIZ
            + "' AND order_id = '"
            + order
            + "'");
  }

  private static String announcedRefunds(UUID order) {
    return scalar(
        "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
            + BIZ
            + "' AND event_type = 'PaymentRefunded' AND payload LIKE '%\"orderId\":\""
            + order
            + "\"%'");
  }

  private static String cardRefundsOf(String saleAttempt) {
    return scalar(
        "SELECT count(*) || '/' || coalesce(string_agg(state, ','), '-')"
            + " FROM payment.terminal_payments WHERE tenant_id = '"
            + BIZ
            + "' AND kind = 'REFUND' AND refund_of = '"
            + saleAttempt
            + "'");
  }

  private static String dueOf(UUID order) {
    return scalar(
        "SELECT coalesce(string_agg(state || '/' || amount::numeric(10,2) || '/'"
            + " || coalesce(payment_id::text, 'unrecorded'), ','), 'none')"
            + " FROM payment.card_refund_dues WHERE tenant_id = '"
            + BIZ
            + "' AND order_id = '"
            + order
            + "'");
  }

  private static String decisions(String attempt) {
    return scalar(
        "SELECT count(*) FROM payment.terminal_attempt_decisions WHERE attempt_id = '"
            + attempt
            + "'");
  }

  private static String details(Answer a) {
    return String.valueOf(a.body().get("details"));
  }

  // ── the guard ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "An approval nobody recorded holds its machine: a new press is refused with what holds it, a"
          + " replay is answered, and recording it on its order frees the machine")
  void anUnrecordedApprovalHoldsTheMachine() {
    Terminals.Terminal t = machine();
    UUID order = Ids.newId();
    String key = Ids.newId().toString();
    Answer first = sale(cashier, t.id(), order, "12.50", key);
    assertThat(first.body().toString(), first.status(), is(201));
    String attempt = first.data().getString("id");
    assertThat(first.data().getString("standing"), is("APPROVED_UNRECORDED"));

    Answer next = sale(cashier, t.id(), Ids.newId(), "7.00", Ids.newId().toString());
    assertThat(next.body().toString(), next.status(), is(409));
    assertThat(next.code(), is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat(details(next), containsString("attemptId=" + attempt));
    assertThat(details(next), containsString("orderId=" + order));
    assertThat(details(next), containsString("amount=12.50;currency=GBP;onCard=12.50"));
    assertThat(details(next), containsString("standing=APPROVED_UNRECORDED"));
    // The same sale under a new key is a second payment too.
    Answer sameSale = sale(cashier, t.id(), order, "12.50", Ids.newId().toString());
    assertThat(sameSale.status(), is(409));
    assertThat(sameSale.code(), is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat("nothing reached the machine", attemptsOn(t.id()), is("1"));

    // A replay under the first key is answered as before: the same attempt, the same approval.
    Answer replay = sale(cashier, t.id(), order, "12.50", key);
    assertThat(replay.body().toString(), replay.status(), is(201));
    assertThat(replay.data().getString("id"), is(attempt));
    assertThat(replay.data().getString("authCode"), is(first.data().getString("authCode")));

    Answer held = unsettled(cashier, t.id());
    assertThat(held.status(), is(200));
    assertThat(held.list(), hasSize(1));

    // The till records it on its order, naming it: the machine is free.
    Answer tender = record(cashier, order, "12.50", "CARD", attempt, Ids.newId().toString());
    assertThat(tender.body().toString(), tender.status(), is(201));
    assertThat(
        scalar("SELECT payment_id FROM payment.terminal_payments WHERE id = '" + attempt + "'"),
        is(tender.data().getString("id")));
    // recorded from the machine's approval, so it needs no reference and is entered as a terminal's
    assertThat(
        scalar(
            "SELECT entry_mode FROM payment.payment_tenders WHERE id = '"
                + tender.data().getString("id")
                + "'"),
        is("TERMINAL"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
    Answer after = sale(cashier, t.id(), Ids.newId(), "7.00", Ids.newId().toString());
    assertThat(after.body().toString(), after.status(), is(201));
  }

  @Test
  @DisplayName("Two first presses on one machine at once: one reaches the machine, the rest wait")
  void twoFirstPressesCannotBothPass() throws Exception {
    Terminals.Terminal t = machine();
    List<Object> results =
        Concurrency.inParallel(
            8,
            () -> {
              try {
                return terminals
                    .sale(
                        BIZ,
                        t.id(),
                        Ids.newId(),
                        new BigDecimal("4.00"),
                        "GBP",
                        actor,
                        Ids.newId().toString())
                    .id();
              } catch (ApiException e) {
                return e.code();
              }
            });
    assertThat(results.stream().filter(r -> r instanceof UUID).count(), is(1L));
    assertThat(
        results.stream().filter(r -> !(r instanceof UUID)).toList(),
        everyItem(is((Object) "TERMINAL_UNSETTLED_APPROVAL")));
    assertThat("one payment on the card", attemptsOn(t.id()), is("1"));
  }

  @Test
  @DisplayName(
      "A timeout holds its machine until a manager at its store says what it shows, once, with a"
          + " reason and a key")
  void aTimeoutWaitsForAPerson() {
    Terminals.Terminal t = machine();
    Answer x = sale(cashier, t.id(), Ids.newId(), "9.03", Ids.newId().toString());
    assertThat(x.data().getString("state"), is("TIMED_OUT"));
    assertThat(x.data().getString("standing"), is("UNDECIDED"));
    String attempt = x.data().getString("id");
    Answer next = sale(cashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString());
    assertThat(next.code(), is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat(details(next), containsString("standing=UNDECIDED"));

    String key = Ids.newId().toString();
    assertThat(settle(cashier, attempt, "NOT_TAKEN", "looked", key).status(), is(403));
    Answer noKey = settle(manager, attempt, "NOT_TAKEN", "looked", null);
    assertThat(noKey.status(), is(400));
    assertThat(noKey.code(), is("IDEMPOTENCY_KEY_REQUIRED"));
    for (String bad : new String[] {null, "", "x".repeat(501)}) {
      Answer refused = settle(manager, attempt, "NOT_TAKEN", bad, key);
      assertThat(String.valueOf(bad).length() + " " + refused.body(), refused.status(), is(400));
      assertThat(refused.code(), is("VALIDATION_FAILED"));
    }
    Answer maybe = settle(manager, attempt, "MAYBE", "looked", key);
    assertThat(maybe.status(), is(400));
    assertThat(maybe.code(), is("TERMINAL_OUTCOME_INVALID"));
    // A manager held to another store, and another business, decide nothing here.
    Answer notHers = settle(elsewhere, attempt, "NOT_TAKEN", "not my store", key);
    assertThat(notHers.status(), is(403));
    assertThat(notHers.code(), is("STORE_ACCESS_DENIED"));
    for (Caller rival : rivals()) {
      Answer theirs = settle(rival, attempt, "NOT_TAKEN", "not ours", Ids.newId().toString());
      assertThat(rival.roles(), theirs.status(), is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat("nothing recorded", decisions(attempt), is("0"));

    Answer ok = settle(manager, attempt, "NOT_TAKEN", "the machine shows no transaction", key);
    assertThat(ok.body().toString(), ok.status(), is(200));
    assertThat(ok.data().getString("standing"), is("SETTLED"));
    assertThat(ok.data().getJsonObject("decision").getString("outcome"), is("NOT_TAKEN"));
    assertThat(
        ok.data().getJsonObject("decision").getString("decidedBy"),
        is(manager.userId().toString()));
    Answer again = settle(manager, attempt, "NOT_TAKEN", "the machine shows no transaction", key);
    assertThat("a replay answers with the first", again.status(), is(200));
    assertThat(
        again.data().getJsonObject("decision").getString("decidedAt"),
        is(ok.data().getJsonObject("decision").getString("decidedAt")));
    Answer second = settle(owner, attempt, "APPROVED", "a second look", Ids.newId().toString());
    assertThat(second.status(), is(409));
    assertThat(second.code(), is("TERMINAL_ATTEMPT_ALREADY_DECIDED"));
    assertThat(
        scalar(
            "SELECT outcome || '/' || reason || '/' || decided_by FROM"
                + " payment.terminal_attempt_decisions WHERE attempt_id = '"
                + attempt
                + "'"),
        is("NOT_TAKEN/the machine shows no transaction/" + manager.userId()));

    assertThat(
        sale(cashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString()).status(), is(201));
    // One the machine answered is not a person's to decide.
    String answered = unsettledFirstApproval(t);
    Answer notTimedOut = settle(manager, answered, "NOT_TAKEN", "looked", Ids.newId().toString());
    assertThat(notTimedOut.status(), is(409));
    assertThat(notTimedOut.code(), is("TERMINAL_NOT_TIMED_OUT"));
  }

  private String unsettledFirstApproval(Terminals.Terminal t) {
    return unsettled(cashier, t.id()).list().getJsonObject(0).getString("id");
  }

  @Test
  @DisplayName(
      "A timeout seen approved is an approval: it holds the machine until it is put back, with a"
          + " reason, by a manager at its store")
  void aTimeoutSeenApprovedIsPutBack() {
    Terminals.Terminal t = machine();
    String attempt =
        sale(cashier, t.id(), Ids.newId(), "9.03", Ids.newId().toString()).data().getString("id");
    Answer seen =
        settle(manager, attempt, "APPROVED", "the machine printed an approval slip", null);
    assertThat(seen.code(), is("IDEMPOTENCY_KEY_REQUIRED"));
    seen =
        settle(
            manager,
            attempt,
            "APPROVED",
            "the machine printed an approval slip",
            Ids.newId().toString());
    assertThat(seen.body().toString(), seen.status(), is(200));
    assertThat(seen.data().getString("standing"), is("APPROVED_UNRECORDED"));
    assertThat(
        sale(cashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString()).code(),
        is("TERMINAL_UNSETTLED_APPROVAL"));

    // The reason is required and held to its limit; nothing reaches the machine without one.
    for (String bad : new String[] {null, "", "x".repeat(501)}) {
      Answer refused = refund(manager, attempt, "9.03", bad, Ids.newId().toString());
      assertThat(refused.body().toString(), refused.status(), is(400));
      assertThat(refused.code(), is("VALIDATION_FAILED"));
    }
    assertThat(
        refund(cashier, attempt, "9.03", "not mine to give", Ids.newId().toString()).status(),
        is(403));
    Answer notHers = refund(elsewhere, attempt, "9.03", "not my store", Ids.newId().toString());
    assertThat(notHers.status(), is(403));
    assertThat(notHers.code(), is("STORE_ACCESS_DENIED"));
    for (Caller rival : rivals()) {
      Answer theirs = refund(rival, attempt, "9.03", "not ours", Ids.newId().toString());
      assertThat(rival.roles(), theirs.status(), is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat("nothing went back", cardRefundsOf(attempt), is("0/-"));

    String key = Ids.newId().toString();
    Answer back = refund(manager, attempt, "9.03", "the customer paid in cash instead", key);
    assertThat(back.body().toString(), back.status(), is(201));
    assertThat(back.data().getString("state"), is("APPROVED"));
    assertThat(back.data().getString("reason"), is("the customer paid in cash instead"));
    assertThat(back.data().getString("requestedBy"), is(manager.userId().toString()));
    assertThat(
        scalar(
            "SELECT reason || '/' || requested_by FROM payment.terminal_payments WHERE id = '"
                + back.data().getString("id")
                + "'"),
        is("the customer paid in cash instead/" + manager.userId()));
    assertThat(
        "a replay is the same refund",
        refund(manager, attempt, "9.03", "the customer paid in cash instead", key)
            .data()
            .getString("id"),
        is(back.data().getString("id")));
    Answer more = refund(manager, attempt, "0.50", "again", Ids.newId().toString());
    assertThat(more.status(), is(409));
    assertThat(more.code(), is("TERMINAL_REFUND_TOO_LARGE"));

    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
    assertThat(
        sale(cashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString()).status(), is(201));
  }

  @Test
  @DisplayName(
      "Another business, and staff held to another store, take nothing on our machine and read"
          + " nothing of it")
  void ourMachineIsOurs() {
    Terminals.Terminal t = machine();
    for (Caller rival : rivals()) {
      Answer theirs = sale(rival, t.id(), Ids.newId(), "5.00", Ids.newId().toString());
      assertThat(rival.roles(), theirs.status(), is(404));
      assertThat(theirs.code(), is("TERMINAL_NOT_FOUND"));
      Answer read = unsettled(rival, t.id());
      assertThat(read.status(), is(404));
    }
    Answer wrongStore = sale(elsewhereCashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString());
    assertThat(wrongStore.status(), is(403));
    assertThat(wrongStore.code(), is("STORE_ACCESS_DENIED"));
    assertThat(unsettled(elsewhereCashier, t.id()).code(), is("STORE_ACCESS_DENIED"));
    assertThat("nothing reached the machine", attemptsOn(t.id()), is("0"));
  }

  // ── recording an approval as its tender ─────────────────────────────────────

  @Test
  @DisplayName("A tender records only its own approval, once, at exactly what the machine took")
  void aTenderRecordsItsOwnApproval() {
    UUID order = Ids.newId();
    String attempt = approved(machine().id(), order, "10.00").getString("id");

    Answer other = record(cashier, Ids.newId(), "10.00", "CARD", attempt, Ids.newId().toString());
    assertThat(other.status(), is(409));
    assertThat(other.code(), is("TERMINAL_ATTEMPT_OTHER_ORDER"));
    Answer less = record(cashier, order, "9.99", "CARD", attempt, Ids.newId().toString());
    assertThat(less.status(), is(409));
    assertThat(less.code(), is("TERMINAL_AMOUNT_MISMATCH"));
    Answer cash = record(cashier, order, "10.00", "CASH", attempt, Ids.newId().toString());
    assertThat(cash.status(), is(400));
    assertThat(cash.code(), is("PAYMENT_INVALID_METHOD"));
    for (Caller rival : rivals()) {
      Answer theirs = record(rival, order, "10.00", "CARD", attempt, Ids.newId().toString());
      assertThat(rival.roles(), theirs.status(), is(404));
      assertThat(theirs.code(), is("TERMINAL_ATTEMPT_NOT_FOUND"));
    }
    // Naming our store as well finds nothing more: the attempt is read in the caller's business.
    Answer ourStore =
        ItCalls.call(
            target,
            "POST",
            "/payments",
            new Caller(RIVAL, Ids.newId(), "OWNER"),
            "{\"orderId\":\""
                + order
                + "\",\"amount\":10.00,\"method\":\"CARD\",\"storeId\":\""
                + HERE
                + "\",\"terminalPaymentId\":\""
                + attempt
                + "\"}",
            Ids.newId().toString());
    assertThat(ourStore.body().toString(), ourStore.status(), is(404));
    assertThat(ourStore.code(), is("TERMINAL_ATTEMPT_NOT_FOUND"));
    assertThat(
        "no tender written in either business",
        scalar("SELECT count(*) FROM payment.payment_tenders WHERE order_id = '" + order + "'"),
        is("0"));
    Answer wrongStore =
        record(elsewhereCashier, order, "10.00", "CARD", attempt, Ids.newId().toString());
    assertThat(wrongStore.status(), is(403));
    assertThat(wrongStore.code(), is("STORE_ACCESS_DENIED"));
    String declined =
        sale(cashier, machine().id(), order, "9.01", Ids.newId().toString()).data().getString("id");
    Answer nothing = record(cashier, order, "9.01", "CARD", declined, Ids.newId().toString());
    assertThat(nothing.status(), is(409));
    assertThat(nothing.code(), is("TERMINAL_NOT_APPROVED"));
    assertThat(
        "no tender written",
        scalar(
            "SELECT count(*) FROM payment.payment_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is("0"));

    assertThat(
        record(cashier, order, "10.00", "CARD", attempt, Ids.newId().toString()).status(), is(201));
    Answer twice = record(cashier, order, "10.00", "CARD", attempt, Ids.newId().toString());
    assertThat(twice.status(), is(409));
    assertThat(twice.code(), is("TERMINAL_ATTEMPT_ALREADY_RECORDED"));
  }

  @Test
  @DisplayName(
      "A CARD tender that names no attempt records the approval on its order at its amount, so a"
          + " till that does not name it still frees its machine")
  void aTenderNamingNoAttemptRecordsTheMatchingApproval() {
    Terminals.Terminal t = machine();
    UUID order = Ids.newId();
    String attempt = approved(t.id(), order, "15.00").getString("id");
    // Where the store has a machine a typed card is refused, unless the owner has allowed a
    // standalone one; then it is typed with a reference, and the approval is still found.
    Answer refused = record(cashier, order, "15.00", "CARD", null, Ids.newId().toString());
    assertThat(refused.code(), is("PAYMENT_CARD_NEEDS_TERMINAL"));
    allowStandalone(HERE);
    Answer tender = recordTyped(cashier, order, "15.00", Ids.newId().toString());
    assertThat(tender.body().toString(), tender.status(), is(201));
    assertThat(
        scalar("SELECT payment_id FROM payment.terminal_payments WHERE id = '" + attempt + "'"),
        is(tender.data().getString("id")));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
  }

  // ── money owed back to a card ───────────────────────────────────────────────

  @Test
  @DisplayName(
      "A cancelled order puts a card a machine took back through that machine, once — never a"
          + " refund in the books alone")
  void aCancelledOrderPutsTheCardBack() {
    UUID order = Ids.newId();
    String attempt = approved(machine().id(), order, "20.00").getString("id");
    Answer tender = record(cashier, order, "20.00", "CARD", attempt, Ids.newId().toString());
    String tenderId = tender.data().getString("id");

    // Nor may a manager write it back in the books alone.
    Answer bookOnly =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + order + "/refunds",
            manager,
            "{\"paymentId\":\""
                + tenderId
                + "\",\"amount\":5.00,\"method\":\"CARD\",\"reason\":\"price match\"}",
            Ids.newId().toString());
    assertThat(bookOnly.body().toString(), bookOnly.status(), is(409));
    assertThat(bookOnly.code(), is("PAYMENT_REFUND_VIA_TERMINAL"));
    assertThat(details(bookOnly), containsString("attemptId=" + attempt));
    assertThat(refundsOf(order), is("0/0.00/-"));

    UUID event = Ids.newId();
    payments.refundForOrderEvent(event, CONSUMER, BIZ, order, null, "Order cancelled");

    assertThat("put back on the card that paid", cardRefundsOf(attempt), is("1/APPROVED"));
    assertThat(dueOf(order), is("REFUNDED/20.00/" + tenderId));
    assertThat("and only then in the books", refundsOf(order), is("1/20.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));

    // Once: the same event again, and another cancellation, move nothing more.
    payments.refundForOrderEvent(event, CONSUMER, BIZ, order, null, "Order cancelled");
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");
    assertThat(cardRefundsOf(attempt), is("1/APPROVED"));
    assertThat(refundsOf(order), is("1/20.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));
  }

  @Test
  @DisplayName("A voided sale gives a card back the same way, and an approval never recorded too")
  void aVoidedSaleAndAnUnrecordedApproval() {
    Terminals.Terminal t = machine();
    UUID order = Ids.newId();
    String attempt = approved(t.id(), order, "8.00").getString("id");
    // Never recorded: nothing in the books, all of it on the card, and the machine held.
    assertThat(unsettled(cashier, t.id()).list(), hasSize(1));

    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Sale voided");

    assertThat(cardRefundsOf(attempt), is("1/APPROVED"));
    assertThat(dueOf(order), is("REFUNDED/8.00/unrecorded"));
    assertThat("nothing was in the books to reverse", refundsOf(order), is("0/0.00/-"));
    assertThat(announcedRefunds(order), is("0"));
    assertThat("and the machine is free", unsettled(cashier, t.id()).list(), hasSize(0));
  }

  @Test
  @DisplayName("A return to the original card goes back through the machine, for what came back")
  void aReturnToTheCard() {
    UUID order = Ids.newId();
    String attempt = approved(machine().id(), order, "30.00").getString("id");
    String tenderId =
        record(cashier, order, "30.00", "CARD", attempt, Ids.newId().toString())
            .data()
            .getString("id");
    UUID returnId = Ids.newId();

    payments.refundReturnForOrderEvent(
        Ids.newId(),
        CONSUMER,
        BIZ,
        order,
        new BigDecimal("12.00"),
        "Return refund",
        new PaymentService.ReturnRefund("ORIGINAL", returnId, null, "GBP"));

    assertThat(cardRefundsOf(attempt), is("1/APPROVED"));
    assertThat(dueOf(order), is("REFUNDED/12.00/" + tenderId));
    assertThat(refundsOf(order), is("1/12.00/CARD"));
    assertThat(
        scalar(
            "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentRefunded' AND payload"
                + " LIKE '%\"refundMethod\":\"ORIGINAL\"%' AND payload LIKE '%\"returnId\":\""
                + returnId
                + "\"%'"),
        is("1"));
  }

  @Test
  @DisplayName(
      "A retired machine with no other of its vendor at its store leaves the money owed and flagged"
          + " for a manager there, never refunded in the books alone — until a manager says how it"
          + " was given back another way: once, in the books with it, by nobody else")
  void anUnreachableMachineLeavesItOwed() {
    UUID lone = LONE.get(0);
    Caller manager = managerAt(lone);
    Terminals.Terminal t = machineAt(lone);
    UUID order = Ids.newId();
    String[] paid = recordedAt(t, order, "16.00");
    String attempt = paid[0];
    String tenderId = paid[1];
    terminals.retire(BIZ, t.id(), "stolen");

    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");

    assertThat(dueOf(order), is("NEEDS_ATTENTION/16.00/" + tenderId));
    assertThat(
        scalar("SELECT attention FROM payment.card_refund_dues WHERE order_id = '" + order + "'"),
        containsString("TERMINAL_RETIRED"));
    assertThat("nothing went back", cardRefundsOf(attempt), is("0/-"));
    assertThat("and nothing in the books says it did", refundsOf(order), is("0/0.00/-"));
    assertThat(announcedRefunds(order), is("0"));
    // What is owed stays spoken for: the books cannot give it back another way behind the due.
    String cashBody =
        "{\"paymentId\":\""
            + tenderId
            + "\",\"amount\":16.00,\"method\":\"CASH\",\"reason\":\"cash instead\"}";
    Answer cash =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + order + "/refunds",
            manager,
            cashBody,
            Ids.newId().toString());
    assertThat(cash.status(), is(409));
    assertThat(cash.code(), is("REFUND_EXCEEDS_PAYMENT"));

    String due = dueIdOf(order);
    Answer list =
        ItCalls.get(target, "/payments/terminal/refund-dues?state=NEEDS_ATTENTION", manager);
    assertThat(list.status(), is(200));
    assertThat(list.list().toString(), containsString(due));
    assertThat(
        "a manager held to another store does not see it",
        ItCalls.get(target, "/payments/terminal/refund-dues", elsewhere).list().toString(),
        not(containsString(due)));
    Answer notHers = retryDue(elsewhere, due);
    assertThat(notHers.status(), is(403));
    assertThat(notHers.code(), is("STORE_ACCESS_DENIED"));
    for (Caller rival : rivals()) {
      if (rival.roles().equals("CASHIER")) continue;
      Answer theirs = ItCalls.get(target, "/payments/terminal/refund-dues/" + due, rival);
      assertThat(rival.roles(), theirs.status(), is(404));
      assertThat(theirs.code(), is("CARD_REFUND_DUE_NOT_FOUND"));
      assertThat(
          ItCalls.get(target, "/payments/terminal/refund-dues", rival).list().toString(),
          not(containsString(due)));
    }
    Answer namingOurStore =
        ItCalls.get(
            target,
            "/payments/terminal/refund-dues?storeId=" + lone,
            new Caller(RIVAL, Ids.newId(), "OWNER"));
    assertThat(namingOurStore.status(), is(200));
    assertThat(
        "another business naming our store reads none of it", namingOurStore.list(), hasSize(0));
    Answer noKey =
        ItCalls.call(
            target,
            "POST",
            "/payments/terminal/refund-dues/" + due + "/retry",
            manager,
            "{}",
            null);
    assertThat(noKey.code(), is("IDEMPOTENCY_KEY_REQUIRED"));
    Answer retired = retryDue(manager, due);
    assertThat(retired.status(), is(409));
    assertThat(retired.code(), is("TERMINAL_RETIRED"));
    assertThat(details(retired), containsString("terminalId=" + t.id() + ";vendor=SIMULATED"));
    assertThat(dueOf(order), is("NEEDS_ATTENTION/16.00/" + tenderId));

    // No machine can put it back: a manager at the store says how it was given back instead.
    for (Caller rival : rivals()) {
      Answer theirs = anotherWay(rival, due, "CASH", null, "not ours", Ids.newId().toString());
      assertThat(
          rival.roles() + " " + theirs.body(),
          theirs.status(),
          is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    Answer rivalNamingOurStore =
        anotherWay(
            new Caller(RIVAL, Ids.newId(), "MANAGER", lone),
            due,
            "CASH",
            null,
            "naming your store",
            Ids.newId().toString());
    assertThat(rivalNamingOurStore.status(), is(404));
    assertThat(rivalNamingOurStore.code(), is("CARD_REFUND_DUE_NOT_FOUND"));
    Answer heldElsewhere =
        anotherWay(elsewhere, due, "CASH", null, "not my store", Ids.newId().toString());
    assertThat(heldElsewhere.status(), is(403));
    assertThat(heldElsewhere.code(), is("STORE_ACCESS_DENIED"));
    Answer aCashier =
        anotherWay(cashierAt(lone), due, "CASH", null, "mine to give", Ids.newId().toString());
    assertThat(aCashier.status(), is(403));
    Answer keyless = anotherWay(manager, due, "CASH", null, "the machine is gone", null);
    assertThat(keyless.status(), is(400));
    assertThat(keyless.code(), is("IDEMPOTENCY_KEY_REQUIRED"));
    Answer noReason = anotherWay(manager, due, "CASH", null, null, Ids.newId().toString());
    assertThat(noReason.status(), is(400));
    assertThat(noReason.code(), is("VALIDATION_FAILED"));
    Answer issued =
        anotherWay(manager, due, "GIFT_CARD", null, "a gift card", Ids.newId().toString());
    assertThat(issued.status(), is(400));
    assertThat(issued.code(), is("CARD_REFUND_METHOD_INVALID"));
    Answer noReference =
        anotherWay(manager, due, "CARD", null, "the acquirer did it", Ids.newId().toString());
    assertThat(noReference.status(), is(400));
    assertThat(noReference.code(), is("CARD_REFUND_REFERENCE_REQUIRED"));
    assertThat("nothing moved", dueOf(order), is("NEEDS_ATTENTION/16.00/" + tenderId));
    assertThat(closuresOf(due), is("0/-"));
    assertThat(refundsOf(order), is("0/0.00/-"));

    String key = Ids.newId().toString();
    Answer given = anotherWay(manager, due, "cash", null, "the machine was stolen", key);
    assertThat(given.body().toString(), given.status(), is(200));
    assertThat(given.data().getString("state"), is("REFUNDED_ANOTHER_WAY"));
    assertThat(given.data().getJsonObject("anotherWay").getString("method"), is("CASH"));
    assertThat(
        given.data().getJsonObject("anotherWay").getString("closedBy"),
        is(manager.userId().toString()));
    assertThat(dueOf(order), is("REFUNDED_ANOTHER_WAY/16.00/" + tenderId));
    assertThat(closuresOf(due), is("1/CASH"));
    assertThat("in the books with it, as cash", refundsOf(order), is("1/16.00/CASH"));
    assertThat(announcedRefunds(order), is("1"));
    assertThat(
        "the cash left the store the sale was made at",
        scalar(
            "SELECT store_id FROM payment.refund_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is(lone.toString()));
    assertThat(
        "the due names the books' refund",
        scalar("SELECT refund_id FROM payment.card_refund_dues WHERE id = '" + due + "'"),
        is(
            scalar(
                "SELECT id FROM payment.refund_tenders WHERE tenant_id = '"
                    + BIZ
                    + "' AND order_id = '"
                    + order
                    + "'")));
    assertThat("no machine was asked", cardRefundsOf(attempt), is("0/-"));

    // Once: the same key answers with the first, and nothing gives it back again.
    Answer replay = anotherWay(manager, due, "cash", null, "the machine was stolen", key);
    assertThat(replay.body().toString(), replay.status(), is(200));
    assertThat(replay.data().getString("state"), is("REFUNDED_ANOTHER_WAY"));
    Answer twice = anotherWay(manager, due, "CASH", null, "and once more", Ids.newId().toString());
    assertThat(twice.status(), is(409));
    assertThat(twice.code(), is("CARD_REFUND_DUE_SETTLED"));
    Answer askedAfter = retryDue(manager, due);
    assertThat(askedAfter.status(), is(409));
    assertThat(askedAfter.code(), is("CARD_REFUND_DUE_SETTLED"));
    Answer moreCash =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + order + "/refunds",
            manager,
            cashBody,
            Ids.newId().toString());
    assertThat("the tender is all given back", moreCash.code(), is("REFUND_EXCEEDS_PAYMENT"));
    assertThat(refundsOf(order), is("1/16.00/CASH"));
    assertThat(closuresOf(due), is("1/CASH"));
    assertThat(announcedRefunds(order), is("1"));

    Answer read = ItCalls.get(target, "/payments/terminal/refund-dues/" + due, manager);
    assertThat(read.status(), is(200));
    assertThat(
        read.data().getJsonObject("anotherWay").getString("reason"), is("the machine was stolen"));
    assertThat(
        ItCalls.get(target, "/payments/terminal/refund-dues?state=REFUNDED_ANOTHER_WAY", manager)
            .list()
            .toString(),
        containsString(due));
    // A key used for this due closes no other.
    UUID other = Ids.newId();
    Terminals.Terminal t2 = machineAt(lone);
    recordedAt(t2, other, "9.00");
    terminals.retire(BIZ, t2.id(), "swapped");
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, other, null, "Order cancelled");
    Answer reused = anotherWay(manager, dueIdOf(other), "CASH", null, "same key", key);
    assertThat(reused.status(), is(409));
    assertThat(reused.code(), is("IDEMPOTENCY_KEY_REUSED"));
    assertThat(dueOf(other), containsString("NEEDS_ATTENTION/9.00/"));
  }

  @Test
  @DisplayName(
      "A card payment given back another way is written as a refund of that CARD tender under the"
          + " way it was given back, whichever of CASH, UPI, WALLET or CARD: the schema's set of"
          + " refund methods holds every one of them")
  void aCardGivenBackAnotherWayIsARefundUnderThatWay() {
    String[] ways = {"UPI", "WALLET", "CARD"};
    for (int i = 0; i < ways.length; i++) {
      String way = ways[i];
      UUID lone = LONE.get(5 + i);
      Caller manager = managerAt(lone);
      Terminals.Terminal t = machineAt(lone);
      UUID order = Ids.newId();
      String tenderId = recordedAt(t, order, "12.00")[1];
      terminals.retire(BIZ, t.id(), "stolen");
      payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");
      String due = dueIdOf(order);
      assertThat(way, dueOf(order), is("NEEDS_ATTENTION/12.00/" + tenderId));

      Answer given =
          anotherWay(
              manager,
              due,
              way,
              "CARD".equals(way) ? "ACQ-" + order : null,
              "given back as " + way,
              Ids.newId().toString());

      assertThat(way + " " + given.body(), given.status(), is(200));
      assertThat(dueOf(order), is("REFUNDED_ANOTHER_WAY/12.00/" + tenderId));
      assertThat(
          "one refund in the books, under the way it went back",
          refundsOf(order),
          is("1/12.00/" + way));
      assertThat(
          "of the card tender, whose own method is CARD",
          scalar(
              "SELECT r.payment_id || '/' || r.method || '/' || t.method FROM payment.refund_tenders"
                  + " r JOIN payment.payment_tenders t ON t.tenant_id = r.tenant_id AND t.id ="
                  + " r.payment_id WHERE r.tenant_id = '"
                  + BIZ
                  + "' AND r.order_id = '"
                  + order
                  + "'"),
          is(tenderId + "/" + way + "/CARD"));
      assertThat(announcedRefunds(order), is("1"));
    }
  }

  @Test
  @DisplayName(
      "A manager's refund of a recorded card payment is the books' refund too, written once the"
          + " machine has put it back, and never more than the tender has left")
  void aManagersRefundOfARecordedCard() {
    UUID order = Ids.newId();
    String attempt = approved(machine().id(), order, "30.00").getString("id");
    String tenderId =
        record(cashier, order, "30.00", "CARD", attempt, Ids.newId().toString())
            .data()
            .getString("id");
    String key = Ids.newId().toString();
    Answer back = refund(manager, attempt, "10.00", "price match", key);
    assertThat(back.body().toString(), back.status(), is(201));
    assertThat(back.data().getString("state"), is("APPROVED"));
    assertThat(dueOf(order), is("REFUNDED/10.00/" + tenderId));
    assertThat(refundsOf(order), is("1/10.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));

    Answer replay = refund(manager, attempt, "10.00", "price match", key);
    assertThat(replay.data().getString("id"), is(back.data().getString("id")));
    assertThat(refundsOf(order), is("1/10.00/CARD"));

    Answer tooMuch = refund(manager, attempt, "25.00", "more", Ids.newId().toString());
    assertThat(tooMuch.status(), is(409));
    assertThat(tooMuch.code(), is("TERMINAL_REFUND_TOO_LARGE"));
    assertThat(tooMuch.body().getString("detail"), containsString("20.00"));
  }

  // ── a cancel, a late answer, and a request left at the machine ───────────────

  private Terminals.Terminal gatedMachine() {
    return terminals.register(BIZ, HERE, "Gated " + Ids.newId(), GatedTerminal.VENDOR, null, actor);
  }

  /** Presses card on a gated machine; the press waits at the machine until the test answers. */
  private CompletableFuture<Answer> pressAndWait(UUID terminal, UUID order, String amount)
      throws InterruptedException {
    GatedTerminal.arm();
    String key = Ids.newId().toString();
    CompletableFuture<Answer> press =
        CompletableFuture.supplyAsync(() -> sale(cashier, terminal, order, amount, key));
    GatedTerminal.atMachine();
    return press;
  }

  private static String atMachineAttempt(UUID terminal) {
    return scalar(
        "SELECT id FROM payment.terminal_payments WHERE tenant_id = '"
            + BIZ
            + "' AND terminal_id = '"
            + terminal
            + "' AND state = 'REQUESTED'");
  }

  /**
   * A card request the machine never answered because the call that asked it is gone (a crash, a
   * deploy): the row as the claim wrote it, {@code ago} before now.
   */
  private String leftAtMachine(Terminals.Terminal t, UUID order, String amount, String ago) {
    UUID id = Ids.newId();
    Envelopes.exec(
        PG,
        "INSERT INTO payment.terminal_payments (id, tenant_id, store_id, terminal_id, order_id,"
            + " amount, currency, kind, state, requested_at, requested_by) VALUES ('"
            + id
            + "', '"
            + BIZ
            + "', '"
            + t.storeId()
            + "', '"
            + t.id()
            + "', '"
            + order
            + "', "
            + amount
            + ", 'GBP', 'SALE', 'REQUESTED', now() - interval '"
            + ago
            + "', '"
            + actor
            + "')");
    return id.toString();
  }

  private Answer cancel(Caller who, String attempt) {
    return ItCalls.call(
        target, "POST", "/payments/terminal/" + attempt + "/cancel", who, "{}", null);
  }

  private static String tendersOn(UUID order) {
    return scalar(
        "SELECT count(*) FROM payment.payment_tenders WHERE tenant_id = '"
            + BIZ
            + "' AND order_id = '"
            + order
            + "'");
  }

  @Test
  @DisplayName(
      "A cancel while the cardholder is at the machine never frees it: the machine's own answer"
          + " settles the card, and the approval it gives is kept, held and recorded once")
  void aCancelNeverFreesAMachineStillAnswering() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    int cancelsBefore = GatedTerminal.cancels();
    CompletableFuture<Answer> press = pressAndWait(t.id(), order, "12.50");
    String attempt = atMachineAttempt(t.id());

    Answer cancelled = cancel(cashier, attempt);
    assertThat(cancelled.body().toString(), cancelled.status(), is(200));
    assertThat(
        "a cancel is a question for the machine, not its answer",
        cancelled.data().getString("state"),
        is("REQUESTED"));
    assertThat(cancelled.data().getString("standing"), is("AT_MACHINE"));
    assertThat("the machine was asked to stop", GatedTerminal.cancels(), is(cancelsBefore + 1));
    Answer next = sale(cashier, t.id(), Ids.newId(), "7.00", Ids.newId().toString());
    assertThat(next.status(), is(409));
    assertThat(next.code(), is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat(details(next), containsString("standing=AT_MACHINE"));
    Answer tooSoon =
        settle(manager, attempt, "NOT_TAKEN", "the till says cancelled", Ids.newId().toString());
    assertThat(tooSoon.body().toString(), tooSoon.status(), is(409));
    assertThat(tooSoon.code(), is("TERMINAL_REQUEST_IN_FLIGHT"));
    assertThat(details(tooSoon), containsString("decidableFrom="));
    assertThat(decisions(attempt), is("0"));

    // The cardholder had already finished: the machine approves after the cancel.
    GatedTerminal.answer(GatedTerminal.approval("GATE-" + attempt));
    Answer taken = press.get(30, TimeUnit.SECONDS);
    assertThat(taken.body().toString(), taken.status(), is(201));
    assertThat(taken.data().getString("state"), is("APPROVED"));
    assertThat(taken.data().getString("standing"), is("APPROVED_UNRECORDED"));
    assertThat(
        scalar(
            "SELECT state || '/' || provider_ref || '/' || auth_code FROM"
                + " payment.terminal_payments WHERE id = '"
                + attempt
                + "'"),
        is("APPROVED/GATE-" + attempt + "/GATE01"));
    assertThat("one payment on the card, and it is on the record", attemptsOn(t.id()), is("1"));
    assertThat(
        sale(cashier, t.id(), Ids.newId(), "7.00", Ids.newId().toString()).code(),
        is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat(
        "a second cancel reads the approval",
        cancel(cashier, attempt).data().getString("state"),
        is("APPROVED"));
    assertThat(
        record(cashier, order, "12.50", "CARD", attempt, Ids.newId().toString()).status(), is(201));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
  }

  @Test
  @DisplayName("A cancel the machine honours is its answer: nothing taken, and the machine is free")
  void aCancelTheMachineHonours() throws Exception {
    Terminals.Terminal t = gatedMachine();
    CompletableFuture<Answer> press = pressAndWait(t.id(), Ids.newId(), "6.00");
    String attempt = atMachineAttempt(t.id());
    assertThat(cancel(cashier, attempt).data().getString("state"), is("REQUESTED"));
    GatedTerminal.answer(Terminals.refused(Terminals.CANCELLED, null, "Cancelled at the till"));
    Answer stopped = press.get(30, TimeUnit.SECONDS);
    assertThat(stopped.data().getString("state"), is("CANCELLED"));
    assertThat(stopped.data().getString("standing"), is("SETTLED"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
  }

  @Test
  @DisplayName("A refund on a machine is not cancelled at the pinpad, and its record is untouched")
  void aRefundIsNotCancelled() {
    UUID order = Ids.newId();
    String attempt = approved(machine().id(), order, "10.00").getString("id");
    String refund =
        refund(manager, attempt, "10.00", "changed their mind", Ids.newId().toString())
            .data()
            .getString("id");
    Answer c = cancel(cashier, refund);
    assertThat(c.body().toString(), c.status(), is(409));
    assertThat(c.code(), is("TERMINAL_NOT_A_SALE"));
    assertThat(cardRefundsOf(attempt), is("1/APPROVED"));
  }

  @Test
  @DisplayName(
      "A card request left at the machine holds it; a cancel cannot clear it, and only a manager at"
          + " its store, once the machine has had its time to answer, says what it shows")
  void aRequestLeftAtTheMachine() {
    Terminals.Terminal t = machine();
    String attempt = leftAtMachine(t, Ids.newId(), "11.00", "1 hour");
    assertThat(unsettled(cashier, t.id()).list(), hasSize(1));
    Answer c = cancel(cashier, attempt);
    assertThat(c.status(), is(200));
    assertThat(c.data().getString("state"), is("REQUESTED"));
    assertThat(
        sale(cashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString()).code(),
        is("TERMINAL_UNSETTLED_APPROVAL"));

    String key = Ids.newId().toString();
    assertThat(settle(cashier, attempt, "NOT_TAKEN", "looked", key).status(), is(403));
    Answer notHers = settle(elsewhere, attempt, "NOT_TAKEN", "not my store", key);
    assertThat(notHers.status(), is(403));
    assertThat(notHers.code(), is("STORE_ACCESS_DENIED"));
    for (Caller rival : rivals()) {
      Answer theirs = settle(rival, attempt, "NOT_TAKEN", "not ours", Ids.newId().toString());
      assertThat(rival.roles(), theirs.status(), is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat("nothing recorded, nothing moved", decisions(attempt), is("0"));
    assertThat(
        scalar("SELECT state FROM payment.terminal_payments WHERE id = '" + attempt + "'"),
        is("REQUESTED"));

    Answer ok = settle(manager, attempt, "NOT_TAKEN", "the machine shows no transaction", key);
    assertThat(ok.body().toString(), ok.status(), is(200));
    assertThat("it never answered, so it timed out", ok.data().getString("state"), is("TIMED_OUT"));
    assertThat(ok.data().getString("standing"), is("SETTLED"));
    assertThat(ok.data().getJsonObject("decision").getString("outcome"), is("NOT_TAKEN"));
    assertThat(
        scalar("SELECT outcome_detail FROM payment.terminal_payments WHERE id = '" + attempt + "'"),
        containsString("never answered"));
    assertThat(
        sale(cashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString()).status(), is(201));

    // One asked a moment ago is still the machine's to answer.
    String fresh = leftAtMachine(machine(), Ids.newId(), "11.00", "0 seconds");
    Answer wait = settle(manager, fresh, "NOT_TAKEN", "looked", Ids.newId().toString());
    assertThat(wait.status(), is(409));
    assertThat(wait.code(), is("TERMINAL_REQUEST_IN_FLIGHT"));
    assertThat(decisions(fresh), is("0"));
  }

  @Test
  @DisplayName(
      "A machine that answers after a person settled its card is never dropped: its approval beats"
          + " their 'not taken', is kept with its reference, and holds the machine again")
  void aLateApprovalBeatsAPersonsWord() throws Exception {
    Terminals.Terminal t = gatedMachine();
    CompletableFuture<Answer> press = pressAndWait(t.id(), Ids.newId(), "14.00");
    String attempt = atMachineAttempt(t.id());
    // Silent for longer than a machine may be: a manager looks, and sees nothing.
    Envelopes.exec(
        PG,
        "UPDATE payment.terminal_payments SET requested_at = now() - interval '1 hour' WHERE id ="
            + " '"
            + attempt
            + "'");
    Answer looked =
        settle(manager, attempt, "NOT_TAKEN", "the screen is blank", Ids.newId().toString());
    assertThat(looked.body().toString(), looked.status(), is(200));
    assertThat(looked.data().getString("standing"), is("SETTLED"));

    GatedTerminal.answer(GatedTerminal.approval("GATE-LATE-" + attempt));
    Answer taken = press.get(30, TimeUnit.SECONDS);
    assertThat(taken.body().toString(), taken.status(), is(201));
    assertThat(taken.data().getString("state"), is("APPROVED"));
    assertThat(
        scalar(
            "SELECT state || '/' || provider_ref || '/' || auth_code FROM"
                + " payment.terminal_payments WHERE id = '"
                + attempt
                + "'"),
        is("APPROVED/GATE-LATE-" + attempt + "/GATE01"));
    assertThat("the person's word is kept beside it", decisions(attempt), is("1"));
    assertThat(
        unsettled(cashier, t.id()).list().getJsonObject(0).getString("standing"),
        is("APPROVED_UNRECORDED"));
    assertThat(
        sale(cashier, t.id(), Ids.newId(), "5.00", Ids.newId().toString()).code(),
        is("TERMINAL_UNSETTLED_APPROVAL"));
  }

  // ── an order given up ───────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A timeout on a sale given up, later seen approved, is owed back and put back through its"
          + " machine at once, and never recorded on the given-up sale")
  void anApprovalKnownAfterTheSaleIsGivenUp() {
    Terminals.Terminal t = machine();
    UUID order = Ids.newId();
    String attempt =
        sale(cashier, t.id(), order, "9.03", Ids.newId().toString()).data().getString("id");
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");
    assertThat("nothing is known to be owed yet", dueOf(order), is("none"));
    assertThat(
        scalar(
            "SELECT count(*) FROM payment.given_up_orders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is("1"));
    Answer early = record(cashier, order, "9.03", "CARD", attempt, Ids.newId().toString());
    assertThat(early.body().toString(), early.status(), is(409));
    assertThat(early.code(), is("PAYMENT_ORDER_GIVEN_UP"));

    Answer seen =
        settle(manager, attempt, "APPROVED", "the slip says approved", Ids.newId().toString());
    assertThat(seen.body().toString(), seen.status(), is(200));
    assertThat(
        "owed back and put back, so it holds nothing",
        seen.data().getString("standing"),
        is("SETTLED"));
    assertThat("put back on the card that paid", cardRefundsOf(attempt), is("1/APPROVED"));
    assertThat(dueOf(order), is("REFUNDED/9.03/unrecorded"));
    assertThat("nothing was in the books to reverse", refundsOf(order), is("0/0.00/-"));

    allowStandalone(HERE);
    for (String named : new String[] {attempt, null}) {
      Answer replay =
          named == null
              ? recordTyped(cashier, order, "9.03", Ids.newId().toString())
              : record(cashier, order, "9.03", "CARD", named, Ids.newId().toString());
      assertThat(replay.body().toString(), replay.status(), is(409));
      assertThat(replay.code(), is("PAYMENT_ORDER_GIVEN_UP"));
    }
    assertThat("no tender on the given-up sale", tendersOn(order), is("0"));
    Answer again = sale(cashier, t.id(), order, "9.00", Ids.newId().toString());
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("PAYMENT_ORDER_GIVEN_UP"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
  }

  @Test
  @DisplayName(
      "A sale given up while its card is at the machine: the approval that follows is owed back and"
          + " put back through that machine")
  void aSaleGivenUpWhileTheCardIsAtTheMachine() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    CompletableFuture<Answer> press = pressAndWait(t.id(), order, "18.00");
    String attempt = atMachineAttempt(t.id());
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Sale voided");
    assertThat(dueOf(order), is("none"));

    GatedTerminal.answer(GatedTerminal.approval("GATE-" + attempt));
    Answer taken = press.get(30, TimeUnit.SECONDS);
    assertThat(taken.body().toString(), taken.status(), is(201));
    assertThat(taken.data().getString("state"), is("APPROVED"));
    assertThat(taken.data().getString("standing"), is("SETTLED"));
    assertThat(cardRefundsOf(attempt), is("1/APPROVED"));
    assertThat(dueOf(order), is("REFUNDED/18.00/unrecorded"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
    Answer replay = record(cashier, order, "18.00", "CARD", attempt, Ids.newId().toString());
    assertThat(replay.code(), is("PAYMENT_ORDER_GIVEN_UP"));
    assertThat(tendersOn(order), is("0"));
  }

  @Test
  @DisplayName(
      "On an order known given up, a card tender a machine's payment could stand for is refused,"
          + " named or not")
  void aCardTenderOnAGivenUpOrder() {
    Terminals.Terminal t = machine();
    UUID order = Ids.newId();
    String attempt = approved(t.id(), order, "11.00").getString("id");
    // As payment-svc knows it the instant a give-up commits, before its card is owed back.
    Envelopes.exec(
        PG,
        "INSERT INTO payment.given_up_orders (id, tenant_id, order_id, event_id, reason,"
            + " given_up_at) VALUES ('"
            + Ids.newId()
            + "', '"
            + BIZ
            + "', '"
            + order
            + "', '"
            + Ids.newId()
            + "', 'Order cancelled', now())");
    allowStandalone(HERE);
    for (String named : new String[] {null, attempt}) {
      Answer refused =
          named == null
              ? recordTyped(cashier, order, "11.00", Ids.newId().toString())
              : record(cashier, order, "11.00", "CARD", named, Ids.newId().toString());
      assertThat(refused.body().toString(), refused.status(), is(409));
      assertThat(refused.code(), is("PAYMENT_ORDER_GIVEN_UP"));
      assertThat(details(refused), containsString("orderId=" + order));
    }
    assertThat(tendersOn(order), is("0"));
    assertThat(
        scalar(
            "SELECT CASE WHEN payment_id IS NULL THEN 1 ELSE 0 END"
                + " FROM payment.terminal_payments WHERE id = '"
                + attempt
                + "'"),
        is("1"));
  }

  // ── who may put money back ──────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A manager whose role does not hold sales.refund puts nothing back on a card: not by a refund,"
          + " a person's word, a retry or another way — and holding it, they do")
  void aManagerWithoutTheRefundPermission() {
    String narrowed = "sales.void,till.manage";
    Terminals.Terminal t1 = machine();
    String attempt = approved(t1.id(), Ids.newId(), "10.00").getString("id");
    String refundBody = "{\"amount\":\"10.00\",\"reason\":\"changed their mind\"}";
    Answer r =
        ItCalls.call(
            target,
            "POST",
            "/payments/terminal/" + attempt + "/refunds",
            manager,
            refundBody,
            Ids.newId().toString(),
            narrowed);
    assertThat(r.body().toString(), r.status(), is(403));
    assertThat(r.code(), is("PERMISSION_DENIED"));
    assertThat(r.body().getString("detail"), containsString("sales.refund"));
    assertThat("nothing went back", cardRefundsOf(attempt), is("0/-"));

    String timedOut =
        sale(cashier, machine().id(), Ids.newId(), "9.03", Ids.newId().toString())
            .data()
            .getString("id");
    Answer s =
        ItCalls.call(
            target,
            "POST",
            "/payments/terminal/" + timedOut + "/settle",
            manager,
            "{\"outcome\":\"APPROVED\",\"reason\":\"looked\"}",
            Ids.newId().toString(),
            narrowed);
    assertThat(s.status(), is(403));
    assertThat(s.code(), is("PERMISSION_DENIED"));
    assertThat(decisions(timedOut), is("0"));

    // At a store with no other machine, so the retired one's card has none to go back through.
    UUID lone = LONE.get(1);
    Caller loneManager = managerAt(lone);
    Terminals.Terminal t3 = machineAt(lone);
    UUID order = Ids.newId();
    String[] paid = recordedAt(t3, order, "16.00");
    String a3 = paid[0];
    String tenderId = paid[1];
    terminals.retire(BIZ, t3.id(), "broken");
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");
    String due = dueIdOf(order);
    Answer retry =
        ItCalls.call(
            target,
            "POST",
            "/payments/terminal/refund-dues/" + due + "/retry",
            loneManager,
            "{}",
            Ids.newId().toString(),
            narrowed);
    assertThat(retry.status(), is(403));
    assertThat(retry.code(), is("PERMISSION_DENIED"));
    Answer otherWay =
        ItCalls.call(
            target,
            "POST",
            "/payments/terminal/refund-dues/" + due + "/another-way",
            loneManager,
            "{\"method\":\"CASH\",\"reason\":\"from the drawer\"}",
            Ids.newId().toString(),
            narrowed);
    assertThat(otherWay.status(), is(403));
    assertThat(otherWay.code(), is("PERMISSION_DENIED"));
    assertThat(dueOf(order), is("NEEDS_ATTENTION/16.00/" + tenderId));
    assertThat(cardRefundsOf(a3), is("0/-"));
    assertThat(refundsOf(order), is("0/0.00/-"));
    assertThat(closuresOf(due), is("0/-"));

    Answer held =
        ItCalls.call(
            target,
            "POST",
            "/payments/terminal/" + attempt + "/refunds",
            manager,
            refundBody,
            Ids.newId().toString(),
            narrowed + ",sales.refund");
    assertThat(held.body().toString(), held.status(), is(201));
    assertThat(cardRefundsOf(attempt), is("1/APPROVED"));
  }

  // ── a refund the machine has not put back ───────────────────────────────────

  /** Asks the gated machine for a refund that waits at it until the test answers. */
  private CompletableFuture<Answer> refundAndWait(String saleAttempt, String amount, String key)
      throws InterruptedException {
    GatedTerminal.armRefund();
    CompletableFuture<Answer> asked =
        CompletableFuture.supplyAsync(
            () -> refund(manager, saleAttempt, amount, "the sale changed", key));
    GatedTerminal.atMachine();
    return asked;
  }

  private static String salesOn(UUID terminal) {
    return scalar(
        "SELECT count(*) FROM payment.terminal_payments WHERE tenant_id = '"
            + BIZ
            + "' AND terminal_id = '"
            + terminal
            + "' AND kind = 'SALE'");
  }

  @Test
  @DisplayName(
      "A refund the machine has not put back — still at it, or timed out with nobody's word on it —"
          + " leaves the sale it would reverse holding the machine and holds it itself: the changed"
          + " sale is not charged again until the first card is accounted for")
  void aRefundNotYetBackHoldsTheMachine() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    CompletableFuture<Answer> press = pressAndWait(t.id(), order, "10.00");
    String sale = atMachineAttempt(t.id());
    GatedTerminal.answer(GatedTerminal.approval("GATE-" + sale));
    assertThat(press.get(30, TimeUnit.SECONDS).status(), is(201));

    // The sale changes before the approval is recorded: a manager puts it back, and the refund
    // waits at the machine.
    CompletableFuture<Answer> back = refundAndWait(sale, "10.00", Ids.newId().toString());
    String refundAttempt = atMachineAttempt(t.id());
    Answer whileAtMachine = sale(cashier, t.id(), order, "12.00", Ids.newId().toString());
    assertThat(whileAtMachine.body().toString(), whileAtMachine.status(), is(409));
    assertThat(whileAtMachine.code(), is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat(
        details(whileAtMachine),
        containsString(
            "attemptId="
                + sale
                + ";orderId="
                + order
                + ";amount=10.00;currency=GBP;onCard=10.00;state=APPROVED;standing="
                + "APPROVED_UNRECORDED;kind=SALE"));
    assertThat(
        details(whileAtMachine),
        containsString(
            "attemptId="
                + refundAttempt
                + ";orderId="
                + order
                + ";amount=10.00;currency=GBP;onCard=0.00;state=REQUESTED;standing=AT_MACHINE"
                + ";kind=REFUND;refundOf="
                + sale));

    // The machine never answers the refund.
    GatedTerminal.answer(
        Terminals.refused(Terminals.TIMED_OUT, "GATE-R-TO", "No answer from the terminal"));
    Answer timedOut = back.get(30, TimeUnit.SECONDS);
    assertThat(timedOut.body().toString(), timedOut.status(), is(201));
    assertThat(timedOut.data().getString("state"), is("TIMED_OUT"));
    assertThat(timedOut.data().getString("standing"), is("UNDECIDED"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(2));
    Answer again = sale(cashier, t.id(), order, "12.00", Ids.newId().toString());
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat(details(again), containsString("onCard=10.00;state=APPROVED"));
    assertThat(details(again), containsString("state=TIMED_OUT;standing=UNDECIDED;kind=REFUND"));
    assertThat("the card was charged once", salesOn(t.id()), is("1"));

    // Another business's manager cannot say what our machine shows.
    for (Caller rival : rivals()) {
      Answer theirs = settle(rival, refundAttempt, "NOT_TAKEN", "not ours", Ids.newId().toString());
      assertThat(
          theirs.body().toString(),
          theirs.status(),
          is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat(decisions(refundAttempt), is("0"));

    // A manager looks: the refund did not go through, so the first card is still all on the card.
    Answer notTaken =
        settle(
            manager, refundAttempt, "NOT_TAKEN", "machine shows no refund", Ids.newId().toString());
    assertThat(notTaken.body().toString(), notTaken.status(), is(200));
    Answer held = unsettled(cashier, t.id());
    assertThat(held.list(), hasSize(1));
    assertThat(held.list().getJsonObject(0).getString("id"), is(sale));
    assertThat(
        sale(cashier, t.id(), order, "12.00", Ids.newId().toString()).code(),
        is("TERMINAL_UNSETTLED_APPROVAL"));

    // Asked again, the issuer refuses it: still on the card, still held.
    CompletableFuture<Answer> refused = refundAndWait(sale, "10.00", Ids.newId().toString());
    GatedTerminal.answer(
        Terminals.refused(Terminals.DECLINED, "GATE-R-DEC", "refund refused by the issuer"));
    assertThat(refused.get(30, TimeUnit.SECONDS).data().getString("state"), is("DECLINED"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(1));
    assertThat(salesOn(t.id()), is("1"));

    // Put back for real: the machine is free, and the changed sale is charged once.
    Answer putBack = refund(manager, sale, "10.00", "the sale changed", Ids.newId().toString());
    assertThat(putBack.body().toString(), putBack.status(), is(201));
    assertThat(putBack.data().getString("state"), is("APPROVED"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
    CompletableFuture<Answer> changed = pressAndWait(t.id(), order, "12.00");
    GatedTerminal.answer(GatedTerminal.approval("GATE-CHANGED-" + order));
    assertThat(changed.get(30, TimeUnit.SECONDS).status(), is(201));
    assertThat(salesOn(t.id()), is("2"));
  }

  @Test
  @DisplayName(
      "A refund seen approved by a person settles the sale it reversed: the machine is free once"
          + " every card on it is accounted for")
  void aRefundSeenApprovedFreesTheMachine() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    CompletableFuture<Answer> press = pressAndWait(t.id(), order, "6.00");
    String sale = atMachineAttempt(t.id());
    GatedTerminal.answer(GatedTerminal.approval("GATE-" + sale));
    assertThat(press.get(30, TimeUnit.SECONDS).status(), is(201));

    CompletableFuture<Answer> back = refundAndWait(sale, "6.00", Ids.newId().toString());
    GatedTerminal.answer(
        Terminals.refused(Terminals.TIMED_OUT, "GATE-R-TO2", "No answer from the terminal"));
    String refundAttempt = back.get(30, TimeUnit.SECONDS).data().getString("id");
    assertThat(unsettled(cashier, t.id()).list(), hasSize(2));

    Answer seen =
        settle(
            manager, refundAttempt, "APPROVED", "receipt shows the refund", Ids.newId().toString());
    assertThat(seen.body().toString(), seen.status(), is(200));
    assertThat(seen.data().getString("standing"), is("SETTLED"));
    assertThat(unsettled(cashier, t.id()).list(), hasSize(0));
    // And nothing more goes back on that card.
    Answer more = refund(manager, sale, "0.50", "again", Ids.newId().toString());
    assertThat(more.status(), is(409));
    assertThat(more.code(), is("TERMINAL_REFUND_TOO_LARGE"));
  }

  // ── a void from before payment-svc refunded voids ───────────────────────────

  /** A UUIDv7 minted at {@code at}, as an event announced then would carry. */
  private static UUID mintedAt(java.time.Instant at) {
    String millis = String.format("%012x", at.toEpochMilli());
    return Ids.parse(
        millis.substring(0, 8)
            + "-"
            + millis.substring(8)
            + "-"
            + Ids.newId().toString().substring(14));
  }

  @Test
  @DisplayName(
      "A void announced before payment-svc gave back what a voided sale took is history: nothing is"
          + " refunded, owed or given up for it — a void after is refunded, its card through the"
          + " machine")
  void aVoidFromBeforeVoidsWereRefunded() {
    assertThat(
        "the migration kept when voids began to be refunded",
        scalar(
            "SELECT count(*) FROM payment.events_handled_since WHERE event_type = 'OrderVoided'"),
        is("1"));
    UUID order = Ids.newId();
    String attempt = approved(machine().id(), order, "9.00").getString("id");
    String tenderId =
        record(cashier, order, "9.00", "CARD", attempt, Ids.newId().toString())
            .data()
            .getString("id");

    boolean acted =
        payments.refundVoidForOrderEvent(
            mintedAt(java.time.Instant.now().minus(java.time.Duration.ofDays(3))),
            CONSUMER,
            BIZ,
            order);

    assertThat(acted, is(false));
    assertThat(cardRefundsOf(attempt), is("0/-"));
    assertThat(dueOf(order), is("none"));
    assertThat(refundsOf(order), is("0/0.00/-"));
    assertThat(announcedRefunds(order), is("0"));
    assertThat(
        scalar("SELECT count(*) FROM payment.given_up_orders WHERE order_id = '" + order + "'"),
        is("0"));

    assertThat(payments.refundVoidForOrderEvent(Ids.newId(), CONSUMER, BIZ, order), is(true));
    assertThat(cardRefundsOf(attempt), is("1/APPROVED"));
    assertThat(dueOf(order), is("REFUNDED/9.00/" + tenderId));
    assertThat(refundsOf(order), is("1/9.00/CARD"));
  }

  // ── retiring a machine that holds a card ────────────────────────────────────

  private Answer retire(Caller who, UUID terminal) {
    return ItCalls.post(
        target,
        "/admin/payments/terminals/" + terminal + "/retire",
        who,
        "{\"reason\":\"swapped after a fault\"}");
  }

  private static String statusOf(UUID terminal) {
    return scalar("SELECT status FROM payment.card_terminals WHERE id = '" + terminal + "'");
  }

  @Test
  @DisplayName(
      "A machine holding a card payment that is not settled is not retired — not by another"
          + " business, not by a manager held elsewhere, and not by its own owner until the card is"
          + " settled; then it is")
  void aMachineHoldingACardIsNotRetired() {
    Terminals.Terminal t = machine();
    UUID order = Ids.newId();
    String attempt = approved(t.id(), order, "18.00").getString("id");

    for (Caller rival : rivals()) {
      Answer theirs = retire(rival, t.id());
      assertThat(
          rival.roles() + " " + theirs.body(),
          theirs.status(),
          is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    Answer notHers = retire(elsewhere, t.id());
    assertThat(notHers.status(), is(403));
    assertThat(notHers.code(), is("STORE_ACCESS_DENIED"));
    assertThat("nothing moved", statusOf(t.id()), is("ACTIVE"));

    Answer held = retire(owner, t.id());
    assertThat(held.body().toString(), held.status(), is(409));
    assertThat(held.code(), is("TERMINAL_UNSETTLED_APPROVAL"));
    assertThat(details(held), containsString("attemptId=" + attempt));
    assertThat(details(held), containsString("standing=APPROVED_UNRECORDED;kind=SALE"));
    assertThat("still in service, its card still to be settled", statusOf(t.id()), is("ACTIVE"));

    // A timeout nobody has looked at holds it too.
    assertThat(
        record(cashier, order, "18.00", "CARD", attempt, Ids.newId().toString()).status(), is(201));
    String quiet =
        sale(cashier, t.id(), Ids.newId(), "6.03", Ids.newId().toString()).data().getString("id");
    Answer undecided = retire(manager, t.id());
    assertThat(undecided.status(), is(409));
    assertThat(details(undecided), containsString("attemptId=" + quiet));
    assertThat(details(undecided), containsString("standing=UNDECIDED"));

    assertThat(
        settle(manager, quiet, "NOT_TAKEN", "the machine shows nothing", Ids.newId().toString())
            .status(),
        is(200));
    Answer retired = retire(manager, t.id());
    assertThat(retired.body().toString(), retired.status(), is(200));
    assertThat(statusOf(t.id()), is("RETIRED"));
  }

  @Test
  @DisplayName("A machine with a card at it is not retired while the cardholder is there")
  void aCardAtTheMachineHoldsItsRetirement() throws Exception {
    Terminals.Terminal t = gatedMachine();
    CompletableFuture<Answer> press = pressAndWait(t.id(), Ids.newId(), "8.00");
    Answer atIt = retire(owner, t.id());
    assertThat(atIt.status(), is(409));
    assertThat(details(atIt), containsString("standing=AT_MACHINE"));
    GatedTerminal.answer(Terminals.refused(Terminals.DECLINED, "GATE-DEC", "declined"));
    assertThat(press.get(30, TimeUnit.SECONDS).data().getString("state"), is("DECLINED"));
    assertThat(retire(owner, t.id()).status(), is(200));
  }

  @Test
  @DisplayName(
      "A press and a retirement at once: a machine is never retired with a card on it unsettled,"
          + " and a card is never claimed on a retired machine")
  void aPressAndARetirementRace() throws Exception {
    for (int round = 0; round < 5; round++) {
      Terminals.Terminal t = machine();
      java.util.concurrent.atomic.AtomicInteger turn =
          new java.util.concurrent.atomic.AtomicInteger();
      List<Object> results =
          Concurrency.inParallel(
              6,
              () -> {
                try {
                  if (turn.getAndIncrement() % 2 == 0) {
                    return terminals
                        .sale(
                            BIZ,
                            t.id(),
                            Ids.newId(),
                            new BigDecimal("4.00"),
                            "GBP",
                            actor,
                            Ids.newId().toString())
                        .state();
                  }
                  return "RETIRED:" + terminals.retire(BIZ, t.id(), "race").status();
                } catch (ApiException e) {
                  return e.code();
                }
              });
      for (Object r : results) {
        assertThat(
            results.toString(),
            List.of(
                    "APPROVED",
                    "RETIRED:RETIRED",
                    "TERMINAL_UNSETTLED_APPROVAL",
                    "TERMINAL_RETIRED",
                    "TERMINAL_ALREADY_RETIRED")
                .contains(String.valueOf(r)),
            is(true));
      }
      boolean retired = "RETIRED".equals(statusOf(t.id()));
      String approvals =
          scalar(
              "SELECT count(*) FROM payment.terminal_payments WHERE terminal_id = '"
                  + t.id()
                  + "' AND state = 'APPROVED' AND payment_id IS NULL");
      assertThat(
          "retired with a card on it unsettled: " + results,
          retired && !"0".equals(approvals),
          is(false));
      assertThat("one card at most on one machine", Integer.parseInt(approvals) <= 1, is(true));
      assertThat(
          "either the card or the retirement, and one of them happened",
          retired || "1".equals(approvals),
          is(true));
    }
  }

  // ── one refund of a card at a time, and a refund that answers late ───────────

  /** A recorded card sale of {@code amount} on a gated machine: the sale attempt and its tender. */
  private String[] recordedOnGated(Terminals.Terminal t, UUID order, String amount)
      throws Exception {
    CompletableFuture<Answer> press = pressAndWait(t.id(), order, amount);
    String sale = atMachineAttempt(t.id());
    GatedTerminal.answer(GatedTerminal.approval("GATE-" + sale));
    assertThat(press.get(30, TimeUnit.SECONDS).status(), is(201));
    String tender =
        record(cashier, order, amount, "CARD", sale, Ids.newId().toString()).data().getString("id");
    return new String[] {sale, tender};
  }

  @Test
  @DisplayName(
      "While a refund of a card is at the machine, another is refused naming it — not asked of the"
          + " machine, and not written anywhere — and another business or store finds nothing")
  void aSecondRefundWaitsForTheFirst() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    String[] paid = recordedOnGated(t, order, "30.00");
    String sale = paid[0];
    CompletableFuture<Answer> first = refundAndWait(sale, "10.00", Ids.newId().toString());
    String atMachine = atMachineAttempt(t.id());

    List<Answer> others =
        Concurrency.inParallel(
            6, () -> refund(manager, sale, "5.00", "another item", Ids.newId().toString()));
    for (Answer other : others) {
      assertThat(other.body().toString(), other.status(), is(409));
      assertThat(other.code(), is("TERMINAL_REFUND_IN_FLIGHT"));
      assertThat(details(other), containsString("attemptId=" + atMachine + ";state=REQUESTED"));
    }
    for (Caller rival : rivals()) {
      assertThat(
          rival.roles(),
          refund(rival, sale, "5.00", "not ours", Ids.newId().toString()).status(),
          is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat(
        refund(elsewhere, sale, "5.00", "not my store", Ids.newId().toString()).code(),
        is("STORE_ACCESS_DENIED"));
    assertThat("one refund asked, the one at the machine", cardRefundsOf(sale), is("1/REQUESTED"));
    assertThat("one sum owed back", dueOf(order), is("OWED/10.00/" + paid[1]));

    GatedTerminal.answer(GatedTerminal.approval("GATE-R-" + atMachine));
    assertThat(first.get(30, TimeUnit.SECONDS).data().getString("state"), is("APPROVED"));
    assertThat(refundsOf(order), is("1/10.00/CARD"));
    // Answered, it no longer holds the next one back.
    Answer next = refund(manager, sale, "5.00", "another item", Ids.newId().toString());
    assertThat(next.body().toString(), next.status(), is(201));
    assertThat(refundsOf(order), is("2/15.00/CARD"));
  }

  @Test
  @DisplayName(
      "Many refunds of one card at once: what the books say went back is exactly what the machine"
          + " put back, never more than the card paid")
  void manyRefundsAtOnceKeepTheBooksWithTheCard() throws Exception {
    UUID order = Ids.newId();
    String sale = approved(machine().id(), order, "30.00").getString("id");
    record(cashier, order, "30.00", "CARD", sale, Ids.newId().toString());
    List<Answer> asked =
        Concurrency.inParallel(
            10, () -> refund(manager, sale, "4.00", "one item each", Ids.newId().toString()));
    for (Answer a : asked) {
      assertThat(
          a.body().toString(),
          a.status() == 201
              || (a.status() == 409
                  && List.of(
                          "TERMINAL_REFUND_IN_FLIGHT",
                          "TERMINAL_REFUND_TOO_LARGE",
                          "TERMINAL_REQUEST_IN_FLIGHT")
                      .contains(a.code())),
          is(true));
    }
    String onCard =
        scalar(
            "SELECT count(*) || '/' || coalesce(sum(amount), 0)::numeric(10,2) FROM"
                + " payment.terminal_payments WHERE tenant_id = '"
                + BIZ
                + "' AND refund_of = '"
                + sale
                + "' AND state = 'APPROVED'");
    String inBooks =
        scalar(
            "SELECT count(*) || '/' || coalesce(sum(amount), 0)::numeric(10,2) FROM"
                + " payment.refund_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'");
    assertThat("the books follow the card, once each", inBooks, is(onCard));
    assertThat(
        new BigDecimal(onCard.substring(onCard.indexOf('/') + 1)).compareTo(new BigDecimal("30.00"))
            <= 0,
        is(true));
    assertThat(announcedRefunds(order), is(onCard.substring(0, onCard.indexOf('/'))));
  }

  /** Ages a refund still at the machine past its time to answer, and a manager says "not made". */
  private void saidNotMade(String refundAttempt) {
    Envelopes.exec(
        PG,
        "UPDATE payment.terminal_payments SET requested_at = now() - interval '1 hour' WHERE id ="
            + " '"
            + refundAttempt
            + "'");
    Answer looked =
        settle(
            manager,
            refundAttempt,
            "NOT_TAKEN",
            "the machine shows no refund",
            Ids.newId().toString());
    assertThat(looked.body().toString(), looked.status(), is(200));
  }

  @Test
  @DisplayName(
      "A manager's refund the machine approves after a person said it was not made is in the books"
          + " once, and counts against what is left on the card: the card is not refunded again")
  void aLateRefundApprovalIsInTheBooksOnce() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    String[] paid = recordedOnGated(t, order, "30.00");
    String sale = paid[0];
    String key = Ids.newId().toString();
    CompletableFuture<Answer> back = refundAndWait(sale, "30.00", key);
    String refundAttempt = atMachineAttempt(t.id());

    saidNotMade(refundAttempt);
    assertThat("taken as not made", dueOf(order), is("NOT_REFUNDED/30.00/" + paid[1]));
    assertThat(refundsOf(order), is("0/0.00/-"));

    // The call that asked the machine outlived the request: the machine answers that it did.
    GatedTerminal.answer(GatedTerminal.approval("GATE-LATE-R-" + refundAttempt));
    Answer late = back.get(30, TimeUnit.SECONDS);
    assertThat(late.body().toString(), late.status(), is(201));
    assertThat(late.data().getString("state"), is("APPROVED"));
    assertThat("put back after all", dueOf(order), is("REFUNDED/30.00/" + paid[1]));
    assertThat("in the books once", refundsOf(order), is("1/30.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));
    assertThat(
        scalar(
            "SELECT reference FROM payment.refund_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is("GATE-LATE-R-" + refundAttempt));

    // Told again — the request retried under its key — it is still once.
    Answer replay = refund(manager, sale, "30.00", "the sale changed", key);
    assertThat(replay.body().toString(), replay.status(), is(201));
    assertThat(replay.data().getString("id"), is(refundAttempt));
    assertThat(refundsOf(order), is("1/30.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));

    // And it counts against what is left on the card: nothing more goes back.
    Answer again = refund(manager, sale, "1.00", "once more", Ids.newId().toString());
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("TERMINAL_REFUND_TOO_LARGE"));
    assertThat(refundsOf(order), is("1/30.00/CARD"));
    assertThat(cardRefundsOf(sale), is("1/APPROVED"));
  }

  @Test
  @DisplayName(
      "When a person's 'not made' was wrong and the card was put back again meanwhile, both are in"
          + " the books, each once, and nothing more goes back")
  void aLateRefundApprovalBesideAnotherIsBookedToo() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    String[] paid = recordedOnGated(t, order, "20.00");
    String sale = paid[0];
    CompletableFuture<Answer> back = refundAndWait(sale, "20.00", Ids.newId().toString());
    String first = atMachineAttempt(t.id());
    saidNotMade(first);

    // Taken at the person's word: the card is put back again, and the machine does it at once.
    Answer again = refund(manager, sale, "20.00", "the sale changed", Ids.newId().toString());
    assertThat(again.body().toString(), again.status(), is(201));
    assertThat(again.data().getString("state"), is("APPROVED"));
    assertThat(refundsOf(order), is("1/20.00/CARD"));

    GatedTerminal.answer(GatedTerminal.approval("GATE-LATE-R2-" + first));
    assertThat(back.get(30, TimeUnit.SECONDS).data().getString("state"), is("APPROVED"));
    assertThat("the books say what the card had: twice", refundsOf(order), is("2/40.00/CARD"));
    assertThat(announcedRefunds(order), is("2"));
    Answer more = refund(manager, sale, "1.00", "more", Ids.newId().toString());
    assertThat(more.code(), is("TERMINAL_REFUND_TOO_LARGE"));
  }

  // ── a card put back is the store's of the sale it reverses ──────────────────

  private BigDecimal cardRefundedIn(Caller who, String from) {
    Answer mix = ItCalls.get(target, "/admin/reports/tender-mix?from=" + from, who);
    assertThat(mix.body().toString(), mix.status(), is(200));
    for (JsonObject row : mix.list().getValuesAs(JsonObject.class)) {
      if ("CARD".equals(row.getString("method"))) {
        return row.getJsonNumber("refundedAmount").bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  @Test
  @DisplayName(
      "A card put back through a terminal is the store's of the sale it reverses: a manager held"
          + " there reads it in the tender mix; one held elsewhere, and another business, do not")
  void aCardPutBackIsItsSalesStore() {
    UUID order = Ids.newId();
    String sale = approved(machine().id(), order, "40.00").getString("id");
    record(cashier, order, "40.00", "CARD", sale, Ids.newId().toString());
    String from = java.time.Instant.now().minusMillis(1).toString();

    assertThat(refund(manager, sale, "15.00", "faulty", Ids.newId().toString()).status(), is(201));
    payments.refundForOrderEvent(
        Ids.newId(), CONSUMER, BIZ, order, new BigDecimal("5.00"), "returned");

    assertThat(
        "every refund row names the sale's store",
        scalar(
            "SELECT count(*) || '/' || count(*) FILTER (WHERE store_id = '"
                + HERE
                + "') FROM payment.refund_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is("2/2"));
    assertThat(cardRefundedIn(manager, from).compareTo(new BigDecimal("20.00")), is(0));
    assertThat(cardRefundedIn(elsewhere, from).signum(), is(0));
    for (Caller rival : rivals()) {
      if (rival.roles().equals("CASHIER")) continue;
      assertThat(rival.roles(), cardRefundedIn(rival, from).signum(), is(0));
    }
    Answer namingOurs =
        ItCalls.get(
            target,
            "/admin/reports/tender-mix?storeId=" + HERE + "&from=" + from,
            new Caller(RIVAL, Ids.newId(), "OWNER"));
    assertThat(
        "another business naming our store reads none of it",
        namingOurs.status() != 200 || namingOurs.list().isEmpty(),
        is(true));
  }

  // ── the business's own currency ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "A card is taken in the business's own currency: a sale in another is refused 409 naming"
          + " it, before anything is claimed or asked — and another business, or a cashier held"
          + " elsewhere, is refused before that")
  void aSaleInAnotherCurrencyIsRefused() {
    Terminals.Terminal t = machine();
    String body =
        "{\"terminalId\":\""
            + t.id()
            + "\",\"orderId\":\""
            + Ids.newId()
            + "\",\"amount\":\"12.50\",\"currency\":\"EUR\"}";
    for (Caller rival : rivals()) {
      Answer theirs =
          ItCalls.call(target, "POST", "/payments/terminal", rival, body, Ids.newId().toString());
      assertThat(rival.roles(), theirs.status(), is(404));
      assertThat(theirs.code(), is("TERMINAL_NOT_FOUND"));
    }
    Answer notHers =
        ItCalls.call(
            target, "POST", "/payments/terminal", elsewhereCashier, body, Ids.newId().toString());
    assertThat(notHers.status(), is(403));
    assertThat(notHers.code(), is("STORE_ACCESS_DENIED"));

    Answer euros =
        ItCalls.call(target, "POST", "/payments/terminal", cashier, body, Ids.newId().toString());
    assertThat(euros.body().toString(), euros.status(), is(409));
    assertThat(euros.code(), is("TERMINAL_CURRENCY_MISMATCH"));
    assertThat(details(euros), containsString("currency=GBP"));
    assertThat("nothing claimed, nothing asked of the machine", attemptsOn(t.id()), is("0"));
    // In its own currency, as the till names it in any case, it is taken.
    Answer pounds =
        ItCalls.call(
            target,
            "POST",
            "/payments/terminal",
            cashier,
            body.replace("\"EUR\"", "\"gbp\""),
            Ids.newId().toString());
    assertThat(pounds.body().toString(), pounds.status(), is(201));
  }

  // ── a retired machine's cards, and the last machine of its vendor at a store ─

  private static String machineOfRefund(String saleAttempt) {
    return scalar(
        "SELECT coalesce(string_agg(DISTINCT terminal_id::text, ','), '-')"
            + " FROM payment.terminal_payments WHERE tenant_id = '"
            + BIZ
            + "' AND kind = 'REFUND' AND refund_of = '"
            + saleAttempt
            + "'");
  }

  @Test
  @DisplayName(
      "A card a retired machine took goes back through the machine that replaced it at its store —"
          + " a cancelled sale's and a manager's refund alike — and the last machine of its vendor"
          + " there is not retired while a card is owed money back")
  void aRetiredMachinesCardGoesBackThroughItsReplacement() {
    UUID lone = LONE.get(2);
    Caller manager = managerAt(lone);
    Terminals.Terminal old = machineAt(lone);
    UUID cancelled = Ids.newId();
    String[] first = recordedAt(old, cancelled, "20.00");
    UUID refunded = Ids.newId();
    String[] second = recordedAt(old, refunded, "30.00");
    UUID returned = Ids.newId();
    String[] third = recordedAt(old, returned, "30.00");
    // The swap after a fault: the replacement is registered, and the old machine retired.
    Terminals.Terminal replacement = machineAt(lone);
    assertThat(retire(manager, old.id()).status(), is(200));

    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, cancelled, null, "Order cancelled");
    assertThat(
        "put back on the card that paid", dueOf(cancelled), is("REFUNDED/20.00/" + first[1]));
    assertThat(cardRefundsOf(first[0]), is("1/APPROVED"));
    assertThat(
        "through the machine in service",
        machineOfRefund(first[0]),
        is(replacement.id().toString()));
    assertThat("and only then in the books", refundsOf(cancelled), is("1/20.00/CARD"));
    assertThat(announcedRefunds(cancelled), is("1"));

    // A manager's own refund of a card the retired machine took goes the same way.
    Answer back = refund(manager, second[0], "10.00", "price match", Ids.newId().toString());
    assertThat(back.body().toString(), back.status(), is(201));
    assertThat(back.data().getString("state"), is("APPROVED"));
    assertThat(back.data().getString("terminalId"), is(replacement.id().toString()));
    assertThat(refundsOf(refunded), is("1/10.00/CARD"));
    for (Caller rival : rivals()) {
      assertThat(
          rival.roles(),
          refund(rival, second[0], "1.00", "not ours", Ids.newId().toString()).status(),
          is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat(
        refund(elsewhere, second[0], "1.00", "not my store", Ids.newId().toString()).code(),
        is("STORE_ACCESS_DENIED"));
    assertThat("nothing more moved", refundsOf(refunded), is("1/10.00/CARD"));

    // A return the issuer will not take back on the card (.01): owed, and waiting for a person.
    payments.refundForOrderEvent(
        Ids.newId(), CONSUMER, BIZ, returned, new BigDecimal("12.01"), "returned");
    assertThat(dueOf(returned), is("NEEDS_ATTENTION/12.01/" + third[1]));
    assertThat(cardRefundsOf(third[0]), is("1/DECLINED"));
    String due = dueIdOf(returned);

    // The replacement is now the last machine of its vendor here: with it gone nothing could put
    // that card back, so it is not retired.
    for (Caller rival : rivals()) {
      Answer theirs = retire(rival, replacement.id());
      assertThat(rival.roles(), theirs.status(), is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat(retire(elsewhere, replacement.id()).code(), is("STORE_ACCESS_DENIED"));
    Answer owing = retire(manager, replacement.id());
    assertThat(owing.body().toString(), owing.status(), is(409));
    assertThat(owing.code(), is("TERMINAL_REFUNDS_OWED"));
    assertThat(
        details(owing),
        containsString(
            "dueId="
                + due
                + ";attemptId="
                + third[0]
                + ";orderId="
                + returned
                + ";amount=12.01;currency=GBP;state=NEEDS_ATTENTION"));
    assertThat("still in service", statusOf(replacement.id()), is("ACTIVE"));

    // With another registered, it is: that one puts the card back (and the issuer refuses again).
    Terminals.Terminal next = machineAt(lone);
    assertThat(retire(manager, replacement.id()).status(), is(200));
    Answer asked = retryDue(manager, due);
    assertThat(asked.body().toString(), asked.status(), is(200));
    assertThat(asked.data().getString("state"), is("NEEDS_ATTENTION"));
    assertThat(cardRefundsOf(third[0]), is("2/DECLINED,DECLINED"));
    assertThat(
        scalar(
            "SELECT terminal_id FROM payment.terminal_payments WHERE tenant_id = '"
                + BIZ
                + "' AND kind = 'REFUND' AND refund_of = '"
                + third[0]
                + "' ORDER BY requested_at DESC, id DESC LIMIT 1"),
        is(next.id().toString()));
    assertThat(retire(manager, next.id()).code(), is("TERMINAL_REFUNDS_OWED"));

    // The card cannot take it: the acquirer refunds it directly, and a manager records that.
    Answer given =
        anotherWay(
            manager,
            due,
            "CARD",
            "ACQ-R-" + returned,
            "the issuer refuses the machine's refund",
            Ids.newId().toString());
    assertThat(given.body().toString(), given.status(), is(200));
    assertThat(dueOf(returned), is("REFUNDED_ANOTHER_WAY/12.01/" + third[1]));
    assertThat(refundsOf(returned), is("1/12.01/CARD"));
    assertThat(
        scalar(
            "SELECT reference FROM payment.refund_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + returned
                + "'"),
        is("ACQ-R-" + returned));
    assertThat(announcedRefunds(returned), is("1"));
    // Nothing is owed any more, so the last machine may go.
    Answer gone = retire(manager, next.id());
    assertThat(gone.body().toString(), gone.status(), is(200));
    assertThat(statusOf(next.id()), is("RETIRED"));
  }

  @Test
  @DisplayName(
      "An approval never recorded that no machine puts back goes back on its card through the"
          + " acquirer only — not while a refund of it is unaccounted for, and never as cash the"
          + " books have nothing to give")
  void anApprovalNeverRecordedThatNoMachinePutsBack() {
    UUID lone = LONE.get(3);
    Caller manager = managerAt(lone);
    Caller till = cashierAt(lone);
    Terminals.Terminal t = machineAt(lone);
    UUID order = Ids.newId();
    Answer taken = sale(till, t.id(), order, "8.05", Ids.newId().toString());
    assertThat(taken.body().toString(), taken.status(), is(201));
    assertThat(taken.data().getString("state"), is("APPROVED"));
    String attempt = taken.data().getString("id");

    // Voided before it was recorded: owed back, and the machine never answers the refund (.05).
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Sale voided");
    assertThat(dueOf(order), is("NEEDS_ATTENTION/8.05/unrecorded"));
    assertThat(cardRefundsOf(attempt), is("1/TIMED_OUT"));
    String due = dueIdOf(order);
    String refundAttempt =
        scalar(
            "SELECT id FROM payment.terminal_payments WHERE tenant_id = '"
                + BIZ
                + "' AND kind = 'REFUND' AND refund_of = '"
                + attempt
                + "'");

    Answer tooSoon =
        anotherWay(manager, due, "CARD", "ACQ-9", "the acquirer did it", Ids.newId().toString());
    assertThat(tooSoon.body().toString(), tooSoon.status(), is(409));
    assertThat(
        "it may already be back on the card", tooSoon.code(), is("TERMINAL_REFUND_UNDECIDED"));
    assertThat(closuresOf(due), is("0/-"));

    assertThat(
        settle(
                manager,
                refundAttempt,
                "NOT_TAKEN",
                "the machine shows no refund",
                Ids.newId().toString())
            .status(),
        is(200));
    assertThat("still owed", dueOf(order), is("NEEDS_ATTENTION/8.05/unrecorded"));

    Answer cash = anotherWay(manager, due, "CASH", null, "from the drawer", Ids.newId().toString());
    assertThat(cash.body().toString(), cash.status(), is(409));
    assertThat(cash.code(), is("CARD_REFUND_DUE_NOT_IN_BOOKS"));
    assertThat(closuresOf(due), is("0/-"));
    assertThat(dueOf(order), is("NEEDS_ATTENTION/8.05/unrecorded"));

    Answer given =
        anotherWay(
            manager,
            due,
            "CARD",
            "ACQ-9-" + order,
            "refunded in the acquirer's portal",
            Ids.newId().toString());
    assertThat(given.body().toString(), given.status(), is(200));
    assertThat(given.data().getString("state"), is("REFUNDED_ANOTHER_WAY"));
    assertThat(
        given.data().getJsonObject("anotherWay").getString("reference"), is("ACQ-9-" + order));
    assertThat(dueOf(order), is("REFUNDED_ANOTHER_WAY/8.05/unrecorded"));
    assertThat(closuresOf(due), is("1/CARD"));
    assertThat("the books never had it", refundsOf(order), is("0/0.00/-"));
    assertThat(announcedRefunds(order), is("0"));
    assertThat("and the machine is free", unsettled(till, t.id()).list(), hasSize(0));
    Answer more = refund(manager, attempt, "1.00", "again", Ids.newId().toString());
    assertThat(more.status(), is(409));
    assertThat(more.code(), is("TERMINAL_REFUND_TOO_LARGE"));
  }

  @Test
  @DisplayName(
      "Asked of a machine again and given back another way at once: the money goes back one way,"
          + " never both and never neither")
  void aRetryAndAnotherWayRace() throws Exception {
    UUID lone = LONE.get(4);
    Caller manager = managerAt(lone);
    Terminals.Terminal old = machineAt(lone);
    UUID order = Ids.newId();
    String[] paid = recordedAt(old, order, "25.00");
    terminals.retire(BIZ, old.id(), "swapped");
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");
    assertThat(dueOf(order), is("NEEDS_ATTENTION/25.00/" + paid[1]));
    String due = dueIdOf(order);
    // A machine of the vendor arrives, so asking again can now put it back — while another
    // manager hands the money over in cash.
    machineAt(lone);
    java.util.concurrent.atomic.AtomicInteger turn =
        new java.util.concurrent.atomic.AtomicInteger();
    List<Answer> answers =
        Concurrency.inParallel(
            6,
            () ->
                turn.getAndIncrement() % 2 == 0
                    ? retryDue(manager, due)
                    : anotherWay(
                        manager, due, "CASH", null, "handed over in cash", Ids.newId().toString()));
    for (Answer a : answers) {
      assertThat(
          a.body().toString(),
          a.status() == 200
              || (a.status() == 409
                  && List.of("CARD_REFUND_DUE_SETTLED", "TERMINAL_REQUEST_IN_FLIGHT")
                      .contains(a.code())),
          is(true));
    }
    String state = scalar("SELECT state FROM payment.card_refund_dues WHERE id = '" + due + "'");
    assertThat(state, List.of("REFUNDED", "REFUNDED_ANOTHER_WAY").contains(state), is(true));
    boolean onCard = "REFUNDED".equals(state);
    assertThat(refundsOf(order), is(onCard ? "1/25.00/CARD" : "1/25.00/CASH"));
    assertThat(announcedRefunds(order), is("1"));
    assertThat(cardRefundsOf(paid[0]), is(onCard ? "1/APPROVED" : "0/-"));
    assertThat(closuresOf(due), is(onCard ? "0/-" : "1/CASH"));
  }

  @Test
  @DisplayName(
      "Given back another way on a person's 'not made', and then the machine says it did put it"
          + " back: both are in the books, each once, and nothing more goes back")
  void aLateMachineApprovalAfterAnotherWay() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    String[] paid = recordedOnGated(t, order, "20.00");
    GatedTerminal.armRefund();
    CompletableFuture<Void> cancelling =
        CompletableFuture.runAsync(
            () ->
                payments.refundForOrderEvent(
                    Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled"));
    GatedTerminal.atMachine();
    String refundAttempt = atMachineAttempt(t.id());
    String due = dueIdOf(order);
    Answer atMachine =
        anotherWay(manager, due, "CASH", null, "they want cash", Ids.newId().toString());
    assertThat(atMachine.status(), is(409));
    assertThat(
        "a refund of it is at the machine", atMachine.code(), is("TERMINAL_REQUEST_IN_FLIGHT"));

    saidNotMade(refundAttempt);
    assertThat(dueOf(order), is("NEEDS_ATTENTION/20.00/" + paid[1]));
    Answer given =
        anotherWay(manager, due, "CASH", null, "they wanted cash", Ids.newId().toString());
    assertThat(given.body().toString(), given.status(), is(200));
    assertThat(refundsOf(order), is("1/20.00/CASH"));

    // The call that asked the machine outlived everything: it answers that it did put it back.
    GatedTerminal.answer(GatedTerminal.approval("GATE-LATE-AW-" + refundAttempt));
    cancelling.get(30, TimeUnit.SECONDS);
    assertThat(cardRefundsOf(paid[0]), is("1/APPROVED"));
    assertThat("the books say what happened: twice", refundsOf(order), is("2/40.00/CARD,CASH"));
    assertThat(announcedRefunds(order), is("2"));
    assertThat(
        "what a person gave by hand stays given", dueOf(order), containsString("ANOTHER_WAY"));
    assertThat(closuresOf(due), is("1/CASH"));
    Answer more = refund(manager, paid[0], "1.00", "more", Ids.newId().toString());
    assertThat(more.code(), is("TERMINAL_REFUND_TOO_LARGE"));
  }

  // ── a refund asked before its sale was recorded, answered after ─────────────

  /** An approval on a gated machine that no tender records yet. */
  private String unrecordedOnGated(Terminals.Terminal t, UUID order, String amount)
      throws Exception {
    CompletableFuture<Answer> press = pressAndWait(t.id(), order, amount);
    String sale = atMachineAttempt(t.id());
    GatedTerminal.answer(GatedTerminal.approval("GATE-" + sale));
    assertThat(press.get(30, TimeUnit.SECONDS).status(), is(201));
    return sale;
  }

  @Test
  @DisplayName(
      "A refund of an approval not yet recorded, said 'not made' by a person, whose sale is then"
          + " recorded and which the machine approves after all: the books give it back against"
          + " that tender once, and neither the card nor the books give it again")
  void aLateRefundApprovalOfASaleRecordedSince() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    String sale = unrecordedOnGated(t, order, "30.00");

    // A manager puts the approval back; the machine goes quiet with the refund at it.
    String key = Ids.newId().toString();
    CompletableFuture<Answer> back = refundAndWait(sale, "30.00", key);
    String refundAttempt = atMachineAttempt(t.id());
    assertThat("asked for nothing owed: the sale is not in the books", dueOf(order), is("none"));
    for (Caller rival : rivals()) {
      Answer theirs = settle(rival, refundAttempt, "NOT_TAKEN", "not ours", Ids.newId().toString());
      assertThat(rival.roles(), theirs.status(), is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat(
        settle(elsewhere, refundAttempt, "NOT_TAKEN", "not my store", Ids.newId().toString())
            .code(),
        is("STORE_ACCESS_DENIED"));
    assertThat(decisions(refundAttempt), is("0"));
    saidNotMade(refundAttempt);

    // Taken at the person's word the sale is whole on its card, so the cashier records it.
    Answer tender = record(cashier, order, "30.00", "CARD", sale, Ids.newId().toString());
    assertThat(tender.body().toString(), tender.status(), is(201));
    String tenderId = tender.data().getString("id");
    assertThat(refundsOf(order), is("0/0.00/-"));

    // The call that asked the machine outlived the request: it answers that it did put it back.
    GatedTerminal.answer(GatedTerminal.approval("GATE-LATE-U-" + refundAttempt));
    Answer late = back.get(30, TimeUnit.SECONDS);
    assertThat(late.body().toString(), late.status(), is(201));
    assertThat(late.data().getString("state"), is("APPROVED"));
    assertThat("in the books once", refundsOf(order), is("1/30.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));
    assertThat(
        "against the tender the sale became, at its store, under the machine's reference",
        scalar(
            "SELECT payment_id::text || '/' || reference || '/' || store_id::text FROM"
                + " payment.refund_tenders"
                + " WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is(tenderId + "/GATE-LATE-U-" + refundAttempt + "/" + HERE));
    assertThat("it was never owed: no due stands for it", dueOf(order), is("none"));

    // Told again — the request retried under its key — it is still once.
    Answer replay = refund(manager, sale, "30.00", "the sale changed", key);
    assertThat(replay.body().toString(), replay.status(), is(201));
    assertThat(replay.data().getString("id"), is(refundAttempt));
    assertThat(refundsOf(order), is("1/30.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));

    // Neither the card nor the books give it back again.
    Answer again = refund(manager, sale, "1.00", "once more", Ids.newId().toString());
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("TERMINAL_REFUND_TOO_LARGE"));
    Answer cash =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + order + "/refunds",
            manager,
            "{\"paymentId\":\""
                + tenderId
                + "\",\"amount\":1.00,\"method\":\"CASH\",\"reason\":\"cash too\"}",
            Ids.newId().toString());
    assertThat(cash.status(), is(409));
    assertThat(cash.code(), is("REFUND_EXCEEDS_PAYMENT"));
    // And a cancellation of that order afterwards owes nothing more to the card.
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");
    assertThat(dueOf(order), is("none"));
    assertThat(refundsOf(order), is("1/30.00/CARD"));
    assertThat(cardRefundsOf(sale), is("1/APPROVED"));
    assertThat(announcedRefunds(order), is("1"));
  }

  @Test
  @DisplayName(
      "The machine's late approval of a refund and the recording of its sale at once: either the"
          + " sale is recorded and the books give the refund back, or it is not recorded at all —"
          + " never a tender standing whole for money that went back")
  void aLateRefundApprovalAndTheRecordingRace() throws Exception {
    for (int round = 0; round < 4; round++) {
      Terminals.Terminal t = gatedMachine();
      UUID order = Ids.newId();
      String sale = unrecordedOnGated(t, order, "30.00");
      CompletableFuture<Answer> back = refundAndWait(sale, "30.00", Ids.newId().toString());
      String refundAttempt = atMachineAttempt(t.id());
      saidNotMade(refundAttempt);

      CompletableFuture<Answer> recording =
          CompletableFuture.supplyAsync(
              () -> record(cashier, order, "30.00", "CARD", sale, Ids.newId().toString()));
      GatedTerminal.answer(GatedTerminal.approval("GATE-RACE-" + refundAttempt));
      Answer late = back.get(30, TimeUnit.SECONDS);
      Answer recorded = recording.get(30, TimeUnit.SECONDS);

      assertThat(late.body().toString(), late.data().getString("state"), is("APPROVED"));
      if (recorded.status() == 201) {
        assertThat("round " + round, tendersOn(order), is("1"));
        assertThat("the books follow the card", refundsOf(order), is("1/30.00/CARD"));
        assertThat(announcedRefunds(order), is("1"));
      } else {
        assertThat(recorded.body().toString(), recorded.status(), is(409));
        assertThat(recorded.code(), is("TERMINAL_ATTEMPT_REFUNDED"));
        assertThat("round " + round, tendersOn(order), is("0"));
        assertThat(refundsOf(order), is("0/0.00/-"));
        assertThat(announcedRefunds(order), is("0"));
      }
      assertThat(
          refund(manager, sale, "1.00", "once more", Ids.newId().toString()).code(),
          is("TERMINAL_REFUND_TOO_LARGE"));
    }
  }

  // ── what is left on a tender, and a machine's approval landing in the books ──

  /** A refund written in the books alone, as a manager at the desk asks it. */
  private Answer booksRefund(Caller who, UUID order, String tender, String amount, String method) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/by-order/" + order + "/refunds",
        who,
        "{\"paymentId\":\""
            + tender
            + "\",\"amount\":"
            + amount
            + ",\"method\":\""
            + method
            + "\",\"reason\":\"asked at the desk\"}",
        Ids.newId().toString());
  }

  @Test
  @DisplayName(
      "A machine's approval of a card refund is written in the books with its tender's row taken:"
          + " while somebody counting that tender holds the row it waits, the sum still owed —"
          + " never refunded and owed by nobody, where it could be given back twice")
  void aMachinesApprovalWaitsForTheTendersRow() throws Exception {
    Terminals.Terminal t = gatedMachine();
    UUID order = Ids.newId();
    String[] paid = recordedOnGated(t, order, "30.00");
    String sale = paid[0];
    String tender = paid[1];
    CompletableFuture<Answer> back = refundAndWait(sale, "30.00", Ids.newId().toString());
    assertThat(dueOf(order), is("OWED/30.00/" + tender));

    // At the machine, the sum is owed back and spoken for: no cash for it, from anybody. Another
    // business finds no tender (also when it names our store), and a manager held to another of
    // our stores may not act at this one — each before anything is counted or written.
    assertThat(
        booksRefund(manager, order, tender, "30.00", "CASH").code(), is("REFUND_EXCEEDS_PAYMENT"));
    for (Caller rival : rivals()) {
      assertThat(
          rival.roles(),
          booksRefund(rival, order, tender, "30.00", "CASH").status(),
          is(rival.roles().equals("CASHIER") ? 403 : 404));
    }
    assertThat(
        booksRefund(new Caller(RIVAL, Ids.newId(), "MANAGER", HERE), order, tender, "1.00", "CASH")
            .code(),
        is("PAYMENT_NOT_FOUND"));
    assertThat(
        booksRefund(elsewhere, order, tender, "1.00", "CASH").code(), is("STORE_ACCESS_DENIED"));
    assertThat(refundsOf(order), is("0/0.00/-"));

    try (java.sql.Connection counting = PG.dataSource().getConnection()) {
      counting.setAutoCommit(false);
      // Somebody counting what is left on the tender holds its row, as every refund in the books
      // does from before its first read to its commit.
      try (java.sql.PreparedStatement lock =
          counting.prepareStatement(
              "SELECT id FROM payment.payment_tenders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
        lock.setObject(1, BIZ);
        lock.setObject(2, Ids.parse(tender));
        try (java.sql.ResultSet rs = lock.executeQuery()) {
          assertThat(rs.next(), is(true));
        }
      }

      GatedTerminal.answer(GatedTerminal.approval("GATE-R-" + sale));

      // The machine put it back and its attempt says so; the books wait for the row.
      org.junit.jupiter.api.Assertions.assertThrows(
          java.util.concurrent.TimeoutException.class, () -> back.get(3, TimeUnit.SECONDS));
      assertThat(cardRefundsOf(sale), is("1/APPROVED"));
      assertThat("not in the books while the row is held", refundsOf(order), is("0/0.00/-"));
      assertThat("and still owed", dueOf(order), is("OWED/30.00/" + tender));
      // So whoever holds the row counts all of it as spoken for, whichever they read first.
      try (java.sql.PreparedStatement counted =
          counting.prepareStatement(
              "SELECT (SELECT COALESCE(SUM(amount), 0) FROM payment.refund_tenders"
                  + " WHERE tenant_id = ? AND payment_id = ?)"
                  + " + (SELECT COALESCE(SUM(amount), 0) FROM payment.card_refund_dues"
                  + " WHERE tenant_id = ? AND payment_id = ?"
                  + " AND state IN ('OWED', 'NEEDS_ATTENTION'))")) {
        counted.setObject(1, BIZ);
        counted.setObject(2, Ids.parse(tender));
        counted.setObject(3, BIZ);
        counted.setObject(4, Ids.parse(tender));
        try (java.sql.ResultSet rs = counted.executeQuery()) {
          assertThat(rs.next(), is(true));
          assertThat(rs.getBigDecimal(1).compareTo(new BigDecimal("30.00")), is(0));
        }
      }
      counting.rollback();
    }

    Answer landed = back.get(30, TimeUnit.SECONDS);
    assertThat(landed.body().toString(), landed.data().getString("state"), is("APPROVED"));
    assertThat("in the books once the row is free", refundsOf(order), is("1/30.00/CARD"));
    assertThat(dueOf(order), is("REFUNDED/30.00/" + tender));
    assertThat(announcedRefunds(order), is("1"));
    // Refunded now instead of owed: still nothing left for cash.
    assertThat(
        booksRefund(manager, order, tender, "0.01", "CASH").code(), is("REFUND_EXCEEDS_PAYMENT"));
    assertThat(refundsOf(order), is("1/30.00/CARD"));
    assertThat(announcedRefunds(order), is("1"));
  }

  @Test
  @DisplayName(
      "Cash refunds in the books and the machine's approval of a card refund of the same tender at"
          + " once: the tender gives back what it took, once — never 60.00 against 30.00")
  void aCashRefundAndTheMachinesApprovalRace() throws Exception {
    for (int round = 0; round < 6; round++) {
      Terminals.Terminal t = gatedMachine();
      UUID order = Ids.newId();
      String[] paid = recordedOnGated(t, order, "30.00");
      String sale = paid[0];
      String tender = paid[1];
      CompletableFuture<Answer> back = refundAndWait(sale, "30.00", Ids.newId().toString());

      CompletableFuture<List<Answer>> cash =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return Concurrency.inParallel(
                      4, () -> booksRefund(manager, order, tender, "30.00", "CASH"));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      GatedTerminal.answer(GatedTerminal.approval("GATE-R-" + sale));

      Answer landed = back.get(30, TimeUnit.SECONDS);
      assertThat(landed.body().toString(), landed.data().getString("state"), is("APPROVED"));
      for (Answer a : cash.get(30, TimeUnit.SECONDS)) {
        assertThat("round " + round + " " + a.body(), a.status(), is(409));
        assertThat(a.code(), is("REFUND_EXCEEDS_PAYMENT"));
      }
      assertThat("round " + round, refundsOf(order), is("1/30.00/CARD"));
      assertThat(dueOf(order), is("REFUNDED/30.00/" + tender));
      assertThat(announcedRefunds(order), is("1"));
    }
  }

  @Test
  @DisplayName(
      "An order cancelled at the moment the machine approves a card refund of all of it owes"
          + " nothing more: no second sum owed back for money already on the card")
  void aCancellationAndTheMachinesApprovalRace() throws Exception {
    for (int round = 0; round < 6; round++) {
      Terminals.Terminal t = gatedMachine();
      UUID order = Ids.newId();
      String[] paid = recordedOnGated(t, order, "30.00");
      String sale = paid[0];
      String tender = paid[1];
      CompletableFuture<Answer> back = refundAndWait(sale, "30.00", Ids.newId().toString());

      CompletableFuture<Void> cancelled =
          CompletableFuture.runAsync(
              () ->
                  payments.refundForOrderEvent(
                      Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled"));
      GatedTerminal.answer(GatedTerminal.approval("GATE-R-" + sale));

      Answer landed = back.get(30, TimeUnit.SECONDS);
      cancelled.get(30, TimeUnit.SECONDS);
      assertThat(landed.body().toString(), landed.data().getString("state"), is("APPROVED"));
      assertThat(
          "round " + round + ": one sum, put back once",
          dueOf(order),
          is("REFUNDED/30.00/" + tender));
      assertThat("round " + round, refundsOf(order), is("1/30.00/CARD"));
      assertThat(cardRefundsOf(sale), is("1/APPROVED"));
      assertThat(announcedRefunds(order), is("1"));
    }
  }

  /** A drawer on the SESSION basis at {@code store}, opened as its manager. */
  private UUID drawerAt(UUID store, Caller who) {
    Answer a =
        ItCalls.post(
            target,
            "/admin/cash/till-sessions",
            who,
            "{\"storeId\":\"" + store + "\",\"floatAmount\":100,\"basis\":\"SESSION\"}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(a.data().getString("id"));
  }

  /** A card sale recorded at {@code store} whose machine is gone, owed back: its order and due. */
  private UUID[] owedAt(UUID store, String amount) {
    Terminals.Terminal t = machineAt(store);
    UUID order = Ids.newId();
    recordedAt(t, order, amount);
    terminals.retire(BIZ, t.id(), "stolen");
    payments.refundForOrderEvent(Ids.newId(), CONSUMER, BIZ, order, null, "Order cancelled");
    return new UUID[] {order, Ids.parse(dueIdOf(order))};
  }

  private Answer anotherWayAt(Caller who, UUID due, UUID session, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/terminal/refund-dues/" + due + "/another-way",
        who,
        "{\"method\":\"CASH\",\"reason\":\"the machine was stolen\""
            + (session == null ? "" : ",\"tillSessionId\":\"" + session + "\"")
            + "}",
        key);
  }

  @Test
  @DisplayName(
      "Cash given back for a card payment no machine can return leaves the drawer the manager"
          + " names: refused for a closed or another store's, and counted in none if none is named")
  void aCardGivenBackInCashLeavesTheDrawerItNames() {
    UUID lone = LONE.get(8);
    Caller manager = managerAt(lone);
    UUID drawer = drawerAt(lone, manager);
    UUID closed = drawerAt(lone, manager);
    assertThat(
        ItCalls.post(
                target,
                "/admin/cash/till-sessions/" + closed + "/close",
                manager,
                "{\"countedCash\":100}")
            .status(),
        is(200));
    UUID elsewhereDrawer = drawerAt(ELSEWHERE, Caller.owner(BIZ));
    UUID[] named = owedAt(lone, "16.00");
    UUID quiet = LONE.get(9);
    UUID[] loose = owedAt(quiet, "7.00");

    Answer isClosed = anotherWayAt(manager, named[1], closed, Ids.newId().toString());
    assertThat(isClosed.status(), is(409));
    assertThat(isClosed.code(), is("TILL_SESSION_NOT_OPEN"));
    Answer wrongStore = anotherWayAt(owner, named[1], elsewhereDrawer, Ids.newId().toString());
    assertThat(wrongStore.status(), is(409));
    assertThat(wrongStore.code(), is("TILL_SESSION_OTHER_STORE"));
    Answer unknown = anotherWayAt(manager, named[1], Ids.newId(), Ids.newId().toString());
    assertThat(unknown.status(), is(404));
    assertThat(unknown.code(), is("TILL_SESSION_NOT_FOUND"));
    assertThat("nothing moved", dueOf(named[0]), containsString("NEEDS_ATTENTION/16.00/"));
    assertThat(refundsOf(named[0]), is("0/0.00/-"));

    String key = Ids.newId().toString();
    Answer given = anotherWayAt(manager, named[1], drawer, key);
    assertThat(given.body().toString(), given.status(), is(200));
    assertThat(
        "the cash is the drawer's",
        scalar(
            "SELECT till_session_id || ' ' || store_id FROM payment.refund_tenders"
                + " WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + named[0]
                + "'"),
        is(drawer + " " + lone));
    JsonObject x =
        ItCalls.get(target, "/admin/cash/till-sessions/" + drawer + "/x-report", manager).data();
    assertThat(
        x.getJsonNumber("cashRefunds").bigDecimalValue().compareTo(new BigDecimal("16")), is(0));
    assertThat(
        "the drawer expects its float less what it gave",
        x.getJsonNumber("expectedCashInTill").bigDecimalValue().compareTo(new BigDecimal("84")),
        is(0));

    // a retry after the drawer has closed is answered, not refused
    assertThat(
        ItCalls.post(
                target,
                "/admin/cash/till-sessions/" + drawer + "/close",
                manager,
                "{\"countedCash\":84}")
            .status(),
        is(200));
    Answer replay = anotherWayAt(manager, named[1], drawer, key);
    assertThat(replay.body().toString(), replay.status(), is(200));
    assertThat(refundsOf(named[0]), is("1/16.00/CASH"));

    // naming no drawer is recorded as it always was, in none
    Answer none = anotherWayAt(managerAt(quiet), loose[1], null, Ids.newId().toString());
    assertThat(none.body().toString(), none.status(), is(200));
    assertThat(
        scalar(
            "SELECT coalesce(till_session_id::text, '-') FROM payment.refund_tenders"
                + " WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + loose[0]
                + "'"),
        is("-"));
  }

  private Answer recordNaming(
      Caller who, UUID order, String amount, String attempt, UUID session, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments",
        who,
        "{\"orderId\":\""
            + order
            + "\",\"amount\":"
            + amount
            + ",\"method\":\"CARD\",\"terminalPaymentId\":\""
            + attempt
            + "\",\"tillSessionId\":\""
            + session
            + "\"}",
        key);
  }

  @Test
  @DisplayName(
      "A card machine's payment recorded as a tender names its drawer: refused with the machine"
          + " unsettled when the drawer has closed, the drawer's own when open, and a retry after"
          + " the close answers the first")
  void aCardMachinesTenderNamesItsDrawer() {
    UUID lone = LONE.get(10);
    Caller manager = managerAt(lone);
    Terminals.Terminal t = machineAt(lone);
    UUID closed = drawerAt(lone, manager);
    UUID open = drawerAt(lone, manager);
    assertThat(
        ItCalls.post(
                target,
                "/admin/cash/till-sessions/" + closed + "/close",
                manager,
                "{\"countedCash\":100}")
            .status(),
        is(200));
    UUID order = Ids.newId();
    Caller till = cashierAt(lone);
    Answer sold = sale(till, t.id(), order, "12.00", Ids.newId().toString());
    assertThat(sold.body().toString(), sold.status(), is(201));
    String attempt = sold.data().getString("id");

    Answer refused = recordNaming(till, order, "12.00", attempt, closed, Ids.newId().toString());
    assertThat(refused.status(), is(409));
    assertThat(refused.code(), is("TILL_SESSION_NOT_OPEN"));
    assertThat(
        "nothing was recorded, and the machine is still waiting for its tender",
        scalar(
            "SELECT count(*) FROM payment.payment_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is("0"));

    String key = Ids.newId().toString();
    Answer taken = recordNaming(till, order, "12.00", attempt, open, key);
    assertThat(taken.body().toString(), taken.status(), is(201));
    assertThat(
        scalar(
            "SELECT till_session_id FROM payment.payment_tenders WHERE tenant_id = '"
                + BIZ
                + "' AND order_id = '"
                + order
                + "'"),
        is(open.toString()));
    JsonObject x =
        ItCalls.get(target, "/admin/cash/till-sessions/" + open + "/x-report", manager).data();
    assertThat(
        "a card is in the drawer's takings, not in its cash",
        x.getJsonObject("tenderSummary").getJsonObject("CARD").getJsonNumber("sales").intValue(),
        is(12));
    assertThat(x.getJsonNumber("expectedCashInTill").bigDecimalValue().intValue(), is(100));

    assertThat(
        ItCalls.post(
                target,
                "/admin/cash/till-sessions/" + open + "/close",
                manager,
                "{\"countedCash\":100}")
            .status(),
        is(200));
    Answer again = recordNaming(till, order, "12.00", attempt, open, key);
    assertThat(again.body().toString(), again.status(), is(201));
    assertThat(again.data().getString("id"), is(taken.data().getString("id")));
  }
}
