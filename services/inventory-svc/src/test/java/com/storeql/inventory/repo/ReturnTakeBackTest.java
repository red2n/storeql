package com.storeql.inventory.repo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A return takes back its share of a line's revenue and cost at the minor units of the currency the
 * sale was recorded in — never two decimals assumed: whole yen, a dinar's fils, pence.
 */
class ReturnTakeBackTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aReturnTakesBackItsShareInTheSalesOwnMinorUnits() {
    // One of three from a ¥1,000 line is ¥333, not 333.33.
    assertThat(InventoryRepository.takenBack(d("1000"), d("1"), d("3"), 0), is(d("333")));
    // One of three from a KWD 10.000 line is 3.333, not 3.33.
    assertThat(InventoryRepository.takenBack(d("10.000"), d("1"), d("3"), 3), is(d("3.333")));
    // A cost of 7.5005 (four-place unit costs) over 3, one back, in pence: 2.50.
    assertThat(InventoryRepository.takenBack(d("7.5005"), d("1"), d("3"), 2), is(d("2.50")));
  }
}
