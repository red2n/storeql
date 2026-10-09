package com.storeql.order.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parsing tests for {@code /prices/quote}.
 *
 * <p>These exist because SJ-D14 established that the only two places in this codebase which parsed
 * a cross-service DTO inline — behind service discovery, a circuit breaker and a web client — were
 * the only two that were wrong, and stayed wrong for months. Extracting the parse is what makes it
 * assertable at all.
 *
 * <p><b>SJ-D20 is the case they were written for</b>, and it was found by placing one real order
 * rather than by reading any of this: the quote's shape and checkout's assumptions disagreed in two
 * places at once, and the resulting order carried £144 of VAT on an £80 basket with the coupon
 * charged twice.
 */
class PricingClientQuoteParseTest {

  private static JsonObject json(String s) {
    try (var r = Json.createReader(new StringReader(s))) {
      return r.readObject();
    }
  }

  /**
   * The first half of SJ-D20. {@code netTotal} already has this line's share of the basket-level
   * discount subtracted; the caller subtracts the basket discount separately. Reading netTotal here
   * therefore took it off twice — visible as a subtotal of 72.00 on ten items priced at 8.00.
   */
  @Test
  void unitPriceExcludesTheBasketDiscountBecauseTheCallerSubtractsItSeparately() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[{"variantId":"01a090ae-611e-700b-bde4-50df0324c37c","qty":10,
                           "unitPrice":8.00,"lineTotal":80.00,"discount":0,
                           "netTotal":72.00,"vatAmount":14.40,"vatCode":"T1"}],
                 "subtotal":80.00,"totalDiscount":8.00,"basketDiscount":8.00,
                 "vatAmount":14.40,"total":86.40,"currency":"GBP",
                 "appliedPromotions":[],"rejectedCoupons":{}}
                """));

    // 8.00, not 7.20 — the basket discount is reported once, in basketDiscount.
    assertEquals(0, q.lines().get(0).unitPrice().compareTo(new BigDecimal("8.00")));
    assertEquals(0, q.basketDiscount().compareTo(new BigDecimal("8.00")));
  }

  /** A line-level promotion, by contrast, does belong in the unit price. */
  @Test
  void unitPriceDoesIncludeALineLevelDiscount() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[{"variantId":"01a090ae-611e-700b-bde4-50df0324c37c","qty":10,
                           "unitPrice":8.00,"lineTotal":80.00,"discount":8.00,
                           "netTotal":72.00,"vatAmount":14.40,"vatCode":"T1"}],
                 "subtotal":80.00,"totalDiscount":8.00,"basketDiscount":0,
                 "vatAmount":14.40,"total":86.40,"currency":"GBP",
                 "appliedPromotions":[],"rejectedCoupons":{}}
                """));
    assertEquals(0, q.lines().get(0).unitPrice().compareTo(new BigDecimal("7.20")));
  }

  /**
   * The second half of SJ-D20. A quote returns the whole line's VAT; the per-unit form belongs to
   * {@code /prices/resolve-batch}. Checkout multiplied by the quantity again, so an £80 basket came
   * back carrying £144.
   */
  @Test
  void vatIsForTheWholeLineNotPerUnit() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[{"variantId":"01a090ae-611e-700b-bde4-50df0324c37c","qty":10,
                           "unitPrice":8.00,"lineTotal":80.00,"discount":0,
                           "netTotal":80.00,"vatAmount":16.00,"vatCode":"T1"}],
                 "subtotal":80.00,"totalDiscount":0,"basketDiscount":0,
                 "vatAmount":16.00,"total":96.00,"currency":"GBP",
                 "appliedPromotions":[],"rejectedCoupons":{}}
                """));
    // 16.00 for ten items, i.e. the line — not 16.00 per unit waiting to be multiplied to 160.
    assertEquals(0, q.lines().get(0).lineVat().compareTo(new BigDecimal("16.00")));
  }

  @Test
  void appliedPromotionsCarryTheirLineOrNullForAWholeBasketOne() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[],"subtotal":0,"totalDiscount":13.00,"basketDiscount":5.00,
                 "vatAmount":0,"total":0,"currency":"GBP",
                 "appliedPromotions":[
                   {"promotionId":"01a090ae-611e-700f-b645-a14095230b77","name":"Shirts 10%",
                    "variantId":"01a090ae-611e-700b-bde4-50df0324c37c","amount":8.00},
                   {"promotionId":"01a090ae-611e-7011-ae7d-1bd68c966ff6","name":"£5 off",
                    "amount":5.00}],
                 "rejectedCoupons":{"NOPE":"NO_SUCH_COUPON"}}
                """));

    assertEquals(2, q.applied().size());
    assertEquals("Shirts 10%", q.applied().get(0).name());
    // JSON-B omits a null field entirely rather than serialising it as null, and isNull throws on
    // an absent key. That is SJ-D14 exactly, and it 503'd every guest checkout for months.
    assertNull(q.applied().get(1).variantId());
    assertEquals("NO_SUCH_COUPON", q.rejectedCoupons().get("NOPE"));
  }

  /** An absent optional block must not throw — the SJ-D14 shape once more. */
  @Test
  void aQuoteWithNoPromotionsAtAllParses() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[{"variantId":"01a090ae-611e-700b-bde4-50df0324c37c","qty":1,
                           "unitPrice":5.00,"lineTotal":5.00,"discount":0,
                           "netTotal":5.00,"vatAmount":1.00,"vatCode":"T1"}],
                 "subtotal":5.00,"totalDiscount":0,"basketDiscount":0,
                 "vatAmount":1.00,"total":6.00,"currency":"GBP"}
                """));
    assertTrue(q.applied().isEmpty());
    assertTrue(q.rejectedCoupons().isEmpty());
    assertEquals(0, q.basketDiscount().compareTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("A line's value is carried, not rebuilt from a rounded unit price")
  void lineNetSurvivesAQuantityThatDoesNotDivideEvenly() {
    // Three units of a £100 line. The unit price can only be 33.33, which multiplies back to
    // 99.99 — so an order built by multiplying would be a penny short of the quote it came from,
    // on every such line, and worse with fractional quantities.
    var basket =
        PricingClient.parseQuote(
            json(
                "{\"lines\":[{\"qty\":3,\"lineTotal\":100.00,\"discount\":0,"
                    + "\"vatAmount\":20.00}]}"));

    var line = basket.lines().get(0);
    assertThat(line.unitPrice(), comparesEqualTo(new BigDecimal("33.33")));
    assertThat(
        line.unitPrice().multiply(new BigDecimal("3")), comparesEqualTo(new BigDecimal("99.99")));
    // The figure that actually goes on the order.
    assertThat(line.lineNet(), comparesEqualTo(new BigDecimal("100.00")));
  }

  @Test
  @DisplayName("lineNet is net of the LINE discount only — the basket discount is subtracted once")
  void lineNetExcludesOnlyTheLineDiscount() {
    var basket =
        PricingClient.parseQuote(
            json(
                "{\"lines\":[{\"qty\":2,\"lineTotal\":50.00,\"discount\":10.00,"
                    + "\"netTotal\":35.00,\"vatAmount\":8.00}],"
                    + "\"basketDiscount\":5.00}"));

    // netTotal (35.00) already has the basket discount in it; reading that here charged it twice
    // (SJ-D20). lineNet must be lineTotal − discount = 40.00.
    assertThat(basket.lines().get(0).lineNet(), comparesEqualTo(new BigDecimal("40.00")));
    assertThat(basket.basketDiscount(), comparesEqualTo(new BigDecimal("5.00")));
  }

  /** A yen quote's unit price is whole yen: ¥1,000 over three is ¥333, never ¥333.33. */
  @Test
  void aYenQuotesUnitPriceIsWholeYen() {
    var basket =
        PricingClient.parseQuote(
            json(
                "{\"lines\":[{\"qty\":3,\"lineTotal\":1000,\"discount\":0,"
                    + "\"vatAmount\":100}],\"currency\":\"JPY\"}"));
    assertEquals(new BigDecimal("333"), basket.lines().get(0).unitPrice());
  }

  /**
   * A dinar quote keeps its third decimal: KWD 10 over three is 3.333 — the currency decides the
   * precision, not how the line total happened to be written.
   */
  @Test
  void aDinarQuotesUnitPriceKeepsItsThirdDecimal() {
    var basket =
        PricingClient.parseQuote(
            json(
                "{\"lines\":[{\"qty\":3,\"lineTotal\":10,\"discount\":0,"
                    + "\"vatAmount\":0.500}],\"currency\":\"KWD\"}"));
    assertEquals(new BigDecimal("3.333"), basket.lines().get(0).unitPrice());
  }

  // ── a quote of shelf prices (intent/vat-inclusive-pricing.md) ──────────────

  /**
   * A shelf-price quote's lines carry what they finally cost, VAT inside: the line gross, the VAT
   * in it, and the net that is left. Order placement shares the staff discount over those grosses.
   */
  @Test
  @DisplayName(
      "a shelf-price quote is read as gross: what each line finally costs, and the VAT in it")
  void aShelfPriceQuoteIsReadAsGross() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[{"variantId":"01a090ae-611e-700b-bde4-50df0324c37c","qty":3,
                           "unitPrice":1.29,"lineTotal":3.87,"discount":0.00,
                           "netTotal":3.22,"vatAmount":0.65,"vatCode":"T1","vatRate":0.20,
                           "lineGross":3.87},
                          {"variantId":"01a090ae-611e-700b-bde4-50df0324c37d","qty":1,
                           "unitPrice":1.99,"lineTotal":1.99,"discount":0.00,
                           "netTotal":1.90,"vatAmount":0.09,"vatCode":"T5","vatRate":0.05,
                           "lineGross":1.99}],
                 "subtotal":5.86,"totalDiscount":0.00,"basketDiscount":0,
                 "vatAmount":0.74,"total":5.86,"currency":"GBP","taxInclusive":true,
                 "appliedPromotions":[],"rejectedCoupons":{}}
                """));

    assertTrue(q.taxInclusive());
    assertEquals(0, q.lines().get(0).lineGross().compareTo(new BigDecimal("3.87")));
    assertEquals(0, q.lines().get(0).lineVat().compareTo(new BigDecimal("0.65")));
    assertEquals(0, q.lines().get(0).listUnit().compareTo(new BigDecimal("1.29")));
    assertEquals(0, q.lines().get(1).lineGross().compareTo(new BigDecimal("1.99")));
    assertEquals(0, q.lines().get(1).vatRate().compareTo(new BigDecimal("0.05")));
  }

  @Test
  @DisplayName(
      "a line promotion and the basket's share are both inside lineGross, never taken twice")
  void promotionsAreInsideTheGross() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[{"variantId":"01a090ae-611e-700b-bde4-50df0324c37c","qty":1,
                           "unitPrice":2.00,"lineTotal":2.00,"discount":0.20,
                           "netTotal":1.38,"vatAmount":0.23,"vatCode":"T1","vatRate":0.20,
                           "lineGross":1.61}],
                 "subtotal":2.00,"totalDiscount":0.39,"basketDiscount":0.19,
                 "vatAmount":0.23,"total":1.61,"currency":"GBP","taxInclusive":true,
                 "appliedPromotions":[],"rejectedCoupons":{}}
                """));

    var line = q.lines().get(0);
    assertEquals(0, line.lineGross().compareTo(new BigDecimal("1.61")));
    // the value after the line's own offer, before the basket's share: 2.00 less 0.20
    assertEquals(0, line.lineNet().compareTo(new BigDecimal("1.80")));
    assertEquals(0, q.basketDiscount().compareTo(new BigDecimal("0.19")));
  }

  @Test
  @DisplayName("a quote that says nothing of shelf prices is net, as it always was")
  void aNetQuoteIsUnchanged() {
    var q =
        PricingClient.parseQuote(
            json(
                """
                {"lines":[{"variantId":"01a090ae-611e-700b-bde4-50df0324c37c","qty":1,
                           "unitPrice":10.00,"lineTotal":10.00,"discount":0,
                           "netTotal":10.00,"vatAmount":2.00,"vatCode":"T1"}],
                 "basketDiscount":0,"currency":"GBP","appliedPromotions":[],"rejectedCoupons":{}}
                """));

    assertEquals(false, q.taxInclusive());
    assertNull(q.lines().get(0).lineGross());
  }
}
