package com.storeql.tenant.repo;

import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * What a business was told about its trial (21.13): once per stage, with the notice itself.
 *
 * <p>The claim and the outbox row are one transaction, so the notice is never sent without the
 * record that says it was, nor recorded without being queued; a second run of the same day finds
 * the stage taken and writes nothing.
 */
@ApplicationScoped
public class TrialNoticeRepository extends BaseOutboxRepository {

  private static final String CLAIM =
      "INSERT INTO trial_notices (id, tenant_id, subscription_id, stage, created_at)"
          + " VALUES (?,?,?,?,?) ON CONFLICT (subscription_id, stage) DO NOTHING";

  /**
   * Records a stage of a subscription's trial as announced, and queues the announcement.
   *
   * @return false when this stage was already announced, in which case nothing is written
   */
  public boolean claim(
      UUID id, UUID tenantId, UUID subscriptionId, String stage, OutboxRow notice) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(CLAIM)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, subscriptionId);
            ps.setString(4, stage);
            ps.setObject(5, Instant.now().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() != 1) return false;
          }
          insertOutbox(c, notice);
          return true;
        },
        "claim trial notice");
  }
}
