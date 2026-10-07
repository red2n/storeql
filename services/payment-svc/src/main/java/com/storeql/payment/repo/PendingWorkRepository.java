package com.storeql.payment.repo;

import com.storeql.payment.domain.CardSettlement;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.PendingWorkCount;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * How many things wait for a person: one statement, led by {@code tenant_id}, answered by the
 * partial index {@code idx_card_refund_dues_tenant_state}, which holds only the dues in that state.
 *
 * <p>The state is written into the statement as a literal rather than bound, because a partial
 * index is used only when the planner can see its predicate in the statement itself.
 *
 * <p>The system-health screen asks every few seconds, so the count reads at most {@link
 * PendingWorkCount#CAP} index entries and stops: the cost of a poll does not grow with the queue. A
 * count equal to the cap means "that many or more".
 */
@ApplicationScoped
public class PendingWorkRepository extends BaseJdbcRepository {

  /**
   * Dues a card machine could not put back, waiting for a manager, counted up to a limit; the
   * parameters are {@code tenant_id} and then the limit. Public so a test can ask the database how
   * it would run it.
   */
  public static final String CARD_REFUND_DUES_NEEDING_ATTENTION =
      "SELECT count(*) AS n FROM (SELECT 1 FROM card_refund_dues WHERE tenant_id = ? AND state = '"
          + CardSettlement.NEEDS_ATTENTION
          + "' LIMIT ?) AS waiting";

  /**
   * The dues waiting for a manager in a business, up to {@link PendingWorkCount#CAP}.
   *
   * @param tenantId the business
   * @return how many wait, never more than the cap; the cap itself means "that many or more"
   */
  public long cardRefundDuesNeedingAttention(UUID tenantId) {
    return query(
            CARD_REFUND_DUES_NEEDING_ATTENTION,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setInt(2, PendingWorkCount.CAP);
            },
            rs -> rs.getLong("n"),
            "count card refund dues needing attention")
        .get(0);
  }
}
