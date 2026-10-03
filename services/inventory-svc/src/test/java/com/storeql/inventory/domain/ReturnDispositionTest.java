package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Where returned goods go, by the condition the till recorded — pure. */
class ReturnDispositionTest {

  @Test
  void sealedAndAnEventWithNoConditionGoBackOnSale() {
    assertThat(ReturnDisposition.of("SEALED", false).materialStatus(), is("AVAILABLE"));
    assertThat(ReturnDisposition.of(null, false).materialStatus(), is("AVAILABLE"));
    assertThat(ReturnDisposition.of("", false).materialStatus(), is("AVAILABLE"));
    assertThat(ReturnDisposition.of("SEALED", false).reason(), is(nullValue()));
    assertThat(ReturnDisposition.of("SEALED", false).onSale(), is(true));
  }

  @Test
  void openedWaitsForACheckWithItsReasonInWords() {
    var d = ReturnDisposition.of("OPENED", false);
    assertThat(d.materialStatus(), is("INSPECTION"));
    assertThat(d.reason(), containsString("opened"));
    assertThat(d.onSale(), is(false));
  }

  @Test
  void damagedAndFaultyAreBothDamagedAndSayWhich() {
    var damaged = ReturnDisposition.of("DAMAGED", false);
    var faulty = ReturnDisposition.of("faulty", false);
    assertThat(damaged.materialStatus(), is("DAMAGED"));
    assertThat(faulty.materialStatus(), is("DAMAGED"));
    assertThat(damaged.reason(), containsString("damaged"));
    assertThat(faulty.reason(), containsString("faulty"));
  }

  @Test
  void aRecallReturnIsRecalledWhateverItsCondition() {
    for (String condition : new String[] {"SEALED", "OPENED", "DAMAGED", "FAULTY", null}) {
      assertThat(ReturnDisposition.of(condition, true).materialStatus(), is("RECALLED"));
    }
  }

  @Test
  void placingKeepsTheLotCostAndDateAndChangesOnlyTheStatus() {
    Batch b =
        new Batch(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "L1",
            new BigDecimal("2"),
            new BigDecimal("2"),
            new BigDecimal("2.50"),
            LocalDate.parse("2026-12-01"),
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            "A",
            null);
    Batch placed = ReturnDisposition.of("OPENED", false).place(b);
    assertThat(placed.materialStatus(), is("INSPECTION"));
    assertThat(placed.materialStatusReason(), containsString("opened"));
    assertThat(placed.id(), is(b.id()));
    assertThat(placed.batchNo(), is("L1"));
    assertThat(placed.costPrice(), is(b.costPrice()));
    assertThat(placed.expiryDate(), is(b.expiryDate()));
    assertThat(placed.grade(), is("A"));
    assertThat(ReturnDisposition.ON_SALE.place(b), is(b));
  }
}
