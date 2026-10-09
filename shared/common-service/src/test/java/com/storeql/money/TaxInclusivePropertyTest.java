package com.storeql.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The VAT inside a tax-inclusive shelf price (intent/vat-inclusive-pricing.md): the shelf price is
 * the truth, the VAT in it is {@code gross × r / (1 + r)} in one division to the currency's minor
 * units, and the net is what is left. Every price in a 2-, 0- and 3-decimal currency is tried at
 * six rates, and the golden vectors (made by an independent Python implementation) are reproduced
 * row by row, so the offline Dart port can be held to the same file.
 */
class TaxInclusivePropertyTest {

  private static final List<String> RATES = List.of("0", "0.05", "0.10", "0.19", "0.20", "0.23");

  // ── the defect this class exists for, written down ───────────────────────────

  @Test
  @DisplayName("a net price in two decimals cannot make 1 in 6 shelf prices at 20% (the old model)")
  void theNetModelCouldNotMakeEveryShelfPrice() {
    assertEquals(1650, unreachableShelfPrices("0.20"), "of 9,900 prices from 1.00 to 99.99");
    assertEquals(471, unreachableShelfPrices("0.05"));
    // The two the customer would meet first.
    assertFalse(reachable("1.29", "0.20"));
    assertFalse(reachable("1.99", "0.05"));
  }

  /**
   * Shelf prices 1.00..99.99 that no 2-decimal net price turns into, by net + round(net × rate).
   */
  private static int unreachableShelfPrices(String rate) {
    BigDecimal r = new BigDecimal(rate);
    Set<BigDecimal> made = new HashSet<>();
    for (int pence = 1; pence < 100_000; pence++) {
      BigDecimal net = BigDecimal.valueOf(pence, 2);
      made.add(net.add(net.multiply(r).setScale(2, RoundingMode.HALF_UP)));
    }
    int missing = 0;
    for (int pence = 100; pence < 10_000; pence++) {
      if (!made.contains(BigDecimal.valueOf(pence, 2))) {
        missing++;
      }
    }
    return missing;
  }

  private static boolean reachable(String gross, String rate) {
    BigDecimal r = new BigDecimal(rate);
    BigDecimal want = new BigDecimal(gross);
    for (int pence = 1; pence < 100_000; pence++) {
      BigDecimal net = BigDecimal.valueOf(pence, 2);
      if (net.add(net.multiply(r).setScale(2, RoundingMode.HALF_UP)).compareTo(want) == 0) {
        return true;
      }
    }
    return false;
  }

  // ── the properties, over every price ─────────────────────────────────────────

  @Test
  @DisplayName("net + VAT is exactly the shelf price, for every price from 0.01 to 999.99")
  void everyPoundPriceSplitsExactly() {
    for (String rate : RATES) {
      splitsEveryPrice(new BigDecimal(rate), 2, 99_999);
    }
  }

  @Test
  @DisplayName("the same holds for a currency with no minor unit (1 to 99,999)")
  void everyWholeUnitPriceSplitsExactly() {
    for (String rate : RATES) {
      splitsEveryPrice(new BigDecimal(rate), 0, 99_999);
    }
  }

  @Test
  @DisplayName("and for a currency with three (0.001 to 99.999)")
  void everyThreeDecimalPriceSplitsExactly() {
    for (String rate : RATES) {
      splitsEveryPrice(new BigDecimal(rate), 3, 99_999);
    }
  }

  private static void splitsEveryPrice(BigDecimal rate, int scale, int count) {
    BigDecimal previousVat = BigDecimal.ZERO;
    for (int units = 1; units <= count; units++) {
      BigDecimal gross = BigDecimal.valueOf(units, scale);
      BigDecimal vat = TaxInclusive.vatInside(gross, rate, scale);
      BigDecimal net = TaxInclusive.netOf(gross, rate, scale);
      assertEquals(0, net.add(vat).compareTo(gross), gross + " at " + rate);
      assertEquals(scale, vat.scale());
      assertEquals(scale, net.scale());
      assertTrue(vat.signum() >= 0 && vat.compareTo(gross) <= 0, gross + " at " + rate);
      assertTrue(vat.compareTo(previousVat) >= 0, "VAT never falls as the price rises: " + gross);
      previousVat = vat;
    }
  }

  @Test
  @DisplayName("1.29 at 20% is an exact tie and rounds up; 1.99 at 5% is 0.09")
  void theFirstTwoPricesACustomerMeets() {
    assertEquals(
        new BigDecimal("0.22"),
        TaxInclusive.vatInside(new BigDecimal("1.29"), new BigDecimal("0.20"), 2));
    assertEquals(
        new BigDecimal("1.07"),
        TaxInclusive.netOf(new BigDecimal("1.29"), new BigDecimal("0.20"), 2));
    assertEquals(
        new BigDecimal("0.09"),
        TaxInclusive.vatInside(new BigDecimal("1.99"), new BigDecimal("0.05"), 2));
    assertEquals(
        new BigDecimal("16.67"),
        TaxInclusive.vatInside(new BigDecimal("100.00"), new BigDecimal("0.20"), 2));
  }

  @Test
  @DisplayName("a refund splits as the sale did, with the sign: the VAT in -1.29 is -0.22")
  void aNegativeAmountIsTheMirrorImage() {
    BigDecimal vat = TaxInclusive.vatInside(new BigDecimal("-1.29"), new BigDecimal("0.20"), 2);

    assertEquals(new BigDecimal("-0.22"), vat);
    assertEquals(
        new BigDecimal("-1.07"),
        TaxInclusive.netOf(new BigDecimal("-1.29"), new BigDecimal("0.20"), 2));
  }

  @Test
  @DisplayName("a zero rate or a zero price has no VAT, at the currency's scale")
  void noRateNoVat() {
    assertEquals(
        new BigDecimal("0.00"), TaxInclusive.vatInside(new BigDecimal("5.00"), BigDecimal.ZERO, 2));
    assertEquals(
        new BigDecimal("0"), TaxInclusive.vatInside(BigDecimal.ZERO, new BigDecimal("0.20"), 0));
  }

  @Test
  @DisplayName("an amount finer than the currency's minor unit is refused, never rounded")
  void tooFineIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TaxInclusive.vatInside(new BigDecimal("1.295"), new BigDecimal("0.20"), 2));
    assertEquals(
        new BigDecimal("0.22"),
        TaxInclusive.vatInside(new BigDecimal("1.2900"), new BigDecimal("0.20"), 2),
        "trailing zeros are not precision");
  }

  // ── the golden vectors ───────────────────────────────────────────────────────

  @Test
  @DisplayName("every golden vector made by the independent implementation is reproduced")
  void goldenVectorsAreReproduced() throws Exception {
    JsonArray vectors;
    try (InputStream in = getClass().getResourceAsStream("/tax-inclusive-vectors.json")) {
      assertTrue(in != null, "tax-inclusive-vectors.json is on the test classpath");
      vectors = Json.createReader(in).readObject().getJsonArray("vectors");
    }
    assertTrue(vectors.size() >= 3_000, "a real set: " + vectors.size());
    for (JsonObject v : vectors.getValuesAs(JsonObject.class)) {
      int scale = v.getInt("scale");
      BigDecimal gross = new BigDecimal(v.getString("gross"));
      BigDecimal rate = new BigDecimal(v.getString("rate"));
      String where = v.getString("currency") + " " + gross + " at " + rate;
      assertEquals(
          new BigDecimal(v.getString("vat")), TaxInclusive.vatInside(gross, rate, scale), where);
      assertEquals(
          new BigDecimal(v.getString("net")), TaxInclusive.netOf(gross, rate, scale), where);
    }
  }

  @Test
  @DisplayName("every shared-discount vector made by the independent implementation is reproduced")
  void shareVectorsAreReproduced() throws Exception {
    JsonArray cases;
    try (InputStream in = getClass().getResourceAsStream("/tax-inclusive-share-vectors.json")) {
      assertTrue(in != null, "tax-inclusive-share-vectors.json is on the test classpath");
      cases = Json.createReader(in).readObject().getJsonArray("cases");
    }
    assertTrue(cases.size() >= 300, "a real set: " + cases.size());
    for (JsonObject c : cases.getValuesAs(JsonObject.class)) {
      int scale = c.getInt("scale");
      BigDecimal amount = BigDecimal.valueOf(c.getJsonNumber("amount").longValue(), scale);
      List<BigDecimal> grosses =
          c.getJsonArray("grosses").getValuesAs(jakarta.json.JsonNumber.class).stream()
              .map(n -> BigDecimal.valueOf(n.longValue(), scale))
              .toList();
      List<BigDecimal> want =
          c.getJsonArray("shares").getValuesAs(jakarta.json.JsonNumber.class).stream()
              .map(n -> BigDecimal.valueOf(n.longValue(), scale))
              .toList();

      assertEquals(want, TaxInclusive.shareByGross(amount, grosses, scale), c.toString());
    }
  }

  // ── sharing a discount, and running totals ───────────────────────────────────

  @Test
  @DisplayName("a discount shared by gross value sums to the discount exactly, to the penny")
  void aDiscountIsSharedWithoutLosingAPenny() {
    List<BigDecimal> grosses =
        List.of(
            new BigDecimal("1.29"),
            new BigDecimal("3.50"),
            new BigDecimal("0.99"),
            new BigDecimal("12.00"));
    BigDecimal discount = new BigDecimal("1.00");

    List<BigDecimal> shares = TaxInclusive.shareByGross(discount, grosses, 2);

    assertEquals(4, shares.size());
    assertEquals(0, shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(discount));
    for (int i = 0; i < shares.size(); i++) {
      assertTrue(shares.get(i).signum() >= 0 && shares.get(i).compareTo(grosses.get(i)) <= 0);
      assertEquals(2, shares.get(i).scale());
    }
    // proportional: the biggest line takes the biggest share
    assertTrue(shares.get(3).compareTo(shares.get(0)) > 0);
  }

  @Test
  @DisplayName("sharing is exact for every discount up to the whole basket, in three currencies")
  void everyDiscountIsSharedExactly() {
    for (int scale : new int[] {0, 2, 3}) {
      List<BigDecimal> grosses =
          List.of(
              BigDecimal.valueOf(129, scale),
              BigDecimal.valueOf(1, scale),
              BigDecimal.valueOf(3331, scale));
      BigDecimal total = grosses.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      long units = total.movePointRight(scale).longValueExact();
      for (long u = 0; u <= units; u++) {
        BigDecimal discount = BigDecimal.valueOf(u, scale);
        List<BigDecimal> shares = TaxInclusive.shareByGross(discount, grosses, scale);
        assertEquals(
            0,
            shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(discount),
            discount.toPlainString());
        for (int i = 0; i < shares.size(); i++) {
          assertTrue(
              shares.get(i).compareTo(grosses.get(i)) <= 0, "no line gives more than it costs");
        }
      }
    }
  }

  @Test
  @DisplayName("a discount larger than the basket, or negative, is refused")
  void aDiscountOutsideTheBasketIsRefused() {
    List<BigDecimal> grosses = List.of(new BigDecimal("1.00"), new BigDecimal("2.00"));

    assertThrows(
        IllegalArgumentException.class,
        () -> TaxInclusive.shareByGross(new BigDecimal("3.01"), grosses, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> TaxInclusive.shareByGross(new BigDecimal("-0.01"), grosses, 2));
  }

  @Test
  @DisplayName("running totals: the parts of a refund add up to the whole, whatever the split")
  void runningTotalsSumToTheWhole() {
    BigDecimal paid = new BigDecimal("10.00"); // three units bought for ten pounds
    int n = 3;
    BigDecimal sum = BigDecimal.ZERO;
    for (int k = 1; k <= n; k++) {
      sum =
          sum.add(
              TaxInclusive.running(paid, k, n, 2)
                  .subtract(TaxInclusive.running(paid, k - 1, n, 2)));
    }

    assertEquals(0, sum.compareTo(paid));
    assertEquals(
        0, TaxInclusive.running(paid, n, n, 2).compareTo(paid), "all of it is exactly the whole");
    assertEquals(new BigDecimal("0.00"), TaxInclusive.running(paid, 0, n, 2));
  }
}
