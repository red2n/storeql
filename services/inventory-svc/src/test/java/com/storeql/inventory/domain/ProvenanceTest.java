package com.storeql.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Provenance.Drawn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What arrives is what left (SJ-D71): a return is split across the batches its sale drew, in draw
 * order and never twice; an arrival carries its source's lot, date, cost and grade, or the system
 * number when the source had no lot.
 */
class ProvenanceTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  private static Drawn drawn(String qty, String lot, String expiry, String cost) {
    return new Drawn(
        Ids.newId(),
        new BigDecimal(qty),
        lot,
        expiry == null ? null : LocalDate.parse(expiry),
        cost == null ? null : new BigDecimal(cost),
        null);
  }

  @Test
  void aReturnIsSplitAcrossTheDrawsInOrderAndTheRestIsUnplaced() {
    Drawn first = drawn("3", "L1", "2026-10-01", "2.50");
    Drawn second = drawn("2", "L2", "2026-11-01", "3.00");

    List<Drawn> back = Provenance.allocate(List.of(first, second), Map.of(), new BigDecimal("4"));
    assertEquals(2, back.size());
    assertEquals(first.batchId(), back.get(0).batchId());
    assertEquals(new BigDecimal("3"), back.get(0).qty());
    assertEquals(second.batchId(), back.get(1).batchId());
    assertEquals(new BigDecimal("1"), back.get(1).qty());
    assertEquals(BigDecimal.ZERO, Provenance.unplaced(new BigDecimal("4"), back));

    List<Drawn> more = Provenance.allocate(List.of(first, second), Map.of(), new BigDecimal("7"));
    assertEquals(new BigDecimal("2"), Provenance.unplaced(new BigDecimal("7"), more));
  }

  @Test
  void whatWasAlreadyGivenBackIsNotGivenBackAgain() {
    Drawn first = drawn("3", "L1", "2026-10-01", "2.50");
    Drawn second = drawn("2", "L2", "2026-11-01", "3.00");

    List<Drawn> back =
        Provenance.allocate(
            List.of(first, second),
            Map.of(first.batchId(), new BigDecimal("3"), second.batchId(), new BigDecimal("1")),
            new BigDecimal("2"));
    assertEquals(1, back.size(), "L1 is full; only L2's last unit can come back");
    assertEquals(second.batchId(), back.get(0).batchId());
    assertEquals(new BigDecimal("1"), back.get(0).qty());
    assertEquals(new BigDecimal("1"), Provenance.unplaced(new BigDecimal("2"), back));
  }

  @Test
  void nothingDrawnMeansNothingPlaced() {
    List<Drawn> back = Provenance.allocate(List.of(), Map.of(), new BigDecimal("2"));
    assertTrue(back.isEmpty());
    assertEquals(new BigDecimal("2"), Provenance.unplaced(new BigDecimal("2"), back));
  }

  @Test
  void anArrivalCarriesItsSourcesLotDateCostAndGrade() {
    Drawn from =
        new Drawn(
            Ids.newId(),
            new BigDecimal("4"),
            "SUPPLIER-7",
            LocalDate.parse("2026-10-01"),
            new BigDecimal("2.50"),
            "A");
    Batch arrived = Provenance.arrival(TENANT, STORE, VARIANT, from, "TO-0000a001");
    assertEquals("SUPPLIER-7", arrived.batchNo());
    assertEquals(LocalDate.parse("2026-10-01"), arrived.expiryDate());
    assertEquals(new BigDecimal("2.50"), arrived.costPrice());
    assertEquals("A", arrived.grade());
    assertEquals(new BigDecimal("4"), arrived.receivedQty());
    assertEquals(new BigDecimal("4"), arrived.remainingQty());
    assertEquals(STORE, arrived.storeId());
    assertTrue(Ids.isV7(arrived.id()));
  }

  @Test
  void aSourceWithNoLotHandsOnTheSystemNumberButStillItsDate() {
    for (String systemOrNone : new String[] {null, "", "ADJ", "CC-0000c001", "TO-0000a001"}) {
      Drawn from = drawn("2", systemOrNone, "2026-10-15", null);
      Batch arrived = Provenance.arrival(TENANT, STORE, VARIANT, from, "MO-0000b002");
      assertEquals("MO-0000b002", arrived.batchNo(), String.valueOf(systemOrNone));
      assertEquals(LocalDate.parse("2026-10-15"), arrived.expiryDate());
      assertNull(arrived.costPrice());
    }
  }

  @Test
  void anAnonymousArrivalIsTheBatchOfBefore() {
    Batch b = Provenance.anonymous(TENANT, STORE, VARIANT, new BigDecimal("1"), "RET-0000a001");
    assertEquals("RET-0000a001", b.batchNo());
    assertNull(b.expiryDate());
    assertNull(b.costPrice());
    assertEquals(Batch.STATUS_ACTIVE, b.status());
    assertEquals(Batch.MATERIAL_AVAILABLE, b.materialStatus());
  }
}
