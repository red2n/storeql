package com.storeql.payment;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.service.TerminalService;
import com.storeql.test.PostgresSupport;
import com.storeql.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
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
 * Taking a card on a terminal (07.16), against a real database.
 *
 * <p>The assertion this class exists for is the double charge: <b>the same idempotency key must
 * never reach the terminal twice.</b> That cannot be had from a unit test, because the guard is a
 * row and a primary key written in the same transaction as the attempt, before the device is asked
 * for anything.
 *
 * <p>The second is the timeout. A decline took nothing and a failure never started, but a timeout
 * means the card <em>may</em> have been charged — so it records no payment, and the attempt keeps
 * its provider reference so it can be found in the acquirer's settlement file. A platform that
 * treated it as a decline would lose a taking; one that treated it as an approval would claim money
 * it cannot prove.
 *
 * <p>The simulator picks its outcome from the amount's minor units, which is the acquirers' own
 * convention: {@code .01} declines, {@code .02} is cancelled, {@code .03} times out, {@code .04}
 * fails.
 */
@HelidonTest
class TerminalPaymentIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "payment");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject TerminalService svc;
  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private final UUID tenant = Ids.newId();
  private final UUID store = Ids.newId();
  private final UUID actor = Ids.newId();

  private Terminals.Terminal aTerminal(String label) {
    return svc.register(tenant, store, label + "-" + Ids.newId(), "SIMULATED", null, actor);
  }

  private Terminals.Attempt take(Terminals.Terminal t, String amount, String key) {
    return svc.sale(tenant, t.id(), Ids.newId(), new BigDecimal(amount), "GBP", actor, key);
  }

  // ── the double charge ──────────────────────────────────────────────────────

  @Test
  @DisplayName("The same key never reaches the terminal twice")
  void theSameKeyNeverReachesTheTerminalTwice() {
    Terminals.Terminal t = aTerminal("till");
    UUID order = Ids.newId();
    String key = Ids.newId().toString();

    var first = svc.sale(tenant, t.id(), order, new BigDecimal("12.50"), "GBP", actor, key);
    var second = svc.sale(tenant, t.id(), order, new BigDecimal("12.50"), "GBP", actor, key);

    assertThat("the retry is the first attempt, not a second one", second.id(), is(first.id()));
    assertThat(first.state(), is(Terminals.APPROVED));
    // One attempt on the record for one press, however many times it was pressed.
    assertThat(svc.attemptsOf(tenant, order), hasSize(1));
    assertThat(
        "and the same authorisation, not a second one on the customer's card",
        second.authCode(),
        is(first.authCode()));
  }

  @Test
  @DisplayName("Two different presses on one sale are two attempts, because they are two decisions")
  void twoDifferentPressesAreTwoAttempts() {
    Terminals.Terminal t = aTerminal("till");
    UUID order = Ids.newId();
    // Two cards for one sale — a split tender — and the guard must not collapse them. It keys on
    // the
    // press, not on the order, which is why a customer may pay half on each of two cards. The
    // first is recorded as its tender before the second is asked, as the till does: until then the
    // machine holds it (TerminalSettlementIT).
    var first =
        svc.sale(
            tenant, t.id(), order, new BigDecimal("5.00"), "GBP", actor, Ids.newId().toString());
    svc.attachPayment(tenant, first.id(), Ids.newId());
    svc.sale(tenant, t.id(), order, new BigDecimal("5.00"), "GBP", actor, Ids.newId().toString());
    assertThat(svc.attemptsOf(tenant, order), hasSize(2));
  }

  // ── what the terminal said ─────────────────────────────────────────────────

  @Test
  @DisplayName("An approval keeps what a card receipt has to carry")
  void anApprovalKeepsTheReceipt() {
    var approved = take(aTerminal("till"), "20.00", Ids.newId().toString());

    assertThat(approved.state(), is(Terminals.APPROVED));
    assertThat(approved.scheme(), is(not(nullValue())));
    assertThat(approved.panLast4(), is(not(nullValue())));
    assertThat(approved.authCode(), is(not(nullValue())));
    assertThat(approved.aid(), is(not(nullValue())));
    assertThat(approved.entryMode(), is(not(nullValue())));
    // The line a receipt prints, assembled once so every printer agrees.
    assertThat(approved.receiptLine(), is(not(nullValue())));
    assertThat(approved.receiptLine(), org.hamcrest.Matchers.containsString("****"));
    assertThat("and the full number is nowhere", approved.receiptLine().length() < 60, is(true));
  }

  @Test
  @DisplayName("A decline is settled, carries the terminal's reason, and took nothing")
  void aDecline() {
    var declined = take(aTerminal("till"), "9.01", Ids.newId().toString());

    assertThat(declined.state(), is(Terminals.DECLINED));
    assertThat(declined.settled(), is(true));
    assertThat(declined.outcomeDetail(), is(not(nullValue())));
    assertThat("nothing to print", declined.receiptLine(), is(nullValue()));
    assertThat("and no tender", declined.paymentId(), is(nullValue()));
  }

  @Test
  @DisplayName("A timeout keeps its reference, because that is how the money is traced")
  void aTimeout() {
    var timedOut = take(aTerminal("till"), "9.03", Ids.newId().toString());

    assertThat(timedOut.state(), is(Terminals.TIMED_OUT));
    assertThat(
        "no tender: the platform cannot prove the money moved",
        timedOut.paymentId(),
        is(nullValue()));
    // The reference is the only way to find this attempt in the acquirer's settlement file, which
    // is
    // the only way to learn what really happened. Dropping it would leave a possible charge with
    // nothing to match it to.
    assertThat(timedOut.providerRef(), is(not(nullValue())));
    assertThat(timedOut.settled(), is(true));
  }

  @Test
  @DisplayName("A cancellation and a failure are settled too — nothing is left REQUESTED")
  void cancelledAndFailed() {
    assertThat(
        take(aTerminal("till"), "9.02", Ids.newId().toString()).state(), is(Terminals.CANCELLED));
    assertThat(
        take(aTerminal("till"), "9.04", Ids.newId().toString()).state(), is(Terminals.FAILED));
    // An attempt stuck in REQUESTED is indistinguishable from one where the card may have been
    // charged, which is the state this row exists to avoid producing by accident.
  }

  // ── putting money back ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A refund goes back on the card that paid, and never more than it took")
  void aRefund() {
    Terminals.Terminal t = aTerminal("till");
    var sale = take(t, "30.00", Ids.newId().toString());

    var back =
        svc.refund(
            tenant,
            sale.id(),
            new BigDecimal("10.00"),
            actor,
            Ids.newId().toString(),
            "one item was faulty");
    assertThat(back.state(), is(Terminals.APPROVED));
    assertThat(back.kind(), is(Terminals.REFUND));
    assertThat("it names what it puts back", back.refundOf(), is(sale.id()));

    ApiException tooMuch =
        assertThrows(
            ApiException.class,
            () -> svc.refund(tenant, sale.id(), new BigDecimal("40.00"), actor, "r2", "too much"));
    assertThat(tooMuch.code(), is("TERMINAL_REFUND_TOO_LARGE"));
  }

  @Test
  @DisplayName("Nothing is refunded against an attempt that took no money")
  void refundingADecline() {
    var declined = take(aTerminal("till"), "9.01", Ids.newId().toString());
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.refund(tenant, declined.id(), new BigDecimal("1.00"), actor, "r", "nothing"));
    assertThat(e.code(), is("TERMINAL_NOT_APPROVED"));
  }

  @Test
  @DisplayName("A cancel that races an approval loses, and the approval stands")
  void cancelDoesNotUndoAnApproval() {
    // The state moves once out of REQUESTED and never back, so a cancel arriving after the
    // cardholder
    // has tapped cannot turn a taking into a cancellation.
    var approved = take(aTerminal("till"), "15.00", Ids.newId().toString());
    var after = svc.cancel(tenant, approved.id());
    assertThat(after.state(), is(Terminals.APPROVED));
  }

  // ── the register ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A retired terminal takes no more cards, and is not deleted")
  void aRetiredTerminal() {
    Terminals.Terminal t = aTerminal("till");
    var retired = svc.retire(tenant, t.id(), "screen cracked");
    assertThat(retired.status(), is(Terminals.RETIRED));
    assertThat(retired.retiredReason(), is("screen cracked"));

    ApiException e =
        assertThrows(ApiException.class, () -> take(retired, "5.00", Ids.newId().toString()));
    assertThat(e.code(), is("TERMINAL_RETIRED"));
    // Kept, because payments point at it.
    assertThat(svc.list(tenant).stream().anyMatch(x -> x.id().equals(t.id())), is(true));
  }

  @Test
  @DisplayName("Two active terminals cannot share a label in one store")
  void labelsAreUniquePerStore() {
    String label = "Till " + Ids.newId();
    svc.register(tenant, store, label, "SIMULATED", null, actor);
    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.register(tenant, store, label, "SIMULATED", null, actor));
    assertThat(e.code(), is("TERMINAL_ALREADY_REGISTERED"));
  }

  @Test
  @DisplayName("A retired terminal's label is free for the device that replaces it")
  void aReplacementTakesTheLabel() {
    // Which is what happens when a pinpad is swapped after a fault, and a shop should not have to
    // invent "Till 2 (new)".
    String label = "Till " + Ids.newId();
    var first = svc.register(tenant, store, label, "SIMULATED", null, actor);
    svc.retire(tenant, first.id(), "swapped");
    var replacement = svc.register(tenant, store, label, "SIMULATED", null, actor);
    assertThat(replacement.id(), is(not(first.id())));
  }

  @Test
  @DisplayName("An unknown vendor is refused, and only configured ones are offered")
  void vendors() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.register(tenant, store, "Till X", "MY_OWN_PINPAD", null, actor));
    assertThat(e.code(), is("TERMINAL_VENDOR_UNKNOWN"));
    // The simulator is always available; a real vendor appears only when configured, so a business
    // is
    // not asked to pair a device the platform cannot reach.
    assertThat(svc.availableVendors(), contains("SIMULATED"));
  }

  @Test
  @DisplayName("One business never sees another's terminals or attempts")
  void oneBusinessNeverSeesAnothers() {
    Terminals.Terminal mine = aTerminal("till");
    UUID order = Ids.newId();
    svc.sale(
        tenant, mine.id(), order, new BigDecimal("7.00"), "GBP", actor, Ids.newId().toString());

    UUID rival = Ids.newId();
    assertThat(svc.list(rival), hasSize(0));
    assertThat(svc.attemptsOf(rival, order), hasSize(0));
    // And it cannot take a card on this business's device by naming its id.
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.sale(
                    rival,
                    mine.id(),
                    Ids.newId(),
                    new BigDecimal("7.00"),
                    "GBP",
                    actor,
                    Ids.newId().toString()));
    assertThat(e.code(), is("TERMINAL_NOT_FOUND"));
  }

  // ── what the amount may be ─────────────────────────────────────────────────

  @Test
  @DisplayName("An amount is positive money to two places, and a third is refused not rounded")
  void theAmount() {
    Terminals.Terminal t = aTerminal("till");
    for (String bad : new String[] {"0.00", "-5.00", "1.005"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.sale(tenant, t.id(), Ids.newId(), new BigDecimal(bad), "GBP", actor, "k"));
      assertThat(bad, e.code(), is("TERMINAL_AMOUNT_INVALID"));
    }
  }

  @Test
  @DisplayName(
      "A dinar's third decimal and a whole yen are kept as taken, through the database, and a"
          + " finer amount is refused not rounded")
  void theAmountIsTheCurrencysOwn() {
    // A machine per approval: an approval nobody records holds its machine for the next sale.
    Terminals.Terminal t = aTerminal("till");
    var kwd =
        svc.sale(
            tenant,
            t.id(),
            Ids.newId(),
            new BigDecimal("1.125"),
            "KWD",
            actor,
            Ids.newId().toString());
    // Read back from the row, not the claim held in memory: a two-place column would say 1.13.
    var kwdRow = svc.attempt(tenant, kwd.id()).orElseThrow();
    assertThat(kwdRow.amount().compareTo(new BigDecimal("1.125")), is(0));
    assertThat(com.storeql.payment.mapper.TerminalMappers.toDto(kwdRow).amount(), is("1.125"));

    var jpy =
        svc.sale(
            tenant,
            aTerminal("till").id(),
            Ids.newId(),
            new BigDecimal("1250"),
            "JPY",
            actor,
            Ids.newId().toString());
    assertThat(
        com.storeql.payment.mapper.TerminalMappers.toDto(
                svc.attempt(tenant, jpy.id()).orElseThrow())
            .amount(),
        is("1250"));

    var gbp = take(aTerminal("till"), "12.5", Ids.newId().toString());
    assertThat(
        com.storeql.payment.mapper.TerminalMappers.toDto(
                svc.attempt(tenant, gbp.id()).orElseThrow())
            .amount(),
        is("12.50"));

    for (String[] bad : new String[][] {{"1.1255", "KWD"}, {"1250.5", "JPY"}, {"1.005", "GBP"}}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () ->
                  svc.sale(
                      tenant,
                      t.id(),
                      Ids.newId(),
                      new BigDecimal(bad[0]),
                      bad[1],
                      actor,
                      Ids.newId().toString()));
      assertThat(bad[0] + " " + bad[1], e.code(), is("TERMINAL_AMOUNT_INVALID"));
    }
  }

  @Test
  @DisplayName("Nothing card-shaped is accepted, wherever it came from")
  void noCardNumbers() {
    // Belt to the gateway's braces: a PAN arriving by any other route is refused here, because
    // nothing downstream has anywhere to put one.
    ApiException e =
        assertThrows(
            ApiException.class, () -> TerminalService.refuseCardData("4242 4242 4242 4242"));
    assertThat(e.code(), is("TERMINAL_CARD_DATA_NOT_ACCEPTED"));
    assertThrows(ApiException.class, () -> TerminalService.refuseCardData("4242424242424242"));
    assertThrows(ApiException.class, () -> TerminalService.refuseCardData("4242-4242-4242-4242"));
    // And an ordinary label is not mistaken for one.
    TerminalService.refuseCardData("Till 2", "SN-90210", null, "");
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  private static String terminalRows(UUID tenantId) {
    return scalar(
        PG, "SELECT count(*) FROM payment.card_terminals WHERE tenant_id = '" + tenantId + "'");
  }

  @Test
  @DisplayName("A terminal retired twice is refused the second time and keeps the first reason")
  void aRetiredTerminalIsNotRetiredAgain() {
    Terminals.Terminal t = aTerminal("till");
    svc.retire(tenant, t.id(), "cracked");

    ApiException e = assertThrows(ApiException.class, () -> svc.retire(tenant, t.id(), "other"));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("TERMINAL_ALREADY_RETIRED"));
    assertThat(
        scalar(PG, "SELECT retired_reason FROM payment.card_terminals WHERE id = '" + t.id() + "'"),
        is("cracked"));

    // Over HTTP, for a terminal of a business of its own.
    UUID biz = Ids.newId();
    Caller owner = Caller.owner(biz);
    Terminals.Terminal mine =
        svc.register(biz, store, "Till " + Ids.newId(), "SIMULATED", null, actor);
    String path = "/admin/payments/terminals/" + mine.id() + "/retire";
    assertThat(ItCalls.post(target, path, owner, "{\"reason\":\"first\"}").status(), is(200));
    Answer again = ItCalls.post(target, path, owner, "{\"reason\":\"second\"}");
    assertThat(again.body().toString(), again.status(), is(409));
    assertThat(again.code(), is("TERMINAL_ALREADY_RETIRED"));
    assertThat(
        scalar(
            PG, "SELECT retired_reason FROM payment.card_terminals WHERE id = '" + mine.id() + "'"),
        is("first"));
  }

  private static String retirement(UUID terminalId) {
    return scalar(
        PG,
        "SELECT status || '/' || coalesce(length(retired_reason)::text, 'none')"
            + " FROM payment.card_terminals WHERE id = '"
            + terminalId
            + "'");
  }

  @Test
  @DisplayName(
      "A retirement's reason has a limit that is kept, another business cannot retire our terminal,"
          + " and a manager held to another store cannot retire this store's")
  void aTerminalIsRetiredOnlyWithinItsLimitsByItsOwnBusinessAtItsOwnStore() {
    UUID biz = Ids.newId();
    UUID here = Ids.newId();
    UUID elsewhere = Ids.newId();
    Terminals.Terminal t = svc.register(biz, here, "Till " + Ids.newId(), "SIMULATED", null, actor);
    String path = "/admin/payments/terminals/" + t.id() + "/retire";
    assertThat(retirement(t.id()), is("ACTIVE/none"));

    // A reason longer than the limit (300) is refused as a whole, by name, and the terminal stays
    // in service.
    Answer tooLong =
        ItCalls.post(target, path, Caller.owner(biz), "{\"reason\":\"" + "x".repeat(301) + "\"}");
    assertThat(tooLong.body().toString(), tooLong.status(), is(400));
    assertThat(tooLong.code(), is("VALIDATION_FAILED"));
    assertThat("still in service, no reason kept", retirement(t.id()), is("ACTIVE/none"));

    // Another business: its owner and manager find no such terminal; the rest are refused as at
    // home.
    UUID rival = Ids.newId();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs =
          ItCalls.post(
              target, path, new Caller(rival, Ids.newId(), role), "{\"reason\":\"not yours\"}");
      assertThat(role + " " + theirs.body(), theirs.status(), is(404));
      assertThat(role, theirs.code(), is("TERMINAL_NOT_FOUND"));
    }
    for (UUID business : new UUID[] {biz, rival}) {
      for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
        assertThat(
            role,
            ItCalls.post(
                    target,
                    path,
                    new Caller(business, Ids.newId(), role),
                    "{\"reason\":\"not mine\"}")
                .status(),
            is(403));
      }
    }
    // A manager of ours held to another store does not retire this store's terminal.
    Answer wrongStore =
        ItCalls.post(
            target,
            path,
            Caller.heldTo(biz, "MANAGER", elsewhere),
            "{\"reason\":\"not my store\"}");
    assertThat(wrongStore.body().toString(), wrongStore.status(), is(403));
    assertThat(wrongStore.code(), is("STORE_ACCESS_DENIED"));
    assertThat("nothing moved", retirement(t.id()), is("ACTIVE/none"));

    // The manager held to this store does, with a reason of exactly the limit.
    Answer ok =
        ItCalls.post(
            target,
            path,
            Caller.heldTo(biz, "MANAGER", here),
            "{\"reason\":\"" + "y".repeat(300) + "\"}");
    assertThat(ok.body().toString(), ok.status(), is(200));
    assertThat(retirement(t.id()), is("RETIRED/300"));

    // The body is as optional as the reason: a terminal is retired with none.
    Terminals.Terminal other =
        svc.register(biz, here, "Till " + Ids.newId(), "SIMULATED", null, actor);
    Answer bare =
        ItCalls.post(
            target, "/admin/payments/terminals/" + other.id() + "/retire", Caller.owner(biz), "{}");
    assertThat(bare.body().toString(), bare.status(), is(200));
    assertThat(retirement(other.id()), is("RETIRED/none"));
  }

  @Test
  @DisplayName("Another business cannot refund, cancel or read an attempt it does not hold")
  void anAttemptOfAnotherBusinessIsNotFound() {
    var sale = take(aTerminal("till"), "20.00", Ids.newId().toString());
    UUID rival = Ids.newId();

    ApiException refund =
        assertThrows(
            ApiException.class,
            () ->
                svc.refund(
                    rival,
                    sale.id(),
                    new BigDecimal("1.00"),
                    actor,
                    Ids.newId().toString(),
                    "not ours"));
    assertThat(refund.status(), is(404));
    assertThat(refund.code(), is("TERMINAL_ATTEMPT_NOT_FOUND"));
    ApiException cancel = assertThrows(ApiException.class, () -> svc.cancel(rival, sale.id()));
    assertThat(cancel.status(), is(404));
    assertThat(cancel.code(), is("TERMINAL_ATTEMPT_NOT_FOUND"));

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      Answer read =
          ItCalls.get(
              target, "/payments/terminal/" + sale.id(), new Caller(rival, Ids.newId(), role));
      assertThat(role + " " + read.body(), read.status(), is(404));
      assertThat(role, read.code(), is("TERMINAL_ATTEMPT_NOT_FOUND"));
    }
    // And one nobody made is no more found by the business that asks.
    Answer none =
        ItCalls.get(
            target, "/payments/terminal/" + Ids.newId(), new Caller(tenant, actor, "CASHIER"));
    assertThat(none.status(), is(404));
    assertThat(none.code(), is("TERMINAL_ATTEMPT_NOT_FOUND"));
    // The sale is untouched: one attempt, no refund written.
    assertThat(svc.attemptsOf(tenant, sale.orderId()), hasSize(1));
  }

  @Test
  @DisplayName("An id that is not an id is 400 TERMINAL_ID_INVALID, and nothing is stored")
  void anIdThatIsNotAnIdIsRefused() {
    UUID biz = Ids.newId();
    Caller owner = Caller.owner(biz);
    Answer register =
        ItCalls.post(
            target,
            "/admin/payments/terminals",
            owner,
            "{\"storeId\":\"till-1\",\"label\":\"Till 1\",\"vendor\":\"SIMULATED\"}");
    assertThat(register.body().toString(), register.status(), is(400));
    assertThat(register.code(), is("TERMINAL_ID_INVALID"));
    assertThat(terminalRows(biz), is("0"));

    Answer sale =
        ItCalls.post(
            target,
            "/payments/terminal",
            new Caller(biz, Ids.newId(), "CASHIER"),
            "{\"terminalId\":\"abc\",\"orderId\":\""
                + Ids.newId()
                + "\",\"amount\":5.00,\"currency\":\"GBP\"}");
    assertThat(sale.body().toString(), sale.status(), is(400));
    assertThat(sale.code(), is("TERMINAL_ID_INVALID"));
    assertThat(
        scalar(
            PG, "SELECT count(*) FROM payment.terminal_payments WHERE tenant_id = '" + biz + "'"),
        is("0"));
  }

  @Test
  @DisplayName("A label of only spaces is refused, in the service and over HTTP")
  void aLabelOfOnlySpacesIsRefused() {
    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.register(tenant, store, "   ", "SIMULATED", null, actor));
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("TERMINAL_LABEL_REQUIRED"));

    UUID biz = Ids.newId();
    Answer http =
        ItCalls.post(
            target,
            "/admin/payments/terminals",
            Caller.owner(biz),
            "{\"storeId\":\"" + store + "\",\"label\":\"\\u2003\",\"vendor\":\"SIMULATED\"}");
    assertThat(http.body().toString(), http.status(), is(400));
    assertThat(http.code(), is("TERMINAL_LABEL_REQUIRED"));
    assertThat(terminalRows(biz), is("0"));
  }

  @Test
  @DisplayName("A vendor that is known but not deployed cannot be registered")
  void aVendorNotDeployedIsRefused() {
    UUID biz = Ids.newId();
    for (String vendor : new String[] {"ADYEN", "STRIPE_TERMINAL", "VERIFONE"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.register(biz, store, "Till Y " + Ids.newId(), vendor, null, actor));
      assertThat(vendor, e.status(), is(409));
      assertThat(vendor, e.code(), is("TERMINAL_VENDOR_UNAVAILABLE"));
    }
    Answer http =
        ItCalls.post(
            target,
            "/admin/payments/terminals",
            Caller.owner(biz),
            "{\"storeId\":\"" + store + "\",\"label\":\"Till Y\",\"vendor\":\"ADYEN\"}");
    assertThat(http.body().toString(), http.status(), is(409));
    assertThat(http.code(), is("TERMINAL_VENDOR_UNAVAILABLE"));
    assertThat(terminalRows(biz), is("0"));
  }

  @Test
  @DisplayName("The same key pressed at once makes one attempt row")
  void theSameKeyPressedAtOnceMakesOneAttemptRow() throws Exception {
    Terminals.Terminal t = aTerminal("till");
    UUID order = Ids.newId();
    String key = Ids.newId().toString();
    int n = 8;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  return svc.sale(
                      tenant, t.id(), order, new BigDecimal("12.50"), "GBP", actor, key);
                } catch (ApiException e) {
                  return e;
                }
              }));
    }
    ready.await();
    go.countDown();
    UUID attempt = null;
    for (Future<Object> f : futures) {
      Object r = f.get();
      if (r instanceof ApiException e) {
        assertThat(e.getMessage(), e.status(), is(409));
        assertThat(e.code(), is("TERMINAL_REQUEST_IN_FLIGHT"));
      } else {
        UUID id = ((Terminals.Attempt) r).id();
        if (attempt == null) attempt = id;
        assertThat("every answer is the one attempt", id, is(attempt));
      }
    }
    pool.shutdown();

    assertThat(svc.attemptsOf(tenant, order), hasSize(1));
  }
}
