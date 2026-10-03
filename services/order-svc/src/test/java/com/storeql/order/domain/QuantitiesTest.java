package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A quantity is counted to three places and never rounded — a till's floating-point noise and a
 * label's finer weight aside, which are taken at the quantity counted.
 */
class QuantitiesTest {

  private static BigDecimal q(String v) {
    return new BigDecimal(v);
  }

  private static void refused(Runnable r, String field) {
    ApiException e = assertThrows(ApiException.class, r::run);
    assertEquals(400, e.status());
    assertEquals("VALIDATION_FAILED", e.code());
    assertTrue(e.details().get(0).startsWith(field + ":"), e.details().toString());
  }

  @Test
  void aTypedQuantityToThreePlacesIsTakenAsItIs() {
    for (String v : new String[] {"1", "3", "0.375", "0.001", "2.50", "1.000000"}) {
      BigDecimal given = q(v);
      assertSame(given, Quantities.typed(given, "qty"), v);
    }
    assertNull(Quantities.typed(null, "qty"));
  }

  @Test
  void aTypedQuantityFinerThanThreePlacesIsRefusedNeverRounded() {
    for (String v : new String[] {"0.3755", "1.0005", "0.0001", "0.30000000000000004"}) {
      refused(() -> Quantities.typed(q(v), "items[0].qty"), "items[0].qty");
    }
  }

  @Test
  void aQuantityWithMoreWholeDigitsThanTheColumnHoldsIsRefused() {
    refused(() -> Quantities.typed(q("1000000000000000"), "qty"), "qty");
    refused(() -> Quantities.fromTill(q("1E+20"), "qty"), "qty");
    assertEquals(q("999999999999999.999"), Quantities.typed(q("999999999999999.999"), "qty"));
  }

  /** 0.1 kg and 0.2 kg weighed and added on the till are 0.30000000000000004 in a double. */
  @Test
  void aTillsDoubleIsTheThreePlaceQuantityItStandsFor() {
    assertEquals(q("0.300"), Quantities.fromTill(q("0.30000000000000004"), "qty"));
    // 0.512 + 0.348 and 0.375 + 0.2, as a double adds them, either side of the quantity.
    assertEquals(q("0.860"), Quantities.fromTill(q("0.8600000000000001"), "qty"));
    assertEquals(q("0.575"), Quantities.fromTill(q("0.5749999999999998"), "qty"));
    assertEquals(q("12.345"), Quantities.fromTill(q("12.345000000000001"), "qty"));
    // Already at three places or fewer: as given, untouched.
    BigDecimal whole = q("2");
    assertSame(whole, Quantities.fromTill(whole, "qty"));
    assertNull(Quantities.fromTill(null, "qty"));
  }

  /**
   * A pack's label carries its net weight to as many as five places (GS1 AI 3105: 0.37512 kg), and
   * the till sells the line at that reading. Stock and every quantity column count the gram, so the
   * reading is taken at the gram below it: the customer is never charged for weight the label does
   * not show, and the till's own total, worked on the finer reading, always covers the sale.
   */
  @Test
  void aTillsLabelWeightIsCountedAtTheGramBelow() {
    assertEquals(q("0.375"), Quantities.fromTill(q("0.37512"), "qty"));
    assertEquals(q("0.375"), Quantities.fromTill(q("0.3755"), "qty"));
    assertEquals(q("0.375"), Quantities.fromTill(q("0.37599"), "qty"));
    assertEquals(q("0.999"), Quantities.fromTill(q("0.999999"), "qty"));
    assertEquals(q("2.000"), Quantities.fromTill(q("2.00001"), "qty"));
    assertEquals(q("0.001"), Quantities.fromTill(q("0.00105"), "qty"));
    // Trailing zeros are no finer: as given, untouched.
    BigDecimal padded = q("1.25000");
    assertSame(padded, Quantities.fromTill(padded, "qty"));
  }

  /**
   * Two labelled packs of one product add up in a double on the till: the noise is taken off the
   * reading first, then the reading is counted at the gram below — so 0.375 + 0.2, a double's
   * 0.5749999999999998, is still 0.575 and never 0.574.
   */
  @Test
  void addedLabelWeightsAreTheReadingTheyStandForAtTheGramBelow() {
    assertEquals(q("0.498"), Quantities.fromTill(q(Double.toString(0.37512 + 0.12345)), "qty"));
    assertEquals(q("0.575"), Quantities.fromTill(q("0.5751200000000001"), "qty"));
    assertEquals(q("1.125"), Quantities.fromTill(q("1.1253600000000001"), "qty"));
    assertEquals(q("0.202"), Quantities.fromTill(q("0.20285999999999998"), "qty"));
    assertEquals(q("0.575"), Quantities.fromTill(q("0.5749999999999998"), "qty"));
    assertEquals(q("0.600"), Quantities.fromTill(q("0.6000000000000001"), "qty"));
  }

  @Test
  void aTillsQuantityFinerThanAReadingIsRefused() {
    // Past six places and no double's noise around a reading: no scale or label says that.
    for (String v : new String[] {"0.3755123", "1.0000001", "0.12345678", "0.30000001"}) {
      refused(() -> Quantities.fromTill(q(v), "items[1].qty"), "items[1].qty");
    }
  }

  /** Under a gram is nothing at the gram, and a line of nothing is no sale. */
  @Test
  void aTillsReadingUnderAGramIsRefused() {
    for (String v : new String[] {"0.0004", "0.00099", "1E-12"}) {
      refused(() -> Quantities.fromTill(q(v), "items[1].qty"), "items[1].qty");
    }
  }

  /** Past twenty places is no double a till sends, and costs nothing to refuse. */
  @Test
  void anAbsurdExponentIsRefusedAtOnce() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          refused(() -> Quantities.fromTill(q("1E-80000000"), "qty"), "qty");
          refused(() -> Quantities.typed(q("1E-80000000"), "qty"), "qty");
          refused(() -> Quantities.fromTill(q("1E+80000000"), "qty"), "qty");
        });
  }
}
