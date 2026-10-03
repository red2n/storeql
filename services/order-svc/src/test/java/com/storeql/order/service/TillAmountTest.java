package com.storeql.order.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A figure the till works out in binary floating point rather than types — a gift-card tender of
 * what is left to pay, a discount clamped to the goods — arrives with the double's noise and is
 * taken at the currency's own minor units, half up, as payment-svc takes the same till's tenders
 * ({@code Amounts.tendered}). Never two places assumed: whole yen, fils for dinars.
 */
class TillAmountTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aDoublesNoiseIsTheAmountTheCashierWasShown() {
    // Three items at £1.10: the till's 3 × 1.10 is 3.3000000000000003.
    assertThat(TillAmount.rounded(d("3.3000000000000003"), "GBP"), is(d("3.30")));
    // A full-basket discount on £0.70 + £0.10 is 0.7999999999999999.
    assertThat(TillAmount.rounded(d("0.7999999999999999"), "GBP"), is(d("0.80")));
    // A weighed 0.375 kg at 12.99, which the till never rounds: 4.87125, as order-svc's line is.
    assertThat(TillAmount.rounded(d("4.87125"), "GBP"), is(d("4.87")));
    // Half up, as order-svc rounds a line, so a tender that covers the total keeps covering it.
    assertThat(TillAmount.rounded(d("4.875"), "GBP"), is(d("4.88")));
  }

  @Test
  void theCurrencysOwnUnitsNeverTwoPlaces() {
    assertThat(TillAmount.rounded(d("1.2345000000000002"), "KWD"), is(d("1.235")));
    assertThat(TillAmount.rounded(d("3.7049999999999996"), "KWD"), is(d("3.705")));
    assertThat(TillAmount.rounded(d("998.9999999999999"), "JPY"), is(d("999")));
    assertThat(TillAmount.rounded(d("333.5"), "JPY"), is(d("334")));
    assertThat(TillAmount.rounded(d("10"), "GBP"), is(d("10.00")));
  }

  @Test
  void nothingAndAVanishinglySmallAmount() {
    assertThat(TillAmount.rounded(null, "GBP"), is((BigDecimal) null));
    // Under half a penny is nothing; under a tenth of one is said at once, without building a
    // power of ten as long as the exponent.
    assertThat(TillAmount.rounded(d("0.004"), "GBP"), is(d("0.00")));
    assertThat(TillAmount.rounded(d("1E-80000000"), "GBP"), is(d("0.00")));
    assertThat(TillAmount.rounded(d("1E-80000000"), "JPY"), is(d("0")));
  }

  /**
   * An amount with more whole digits than any money column holds is refused before it is rounded:
   * {@code 1E+80000000} is eleven characters on the wire, and rounding it to pence builds an
   * eighty-million-digit number first.
   */
  @Test
  void anAbsurdlyLargeAmountIsRefusedBeforeItIsRounded() {
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2),
        () -> {
          var e =
              assertThrows(ApiException.class, () -> TillAmount.rounded(d("1E+80000000"), "GBP"));
          assertThat(e.status(), is(400));
          assertThat(e.code(), is("VALIDATION_FAILED"));
          assertThat(
              assertThrows(
                      ApiException.class, () -> TillAmount.giftCardCharge(d("1E+80000000"), "GBP"))
                  .code(),
              is("GIFT_CARD_AMOUNT_INVALID"));
          assertThat(
              assertThrows(
                      ApiException.class,
                      () -> TypedMoney.require(d("1E+80000000"), "GBP", "unitPrice"))
                  .code(),
              is("VALIDATION_FAILED"));
        });
    // Eighteen whole digits is still money.
    assertThat(
        TillAmount.rounded(d("999999999999999999.004"), "GBP"), is(d("999999999999999999.00")));
  }

  /**
   * What a till charges a card is the double it worked out — what is left to pay, or the card's
   * balance when that is less. A sale the till rang up, and one replayed from its offline queue, is
   * not refused over binary noise; only an amount that is nothing once rounded is.
   */
  @Test
  void aCardIsChargedWhatTheTillWorkedOutAtTheCurrencysUnits() {
    assertThat(TillAmount.giftCardCharge(d("3.3000000000000003"), "GBP"), is(d("3.30")));
    assertThat(TillAmount.giftCardCharge(d("4.87125"), "GBP"), is(d("4.87")));
    assertThat(TillAmount.giftCardCharge(d("1.2345000000000002"), "KWD"), is(d("1.235")));
    assertThat(TillAmount.giftCardCharge(d("998.9999999999999"), "JPY"), is(d("999")));
    // A figure already at the currency's units is kept as it is, at its scale.
    assertThat(TillAmount.giftCardCharge(d("30"), "GBP"), is(d("30.00")));
    assertThat(TillAmount.giftCardCharge(d("1.2"), "KWD"), is(d("1.200")));
  }

  @Test
  void aChargeOfNothingIsRefused() {
    for (String bad : new String[] {"0", "-5", "0.004", "1E-80000000"}) {
      var e = assertThrows(ApiException.class, () -> TillAmount.giftCardCharge(d(bad), "GBP"), bad);
      assertThat(e.status(), is(400));
      assertThat(e.code(), is("GIFT_CARD_AMOUNT_INVALID"));
    }
    assertThat(
        assertThrows(ApiException.class, () -> TillAmount.giftCardCharge(d("0.4"), "JPY")).code(),
        is("GIFT_CARD_AMOUNT_INVALID"));
    assertThat(
        assertThrows(ApiException.class, () -> TillAmount.giftCardCharge(null, "GBP")).code(),
        is("GIFT_CARD_AMOUNT_INVALID"));
  }

  // ── the goods as the till rang them up ─────────────────────────────────────

  private static OrderItemRequest sent(String qty, String unitPrice) {
    return new OrderItemRequest(
        "v", d(qty), unitPrice == null ? null : d(unitPrice), null, null, null, null, null);
  }

  /**
   * The till adds its lines up unrounded and caps a discount at that: 2 × 0.333 kg at 1.99 is
   * 1.32534 on the till, 1.33 at the currency's units, while this service's lines are 0.66 each.
   * What the till rang up is what it sent, multiplied out, added, then rounded once.
   */
  @Test
  void theGoodsAreWhatTheTillSentAddedUpUnroundedThenRoundedOnce() {
    var weighed = List.of(sent("0.333", "1.99"), sent("0.333", "1.99"));
    assertThat(
        TillAmount.goodsAsRung(weighed, List.of(d("0.66"), d("0.66")), "GBP"), is(d("1.33")));
    var fils = List.of(sent("0.333", "1.995"), sent("0.333", "1.995"));
    assertThat(
        TillAmount.goodsAsRung(fils, List.of(d("0.664"), d("0.664")), "KWD"), is(d("1.329")));
    var yen = List.of(sent("0.333", "199"), sent("0.333", "199"));
    assertThat(TillAmount.goodsAsRung(yen, List.of(d("66"), d("66")), "JPY"), is(d("133")));
    // The till's own price, even where the quote's line differs (an older shelf price).
    assertThat(
        TillAmount.goodsAsRung(List.of(sent("3", "2.505")), List.of(d("7.50")), "GBP"),
        is(d("7.52")));
  }

  /**
   * A line the till sent no price for counts at this service's own line; so does one no till's
   * double could be, rather than building a power of ten as long as its exponent.
   */
  @Test
  void aLineWithNoTillPriceCountsAtTheServicesOwnLine() {
    assertThat(
        TillAmount.goodsAsRung(
            List.of(sent("2", null), sent("1", "1.10")), List.of(d("5.00"), d("1.10")), "GBP"),
        is(d("6.10")));
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2),
        () -> {
          assertThat(
              TillAmount.goodsAsRung(List.of(sent("1", "1E-80000000")), List.of(d("0")), "GBP"),
              is(d("0.00")));
          assertThat(
              TillAmount.goodsAsRung(List.of(sent("1E-80000000", "1.99")), List.of(d("0")), "JPY"),
              is(d("0")));
          assertThat(
              TillAmount.goodsAsRung(List.of(sent("1", "1E+80000000")), List.of(d("9")), "JPY"),
              is(d("9")));
        });
  }
}
