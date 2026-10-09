package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.data;
import static com.storeql.order.support.ShelfPricing.BASKET_PERCENT;
import static com.storeql.order.support.ShelfPricing.BREAD;
import static com.storeql.order.support.ShelfPricing.CHEESE;
import static com.storeql.order.support.ShelfPricing.JAM;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.order.support.ShelfPricing;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An order sold at shelf prices, VAT inside (intent/vat-inclusive-pricing.md): what the shelf says
 * is what is charged, receipted and kept; the VAT in it is carried, and a staff discount lowers it.
 * pricing-svc is a stand-in that quotes the way the real one does for a tax-inclusive list.
 */
@HelidonTest
class OrderInclusiveIT {

  private static final String T = "01a0b2c4-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0b2c4-1111-7000-8000-000000000003";
  private static final String STORE = "01a0b2c4-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0b2c4-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0b2c4-4444-7000-8000-000000000002";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "GBP", "GB").with(OTHER_T, "GBP", "GB");
    PRICING = ShelfPricing.start();
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

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static String body(String discount, String... variantAndQty) {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < variantAndQty.length; i += 2) {
      items.append(i == 0 ? "" : ",");
      items.append("{\"variantId\":\"").append(variantAndQty[i]).append("\",\"qty\":");
      items.append(variantAndQty[i + 1]).append("}");
    }
    return "{\"storeId\":\""
        + STORE
        + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":["
        + items
        + "]"
        + (discount == null
            ? ""
            : ",\"discountAmount\":" + discount + ",\"discountReason\":\"damaged box\"")
        + "}";
  }

  private JsonObject place(String tenant, String discount, String... variantAndQty) {
    return placeAs("MANAGER", tenant, discount, variantAndQty);
  }

  private JsonObject placeAs(String role, String tenant, String discount, String... variantAndQty) {
    Response r =
        rig()
            .post(
                "/orders",
                body(discount, variantAndQty),
                tenant,
                role,
                MANAGER,
                Ids.newId().toString());
    return data(r, 201);
  }

  private static BigDecimal money(JsonObject o, String key) {
    return o.getJsonNumber(key).bigDecimalValue();
  }

  private static JsonObject line(JsonObject order, String variant) {
    for (JsonValue v : order.getJsonArray("items")) {
      if (variant.equals(v.asJsonObject().getString("variantId"))) return v.asJsonObject();
    }
    throw new AssertionError("no line for " + variant);
  }

  private void identities(JsonObject order) {
    // The order says it is sold at shelf prices, and its lines add up to what is charged.
    assertThat(order.getBoolean("taxInclusive"), is(true));
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    BigDecimal gross = BigDecimal.ZERO;
    for (JsonValue v : order.getJsonArray("items")) {
      JsonObject l = v.asJsonObject();
      net = net.add(money(l, "lineTotal"));
      vat = vat.add(money(l, "vatAmount"));
      gross = gross.add(money(l, "grossTotal"));
      assertThat(
          "line net + vat = what was paid",
          money(l, "lineTotal").add(money(l, "vatAmount")).compareTo(money(l, "grossTotal")),
          is(0));
    }
    assertThat(money(order, "subtotal").compareTo(net), is(0));
    assertThat(money(order, "taxAmount").compareTo(vat), is(0));
    assertThat("subtotal + tax = lines paid", net.add(vat).compareTo(gross), is(0));
    assertThat(money(order, "total").compareTo(gross), is(0));
  }

  // ── a shelf price is the price ───────────────────────────────────────────────

  @Test
  @DisplayName("1.29 at 20% and 1.99 at 5% are ordered as exactly 3.28, with 0.31 of VAT inside")
  void shelfPricesAreOrderedExactly() {
    BASKET_PERCENT.set(BigDecimal.ZERO);

    JsonObject order = place(T, null, BREAD, "1", JAM, "1");

    assertThat(money(order, "total"), comparesEqualTo(new BigDecimal("3.28")));
    assertThat(money(order, "taxAmount"), comparesEqualTo(new BigDecimal("0.31")));
    assertThat(money(order, "subtotal"), comparesEqualTo(new BigDecimal("2.97")));
    assertThat(money(line(order, BREAD), "grossTotal"), comparesEqualTo(new BigDecimal("1.29")));
    assertThat(money(line(order, BREAD), "vatAmount"), comparesEqualTo(new BigDecimal("0.22")));
    assertThat(money(line(order, BREAD), "lineTotal"), comparesEqualTo(new BigDecimal("1.07")));
    assertThat(money(line(order, BREAD), "listUnitPrice"), comparesEqualTo(new BigDecimal("1.29")));
    assertThat(money(line(order, JAM), "grossTotal"), comparesEqualTo(new BigDecimal("1.99")));
    identities(order);
  }

  @Test
  @DisplayName(
      "Three at 1.29 are 3.87 with 0.65 VAT inside, worked on the line, not three times 0.22")
  void aQuantityIsWorkedOnTheLine() {
    BASKET_PERCENT.set(BigDecimal.ZERO);

    JsonObject order = place(T, null, BREAD, "3");

    assertThat(money(order, "total"), comparesEqualTo(new BigDecimal("3.87")));
    assertThat(money(order, "taxAmount"), comparesEqualTo(new BigDecimal("0.65")));
    identities(order);
  }

  @Test
  @DisplayName("A staff discount is shared by gross and lowers the VAT; the order still adds up")
  void aStaffDiscountLowersTheVat() {
    BASKET_PERCENT.set(BigDecimal.ZERO);

    JsonObject order = place(T, "1.00", BREAD, "1", CHEESE, "1");

    assertThat(money(order, "total"), comparesEqualTo(new BigDecimal("12.29")));
    assertThat(money(order, "discountAmount"), comparesEqualTo(new BigDecimal("1.00")));
    // 13.29 less 1.00: the bread bears 0.10 and the cheese 0.90
    assertThat(money(line(order, BREAD), "grossTotal"), comparesEqualTo(new BigDecimal("1.19")));
    assertThat(money(line(order, BREAD), "vatAmount"), comparesEqualTo(new BigDecimal("0.20")));
    assertThat(money(line(order, CHEESE), "grossTotal"), comparesEqualTo(new BigDecimal("11.10")));
    assertThat(money(line(order, CHEESE), "vatAmount"), comparesEqualTo(new BigDecimal("1.85")));
    assertThat(money(order, "taxAmount"), comparesEqualTo(new BigDecimal("2.05")));
    identities(order);
  }

  @Test
  @DisplayName("A basket promotion is inside the lines already and is not taken off again")
  void aBasketPromotionIsNotTakenTwice() {
    BASKET_PERCENT.set(new BigDecimal("10"));
    try {
      JsonObject order = place(T, null, BREAD, "1", CHEESE, "1");

      // 13.29 less ten per cent (1.33): 11.96 is charged once.
      assertThat(money(order, "total"), comparesEqualTo(new BigDecimal("11.96")));
      assertThat(money(order, "promotionDiscount"), comparesEqualTo(new BigDecimal("1.33")));
      identities(order);
    } finally {
      BASKET_PERCENT.set(BigDecimal.ZERO);
    }
  }

  @Test
  @DisplayName("A promotion and a staff discount together: both inside, the total the lines add to")
  void aPromotionAndAStaffDiscountTogether() {
    BASKET_PERCENT.set(new BigDecimal("10"));
    try {
      JsonObject order = place(T, "0.96", BREAD, "1", CHEESE, "1");

      assertThat(money(order, "total"), comparesEqualTo(new BigDecimal("11.00")));
      identities(order);
    } finally {
      BASKET_PERCENT.set(BigDecimal.ZERO);
    }
  }

  @Test
  @DisplayName("A discount of the whole basket leaves nothing to pay and no VAT")
  void aDiscountOfTheWholeBasket() {
    BASKET_PERCENT.set(BigDecimal.ZERO);

    JsonObject order = placeAs("OWNER", T, "3.28", BREAD, "1", JAM, "1");

    assertThat(money(order, "total"), comparesEqualTo(new BigDecimal("0.00")));
    assertThat(money(order, "taxAmount"), comparesEqualTo(new BigDecimal("0.00")));
    identities(order);
  }

  @Test
  @DisplayName("Another business cannot read the order")
  void anotherBusinessCannotReadTheOrder() {
    BASKET_PERCENT.set(BigDecimal.ZERO);
    JsonObject order = place(T, null, BREAD, "1");

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "CUSTOMER"}) {
      assertThat(
          role,
          rig().get("/orders/" + order.getString("id"), OTHER_T, role, CASHIER).getStatus(),
          is(404));
    }
  }

  // ── a return is what was paid ────────────────────────────────────────────────

  private void pay(JsonObject order) {
    orderService.handlePaymentCaptured(
        Ids.parse(T),
        Ids.parse(order.getString("id")),
        Ids.newId(),
        order.getJsonNumber("total").bigDecimalValue());
  }

  private Response giveBack(String order, String variant, int qty, String tenant, String roles) {
    return rig()
        .post(
            "/orders/" + order + "/returns",
            "{\"reason\":\"changed mind\",\"items\":[{\"variantId\":\""
                + variant
                + "\",\"qty\":"
                + qty
                + ",\"condition\":\"SEALED\"}]}",
            tenant,
            roles,
            CASHIER,
            Ids.newId().toString());
  }

  @Test
  @DisplayName(
      "Three at 1.29 come back one at a time for 3.87 in all, the 0.65 VAT in 0.22, 0.21, 0.22")
  void aLineComesBackInPartsForExactlyWhatWasPaid() {
    BASKET_PERCENT.set(BigDecimal.ZERO);
    JsonObject order = place(T, null, BREAD, "3");
    pay(order);
    String id = order.getString("id");
    BigDecimal refunded = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    List<String> parts = new ArrayList<>();

    for (int k = 0; k < 3; k++) {
      JsonObject ret = data(giveBack(id, BREAD, 1, T, "CASHIER"), 201);
      refunded = refunded.add(money(ret, "refundAmount"));
      BigDecimal partVat = money(rig().event(id, "OrderReturned"), "vatAmount");
      vat = vat.add(partVat);
      parts.add(partVat.toPlainString());
      assertThat(
          rig()
              .event(id, "OrderReturned")
              .getJsonArray("items")
              .getJsonObject(0)
              .getJsonNumber("vatAmount")
              .bigDecimalValue(),
          comparesEqualTo(partVat));
    }

    assertThat(refunded, comparesEqualTo(new BigDecimal("3.87")));
    assertThat(vat, comparesEqualTo(new BigDecimal("0.65")));
    assertThat(parts.toString(), is("[0.22, 0.21, 0.22]"));
    assertThat(
        new BigDecimal(
            rig()
                .one(
                    "SELECT sum(tax_amount) FROM \"order\".return_items WHERE return_id IN"
                        + " (SELECT id FROM \"order\".returns WHERE order_id='"
                        + id
                        + "')")),
        comparesEqualTo(new BigDecimal("0.65")));
  }

  @Test
  @DisplayName(
      "An exchange at shelf prices: the loaf comes back for 1.29 with its 0.22 of VAT, the jam is"
          + " 1.99, and the customer pays the 0.70 between them")
  void anExchangeIsTheSameMoneyAsAReturnAndASale() {
    BASKET_PERCENT.set(BigDecimal.ZERO);
    JsonObject order = place(T, null, BREAD, "1", JAM, "1");
    pay(order);
    String id = order.getString("id");

    JsonObject x =
        data(
            rig()
                .post(
                    "/orders/" + id + "/exchange",
                    "{\"reason\":\"wrong item\",\"returnItems\":[{\"variantId\":\""
                        + BREAD
                        + "\",\"qty\":1,\"condition\":\"SEALED\"}],\"newItems\":[{\"variantId\":\""
                        + JAM
                        + "\",\"qty\":1}]}",
                    T,
                    "CASHIER",
                    CASHIER,
                    Ids.newId().toString()),
            201);

    // What came back is what was paid for the loaf, VAT included; the new sale is a shelf price.
    assertThat(money(x, "exchangeAmount"), comparesEqualTo(new BigDecimal("1.29")));
    JsonObject bought = x.getJsonObject("order");
    identities(bought);
    assertThat(money(bought, "total"), comparesEqualTo(new BigDecimal("1.99")));
    assertThat(money(x, "dueFromCustomer"), comparesEqualTo(new BigDecimal("0.70")));
    assertThat(money(x, "refundToCustomer").signum(), is(0));
    // The return carries the VAT inside the loaf (1.29 at 20% = 0.22), and the event says so.
    assertThat(
        money(rig().event(id, "OrderReturned"), "vatAmount"),
        comparesEqualTo(new BigDecimal("0.22")));
    assertThat(
        new BigDecimal(
            rig()
                .one(
                    "SELECT sum(tax_amount) FROM \"order\".return_items WHERE return_id = '"
                        + x.getJsonObject("return").getString("id")
                        + "'")),
        comparesEqualTo(new BigDecimal("0.22")));
  }

  @Test
  @DisplayName(
      "After a staff discount each line comes back for what it was paid, not its shelf price")
  void aDiscountedSaleReturnsWhatWasPaid() {
    BASKET_PERCENT.set(BigDecimal.ZERO);
    JsonObject order = place(T, "1.00", BREAD, "1", CHEESE, "1");
    pay(order);
    String id = order.getString("id");

    JsonObject cheese = data(giveBack(id, CHEESE, 1, T, "CASHIER"), 201);
    assertThat(money(cheese, "refundAmount"), comparesEqualTo(new BigDecimal("11.10")));
    assertThat(
        money(rig().event(id, "OrderReturned"), "vatAmount"),
        comparesEqualTo(new BigDecimal("1.85")));

    JsonObject bread = data(giveBack(id, BREAD, 1, T, "CASHIER"), 201);
    assertThat(money(bread, "refundAmount"), comparesEqualTo(new BigDecimal("1.19")));
    assertThat(
        money(rig().event(id, "OrderReturned"), "vatAmount"),
        comparesEqualTo(new BigDecimal("0.20")));
    assertThat(
        "the two refunds are the order's total",
        money(cheese, "refundAmount").add(money(bread, "refundAmount")),
        comparesEqualTo(money(order, "total")));
  }

  @Test
  @DisplayName("Another business's staff cannot return this business's sale")
  void anotherBusinessCannotReturn() {
    BASKET_PERCENT.set(BigDecimal.ZERO);
    JsonObject order = place(T, null, BREAD, "1");
    pay(order);

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      assertThat(
          role, giveBack(order.getString("id"), BREAD, 1, OTHER_T, role).getStatus(), is(404));
    }
    assertThat(rig().count("returns", "order_id='" + order.getString("id") + "'"), is(0L));
  }

  @Test
  @DisplayName(
      "A return with no receipt is credited the shelf price whole, the VAT worked on the line")
  void aNoReceiptReturnAtTheShelfPrice() {
    assertThat(
        rig()
            .put(
                "/admin/return-policy",
                "{\"windowDays\":30,\"noReceiptAllowed\":true,\"noReceiptCeiling\":30.00}",
                T,
                "OWNER",
                MANAGER)
            .getStatus(),
        is(200));

    JsonObject ret =
        data(
            rig()
                .post(
                    "/returns/no-receipt",
                    "{\"storeId\":\""
                        + STORE
                        + "\",\"reason\":\"no receipt\",\"refundMethod\":\"GIFT_CARD\","
                        + "\"customerContact\":\"07700900123\",\"items\":[{\"variantId\":\""
                        + BREAD
                        + "\",\"qty\":3,\"condition\":\"SEALED\"}]}",
                    T,
                    "MANAGER",
                    MANAGER,
                    Ids.newId().toString()),
            201);

    assertThat(money(ret, "refundAmount"), comparesEqualTo(new BigDecimal("3.87")));
    JsonObject item = ret.getJsonArray("items").getJsonObject(0);
    assertThat(money(item, "unitPrice"), comparesEqualTo(new BigDecimal("1.29")));
    assertThat(money(item, "taxAmount"), comparesEqualTo(new BigDecimal("0.65")));
    assertThat(
        money(rig().event(ret.getString("id"), "NoReceiptReturnRecorded"), "taxAmount"),
        comparesEqualTo(new BigDecimal("0.65")));
  }

  // ── the receipt ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The receipt document draws the sale at shelf prices and its VAT table adds up")
  void theReceiptDocumentAddsUp() {
    BASKET_PERCENT.set(BigDecimal.ZERO);
    JsonObject order = place(T, null, BREAD, "1", JAM, "1");
    pay(order);

    JsonObject doc =
        data(
            rig()
                .get(
                    "/orders/" + order.getString("id") + "/receipt-document",
                    T,
                    "MANAGER",
                    CASHIER),
            200);

    assertThat(doc.getJsonArray("lines").size(), is(2));
    JsonObject first = doc.getJsonArray("lines").getJsonObject(0);
    assertThat(money(first, "listUnitPrice"), comparesEqualTo(new BigDecimal("1.29")));
    assertThat(money(first, "lineGross"), comparesEqualTo(new BigDecimal("1.29")));
    assertThat(doc.getJsonArray("vat").size(), is(2));
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    for (JsonValue v : doc.getJsonArray("vat")) {
      gross = gross.add(money(v.asJsonObject(), "gross"));
      vat = vat.add(money(v.asJsonObject(), "vat"));
      assertThat(
          money(v.asJsonObject(), "net").add(money(v.asJsonObject(), "vat")),
          comparesEqualTo(money(v.asJsonObject(), "gross")));
    }
    assertThat(gross, comparesEqualTo(money(doc, "total")));
    assertThat(money(doc, "total"), comparesEqualTo(new BigDecimal("3.28")));
    assertThat(vat, comparesEqualTo(new BigDecimal("0.31")));
    assertThat(money(doc, "vatTotal"), comparesEqualTo(new BigDecimal("0.31")));
    assertThat(doc.getString("reference").length(), is(8));
    assertThat(doc.getString("timeZone").isEmpty(), is(false));
    assertThat(doc.containsKey("seller"), is(true));
  }

  @Test
  @DisplayName("Another business's staff and a shopper with no claim on it cannot read the receipt")
  void theReceiptIsNotForStrangers() {
    BASKET_PERCENT.set(BigDecimal.ZERO);
    JsonObject order = place(T, null, BREAD, "1");
    pay(order);
    String path = "/orders/" + order.getString("id") + "/receipt-document";

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      assertThat(role, rig().get(path, OTHER_T, role, CASHIER).getStatus(), is(404));
    }
    // A shopper with no login of ours is turned away before the order is looked up at all.
    assertThat(rig().get(path, OTHER_T, "CUSTOMER", CASHIER).getStatus(), is(403));
    assertThat(rig().get(path, T, "CUSTOMER", CASHIER).getStatus(), is(403));
  }
}
