package com.storeql.order.domain;

import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The receipt of a sale at shelf prices, VAT inside (intent/vat-inclusive-pricing.md), built once
 * on the server so the till's screen, the thermal print and the emailed copy cannot drift from each
 * other or from the books: every figure is read from the order, none is worked out again.
 *
 * <p>The lines are at the shelf price with what was saved beside them, the VAT table is by code and
 * its gross column adds up to what the lines were paid, and the total is that plus any container
 * deposit. The business's legal name and VAT number head it when they are known.
 */
public final class ReceiptDocument {

  private ReceiptDocument() {}

  /** Who is selling: null fields are not yet set by the business. */
  public record Seller(String legalName, String tradingName, String vatNumber) {}

  /** How a part of the total was paid. */
  public record Tender(String method, BigDecimal amount) {}

  /**
   * One line as printed.
   *
   * @param name the product's name, or null when product-svc could not say
   * @param qty what stands of the line (what was sold less what was closed short)
   * @param listUnitPrice the shelf price of one before any offer, or null when not recorded
   * @param lineGross what the customer paid for the line, VAT included
   * @param saved what the offers and discounts took off the shelf price of the line; zero when the
   *     shelf price is unknown
   */
  public record Line(
      UUID variantId,
      String name,
      BigDecimal qty,
      BigDecimal listUnitPrice,
      BigDecimal lineGross,
      BigDecimal saved,
      String vatCode,
      BigDecimal vatRate) {}

  /** One row of the VAT table: what was paid at a code, the net of it and the VAT inside. */
  public record VatRow(
      String vatCode, BigDecimal rate, BigDecimal gross, BigDecimal net, BigDecimal vat) {}

  /** The whole receipt. */
  public record Doc(
      UUID orderId,
      String reference,
      String status,
      String currency,
      Instant issuedAt,
      String timeZone,
      String localDate,
      String localTime,
      Seller seller,
      List<Line> lines,
      BigDecimal staffDiscount,
      BigDecimal promotionDiscount,
      BigDecimal deposit,
      BigDecimal total,
      List<VatRow> vat,
      BigDecimal vatTotal,
      List<Tender> tenders) {
    public Doc {
      lines = List.copyOf(lines);
      vat = List.copyOf(vat);
      tenders = List.copyOf(tenders);
    }
  }

  /**
   * The receipt as plain text, for the emailed copy: the same figures the till shows, nothing
   * worked out again. Amounts print at the currency's own scale as they were stored.
   */
  public static String toText(Doc d) {
    StringBuilder sb = new StringBuilder();
    Seller s = d.seller();
    String name = s.legalName() != null ? s.legalName() : s.tradingName();
    if (name != null) sb.append(name).append('\n');
    if (s.tradingName() != null && !s.tradingName().equals(name)) {
      sb.append("Trading as ").append(s.tradingName()).append('\n');
    }
    if (s.vatNumber() != null) sb.append("VAT No. ").append(s.vatNumber()).append('\n');
    sb.append("\nReceipt ")
        .append(d.reference())
        .append(" — ")
        .append(d.localDate())
        .append(' ')
        .append(d.localTime())
        .append(" (")
        .append(d.timeZone())
        .append(")\n\n");
    for (Line l : d.lines()) {
      sb.append(l.name() != null ? l.name() : String.valueOf(l.variantId()))
          .append("\n  ")
          .append(l.qty().stripTrailingZeros().toPlainString());
      if (l.listUnitPrice() != null) {
        sb.append(" × ").append(l.listUnitPrice().toPlainString());
      }
      sb.append("   ").append(l.lineGross().toPlainString()).append(' ').append(d.currency());
      if (l.saved().signum() > 0) {
        sb.append("   (saved ").append(l.saved().toPlainString()).append(')');
      }
      sb.append('\n');
    }
    if (d.deposit().signum() > 0) {
      sb.append("Container deposit   ").append(d.deposit().toPlainString()).append('\n');
    }
    sb.append("\nTotal   ").append(d.total().toPlainString()).append(' ').append(d.currency());
    sb.append("\nPrices include VAT.\n\nVAT      Rate     Gross       Net       VAT\n");
    for (VatRow v : d.vat()) {
      sb.append(v.vatCode() == null ? "-" : v.vatCode())
          .append("      ")
          .append(
              v.rate() == null
                  ? "-"
                  : v.rate().movePointRight(2).stripTrailingZeros().toPlainString() + "%")
          .append("     ")
          .append(v.gross().toPlainString())
          .append("     ")
          .append(v.net().toPlainString())
          .append("     ")
          .append(v.vat().toPlainString())
          .append('\n');
    }
    for (Tender t : d.tenders()) {
      sb.append(t.method() == null ? "Paid" : "Paid by " + t.method())
          .append("   ")
          .append(t.amount().toPlainString())
          .append('\n');
    }
    sb.append("\nThank you.\n");
    return sb.toString();
  }

  /**
   * Builds the receipt of a shelf-price sale.
   *
   * @param order the sale, {@code taxInclusive}
   * @param items its lines
   * @param deposit the container deposit added to the sale, or zero
   * @param names product names by variant; a variant with none prints without a name
   * @param seller who is selling
   * @param zone the store's own time zone, in which the sale's date and time print
   * @param tenders how it was paid
   * @throws IllegalArgumentException for an order not sold at shelf prices
   */
  public static Doc build(
      Order order,
      List<OrderItem> items,
      BigDecimal deposit,
      Map<UUID, String> names,
      Seller seller,
      ZoneId zone,
      List<Tender> tenders) {
    if (!order.taxInclusive()) {
      throw new IllegalArgumentException("a receipt document is of a sale at shelf prices");
    }
    List<Line> lines = new ArrayList<>();
    Map<String, BigDecimal[]> byCode = new LinkedHashMap<>();
    Map<String, BigDecimal> rateOf = new LinkedHashMap<>();
    BigDecimal vatTotal = BigDecimal.ZERO;
    for (OrderItem i : items) {
      BigDecimal standing = i.standingQty();
      if (standing.signum() <= 0) continue;
      BigDecimal gross = i.paidGross() == null ? i.lineTotal() : i.paidGross();
      BigDecimal vat = i.vatAmount() == null ? BigDecimal.ZERO : i.vatAmount();
      BigDecimal saved = BigDecimal.ZERO;
      if (i.listUnitPrice() != null) {
        saved =
            i.listUnitPrice()
                .multiply(standing)
                .setScale(gross.scale(), java.math.RoundingMode.HALF_UP)
                .subtract(gross)
                .max(BigDecimal.ZERO);
      }
      lines.add(
          new Line(
              i.variantId(),
              names.get(i.variantId()),
              standing,
              i.listUnitPrice(),
              gross,
              saved,
              i.vatCode(),
              i.vatRate()));
      String code = i.vatCode() == null ? "" : i.vatCode();
      BigDecimal[] sums =
          byCode.computeIfAbsent(code, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      sums[0] = sums[0].add(gross);
      sums[1] = sums[1].add(vat);
      rateOf.putIfAbsent(code, i.vatRate());
      vatTotal = vatTotal.add(vat);
    }
    List<VatRow> table = new ArrayList<>();
    for (var e : byCode.entrySet()) {
      BigDecimal gross = e.getValue()[0];
      BigDecimal vat = e.getValue()[1];
      table.add(
          new VatRow(
              e.getKey().isEmpty() ? null : e.getKey(),
              rateOf.get(e.getKey()),
              gross,
              gross.subtract(vat),
              vat));
    }
    String id = order.id().toString();
    ZonedDateTime local = order.createdAt().atZone(zone);
    return new Doc(
        order.id(),
        id.substring(id.length() - 8),
        order.status(),
        order.currency(),
        order.createdAt(),
        zone.getId(),
        local.toLocalDate().toString(),
        local.toLocalTime().withNano(0).toString(),
        seller,
        lines,
        order.discountAmount() == null ? BigDecimal.ZERO : order.discountAmount(),
        order.promotionDiscount() == null ? BigDecimal.ZERO : order.promotionDiscount(),
        deposit == null ? BigDecimal.ZERO : deposit,
        order.total(),
        table,
        vatTotal,
        tenders);
  }
}
