package com.storeql.order.support;

import static com.storeql.test.Envelopes.parse;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the return-controls tests share: a pricing-svc stand-in with a small catalogue, the calls a
 * member of staff makes at the till, and reads of what a service wrote (rows and outbox events).
 */
public final class ReturnsRig {

  /** A catalogue item: what pricing-svc says one costs and the VAT rate it carries. */
  public record Item(String unitPrice, String rate) {}

  /** Ten each, no VAT. */
  public static final String V_A = "01a0a1c3-3333-7000-8000-000000000001";

  /** Twenty-five each, no VAT. */
  public static final String V_B = "01a0a1c3-3333-7000-8000-000000000002";

  /** Four each, no VAT. */
  public static final String V_C = "01a0a1c3-3333-7000-8000-000000000003";

  /** Ten each before 20% VAT: twelve at the till. */
  public static final String V_TAX = "01a0a1c3-3333-7000-8000-000000000004";

  private static final Map<String, Item> CATALOGUE =
      Map.of(
          V_A, new Item("10.00", "0"),
          V_B, new Item("25.00", "0"),
          V_C, new Item("4.00", "0"),
          V_TAX, new Item("10.00", "0.20"));

  private final WebTarget target;
  private final OrderService orders;
  private final PostgresSupport pg;

  public ReturnsRig(WebTarget target, OrderService orders, PostgresSupport pg) {
    this.target = target;
    this.orders = orders;
    this.pg = pg;
  }

  /**
   * pricing-svc as the tests need it: a quote (for placing a sale) and a resolve (for a return with
   * no receipt), both at the catalogue's price and rate. While {@code down} is set, resolve answers
   * 503.
   */
  public static JsonStub pricing(AtomicBoolean down) {
    return JsonStub.start("pricing-svc")
        .on("POST", "/prices/quote", ReturnsRig::quote)
        .on(
            "POST",
            "/prices/resolve",
            call ->
                down.get()
                    ? new JsonStub.Answer(503, "{\"error\":{\"code\":\"DOWN\",\"message\":\"x\"}}")
                    : resolve(call));
  }

  private static JsonStub.Answer quote(JsonStub.Call call) {
    var lines = Json.createArrayBuilder();
    try (JsonReader r = Json.createReader(new StringReader(call.body()))) {
      for (JsonValue v : r.readObject().getJsonArray("lines")) {
        JsonObject l = v.asJsonObject();
        Item item = CATALOGUE.get(l.getString("variantId"));
        if (item == null) {
          return new JsonStub.Answer(
              404, "{\"error\":{\"code\":\"PRICING_NO_PRICE\",\"message\":\"no price\"}}");
        }
        BigDecimal qty = l.getJsonNumber("qty").bigDecimalValue();
        BigDecimal lineTotal = new BigDecimal(item.unitPrice()).multiply(qty);
        lines.add(
            Json.createObjectBuilder()
                .add("variantId", l.getString("variantId"))
                .add("qty", qty)
                .add("lineTotal", lineTotal)
                .add("discount", BigDecimal.ZERO)
                .add("vatAmount", lineTotal.multiply(new BigDecimal(item.rate())))
                .add("vatCode", "STD")
                .add("vatRate", new BigDecimal(item.rate())));
      }
    }
    return JsonStub.Answer.ok(
        Json.createObjectBuilder()
            .add("lines", lines)
            .add("basketDiscount", BigDecimal.ZERO)
            .build()
            .toString());
  }

  private static JsonStub.Answer resolve(JsonStub.Call call) {
    try (JsonReader r = Json.createReader(new StringReader(call.body()))) {
      Item item = CATALOGUE.get(r.readObject().getString("variantId"));
      if (item == null) return new JsonStub.Answer(404, "{}");
      BigDecimal unit = new BigDecimal(item.unitPrice());
      return JsonStub.Answer.ok(
          Json.createObjectBuilder()
              .add("unitPrice", unit)
              .add("vatAmount", unit.multiply(new BigDecimal(item.rate())))
              .build()
              .toString());
    }
  }

  // ── calls ─────────────────────────────────────────────────────────────────

  public Invocation.Builder as(String path, String tenant, String roles, String user) {
    // A query string is given as parameters: path() would escape the question mark.
    int q = path.indexOf('?');
    var t = target.path(q < 0 ? path : path.substring(0, q));
    if (q >= 0) {
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        t = t.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    var b = t.request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (user != null) b = b.header("X-User-Id", user);
    return b;
  }

  public Response post(
      String path, String json, String tenant, String roles, String user, String key) {
    var b = as(path, tenant, roles, user);
    if (key != null) b = b.header("Idempotency-Key", key);
    return b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  public Response get(String path, String tenant, String roles, String user) {
    return as(path, tenant, roles, user).get();
  }

  public Response put(String path, String json, String tenant, String roles, String user) {
    return as(path, tenant, roles, user).put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  public static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return parse(body).getJsonObject("data");
  }

  /**
   * A till sale of {@code qty} of a catalogue item, placed by {@code cashier} and paid in full, so
   * it is FULFILLED and can be returned. The customer may be null.
   *
   * @return the order id
   */
  public String sale(
      String tenant, String store, String variant, int qty, String customer, String cashier) {
    Response r =
        post(
            "/orders",
            "{\"storeId\":\""
                + store
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + variant
                + "\",\"qty\":"
                + qty
                + "}]"
                + (customer == null ? "" : ",\"customerId\":\"" + customer + "\"")
                + "}",
            tenant,
            "MANAGER",
            cashier,
            Ids.newId().toString());
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    JsonObject order = parse(body).getJsonObject("data");
    orders.handlePaymentCaptured(
        Ids.parse(tenant),
        Ids.parse(order.getString("id")),
        Ids.newId(),
        order.getJsonNumber("total").bigDecimalValue());
    return order.getString("id");
  }

  // ── reads ─────────────────────────────────────────────────────────────────

  /** One value from the service's schema; the SQL names its own tables. */
  public String one(String sql) {
    return scalar(pg, sql);
  }

  public long count(String table, String where) {
    return Long.parseLong(one("SELECT count(*) FROM \"order\"." + table + " WHERE " + where));
  }

  public long events(String aggregate, String type) {
    return count("outbox", "aggregate_id='" + aggregate + "' AND event_type='" + type + "'");
  }

  public JsonObject event(String aggregate, String type) {
    return parse(
        one(
            "SELECT payload FROM \"order\".outbox WHERE aggregate_id='"
                + aggregate
                + "' AND event_type='"
                + type
                + "' ORDER BY created_at DESC LIMIT 1"));
  }

  /** Puts the moment the goods left the store {@code days} days back. */
  public void backdate(String order, int days) {
    com.storeql.test.Envelopes.exec(
        pg,
        "UPDATE \"order\".order_status_history SET changed_at = now() - interval '"
            + days
            + " days' WHERE order_id='"
            + order
            + "' AND to_status IN ('FULFILLED','PARTIALLY_FULFILLED')");
  }

  public static boolean nullish(JsonObject o, String key) {
    return !o.containsKey(key) || o.get(key).getValueType() == JsonValue.ValueType.NULL;
  }
}
