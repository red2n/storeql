package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PermissionGate;
import com.storeql.test.PostgresSupport;
import com.storeql.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
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
 * The back-office refund, {@code POST /payments/by-order/{orderId}/refunds} (catalogue RFD-03 to
 * RFD-08): an unknown method is refused; a cashier, storekeeper or shopper cannot; a manager whose
 * role has sales.refund narrowed out is refused by name; concurrent refunds on one tender cannot
 * together exceed what was captured; a retry with the same Idempotency-Key refunds once; and
 * another business's staff of every role, naming our ids, find nothing and move nothing.
 *
 * <p>And a refund is the store's where its tender was taken: a manager held to other stores is
 * {@code 403 STORE_ACCESS_DENIED} (after another business's {@code 404}, before a replay is
 * answered or anything is written), one held to that store or to none refunds it.
 */
@HelidonTest
class BackOfficeRefundIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  @Inject WebTarget target;
  @Inject PaymentRepository payments;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private record Sale(UUID tenant, UUID order, UUID payment) {}

  private Sale sale(String amount) {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenant,
            order,
            new BigDecimal(amount),
            PaymentTender.METHOD_CARD,
            "auth-1",
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            Ids.newId()),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenant, id, "{}"));
    return new Sale(tenant, order, id);
  }

  private static String refundBody(Sale s, String amount, String method) {
    return "{\"paymentId\":\""
        + s.payment()
        + "\",\"amount\":"
        + amount
        + ",\"method\":\""
        + method
        + "\",\"reason\":\"damaged\"}";
  }

  private Answer refund(Sale s, Caller who, String amount, String method, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments/by-order/" + s.order() + "/refunds",
        who,
        refundBody(s, amount, method),
        key);
  }

  private static Caller manager(UUID tenant) {
    return new Caller(tenant, Ids.newId(), "MANAGER");
  }

  private String refundRows(Sale s) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM payment.refund_tenders WHERE tenant_id = '"
            + s.tenant()
            + "' AND payment_id = '"
            + s.payment()
            + "'");
  }

  private String refundedTotal(Sale s) {
    return Envelopes.scalar(
        PG,
        "SELECT COALESCE(SUM(amount), 0) FROM payment.refund_tenders WHERE tenant_id = '"
            + s.tenant()
            + "' AND payment_id = '"
            + s.payment()
            + "'");
  }

  private String refundEvents(Sale s) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
            + s.tenant()
            + "' AND event_type = 'PaymentRefunded'");
  }

  private static void assertMoney(String actual, String expected) {
    assertThat(actual, new BigDecimal(actual).compareTo(new BigDecimal(expected)), is(0));
  }

  @Test
  @DisplayName("RFD-03: an unknown refund method is 400 PAYMENT_INVALID_METHOD and moves nothing")
  void anUnknownMethodIsRefused() {
    Sale s = sale("20.00");

    Answer a = refund(s, manager(s.tenant()), "5.00", "BITCOIN", Ids.newId().toString());

    assertThat(a.body().toString(), a.status(), is(400));
    assertThat(a.code(), is("PAYMENT_INVALID_METHOD"));
    assertThat(refundRows(s), is("0"));
    assertThat(refundEvents(s), is("0"));
    // The same request with a known method is taken, so the refusal was the method's.
    assertThat(refund(s, manager(s.tenant()), "5.00", "CARD", null).status(), is(201));
  }

  @Test
  @DisplayName("RFD-04: a cashier, storekeeper or shopper cannot record a refund")
  void nobodyBelowManagementCanRefund() {
    Sale s = sale("20.00");

    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Answer a = refund(s, new Caller(s.tenant(), Ids.newId(), role), "5.00", "CARD", null);
      assertThat(role + " " + a.body(), a.status(), is(403));
    }
    assertThat(refundRows(s), is("0"));
    assertThat(refundEvents(s), is("0"));
    // Management still can.
    assertThat(
        refund(s, new Caller(s.tenant(), Ids.newId(), "OWNER"), "5.00", "CARD", null).status(),
        is(201));
  }

  @Test
  @DisplayName("RFD-05: a manager with sales.refund narrowed out is 403, named, and moves nothing")
  void aManagerNarrowedOutOfRefundsIsRefusedByName() {
    Sale s = sale("20.00");
    PermissionGate gate = new PermissionGate(target, s.tenant().toString(), Ids.newId().toString());

    try (Response narrowed =
        gate.send(
            "POST",
            "/payments/by-order/" + s.order() + "/refunds",
            refundBody(s, "5.00", "CARD"),
            "MANAGER",
            "-")) {
      String body = narrowed.readEntity(String.class);
      assertThat(body, narrowed.getStatus(), is(403));
      assertThat(
          body, body.contains("PERMISSION_DENIED") && body.contains("sales.refund"), is(true));
    }
    assertThat(refundRows(s), is("0"));
    assertThat(refundEvents(s), is("0"));
    // Holding it, the same manager's refund goes through.
    try (Response held =
        gate.send(
            "POST",
            "/payments/by-order/" + s.order() + "/refunds",
            refundBody(s, "5.00", "CARD"),
            "MANAGER",
            "sales.refund")) {
      assertThat(held.getStatus(), is(201));
    }
    assertThat(refundRows(s), is("1"));
  }

  /** Fires {@code n} refunds of {@code amount} at once, each with its own key; the answers. */
  private List<Answer> concurrently(Sale s, int n, String amount, boolean sameKey)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch go = new CountDownLatch(1);
    String shared = Ids.newId().toString();
    List<Future<Answer>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Caller who = manager(s.tenant());
      String key = sameKey ? shared : Ids.newId().toString();
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return refund(s, who, amount, "CARD", key);
              }));
    }
    ready.await();
    go.countDown();
    List<Answer> out = new ArrayList<>();
    for (Future<Answer> f : futures) out.add(f.get());
    pool.shutdown();
    return out;
  }

  @Test
  @DisplayName("RFD-06: two concurrent refunds of 15 on a 20 tender: exactly one succeeds")
  void twoConcurrentRefundsCannotExceedTheTender() throws Exception {
    for (int round = 0; round < 3; round++) {
      Sale s = sale("20.00");

      List<Answer> answers = concurrently(s, 2, "15.00", false);

      List<Integer> statuses = answers.stream().map(Answer::status).toList();
      assertThat(answers.toString(), statuses, containsInAnyOrder(201, 409));
      Answer refused = answers.stream().filter(a -> a.status() == 409).findFirst().orElseThrow();
      assertThat(refused.code(), is("REFUND_EXCEEDS_PAYMENT"));
      assertMoney(refundedTotal(s), "15.00");
      assertThat(refundRows(s), is("1"));
      assertThat("one refund announced", refundEvents(s), is("1"));
    }
  }

  @Test
  @DisplayName("RFD-06: five concurrent refunds of 5 on a 20 tender refund exactly 20, no more")
  void manyConcurrentRefundsStopAtTheTender() throws Exception {
    Sale s = sale("20.00");

    List<Answer> answers = concurrently(s, 5, "5.00", false);

    assertThat(answers.stream().filter(a -> a.status() == 201).count(), is(4L));
    assertThat(answers.stream().filter(a -> a.status() == 409).count(), is(1L));
    assertMoney(refundedTotal(s), "20.00");
    assertThat(refundRows(s), is("4"));
    assertThat(refundEvents(s), is("4"));
    // Nothing is left to refund.
    Answer more = refund(s, manager(s.tenant()), "0.01", "CARD", null);
    assertThat(more.code(), is("REFUND_EXCEEDS_PAYMENT"));
  }

  @Test
  @DisplayName("RFD-07: retrying the same Idempotency-Key refunds once and returns the same refund")
  void theSameKeyRefundsOnce() {
    Sale s = sale("20.00");
    String key = Ids.newId().toString();
    Caller me = manager(s.tenant());

    Answer first = refund(s, me, "8.00", "CARD", key);
    Answer retry = refund(s, me, "8.00", "CARD", key);

    assertThat(first.body().toString(), first.status(), is(201));
    assertThat(retry.body().toString(), retry.status(), is(201));
    assertThat(retry.data().getString("id"), is(first.data().getString("id")));
    assertThat(refundRows(s), is("1"));
    assertMoney(refundedTotal(s), "8.00");
    assertThat(refundEvents(s), is("1"));
    // Another key is another refund.
    assertThat(refund(s, me, "8.00", "CARD", Ids.newId().toString()).status(), is(201));
    assertMoney(refundedTotal(s), "16.00");
  }

  @Test
  @DisplayName("RFD-07: the same key arriving twice at once still refunds once")
  void theSameKeyAtTheSameTimeRefundsOnce() throws Exception {
    Sale s = sale("20.00");

    List<Answer> answers = concurrently(s, 2, "8.00", true);

    for (Answer a : answers) assertThat(a.body().toString(), a.status(), is(201));
    assertThat(answers.get(0).data().getString("id"), is(answers.get(1).data().getString("id")));
    assertThat(refundRows(s), is("1"));
    assertThat(refundEvents(s), is("1"));
  }

  @Test
  @DisplayName("RFD-08: another business's staff of every role, naming our ids, refund nothing")
  void anotherBusinessCannotRefundOurPayment() {
    Sale s = sale("20.00");
    UUID stranger = Ids.newId();

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer a = refund(s, new Caller(stranger, Ids.newId(), role), "5.00", "CARD", null);
      assertThat(role + " " + a.body(), a.status(), is(404));
      assertThat(a.code(), is("PAYMENT_NOT_FOUND"));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Answer a = refund(s, new Caller(stranger, Ids.newId(), role), "5.00", "CARD", null);
      assertThat(role + " " + a.body(), a.status(), is(403));
    }
    assertThat(refundRows(s), is("0"));
    assertThat(refundEvents(s), is("0"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM payment.refund_tenders WHERE tenant_id = '" + stranger + "'"),
        is("0"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM payment.outbox WHERE tenant_id = '" + stranger + "'"),
        is("0"));

    // Nor can they read what was refunded or paid: the tender is not theirs, the list is empty.
    assertThat(refund(s, manager(s.tenant()), "5.00", "CARD", null).status(), is(201));
    Caller theirs = new Caller(stranger, Ids.newId(), "MANAGER");
    assertThat(ItCalls.get(target, "/payments/" + s.payment(), theirs).status(), is(404));
    Answer list = ItCalls.get(target, "/payments/by-order/" + s.order() + "/refunds", theirs);
    assertThat(list.status(), is(200));
    assertThat(list.list().size(), is(0));
    // The refund made by us stands, once.
    assertThat(refundRows(s), is("1"));
  }

  // ── a refund is the store's where its tender was taken ──────────────────────

  /** A cash sale of {@code amount} taken at {@code store} (null for a tender taken at no store). */
  private Sale saleAt(UUID tenant, UUID store, String amount) {
    UUID order = Ids.newId();
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenant,
            order,
            new BigDecimal(amount),
            PaymentTender.METHOD_CASH,
            null,
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            store),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenant, id, "{}"));
    return new Sale(tenant, order, id);
  }

  private static Caller managerAt(UUID tenant, UUID... stores) {
    return Caller.heldTo(tenant, "MANAGER", stores);
  }

  private String refundStores(Sale s) {
    return Envelopes.scalar(
        PG,
        "SELECT coalesce(string_agg(DISTINCT coalesce(store_id::text, 'none'), ','), '-')"
            + " FROM payment.refund_tenders WHERE tenant_id = '"
            + s.tenant()
            + "' AND payment_id = '"
            + s.payment()
            + "'");
  }

  @Test
  @DisplayName(
      "A manager held to another store cannot refund a tender taken at this one: 403"
          + " STORE_ACCESS_DENIED, nothing written, announced or read back")
  void aManagerHeldElsewhereCannotRefundThisStoresTender() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    Sale s = saleAt(tenant, storeA, "30.00");

    for (String method : new String[] {"CASH", "CARD", "UPI"}) {
      Answer refused = refund(s, managerAt(tenant, storeB), "5.00", method, Ids.newId().toString());
      assertThat(method + " " + refused.body(), refused.status(), is(403));
      assertThat(refused.code(), is("STORE_ACCESS_DENIED"));
    }
    // Held to two other stores: still not this one.
    assertThat(
        refund(s, managerAt(tenant, storeB, Ids.newId()), "5.00", "CASH", null).code(),
        is("STORE_ACCESS_DENIED"));
    // The store comes before the order named and before the cap: neither is told to them.
    Answer wrongOrder =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + Ids.newId() + "/refunds",
            managerAt(tenant, storeB),
            refundBody(s, "5.00", "CASH"),
            Ids.newId().toString());
    assertThat(wrongOrder.code(), is("STORE_ACCESS_DENIED"));
    assertThat(
        refund(s, managerAt(tenant, storeB), "31.00", "CASH", null).code(),
        is("STORE_ACCESS_DENIED"));
    assertThat("store A's drawer is not moved", refundRows(s), is("0"));
    assertThat("and its order is not told", refundEvents(s), is("0"));

    // Held to the store the tender was taken at — alone or among others — or to none, it is theirs.
    String key = Ids.newId().toString();
    Answer own = refund(s, managerAt(tenant, storeA), "5.00", "CASH", key);
    assertThat(own.body().toString(), own.status(), is(201));
    assertThat(
        refund(s, managerAt(tenant, storeB, storeA), "5.00", "CASH", null).status(), is(201));
    assertThat(refund(s, manager(tenant), "5.00", "CASH", null).status(), is(201));
    assertThat(refund(s, Caller.owner(tenant), "5.00", "CASH", null).status(), is(201));
    assertThat(refundRows(s), is("4"));
    assertMoney(refundedTotal(s), "20.00");
    assertThat("each is the store's where the tender was taken", refundStores(s), is(storeA + ""));

    // A refund already made is not replayed to a manager held elsewhere who has its key.
    Answer replayed = refund(s, managerAt(tenant, storeB), "5.00", "CASH", key);
    assertThat(replayed.body().toString(), replayed.status(), is(403));
    assertThat(replayed.code(), is("STORE_ACCESS_DENIED"));
    assertThat(replayed.body().toString().contains(own.data().getString("id")), is(false));
    // To its own maker it is.
    Answer again = refund(s, managerAt(tenant, storeA), "5.00", "CASH", key);
    assertThat(again.status(), is(201));
    assertThat(again.data().getString("id"), is(own.data().getString("id")));
    assertThat(refundRows(s), is("4"));
    assertThat(refundEvents(s), is("4"));
  }

  @Test
  @DisplayName(
      "Another business's staff of every role, held to our store by name, find no tender: 404"
          + " before any store is judged, and nothing moves")
  void anotherBusinessNamingOurStoreFindsNothing() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    Sale s = saleAt(tenant, storeA, "30.00");
    UUID stranger = Ids.newId();

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      // Their own token, naming our store as one of theirs and as none.
      for (Caller who :
          new Caller[] {
            new Caller(stranger, Ids.newId(), role),
            new Caller(stranger, Ids.newId(), role, storeA),
            Caller.heldTo(stranger, role, Ids.newId())
          }) {
        Answer a = refund(s, who, "5.00", "CASH", Ids.newId().toString());
        assertThat(role + " " + a.body(), a.status(), is(404));
        assertThat(a.code(), is("PAYMENT_NOT_FOUND"));
      }
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Answer a = refund(s, new Caller(stranger, Ids.newId(), role, storeA), "5.00", "CASH", null);
      assertThat(role + " " + a.body(), a.status(), is(403));
    }
    assertThat(refundRows(s), is("0"));
    assertThat(refundEvents(s), is("0"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM payment.refund_tenders WHERE tenant_id = '" + stranger + "'"),
        is("0"));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM payment.outbox WHERE tenant_id = '" + stranger + "'"),
        is("0"));
  }

  @Test
  @DisplayName(
      "A tender taken at no store is the whole business's: a manager held to stores does not"
          + " refund it, one held to none does")
  void aTenderWithNoStoreIsRefundedByACallerHeldToNone() {
    UUID tenant = Ids.newId();
    Sale s = saleAt(tenant, null, "30.00");

    Answer held = refund(s, managerAt(tenant, Ids.newId()), "5.00", "CASH", null);
    assertThat(held.body().toString(), held.status(), is(403));
    assertThat(held.code(), is("STORE_ACCESS_DENIED"));
    assertThat(refundRows(s), is("0"));
    assertThat(refundEvents(s), is("0"));

    assertThat(refund(s, manager(tenant), "5.00", "CASH", null).status(), is(201));
    assertThat(refundStores(s), is("none"));
  }

  @Test
  @DisplayName(
      "A key that already made a refund of one tender is refused for another: 409"
          + " IDEMPOTENCY_KEY_REUSED, never answered with the first tender's refund")
  void aKeyUsedForAnotherTenderIsRefused() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    Sale atA = saleAt(tenant, storeA, "30.00");
    Sale atB = saleAt(tenant, storeB, "30.00");
    String key = Ids.newId().toString();
    Answer first = refund(atA, managerAt(tenant, storeA), "5.00", "CASH", key);
    assertThat(first.body().toString(), first.status(), is(201));

    // Store B's manager, with store A's key, on their own tender: not store A's refund.
    Answer reused = refund(atB, managerAt(tenant, storeB), "5.00", "CASH", key);

    assertThat(reused.body().toString(), reused.status(), is(409));
    assertThat(reused.code(), is("IDEMPOTENCY_KEY_REUSED"));
    assertThat(reused.body().toString().contains(first.data().getString("id")), is(false));
    assertThat(refundRows(atB), is("0"));
    assertThat(refundRows(atA), is("1"));
    // Under a key of its own the same refund is taken, so the refusal was the key's.
    assertThat(
        refund(atB, managerAt(tenant, storeB), "5.00", "CASH", Ids.newId().toString()).status(),
        is(201));
  }

  @Test
  @DisplayName("A payment of another order of the same business is not refunded under this one")
  void aPaymentOfAnotherOrderIsNotRefundedUnderThisOne() {
    Sale a = sale("20.00");
    // A second sale in the SAME business, on another order.
    UUID otherOrder = Ids.newId();
    UUID otherPayment = Ids.newId();
    payments.createTender(
        new PaymentTender(
            otherPayment,
            a.tenant(),
            otherOrder,
            new BigDecimal("20.00"),
            PaymentTender.METHOD_CARD,
            "auth-2",
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            Ids.newId()),
        new OutboxRow(
            "PaymentCaptured", "storeql.payment.payment-captured", a.tenant(), otherPayment, "{}"));
    Sale b = new Sale(a.tenant(), otherOrder, otherPayment);
    String eventsBefore = refundEvents(a);

    // Order A's URL, payment B's id.
    Answer refused =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + a.order() + "/refunds",
            manager(a.tenant()),
            refundBody(b, "1.00", "CARD"),
            Ids.newId().toString());

    assertThat(refused.body().toString(), refused.status(), is(409));
    assertThat(refused.code(), is("PAYMENT_ORDER_MISMATCH"));
    assertThat(refundRows(a), is("0"));
    assertThat(refundRows(b), is("0"));
    assertThat(refundEvents(a), is(eventsBefore));
    // Under its own order the same payment is refunded, so the refusal was the mismatch.
    Answer own =
        ItCalls.call(
            target,
            "POST",
            "/payments/by-order/" + b.order() + "/refunds",
            manager(a.tenant()),
            refundBody(b, "1.00", "CARD"),
            Ids.newId().toString());
    assertThat(own.body().toString(), own.status(), is(201));
  }

  @Test
  @DisplayName("The same tender key at the same time takes one tender: the rest replay or retry")
  void theSameTenderKeyAtTheSameTimeTakesOneTender() throws Exception {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    String key = Ids.newId().toString();
    int n = 8;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      UUID id = Ids.newId();
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  return payments.createTender(
                      new PaymentTender(
                          id,
                          tenant,
                          order,
                          new BigDecimal("5.00"),
                          PaymentTender.METHOD_CARD,
                          "auth-race",
                          key,
                          PaymentTender.STATUS_CAPTURED,
                          null,
                          Instant.now(),
                          Ids.newId()),
                      new OutboxRow(
                          "PaymentCaptured", "storeql.payment.payment-captured", tenant, id, "{}"));
                } catch (ApiException e) {
                  return e;
                }
              }));
    }
    ready.await();
    go.countDown();
    int taken = 0;
    for (Future<Object> f : futures) {
      Object r = f.get();
      if (r instanceof ApiException e) {
        assertThat(e.getMessage(), e.status(), is(409));
        assertThat(e.code(), is("IDEMPOTENCY_CONFLICT"));
      } else {
        taken++;
      }
    }
    pool.shutdown();

    assertThat(taken >= 1, is(true));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM payment.payment_tenders WHERE tenant_id = '"
                + tenant
                + "' AND order_id = '"
                + order
                + "'"),
        is("1"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
                + tenant
                + "' AND event_type = 'PaymentCaptured'"),
        is("1"));
  }
}
