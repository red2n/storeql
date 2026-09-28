package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A supplier's performance, pure: each delivery measured against its promise, and a period's
 * figures weighed into one score.
 *
 * <p>The promise is the order's own expected date, or failing that the supplier's quoted lead time
 * from the day the order went to them; a delivery with neither is measured but never judged late.
 * The score weighs what is known — on-time deliveries 40, fill 30, quality 20 (the whole less the
 * return rate), invoice accuracy 10 — and renormalises over the parts that exist, so a supplier
 * nobody has invoiced yet is not marked down for it.
 */
public final class SupplierScorecard {

  private SupplierScorecard() {}

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal W_ON_TIME = new BigDecimal("40");
  private static final BigDecimal W_FILL = new BigDecimal("30");
  private static final BigDecimal W_QUALITY = new BigDecimal("20");
  private static final BigDecimal W_INVOICES = new BigDecimal("10");

  /** One delivery as measured on its goods receipt: the fact the scorecard is made from. */
  public record Delivery(
      UUID id,
      UUID tenantId,
      UUID supplierId,
      UUID poId,
      UUID grId,
      UUID storeId,
      Instant orderedAt,
      LocalDate promisedDate,
      Instant receivedAt,
      int leadDays,
      Integer lateDays,
      boolean complete,
      BigDecimal receivedQty) {}

  /** The period's deliveries added up. */
  public record Deliveries(
      int count,
      BigDecimal avgLeadDays,
      BigDecimal medianLeadDays,
      Integer maxLeadDays,
      int promised,
      int onTime,
      int late,
      BigDecimal onTimePct,
      BigDecimal avgDaysLate,
      BigDecimal receivedQty) {
    public static final Deliveries NONE =
        new Deliveries(0, null, null, null, 0, 0, 0, null, null, BigDecimal.ZERO);
  }

  /** Ordered against received, over the orders that reached their end in the period. */
  public record Fill(
      int orders,
      BigDecimal orderedQty,
      BigDecimal receivedQty,
      BigDecimal fillRatePct,
      int shortClosed) {
    public static final Fill NONE = new Fill(0, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
  }

  /** What went back, against what arrived. */
  public record Quality(int returns, BigDecimal returnedQty, BigDecimal returnRatePct) {
    public static final Quality NONE = new Quality(0, BigDecimal.ZERO, null);
  }

  /** Invoices that agreed with the order and the receipt, against all of them. */
  public record Invoices(int invoices, int flagged, BigDecimal accuracyPct) {
    public static final Invoices NONE = new Invoices(0, 0, null);
  }

  /** The scorecard: one supplier, one period. */
  public record Card(
      UUID supplierId,
      String supplierName,
      Integer leadTimeDays,
      LocalDate from,
      LocalDate to,
      Deliveries deliveries,
      Fill fill,
      Quality quality,
      Invoices invoices,
      BigDecimal score,
      String grade) {}

  /** The date the goods were due: the order's own, else the quote from the day it was ordered. */
  public static LocalDate promise(
      LocalDate expectedDelivery, Instant orderedAt, Integer quotedLeadDays) {
    if (expectedDelivery != null) return expectedDelivery;
    if (quotedLeadDays == null) return null;
    return date(orderedAt).plusDays(quotedLeadDays);
  }

  /** Whole days from order to arrival; never negative. */
  public static int leadDays(Instant orderedAt, Instant receivedAt) {
    long days = ChronoUnit.DAYS.between(date(orderedAt), date(receivedAt));
    return (int) Math.max(0, days);
  }

  /** Days after the promise, negative when early; null when nothing was promised. */
  public static Integer lateDays(LocalDate promisedDate, Instant receivedAt) {
    if (promisedDate == null) return null;
    return (int) ChronoUnit.DAYS.between(promisedDate, date(receivedAt));
  }

  /** {@code part} as a share of {@code whole} in percent, one decimal; unknown for no whole. */
  public static BigDecimal pct(BigDecimal part, BigDecimal whole) {
    if (part == null || whole == null || whole.signum() == 0) return null;
    return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
  }

  /**
   * The weighted score over the parts that are known, one decimal; null when nothing is.
   *
   * @param onTimePct deliveries on or before their promise, in percent; null when none promised
   * @param fillRatePct received against ordered over the period's finished orders; null when none
   * @param returnRatePct what went back against what arrived; null when nothing arrived
   * @param invoiceAccuracyPct invoices that matched against all; null when none
   */
  public static BigDecimal score(
      BigDecimal onTimePct,
      BigDecimal fillRatePct,
      BigDecimal returnRatePct,
      BigDecimal invoiceAccuracyPct) {
    BigDecimal weighted = BigDecimal.ZERO;
    BigDecimal weights = BigDecimal.ZERO;
    if (onTimePct != null) {
      weighted = weighted.add(onTimePct.multiply(W_ON_TIME));
      weights = weights.add(W_ON_TIME);
    }
    if (fillRatePct != null) {
      weighted = weighted.add(fillRatePct.multiply(W_FILL));
      weights = weights.add(W_FILL);
    }
    if (returnRatePct != null) {
      BigDecimal quality = HUNDRED.subtract(returnRatePct).max(BigDecimal.ZERO);
      weighted = weighted.add(quality.multiply(W_QUALITY));
      weights = weights.add(W_QUALITY);
    }
    if (invoiceAccuracyPct != null) {
      weighted = weighted.add(invoiceAccuracyPct.multiply(W_INVOICES));
      weights = weights.add(W_INVOICES);
    }
    if (weights.signum() == 0) return null;
    return weighted.divide(weights, 1, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(HUNDRED);
  }

  /** A at 90 and above, B at 75, C at 60, D below; null for no score. */
  public static String grade(BigDecimal score) {
    if (score == null) return null;
    if (score.compareTo(new BigDecimal("90")) >= 0) return "A";
    if (score.compareTo(new BigDecimal("75")) >= 0) return "B";
    if (score.compareTo(new BigDecimal("60")) >= 0) return "C";
    return "D";
  }

  private static LocalDate date(Instant at) {
    return at.atZone(ZoneOffset.UTC).toLocalDate();
  }
}
