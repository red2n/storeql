package com.storeql.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Recall.Match;
import com.storeql.inventory.domain.Recall.Scope;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a recall's scope takes off sale: a known lot or date outside the scope rules a batch out, an
 * unknown one cannot, and a batch number this service wrote itself is not a lot.
 */
class RecallTest {

  private static final UUID VARIANT = Ids.newId();
  private static final LocalDate OCT_1 = LocalDate.parse("2026-10-01");
  private static final LocalDate OCT_31 = LocalDate.parse("2026-10-31");

  @Test
  void aLineNamingNothingCoversEveryPackWhateverItsLotOrDate() {
    Scope every = new Scope(Ids.newId(), VARIANT, null, null, null);
    assertTrue(every.coversEveryPack());
    assertEquals(Match.IN_SCOPE, every.classify("L1", OCT_1));
    assertEquals(Match.IN_SCOPE, every.classify(null, null));
  }

  @Test
  void aLotIsMatchedIgnoringCaseAndSpacesAndAnotherLotIsRuledOut() {
    Scope lot = new Scope(Ids.newId(), VARIANT, "L-2291", null, null);
    assertEquals(Match.IN_SCOPE, lot.classify(" l-2291 ", null));
    assertNull(lot.classify("L-2292", null));
    assertEquals(Match.LOT_UNKNOWN, lot.classify(null, OCT_1));
    assertEquals(Match.LOT_UNKNOWN, lot.classify("  ", OCT_1));
  }

  @Test
  void aBatchNumberTheServiceWroteItselfCannotRuleABatchOut() {
    Scope lot = new Scope(Ids.newId(), VARIANT, "L-2291", null, null);
    for (String system :
        List.of("ADJ", "CC-1a2b3c4d", "MO-1a2b3c4d", "TO-1a2b3c4d", "RET-1a2b3c4d")) {
      assertEquals(Match.LOT_UNKNOWN, lot.classify(system, OCT_1), system);
    }
    assertTrue(Recall.isSupplierLot("TO-SUPPLIER-LOT"));
  }

  /**
   * The service's own batch numbers now end in an id's tail ({@link Ids#shortRef}) rather than its
   * head. If recall stopped recognising them it would treat "MO-…" as a supplier lot and rule stock
   * out of a recall by a number the service invented.
   */
  @Test
  void batchNumbersBuiltFromAnIdTailAreStillRecognisedAsTheServicesOwn() {
    for (int i = 0; i < 1_000; i++) {
      String ref = Ids.shortRef(Ids.newId());
      for (String kind : List.of("CC", "MO", "TO", "RET")) {
        assertFalse(Recall.isSupplierLot(kind + "-" + ref), kind + "-" + ref);
      }
    }
  }

  /** Only that exact shape is the service's: anything close to it is still a supplier's lot. */
  @Test
  void aNearMissOnTheSystemShapeIsStillASupplierLot() {
    String ref = Ids.shortRef(Ids.parse("01a0905d-7082-7518-9ec6-aee90d72a43e"));
    assertEquals("0d72a43e", ref);

    assertTrue(Recall.isSupplierLot("MO-" + ref.toUpperCase(Locale.ROOT)), "upper case");
    assertTrue(Recall.isSupplierLot("MO-" + ref.substring(1)), "seven digits");
    assertTrue(Recall.isSupplierLot("MO-" + ref + "0"), "nine digits");
    assertTrue(Recall.isSupplierLot("XX-" + ref), "unknown kind");
    assertFalse(Recall.isSupplierLot(null));
    assertFalse(Recall.isSupplierLot(" "));
  }

  @Test
  void datesAreInclusiveAndAnUnknownDateIsHeldAsPossiblyAffected() {
    Scope range = new Scope(Ids.newId(), VARIANT, null, OCT_1, OCT_31);
    assertEquals(Match.IN_SCOPE, range.classify("L1", OCT_1));
    assertEquals(Match.IN_SCOPE, range.classify("L1", OCT_31));
    assertNull(range.classify("L1", OCT_31.plusDays(1)));
    assertNull(range.classify("L1", OCT_1.minusDays(1)));
    assertEquals(Match.DATE_UNKNOWN, range.classify("L1", null));

    Scope onOrAfter = new Scope(Ids.newId(), VARIANT, null, OCT_1, null);
    assertEquals(Match.IN_SCOPE, onOrAfter.classify(null, OCT_31.plusYears(1)));
  }

  @Test
  void aKnownMismatchOutranksAnUnknownAndTheMostCertainLineWins() {
    Scope both = new Scope(Ids.newId(), VARIANT, "L1", OCT_1, OCT_31);
    assertNull(both.classify("L2", null), "a different lot rules it out, date or no date");
    assertNull(both.classify(null, OCT_31.plusDays(1)), "an out-of-range date rules it out");
    assertEquals(Match.LOT_UNKNOWN, both.classify(null, null));

    List<Scope> notice =
        List.of(
            new Scope(Ids.newId(), VARIANT, "L9", null, null),
            new Scope(Ids.newId(), VARIANT, null, OCT_1, OCT_31),
            new Scope(Ids.newId(), Ids.newId(), null, null, null));
    assertEquals(Match.IN_SCOPE, Recall.classify(notice, VARIANT, null, OCT_1));
    assertEquals(Match.LOT_UNKNOWN, Recall.classify(notice, VARIANT, "ADJ", OCT_31.plusDays(9)));
    assertNull(Recall.classify(notice, VARIANT, "L1", OCT_31.plusDays(9)));
    assertFalse(Match.IN_SCOPE.isReleasable());
    assertTrue(Match.DATE_UNKNOWN.isReleasable());
  }

  @Test
  void stockOfSeveralLotsIsJudgedByTheMostCertainOfThem() {
    List<Scope> byLot = List.of(new Scope(Ids.newId(), VARIANT, "L1", null, null));
    Recall.Lot own = new Recall.Lot("L2", OCT_1);
    Recall.Lot merged = new Recall.Lot("L1", OCT_31);
    Recall.Lot unnumbered = new Recall.Lot(null, OCT_1);

    assertNull(Recall.classify(byLot, VARIANT, List.of(own)), "its own lot rules it out");
    assertEquals(
        Match.IN_SCOPE,
        Recall.classify(byLot, VARIANT, List.of(own, merged)),
        "a lot merged into it brings it in scope, whatever order the lots are in");
    assertEquals(Match.IN_SCOPE, Recall.classify(byLot, VARIANT, List.of(merged, own)));
    assertEquals(
        Match.LOT_UNKNOWN,
        Recall.classify(byLot, VARIANT, List.of(own, unnumbered)),
        "a lot nobody can name cannot rule the stock out");
    assertNull(Recall.classify(byLot, VARIANT, List.of()), "no lot, no verdict");
  }

  @Test
  void eachLotIsJudgedWithItsOwnDate() {
    List<Scope> window = List.of(new Scope(Ids.newId(), VARIANT, "L1", OCT_1, OCT_1.plusDays(5)));

    assertNull(
        Recall.classify(window, VARIANT, List.of(new Recall.Lot("L1", OCT_31))),
        "the merged-in lot's date is outside the window");
    assertEquals(
        Match.IN_SCOPE,
        Recall.classify(
            window, VARIANT, List.of(new Recall.Lot("L2", OCT_1), new Recall.Lot("L1", OCT_1))));
  }
}
