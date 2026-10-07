package com.storeql.customer.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.PendingWorkCount;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

/**
 * How many things wait for a person: one statement, led by {@code tenant_id}, answered from the
 * queue index {@code idx_privacy_requests_queue}, whose first two keys are the tenant and the
 * status. The count is bounded: the system-health screen polls it, so it reads at most {@link
 * PendingWorkCount#CAP} rows of a queue and stops, whatever the queue's length.
 */
@ApplicationScoped
public class PendingWorkRepository extends BaseJdbcRepository {

  /**
   * Privacy requests opened and not yet answered, counted up to the cap: the inner query stops at
   * its {@code LIMIT}, so the index is read no further than that. Binds the business, then the cap.
   * The status is a literal so the planner can match it to the index. The {@code ORDER BY due_on}
   * is the index's next key: without it the planner may take a bitmap scan, which reads every
   * matching index entry before the limit applies, and the count would cost as much as the queue is
   * long. Public so the plan test plans this very text.
   */
  public static final String PRIVACY_REQUESTS_OPEN_SQL =
      "SELECT count(*) AS n FROM (SELECT 1 FROM privacy_requests"
          + " WHERE tenant_id = ? AND status = 'OPEN' ORDER BY due_on LIMIT ?) AS waiting";

  /**
   * Privacy requests opened and not yet answered.
   *
   * @return how many, and {@link PendingWorkCount#CAP} when that many or more
   */
  public long privacyRequestsOpen(UUID tenantId) {
    return query(
            PRIVACY_REQUESTS_OPEN_SQL,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setInt(2, PendingWorkCount.CAP);
            },
            rs -> rs.getLong("n"),
            "count open privacy requests")
        .get(0);
  }
}
