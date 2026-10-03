package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * What a return is worth and how an exchange settles, the arithmetic on its own: like for like
 * moves nothing, a dearer basket owes the difference, a cheaper one gives it back, VAT is part of
 * the value, and a no-receipt line is the current price with its VAT.
 */
class ReturnValueTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Test
  void likeForLikeSettlesForNothing() {
    var s = ReturnValue.settle(d("10.00"), d("10.00"));
    assertThat(s.exchangeAmount(), is(d("10.00")));
    assertThat(s.dueFromCustomer().signum(), is(0));
    assertThat(s.refundToCustomer().signum(), is(0));
  }

  @Test
  void aDearerBasketOwesOnlyTheDifference() {
    var s = ReturnValue.settle(d("10.00"), d("25.00"));
    assertThat(s.exchangeAmount(), is(d("10.00")));
    assertThat(s.dueFromCustomer(), is(d("15.00")));
    assertThat(s.refundToCustomer().signum(), is(0));
  }

  @Test
  void aCheaperBasketGivesBackOnlyTheDifference() {
    var s = ReturnValue.settle(d("20.00"), d("4.00"));
    assertThat(s.exchangeAmount(), is(d("4.00")));
    assertThat(s.dueFromCustomer().signum(), is(0));
    assertThat(s.refundToCustomer(), is(d("16.00")));
  }

  @Test
  void oneOfTheTwoDifferencesIsAlwaysZeroAndTheThreeAddUp() {
    for (String[] pair :
        new String[][] {{"0.00", "9.99"}, {"9.99", "0.00"}, {"12.34", "12.35"}, {"7.00", "7.00"}}) {
      var r = d(pair[0]);
      var b = d(pair[1]);
      var s = ReturnValue.settle(r, b);
      assertThat(s.dueFromCustomer().signum() == 0 || s.refundToCustomer().signum() == 0, is(true));
      // What the returned value pays plus what is still due is the new basket...
      assertThat(s.exchangeAmount().add(s.dueFromCustomer()).compareTo(b), is(0));
      // ...and what it pays plus what goes back is the returned value.
      assertThat(s.exchangeAmount().add(s.refundToCustomer()).compareTo(r), is(0));
    }
  }

  @Test
  void anAmountBelowNothingIsNoExchange() {
    assertThrows(IllegalArgumentException.class, () -> ReturnValue.settle(d("-1"), d("1")));
    assertThrows(IllegalArgumentException.class, () -> ReturnValue.settle(d("1"), d("-1")));
    assertThrows(IllegalArgumentException.class, () -> ReturnValue.settle(null, d("1")));
  }

  @Test
  void aReturnedLineIsWorthItsPriceAndItsShareOfTheVat() {
    // Two sold at ten net with four of VAT on the line: one back is ten and two.
    assertThat(ReturnValue.grossOf(d("10.00"), d("1"), d("4.0000"), d("2"), 2), is(d("12.00")));
    // Both back is the whole line.
    assertThat(ReturnValue.grossOf(d("10.00"), d("2"), d("4.0000"), d("2"), 2), is(d("24.00")));
  }

  @Test
  void aLineSoldWithNoVatRecordedIsWorthItsNetPrice() {
    assertThat(ReturnValue.grossOf(d("10.00"), d("2"), null, d("2"), 2), is(d("20.00")));
    assertThat(ReturnValue.grossOf(d("10.00"), d("1"), d("4"), null, 2), is(d("10.00")));
  }

  @Test
  void aShareOfVatIsRoundedToTheMinorUnitOnce() {
    // A third of a line's 1.00 of VAT is 0.33; three thirds are the whole 1.00.
    assertThat(ReturnValue.grossOf(d("3.00"), d("1"), d("1.00"), d("3"), 2), is(d("3.33")));
    assertThat(ReturnValue.grossOf(d("3.00"), d("3"), d("1.00"), d("3"), 2), is(d("10.00")));
  }

  @Test
  void aNoReceiptLineIsTodaysPriceWithItsVat() {
    var line = ReturnValue.priceLine(d("10.00"), d("2.0000"), d("2"), 2);
    assertThat(line.unitPrice().compareTo(d("12")), is(0));
    assertThat(line.taxAmount(), is(d("4.00")));
    assertThat(line.value(), is(d("24.00")));
  }

  @Test
  void aNoReceiptLineWithNoVatIsThePriceAlone() {
    var line = ReturnValue.priceLine(d("4.50"), null, d("3"), 2);
    assertThat(line.taxAmount(), is(d("0.00")));
    assertThat(line.value(), is(d("13.50")));
  }

  @Test
  void aDinarNoReceiptLineKeepsItsThirdDecimal() {
    // KWD has three minor units (fils): 3 × (1.235 + 0.062) is 3.891, never 3.89.
    var line = ReturnValue.priceLine(d("1.235"), d("0.062"), d("3"), 3);
    assertThat(line.taxAmount(), is(d("0.186")));
    assertThat(line.value(), is(d("3.891")));
  }

  @Test
  void aYenNoReceiptLineIsWholeYen() {
    // JPY has no minor unit: 1 × (333 + 33.3) is ¥366, VAT ¥33 — never ¥366.30.
    var line = ReturnValue.priceLine(d("333"), d("33.3"), d("1"), 0);
    assertThat(line.taxAmount(), is(d("33")));
    assertThat(line.value(), is(d("366")));
  }

  @Test
  void aReturnedLineIsWorthItsShareInTheCurrencysOwnMinorUnits() {
    // One of three sold: KWD 1.235 net + a third of 0.371 VAT = 1.35867, kept to the fils.
    assertThat(ReturnValue.grossOf(d("1.235"), d("1"), d("0.371"), d("3"), 3), is(d("1.359")));
    // One of three sold: ¥100 net + a third of ¥25 VAT = ¥108.33, kept to the yen.
    assertThat(ReturnValue.grossOf(d("100"), d("1"), d("25"), d("3"), 0), is(d("108")));
  }
}
