package com.storeql.purchase.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain.ConsignmentSale;
import com.storeql.purchase.domain.Domain.DutyRelease;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.Supplier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journals written from inventory-svc's events name what they are about as people read them:
 * "#" and the last eight of the id, the same handle every other journal and screen uses. They are
 * read in the accounting package and on the Integrations screen, where a whole id is unreadable.
 */
class JournalDescriptionTest {

  private static final UUID TENANT = Ids.parse("01a090ae-611e-702c-a97b-d1b8025478e1");
  private static final UUID STORE = Ids.parse("01a090ae-611e-703c-a378-a4972ea461c8");
  private static final UUID VARIANT = Ids.parse("01a0905d-7082-7518-9ec6-aee90d72a43e");
  private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

  private static void describedAs(List<NominalLedgerEntry> lines, String description) {
    assertThat(lines.isEmpty(), is(false));
    for (NominalLedgerEntry l : lines) {
      assertThat(l.description(), is(description));
      assertThat(l.description(), not(containsString(VARIANT.toString())));
    }
  }

  @Test
  @DisplayName("Duty released from bond names the variant by '#' and its handle")
  void dutyNamesTheVariantByItsHandle() {
    DutyRelease r =
        new DutyRelease(
            Ids.newId(),
            TENANT,
            Ids.newId(),
            Ids.newId(),
            STORE,
            VARIANT,
            new BigDecimal("4"),
            new BigDecimal("2.50"),
            new BigDecimal("10.00"),
            "GBP",
            "W5 Sep",
            DAY,
            Instant.now());
    describedAs(
        DutyService.posting(r), "Duty on 4 x variant #0d72a43e released from bond (W5 Sep)");
  }

  @Test
  @DisplayName("A consignment sale names the variant by '#' and its handle")
  void consignmentNamesTheVariantByItsHandle() {
    UUID supplierId = Ids.newId();
    ConsignmentSale sale =
        new ConsignmentSale(
            Ids.newId(),
            TENANT,
            Ids.newId(),
            supplierId,
            STORE,
            VARIANT,
            Ids.newId(),
            Ids.newId(),
            new BigDecimal("3"),
            new BigDecimal("3.00"),
            new BigDecimal("9.00"),
            "GBP",
            DAY,
            null,
            Instant.now());
    Supplier supplier =
        new Supplier(
            supplierId,
            TENANT,
            "Sale or Return Ltd",
            null,
            true,
            "GB",
            "GBP",
            30,
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    describedAs(
        ConsignmentService.salePosting(sale, supplier),
        "Consignment sale of 3 x variant #0d72a43e (Sale or Return Ltd)");
  }
}
