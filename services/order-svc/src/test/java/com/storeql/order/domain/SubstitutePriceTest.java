package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * What a substitute is charged: its own price when that is no more than the original's, the
 * original's otherwise, with the substitute's VAT within the charge either way; a share of a line
 * for what stands of it, to the cent.
 */
class SubstitutePriceTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void aCheaperSubstituteIsChargedItsOwnPrice() {
    // Original 3.00 gross a unit; the substitute quoted 2.00 net + 0.40 VAT for two = 1.20 gross
    // each.
    var c = SubstitutePrice.charge(d("3.00"), d("2.00"), d("0.40"), d("0.20"), d("2"), 2);
    assertThat(c.lineNet(), comparesEqualTo(d("2.00")));
    assertThat(c.lineVat(), comparesEqualTo(d("0.40")));
    assertThat(c.unitPrice(), comparesEqualTo(d("1.00")));
    assertThat(c.capped(), is(false));
  }

  @Test
  void aDearerSubstituteIsHeldToTheOriginalWithItsOwnVatInside() {
    // Original 2.40 gross a unit; the substitute quoted 5.00 + 1.00 VAT for two (3.00 gross each).
    var c = SubstitutePrice.charge(d("2.40"), d("5.00"), d("1.00"), d("0.20"), d("2"), 2);
    assertThat(c.gross(), comparesEqualTo(d("4.80")));
    assertThat(c.lineNet(), comparesEqualTo(d("4.00")));
    assertThat(c.lineVat(), comparesEqualTo(d("0.80")));
    assertThat(c.unitPrice(), comparesEqualTo(d("2.00")));
    assertThat(c.capped(), is(true));
  }

  @Test
  void withoutARateTheQuotesOwnProportionHoldsAndWithoutVatTheChargeIsAllNet() {
    var byShare = SubstitutePrice.charge(d("2.40"), d("5.00"), d("1.00"), null, d("2"), 2);
    assertThat(byShare.gross(), comparesEqualTo(d("4.80")));
    assertThat(byShare.lineVat(), comparesEqualTo(d("0.80")));
    var noVat = SubstitutePrice.charge(d("2.40"), d("5.00"), null, null, d("2"), 2);
    assertThat(noVat.lineNet(), comparesEqualTo(d("4.80")));
    assertThat(noVat.lineVat(), comparesEqualTo(d("0.00")));
  }

  @Test
  void aShareOfALineFollowsWhatStandsOfIt() {
    // Three ordered at 10.00; one closed short: two thirds stand.
    assertThat(SubstitutePrice.share(d("10.00"), d("2"), d("3"), 2), comparesEqualTo(d("6.67")));
    assertThat(SubstitutePrice.share(d("2.00"), d("2"), d("3"), 2), comparesEqualTo(d("1.33")));
    assertThat(SubstitutePrice.share(d("10.00"), d("0"), d("3"), 2), comparesEqualTo(d("0.00")));
    assertThat(SubstitutePrice.share(null, d("2"), d("3"), 2), is((BigDecimal) null));
  }
}
