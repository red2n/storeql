package com.storeql.payment.service;

import com.storeql.payment.domain.Domain.TenderMixRow;
import com.storeql.payment.repo.TenderMixRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The tender-mix report: what proportion of the take arrived through each payment method.
 *
 * <p>The repository returns captures and refunds; the net and the percentage share are completed
 * here, because a share needs the total of every row and no single row can see it.
 */
@ApplicationScoped
public class TenderMixService {

  @Inject TenderMixRepository repo;

  /**
   * @param stores restrict to these stores, or {@code null} for every store in the tenant — never a
   *     store the caller cannot act in, which {@link com.storeql.web.TenantContext#reportStores}
   *     has already checked
   * @param from inclusive lower bound, or null for all time
   * @param to exclusive upper bound, or null for all time
   */
  public List<TenderMixRow> tenderMix(UUID tenantId, Set<UUID> stores, Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to))
      throw ApiException.badRequest(
          "PAYMENT_INVALID_PERIOD", "from must be before to — got " + from + " and " + to);

    List<TenderMixRow> raw = repo.tenderMix(tenantId, stores, from, to);
    BigDecimal total =
        raw.stream()
            .map(r -> r.capturedAmount().subtract(r.refundedAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return raw.stream().map(r -> withNetAndShare(r, total)).toList();
  }

  /**
   * Share is null rather than zero when there is no positive total to take a share of. A window in
   * which more was refunded than captured is a real thing — the day after a recall, say — and
   * printing "-140%" beside a method, or flattening every share to zero, would both be worse than
   * saying the question does not apply.
   */
  private static TenderMixRow withNetAndShare(TenderMixRow r, BigDecimal total) {
    BigDecimal net = r.capturedAmount().subtract(r.refundedAmount());
    BigDecimal share =
        total.signum() <= 0
            ? null
            : net.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    return new TenderMixRow(
        r.method(),
        r.capturedAmount(),
        r.capturedCount(),
        r.refundedAmount(),
        r.refundedCount(),
        r.failedCount(),
        net,
        share);
  }
}
