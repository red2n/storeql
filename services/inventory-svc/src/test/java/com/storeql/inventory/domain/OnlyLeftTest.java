package com.storeql.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * "Only N left": pure, and every gate that keeps the count honest — off by default, never above the
 * threshold, never a store's worth of stock nobody asked about, never a weight dressed up as a
 * count.
 */
class OnlyLeftTest {

  private static final Integer THRESHOLD = 5;

  private static Integer left(String available, boolean dropship) {
    return OnlyLeft.compute(true, new BigDecimal(available), THRESHOLD, dropship);
  }

  @Test
  void noThresholdSetMeansNoCountWhateverIsOnTheShelf() {
    assertNull(OnlyLeft.compute(true, new BigDecimal("3"), null, false));
  }

  @Test
  void underTheThresholdShowsTheWholeCount() {
    assertEquals(3, left("3", false));
  }

  @Test
  void atTheThresholdStillShowsTheCount() {
    assertEquals(5, left("5", false));
  }

  @Test
  void oneOverTheThresholdNeverShowsACount() {
    assertNull(left("6", false));
  }

  @Test
  void outOfStockShowsNoCount() {
    assertNull(left("0", false));
  }

  @Test
  void aFractionalQuantityNeverGetsACountWeighedGoodsStayUncounted() {
    assertNull(left("2.5", false));
  }

  @Test
  void trailingZerosAreStillAWholeNumber() {
    assertEquals(3, left("3.000", false));
  }

  @Test
  void aDropshipLineHasNothingOnTheShelfToCount() {
    assertNull(left("3", true));
  }

  @Test
  void noStoreNamedOnTheReadMeansNoOneShelfToCount() {
    assertNull(OnlyLeft.compute(false, new BigDecimal("3"), THRESHOLD, false));
  }

  @Test
  void aNullAvailableQuantityIsTreatedAsNone() {
    assertNull(OnlyLeft.compute(true, null, THRESHOLD, false));
  }

  @Test
  void everyGateAppliesTogetherNotJustOneAtATime() {
    // Below the threshold, but dropship — still no count.
    assertNull(OnlyLeft.compute(true, new BigDecimal("2"), THRESHOLD, true));
    // Below the threshold, but no store was named — still no count.
    assertNull(OnlyLeft.compute(false, new BigDecimal("2"), THRESHOLD, false));
  }
}
