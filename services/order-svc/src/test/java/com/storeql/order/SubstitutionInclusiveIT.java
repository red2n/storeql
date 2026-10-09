package com.storeql.order;

import static com.storeql.order.support.ShelfPricing.BREAD;
import static com.storeql.order.support.ShelfPricing.CHEESE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ShelfPricing;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An online order at shelf prices (intent/vat-inclusive-pricing.md) whose line runs short or is
 * replaced: what stands of the line is what was paid for those units, with the VAT inside it, the
 * order still adds up, a substitute is charged no more than the original, and the money that goes
 * back is the difference. Another business sees and changes none of it.
 */
@HelidonTest
class SubstitutionInclusiveIT {

  private static final String T = "01a0b2c5-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0b2c5-611e-702c-a97b-d1b8025478e2";
  private static final String S = "01a0b2c5-611e-703c-a378-a4972ea461e1";
  private static final String SHOPPER = "01a0b2c5-611e-700b-bde4-50df0324c3e1";
  private static final String STAFF = "01a0b2c5-611e-700b-bde4-50df0324c3e3";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;
  private static final JsonStub PEERS;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "GBP", "GB").with(T2, "GBP", "GB");
    PRICING = ShelfPricing.start();
    PEERS =
        JsonStub.start("inventory-svc", "product-svc")
            .on(
                "GET",
                "/admin/inventory/network/stock",
                200,
                "{\"data\":{\"levels\":[{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + BREAD
                    + "\",\"available\":9}],\"dropship\":[]}}")
            .on(
                "GET",
                "/admin/products/variants/resolve",
                200,
                "{\"data\":[{\"variantId\":\""
                    + CHEESE
                    + "\",\"productName\":\"Cheese\",\"sku\":\"CHE\"},{\"variantId\":\""
                    + BREAD
                    + "\",\"productName\":\"Bread\",\"sku\":\"BRE\"}]}");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService svc;

  @AfterAll
  static void stop() {
    System.clearProperty("storeql.order.pricing.enforce");
    PRICING.close();
    PEERS.close();
    PG.stop();
  }

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String stores) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("Idempotency-Key", Ids.newId().toString());
    if (user != null) b = b.header("X-User-Id", user);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return "GET".equals(method)
        ? b.get()
        : b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
  }

  private Response picker(String method, String path, String json) {
    return call(method, path, json, T, STAFF, "CASHIER", S);
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

  /** Three cheeses at 12.00 and 20%: 36.00 with 6.00 VAT inside, paid and confirmed. */
  private UUID threeCheeses() {
    String body =
        "{\"storeId\":\""
            + S
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\",\"currency\":\"GBP\","
            + "\"contactPhone\":\"07700900123\",\"items\":[{\"variantId\":\""
            + CHEESE
            + "\",\"qty\":3}]}";
    JsonObject placed =
        Envelopes.created(call("POST", "/orders", body, T, SHOPPER, "CUSTOMER", null));
    UUID id = Ids.parse(placed.getString("id"));
    assertThat(money(placed, "total"), comparesEqualTo(new BigDecimal("36.00")));
    assertThat(placed.getBoolean("taxInclusive"), is(true));
    svc.handlePaymentCaptured(Ids.parse(T), id, Ids.newId(), new BigDecimal("36.00"));
    return id;
  }

  private JsonObject order(UUID id) {
    return Envelopes.ok(call("GET", "/orders/" + id, null, T, STAFF, "OWNER", null));
  }

  private void identities(JsonObject o) {
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    BigDecimal paid = BigDecimal.ZERO;
    for (JsonValue v : o.getJsonArray("items")) {
      JsonObject l = v.asJsonObject();
      net = net.add(money(l, "lineTotal"));
      vat = vat.add(money(l, "vatAmount"));
      paid = paid.add(money(l, "grossTotal"));
      assertThat(
          money(l, "lineTotal").add(money(l, "vatAmount")),
          comparesEqualTo(money(l, "grossTotal")));
    }
    assertThat(money(o, "subtotal"), comparesEqualTo(net));
    assertThat(money(o, "taxAmount"), comparesEqualTo(vat));
    assertThat(money(o, "total"), comparesEqualTo(paid));
  }

  @Test
  @DisplayName("Closing one of three short leaves what was paid for two, with the VAT inside it")
  void aShortCloseKeepsWhatWasPaidForWhatStands() {
    UUID id = threeCheeses();

    JsonObject after =
        Envelopes.ok(
            picker(
                "POST",
                "/orders/" + id + "/lines/" + CHEESE + "/short",
                "{\"qty\":1,\"reason\":\"last one mouldy\"}"));

    assertThat(money(after, "total"), comparesEqualTo(new BigDecimal("24.00")));
    assertThat(money(after, "taxAmount"), comparesEqualTo(new BigDecimal("4.00")));
    assertThat(money(line(after, CHEESE), "grossTotal"), comparesEqualTo(new BigDecimal("24.00")));
    assertThat(money(line(after, CHEESE), "lineTotal"), comparesEqualTo(new BigDecimal("20.00")));
    identities(after);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT refund_amount FROM \"order\".order_line_adjustments WHERE order_id = '"
                + id
                + "'"),
        is("12.00")); // The VAT inside the 12.00 that goes back travels with it, for the ledger.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT (payload::jsonb->>'vatAmount')::numeric(12,2)::text FROM \"order\".outbox WHERE aggregate_id = '"
                + id
                + "' AND event_type = 'OrderLineShortClosed'"),
        is("2.00"));
  }

  @Test
  @DisplayName("A dearer substitute is held to the original's price, a cheaper one charged its own")
  void aSubstituteIsChargedNoMoreThanTheOriginal() {
    UUID id = threeCheeses();

    // Bread at 1.29 for one cheese worth 12.00: the shopper pays 1.29 and 10.71 goes back.
    JsonObject after =
        Envelopes.ok(
            picker(
                "POST",
                "/orders/" + id + "/lines/" + CHEESE + "/substitute",
                "{\"substituteVariantId\":\"" + BREAD + "\",\"qty\":1}"));

    assertThat(money(after, "total"), comparesEqualTo(new BigDecimal("25.29")));
    assertThat(money(line(after, BREAD), "grossTotal"), comparesEqualTo(new BigDecimal("1.29")));
    assertThat(money(line(after, BREAD), "vatAmount"), comparesEqualTo(new BigDecimal("0.22")));
    assertThat(money(line(after, CHEESE), "grossTotal"), comparesEqualTo(new BigDecimal("24.00")));
    identities(after);
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT refund_amount FROM \"order\".order_line_adjustments WHERE order_id = '"
                + id
                + "'"),
        is("10.71"));
    // 36.00 held 6.00 of VAT; 25.29 holds 4.22 (4.00 in the cheese, 0.22 in the bread): 1.78 goes
    // back.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT (payload::jsonb->>'vatAmount')::numeric(12,2)::text FROM \"order\".outbox WHERE aggregate_id = '"
                + id
                + "' AND event_type = 'OrderLineSubstituted'"),
        is("1.78"));
  }

  @Test
  @DisplayName("Another business's staff can close or substitute nothing here")
  void anotherBusinessChangesNothing() {
    UUID id = threeCheeses();

    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      assertThat(
          role,
          call(
                  "POST",
                  "/orders/" + id + "/lines/" + CHEESE + "/short",
                  "{\"qty\":1}",
                  T2,
                  STAFF,
                  role,
                  null)
              .getStatus(),
          is(404));
    }
    assertThat(money(order(id), "total"), comparesEqualTo(new BigDecimal("36.00")));
  }
}
