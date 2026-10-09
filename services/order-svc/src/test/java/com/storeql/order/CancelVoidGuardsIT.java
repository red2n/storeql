package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cancelling and voiding (catalogue VOID-10, 11, 12, 15): an owner cancels a pending order, a
 * manager cancels with no body at all, a fulfilled order cannot be cancelled, and a void that
 * already happened is refused the second time, unless it is a retry under the same key.
 */
@HelidonTest
class CancelVoidGuardsIT {

  private static final String T = "01a0a1cd-1111-7000-8000-000000000001";
  private static final String STORE = "01a0a1cd-2222-7000-8000-00000000000a";
  private static final String OWNER = "01a0a1cd-4444-7000-8000-000000000001";
  private static final String MANAGER = "01a0a1cd-4444-7000-8000-000000000002";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US");
    PRICING = ReturnsRig.pricing(new AtomicBoolean(false));
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService orderService;

  private ReturnsRig rig;

  @AfterAll
  static void stop() {
    System.clearProperty("storeql.order.pricing.enforce");
    PRICING.close();
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    return rig;
  }

  /** An online pickup order for two, PENDING and unpaid: the order id and its total. */
  private JsonObject onlineOrder() {
    Response r =
        rig()
            .post(
                "/orders",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\","
                    + "\"items\":[{\"variantId\":\""
                    + V_A
                    + "\",\"qty\":2}]}",
                T,
                "OWNER",
                OWNER,
                Ids.newId().toString());
    return data(r, 201);
  }

  private String statusOf(String order) {
    return rig().one("SELECT status FROM \"order\".orders WHERE id='" + order + "'");
  }

  private Response cancel(String order, String json, String role, String user) {
    return rig()
        .as("/orders/" + order + "/cancel", T, role, user)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("VOID-11: an owner cancels a pending order, and OrderCancelled is announced")
  void ownerCancelsAPendingOrder() {
    String order = onlineOrder().getString("id");
    Response r = cancel(order, "{\"reason\":\"customer changed their mind\"}", "OWNER", OWNER);
    JsonObject cancelled = data(r, 200);
    assertThat(cancelled.getString("status"), is("CANCELLED"));
    assertThat(statusOf(order), is("CANCELLED"));
    assertThat(rig().events(order, "OrderCancelled"), is(1L));
    assertThat(
        rig()
            .one(
                "SELECT reason FROM \"order\".order_status_history WHERE order_id='"
                    + order
                    + "' AND to_status='CANCELLED'"),
        is("customer changed their mind"));
    assertThat(rig().event(order, "OrderCancelled").getString("orderId"), is(order));
  }

  @Test
  @DisplayName(
      "VOID-17: a cancel made at a till names the drawer on OrderCancelled; a bad id is a 400")
  void aCancelNamesTheDrawer() {
    String order = onlineOrder().getString("id");
    String other = onlineOrder().getString("id");
    String drawer = Ids.newId().toString();

    Response bad =
        cancel(
            other,
            "{\"reason\":\"held sale\",\"tillSessionId\":\"not-an-id\"}",
            "MANAGER",
            MANAGER);
    assertThat(bad.getStatus(), is(400));
    assertThat(statusOf(other), is("PENDING"));

    JsonObject cancelled =
        data(
            cancel(
                order,
                "{\"reason\":\"held sale\",\"tillSessionId\":\"" + drawer + "\"}",
                "MANAGER",
                MANAGER),
            200);
    assertThat(cancelled.getString("status"), is("CANCELLED"));

    assertThat(rig().event(order, "OrderCancelled").getString("tillSessionId"), is(drawer));
    // A cancel that names none says none.
    cancel(other, "{\"reason\":\"no drawer\"}", "MANAGER", MANAGER);
    assertThat(rig().event(other, "OrderCancelled").containsKey("tillSessionId"), is(false));
  }

  @Test
  @DisplayName("VOID-12: cancelling with no body at all is allowed and records no reason")
  void cancelWithNoBody() {
    JsonObject placed = onlineOrder();
    String order = placed.getString("id");
    orderService.handlePaymentCaptured(
        Ids.parse(T),
        Ids.parse(order),
        Ids.newId(),
        placed.getJsonNumber("total").bigDecimalValue());
    assertThat(statusOf(order), is("CONFIRMED"));

    Response r = cancel(order, "", "MANAGER", MANAGER);
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    assertThat(statusOf(order), is("CANCELLED"));
    assertThat(
        rig()
            .one(
                "SELECT reason FROM \"order\".order_status_history WHERE order_id='"
                    + order
                    + "' AND to_status='CANCELLED'"),
        is(nullValue()));
    // A body that IS sent must carry a reason.
    String other = onlineOrder().getString("id");
    assertThat(cancel(other, "{\"reason\":\"\"}", "MANAGER", MANAGER).getStatus(), is(400));
    assertThat(statusOf(other), is("PENDING"));
  }

  @Test
  @DisplayName("VOID-15: a fulfilled order cannot be cancelled; it is returned instead")
  void aFulfilledOrderCannotBeCancelled() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    assertThat(statusOf(order), is("FULFILLED"));
    Response r = cancel(order, "{\"reason\":\"oops\"}", "MANAGER", MANAGER);
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("ORDER_CANNOT_CANCEL"));
    assertThat(statusOf(order), is("FULFILLED"));
    assertThat(rig().events(order, "OrderCancelled"), is(0L));
  }

  @Test
  @DisplayName("VOID-16: a void made at a till names the drawer on OrderVoided; a bad id is a 400")
  void aVoidNamesTheDrawer() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    String other = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    String drawer = Ids.newId().toString();

    Response bad =
        rig()
            .post(
                "/orders/" + other + "/void",
                "{\"reason\":\"mis-ring\",\"tillSessionId\":\"not-an-id\"}",
                T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString());
    assertThat(bad.getStatus(), is(400));
    assertThat(statusOf(other), is("FULFILLED"));

    data(
        rig()
            .post(
                "/orders/" + order + "/void",
                "{\"reason\":\"mis-ring\",\"tillSessionId\":\"" + drawer + "\"}",
                T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString()),
        200);

    assertThat(rig().event(order, "OrderVoided").getString("tillSessionId"), is(drawer));
  }

  @Test
  @DisplayName(
      "VOID-10: a second void under a new key is refused; a retry under the same key is not")
  void aSecondVoidIsRefusedButARetryIsNot() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    String key = Ids.newId().toString();
    JsonObject first =
        data(
            rig()
                .post(
                    "/orders/" + order + "/void",
                    "{\"reason\":\"rang up twice\"}",
                    T,
                    "MANAGER",
                    MANAGER,
                    key),
            200);
    assertThat(statusOf(order), is("VOIDED"));

    // The same key: the first answer again, and nothing written.
    JsonObject replay =
        data(
            rig()
                .post(
                    "/orders/" + order + "/void",
                    "{\"reason\":\"rang up twice\"}",
                    T,
                    "MANAGER",
                    MANAGER,
                    key),
            200);
    assertThat(replay.getString("orderId"), is(first.getString("orderId")));
    assertThat(replay.getString("voidedAt"), is(first.getString("voidedAt")));

    // A different key, with the same or another reason: refused outright.
    for (String reason : new String[] {"rang up twice", "a different reason"}) {
      Response again =
          rig()
              .post(
                  "/orders/" + order + "/void",
                  "{\"reason\":\"" + reason + "\"}",
                  T,
                  "MANAGER",
                  MANAGER,
                  Ids.newId().toString());
      assertThat(again.getStatus(), is(409));
      assertThat(again.readEntity(String.class), containsString("ORDER_CANNOT_VOID"));
    }
    assertThat(rig().count("pos_void_log", "order_id='" + order + "'"), is(1L));
    assertThat(rig().events(order, "OrderVoided"), is(1L));
    // A void needs a key at all.
    Response none =
        rig().post("/orders/" + order + "/void", "{\"reason\":\"x\"}", T, "MANAGER", MANAGER, null);
    assertThat(none.getStatus(), is(400));
  }
}
