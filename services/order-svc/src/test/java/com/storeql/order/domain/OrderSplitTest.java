package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.hasSize;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A priced order divided into its parts, pure: each part its own lines, amounts and VAT; the
 * whole-basket promotion and any discount shared by the parts' subtotals; the last part taking the
 * rounding, so the parts add up to exactly what the shopper was quoted. Written before the code.
 */
class OrderSplitTest {

  private static final UUID LEEDS = Ids.newId();
  private static final UUID YORK = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static Map<UUID, Map<UUID, BigDecimal>> plan(Object... storeVariantQty) {
    Map<UUID, Map<UUID, BigDecimal>> p = new LinkedHashMap<>();
    for (int i = 0; i < storeVariantQty.length; i += 3) {
      p.computeIfAbsent((UUID) storeVariantQty[i], k -> new LinkedHashMap<>())
          .put((UUID) storeVariantQty[i + 1], d((String) storeVariantQty[i + 2]));
    }
    return p;
  }

  @Test
  void wholeLinesGoToTheirStoresAndTheBasketAmountsAreSharedBySubtotal() {
    // Apples 3 × 2.00 = 6.00 (VAT 1.20) at Leeds; pears 2 × 2.00 = 4.00 (VAT 0.80) at York. The
    // quoted tax is 2.00, the basket promotion 1.00: shared 0.60 and 0.40.
    var parts =
        OrderSplit.split(
            List.of(
                new OrderSplit.Line(APPLES, d("3"), d("6.00"), d("1.20")),
                new OrderSplit.Line(PEARS, d("2"), d("4.00"), d("0.80"))),
            plan(LEEDS, APPLES, "3", YORK, PEARS, "2"),
            d("2.00"),
            BigDecimal.ZERO,
            d("1.00"));
    assertThat(parts, hasSize(2));
    OrderSplit.Part leeds = parts.get(0);
    assertThat(leeds.subtotal(), comparesEqualTo(d("6.00")));
    assertThat(leeds.tax(), comparesEqualTo(d("1.20")));
    assertThat(leeds.promotion(), comparesEqualTo(d("0.60")));
    assertThat(parts.get(1).promotion(), comparesEqualTo(d("0.40")));
    assertThat(parts.get(1).tax(), comparesEqualTo(d("0.80")));
  }

  @Test
  void aLineSplitAcrossStoresIsSharedByQuantityAndTheLastPartTakesTheRounding() {
    // Three units of a 10.00 line (VAT 1.00) split 2 and 1: 6.67 and 3.33 — exactly 10.00; VAT
    // 0.67 and 0.33; a 1.00 promotion shared 0.67 and 0.33; a quoted tax of 1.00 adds up.
    var parts =
        OrderSplit.split(
            List.of(new OrderSplit.Line(APPLES, d("3"), d("10.00"), d("1.00"))),
            plan(LEEDS, APPLES, "2", YORK, APPLES, "1"),
            d("1.00"),
            BigDecimal.ZERO,
            d("1.00"));
    assertThat(parts.get(0).lines().get(0).lineTotal(), comparesEqualTo(d("6.67")));
    assertThat(parts.get(1).lines().get(0).lineTotal(), comparesEqualTo(d("3.33")));
    assertThat(parts.get(0).tax().add(parts.get(1).tax()), comparesEqualTo(d("1.00")));
    assertThat(parts.get(0).promotion().add(parts.get(1).promotion()), comparesEqualTo(d("1.00")));
    assertThat(parts.get(0).lines().get(0).qty(), comparesEqualTo(d("2")));
  }

  @Test
  void withoutVatOnTheLinesTheQuotedTaxIsSharedBySubtotal() {
    var parts =
        OrderSplit.split(
            List.of(
                new OrderSplit.Line(APPLES, d("1"), d("1.00"), null),
                new OrderSplit.Line(PEARS, d("1"), d("2.00"), null)),
            plan(LEEDS, APPLES, "1", YORK, PEARS, "1"),
            d("0.30"),
            d("0.03"),
            BigDecimal.ZERO);
    assertThat(parts.get(0).tax(), comparesEqualTo(d("0.10")));
    assertThat(parts.get(1).tax(), comparesEqualTo(d("0.20")));
    assertThat(parts.get(0).discount().add(parts.get(1).discount()), comparesEqualTo(d("0.03")));
  }

  @Test
  void anAmountIsSharedByWeightTheLastTakingTheCent() {
    var shares =
        OrderSplit.share(new BigDecimal("1.00"), java.util.List.of(d("1"), d("0"), d("1"), d("1")));
    assertThat(shares.get(0), comparesEqualTo(d("0.33")));
    assertThat(shares.get(1), comparesEqualTo(d("0")));
    assertThat(shares.get(2), comparesEqualTo(d("0.33")));
    assertThat(shares.get(3), comparesEqualTo(d("0.34")));
  }
}
