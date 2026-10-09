package com.storeql.order.fiscal;

import com.storeql.order.domain.Domain.FiscalReceipt;
import com.storeql.order.domain.Domain.FiscalStoreSettings;
import com.storeql.order.domain.Domain.TseDevice;
import com.storeql.order.repo.FiscalReceiptRepository.RegisterLine;
import com.storeql.order.repo.FiscalReceiptRepository.RegisterOrder;
import com.storeql.order.repo.FiscalReceiptRepository.RegisterTender;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything a fiscal file is written from (18.5): the register for one store, series and period,
 * with the lines, tenders and order headers behind each document, the store's identity as
 * tenant-svc holds it, and the product names as product-svc holds them. Assembled once by the
 * service; the export writers are pure functions over it.
 *
 * @param settings the store's fiscal settings
 * @param device the store's security module, or null
 * @param business the trading entity the file names
 * @param store the store the register belongs to
 * @param seriesCode the series
 * @param period the fiscal year
 * @param currency ISO-4217 of the documents
 * @param generatedAt when the file was produced
 * @param documents the register, in number order
 * @param lines every line, in document order
 * @param tenders every tender, in document order
 * @param orders the order header behind each document, by number
 * @param productNames variant id to a printable name, for the lines
 * @param linesByNumber the lines grouped by document number (derived)
 * @param tendersByNumber the tenders grouped by document number (derived)
 */
public record RegisterSnapshot(
    FiscalStoreSettings settings,
    TseDevice device,
    Business business,
    Store store,
    String seriesCode,
    String period,
    String currency,
    Instant generatedAt,
    List<FiscalReceipt> documents,
    List<RegisterLine> lines,
    List<RegisterTender> tenders,
    Map<Long, RegisterOrder> orders,
    Map<UUID, ProductName> productNames,
    Map<Long, List<RegisterLine>> linesByNumber,
    Map<Long, List<RegisterTender>> tendersByNumber) {

  /**
   * Defensive copies: a snapshot handed to a writer is not changed under it. The lines and tenders
   * are indexed once by document number (null here means "work them out"), so a writer asking for
   * one document's lines does not scan the whole year's.
   */
  public RegisterSnapshot {
    documents = List.copyOf(documents);
    lines = List.copyOf(lines);
    tenders = List.copyOf(tenders);
    orders = Map.copyOf(orders);
    productNames = Map.copyOf(productNames);
    if (linesByNumber == null) {
      linesByNumber = lines.stream().collect(Collectors.groupingBy(RegisterLine::number));
    }
    if (tendersByNumber == null) {
      tendersByNumber = tenders.stream().collect(Collectors.groupingBy(RegisterTender::number));
    }
    // Map.copyOf over the copied lists: immutable all the way down, and visibly so.
    linesByNumber =
        Map.copyOf(
            linesByNumber.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
    tendersByNumber =
        Map.copyOf(
            tendersByNumber.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
  }

  /** The snapshot as the service assembles it; the per-document indexes are built here. */
  public RegisterSnapshot(
      FiscalStoreSettings settings,
      TseDevice device,
      Business business,
      Store store,
      String seriesCode,
      String period,
      String currency,
      Instant generatedAt,
      List<FiscalReceipt> documents,
      List<RegisterLine> lines,
      List<RegisterTender> tenders,
      Map<Long, RegisterOrder> orders,
      Map<UUID, ProductName> productNames) {
    this(
        settings,
        device,
        business,
        store,
        seriesCode,
        period,
        currency,
        generatedAt,
        documents,
        lines,
        tenders,
        orders,
        productNames,
        null,
        null);
  }

  /** The legal entity: what the file's header names. */
  public record Business(String legalName, String taxRegistrationNumber, String country) {}

  /** The store: its code and postal address, as tenant-svc holds them. */
  public record Store(
      UUID id,
      String name,
      String code,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String postalCode) {}

  /** A printable product: what a line's description reads. */
  public record ProductName(String name, String sku, String unit) {}

  /** The lines behind one document. */
  public List<RegisterLine> linesOf(long number) {
    return linesByNumber.getOrDefault(number, List.of());
  }

  /** The tenders behind one document. */
  public List<RegisterTender> tendersOf(long number) {
    return tendersByNumber.getOrDefault(number, List.of());
  }

  /**
   * The VAT on a line: as the quote priced it, or the order's tax apportioned by the line's share
   * of its subtotal when the line was placed with pricing enforcement off.
   */
  public BigDecimal vatOf(RegisterLine line, FiscalReceipt doc) {
    if (line.vatAmount() != null) {
      return line.vatAmount();
    }
    RegisterOrder o = orders.get(doc.number());
    if (o == null || o.subtotal() == null || o.subtotal().signum() == 0 || o.taxAmount() == null) {
      return BigDecimal.ZERO;
    }
    return o.taxAmount().multiply(line.lineTotal()).divide(o.subtotal(), 4, RoundingMode.HALF_UP);
  }

  /** The rate a line was taxed at, as a percentage with two decimals. */
  public BigDecimal ratePercentOf(RegisterLine line, FiscalReceipt doc) {
    return com.storeql.order.domain.LineRate.percent(
        line.vatRate(), vatOf(line, doc), line.lineTotal());
  }

  /** The name a line prints, falling back to the variant id when product-svc knows no name. */
  public String nameOf(RegisterLine line) {
    ProductName p = productNames.get(line.variantId());
    return p == null || p.name() == null || p.name().isBlank()
        ? line.variantId().toString()
        : p.name();
  }
}
