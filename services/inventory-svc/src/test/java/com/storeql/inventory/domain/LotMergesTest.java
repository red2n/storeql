package com.storeql.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.LotMerges.Mismatch;
import com.storeql.service.Fx;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules of merging one batch's stock into another's: what the two must share, the cost the
 * merged batch carries (rounded to the business currency's own minor units) and the date it is sold
 * by.
 */
class LotMergesTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  private static Batch batch(
      UUID store, UUID variant, String status, String ownership, UUID owner) {
    return new Batch(
        Ids.newId(),
        TENANT,
        store,
        variant,
        "L1",
        BigDecimal.TEN,
        BigDecimal.TEN,
        new BigDecimal("2.0000"),
        LocalDate.parse("2098-01-01"),
        Instant.now(),
        Batch.STATUS_ACTIVE,
        status,
        null,
        null,
        null,
        ownership,
        owner,
        Batch.DUTY_PAID);
  }

  private static Batch withGrade(String grade) {
    return new Batch(
        Ids.newId(),
        TENANT,
        STORE,
        VARIANT,
        "L1",
        BigDecimal.TEN,
        BigDecimal.TEN,
        new BigDecimal("2.0000"),
        LocalDate.parse("2098-01-01"),
        Instant.now(),
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        grade,
        null);
  }

  private static Batch plain() {
    return batch(STORE, VARIANT, Batch.MATERIAL_AVAILABLE, Batch.OWNERSHIP_OWNED, null);
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  @Test
  @DisplayName("Two batches of one variant at one store, in one condition, can be merged")
  void batchesInOneConditionCanBeMerged() {
    assertNull(LotMerges.mismatch(plain(), plain()));
  }

  @Test
  @DisplayName("Another store, another variant or another condition each stop a merge")
  void whatTheTwoMustShare() {
    Batch source = plain();
    assertEquals(
        Mismatch.STORE,
        LotMerges.mismatch(
            source,
            batch(Ids.newId(), VARIANT, Batch.MATERIAL_AVAILABLE, Batch.OWNERSHIP_OWNED, null)));
    assertEquals(
        Mismatch.VARIANT,
        LotMerges.mismatch(
            source,
            batch(STORE, Ids.newId(), Batch.MATERIAL_AVAILABLE, Batch.OWNERSHIP_OWNED, null)));
    assertEquals(
        Mismatch.CONDITION,
        LotMerges.mismatch(
            source, batch(STORE, VARIANT, "QUARANTINE", Batch.OWNERSHIP_OWNED, null)));
    assertEquals(
        Mismatch.CONDITION,
        LotMerges.mismatch(
            source,
            batch(
                STORE,
                VARIANT,
                Batch.MATERIAL_AVAILABLE,
                Batch.OWNERSHIP_CONSIGNMENT,
                Ids.newId())));
  }

  @Test
  @DisplayName("Stock of one grade is not merged into another's")
  void aGradeIsPartOfTheCondition() {
    Batch graded = withGrade("A");
    assertEquals(Mismatch.CONDITION, LotMerges.mismatch(withGrade("REJECT"), graded));
    assertEquals(Mismatch.CONDITION, LotMerges.mismatch(graded, plain()));
    assertEquals(Mismatch.CONDITION, LotMerges.mismatch(plain(), graded));
    assertNull(LotMerges.mismatch(graded, withGrade("A")));
  }

  @Test
  @DisplayName("Consigned stock of two suppliers is two conditions")
  void twoSuppliersAreTwoConditions() {
    Batch one =
        batch(STORE, VARIANT, Batch.MATERIAL_AVAILABLE, Batch.OWNERSHIP_CONSIGNMENT, Ids.newId());
    Batch two =
        batch(STORE, VARIANT, Batch.MATERIAL_AVAILABLE, Batch.OWNERSHIP_CONSIGNMENT, Ids.newId());
    assertEquals(Mismatch.CONDITION, LotMerges.mismatch(one, two));
    assertNull(LotMerges.mismatch(one, one));
  }

  @Test
  @DisplayName("A batch with a unit cost is not merged with one that has none")
  void aCostAndNoCost() {
    Batch costed = plain();
    Batch uncosted =
        new Batch(
            Ids.newId(),
            TENANT,
            STORE,
            VARIANT,
            "L2",
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null,
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            null,
            null);
    assertEquals(Mismatch.COST_UNKNOWN, LotMerges.mismatch(costed, uncosted));
    assertEquals(Mismatch.COST_UNKNOWN, LotMerges.mismatch(uncosted, costed));
    assertNull(LotMerges.mismatch(uncosted, uncosted));
  }

  @Test
  @DisplayName("A cost needs blending only when both are known and differ in value")
  void whenACostNeedsBlending() {
    assertTrue(LotMerges.needsBlend(d("3"), d("2")));
    assertFalse(LotMerges.needsBlend(d("2.0"), d("2.0000")), "the same value at another scale");
    assertFalse(LotMerges.needsBlend(null, d("2")));
    assertFalse(LotMerges.needsBlend(d("2"), null));
    assertFalse(LotMerges.needsBlend(null, null));
  }

  @Test
  @DisplayName("The blended cost is weighted by quantity and rounded half up to the minor units")
  void theBlendedCost() {
    // (6 x 2.00 + 4 x 3.00) / 10 = 2.40
    assertEquals(
        d("2.40"),
        LotMerges.blendedCost(d("6"), d("2.00"), d("4"), d("3.00"), Fx.minorUnits("GBP")));
    // (2 x 1.00 + 1 x 2.00) / 3 = 1.3333 -> 1.33
    assertEquals(
        d("1.33"),
        LotMerges.blendedCost(d("2"), d("1.00"), d("1"), d("2.00"), Fx.minorUnits("EUR")));
    // Yen have none: (2 x 10 + 3 x 11) / 5 = 10.6 -> 11
    assertEquals(
        d("11"), LotMerges.blendedCost(d("2"), d("10"), d("3"), d("11"), Fx.minorUnits("JPY")));
    // Dinars have three: (2 x 1.001 + 1 x 1.000) / 3 = 1.000666... -> 1.001
    assertEquals(
        d("1.001"),
        LotMerges.blendedCost(d("2"), d("1.001"), d("1"), d("1.000"), Fx.minorUnits("KWD")));
    // Exactly half rounds up: (1 x 1.00 + 1 x 1.01) / 2 = 1.005 -> 1.01
    assertEquals(d("1.01"), LotMerges.blendedCost(d("1"), d("1.00"), d("1"), d("1.01"), 2));
  }

  @Test
  @DisplayName("Equal costs, an unknown cost and an empty target are not blended")
  void whenThereIsNothingToBlend() {
    assertEquals(d("2.5000"), LotMerges.blendedCost(d("6"), d("2.5000"), d("4"), d("2.50"), 0));
    assertNull(LotMerges.blendedCost(d("6"), null, d("4"), null, 2));
    assertEquals(
        d("3.1234"),
        LotMerges.blendedCost(BigDecimal.ZERO, d("2.00"), d("4"), d("3.1234"), 2),
        "a target holding none takes the source's cost as it is");
  }

  @Test
  @DisplayName("The merged batch is sold by the earlier date, and a date beats none")
  void theEarlierUseByDate() {
    LocalDate early = LocalDate.parse("2097-06-01");
    LocalDate late = LocalDate.parse("2098-06-01");
    assertEquals(early, LotMerges.earlierExpiry(early, late));
    assertEquals(early, LotMerges.earlierExpiry(late, early));
    assertEquals(early, LotMerges.earlierExpiry(early, null));
    assertEquals(early, LotMerges.earlierExpiry(null, early));
    assertNull(LotMerges.earlierExpiry(null, null));
  }
}
