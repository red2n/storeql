package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shopper cancelling their own order: allowed for an online order they placed that is still
 * PENDING with nothing paid, and for nothing else. Staff cancel as before. The service is driven
 * with the caller's context directly (the shared path filter does not yet admit a shopper's POST to
 * {@code /orders/{id}/cancel}, see the disabled HTTP test), against the real database.
 */
@HelidonTest
class ShopperCancelIT {

  private static final String T = "01a0a1cb-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1cb-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a1cb-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0a1cb-4444-7000-8000-000000000001";
  private static final String SHOPPER = "01a0a1cb-4444-7000-8000-000000000011";
  private static final String NEIGHBOUR = "01a0a1cb-4444-7000-8000-000000000012";

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US").with(OTHER_T, "USD", "US");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService orderService;

  private ReturnsRig rig;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    return rig;
  }

  /** The caller as the filter would describe a shopper: a login, no staff role, no stores. */
  private static TenantContext shopper(String login) {
    TenantContext ctx = mock(TenantContext.class);
    when(ctx.userId()).thenReturn(login == null ? null : Ids.parse(login));
    when(ctx.hasRole("CUSTOMER")).thenReturn(true);
    return ctx;
  }

  /** An online order placed by the shopper: PENDING, 5.00. */
  private String placedBy(String login, String channel) {
    var b =
        rig()
            .as("/orders", T, "POS".equals(channel) ? "MANAGER" : "CUSTOMER", login)
            .header("Idempotency-Key", Ids.newId().toString());
    Response r =
        b.post(
            Entity.entity(
                "{\"storeId\":\""
                    + STORE
                    + "\",\"channel\":\""
                    + channel
                    + "\",\"fulfilmentType\":\""
                    + ("POS".equals(channel) ? "INSTORE" : "PICKUP")
                    + "\",\"items\":[{\"variantId\":\""
                    + V_A
                    + "\",\"qty\":1,\"unitPrice\":5.00}],\"currency\":\"USD\"}",
                MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return com.storeql.test.Envelopes.parse(body).getJsonObject("data").getString("id");
  }

  private String statusOf(String order) {
    return rig().one("SELECT status FROM \"order\".orders WHERE id='" + order + "'");
  }

  private ApiException refused(String tenant, String order, TenantContext ctx) {
    return assertThrows(
        ApiException.class,
        () ->
            orderService.cancelOrder(Ids.parse(tenant), Ids.parse(order), "x", ctx.userId(), ctx));
  }

  @Test
  @DisplayName("A shopper cancels their own unpaid PENDING online order, naming themselves")
  void shopperCancelsTheirOwnUnpaidOrder() {
    String order = placedBy(SHOPPER, "ONLINE");
    var cancelled =
        orderService.cancelOrder(
            Ids.parse(T),
            Ids.parse(order),
            "changed my mind",
            Ids.parse(SHOPPER),
            shopper(SHOPPER));
    assertThat(cancelled.status(), is("CANCELLED"));
    assertThat(statusOf(order), is("CANCELLED"));
    assertThat(rig().events(order, "OrderCancelled"), is(1L));
    assertThat(
        rig()
            .one(
                "SELECT changed_by FROM \"order\".order_status_history WHERE order_id='"
                    + order
                    + "' AND to_status='CANCELLED'"),
        is(SHOPPER));
    // Once cancelled it is finished with.
    assertThat(refused(T, order, shopper(SHOPPER)).code(), is("ORDER_CANNOT_CANCEL"));
    assertThat(rig().events(order, "OrderCancelled"), is(1L));
  }

  @Test
  @DisplayName("Nobody else's order: another shopper, a guest and another business find none")
  void notSomeoneElsesOrder() {
    String order = placedBy(SHOPPER, "ONLINE");
    ApiException neighbour = refused(T, order, shopper(NEIGHBOUR));
    assertThat(neighbour.status(), is(404));
    assertThat(neighbour.code(), is("ORDER_NOT_FOUND"));
    assertThat(refused(T, order, shopper(null)).status(), is(404));
    // The right shopper, naming another business: no such order there.
    assertThat(refused(OTHER_T, order, shopper(SHOPPER)).status(), is(404));
    assertThat(statusOf(order), is("PENDING"));
    assertThat(rig().events(order, "OrderCancelled"), is(0L));
  }

  @Test
  @DisplayName("Not once anything is paid, not once it is confirmed or handed over")
  void notOncePaidOrPicked() {
    // Part paid: the order stays PENDING, but money has moved and the shop must refund it.
    String part = placedBy(SHOPPER, "ONLINE");
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(part), Ids.newId(), new BigDecimal("2.00"));
    ApiException partly = refused(T, part, shopper(SHOPPER));
    assertThat(partly.status(), is(409));
    assertThat(partly.code(), is("ORDER_CANCEL_PAID_NEEDS_STAFF"));
    assertThat(statusOf(part), is("PENDING"));

    // Paid in full: CONFIRMED.
    String paid = placedBy(SHOPPER, "ONLINE");
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(paid), Ids.newId(), new BigDecimal("5.00"));
    assertThat(statusOf(paid), is("CONFIRMED"));
    assertThat(refused(T, paid, shopper(SHOPPER)).code(), is("ORDER_CANNOT_CANCEL"));

    // Handed over: FULFILLED.
    String done = placedBy(SHOPPER, "ONLINE");
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(done), Ids.newId(), new BigDecimal("5.00"));
    orderService.fulfillOrder(Ids.parse(T), Ids.parse(done), Ids.parse(MANAGER));
    assertThat(statusOf(done), is("FULFILLED"));
    ApiException handed = refused(T, done, shopper(SHOPPER));
    assertThat(handed.status(), is(409));
    assertThat(handed.code(), is("ORDER_CANNOT_CANCEL"));

    for (String o : new String[] {part, paid, done}) {
      assertThat(rig().events(o, "OrderCancelled"), is(0L));
    }
  }

  @Test
  @DisplayName("A till sale is not the shopper's to cancel")
  void notATillSale() {
    String sale = placedBy(SHOPPER, "POS");
    // Even under the shopper's own login: a till sale is placed by staff and has no shopper.
    assertThat(refused(T, sale, shopper(SHOPPER)).status(), is(404));
    assertThat(statusOf(sale), is("PENDING"));
  }

  @Test
  @DisplayName("Staff cancel as they always did, with no reason and no body")
  void staffStillCancelWithNoBody() {
    String order = placedBy(SHOPPER, "ONLINE");
    Response r =
        rig()
            .as("/orders/" + order + "/cancel", T, "MANAGER", MANAGER)
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    assertThat(statusOf(order), is("CANCELLED"));
    assertThat(
        rig()
            .one(
                "SELECT reason FROM \"order\".order_status_history WHERE order_id='"
                    + order
                    + "' AND to_status='CANCELLED'"),
        is(nullValue()));
  }

  @Test
  @DisplayName("Over HTTP a shopper cancels their own order and is refused another's")
  void overHttp() {
    String mine = placedBy(SHOPPER, "ONLINE");
    Response ok =
        rig()
            .as("/orders/" + mine + "/cancel", T, "CUSTOMER", SHOPPER)
            .post(Entity.entity("{\"reason\":\"changed my mind\"}", MediaType.APPLICATION_JSON));
    JsonObject data = ReturnsRig.data(ok, 200);
    assertThat(data.getString("status"), is("CANCELLED"));

    String theirs = placedBy(NEIGHBOUR, "ONLINE");
    Response no =
        rig()
            .as("/orders/" + theirs + "/cancel", T, "CUSTOMER", SHOPPER)
            .post(Entity.entity("", MediaType.APPLICATION_JSON));
    assertThat(no.getStatus(), is(404));
  }
}
