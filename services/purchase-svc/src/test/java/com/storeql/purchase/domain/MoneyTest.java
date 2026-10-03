package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Currency-aware rounding across the markets StoreQL trades in. */
class MoneyTest {

  @Test
  @DisplayName("Minor units come from ISO 4217, not from an assumption of two")
  void minorUnitsPerCurrency() {
    assertThat(Money.scaleOf("GBP"), is(2));
    assertThat(Money.scaleOf("USD"), is(2));
    assertThat(Money.scaleOf("INR"), is(2));
    assertThat(Money.scaleOf("CNY"), is(2));
    // The one that breaks a hardcoded 2.
    assertThat(Money.scaleOf("JPY"), is(0));
    assertThat(Money.scaleOf("KRW"), is(0));
  }

  @Test
  @DisplayName("Codes are case- and whitespace-insensitive")
  void normalisation() {
    assertThat(Money.scaleOf(" jpy "), is(0));
    assertThat(Money.requireIso4217(" inr "), is("INR"));
  }

  @Test
  @DisplayName("An unrecognised code falls back to two places rather than throwing")
  void unknownCodeFallsBack() {
    assertThat(Money.scaleOf("ZZZ"), is(2));
    assertThat(Money.scaleOf(null), is(2));
  }

  @Test
  @DisplayName("Rounding is HALF_UP, at the currency's own scale")
  void roundingIsHalfUpAtCurrencyScale() {
    assertThat(
        Money.round(new BigDecimal("1.005"), "GBP"), comparesEqualTo(new BigDecimal("1.01")));
    assertThat(
        Money.round(new BigDecimal("1234.5"), "JPY"), comparesEqualTo(new BigDecimal("1235")));
    assertThat(
        Money.round(new BigDecimal("1234.4"), "JPY"), comparesEqualTo(new BigDecimal("1234")));
  }

  @Test
  @DisplayName("A JPY amount carries no decimal places at all, not merely zeroes")
  void yenScaleIsZeroNotZeroes() {
    assertThat(Money.round(new BigDecimal("1234"), "JPY").scale(), is(0));
    assertThat(Money.round(new BigDecimal("1234"), "GBP").scale(), is(2));
  }

  @Test
  @DisplayName("Client-supplied currency is validated at the boundary")
  void validationAtTheBoundary() {
    for (String good : new String[] {"GBP", "USD", "JPY", "INR", "CNY"}) {
      assertThat(Money.requireIso4217(good), is(good));
    }
    ApiException e = assertThrows(ApiException.class, () -> Money.requireIso4217("POUNDS"));
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("PURCHASE_INVALID_CURRENCY"));

    assertThrows(ApiException.class, () -> Money.requireIso4217("ZZZ"));
    assertThrows(ApiException.class, () -> Money.requireIso4217(null));
    assertThrows(ApiException.class, () -> Money.requireIso4217(""));
  }

  @Test
  @DisplayName("null rounds to null — the caller decides what an absent amount means")
  void nullPassesThrough() {
    assertThat(Money.round(null, "GBP"), is((BigDecimal) null));
  }

  @Test
  @DisplayName(
      "The minor units are the platform's one reading of ISO 4217 — common-service Fx's — so"
          + " purchase money rounds as every converted figure does")
  void minorUnitsAgreeWithFx() {
    for (String code :
        new String[] {
          "GBP", "USD", "EUR", "INR", "JPY", "KRW", "VND", "KWD", "BHD", "TND", "CLF", "XAU", "XXX",
          "ZZZ", "jpy", null
        }) {
      assertThat(
          String.valueOf(code), Money.scaleOf(code), is(com.storeql.service.Fx.minorUnits(code)));
    }
  }

  @Test
  @DisplayName(
      "An amount finer than its currency's minor unit is refused by name: half a yen, a thousandth"
          + " of a pound; a dinar's third decimal is not")
  void anAmountFinerThanItsCurrencyIsRefused() {
    for (String[] c :
        new String[][] {{"0.5", "JPY"}, {"1000.1", "JPY"}, {"5.005", "GBP"}, {"1.2345", "KWD"}}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> Money.requireMinorUnits(new BigDecimal(c[0]), c[1], "amount"));
      assertThat(c[0] + " " + c[1], e.status(), is(400));
      assertThat(e.code(), is("PURCHASE_AMOUNT_TOO_PRECISE"));
    }
    for (String[] c :
        new String[][] {
          {"1000", "JPY"},
          {"1000.00", "JPY"},
          {"5.01", "GBP"},
          {"5.010", "GBP"},
          {"1.234", "KWD"},
          {"0.005", "KWD"}
        }) {
      assertThat(
          c[0] + " " + c[1],
          Money.requireMinorUnits(new BigDecimal(c[0]), c[1], "amount"),
          comparesEqualTo(new BigDecimal(c[0])));
    }
    assertThat(Money.requireMinorUnits(new BigDecimal("1000.00"), "JPY", "amount").scale(), is(0));
    assertThat(Money.requireMinorUnits(null, "JPY", "amount") == null, is(true));
  }

  @Test
  @DisplayName(
      "A figure beyond any amount is refused VALIDATION_FAILED at once, before it is rescaled:"
          + " 1E+2147483647 (which wraps an int and passes @Digits), 1E+80000000, nineteen whole"
          + " digits, an unscaled value of a hundred digits; eighteen whole digits are an amount")
  @org.junit.jupiter.api.Timeout(5)
  void aFigureBeyondAnyAmountIsRefusedBeforeItIsScaled() {
    for (String huge :
        new String[] {
          "1E+2147483647",
          "1E+80000000",
          "-1E+80000000",
          "1000000000000000000",
          "1" + "0".repeat(99) + ".00",
          "0." + "1".repeat(100)
        }) {
      for (String currency : new String[] {"GBP", "JPY", "KWD"}) {
        ApiException e =
            assertThrows(
                ApiException.class,
                () -> Money.requireMinorUnits(new BigDecimal(huge), currency, "amount"));
        String shown = huge.length() > 20 ? huge.substring(0, 20) + "…" : huge;
        assertThat(shown + " " + currency, e.status(), is(400));
        assertThat(shown + " " + currency, e.code(), is("VALIDATION_FAILED"));
        assertThat("the refusal names the field", e.getMessage().startsWith("amount "), is(true));
      }
    }
    assertThat(
        Money.requireMinorUnits(new BigDecimal("999999999999999999.99"), "GBP", "amount"),
        comparesEqualTo(new BigDecimal("999999999999999999.99")));
    assertThat(
        Money.requireMinorUnits(new BigDecimal("999999999999999999"), "JPY", "amount").scale(),
        is(0));
  }

  @Test
  @DisplayName(
      "A figure far finer than its currency is refused PURCHASE_AMOUNT_TOO_PRECISE in a sentence of"
          + " ordinary length: 1E-2147483647 is not written out to two billion digits")
  @org.junit.jupiter.api.Timeout(5)
  void aFigureFarFinerIsRefusedInAShortSentence() {
    for (String fine : new String[] {"1E-2147483647", "1E-80000000", "5E-300"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> Money.requireMinorUnits(new BigDecimal(fine), "GBP", "vatAmount"));
      assertThat(fine, e.code(), is("PURCHASE_AMOUNT_TOO_PRECISE"));
      assertThat(fine, e.getMessage().length() < 200, is(true));
      assertThat(fine, e.getMessage().contains(fine), is(true));
    }
  }
}
