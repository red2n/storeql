package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A request for quotation and the comparison of what came back, pure.
 *
 * <p>Suppliers quote in their own money; the comparison reads every price in the business's own
 * through the rates it keeps. A price that cannot be translated is shown but is never the lowest,
 * and a bid that cannot be added up at home — or that priced only some lines — is shown but never
 * ranked. Nothing here decides: the buyer awards.
 */
public final class Rfq {

  private Rfq() {}

  public static final String DRAFT = "DRAFT";
  public static final String ISSUED = "ISSUED";
  public static final String AWARDED = "AWARDED";
  public static final String CANCELLED = "CANCELLED";

  public static final String INVITED = "INVITED";
  public static final String QUOTED = "QUOTED";
  public static final String DECLINED = "DECLINED";

  /** The request itself. */
  public record Header(
      UUID id,
      UUID tenantId,
      String reference,
      String title,
      UUID storeId,
      String status,
      LocalDate neededBy,
      LocalDate closesOn,
      String notes,
      UUID createdBy,
      Instant createdAt,
      Instant issuedAt,
      Instant awardedAt,
      UUID awardedBy,
      Instant cancelledAt,
      String cancelledReason) {}

  /** One thing asked for. */
  public record Line(
      UUID id,
      UUID tenantId,
      UUID rfqId,
      UUID variantId,
      BigDecimal qty,
      int sortOrder,
      String notes) {}

  /** One supplier asked, and what they said: prices by line id once they have quoted. */
  public record Bid(
      UUID id,
      UUID tenantId,
      UUID rfqId,
      UUID supplierId,
      String supplierName,
      String status,
      String currency,
      Integer leadTimeDays,
      LocalDate validUntil,
      String notes,
      Instant quotedAt,
      Map<UUID, BigDecimal> prices) {}

  /** A line given to a supplier at the price they quoted, on the order it raised. */
  public record Award(
      UUID id,
      UUID tenantId,
      UUID rfqId,
      UUID lineId,
      UUID supplierId,
      UUID poId,
      BigDecimal unitPrice,
      String currency,
      Instant awardedAt) {}

  /** Translates an amount in a currency into the business's own; empty when no rate is kept. */
  @FunctionalInterface
  public interface Translator {
    Optional<BigDecimal> toHome(String currency, BigDecimal amount);
  }

  /** One supplier's price for one line, as quoted and at home. */
  public record Price(
      UUID supplierId,
      BigDecimal unitPrice,
      String currency,
      BigDecimal homeUnitPrice,
      BigDecimal lineTotal,
      BigDecimal homeLineTotal,
      boolean lowest) {}

  public record LineComparison(UUID lineId, UUID variantId, BigDecimal qty, List<Price> prices) {}

  /** One bid added up: in its own money, at home, and where it ranks. */
  public record BidSummary(
      UUID supplierId,
      String status,
      boolean complete,
      BigDecimal total,
      String currency,
      BigDecimal homeTotal,
      Integer rank) {}

  public record Comparison(
      String homeCurrency, List<LineComparison> lines, List<BidSummary> bids) {}

  /** The request as a whole. */
  public record Detail(
      Header header, List<Line> lines, List<Bid> bids, List<Award> awards, Comparison comparison) {}

  /** {@code RFQ-000001}: the tenant's own series. */
  public static String reference(long n) {
    return String.format("RFQ-%06d", n);
  }

  /**
   * Reads every quote against the lines: the lowest price per line at home, each bid's total in its
   * own money and at home, and a rank over the bids that priced every line and can be added up at
   * home.
   */
  public static Comparison compare(
      String homeCurrency, List<Line> lines, List<Bid> bids, Translator fx) {
    List<LineComparison> compared = new ArrayList<>(lines.size());
    for (Line line : lines) {
      List<Price> prices = new ArrayList<>();
      for (Bid bid : bids) {
        if (!QUOTED.equals(bid.status())) continue;
        BigDecimal unit = bid.prices().get(line.id());
        if (unit == null) continue;
        BigDecimal lineTotal = unit.multiply(line.qty()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal homeUnit = fx.toHome(bid.currency(), unit).orElse(null);
        BigDecimal homeTotal = fx.toHome(bid.currency(), lineTotal).orElse(null);
        prices.add(
            new Price(
                bid.supplierId(), unit, bid.currency(), homeUnit, lineTotal, homeTotal, false));
      }
      Optional<BigDecimal> lowest =
          prices.stream()
              .map(Price::homeUnitPrice)
              .filter(p -> p != null)
              .min(Comparator.naturalOrder());
      boolean[] marked = {false};
      List<Price> withLowest = new ArrayList<>(prices.size());
      for (Price p : prices) {
        boolean isLowest =
            !marked[0]
                && lowest.isPresent()
                && p.homeUnitPrice() != null
                && p.homeUnitPrice().compareTo(lowest.get()) == 0;
        if (isLowest) marked[0] = true;
        withLowest.add(
            new Price(
                p.supplierId(),
                p.unitPrice(),
                p.currency(),
                p.homeUnitPrice(),
                p.lineTotal(),
                p.homeLineTotal(),
                isLowest));
      }
      compared.add(new LineComparison(line.id(), line.variantId(), line.qty(), withLowest));
    }

    List<BidSummary> summaries = new ArrayList<>(bids.size());
    for (Bid bid : bids) {
      if (!QUOTED.equals(bid.status())) {
        summaries.add(
            new BidSummary(bid.supplierId(), bid.status(), false, null, null, null, null));
        continue;
      }
      BigDecimal total = BigDecimal.ZERO;
      BigDecimal home = BigDecimal.ZERO;
      boolean complete = true;
      boolean translatable = true;
      for (LineComparison lc : compared) {
        Optional<Price> mine =
            lc.prices().stream().filter(p -> p.supplierId().equals(bid.supplierId())).findFirst();
        if (mine.isEmpty()) {
          complete = false;
          continue;
        }
        total = total.add(mine.get().lineTotal());
        if (mine.get().homeLineTotal() == null) translatable = false;
        else home = home.add(mine.get().homeLineTotal());
      }
      summaries.add(
          new BidSummary(
              bid.supplierId(),
              bid.status(),
              complete,
              total,
              bid.currency(),
              translatable ? home : null,
              null));
    }
    // Rank the bids that priced everything and can be read at home, cheapest first.
    List<BidSummary> rankable =
        summaries.stream()
            .filter(b -> b.complete() && b.homeTotal() != null)
            .sorted(Comparator.comparing(BidSummary::homeTotal))
            .toList();
    List<BidSummary> ranked = new ArrayList<>(summaries.size());
    for (BidSummary b : summaries) {
      int at = rankable.indexOf(b);
      ranked.add(
          new BidSummary(
              b.supplierId(),
              b.status(),
              b.complete(),
              b.total(),
              b.currency(),
              b.homeTotal(),
              at < 0 ? null : at + 1));
    }
    return new Comparison(homeCurrency, compared, ranked);
  }
}
