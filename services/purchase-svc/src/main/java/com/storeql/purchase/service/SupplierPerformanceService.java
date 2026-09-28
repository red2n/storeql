package com.storeql.purchase.service;

import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.SupplierScorecard;
import com.storeql.purchase.domain.SupplierScorecard.Card;
import com.storeql.purchase.domain.SupplierScorecard.Deliveries;
import com.storeql.purchase.domain.SupplierScorecard.Delivery;
import com.storeql.purchase.domain.SupplierScorecard.Fill;
import com.storeql.purchase.domain.SupplierScorecard.Invoices;
import com.storeql.purchase.domain.SupplierScorecard.Quality;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.purchase.repo.SupplierPerformanceRepository;
import com.storeql.purchase.repo.SupplierPerformanceRepository.InvoiceMatches;
import com.storeql.purchase.repo.SupplierPerformanceRepository.Returns;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supplier lead-time tracking and scorecards: the deliveries measured on each receipt, and a
 * period's deliveries, fill, returns and invoice matches weighed into one card per supplier. The
 * period is the last ninety days unless asked otherwise.
 */
@ApplicationScoped
public class SupplierPerformanceService {

  static final int DEFAULT_WINDOW_DAYS = 90;

  /** The most suppliers one ranking reads: the listing's own ceiling. */
  static final int RANKED_LIMIT = 100;

  @Inject SupplierPerformanceRepository repo;
  @Inject PurchaseRepository purchases;

  /** The period a scorecard covers, inclusive of both days. */
  record Period(LocalDate from, LocalDate to) {}

  /**
   * @throws ApiException 400 {@code PURCHASE_PERIOD_INVALID} when the period ends before it starts
   */
  static Period period(String from, String to) {
    LocalDate t = to == null || to.isBlank() ? LocalDate.now() : Parsing.date(to, "to");
    LocalDate f =
        from == null || from.isBlank()
            ? t.minusDays(DEFAULT_WINDOW_DAYS)
            : Parsing.date(from, "from");
    if (f.isAfter(t)) {
      throw ApiException.badRequest(
          "PURCHASE_PERIOD_INVALID", "the period ends (" + t + ") before it starts (" + f + ")");
    }
    return new Period(f, t);
  }

  /**
   * One supplier's scorecard.
   *
   * @throws ApiException 404 {@code PURCHASE_SUPPLIER_NOT_FOUND}
   */
  public Card scorecard(TenantContext ctx, UUID supplierId, String from, String to) {
    UUID tenantId = ctx.requireTenantId();
    Supplier s =
        purchases
            .findSupplier(tenantId, supplierId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + supplierId));
    Period p = period(from, to);
    return card(
        s,
        p,
        repo.deliveryStats(tenantId, supplierId, p.from(), p.to()),
        repo.fillStats(tenantId, supplierId, p.from(), p.to()),
        repo.returnStats(tenantId, supplierId, p.from(), p.to()),
        repo.invoiceStats(tenantId, supplierId, p.from(), p.to()));
  }

  /** Every supplier's scorecard, the best first; those with nothing to judge last, unscored. */
  public List<Card> scorecards(TenantContext ctx, String from, String to) {
    UUID tenantId = ctx.requireTenantId();
    Period p = period(from, to);
    Map<UUID, Deliveries> deliveries = repo.deliveryStats(tenantId, null, p.from(), p.to());
    Map<UUID, Fill> fill = repo.fillStats(tenantId, null, p.from(), p.to());
    Map<UUID, Returns> returns = repo.returnStats(tenantId, null, p.from(), p.to());
    Map<UUID, InvoiceMatches> invoices = repo.invoiceStats(tenantId, null, p.from(), p.to());
    List<Card> cards = new ArrayList<>();
    for (Supplier s : purchases.findSuppliers(tenantId, RANKED_LIMIT)) {
      cards.add(card(s, p, deliveries, fill, returns, invoices));
    }
    cards.sort(
        Comparator.comparing(
                Card::score, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
            .thenComparing(Card::supplierName, String.CASE_INSENSITIVE_ORDER));
    return cards;
  }

  /**
   * A supplier's deliveries in the period, newest first.
   *
   * @throws ApiException 404 {@code PURCHASE_SUPPLIER_NOT_FOUND}
   */
  public List<Delivery> deliveries(TenantContext ctx, UUID supplierId, String from, String to) {
    UUID tenantId = ctx.requireTenantId();
    if (purchases.findSupplier(tenantId, supplierId).isEmpty()) {
      throw ApiException.notFound(
          "PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + supplierId);
    }
    Period p = period(from, to);
    return repo.deliveries(tenantId, supplierId, p.from(), p.to());
  }

  /** The period's figures weighed into the card; a share of nothing is unknown, not zero. */
  static Card card(
      Supplier s,
      Period p,
      Map<UUID, Deliveries> deliveries,
      Map<UUID, Fill> fill,
      Map<UUID, Returns> returns,
      Map<UUID, InvoiceMatches> invoices) {
    Deliveries d = deliveries.getOrDefault(s.id(), Deliveries.NONE);
    Fill f = fill.getOrDefault(s.id(), Fill.NONE);
    Returns r = returns.get(s.id());
    Quality q =
        new Quality(
            r == null ? 0 : r.returns(),
            r == null ? BigDecimal.ZERO : r.returnedQty(),
            SupplierScorecard.pct(r == null ? BigDecimal.ZERO : r.returnedQty(), d.receivedQty()));
    InvoiceMatches m = invoices.get(s.id());
    Invoices i =
        m == null
            ? Invoices.NONE
            : new Invoices(
                m.invoices(),
                m.flagged(),
                SupplierScorecard.pct(
                    BigDecimal.valueOf(m.invoices() - m.flagged()),
                    BigDecimal.valueOf(m.invoices())));
    BigDecimal score =
        SupplierScorecard.score(d.onTimePct(), f.fillRatePct(), q.returnRatePct(), i.accuracyPct());
    return new Card(
        s.id(),
        s.name(),
        s.leadTimeDays(),
        p.from(),
        p.to(),
        d,
        f,
        q,
        i,
        score,
        SupplierScorecard.grade(score));
  }
}
