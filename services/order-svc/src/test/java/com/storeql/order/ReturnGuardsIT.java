package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.V_B;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.Concurrency;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guards round a till return (catalogue RET-04, 05, 09, 13, 14, 15): a voided sale, a variant
 * that was not sold and two returns at once are refused, another business's staff and staff of
 * another store move nothing, and a shopper reads their own returns but takes none.
 */
@HelidonTest
class ReturnGuardsIT {

  private static final String T = "01a0a1cc-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1cc-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a1cc-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1cc-2222-7000-8000-00000000000b";
  private static final String MANAGER = "01a0a1cc-4444-7000-8000-000000000001";
  private static final String SHOPPER = "01a0a1cc-4444-7000-8000-000000000011";
  private static final String NEIGHBOUR = "01a0a1cc-4444-7000-8000-000000000012";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US").with(OTHER_T, "USD", "US");
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

  private static String body(String variant, int qty) {
    return "{\"reason\":\"changed mind\",\"items\":[{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"condition\":\"SEALED\"}]}";
  }

  private Response returnAs(String order, String variant, int qty, String tenant, String roles) {
    return rig()
        .post(
            "/orders/" + order + "/returns",
            body(variant, qty),
            tenant,
            roles,
            MANAGER,
            Ids.newId().toString());
  }

  private long returns(String order) {
    return rig().count("returns", "order_id='" + order + "'");
  }

  @Test
  @DisplayName("RET-04: a voided sale cannot be returned against")
  void aVoidedSaleCannotBeReturned() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    Response voided =
        rig()
            .post(
                "/orders/" + order + "/void",
                "{\"reason\":\"rang up twice\"}",
                T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString());
    assertThat(voided.readEntity(String.class), voided.getStatus(), is(200));

    Response r = returnAs(order, V_A, 1, T, "MANAGER");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("ORDER_CANNOT_RETURN"));
    assertThat(returns(order), is(0L));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }

  @Test
  @DisplayName("RET-05: a variant that is not on the sale is refused")
  void aVariantNotOnTheSaleIsRefused() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    Response r = returnAs(order, V_B, 1, T, "MANAGER");
    assertThat(r.getStatus(), is(404));
    assertThat(r.readEntity(String.class), containsString("ITEM_NOT_IN_ORDER"));
    assertThat(returns(order), is(0L));
  }

  @Test
  @DisplayName("RET-09: two returns of the whole line at once return it once")
  void twoReturnsAtOnceCannotOverRefund() throws Exception {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    List<Integer> statuses =
        Concurrency.inParallel(2, () -> returnAs(order, V_A, 2, T, "MANAGER").getStatus());
    assertThat(statuses.stream().filter(s -> s == 201).count(), is(1L));
    assertThat(statuses.stream().filter(s -> s == 409).count(), is(1L));
    assertThat(returns(order), is(1L));
    assertThat(rig().events(order, "OrderReturned"), is(1L));
    assertThat(
        new BigDecimal(
                rig()
                    .one(
                        "SELECT sum(ri.qty) FROM \"order\".return_items ri JOIN \"order\".returns r"
                            + " ON r.id = ri.return_id WHERE r.order_id='"
                            + order
                            + "'"))
            .compareTo(new BigDecimal("2")),
        is(0));
    // And nothing more can come back.
    Response more = returnAs(order, V_A, 1, T, "MANAGER");
    assertThat(more.getStatus(), is(409));
    assertThat(more.readEntity(String.class), containsString("RETURN_QTY_EXCEEDS_PURCHASED"));
  }

  @Test
  @DisplayName("RET-13: another business's staff of every role, naming the order, return nothing")
  void otherBusinessReturnsNothing() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      Response r = returnAs(order, V_A, 1, OTHER_T, role);
      assertThat(role, r.getStatus(), is(404));
      assertThat(role, r.readEntity(String.class), containsString("ORDER_NOT_FOUND"));
    }
    assertThat(returnAs(order, V_A, 1, OTHER_T, "CUSTOMER").getStatus(), is(403));
    assertThat(returns(order), is(0L));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }

  @Test
  @DisplayName("RET-14: a manager held to another store cannot return this store's sale")
  void aManagerOfAnotherStoreIsRefused() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    Response r =
        rig()
            .postHeld(
                "/orders/" + order + "/returns", body(V_A, 1), T, "MANAGER", MANAGER, OTHER_STORE);
    assertThat(r.getStatus(), is(403));
    assertThat(r.readEntity(String.class), containsString("STORE_ACCESS_DENIED"));
    assertThat(returns(order), is(0L));
  }

  @Test
  @DisplayName("RET-15: a shopper reads their own order's returns but cannot take one")
  void aShopperReadsButCannotReturn() {
    Response placed =
        rig()
            .as("/orders", T, "CUSTOMER", SHOPPER)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                jakarta.ws.rs.client.Entity.entity(
                    "{\"storeId\":\""
                        + STORE
                        + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\","
                        + "\"items\":[{\"variantId\":\""
                        + V_A
                        + "\",\"qty\":2}]}",
                    jakarta.ws.rs.core.MediaType.APPLICATION_JSON));
    String text = placed.readEntity(String.class);
    assertThat(text, placed.getStatus(), is(201));
    var o = Envelopes.parse(text).getJsonObject("data");
    String order = o.getString("id");
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(order), Ids.newId(), o.getJsonNumber("total").bigDecimalValue());
    orderService.fulfillOrder(Ids.parse(T), Ids.parse(order), Ids.parse(MANAGER));
    data(returnAs(order, V_A, 1, T, "MANAGER"), 201);

    Response mine = rig().get("/orders/" + order + "/returns", T, "CUSTOMER", SHOPPER);
    assertThat(mine.readEntity(String.class), mine.getStatus(), is(200));
    // Not a neighbour's to read.
    assertThat(
        rig().get("/orders/" + order + "/returns", T, "CUSTOMER", NEIGHBOUR).getStatus(), is(404));

    long before = returns(order);
    Response take =
        rig()
            .post(
                "/orders/" + order + "/returns",
                body(V_A, 1),
                T,
                "CUSTOMER",
                SHOPPER,
                Ids.newId().toString());
    assertThat(take.getStatus(), is(403));
    assertThat(returns(order), is(before));
  }
}
