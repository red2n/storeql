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
}
