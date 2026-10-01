package com.storeql.notification.repo;

import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.service.Retention;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The retention purge of the notification log (21.16), and this service's outbox, which exists for
 * its announcement. Every query filters tenant_id first.
 */
@ApplicationScoped
public class RetentionRunRepository extends BaseOutboxRepository {

  @Inject
  @ConfigProperty(name = "storeql.retention.batch", defaultValue = "5000")
  int batchSize;

  private int size() {
    return Math.max(1, batchSize);
  }

  /**
   * Deletes the log rows older than the cutoff, except a held customer's, and announces the run.
   * The rows go in keyset-paged batches, each on a transaction of its own (so no run locks or loads
   * a whole backlog); the run's event is written in a last transaction, from the sums. A run that
   * dies half way has deleted what its finished batches deleted and announced nothing; the next
   * daily run finishes the rest.
   *
   * @param classHeld whether a hold stops the whole class; then nothing is deleted
   */
  public Retention.Counts purgeLog(
      UUID tenantId,
      Instant cutoff,
      boolean classHeld,
      Set<UUID> heldCustomers,
      Function<Retention.Counts, OutboxRow> runEvent) {
    int rows = 0;
    int held = 0;
    UUID last = null;
    while (true) {
      final UUID after = last;
      Batch batch = purgeBatch(tenantId, cutoff, classHeld, heldCustomers, after);
      rows += batch.deleted();
      held += batch.held();
      if (batch.seen() < size()) break;
      last = batch.lastId();
    }
    Retention.Counts counts = new Retention.Counts(rows, held);
    inTx(
        c -> {
          insertOutbox(c, runEvent.apply(counts));
          return null;
        },
        "announce notification log purge");
    return counts;
  }

  private record Batch(int seen, int deleted, int held, UUID lastId) {}

  private Batch purgeBatch(
      UUID tenantId, Instant cutoff, boolean classHeld, Set<UUID> heldCustomers, UUID after) {
    return inTx(
        c -> {
          List<UUID> due = new ArrayList<>();
          int held = 0;
          int seen = 0;
          UUID lastId = null;
          String sql =
              "SELECT n.id, n.subject_id FROM notification_log n WHERE n.tenant_id = ?"
                  + " AND n.created_at < ?"
                  + (after == null ? "" : " AND n.id > ?")
                  + " ORDER BY n.id LIMIT ? FOR UPDATE";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, tenantId);
            ps.setObject(i++, cutoff.atOffset(ZoneOffset.UTC));
            if (after != null) ps.setObject(i++, after);
            ps.setInt(i, size());
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                seen++;
                lastId = rs.getObject("id", UUID.class);
                UUID subject = rs.getObject("subject_id", UUID.class);
                if (classHeld || (subject != null && heldCustomers.contains(subject))) {
                  held++;
                } else {
                  due.add(lastId);
                }
              }
            }
          }
          int rows = 0;
          if (!due.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "DELETE FROM notification_log WHERE tenant_id = ? AND id = ANY (?)")) {
              ps.setObject(1, tenantId);
              ps.setArray(2, c.createArrayOf("uuid", due.toArray()));
              rows = ps.executeUpdate();
            }
          }
          return new Batch(seen, rows, held, lastId);
        },
        "purge notification log batch");
  }

  /** Every business with a message in the log: the tenants a retention sweep visits. */
  public List<UUID> tenantsWithLog() {
    return query(
        "SELECT DISTINCT n.tenant_id FROM notification_log n WHERE n.tenant_id IS NOT NULL"
            + " ORDER BY n.tenant_id",
        ps -> {},
        rs -> rs.getObject("tenant_id", UUID.class),
        "tenants with notifications");
  }
}
