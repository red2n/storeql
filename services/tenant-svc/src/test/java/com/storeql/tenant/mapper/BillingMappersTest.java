package com.storeql.tenant.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Subscriptions.Invoice;
import com.storeql.tenant.dto.BillingDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An invoice on the wire.
 *
 * <p>The receivables are every business's invoices on one screen, so a row that does not say whose
 * it is cannot be chased. The business is named on the invoice itself rather than looked up beside
 * it, which keeps the business's own answers and the platform's the same shape.
 */
class BillingMappersTest {

  private static Invoice invoice(UUID id, UUID tenantId) {
    return new Invoice(
        id,
        tenantId,
        Ids.newId(),
        "INV-2026-000042",
        "PERIOD",
        "OPEN",
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-15"),
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-30"),
        "EUR",
        new BigDecimal("10.0000"),
        "DOMESTIC",
        new BigDecimal("0.2300"),
        new BigDecimal("2.3000"),
        new BigDecimal("12.3000"),
        new BigDecimal("1.0000"),
        "{}",
        "{}",
        null,
        null,
        Instant.parse("2026-09-01T00:00:00Z"),
        Instant.parse("2026-09-01T00:00:00Z"));
  }

  @Test
  @DisplayName("An invoice names the business it was sent to")
  void anInvoiceNamesItsBusiness() {
    UUID id = Ids.newId();
    UUID business = Ids.newId();

    BillingDtos.InvoiceResponse wire = BillingMappers.toDto(invoice(id, business));

    assertEquals(id.toString(), wire.id());
    assertEquals(business.toString(), wire.tenantId());
    // Nothing else moved to make room for it.
    assertEquals("INV-2026-000042", wire.number());
    assertEquals(new BigDecimal("12.30"), wire.totalAmount());
    assertEquals(new BigDecimal("11.30"), wire.outstanding());
  }

  @Test
  @DisplayName("A list of invoices from several businesses keeps each one's own business")
  void aListKeepsEachBusiness() {
    UUID one = Ids.newId();
    UUID two = Ids.newId();

    List<BillingDtos.InvoiceResponse> wire =
        BillingMappers.invoices(List.of(invoice(Ids.newId(), one), invoice(Ids.newId(), two)));

    assertEquals(
        List.of(one.toString(), two.toString()), wire.stream().map(i -> i.tenantId()).toList());
  }
}
