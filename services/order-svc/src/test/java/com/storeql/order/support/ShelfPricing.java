package com.storeql.order.support;

import com.storeql.money.TaxInclusive;
import com.storeql.test.JsonStub;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * pricing-svc as it quotes a tax-inclusive price list (intent/vat-inclusive-pricing.md): shelf
 * prices, the VAT inside each line's final cost, an optional whole-basket percentage shared by
 * gross.
 */
public final class ShelfPricing {

  private ShelfPricing() {}

  /** Bread: 1.29 at 20%. */
  public static final String BREAD = "01a0b2c4-3333-7000-8000-000000000001";

  /** Jam: 1.99 at 5%. */
  public static final String JAM = "01a0b2c4-3333-7000-8000-000000000002";

  /** Cheese: 12.00 at 20%. */
  public static final String CHEESE = "01a0b2c4-3333-7000-8000-000000000003";

  /** Milk: 2.49 at 0%. */
  public static final String MILK = "01a0b2c4-3333-7000-8000-000000000004";

  public record Shelf(String price, String rate) {}

  static final Map<String, Shelf> SHELF =
      Map.of(
          BREAD, new Shelf("1.29", "0.20"),
          JAM, new Shelf("1.99", "0.05"),
          CHEESE, new Shelf("12.00", "0.20"),
          MILK, new Shelf("2.49", "0"));

  /** A whole-basket percentage the stand-in takes off, shared by gross as pricing-svc does. */
  public static final AtomicReference<BigDecimal> BASKET_PERCENT =
      new AtomicReference<>(BigDecimal.ZERO);

  /** The stand-in service, answering {@code POST /prices/quote}. */
  public static JsonStub start() {
    return JsonStub.start("pricing-svc")
        .on("POST", "/prices/quote", ShelfPricing::quote)
        .on("POST", "/prices/resolve", ShelfPricing::resolve);
  }

  /** One item's price as pricing-svc resolves it for a tax-inclusive list. */
  static JsonStub.Answer resolve(JsonStub.Call call) {
    try (JsonReader r = Json.createReader(new StringReader(call.body()))) {
      Shelf shelf = SHELF.get(r.readObject().getString("variantId"));
      if (shelf == null) return new JsonStub.Answer(404, "{}");
      BigDecimal gross = new BigDecimal(shelf.price());
      BigDecimal rate = new BigDecimal(shelf.rate());
      BigDecimal vat = TaxInclusive.vatInside(gross, rate, 2);
      return JsonStub.Answer.ok(
          Json.createObjectBuilder()
              .add("unitPrice", gross.subtract(vat))
              .add("vatAmount", vat)
              .add("vatRate", rate)
              .add("totalWithVat", gross)
              .add("taxInclusive", true)
              .build()
              .toString());
    }
  }

  /** The quote pricing-svc gives for a basket of shelf prices. */
  static JsonStub.Answer quote(JsonStub.Call call) {
    List<BigDecimal> grosses = new ArrayList<>();
    List<JsonObject> lines = new ArrayList<>();
    try (JsonReader r = Json.createReader(new StringReader(call.body()))) {
      for (JsonValue v : r.readObject().getJsonArray("lines")) {
        JsonObject l = v.asJsonObject();
        Shelf shelf = SHELF.get(l.getString("variantId"));
        if (shelf == null) {
          return new JsonStub.Answer(
              404, "{\"error\":{\"code\":\"PRICING_NO_PRICE\",\"message\":\"no price\"}}");
        }
        BigDecimal qty = l.getJsonNumber("qty").bigDecimalValue();
        BigDecimal total = new BigDecimal(shelf.price()).multiply(qty).setScale(2);
        grosses.add(total);
        lines.add(l);
      }
    }
    BigDecimal held = grosses.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal basket =
        held.multiply(BASKET_PERCENT.get())
            .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    List<BigDecimal> shares = TaxInclusive.shareByGross(basket, grosses, 2);
    var arr = Json.createArrayBuilder();
    BigDecimal vatTotal = BigDecimal.ZERO;
    BigDecimal grossTotal = BigDecimal.ZERO;
    for (int i = 0; i < lines.size(); i++) {
      JsonObject l = lines.get(i);
      Shelf shelf = SHELF.get(l.getString("variantId"));
      BigDecimal rate = new BigDecimal(shelf.rate());
      BigDecimal gross = grosses.get(i).subtract(shares.get(i));
      BigDecimal vat = TaxInclusive.vatInside(gross, rate, 2);
      vatTotal = vatTotal.add(vat);
      grossTotal = grossTotal.add(gross);
      arr.add(
          Json.createObjectBuilder()
              .add("variantId", l.getString("variantId"))
              .add("qty", l.getJsonNumber("qty").bigDecimalValue())
              .add("unitPrice", new BigDecimal(shelf.price()))
              .add("lineTotal", grosses.get(i))
              .add("discount", BigDecimal.ZERO)
              .add("netTotal", gross.subtract(vat))
              .add("vatAmount", vat)
              .add(
                  "vatCode",
                  rate.signum() == 0
                      ? "T0"
                      : rate.compareTo(new BigDecimal("0.05")) == 0 ? "T5" : "T1")
              .add("vatRate", rate)
              .add("lineGross", gross));
    }
    return JsonStub.Answer.ok(
        Json.createObjectBuilder()
            .add("lines", arr)
            .add("subtotal", held)
            .add("totalDiscount", basket)
            .add("basketDiscount", basket)
            .add("vatAmount", vatTotal)
            .add("total", grossTotal)
            .add("currency", "GBP")
            .add("taxInclusive", true)
            .build()
            .toString());
  }
}
