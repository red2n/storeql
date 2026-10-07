package com.storeql.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Who made a sale, and what a period of them earned (store operations & workforce).
 *
 * <p>The platform could already say who <em>rang a sale up</em> — the POS journal's cashier — and
 * that is a different question. On a counter an assistant sells and a supervisor takes the money,
 * and a shop that pays commission pays the seller. An online order usually has no seller at all,
 * and says so by leaving it empty rather than crediting whoever confirmed it.
 *
 * <p>A statement is a draft until somebody approves it and immutable after, because a figure
 * somebody was paid on must not move when a scheme is corrected, a late refund lands, or a sale is
 * re-attributed. One that has to change is superseded by one that says what it replaced.
 */
public final class SalesAttribution {

  private SalesAttribution() {}

  public static final String DRAFT = "DRAFT";
  public static final String APPROVED = "APPROVED";
  public static final String SUPERSEDED = "SUPERSEDED";

  /** What one person sold on one day, net of VAT and after discounts, with the units. */
  public record SellerDay(UUID sellerUserId, LocalDate day, BigDecimal net, BigDecimal units) {}

  /** A change of who a sale is credited to, kept because money follows it. */
  public record SellerChange(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID fromUserId,
      UUID toUserId,
      String reason,
      Instant changedAt,
      UUID changedBy) {}

  /**
   * One line of a statement: a person, a stretch of days under one arrangement, one rate band.
   *
   * @param schemeId null for a stretch under no arrangement — the sales are still carried, because
   *     a statement whose sales do not add up to the period's takings is the first thing anybody
   *     queries
   * @param thresholdFrom where the band starts, as the arrangement rated it and never rounded as
   *     money: an amount under a percentage arrangement, a count of units under a per-unit one
   * @param amount net sales under a percentage arrangement, units under a per-unit one
   * @param commission in the statement's currency, at its own minor units
   * @param rateCurrency the currency a per-unit rate, and what it earned, is in; null for a
   *     percentage or a stretch under no arrangement
   * @param ratedCommission what the band earned in {@code rateCurrency} before it was translated
   *     into the statement's currency; null when it was not translated
   */
  public record StatementLine(
      UUID id,
      UUID tenantId,
      UUID statementId,
      UUID sellerUserId,
      UUID schemeId,
      String schemeName,
      LocalDate segmentFrom,
      LocalDate segmentTo,
      BigDecimal thresholdFrom,
      BigDecimal rate,
      BigDecimal amount,
      BigDecimal commission,
      String rateCurrency,
      BigDecimal ratedCommission) {}

  /**
   * What a period earned.
   *
   * @param storeId null means every store the business has
   */
  public record Statement(
      UUID id,
      UUID tenantId,
      UUID storeId,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      String status,
      BigDecimal netSales,
      BigDecimal commission,
      String note,
      UUID supersedes,
      UUID supersededBy,
      Instant createdAt,
      UUID createdBy,
      Instant approvedAt,
      UUID approvedBy,
      List<StatementLine> lines) {

    public Statement {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean approved() {
      return APPROVED.equals(status);
    }

    /** Whether this is the statement that stands for its period: approved and not replaced. */
    public boolean standing() {
      return approved() && supersededBy() == null;
    }
  }

  /**
   * Net sales over a period: what a statement's own total says the shop took.
   *
   * @param scale the statement currency's minor units ({@code Fx.minorUnits}), so an empty period
   *     is £0.00, ¥0 or KWD 0.000
   */
  public static BigDecimal netSales(List<SellerDay> days, int scale) {
    BigDecimal total = BigDecimal.ZERO.setScale(scale);
    for (SellerDay d : days) total = total.add(d.net());
    return total;
  }

  /**
   * What is wrong with a period as somebody asked for it, or null when nothing is.
   *
   * <p>A period that has not finished is refused: a statement produced mid-month would be approved,
   * paid, and then contradicted by the rest of the month's trade.
   *
   * @param today the day the statement is being asked for
   */
  public static String periodProblem(LocalDate from, LocalDate to, LocalDate today) {
    if (from == null || to == null) return "a period has a first and a last day";
    if (to.isBefore(from)) return "a period ends on or after it starts";
    if (!to.isBefore(today)) {
      return "that period has not finished; a statement for days still trading would be paid and then"
          + " contradicted";
    }
    if (from.plusDays(366).isBefore(to)) return "a statement covers at most a year";
    return null;
  }
}
