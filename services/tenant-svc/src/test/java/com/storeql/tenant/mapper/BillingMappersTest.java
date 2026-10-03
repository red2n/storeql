package com.storeql.tenant.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Subscriptions;
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
    return invoice(id, tenantId, "EUR", "10.0000", "2.3000", "12.3000", "1.0000");
  }

  private static Invoice invoice(
      UUID id, UUID tenantId, String currency, String net, String tax, String total, String paid) {
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
        currency,
        new BigDecimal(net),
        "DOMESTIC",
        new BigDecimal("0.2300"),
        new BigDecimal(tax),
        new BigDecimal(total),
        new BigDecimal(paid),
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

  @Test
  @DisplayName(
      "An invoice prints its money to its own currency's minor units: whole yen, a dinar's third")
  void anInvoicePrintsItsCurrencysOwnMinorUnits() {
    BillingDtos.InvoiceResponse yen =
        BillingMappers.toDto(
            invoice(Ids.newId(), Ids.newId(), "JPY", "1000.0000", "100.0000", "1100.0000", "0"));
    assertEquals(new BigDecimal("1100"), yen.totalAmount(), "never 1100.00 yen");
    assertEquals(new BigDecimal("1100"), yen.outstanding());
    assertEquals(new BigDecimal("0"), yen.amountPaid());

    BillingDtos.InvoiceResponse dinar =
        BillingMappers.toDto(
            invoice(Ids.newId(), Ids.newId(), "KWD", "10.1250", "0.5063", "10.6313", "0.0000"));
    assertEquals(new BigDecimal("10.631"), dinar.totalAmount(), "a dinar keeps three places");
    assertEquals(new BigDecimal("0.506"), dinar.taxAmount());

    Subscriptions.Payment paid =
        new Subscriptions.Payment(
            Ids.newId(),
            Ids.newId(),
            new BigDecimal("500.0000"),
            "JPY",
            "CARD",
            null,
            null,
            LocalDate.parse("2026-09-02"),
            Ids.newId(),
            Instant.parse("2026-09-02T00:00:00Z"));
    assertEquals(new BigDecimal("500"), BillingMappers.toDto(paid).amount());
  }

  @Test
  @DisplayName(
      "A line's price reads as it was set, to at least its currency's places and never fewer than"
          + " it carries")
  void aLineReadsAsItWasSetInItsCurrency() {
    Invoice yen = invoice(Ids.newId(), Ids.newId(), "JPY", "3.5000", "0", "3.5000", "0");
    Subscriptions.InvoiceLine texts =
        new Subscriptions.InvoiceLine(
            Ids.newId(),
            1,
            "USAGE",
            "SMS over the allowance",
            new BigDecimal("100"),
            new BigDecimal("0.0350"),
            new BigDecimal("3.5000"));
    Subscriptions.InvoiceLine plan =
        new Subscriptions.InvoiceLine(
            Ids.newId(),
            2,
            "PLAN",
            "Plan",
            BigDecimal.ONE,
            new BigDecimal("1000.0000"),
            new BigDecimal("1000.0000"));
    BillingDtos.InvoiceFileResponse file =
        BillingMappers.toDto(new Subscriptions.InvoiceFile(yen, List.of(texts, plan), List.of()));
    assertEquals(new BigDecimal("0.035"), file.lines().get(0).unitAmount(), "a part of a yen");
    assertEquals(new BigDecimal("3.5"), file.lines().get(0).amount());
    assertEquals(new BigDecimal("1000"), file.lines().get(1).amount(), "whole yen, not 1000.00");

    Invoice euro = invoice(Ids.newId(), Ids.newId(), "EUR", "10.0000", "0", "10.0000", "0");
    BillingDtos.InvoiceFileResponse euros =
        BillingMappers.toDto(new Subscriptions.InvoiceFile(euro, List.of(plan), List.of()));
    assertEquals(new BigDecimal("1000.00"), euros.lines().get(0).amount(), "a euro's two places");
  }
}
