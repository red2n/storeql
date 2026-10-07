package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_B;
import static com.storeql.order.support.ReturnsRig.V_TAX;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An order-level discount is shared into what a return refunds: a line comes back for what the
 * customer paid for it, the parts of a line add up to exactly that, and an exchange values the old
 * goods the same way. Tried in another business too, which can neither see nor return the sale.
 */
@HelidonTest
class ReturnDiscountIT {

  private static final String T = "01a0a1c5-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c5-1111-7000-8000-000000000003";
  private static final String STORE = "01a0a1c5-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0a1c5-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c5-4444-7000-8000-000000000002";

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

  private static String returnBody(String variant, int qty) {
    return "{\"reason\":\"changed mind\",\"items\":[{\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"condition\":\"SEALED\"}]}";
  }

  private Response returnOf(String order, String variant, int qty, String tenant, String roles) {
    return rig()
        .post(
            "/orders/" + order + "/returns",
            returnBody(variant, qty),
            tenant,
            roles,
            CASHIER,
            Ids.newId().toString());
  }

  private static BigDecimal money(JsonObject o, String key) {
    return o.getJsonNumber(key).bigDecimalValue();
  }

  private BigDecimal netOf(String returnId) {
    return new BigDecimal(
        rig()
            .one(
                "SELECT sum(refund_amount) FROM \"order\".return_items WHERE return_id='"
                    + returnId
                    + "'"));
  }

  @Test
  @DisplayName("A discounted sale returned in two parts refunds exactly what was paid, no more")
  void discountedSaleReturnedInTwoParts() {
    // Five at 10.00 and 2.00 VAT each: 60.00 with 5.00 off is 55.00 paid.
    JsonObject sale = rig().discountedSale(T, STORE, V_TAX, 5, "5.00", MANAGER, true);
    String order = sale.getString("id");
    assertThat(money(sale, "total"), is(new BigDecimal("55.00")));

    JsonObject first = data(returnOf(order, V_TAX, 2, T, "CASHIER"), 201);
    // 2 of 5: 20.00 net and 4.00 VAT, less two fifths of the 5.00: 22.00, not 24.00.
    assertThat(money(first, "refundAmount"), is(new BigDecimal("22.00")));
    // The line keeps the net revenue it reverses: 20.00 less its 2.00 share.
    assertThat(netOf(first.getString("id")), is(new BigDecimal("18.00")));
    assertThat(
        money(rig().event(order, "OrderReturned"), "refundAmount"), is(new BigDecimal("22.00")));

    JsonObject second = data(returnOf(order, V_TAX, 3, T, "CASHIER"), 201);
    assertThat(money(second, "refundAmount"), is(new BigDecimal("33.00")));
    assertThat(netOf(second.getString("id")), is(new BigDecimal("27.00")));

    // Together exactly what was paid, and nothing more can come back.
    assertThat(
        money(first, "refundAmount").add(money(second, "refundAmount")), is(money(sale, "total")));
    Response third = returnOf(order, V_TAX, 1, T, "CASHIER");
    assertThat(third.getStatus(), is(409));
    assertThat(third.readEntity(String.class), containsString("RETURN_QTY_EXCEEDS_PURCHASED"));
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(2L));
  }

  @Test
  @DisplayName("Three parts that do not divide evenly: the last takes the rounding remainder")
  void threeWayRoundingEndsOnTheExactTotal() {
    // Three at 10.00 and 2.00 VAT: 36.00 with 1.00 off is 35.00 paid; a third of 1.00 is 0.33.
    JsonObject sale = rig().discountedSale(T, STORE, V_TAX, 3, "1.00", MANAGER, true);
    String order = sale.getString("id");
    assertThat(money(sale, "total"), is(new BigDecimal("35.00")));

    BigDecimal sum = BigDecimal.ZERO;
    String[] expected = {"11.67", "11.66", "11.67"};
    for (String want : expected) {
      JsonObject r = data(returnOf(order, V_TAX, 1, T, "CASHIER"), 201);
      assertThat(money(r, "refundAmount"), is(new BigDecimal(want)));
      sum = sum.add(money(r, "refundAmount"));
    }
    assertThat(sum, is(new BigDecimal("35.00")));
  }

  @Test
  @DisplayName("A discount is shared across the lines in proportion to their net value")
  void discountIsSharedAcrossLines() {
    // Two lines on one order: 10.00 (V_TAX, 12.00 with VAT) and 25.00 (V_B); 3.50 off the 35.00.
    Response placed =
        rig()
            .post(
                "/orders",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                    + "\"items\":[{\"variantId\":\""
                    + V_TAX
                    + "\",\"qty\":1},{\"variantId\":\""
                    + V_B
                    + "\",\"qty\":1}],\"discountAmount\":3.50,\"discountReason\":\"loyal\"}",
                T,
                "MANAGER",
                MANAGER,
                Ids.newId().toString());
    JsonObject sale = data(placed, 201);
    String order = sale.getString("id");
    // 35.00 + 2.00 VAT - 3.50.
    assertThat(money(sale, "total"), is(new BigDecimal("33.50")));
    orderService.handlePaymentCaptured(
        Ids.parse(T), Ids.parse(order), Ids.newId(), money(sale, "total"));

    JsonObject a = data(returnOf(order, V_TAX, 1, T, "CASHIER"), 201);
    JsonObject b = data(returnOf(order, V_B, 1, T, "CASHIER"), 201);
    // The taxed line: 10.00 + 2.00 - 1.00 (a tenth of the discount); the other 25.00 - 2.50.
    assertThat(money(a, "refundAmount"), is(new BigDecimal("11.00")));
    assertThat(money(b, "refundAmount"), is(new BigDecimal("22.50")));
    assertThat(money(a, "refundAmount").add(money(b, "refundAmount")), is(money(sale, "total")));
  }

  @Test
  @DisplayName("A sale with no discount is refunded as it always was")
  void noDiscountIsUnchanged() {
    String order = rig().sale(T, STORE, V_TAX, 2, null, MANAGER);
    JsonObject r = data(returnOf(order, V_TAX, 1, T, "CASHIER"), 201);
    assertThat(money(r, "refundAmount"), is(new BigDecimal("12.00")));
    assertThat(netOf(r.getString("id")), is(new BigDecimal("10.00")));
  }

  @Test
  @DisplayName("An exchange values a discounted line at what was paid for it")
  void exchangeOfADiscountedLine() {
    JsonObject sale = rig().discountedSale(T, STORE, V_TAX, 5, "5.00", MANAGER, true);
    String order = sale.getString("id");

    JsonObject x =
        data(
            rig()
                .post(
                    "/orders/" + order + "/exchange",
                    "{\"reason\":\"wrong size\",\"returnItems\":[{\"variantId\":\""
                        + V_TAX
                        + "\",\"qty\":2,\"condition\":\"SEALED\"}],\"newItems\":[{\"variantId\":\""
                        + V_B
                        + "\",\"qty\":1}]}",
                    T,
                    "CASHIER",
                    CASHIER,
                    Ids.newId().toString()),
            201);
    // Worth 22.00 (not 24.00) against a 25.00 basket: 3.00 due.
    assertThat(money(x.getJsonObject("return"), "refundAmount"), is(new BigDecimal("22.00")));
    assertThat(money(x, "exchangeAmount"), is(new BigDecimal("22.00")));
    assertThat(money(x, "dueFromCustomer"), is(new BigDecimal("3.00")));
    assertThat(
        money(rig().event(order, "OrderReturned"), "exchangeAmount"), is(new BigDecimal("22.00")));

    // The rest of the line then comes back by an ordinary return: 33.00, the 55.00 in all.
    JsonObject rest = data(returnOf(order, V_TAX, 3, T, "CASHIER"), 201);
    assertThat(money(rest, "refundAmount"), is(new BigDecimal("33.00")));
  }

  @Test
  @DisplayName("Another business can neither return against the sale nor see its returns")
  void otherBusinessMovesNothing() {
    JsonObject sale = rig().discountedSale(T, STORE, V_TAX, 2, "1.00", MANAGER, true);
    String order = sale.getString("id");
    long before = rig().count("returns", "order_id='" + order + "'");
    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Response r = returnOf(order, V_TAX, 1, OTHER_T, role);
      assertThat(role, r.getStatus() == 404 || r.getStatus() == 403, is(true));
    }
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(before));
    assertThat(rig().events(order, "OrderReturned"), is(0L));
  }
}
