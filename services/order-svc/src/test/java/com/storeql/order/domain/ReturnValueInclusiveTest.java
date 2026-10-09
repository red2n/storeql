package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.money.TaxInclusive;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A refund of a shelf-price line is what was paid for it, in any number of parts. */
class ReturnValueInclusiveTest {

  private static final BigDecimal T1 = new BigDecimal("0.20");

  @Test
  @DisplayName("a whole line comes back for exactly what was paid, with the VAT it carried")
  void aWholeLine() {
    // Three at 1.29: 3.87 paid, 0.65 VAT inside (not three times 0.22).
    BigDecimal paid = new BigDecimal("3.87");
    BigDecimal vat = TaxInclusive.vatInside(paid, T1, 2);

    ReturnValue.InclusiveWorth w =
        ReturnValue.inclusiveWorth(
            paid, vat, new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("3"), 2);

    assertEquals(new BigDecimal("3.87"), w.value());
    assertEquals(new BigDecimal("0.65"), w.vat());
    assertEquals(new BigDecimal("3.22"), w.net());
  }

  @Test
  @DisplayName(
      "the parts of a line add up to the whole, however it is split (qty 1 to 7, every split)")
  void partsAddUpToTheWhole() {
    for (String price : new String[] {"1.29", "1.99", "0.99", "12.35", "100.00"}) {
      for (int qty = 1; qty <= 7; qty++) {
        BigDecimal paid = new BigDecimal(price).multiply(BigDecimal.valueOf(qty));
        BigDecimal vat = TaxInclusive.vatInside(paid, T1, 2);
        // returned one unit at a time
        BigDecimal valueSum = BigDecimal.ZERO;
        BigDecimal vatSum = BigDecimal.ZERO;
        for (int k = 0; k < qty; k++) {
          ReturnValue.InclusiveWorth w =
              ReturnValue.inclusiveWorth(
                  paid, vat, BigDecimal.valueOf(qty), BigDecimal.valueOf(k), BigDecimal.ONE, 2);
          valueSum = valueSum.add(w.value());
          vatSum = vatSum.add(w.vat());
          assertEquals(0, w.net().add(w.vat()).compareTo(w.value()));
          assertTrue(w.value().signum() >= 0 && w.vat().signum() >= 0);
        }
        assertEquals(0, valueSum.compareTo(paid), price + " x " + qty);
        assertEquals(0, vatSum.compareTo(vat), price + " x " + qty);
      }
    }
  }

  @Test
  @DisplayName("returning more than is left gives only what is left")
  void cannotReturnMoreThanWasSold() {
    BigDecimal paid = new BigDecimal("2.58");
    BigDecimal vat = TaxInclusive.vatInside(paid, T1, 2);

    ReturnValue.InclusiveWorth w =
        ReturnValue.inclusiveWorth(
            paid, vat, new BigDecimal("2"), new BigDecimal("1"), new BigDecimal("5"), 2);

    assertEquals(new BigDecimal("1.29"), w.value());
  }

  @Test
  @DisplayName("a line with no quantity is worth nothing")
  void noQuantity() {
    ReturnValue.InclusiveWorth w =
        ReturnValue.inclusiveWorth(
            new BigDecimal("1.00"),
            new BigDecimal("0.17"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE,
            2);
    assertEquals(0, w.value().signum());
  }

  @Test
  @DisplayName(
      "a no-receipt line at the shelf price is credited whole, with the VAT worked on the line")
  void aNoReceiptLineAtTheShelfPrice() {
    ReturnValue.Line line =
        ReturnValue.priceLineInclusive(new BigDecimal("1.29"), T1, new BigDecimal("3"), 2);

    assertEquals(new BigDecimal("3.87"), line.value());
    assertEquals(new BigDecimal("0.65"), line.taxAmount());
    assertEquals(new BigDecimal("1.2900"), line.unitPrice());
  }
}
