package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.V_B;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Money in the currency's own minor units, end to end (the currency minor-units sweep): a dinar
 * business keeps its fils and a yen business is whole yen, on the order, its lines, a layaway, a
 * special order, a parked sale and a gift card — in the answer and in the table (V49 took the
 * columns' two places away; the service rounds before it writes). A gift card is no finer than its
 * currency, and another business never reaches ours whatever store it names.
 */
@HelidonTest
class CurrencyMinorUnitsIT {

  private static final String KW = "01a0a1c9-1111-7000-8000-000000000001";
  private static final String JP = "01a0a1c9-1111-7000-8000-000000000002";
  private static final String OTHER = "01a0a1c9-1111-7000-8000-000000000003";
  private static final String GB = "01a0a1c9-1111-7000-8000-000000000004";
  private static final String STORE = "01a0a1c9-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0a1c9-4444-7000-8000-000000000001";

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(KW, "KWD", "KW")
            .with(JP, "JPY", "JP")
            .with(OTHER, "KWD", "KW")
            .with(GB, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    // The till's own price is trusted here, so the rounding of a typed price is what is tried.
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService orderService;

  private ReturnsRig rig;

  @AfterAll
  static void stop() {
    for (String p :
        List.of("storeql.order.pricing.enforce", "storeql.order.inventory.reserve-enforce")) {
      System.clearProperty(p);
    }
    TENANTS.close();
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    return rig;
  }

  private Response post(String path, String json, String tenant, String roles, String stores) {
    return rig()
        .as(path, tenant, roles, MANAGER, stores)
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static BigDecimal number(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue();
  }

  private String column(String table, String column, String id) {
    return rig()
        .one("SELECT " + column + "::text FROM \"order\"." + table + " WHERE id='" + id + "'");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  // ── a till sale at a typed price ────────────────────────────────────────────

  private static String sale(String unitPrice, String discount) {
    return sale("POS", unitPrice, discount);
  }

  private static String sale(String channel, String unitPrice, String discount) {
    return "{\"storeId\":\""
        + STORE
        + "\",\"channel\":\""
        + channel
        + "\",\"fulfilmentType\":\"INSTORE\","
        + (discount == null
            ? ""
            : "\"discountAmount\":" + discount + ",\"discountReason\":\"scuffed tin\",")
        + "\"items\":[{\"variantId\":\""
        + V_A
        + "\",\"qty\":3,\"unitPrice\":"
        + unitPrice
        + "}]}";
  }

  @Test
  @DisplayName("A typed till price, its line, discount and total keep the dinar's third decimal")
  void aDinarSaleKeepsItsFils() {
    JsonObject order = data(post("/orders", sale("1.235", "0.011"), KW, "MANAGER", null), 201);
    String id = order.getString("id");
    // 3 × 1.235 is 3.705; less 0.011, 3.694 — never 3.71 or 3.69.
    assertThat(number(order, "subtotal"), is(new BigDecimal("3.705")));
    assertThat(number(order, "discountAmount"), is(new BigDecimal("0.011")));
    assertThat(number(order, "total"), is(new BigDecimal("3.694")));
    // As the table keeps it (V49 took the columns' two places away).
    assertThat(column("orders", "total", id), is("3.694"));
    assertThat(
        rig()
            .one(
                "SELECT unit_price::text || '/' || line_total::text FROM \"order\".order_items"
                    + " WHERE order_id='"
                    + id
                    + "'"),
        is("1.235/3.705"));

    // A typed price, or a discount typed anywhere but the till, finer than the dinar is refused
    // by name, and nothing is placed.
    long before = rig().count("orders", "tenant_id='" + KW + "'");
    assertThat(
        code(post("/orders", sale("1.2345", null), KW, "MANAGER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(
        code(post("/orders", sale("ONLINE", "1.235", "0.0105"), KW, "MANAGER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(rig().count("orders", "tenant_id='" + KW + "'"), is(before));
  }

  // ── what the till works out in floating point ──────────────────────────────

  private Response redeem(String tenant, String card, String amount, String orderId, String key) {
    return rig()
        .as("/gift-cards/" + card + "/redeem", tenant, "MANAGER", MANAGER, null)
        .header("Idempotency-Key", key)
        .post(
            Entity.entity(
                "{\"amount\":" + amount + ",\"orderId\":\"" + orderId + "\"}",
                MediaType.APPLICATION_JSON));
  }

  private BigDecimal balance(String tenant, String card) {
    return number(
        data(rig().get("/gift-cards/" + card, tenant, "MANAGER", MANAGER), 200), "currentBalance");
  }

  @Test
  @DisplayName(
      "A till's floating-point discount and gift-card tender are taken at the currency's units")
  void aTillsDoubleIsTakenAtTheCurrencysUnitsNeverRefused() {
    // Three items at £1.10, a full-basket discount the till clamped to its own 3 × 1.10: the
    // double 3.3000000000000003 is the £3.30 the cashier saw, not a refusal (it was refused as
    // finer than the pound, and the offline queue replayed that refusal for ever). An owner may
    // give the whole basket away; a manager a part of it.
    JsonObject free =
        data(post("/orders", sale("1.10", "3.3000000000000003"), GB, "OWNER", null), 201);
    assertThat(number(free, "discountAmount"), is(new BigDecimal("3.30")));
    assertThat(column("orders", "discount_amount", free.getString("id")), is("3.30"));
    JsonObject part =
        data(post("/orders", sale("1.10", "0.7999999999999999"), GB, "MANAGER", null), 201);
    assertThat(number(part, "discountAmount"), is(new BigDecimal("0.80")));
    assertThat(number(part, "total"), is(new BigDecimal("2.50")));

    // A £20 card pays what is left on 3 × £1.10: the till sends 3.3000000000000003.
    String card = issue(GB, "20.00");
    String order =
        data(post("/orders", sale("1.10", null), GB, "MANAGER", null), 201).getString("id");
    String key = Ids.newId().toString();
    JsonObject charged = data(redeem(GB, card, "3.3000000000000003", order, key), 200);
    assertThat(number(charged, "amount"), is(new BigDecimal("3.30")));
    assertThat(balance(GB, card), is(new BigDecimal("16.70")));
    assertThat(
        rig()
            .one(
                "SELECT amount::text FROM \"order\".gift_card_transactions WHERE order_id='"
                    + order
                    + "'"),
        is("3.30"));
    // The offline queue replays the same body under the same key: the same charge, once.
    assertThat(
        number(data(redeem(GB, card, "3.3000000000000003", order, key), 200), "amount"),
        is(new BigDecimal("3.30")));
    assertThat(balance(GB, card), is(new BigDecimal("16.70")));

    // A dinar order keeps its fils; a yen order is whole yen.
    String fils = issue(KW, "10.000");
    String kwOrder =
        data(post("/orders", sale("1.235", null), KW, "MANAGER", null), 201).getString("id");
    assertThat(
        number(
            data(redeem(KW, fils, "1.2345000000000002", kwOrder, Ids.newId().toString()), 200),
            "amount"),
        is(new BigDecimal("1.235")));
    assertThat(balance(KW, fils), is(new BigDecimal("8.765")));
    String yen = issue(JP, "5000");
    String jpOrder =
        data(post("/orders", sale("333", null), JP, "MANAGER", null), 201).getString("id");
    assertThat(
        number(
            data(redeem(JP, yen, "998.9999999999999", jpOrder, Ids.newId().toString()), 200),
            "amount"),
        is(new BigDecimal("999")));
    assertThat(balance(JP, yen), is(new BigDecimal("4001")));

    // Nothing once rounded is still refused, and charges nothing.
    String another =
        data(post("/orders", sale("1.10", null), GB, "MANAGER", null), 201).getString("id");
    assertThat(
        code(redeem(GB, card, "0.004", another, Ids.newId().toString()), 400),
        is("GIFT_CARD_AMOUNT_INVALID"));
    assertThat(balance(GB, card), is(new BigDecimal("16.70")));
  }

  /** Two lines weighed at 0.333 at {@code unitPrice}, rung up at the till with its discount. */
  private static String weighedSale(String unitPrice, String discount) {
    String line = "{\"variantId\":\"%s\",\"qty\":0.333,\"unitPrice\":" + unitPrice + "}";
    return "{\"storeId\":\""
        + STORE
        + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
        + "\"discountAmount\":"
        + discount
        + ",\"discountReason\":\"staff party\",\"items\":["
        + line.formatted(V_A)
        + ","
        + line.formatted(V_B)
        + "]}";
  }

  @Test
  @DisplayName(
      "A till's full-basket discount on weighed goods is the whole basket at the currency's units")
  void aTillsFullBasketDiscountOnWeighedGoodsIsCappedAtTheSubtotal() {
    // 2 × 0.333 kg at £1.99: the till adds 1.32534 unrounded, shows £1.33 and caps the cashier's
    // whole-basket discount at 1.32534. Each line here is £0.66, the order £1.32; the discount,
    // half up £1.33, was over it and refused 400 ORDER_DISCOUNT_EXCEEDS_SUBTOTAL — the sale
    // stopped and its offline replay refused every time. It is the whole basket, £1.32.
    JsonObject gbp = data(post("/orders", weighedSale("1.99", "1.32534"), GB, "OWNER", null), 201);
    assertThat(number(gbp, "subtotal"), is(new BigDecimal("1.32")));
    assertThat(number(gbp, "discountAmount"), is(new BigDecimal("1.32")));
    assertThat(number(gbp, "total").signum(), is(0));
    assertThat(column("orders", "discount_amount", gbp.getString("id")), is("1.32"));
    assertThat(
        rig()
            .one(
                "SELECT discount_amount::text || '/' || discount_pct::text FROM"
                    + " \"order\".order_discounts WHERE order_id='"
                    + gbp.getString("id")
                    + "'"),
        is("1.32/100.000"));

    // A dinar's fils: 2 × 0.664 is 1.328; the till's 1.32867 is 1.329, and the discount 1.328.
    JsonObject kwd = data(post("/orders", weighedSale("1.995", "1.32867"), KW, "OWNER", null), 201);
    assertThat(number(kwd, "discountAmount"), is(new BigDecimal("1.328")));
    assertThat(column("orders", "total", kwd.getString("id")), is("0.000"));
    // Whole yen: 2 × ¥66 is ¥132; the till's 132.534 is ¥133, and the discount ¥132.
    JsonObject jpy = data(post("/orders", weighedSale("199", "132.534"), JP, "OWNER", null), 201);
    assertThat(number(jpy, "discountAmount"), is(new BigDecimal("132")));
    assertThat(column("orders", "total", jpy.getString("id")), is("0"));

    // The cap widens nobody's authority: the whole basket is over a manager's 50%, refused as
    // before. More than the goods the till itself rang up (£1.33) is no rounding of the till's,
    // and is refused by name as a discount typed anywhere else is — at a pound's two places, a
    // dinar's three and in whole yen — and nothing is placed.
    long before = rig().count("orders", "tenant_id='" + GB + "'");
    assertThat(
        code(post("/orders", weighedSale("1.99", "1.32534"), GB, "MANAGER", null), 403),
        is("ORDER_DISCOUNT_EXCEEDS_AUTHORITY"));
    assertThat(
        code(post("/orders", weighedSale("1.99", "1.34"), GB, "OWNER", null), 400),
        is("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL"));
    assertThat(
        code(post("/orders", sale("ONLINE", "1.10", "3.31"), GB, "OWNER", null), 400),
        is("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL"));
    long kwBefore = rig().count("orders", "tenant_id='" + KW + "'");
    assertThat(
        code(post("/orders", weighedSale("1.995", "1.330"), KW, "OWNER", null), 400),
        is("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL"));
    assertThat(rig().count("orders", "tenant_id='" + KW + "'"), is(kwBefore));
    long jpBefore = rig().count("orders", "tenant_id='" + JP + "'");
    assertThat(
        code(post("/orders", weighedSale("199", "134"), JP, "OWNER", null), 400),
        is("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL"));
    assertThat(rig().count("orders", "tenant_id='" + JP + "'"), is(jpBefore));
    assertThat(rig().count("orders", "tenant_id='" + GB + "'"), is(before));
  }

  @Test
  @DisplayName("A typed till price in yen is whole yen, on the line and the order")
  void aYenSaleIsWholeYen() {
    JsonObject order = data(post("/orders", sale("333.00", null), JP, "MANAGER", null), 201);
    assertThat(number(order, "total"), is(new BigDecimal("999")));
    assertThat(column("orders", "total", order.getString("id")), is("999"));
    long before = rig().count("orders", "tenant_id='" + JP + "'");
    assertThat(
        code(post("/orders", sale("333.4", null), JP, "MANAGER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(rig().count("orders", "tenant_id='" + JP + "'"), is(before));
  }

  // ── a layaway, a special order and a parked sale ────────────────────────────

  private static String layaway(String unitPrice, String deposit) {
    return "{\"storeId\":\""
        + STORE
        + "\",\"items\":[{\"variantId\":\""
        + V_A
        + "\",\"qty\":2,\"unitPrice\":"
        + unitPrice
        + "}],\"initialDeposit\":"
        + deposit
        + ",\"paymentMethod\":\"CASH\"}";
  }

  private static String special(String unitPrice) {
    return "{\"storeId\":\""
        + STORE
        + "\",\"customerName\":\"Sato\",\"items\":[{\"variantId\":\""
        + V_A
        + "\",\"qty\":3,\"unitPrice\":"
        + unitPrice
        + "}]}";
  }

  private static String parked(String unitPrice, String discount) {
    return "{\"storeId\":\""
        + STORE
        + "\",\"customerName\":\"Queue\",\"items\":[{\"variantId\":\""
        + V_A
        + "\",\"qty\":3,\"unitPrice\":"
        + unitPrice
        + ",\"discountAmount\":"
        + discount
        + "}]}";
  }

  @Test
  @DisplayName("A dinar layaway and a yen special order and parked sale keep their own units")
  void layawaysSpecialOrdersAndParkedSalesKeepTheCurrencysOwnUnits() {
    JsonObject lay = data(post("/layaways", layaway("1.235", "1.001"), KW, "MANAGER", null), 201);
    // 2 × 1.235 is 2.470; the deposit 1.001; the balance 1.469.
    assertThat(number(lay, "totalAmount"), is(new BigDecimal("2.470")));
    assertThat(number(lay, "depositPaid"), is(new BigDecimal("1.001")));
    assertThat(number(lay, "balance"), is(new BigDecimal("1.469")));
    assertThat(column("layaways", "balance", lay.getString("id")), is("1.469"));
    // A further payment to the fils is taken; one finer than the dinar is refused, nothing moved.
    data(
        post(
            "/layaways/" + lay.getString("id") + "/deposits",
            "{\"amount\":0.469,\"paymentMethod\":\"CASH\"}",
            KW,
            "MANAGER",
            null),
        200);
    assertThat(column("layaways", "balance", lay.getString("id")), is("1.000"));
    assertThat(
        code(
            post(
                "/layaways/" + lay.getString("id") + "/deposits",
                "{\"amount\":0.0005,\"paymentMethod\":\"CASH\"}",
                KW,
                "MANAGER",
                null),
            400),
        is("VALIDATION_FAILED"));
    assertThat(column("layaways", "balance", lay.getString("id")), is("1.000"));
    long layaways = rig().count("layaways", "tenant_id='" + KW + "'");
    assertThat(
        code(post("/layaways", layaway("1.235", "1.0005"), KW, "MANAGER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(rig().count("layaways", "tenant_id='" + KW + "'"), is(layaways));

    JsonObject so = data(post("/admin/special-orders", special("333"), JP, "MANAGER", null), 201);
    assertThat(number(so, "total"), is(new BigDecimal("999")));
    assertThat(column("special_orders", "total", so.getString("id")), is("999"));
    assertThat(
        code(post("/admin/special-orders", special("333.4"), JP, "MANAGER", null), 400),
        is("VALIDATION_FAILED"));

    JsonObject park = data(post("/pos/parked-sales", parked("333", "1"), JP, "CASHIER", null), 201);
    // ¥333 × 3 is ¥999, less ¥1: ¥998, never 998.00.
    assertThat(number(park, "subtotal"), is(new BigDecimal("998")));
    assertThat(
        code(post("/pos/parked-sales", parked("333", "0.4"), JP, "CASHIER", null), 400),
        is("VALIDATION_FAILED"));
  }

  // ── quantities: three places, never rounded ─────────────────────────────────

  /** One line of {@code qty} at {@code unitPrice}, as a till or a storefront sends it. */
  private static String lineSale(String channel, String qty, String unitPrice) {
    return "{\"storeId\":\""
        + STORE
        + "\",\"channel\":\""
        + channel
        + "\",\"fulfilmentType\":\"INSTORE\",\"items\":[{\"variantId\":\""
        + V_A
        + "\",\"qty\":"
        + qty
        + ",\"unitPrice\":"
        + unitPrice
        + "}]}";
  }

  private String itemColumn(String table, String parent, String id) {
    return rig()
        .one(
            "SELECT qty::text || '/' || line_total::text FROM \"order\"."
                + table
                + " WHERE "
                + parent
                + "='"
                + id
                + "'");
  }

  @Test
  @DisplayName(
      "A quantity is counted to three places and never rounded; a till's double is its own")
  void aQuantityIsCountedToThreePlacesNeverRounded() {
    // Two weighings added on the till, 0.1 kg + 0.2 kg, post as 0.30000000000000004: the line is
    // 0.300 kg, worth 0.300 × 1.99 = 0.597, £0.60 — held, kept and deducted as it is charged.
    JsonObject sale =
        data(
            post("/orders", lineSale("POS", "0.30000000000000004", "1.99"), GB, "OWNER", null),
            201);
    assertThat(itemColumn("order_items", "order_id", sale.getString("id")), is("0.300/0.60"));
    assertThat(number(sale, "subtotal"), is(new BigDecimal("0.60")));
    // A dinar's fils and whole yen, by the same line rule.
    JsonObject kwd =
        data(
            post("/orders", lineSale("POS", "0.30000000000000004", "1.995"), KW, "OWNER", null),
            201);
    assertThat(itemColumn("order_items", "order_id", kwd.getString("id")), is("0.300/0.599"));
    JsonObject jpy =
        data(
            post("/orders", lineSale("POS", "0.30000000000000004", "199"), JP, "OWNER", null), 201);
    assertThat(itemColumn("order_items", "order_id", jpy.getString("id")), is("0.300/60"));

    // A pack labelled 0.37512 kg (GS1 AI 3105), sold at the label's reading: the line is 0.375 kg,
    // the gram below, charged 0.375 × 100.00 = £37.50 — held, kept and charged at one figure, which
    // the till's own £37.51 covers — never refused, never charged on a weight the stock never sees.
    JsonObject label =
        data(post("/orders", lineSale("POS", "0.37512", "100.00"), GB, "OWNER", null), 201);
    assertThat(itemColumn("order_items", "order_id", label.getString("id")), is("0.375/37.50"));
    assertThat(number(label, "subtotal"), is(new BigDecimal("37.50")));

    // Finer than a reading and no double's noise around one: refused, never rounded behind the
    // till's back — at the till, online (where no till adds anything up and no label is read, so
    // four places is already too fine), and for the back office's layaways, special orders and the
    // till's parked baskets. Nothing is written.
    long orders = rig().count("orders", "tenant_id='" + GB + "'");
    assertThat(
        code(post("/orders", lineSale("POS", "0.3755123", "1.99"), GB, "OWNER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(
        code(post("/orders", lineSale("POS", "0.0004", "1.99"), GB, "OWNER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(
        code(post("/orders", lineSale("ONLINE", "0.3755", "1.99"), GB, "OWNER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(
        code(post("/orders", lineSale("ONLINE", "0.37512", "1.99"), GB, "OWNER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(
        code(
            post("/orders", lineSale("ONLINE", "0.30000000000000004", "1.99"), GB, "OWNER", null),
            400),
        is("VALIDATION_FAILED"));
    assertThat(rig().count("orders", "tenant_id='" + GB + "'"), is(orders));

    String layawayFine = layaway("1.235", "1.001").replace("\"qty\":2", "\"qty\":2.0005");
    long layaways = rig().count("layaways", "tenant_id='" + KW + "'");
    assertThat(
        code(post("/layaways", layawayFine, KW, "MANAGER", null), 400), is("VALIDATION_FAILED"));
    assertThat(rig().count("layaways", "tenant_id='" + KW + "'"), is(layaways));

    String specialFine = special("333").replace("\"qty\":3", "\"qty\":0.0005");
    long specials = rig().count("special_orders", "tenant_id='" + JP + "'");
    assertThat(
        code(post("/admin/special-orders", specialFine, JP, "MANAGER", null), 400),
        is("VALIDATION_FAILED"));
    assertThat(rig().count("special_orders", "tenant_id='" + JP + "'"), is(specials));

    // A parked basket is the till's: its added weighings are the quantity they stand for, and a
    // quantity finer than that is refused.
    JsonObject parked =
        data(
            post(
                "/pos/parked-sales",
                parked("333", "0").replace("\"qty\":3", "\"qty\":0.30000000000000004"),
                JP,
                "CASHIER",
                null),
            201);
    assertThat(number(parked, "subtotal"), is(new BigDecimal("100")));
    // A label's 0.37512 kg is parked at 0.375, the gram below: 0.375 × ¥333 = ¥124.875, ¥125.
    JsonObject parkedLabel =
        data(
            post(
                "/pos/parked-sales",
                parked("333", "0").replace("\"qty\":3", "\"qty\":0.37512"),
                JP,
                "CASHIER",
                null),
            201);
    assertThat(number(parkedLabel, "subtotal"), is(new BigDecimal("125")));
    assertThat(
        parkedLabel.getJsonArray("items").getJsonObject(0).getJsonNumber("qty").bigDecimalValue(),
        is(new BigDecimal("0.375")));
    assertThat(
        code(
            post(
                "/pos/parked-sales",
                parked("333", "0").replace("\"qty\":3", "\"qty\":0.3755123"),
                JP,
                "CASHIER",
                null),
            400),
        is("VALIDATION_FAILED"));
    // A malformed basket is 400 whoever parks it: a cashier held to another store is told about
    // the basket, not the store, and nothing is parked.
    long parkedSales = rig().count("parked_sales", "tenant_id='" + JP + "'");
    assertThat(
        code(
            post(
                "/pos/parked-sales",
                parked("333", "0").replace("\"qty\":3", "\"qty\":0.3755123"),
                JP,
                "CASHIER",
                Ids.newId().toString()),
            400),
        is("VALIDATION_FAILED"));
    // A well-formed one at a store not theirs is still 403, nothing parked.
    assertThat(
        code(
            post("/pos/parked-sales", parked("333", "0"), JP, "CASHIER", Ids.newId().toString()),
            403),
        is("STORE_ACCESS_DENIED"));
    assertThat(rig().count("parked_sales", "tenant_id='" + JP + "'"), is(parkedSales));
  }

  /**
   * A till sale that already stands is retried from the offline queue under its key with a figure
   * this service now refuses (sales rung up before quantities were counted were rounded by the
   * column): the answer is the sale that stands, nothing new is written — and only to a caller who
   * keeps that sale's store.
   */
  @Test
  @DisplayName("A retried till sale that stands is answered, not refused over its quantity")
  void aRetriedSaleThatStandsIsAnsweredNotRefused() {
    String key = Ids.newId().toString();
    JsonObject first =
        data(
            rig()
                .as("/orders", GB, "MANAGER", MANAGER, STORE)
                .header("Idempotency-Key", key)
                .post(
                    Entity.entity(lineSale("POS", "0.375", "100.00"), MediaType.APPLICATION_JSON)),
            201);
    long orders = rig().count("orders", "tenant_id='" + GB + "'");

    JsonObject replay =
        data(
            rig()
                .as("/orders", GB, "MANAGER", MANAGER, STORE)
                .header("Idempotency-Key", key)
                .post(
                    Entity.entity(
                        lineSale("POS", "0.3755123", "100.00"), MediaType.APPLICATION_JSON)),
            201);
    assertThat(replay.getString("id"), is(first.getString("id")));
    assertThat(rig().count("orders", "tenant_id='" + GB + "'"), is(orders));

    // A manager held to another store gets the refusal, never the sale.
    Response elsewhere =
        rig()
            .as("/orders", GB, "MANAGER", MANAGER, Ids.newId().toString())
            .header("Idempotency-Key", key)
            .post(
                Entity.entity(lineSale("POS", "0.3755123", "100.00"), MediaType.APPLICATION_JSON));
    assertThat(code(elsewhere, 400), is("VALIDATION_FAILED"));
    // Another business's key space never reaches ours.
    Response other =
        rig()
            .as("/orders", OTHER, "OWNER", MANAGER, null)
            .header("Idempotency-Key", key)
            .post(
                Entity.entity(lineSale("POS", "0.3755123", "100.00"), MediaType.APPLICATION_JSON));
    assertThat(code(other, 400), is("VALIDATION_FAILED"));
    assertThat(rig().count("orders", "tenant_id='" + GB + "'"), is(orders));
  }

  // ── gift cards ──────────────────────────────────────────────────────────────

  private String issue(String tenant, String amount) {
    return data(
            post(
                "/gift-cards",
                "{\"storeId\":\"" + STORE + "\",\"amount\":" + amount + ",\"reason\":\"GOODWILL\"}",
                tenant,
                "MANAGER",
                null),
            201)
        .getString("code");
  }

  @Test
  @DisplayName("A gift card is no finer than its currency: fils for dinars, whole yen")
  void aGiftCardIsNoFinerThanItsCurrency() {
    String card = issue(KW, "10.125");
    JsonObject read = data(rig().get("/gift-cards/" + card, KW, "MANAGER", MANAGER), 200);
    assertThat(number(read, "currentBalance"), is(new BigDecimal("10.125")));

    long before = rig().count("gift_cards", "tenant_id='" + KW + "'");
    String body = "{\"storeId\":\"" + STORE + "\",\"amount\":10.1255,\"reason\":\"GOODWILL\"}";
    assertThat(
        code(post("/gift-cards", body, KW, "MANAGER", null), 400), is("GIFT_CARD_AMOUNT_INVALID"));
    assertThat(rig().count("gift_cards", "tenant_id='" + KW + "'"), is(before));

    assertThat(
        code(
            post(
                "/gift-cards",
                "{\"storeId\":\"" + STORE + "\",\"amount\":500.5,\"reason\":\"GOODWILL\"}",
                JP,
                "MANAGER",
                null),
            400),
        is("GIFT_CARD_AMOUNT_INVALID"));
    String yen = issue(JP, "500.00");
    assertThat(
        number(
            data(rig().get("/gift-cards/" + yen, JP, "MANAGER", MANAGER), 200), "currentBalance"),
        is(new BigDecimal("500")));

    // A reload finer than the dinar is refused and moves nothing.
    String reload = "{\"amount\":0.0005,\"reason\":\"GOODWILL\"}";
    assertThat(
        code(post("/gift-cards/" + card + "/reload", reload, KW, "MANAGER", null), 400),
        is("GIFT_CARD_AMOUNT_INVALID"));
    assertThat(
        number(
            data(rig().get("/gift-cards/" + card, KW, "MANAGER", MANAGER), 200), "currentBalance"),
        is(new BigDecimal("10.125")));
  }

  @Test
  @DisplayName("Another business never reaches our card, whatever store it names, nor a shopper")
  void anotherBusinessOrAShopperMovesNothing() {
    String card = issue(KW, "10.125");
    String reload = "{\"amount\":1.0005,\"reason\":\"GOODWILL\"}";
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      // Named our store, with an amount we would refuse: their answer is that there is no card,
      // never the amount — and nothing moved.
      assertThat(
          role,
          code(post("/gift-cards/" + card + "/reload", reload, OTHER, role, STORE), 404),
          is("GIFT_CARD_NOT_FOUND"));
    }
    String redeem =
        "{\"amount\":1.0005,\"orderId\":\"" + Ids.newId() + "\",\"reference\":\"till\"}";
    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(
          role,
          code(post("/gift-cards/" + card + "/redeem", redeem, OTHER, role, STORE), 404),
          is("ORDER_NOT_FOUND"));
    }
    assertThat(
        post("/gift-cards/" + card + "/reload", reload, KW, "CUSTOMER", null).getStatus(), is(403));
    assertThat(
        post("/gift-cards/" + card + "/redeem", redeem, KW, "CUSTOMER", null).getStatus(), is(403));
    assertThat(
        number(
            data(rig().get("/gift-cards/" + card, KW, "MANAGER", MANAGER), 200), "currentBalance"),
        is(new BigDecimal("10.125")));
    // This card's own entries: the rest of the class moves other cards of the same business.
    assertThat(
        rig()
            .count(
                "gift_card_transactions",
                "tenant_id='"
                    + KW
                    + "' AND gift_card_id = (SELECT id FROM \"order\".gift_cards WHERE tenant_id='"
                    + KW
                    + "' AND code='"
                    + card
                    + "') AND tx_type <> 'ISSUE'"),
        is(0L));
  }
}
