package com.storeql.payment;

import static com.storeql.payment.ItCalls.call;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.dto.Dtos.RecordRefundRequest;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.PaymentService;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * One payment for a delivery checkout split across shops (order orchestration), against real
 * Postgres with order-svc a stub: a tender per part, each announcing its PaymentCaptured so its
 * order confirms; a retry replays them; the amount is the checkout's total; the checkout is the
 * shopper's own; every part must be awaiting payment; a part is never paid alone; and refunding one
 * part leaves the other.
 */
@HelidonTest
class GroupPaymentIT {

  private static final UUID T = Ids.newId();
  private static final UUID SHOPPER = Ids.newId();
  private static final UUID LEEDS = Ids.newId();
  private static final UUID YORK = Ids.newId();

  private static final PostgresSupport PG;
  private static final JsonStub ORDERS;

  static {
    PG = PostgresSupport.start();
    ORDERS = JsonStub.start("order-svc");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "payment");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject PaymentService service;
  @Inject PaymentRepository repo;

  @AfterAll
  static void stop() {
    ORDERS.close();
    System.clearProperty("storeql.clients.order-svc.url");
    PG.stop();
  }

  /** A checkout of two parts, Leeds 3.37 and York 1.68, as order-svc would answer for it. */
  private record Checkout(UUID id, UUID leeds, UUID york) {}

  private static Checkout checkout(String yorkStatus) {
    Checkout c = new Checkout(Ids.newId(), Ids.newId(), Ids.newId());
    ORDERS.on(
        "GET",
        "/order-groups/" + c.id(),
        200,
        "{\"data\":{\"id\":\""
            + c.id()
            + "\",\"loginId\":\""
            + SHOPPER
            + "\",\"total\":5.05,\"currency\":\"GBP\",\"parts\":["
            + part(c.leeds(), LEEDS, "PENDING", "3.37")
            + ","
            + part(c.york(), YORK, yorkStatus, "1.68")
            + "]}}");
    ORDERS.on(
        "GET",
        "/orders/" + c.leeds(),
        200,
        "{\"data\":{\"id\":\""
            + c.leeds()
            + "\",\"loginId\":\""
            + SHOPPER
            + "\",\"channel\":\"ONLINE\",\"status\":\"PENDING\",\"total\":3.37,\"storeId\":\""
            + LEEDS
            + "\",\"currency\":\"GBP\",\"group\":{\"id\":\""
            + c.id()
            + "\"}}}");
    return c;
  }

  private static String part(UUID order, UUID store, String status, String total) {
    return "{\"orderId\":\""
        + order
        + "\",\"storeId\":\""
        + store
        + "\",\"status\":\""
        + status
        + "\",\"total\":"
        + total
        + "}";
  }

  private Answer pay(String json, UUID who, String key) {
    return call(target, "POST", "/payments/online", new Caller(T, who, "CUSTOMER"), json, key);
  }

  private static String forCheckout(UUID group, String amount) {
    return "{\"groupId\":\"" + group + "\",\"amount\":" + amount + ",\"method\":\"CARD\"}";
  }

  private static String captured(UUID order) {
    return scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE event_type = 'PaymentCaptured'"
            + " AND payload LIKE '%\"orderId\":\""
            + order
            + "\"%'");
  }

  @Test
  void oneCheckoutPaymentTakesATenderPerPartEachAnnouncedForItsOrder() {
    Checkout c = checkout("PENDING");
    Answer a = pay(forCheckout(c.id(), "5.05"), SHOPPER, Ids.newId().toString());
    assertThat(a.body().toString(), a.status(), is(201));
    JsonArray tenders = a.data().getJsonArray("tenders");
    assertThat(tenders, hasSize(2));
    assertThat(tenders.getJsonObject(0).getString("orderId"), is(c.leeds().toString()));
    assertThat(
        tenders.getJsonObject(0).getJsonNumber("amount").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("3.37")));
    assertThat(tenders.getJsonObject(1).getString("orderId"), is(c.york().toString()));
    assertThat(
        tenders.getJsonObject(1).getJsonNumber("amount").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("1.68")));
    assertThat(captured(c.leeds()), is("1"));
    assertThat(captured(c.york()), is("1"));
    assertThat(repo.findTendersByOrder(T, c.york()).get(0).storeId(), is(YORK));
  }

  @Test
  void aRetriedCheckoutPaymentReplaysItsTenders() {
    Checkout c = checkout("PENDING");
    String key = Ids.newId().toString();
    Answer first = pay(forCheckout(c.id(), "5.05"), SHOPPER, key);
    Answer again = pay(forCheckout(c.id(), "5.05"), SHOPPER, key);
    assertThat(again.status(), is(201));
    assertThat(
        again.data().getJsonArray("tenders").getJsonObject(1).getString("id"),
        is(first.data().getJsonArray("tenders").getJsonObject(1).getString("id")));
    assertThat(repo.findTendersByOrder(T, c.leeds()), hasSize(1));
    assertThat(captured(c.york()), is("1"));
  }

  @Test
  void theCheckoutIsPaidInFullByItsOwnShopperWithEveryPartAwaitingPayment() {
    Checkout c = checkout("PENDING");
    assertThat(
        pay(forCheckout(c.id(), "5.00"), SHOPPER, Ids.newId().toString()).code(),
        is("PAYMENT_GROUP_AMOUNT_MISMATCH"));
    Answer stranger = pay(forCheckout(c.id(), "5.05"), Ids.newId(), Ids.newId().toString());
    assertThat(stranger.status(), is(404));
    assertThat(stranger.code(), is("PAYMENT_GROUP_NOT_FOUND"));
    Checkout confirmed = checkout("CONFIRMED");
    Answer late = pay(forCheckout(confirmed.id(), "5.05"), SHOPPER, Ids.newId().toString());
    assertThat(late.status(), is(409));
    assertThat(late.code(), is("PAYMENT_ORDER_NOT_PAYABLE"));
    assertThat(repo.findTendersByOrder(T, c.leeds()), hasSize(0));
  }

  @Test
  void aPartIsNeverPaidAloneAndNamingBothIsRefused() {
    Checkout c = checkout("PENDING");
    Answer alone =
        pay(
            "{\"orderId\":\"" + c.leeds() + "\",\"amount\":3.37,\"method\":\"CARD\"}",
            SHOPPER,
            Ids.newId().toString());
    assertThat(alone.status(), is(409));
    assertThat(alone.code(), is("PAYMENT_ORDER_IN_GROUP"));
    Answer both =
        pay(
            "{\"orderId\":\""
                + c.leeds()
                + "\",\"groupId\":\""
                + c.id()
                + "\",\"amount\":5.05,\"method\":\"CARD\"}",
            SHOPPER,
            Ids.newId().toString());
    assertThat(both.status(), is(400));
  }

  @Test
  void refundingOnePartLeavesTheOther() {
    Checkout c = checkout("PENDING");
    Answer paid = pay(forCheckout(c.id(), "5.05"), SHOPPER, Ids.newId().toString());
    UUID leedsTender =
        Ids.parse(paid.data().getJsonArray("tenders").getJsonObject(0).getString("id"));
    service.recordRefund(
        T,
        c.leeds(),
        new RecordRefundRequest(
            leedsTender.toString(), new BigDecimal("3.37"), "CARD", null, null, "damaged"),
        Ids.newId().toString());
    assertThat(repo.findRefundsByOrder(T, c.leeds()), hasSize(1));
    assertThat(repo.findRefundsByOrder(T, c.york()), hasSize(0));
  }
}
