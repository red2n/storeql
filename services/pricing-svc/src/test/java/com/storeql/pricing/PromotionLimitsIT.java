package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A promotion's usage limits, from quote to ledger: {@code maxRedemptions} is the total times a
 * coupon may be used and {@code maxPerCustomer} the times one shopper may, counted from the
 * append-only redemption ledger. Quoting spends nothing; a placed order spends one, once, however
 * often it is replayed; a limit reached is said at the next quote with its reason.
 */
@HelidonTest
class PromotionLimitsIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String T = Ids.newId().toString();
  private static final String OTHER = Ids.newId().toString();
  private static final String V = Ids.newId().toString();

  static {
    PG = PostgresSupport.start();
    TENANTS = TenantSvcStub.start().with(T, "GBP", "GB").with(OTHER, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE pricing.promotion_redemptions, pricing.promotion_items,"
              + " pricing.promotions, pricing.promotion_status_changes,"
              + " pricing.price_list_items, pricing.price_lists,"
              + " pricing.product_vat_categories, pricing.vat_rates, pricing.outbox CASCADE");
    }
    // A priced variant to quote: 100.00, so a 10% basket coupon is worth 10.00.
    send(
        "POST",
        "/vat-rates",
        "{\"code\":\"T1\",\"name\":\"Standard\",\"rate\":0.20,\"exempt\":false,"
            + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
        T,
        "OWNER");
    send(
        "POST",
        "/product-vat-categories",
        "{\"variantId\":\"" + V + "\",\"vatCode\":\"T1\"}",
        T,
        "OWNER");
    String list =
        id(
            send(
                "POST",
                "/admin/price-lists",
                "{\"name\":\"Limits\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                    + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
                T,
                "OWNER"));
    send(
        "POST",
        "/admin/price-lists/" + list + "/items",
        "{\"variantId\":\"" + V + "\",\"price\":100.00,\"minQty\":1}",
        T,
        "OWNER");
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response sendR(String method, String path, String json, String tenant, String roles) {
    var b = target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", roles);
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String send(String method, String path, String json, String tenant, String roles) {
    Response r = sendR(method, path, json, tenant, roles);
    String body = r.readEntity(String.class);
    assertThat(path + " " + body, r.getStatus() < 300, is(true));
    return body;
  }

  private static String id(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    return json.substring(start, json.indexOf('"', start));
  }

  /** A 10% basket coupon with the limits given (null = none), scoped to everything. */
  private String coupon(String code, Integer max, Integer perCustomer) {
    String json =
        "{\"name\":\"Coupon "
            + code
            + "\",\"type\":\"BASKET_PERCENT\",\"value\":10,\"couponCode\":\""
            + code
            + "\",\"startsAt\":\"2020-01-01T00:00:00Z\""
            + (max == null ? "" : ",\"maxRedemptions\":" + max)
            + (perCustomer == null ? "" : ",\"maxPerCustomer\":" + perCustomer)
            + "}";
    String promo = id(send("POST", "/admin/promotions", json, T, "OWNER"));
    send("POST", "/admin/promotions/" + promo + "/items", "{\"scopeType\":\"ALL\"}", T, "OWNER");
    return promo;
  }

  private String quote(String code, String customer) {
    return send(
        "POST",
        "/prices/quote",
        "{\"lines\":[{\"variantId\":\""
            + V
            + "\",\"qty\":1}],\"couponCodes\":[\""
            + code
            + "\"]"
            + (customer == null ? "" : ",\"customerId\":\"" + customer + "\"")
            + "}",
        T,
        "OWNER");
  }

  private String redeem(String promo, String order, String customer, String tenant) {
    return send(
        "POST",
        "/prices/redemptions",
        "{\"orderId\":\""
            + order
            + "\",\"currency\":\"GBP\""
            + (customer == null ? "" : ",\"customerId\":\"" + customer + "\"")
            + ",\"appliedPromotions\":[{\"promotionId\":\""
            + promo
            + "\",\"name\":\"c\",\"amount\":10.00}]}",
        tenant,
        "OWNER");
  }

  private static int ledger(String promo) throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement();
        var rs =
            st.executeQuery(
                "SELECT count(*) FROM pricing.promotion_redemptions WHERE promotion_id = '"
                    + promo
                    + "'::uuid")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  // ── the total limit ────────────────────────────────────────────────────────

  @Test
  @DisplayName("A coupon with maxRedemptions 2 works twice, and the third quote says it is spent")
  void theTotalLimitIsCountedFromPlacedOrders() throws Exception {
    String promo = coupon("TWICE", 2, null);

    // Looking at it spends nothing, however often.
    for (int i = 0; i < 4; i++)
      assertThat(quote("TWICE", null), containsString("\"totalDiscount\":10.00"));
    assertThat(ledger(promo), is(0));

    assertThat(redeem(promo, Ids.newId().toString(), null, T), containsString("\"recorded\":1"));
    assertThat(quote("TWICE", null), containsString("\"totalDiscount\":10.00"));
    assertThat(redeem(promo, Ids.newId().toString(), null, T), containsString("\"recorded\":1"));

    String spent = quote("TWICE", null);
    assertThat(spent, containsString("\"totalDiscount\":0"));
    assertThat(spent, containsString("COUPON_EXHAUSTED"));
    assertThat(ledger(promo), is(2));
  }

  @Test
  @DisplayName("Replaying one order's redemption, even at once, spends one use")
  void aReplayedOrderSpendsOneUse() throws Exception {
    String promo = coupon("ONCE", 1, null);
    String order = Ids.newId().toString();
    assertThat(redeem(promo, order, null, T), containsString("\"recorded\":1"));
    assertThat(redeem(promo, order, null, T), containsString("\"recorded\":0"));
    assertThat(ledger(promo), is(1));
    assertThat(quote("ONCE", null), containsString("COUPON_EXHAUSTED"));

    // The same order arriving twice at the same moment: one row.
    String again = coupon("RACE-SAME", 5, null);
    String same = Ids.newId().toString();
    assertThat(racing(again, same, same), is(1));
  }

  /** Two placed orders reach pricing-svc together; returns how many redemptions were recorded. */
  private int racing(String promo, String orderA, String orderB) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      Future<String> a =
          pool.submit(
              () -> {
                go.await();
                return redeem(promo, orderA, null, T);
              });
      Future<String> b =
          pool.submit(
              () -> {
                go.await();
                return redeem(promo, orderB, null, T);
              });
      go.countDown();
      a.get();
      b.get();
    } finally {
      pool.shutdownNow();
    }
    return ledger(promo);
  }

  @Test
  @DisplayName(
      "Two checkouts racing for the last use are both on the ledger, and the coupon is spent")
  void twoCheckoutsRacingForTheLastUseAreBothRecordedAndTheNextQuoteIsRefused() throws Exception {
    // The order is placed (and, at a till, paid) before its redemption is written, so the
    // ledger records what happened rather than refusing an order that already has its discount;
    // what the limit guarantees is that nothing further is quoted once it is reached.
    String promo = coupon("LAST", 1, null);
    assertThat(quote("LAST", null), containsString("\"totalDiscount\":10.00"));
    int rows = racing(promo, Ids.newId().toString(), Ids.newId().toString());
    assertThat("one row per order, no more, no fewer", rows, is(2));
    assertThat(quote("LAST", null), containsString("COUPON_EXHAUSTED"));
    assertThat(quote("LAST", Ids.newId().toString()), containsString("COUPON_EXHAUSTED"));
  }

  // ── the per-customer limit ─────────────────────────────────────────────────

  @Test
  @DisplayName("maxPerCustomer 1 stops that shopper's second use only, and never binds a guest")
  void thePerCustomerLimitBindsTheCustomerAlone() throws Exception {
    String promo = coupon("MINE", null, 1);
    String ada = Ids.newId().toString();
    String bob = Ids.newId().toString();

    assertThat(quote("MINE", ada), containsString("\"totalDiscount\":10.00"));
    redeem(promo, Ids.newId().toString(), ada, T);

    String second = quote("MINE", ada);
    assertThat(second, containsString("\"totalDiscount\":0"));
    assertThat(second, containsString("COUPON_LIMIT_REACHED"));

    assertThat(
        "another shopper still may", quote("MINE", bob), containsString("\"totalDiscount\":10.00"));
    String guest = quote("MINE", null);
    assertThat(
        "a guest has no identity to count against",
        guest,
        containsString("\"totalDiscount\":10.00"));
    assertThat(guest, not(containsString("COUPON_LIMIT_REACHED")));
    assertThat(ledger(promo), is(1));
  }

  @Test
  @DisplayName("Both limits together: the tighter one that is reached first refuses")
  void bothLimitsTogether() throws Exception {
    String promo = coupon("BOTH", 3, 2);
    String ada = Ids.newId().toString();
    String bob = Ids.newId().toString();
    redeem(promo, Ids.newId().toString(), ada, T);
    redeem(promo, Ids.newId().toString(), ada, T);
    assertThat(quote("BOTH", ada), containsString("COUPON_LIMIT_REACHED"));
    assertThat(quote("BOTH", bob), containsString("\"totalDiscount\":10.00"));
    redeem(promo, Ids.newId().toString(), bob, T);
    assertThat(quote("BOTH", bob), containsString("COUPON_EXHAUSTED"));
  }

  // ── inputs and isolation ───────────────────────────────────────────────────

  @Test
  @DisplayName("A limit below one is refused at creation; leaving it out means no limit")
  void aLimitBelowOneIsRefused() {
    for (String bad :
        new String[] {"\"maxRedemptions\":0", "\"maxRedemptions\":-3", "\"maxPerCustomer\":0"}) {
      Response r =
          sendR(
              "POST",
              "/admin/promotions",
              "{\"name\":\"Bad\",\"type\":\"BASKET_PERCENT\",\"value\":10,\"couponCode\":\"BAD\","
                  + "\"startsAt\":\"2020-01-01T00:00:00Z\","
                  + bad
                  + "}",
              T,
              "OWNER");
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(400));
      assertThat(body, containsString("PRICING_INVALID_LIMIT"));
    }
    coupon("UNLIMITED", null, null);
  }

  @Test
  @DisplayName("Another business cannot spend, or see the spend of, our coupon")
  void anotherBusinessCannotSpendOurCoupon() throws Exception {
    String promo = coupon("OURS", 1, null);
    // Naming our promotion from another business records nothing, for us or for them.
    assertThat(
        redeem(promo, Ids.newId().toString(), null, OTHER), containsString("\"recorded\":0"));
    assertThat(ledger(promo), is(0));
    assertThat(quote("OURS", null), containsString("\"totalDiscount\":10.00"));

    // And their quote naming our code finds no such coupon.
    Response theirs =
        sendR(
            "POST",
            "/prices/quote",
            "{\"lines\":[{\"variantId\":\"" + V + "\",\"qty\":1}],\"couponCodes\":[\"OURS\"]}",
            OTHER,
            "OWNER");
    assertThat(theirs.readEntity(String.class), not(containsString("\"totalDiscount\":10.00")));

    // Lower roles cannot create or switch a promotion, so cannot lift a limit either.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertThat(
          sendR(
                  "POST",
                  "/admin/promotions",
                  "{\"name\":\"x\",\"type\":\"BASKET_PERCENT\",\"value\":10,"
                      + "\"startsAt\":\"2020-01-01T00:00:00Z\"}",
                  T,
                  role)
              .getStatus(),
          is(403));
    }
  }

  @Test
  @DisplayName("Another business's staff, of any role, cannot read, switch or reprice our things")
  void promotionsAndPriceListsDoNotCrossBusinesses() throws Exception {
    String promo = coupon("FENCED", null, null);
    String list =
        id(
            send(
                "POST",
                "/admin/price-lists",
                "{\"name\":\"Fenced\",\"channel\":\"POS\",\"currency\":\"GBP\","
                    + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
                T,
                "OWNER"));

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String action : new String[] {"deactivate", "activate"}) {
        Response r =
            sendR(
                "POST",
                "/admin/promotions/" + promo + "/" + action,
                "{\"reason\":\"nope\"}",
                OTHER,
                role);
        String refused = r.readEntity(String.class);
        assertThat(refused, r.getStatus(), is(404));
        assertThat(refused, containsString("PRICING_SUBJECT_NOT_FOUND"));
        r =
            sendR(
                "POST",
                "/admin/price-lists/" + list + "/" + action,
                "{\"reason\":\"nope\"}",
                OTHER,
                role);
        refused = r.readEntity(String.class);
        assertThat(refused, r.getStatus(), is(404));
        assertThat(refused, containsString("PRICING_SUBJECT_NOT_FOUND"));
      }
      Response read = sendR("GET", "/price-lists/" + list, null, OTHER, role);
      String readBody = read.readEntity(String.class);
      assertThat(readBody, read.getStatus(), is(404));
      assertThat(readBody, containsString("PRICING_LIST_NOT_FOUND"));
      Response item =
          sendR(
              "POST",
              "/admin/price-lists/" + list + "/items",
              "{\"variantId\":\"" + V + "\",\"price\":0.01,\"minQty\":1}",
              OTHER,
              role);
      String itemBody = item.readEntity(String.class);
      assertThat(itemBody, item.getStatus(), is(404));
      assertThat(itemBody, containsString("PRICING_LIST_NOT_FOUND"));
    }
    // Lower roles: a shopper cannot switch a promotion of ours, nor can staff below management.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      for (String tenant : new String[] {T, OTHER}) {
        assertThat(
            sendR(
                    "POST",
                    "/admin/promotions/" + promo + "/deactivate",
                    "{\"reason\":\"nope\"}",
                    tenant,
                    role)
                .getStatus(),
            is(403));
        assertThat(
            sendR(
                    "POST",
                    "/admin/promotions/" + promo + "/activate",
                    "{\"reason\":\"nope\"}",
                    tenant,
                    role)
                .getStatus(),
            is(403));
      }
    }
    assertThat(
        "nothing was switched", quote("FENCED", null), containsString("\"totalDiscount\":10.00"));
    String history =
        send("GET", "/admin/promotions/" + promo + "/status-history", null, T, "OWNER");
    assertThat(history, not(containsString("nope")));
  }

  @Test
  @DisplayName("A variant with no price in force resolves to a 404 naming it")
  void noPriceIsA404() {
    Response r =
        sendR(
            "POST",
            "/prices/resolve",
            "{\"variantId\":\"" + Ids.newId() + "\",\"channel\":\"ALL\",\"qty\":1}",
            T,
            "CASHIER");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(404));
    assertThat(body, containsString("PRICING_PRICE_NOT_FOUND"));
  }

  @Test
  @DisplayName("A price list is created in an ISO 4217 currency only; lower case is read as upper")
  void aPriceListCurrencyMustBeAnIsoCode() {
    for (String bad : new String[] {"ZZZ", "US", "GBPP", "12A"}) {
      Response r =
          sendR(
              "POST",
              "/admin/price-lists",
              "{\"name\":\"Odd\",\"channel\":\"ALL\",\"currency\":\""
                  + bad
                  + "\",\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
              T,
              "OWNER");
      String body = r.readEntity(String.class);
      assertThat(bad + " " + body, r.getStatus(), is(400));
      assertThat(body, containsString("CURRENCY_INVALID"));
    }
    Response ok =
        sendR(
            "POST",
            "/admin/price-lists",
            "{\"name\":\"Lower\",\"channel\":\"ALL\",\"currency\":\" eur \","
                + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
            T,
            "OWNER");
    String body = ok.readEntity(String.class);
    assertThat(body, ok.getStatus(), is(201));
    assertThat(body, containsString("\"currency\":\"EUR\""));
  }
}
