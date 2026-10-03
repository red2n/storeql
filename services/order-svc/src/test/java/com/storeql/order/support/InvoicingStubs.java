package com.storeql.order.support;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Violation;
import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * What the invoicing tests stand up where pricing-svc, customer-svc and product-svc would be: a
 * catalogue the quote prices and the resolve names, and the customers an invoice can name. Shared
 * by the invoice and the transport tests, so a business buyer is described once.
 */
public final class InvoicingStubs {

  private InvoicingStubs() {}

  /** What pricing-svc and product-svc know of an item. */
  public record Item(
      String name,
      String sku,
      String unit,
      String hsn,
      String unitPrice,
      String rate,
      String code,
      String vatOverride) {}

  public static final String V_STD = Ids.newId().toString();
  public static final String V_RED = Ids.newId().toString();
  public static final String V_ZERO = Ids.newId().toString();
  public static final String V_GST = Ids.newId().toString();
  public static final String V_NOHSN = Ids.newId().toString();
  public static final String V_ODD = Ids.newId().toString();
  public static final String V_PL = Ids.newId().toString();

  /** Priced like V_STD, but product-svc no longer names it: an invoice line would have no name. */
  public static final String V_NAMELESS = Ids.newId().toString();

  public static final Map<String, Item> CATALOGUE =
      Map.of(
          V_STD,
              new Item("Espresso beans 1kg", "BEANS-1", "KG", null, "10.00", "0.20", "STD", null),
          V_RED, new Item("Tea cakes", "CAKE-6", "PACK", null, "4.00", "0.05", "RED", null),
          V_ZERO, new Item("Bread", "LOAF", "EA", null, "2.50", "0", "ZERO", null),
          V_GST,
              new Item(
                  "Basmati rice 25kg", "RICE-25", "KG", "1006", "100.00", "0.18", "GST18", null),
          V_NOHSN, new Item("Jaggery", "JAG-1", "KG", null, "50.00", "0.18", "GST18", null),
          // A line whose recorded tax is more than its rate makes: 30.00 on 100.00 at 20%.
          V_ODD, new Item("Odd lot", "ODD", "EA", null, "100.00", "0.20", "STD", "30.00"),
          // Poland's standard rate.
          V_PL,
              new Item("Kawa ziarnista 1 kg", "KAWA-1", "KG", null, "100.00", "0.23", "PL23", null),
          V_NAMELESS, new Item(null, "NONAME", "EA", null, "10.00", "0.20", "STD", null));

  /** The services order-svc reads and delivers to, with the catalogue priced and named. */
  public static JsonStub services() {
    return JsonStub.start("pricing-svc", "customer-svc", "product-svc", "purchase-svc")
        .on("POST", "/prices/quote", InvoicingStubs::quote)
        .on("GET", "/admin/products/variants/resolve", InvoicingStubs::resolve);
  }

  /**
   * A customer the shop knows, as a VAT-registered business with a billing address. Nulls leave the
   * address, the registration or the electronic address out.
   *
   * @param address line1, city and pincode, or null for a customer with no addresses
   * @param vat the VAT number, or null for a customer pricing-svc has no registration for
   */
  public static JsonStub business(
      JsonStub stub,
      String customerId,
      String legalName,
      String vat,
      String country,
      String scheme,
      String endpointId,
      String[] address) {
    stub.on("GET", "/customers/" + customerId, 200, customer(customerId, "Ada", "Lovelace"));
    stub.on(
        "GET",
        "/customers/" + customerId + "/addresses",
        200,
        address == null
            ? "{\"data\":[]}"
            : addresses(customerId, country, address[0], address[1], address[2]));
    if (vat != null) {
      stub.on(
          "GET",
          "/customer-vat-status/" + customerId,
          200,
          vatStatus(customerId, vat, legalName, country, scheme, endpointId));
    }
    return stub;
  }

  /** pricing-svc's quote: each line at the catalogue's price and rate, in the order asked. */
  static JsonStub.Answer quote(JsonStub.Call call) {
    JsonArrayBuilder lines = Json.createArrayBuilder();
    try (JsonReader r = Json.createReader(new StringReader(call.body()))) {
      for (JsonValue v : r.readObject().getJsonArray("lines")) {
        JsonObject l = v.asJsonObject();
        Item item = CATALOGUE.get(l.getString("variantId"));
        if (item == null) {
          return new JsonStub.Answer(
              404, "{\"error\":{\"code\":\"PRICING_NO_PRICE\",\"message\":\"no price\"}}");
        }
        BigDecimal qty = l.getJsonNumber("qty").bigDecimalValue();
        BigDecimal lineTotal = money(new BigDecimal(item.unitPrice()).multiply(qty));
        BigDecimal vat =
            item.vatOverride() != null
                ? new BigDecimal(item.vatOverride())
                : money(lineTotal.multiply(new BigDecimal(item.rate())));
        lines.add(
            Json.createObjectBuilder()
                .add("variantId", l.getString("variantId"))
                .add("qty", qty)
                .add("lineTotal", lineTotal)
                .add("discount", BigDecimal.ZERO)
                .add("vatAmount", vat)
                .add("vatCode", item.code())
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

  /** product-svc's resolve: the items asked for that the catalogue knows. */
  static JsonStub.Answer resolve(JsonStub.Call call) {
    JsonArrayBuilder out = Json.createArrayBuilder();
    String ids = call.query() == null ? "" : call.query().replaceFirst("^.*ids=", "");
    for (String id : ids.split("(,|%2C)")) {
      Item item = CATALOGUE.get(id);
      // An item the catalogue holds no name for is one product-svc does not name: left out.
      if (item == null || item.name() == null) continue;
      JsonObjectBuilder o =
          Json.createObjectBuilder()
              .add("variantId", id)
              .add("productName", item.name())
              .add("sku", item.sku())
              .add("unit", item.unit());
      if (item.hsn() != null) o.add("hsnCode", item.hsn());
      out.add(o);
    }
    return JsonStub.Answer.ok(out.build().toString());
  }

  static String vatStatus(
      String customer, String vat, String legalName, String country, String scheme, String id) {
    JsonObjectBuilder o =
        Json.createObjectBuilder()
            .add("customerId", customer)
            .add("vatNumber", vat)
            .add("vatRegistered", true)
            .add("reverseChargeEligible", false)
            .add("countryCode", country)
            .add("legalName", legalName);
    if (scheme != null) o.add("einvoiceScheme", scheme).add("einvoiceId", id);
    return "{\"data\":" + o.build() + "}";
  }

  static String customer(String id, String first, String last) {
    return "{\"data\":{\"id\":\""
        + id
        + "\",\"firstName\":\""
        + first
        + "\",\"lastName\":\""
        + last
        + "\",\"status\":\"ACTIVE\"}}";
  }

  /** A home address first, then the billing one: the invoice must pick the billing one. */
  static String addresses(
      String customer, String country, String line1, String city, String pincode) {
    return "{\"data\":[{\"id\":\""
        + Ids.newId()
        + "\",\"customerId\":\""
        + customer
        + "\",\"type\":\"HOME\",\"line1\":\"7 Somewhere Else\",\"city\":\"Elsewhere\","
        + "\"country\":\""
        + country
        + "\",\"pincode\":\"XX1 1XX\",\"isDefault\":true},{\"id\":\""
        + Ids.newId()
        + "\",\"customerId\":\""
        + customer
        + "\",\"type\":\"BILLING\",\"line1\":\""
        + line1
        + "\",\"city\":\""
        + city
        + "\",\"country\":\""
        + country
        + "\",\"pincode\":\""
        + pincode
        + "\",\"isDefault\":false}]}";
  }

  public static BigDecimal money(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP);
  }

  // ── reading answers ────────────────────────────────────────────────────────

  public static JsonObject data(Response r) {
    return envelope(r).getJsonObject("data");
  }

  public static JsonArray dataArray(Response r) {
    return envelope(r).getJsonArray("data");
  }

  public static JsonObject envelope(Response r) {
    String body = r.readEntity(String.class);
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }

  public static String code(Response r) {
    return envelope(r).getJsonObject("error").getString("code");
  }

  /** A till basket: variant and quantity pairs; the quote prices it. */
  public static String basket(String store, String customer, String currency, String... lines) {
    JsonArrayBuilder items = Json.createArrayBuilder();
    for (int i = 0; i < lines.length; i += 2) {
      items.add(
          Json.createObjectBuilder()
              .add("variantId", lines[i])
              .add("qty", new BigDecimal(lines[i + 1])));
    }
    JsonObjectBuilder o =
        Json.createObjectBuilder()
            .add("storeId", store)
            .add("channel", "POS")
            .add("fulfilmentType", "INSTORE")
            .add("currency", currency)
            .add("items", items);
    if (customer != null) o.add("customerId", customer);
    return o.build().toString();
  }

  /** Waits up to twenty seconds for something that happens in the background. */
  public static <T> T eventually(Supplier<T> probe) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      T got = probe.get();
      if (got != null) return got;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  /** The one document of the kind in a list, asserting there is exactly one. */
  public static JsonObject only(JsonArray docs, String kind) {
    List<JsonObject> matching =
        docs.stream()
            .map(JsonValue::asJsonObject)
            .filter(d -> kind.equals(d.getString("kind")))
            .toList();
    assertThat(docs.toString(), matching, hasSize(1));
    return matching.get(0);
  }

  public static List<String> fatal(List<Violation> violations) {
    return violations.stream()
        .filter(Violation::isFatal)
        .map(v -> v.rule() + ": " + v.message())
        .toList();
  }

  /** The document in a download, read back by the shared module and holding every rule. */
  public static EInvoices.Received readBack(Response r) {
    byte[] bytes = r.readEntity(byte[].class);
    EInvoices.Received received = EInvoices.read(bytes);
    assertThat(fatal(EInvoices.validate(received)), is(List.of()));
    return received;
  }

  public static BigDecimal amount(JsonObject o, String key) {
    return o.getJsonNumber(key).bigDecimalValue().setScale(2, RoundingMode.HALF_UP);
  }
}
