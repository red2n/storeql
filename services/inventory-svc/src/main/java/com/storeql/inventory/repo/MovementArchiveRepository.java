package com.storeql.inventory.repo;

import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stock movement archival (Tier-1 Gap #30). Extracted from {@code InventoryRepository}:
 * self-contained.
 *
 * <p>Golden rule #8: {@code stock_movements} stays append-only. "Purge" relocates matching rows
 * into {@code stock_movements_archive} instead of destroying them — the hot table shrinks, history
 * is never lost. The move is made in chunks: each chunk's delete and archive insert are one
 * statement on one transaction, so a chunk is moved whole or not at all, and a purge interrupted
 * between chunks is simply run again.
 */
@ApplicationScoped
public class MovementArchiveRepository extends BaseJdbcRepository {

  /** Movements moved per statement and transaction. */
  @Inject
  @ConfigProperty(name = "storeql.inventory.archive.chunk-rows", defaultValue = "10000")
  int chunkRows;

  /**
   * Moves movements older than a cutoff into the archive table, removing the originals, a chunk at
   * a time.
   *
   * <p>Each chunk is archived and deleted atomically, so the append-only trail is never simply
   * thrown away: it moves. The whole call is not one transaction — an interruption leaves the
   * earlier chunks archived. The caller enforces the minimum age.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param before purge movements recorded strictly before this instant
   * @return how many movements were archived and removed, moved in chunks of {@code
   *     storeql.inventory.archive.chunk-rows}
   */
  public int purgeMovementsBefore(UUID tenantId, Instant before) {
    OffsetDateTime cutoff = OffsetDateTime.ofInstant(before, ZoneOffset.UTC);
    int chunk = Math.max(1, chunkRows);
    int total = 0;
    while (true) {
      int moved = archiveChunk(tenantId, cutoff, chunk);
      total += moved;
      if (moved < chunk) {
        return total;
      }
    }
  }

  /**
   * Moves up to {@code chunk} of the oldest qualifying movements: the delete and the archive insert
   * are one statement, one short transaction, so a tenant's whole history is never held in one
   * statement and an interrupted purge can simply be run again.
   */
  private int archiveChunk(UUID tenantId, OffsetDateTime cutoff, int chunk) {
    return inTx(
        c -> {
          try (var move =
              c.prepareStatement(
                  "WITH moved AS (DELETE FROM stock_movements WHERE tenant_id=? AND id IN"
                      + " (SELECT id FROM stock_movements WHERE tenant_id=? AND created_at < ?"
                      + " ORDER BY created_at, id LIMIT ?)"
                      + " RETURNING id, tenant_id, store_id, variant_id, batch_id, type, qty,"
                      + " ref_type, ref_id, reason_code, actor_id, created_at)"
                      + " INSERT INTO stock_movements_archive"
                      + " (id, tenant_id, store_id, variant_id, batch_id, type, qty,"
                      + " ref_type, ref_id, reason_code, actor_id, created_at)"
                      + " SELECT id, tenant_id, store_id, variant_id, batch_id, type, qty,"
                      + " ref_type, ref_id, reason_code, actor_id, created_at FROM moved")) {
            move.setObject(1, tenantId);
            move.setObject(2, tenantId);
            move.setObject(3, cutoff);
            move.setInt(4, chunk);
            return move.executeUpdate();
          }
        },
        "archive movements");
  }
}
