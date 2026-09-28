package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.BondRelease;
import com.storeql.inventory.domain.Domain.CycleCountLine;
import com.storeql.inventory.domain.Domain.Level;
import com.storeql.inventory.domain.Domain.LevelSummary;
import com.storeql.inventory.domain.Domain.MoveOrder;
import com.storeql.inventory.domain.Domain.MoveOrderLine;
import com.storeql.inventory.domain.Domain.MoveType;
import com.storeql.inventory.domain.Domain.MovementAttribution;
import com.storeql.inventory.domain.Domain.PickingRule;
import com.storeql.inventory.domain.Domain.PickingRuleZonePriority;
import com.storeql.inventory.domain.Domain.Reservation;
import com.storeql.inventory.domain.Domain.TransferOrder;
import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.inventory.domain.Provenance;
import com.storeql.inventory.domain.Provenance.Drawn;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stock persistence (JDBC). All mutations are transactional and append a {@code stock_movements}
 * row (append-only ledger). FIFO deduction locks batch rows with {@code FOR UPDATE}. Every query
 * filters tenant_id first.
 */
@ApplicationScoped
public class InventoryRepository extends BaseOutboxRepository {

  // ---------------------------------------------------------------- receive
  /**
   * Create a batch + RECEIVE movement + outbox event, atomically. {@code idempotencyKey} may be
   * null (event-driven receives dedupe via {@link #receiveOnce} instead); when present, a retried
   * call with the same key throws {@code BATCH_DUPLICATE_KEY} (409) instead of double-counting
   * stock — the caller looks the original batch up via {@link #findBatchByIdempotencyKey}.
   */
  public Batch receive(
      Batch batch, String refType, UUID refId, OutboxRow event, String idempotencyKey) {
    return inTx(
        c -> {
          String recallHold;
          try {
            recallHold = insertBatch(c, batch, idempotencyKey);
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409, "BATCH_DUPLICATE_KEY", "duplicate idempotency key", List.of(), sqle);
            throw sqle;
          }
          insertMovement(
              c,
              batch.tenantId(),
              batch.storeId(),
              batch.variantId(),
              batch.id(),
              MoveType.RECEIVE,
              batch.receivedQty(),
              refType,
              refId,
              MovementAttribution.system());
          insertOutbox(c, event);
          return recallHold == null ? batch : recalledCopy(batch, recallHold);
        },
        "receive stock");
  }

  /** Look up a previously-received batch by its idempotency key — used to replay a retry. */
  public Optional<Batch> findBatchByIdempotencyKey(UUID tenantId, String idempotencyKey) {
    return query(
            "SELECT id, tenant_id, store_id, variant_id, batch_no, received_qty, remaining_qty,"
                + " cost_price, expiry_date, created_at, status, material_status,"
                + " material_status_reason, grade, zone_id, ownership, owner_supplier_id, duty_status"
                + " FROM inventory_batches WHERE tenant_id=? AND idempotency_key=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            InventoryRepository::mapBatch,
            "find batch by idempotency key")
        .stream()
        .findFirst();
  }

  /**
   * {@link #receive} deduped on {@code dedupeId}: the processed_events mark and the batch creation
   * commit in ONE transaction, so a redelivered event is skipped and a crashed write is retried —
   * never applied twice and never lost. Used by event consumers (a new random batch id per attempt
   * makes plain {@link #receive} non-idempotent under redelivery). Returns false if already
   * processed.
   */
  public boolean receiveOnce(
      UUID dedupeId,
      String consumerName,
      Batch batch,
      String refType,
      UUID refId,
      OutboxRow event) {
    return receiveOnce(dedupeId, consumerName, batch, refType, refId, event, false);
  }

  private boolean receiveOnce(
      UUID dedupeId,
      String consumerName,
      Batch batch,
      String refType,
      UUID refId,
      OutboxRow event,
      boolean reverseRevenue) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          if (reverseRevenue) {
            reverseRevenueTx(
                c,
                batch.tenantId(),
                batch.storeId(),
                batch.variantId(),
                refId,
                batch.receivedQty());
          }
          insertBatch(c, batch);
          insertMovement(
              c,
              batch.tenantId(),
              batch.storeId(),
              batch.variantId(),
              batch.id(),
              MoveType.RECEIVE,
              batch.receivedQty(),
              refType,
              refId,
              MovementAttribution.system());
          insertOutbox(c, event);
          return true;
        },
        "receive stock (deduped)");
  }

  // ---------------------------------------------------------------- adjust
  /**
   * Adjust on-hand by a signed delta against a chosen batch (or create an adjustment batch if
   * none).
   */
  public void adjust(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      OutboxRow event,
      MovementAttribution attribution) {
    adjust(tenantId, storeId, variantId, delta, reason, event, null, attribution);
  }

  /**
   * As {@link #adjust(UUID, UUID, UUID, BigDecimal, String, OutboxRow)}, but a retried call with
   * the same {@code idempotencyKey} is a no-op instead of double-applying the delta — checked
   * before the deduction is attempted, since a negative delta's FIFO deduction could otherwise fail
   * with INSUFFICIENT_STOCK on retry (the original call already consumed that stock).
   */
  public void adjust(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      OutboxRow event,
      String idempotencyKey,
      MovementAttribution attribution) {
    inTx(
        c -> {
          if (idempotencyKey != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO inventory_adjustment_events (tenant_id, idempotency_key)"
                        + " VALUES (?,?)")) {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
              ps.executeUpdate();
            } catch (SQLException sqle) {
              if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
                return null; // already applied — retry, no-op
              }
              throw sqle;
            }
          }
          adjustTx(c, tenantId, storeId, variantId, delta, reason, event, attribution);
          return null;
        },
        "adjust stock");
  }

  // `reason` is accepted from the API down to here but not yet persisted: stock_movements has no
  // free-text column for it, and ref_type below is a fixed small tag set ("ORDER", "LOT_MERGE_IN",
  // ...), not a place to put arbitrary caller-supplied text. Kept as a parameter (rather than
  // dropped from the call chain) so a future migration adding a notes column has it ready to wire
  // up instead of re-threading it back through every caller.
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private void adjustTx(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      OutboxRow event,
      MovementAttribution attribution)
      throws SQLException {
    if (delta.signum() >= 0) {
      Batch b =
          new Batch(
              Ids.newId(),
              tenantId,
              storeId,
              variantId,
              "ADJ",
              delta,
              delta,
              null,
              null,
              Instant.now(),
              Batch.STATUS_ACTIVE,
              Batch.MATERIAL_AVAILABLE,
              null,
              null,
              null);
      insertBatch(c, b);
      // A positive adjustment creates a batch but no deduction, so this is the only movement it
      // writes. Negative adjustments are recorded below instead, per batch.
      insertMovement(
          c,
          tenantId,
          storeId,
          variantId,
          null,
          MoveType.ADJUST,
          delta,
          "ADJUSTMENT",
          null,
          attribution);
    } else {
      // deductFifo writes one ADJUST movement per batch it draws down, each carrying its batch_id.
      // A summary movement on top of those used to be written unconditionally, which double-counted
      // every negative adjustment in the ledger: a write-off of 30 units appeared as 60 across two
      // rows. Stock levels were unaffected (only one deduction ever happened), but every
      // movement-based read was wrong -- the movements list, movement-stats, and now the shrinkage
      // report. The per-batch rows are also strictly more informative, since they tie the write-off
      // to the batches it actually came out of.
      deductFifo(
          c,
          tenantId,
          storeId,
          variantId,
          delta.negate(),
          MoveType.ADJUST,
          "ADJUSTMENT",
          null,
          attribution);
      checkThresholdTx(c, tenantId, storeId, variantId);
    }
    insertOutbox(c, event);
  }

  /**
   * Lot merge: deduct {@code qty} from the source batch's (store, variant) and add it to the
   * target's, in one transaction. The two legs used to be separate {@link #adjust} calls in
   * separate transactions — a crash between them could silently lose stock with no compensating
   * event. Doing both within a single {@code inTx} makes the merge all-or-nothing.
   */
  public void mergeLotAdjust(
      UUID tenantId,
      UUID sourceStoreId,
      UUID sourceVariantId,
      OutboxRow outEvent,
      UUID targetStoreId,
      UUID targetVariantId,
      OutboxRow inEvent,
      BigDecimal qty,
      MovementAttribution attribution) {
    inTx(
        c -> {
          adjustTx(
              c,
              tenantId,
              sourceStoreId,
              sourceVariantId,
              qty.negate(),
              "LOT_MERGE_OUT",
              outEvent,
              attribution);
          adjustTx(
              c,
              tenantId,
              targetStoreId,
              targetVariantId,
              qty,
              "LOT_MERGE_IN",
              inEvent,
              attribution);
          return null;
        },
        "merge lot");
  }

  // ---------------------------------------------------------------- reserve
  /**
   * Hold stock if available. Inserts a HELD reservation + RESERVE movement + outbox. Throws 409 if
   * short. If {@code idempotencyKey} matches an already-held reservation, that reservation is
   * returned unchanged (replay) — checked *before* the availability check, since the original
   * hold's own qty is already counted against availability and would otherwise make a retry of a
   * fully-successful reservation look like it's short on stock.
   */
  public Reservation reserve(Reservation r, OutboxRow event, String idempotencyKey) {
    return inTx(c -> reserveTx(c, r, event, idempotencyKey), "reserve stock");
  }

  /** One item of a {@link #reserveBatch} call. */
  public record ReserveBatchItem(Reservation reservation, OutboxRow event, String idempotencyKey) {}

  /** Per-item result of {@link #reserveBatch}: exactly one of the two fields is set. */
  public record ReserveOutcome(Reservation reservation, RuntimeException error) {
    static ReserveOutcome success(Reservation r) {
      return new ReserveOutcome(r, null);
    }

    static ReserveOutcome failure(RuntimeException e) {
      return new ReserveOutcome(null, e);
    }

    /**
     * Whether this line's hold was placed.
     *
     * @return {@code true} when a reservation came back, {@code false} when it carries the error
     *     that stopped it
     */
    public boolean succeeded() {
      return error == null;
    }
  }

  /**
   * Batch form of {@link #reserve}: holds stock for every item within ONE transaction instead of
   * one {@code BEGIN}/{@code COMMIT} (and connection-pool checkout) per item — {@code bulkReserve}
   * used to loop calling {@link #reserve} once per line, fanning a single "bulk" request out into N
   * separate round trips. A {@code SAVEPOINT} per item preserves the original partial-success
   * behavior: a short/failing line rolls back only its own work, leaving earlier and later items in
   * the batch unaffected.
   */
  public List<ReserveOutcome> reserveBatch(List<ReserveBatchItem> items) {
    return inTx(
        c -> {
          List<ReserveOutcome> outcomes = new ArrayList<>(items.size());
          for (ReserveBatchItem item : items) {
            Savepoint sp = c.setSavepoint();
            try {
              Reservation r = reserveTx(c, item.reservation(), item.event(), item.idempotencyKey());
              outcomes.add(ReserveOutcome.success(r));
            } catch (ApiException e) {
              c.rollback(sp);
              outcomes.add(ReserveOutcome.failure(e));
            } catch (SQLException e) {
              c.rollback(sp);
              outcomes.add(
                  ReserveOutcome.failure(handleTxSqlException("reserve stock (batch item)", e)));
            }
          }
          return outcomes;
        },
        "bulk reserve stock");
  }

  private Reservation reserveTx(Connection c, Reservation r, OutboxRow event, String idempotencyKey)
      throws SQLException {
    if (idempotencyKey != null) {
      Reservation existing = findReservationByIdempotencyKeyTx(c, r.tenantId(), idempotencyKey);
      if (existing != null) {
        return existing;
      }
    }
    // Dropship: the supplier fulfils this per order, so there is no shelf to check and nothing to
    // hold — the reservation exists so the checkout runs as it always has, and says what it is.
    if (isDropshipTx(c, r.tenantId(), r.variantId())) {
      Reservation drop = r.asDropship();
      try {
        insertReservation(c, drop, idempotencyKey);
      } catch (SQLException sqle) {
        if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
          throw new ApiException(
              409, "RESERVATION_DUPLICATE_KEY", "duplicate idempotency key", List.of(), sqle);
        throw sqle;
      }
      insertOutbox(c, event);
      return drop;
    }
    BigDecimal available = availableForUpdate(c, r.tenantId(), r.storeId(), r.variantId());
    if (available.compareTo(r.qty()) < 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_STOCK",
          "Only " + available.toPlainString() + " available, requested " + r.qty().toPlainString());
    }
    try {
      insertReservation(c, r, idempotencyKey);
    } catch (SQLException sqle) {
      if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
        throw new ApiException(
            409, "RESERVATION_DUPLICATE_KEY", "duplicate idempotency key", List.of(), sqle);
      throw sqle;
    }
    insertMovement(
        c,
        r.tenantId(),
        r.storeId(),
        r.variantId(),
        null,
        MoveType.RESERVE,
        r.qty().negate(),
        "RESERVATION",
        r.id(),
        MovementAttribution.system());
    insertOutbox(c, event);
    return r;
  }

  // ---------------------------------------------------------------- consume (FIFO deduct)
  /**
   * Consume a HELD reservation: FIFO-deduct from batches, mark CONSUMED, SALE movements + outbox.
   */
  public void consume(UUID tenantId, UUID reservationId) {
    inTx(
        c -> {
          consumeTx(c, tenantId, reservationId);
          return null;
        },
        "consume reservation");
  }

  /**
   * {@link #consume} deduped on {@code dedupeId}: mark + consume commit in ONE transaction (see
   * {@link #receiveOnce}). Returns false if already processed. Used by the OrderFulfilled consumer
   * so a redelivered event can't double-deduct a line whose reservation was already consumed.
   *
   * @param netAmount the line's revenue net of VAT and discounts, recorded beside the draw-down in
   *     the same transaction (19.7); null when the sale carried none
   */
  public boolean consumeOnce(
      UUID dedupeId, String consumerName, UUID tenantId, UUID reservationId, BigDecimal netAmount) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          Reservation r = consumeTx(c, tenantId, reservationId);
          if (netAmount != null) {
            insertSaleRevenueTx(
                c,
                tenantId,
                r.storeId(),
                r.variantId(),
                r.orderId(),
                r.qty(),
                netAmount,
                null,
                "SALE");
          }
          return true;
        },
        "consume reservation (deduped)");
  }

  private Reservation consumeTx(Connection c, UUID tenantId, UUID reservationId)
      throws SQLException {
    Reservation r = loadReservationForUpdate(c, tenantId, reservationId);
    if (!Reservation.HELD.equals(r.status())) {
      throw ApiException.unprocessable("RESERVATION_NOT_HELD", "Reservation is " + r.status());
    }
    if (r.dropship()) {
      // Nothing was held on the shelf and nothing leaves it: the supplier ships to the customer.
      setReservationStatus(c, reservationId, Reservation.CONSUMED);
      return r;
    }
    Optional<PickingRule> rule = resolvePickingRule(tenantId, r.storeId(), r.variantId());
    List<UUID> zonePriorities =
        rule.filter(rr -> PickingRule.ZONE_PRIORITY.equals(rr.strategy()))
            .map(
                rr ->
                    listZonePriorities(tenantId, rr.id()).stream()
                        .map(PickingRuleZonePriority::zoneId)
                        .toList())
            .orElse(null);
    deductBatches(
        c,
        tenantId,
        r.storeId(),
        r.variantId(),
        r.qty(),
        MoveType.SALE,
        "ORDER",
        r.orderId(),
        rule.map(PickingRule::strategy).orElse(null),
        rule.map(PickingRule::gradePreference).orElse(null),
        zonePriorities,
        MovementAttribution.system());
    checkThresholdTx(c, tenantId, r.storeId(), r.variantId());
    setReservationStatus(c, reservationId, Reservation.CONSUMED);
    // Event built here (not in service layer) because storeId/variantId/qty are only
    // known after loading the reservation inside this transaction.
    insertOutbox(
        c,
        new OutboxRow(
            "StockDeducted",
            "storeql.inventory.stock-deducted",
            tenantId,
            reservationId,
            com.storeql.inventory.service.Events.stockDeducted(
                tenantId, r.storeId(), r.variantId(), reservationId, r.qty())));
    return r;
  }

  // ---------------------------------------------------------------- deductSale (Gap #50 POS→SIM)
  /**
   * FIFO-deduct for a POS sale driven by an OrderFulfilled event (no prior reservation). Creates
   * SALE movements, checks thresholds, and publishes the StockDeducted outbox event — all in one
   * transaction.
   */
  public void deductSale(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId, OutboxRow event) {
    inTx(
        c -> {
          if (isDropshipTx(c, tenantId, variantId)) return null;
          deductFifo(
              c,
              tenantId,
              storeId,
              variantId,
              qty,
              MoveType.SALE,
              "ORDER",
              orderId,
              MovementAttribution.system());
          checkThresholdTx(c, tenantId, storeId, variantId);
          insertOutbox(c, event);
          return null;
        },
        "deduct sale from order");
  }

  /**
   * {@link #deductSale} deduped on {@code dedupeId}: mark + FIFO deduction commit in ONE
   * transaction (see {@link #receiveOnce}). Returns false if already processed.
   */
  /**
   * Deducts goods going back to the supplier (07.8), once per event line: FIFO from the store's
   * available batches, a movement of type RTV against the return, and the adjustment event that
   * tells the tills.
   *
   * @return whether the line was applied now; false when it already was
   */
  public boolean deductReturnToVendorOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID returnId,
      OutboxRow event) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          deductFifo(
              c,
              tenantId,
              storeId,
              variantId,
              qty,
              MoveType.RTV,
              "RTV",
              returnId,
              MovementAttribution.system());
          checkThresholdTx(c, tenantId, storeId, variantId);
          insertOutbox(c, event);
          return true;
        },
        "deduct return to vendor (deduped)");
  }

  /**
   * FIFO-deducts a fulfilled order line, recording what the line earned beside the stock it drew
   * (19.7), in the same transaction and under the same dedupe mark.
   *
   * @param netAmount the line's revenue net of VAT and discounts, or null when the sale carried
   *     none
   */
  public boolean deductSaleOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      BigDecimal netAmount,
      OutboxRow event) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          if (isDropshipTx(c, tenantId, variantId)) {
            // Stock the business never held: the sale earned its revenue, drew no batch.
            if (netAmount != null) {
              insertSaleRevenueTx(
                  c, tenantId, storeId, variantId, orderId, qty, netAmount, null, "SALE");
            }
            return true;
          }
          deductFifo(
              c,
              tenantId,
              storeId,
              variantId,
              qty,
              MoveType.SALE,
              "ORDER",
              orderId,
              MovementAttribution.system());
          checkThresholdTx(c, tenantId, storeId, variantId);
          if (netAmount != null) {
            insertSaleRevenueTx(
                c, tenantId, storeId, variantId, orderId, qty, netAmount, null, "SALE");
          }
          insertOutbox(c, event);
          return true;
        },
        "deduct sale from order (deduped)");
  }

  // ---------------------------------------------------------------- bond release
  /**
   * Releases duty-suspended stock to home use: draws the bonded batches FIFO in a BOND_RELEASE
   * movement, makes a duty-paid batch of each draw at the same store (same lot, cost and date,
   * linked in the genealogy), records the release and announces the duty it owes — on one
   * transaction.
   *
   * @throws ApiException 422 {@code INVENTORY_INSUFFICIENT_BONDED_STOCK} when less than {@code qty}
   *     sits in bond
   */
  public BondRelease releaseFromBond(BondRelease r, OutboxRow event) {
    return inTx(
        c -> {
          List<Drawn> drawn;
          try {
            drawn =
                deductBatches(
                    c,
                    r.tenantId(),
                    r.storeId(),
                    r.variantId(),
                    r.qty(),
                    MoveType.BOND_RELEASE,
                    "BOND_RELEASE",
                    r.id(),
                    null,
                    null,
                    null,
                    MovementAttribution.system());
          } catch (ApiException e) {
            if ("INSUFFICIENT_STOCK".equals(e.code())) {
              throw new ApiException(
                  422,
                  "INVENTORY_INSUFFICIENT_BONDED_STOCK",
                  "less than "
                      + r.qty().toPlainString()
                      + " of the variant sits in bond at the store",
                  List.of(),
                  e);
            }
            throw e;
          }
          for (Drawn d : drawn) {
            Batch paid = Provenance.released(r.tenantId(), r.storeId(), r.variantId(), d);
            insertBatch(c, paid);
            insertMovement(
                c,
                r.tenantId(),
                r.storeId(),
                r.variantId(),
                paid.id(),
                MoveType.BOND_RELEASE,
                d.qty(),
                "BOND_RELEASE",
                r.id(),
                MovementAttribution.system());
            insertGenealogy(
                c, r.tenantId(), d.batchId(), paid.id(), d.qty(), "BOND_RELEASE " + r.id());
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO bond_releases (id, tenant_id, store_id, variant_id, qty,"
                      + " duty_per_unit, duty_amount, currency, reference, released_by, released_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.storeId());
            ps.setObject(4, r.variantId());
            ps.setBigDecimal(5, r.qty());
            ps.setBigDecimal(6, r.dutyPerUnit());
            ps.setBigDecimal(7, r.dutyAmount());
            ps.setString(8, r.currency());
            ps.setString(9, r.reference());
            ps.setObject(10, r.releasedBy());
            ps.setObject(11, r.releasedAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return r;
        },
        "release from bond");
  }

  // ---------------------------------------------------------------- dropship sourcing
  /**
   * Records purchase-svc's word on how a variant is fulfilled, once per event: the latest word
   * wins. A variant sourced from a supplier per order is available with nothing on the shelf.
   */
  public boolean upsertSourcingOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID variantId,
      String fulfilment,
      UUID supplierId) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) return false;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO variant_sourcing (tenant_id, variant_id, fulfilment, supplier_id,"
                      + " updated_at) VALUES (?, ?, ?, ?, now())"
                      + " ON CONFLICT (tenant_id, variant_id) DO UPDATE SET"
                      + " fulfilment = EXCLUDED.fulfilment, supplier_id = EXCLUDED.supplier_id,"
                      + " updated_at = now()")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, variantId);
            ps.setString(3, fulfilment);
            ps.setObject(4, supplierId);
            ps.executeUpdate();
          }
          return true;
        },
        "record variant sourcing");
  }

  /** The variants a supplier fulfils per order for this tenant. */
  public List<UUID> dropshipVariants(UUID tenantId) {
    return query(
        "SELECT variant_id FROM variant_sourcing WHERE tenant_id = ? AND fulfilment = 'DROPSHIP'"
            + " ORDER BY variant_id",
        ps -> ps.setObject(1, tenantId),
        rs -> rs.getObject("variant_id", UUID.class),
        "list dropship variants");
  }

  private static boolean isDropshipTx(Connection c, UUID tenantId, UUID variantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM variant_sourcing WHERE tenant_id = ? AND variant_id = ?"
                + " AND fulfilment = 'DROPSHIP'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  // ---------------------------------------------------------------- release
  /**
   * Release a HELD reservation (returns the held qty to availability): RELEASE movement + outbox.
   */
  public boolean release(UUID tenantId, UUID reservationId, OutboxRow event) {
    return inTx(
        c -> {
          Reservation r = loadReservationForUpdate(c, tenantId, reservationId);
          if (!Reservation.HELD.equals(r.status())) {
            return false; // already consumed/released — idempotent no-op
          }
          insertMovement(
              c,
              tenantId,
              r.storeId(),
              r.variantId(),
              null,
              MoveType.RELEASE,
              r.qty(),
              "RESERVATION",
              reservationId,
              MovementAttribution.system());
          setReservationStatus(c, reservationId, Reservation.RELEASED);
          insertOutbox(c, event);
          return true;
        },
        "release reservation");
  }

  /**
   * A line of an online order closed short or replaced by a substitute (substitutions for
   * out-of-stock online lines): {@code qty} of the order's hold on the variant goes back to the
   * shelf — the hold shrinks, or is released when nothing of it is left — as a RELEASE movement per
   * hold, and the order's waiting line needs that much less; all on one transaction, once per
   * event. A substitute's own draw rides the {@code OrderFulfilled} beside the event.
   *
   * @param released builds the {@code StockReleased} event for a hold released in full
   * @return false when the event was already applied
   */
  public boolean lineClosedOnce(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      BigDecimal qty,
      java.util.function.Function<UUID, OutboxRow> released) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return false;
          }
          List<Reservation> holds = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                      + " fulfilment, created_at FROM reservations WHERE tenant_id = ? AND order_id"
                      + " = ? AND variant_id = ? AND status = 'HELD' ORDER BY created_at"
                      + " FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            ps.setObject(3, variantId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) holds.add(mapReservation(rs));
            }
          }
          BigDecimal left = qty;
          for (Reservation r : holds) {
            if (left.signum() <= 0) break;
            BigDecimal take = left.min(r.qty());
            insertMovement(
                c,
                tenantId,
                r.storeId(),
                r.variantId(),
                null,
                MoveType.RELEASE,
                take,
                "RESERVATION",
                r.id(),
                MovementAttribution.system());
            if (take.compareTo(r.qty()) >= 0) {
              setReservationStatus(c, r.id(), Reservation.RELEASED);
              insertOutbox(c, released.apply(r.id()));
            } else {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "UPDATE reservations SET qty = qty - ? WHERE tenant_id = ? AND id = ?")) {
                ps.setBigDecimal(1, take);
                ps.setObject(2, tenantId);
                ps.setObject(3, r.id());
                ps.executeUpdate();
              }
            }
            left = left.subtract(take);
          }
          WaveRepository.reduceAwaitingTx(c, tenantId, orderId, variantId, qty);
          WaveRepository.pruneOrderTx(c, tenantId, orderId);
          return true;
        },
        "order line closed");
  }

  /** Find HELD reservations that have expired (for the sweeper). */
  public List<UUID> expiredHeldReservations(int limit) {
    return query(
        "SELECT id FROM reservations WHERE status = 'HELD'"
            + " AND expires_at IS NOT NULL AND expires_at < now() LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs -> rs.getObject("id", UUID.class),
        "find expired reservations");
  }

  public record ReservationRef(UUID id, UUID tenantId) {}

  /** Fetch expired reservations with their tenant in one query — avoids N+1 in the sweeper. */
  public List<ReservationRef> expiredHeldReservationsWithTenant(int limit) {
    return query(
        "SELECT id, tenant_id FROM reservations WHERE status = 'HELD'"
            + " AND expires_at IS NOT NULL AND expires_at < now() LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs ->
            new ReservationRef(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
        "find expired reservations with tenant");
  }

  /**
   * The tenant a reservation belongs to.
   *
   * <p>Deliberately not tenant-scoped: the sweeper works across tenants and needs to learn which
   * tenant an expired hold belongs to before it can act within that tenant's scope.
   *
   * @param reservationId the reservation to resolve
   * @return the owning tenant id, or {@code null} when no such reservation exists
   */
  public UUID tenantOfReservation(UUID reservationId) {
    var list =
        query(
            "SELECT tenant_id FROM reservations WHERE id = ?",
            ps -> ps.setObject(1, reservationId),
            rs -> rs.getObject("tenant_id", UUID.class),
            "tenant of reservation");
    return list.isEmpty() ? null : list.get(0);
  }

  // ---------------------------------------------------------------- levels

  /**
   * Per-(store,variant) aggregation shared by the paginated list and the summary count: on-hand
   * (sum remaining batches), reserved (sum HELD). Binds two params — {@code tenant_id} for
   * reservations then {@code tenant_id} for batches. Callers append store/cursor filters + GROUP BY
   * / ORDER BY / LIMIT, or wrap it for aggregate counts.
   */
  private static final String LEVELS_CORE =
      """
      SELECT b.store_id, b.variant_id,
             COALESCE(SUM(b.remaining_qty),0) AS on_hand,
             COALESCE(SUM(CASE WHEN b.duty_status = 'DUTY_SUSPENDED' THEN b.remaining_qty ELSE 0 END),0) AS in_bond,
             COALESCE(MAX(res.reserved),0) AS reserved
      FROM inventory_batches b
      LEFT JOIN (
          SELECT store_id, variant_id, SUM(qty) AS reserved
          FROM reservations WHERE tenant_id = ? AND status = 'HELD'
          GROUP BY store_id, variant_id
      ) res ON res.store_id = b.store_id AND res.variant_id = b.variant_id
      WHERE b.tenant_id = ? AND b.material_status = 'AVAILABLE'""";

  /**
   * DB-load safety valve for {@link #levels}: internal callers (min/max planning, storefront
   * availability) legitimately want every SKU in one call, not a client-paginated page, but nothing
   * upstream caps how large a tenant's catalog can grow. This bounds the worst case instead of
   * leaving the query truly unbounded.
   */
  private static final int LEVELS_SAFETY_CAP = 20_000;

  /**
   * On-hand / reserved / available per (store,variant) — every SKU, for internal callers (min/max
   * planning, storefront) that need the full set rather than a client-paginated page. Capped at
   * {@link #LEVELS_SAFETY_CAP} as a DB-load safety valve. Paginated reads use {@link #levelsPage}.
   */
  public List<Level> levels(UUID tenantId, UUID storeId) {
    return levelsPage(tenantId, storeId, null, null, LEVELS_SAFETY_CAP);
  }

  /**
   * What every store holds of the products named: on hand, reserved and available per store, for
   * routing an online order (order orchestration). Bounded as {@link #levels} is.
   */
  public List<Level> levelsForVariants(UUID tenantId, List<UUID> variantIds) {
    return query(
        LEVELS_CORE
            + " AND b.variant_id = ANY(?) GROUP BY b.store_id, b.variant_id ORDER BY b.store_id,"
            + " b.variant_id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, tenantId);
          ps.setArray(3, ps.getConnection().createArrayOf("uuid", variantIds.toArray()));
          ps.setInt(4, LEVELS_SAFETY_CAP);
        },
        InventoryRepository::mapLevel,
        "load levels for variants");
  }

  /**
   * One keyset page of levels, ordered by {@code (store_id, variant_id)} and starting strictly
   * after the {@code (afterStoreId, afterVariantId)} cursor when both are supplied. {@code limit}
   * caps the returned rows so the caller can request {@code limit + 1} to detect a further page.
   */
  public List<Level> levelsPage(
      UUID tenantId, UUID storeId, UUID afterStoreId, UUID afterVariantId, int limit) {
    boolean hasStore = storeId != null;
    boolean hasCursor = afterStoreId != null && afterVariantId != null;
    String sql =
        LEVELS_CORE
            + (hasStore ? " AND b.store_id = ?" : "")
            + (hasCursor ? " AND (b.store_id, b.variant_id) > (?, ?)" : "")
            + " GROUP BY b.store_id, b.variant_id"
            + " ORDER BY b.store_id, b.variant_id"
            + " LIMIT ?";
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, tenantId);
          if (hasStore) {
            ps.setObject(i++, storeId);
          }
          if (hasCursor) {
            ps.setObject(i++, afterStoreId);
            ps.setObject(i++, afterVariantId);
          }
          ps.setInt(i, limit);
        },
        InventoryRepository::mapLevel,
        "load levels");
  }

  /**
   * Aggregate counts over the same per-(store,variant) levels: total distinct SKUs and how many are
   * at or below {@code lowThreshold} available. A single query that never materializes the full
   * list — backs the admin dashboard KPI tiles.
   */
  public LevelSummary levelsSummary(UUID tenantId, UUID storeId, BigDecimal lowThreshold) {
    boolean hasStore = storeId != null;
    String sql =
        "SELECT COUNT(*) AS sku_count,"
            + " COUNT(*) FILTER (WHERE lv.on_hand - lv.reserved <= ?) AS low_count FROM ("
            + LEVELS_CORE
            + (hasStore ? " AND b.store_id = ?" : "")
            + " GROUP BY b.store_id, b.variant_id) lv";
    List<LevelSummary> rows =
        query(
            sql,
            ps -> {
              int i = 1;
              ps.setBigDecimal(i++, lowThreshold);
              ps.setObject(i++, tenantId);
              ps.setObject(i++, tenantId);
              if (hasStore) {
                ps.setObject(i, storeId);
              }
            },
            rs -> new LevelSummary(rs.getLong("sku_count"), rs.getLong("low_count")),
            "load levels summary");
    return rows.isEmpty() ? new LevelSummary(0, 0) : rows.get(0);
  }

  private static Level mapLevel(ResultSet rs) throws SQLException {
    BigDecimal onHand = rs.getBigDecimal("on_hand");
    BigDecimal reserved = rs.getBigDecimal("reserved");
    BigDecimal inBond = rs.getBigDecimal("in_bond");
    return new Level(
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        onHand,
        reserved,
        onHand.subtract(inBond).subtract(reserved),
        inBond);
  }

  // ---------------------------------------------------------------- batches (read)

  /**
   * Lists the tenant's batches.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @param materialStatus the material status
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<Batch> listBatches(
      UUID tenantId, UUID storeId, UUID variantId, String materialStatus, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                + " remaining_qty, cost_price, expiry_date, created_at, status,"
                + " material_status, material_status_reason, grade, zone_id, ownership, owner_supplier_id, duty_status"
                + " FROM inventory_batches WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    if (materialStatus != null) sb.append(" AND material_status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    String sql = sb.toString();
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i, tenantId);
          i++;
          if (storeId != null) {
            ps.setObject(i, storeId);
            i++;
          }
          if (variantId != null) {
            ps.setObject(i, variantId);
            i++;
          }
          if (materialStatus != null) {
            ps.setString(i, materialStatus);
            i++;
          }
          ps.setInt(i, limit);
        },
        InventoryRepository::mapBatch,
        "list batches");
  }

  /**
   * Looks a stock batch up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param batchId the batch to fetch
   * @return the batch, or empty when it does not exist in this tenant
   */
  public Optional<Batch> getBatch(UUID tenantId, UUID batchId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                + " remaining_qty, cost_price, expiry_date, created_at, status,"
                + " material_status, material_status_reason, grade, zone_id, ownership, owner_supplier_id, duty_status"
                + " FROM inventory_batches WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, batchId);
            },
            InventoryRepository::mapBatch,
            "get batch");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  // ---------------------------------------------------------------- reservations (read)

  /**
   * Lists the tenant's reservations.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<Reservation> listReservations(UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at, fulfilment,"
                + " created_at FROM reservations WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    String sql = sb.toString();
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i, tenantId);
          i++;
          if (storeId != null) {
            ps.setObject(i, storeId);
            i++;
          }
          if (status != null) {
            ps.setString(i, status);
            i++;
          }
          ps.setInt(i, limit);
        },
        InventoryRepository::mapReservation,
        "list reservations");
  }

  /**
   * The HELD reservations placed for one order at checkout — consumed at fulfilment, released on
   * cancellation (both driven by order-svc events).
   */
  public List<Reservation> heldReservationsByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at, fulfilment,"
            + " created_at FROM reservations WHERE tenant_id = ? AND order_id = ?"
            + " AND status = 'HELD'",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        InventoryRepository::mapReservation,
        "held reservations by order");
  }

  /**
   * Looks a reservation up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param reservationId the reservation id
   * @return the reservation, or empty when it does not exist in this tenant
   */
  public Optional<Reservation> findReservation(UUID tenantId, UUID reservationId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at, fulfilment,"
                + " created_at FROM reservations WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, reservationId);
            },
            InventoryRepository::mapReservation,
            "get reservation");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** Look up a previously-held reservation by its idempotency key — used to replay a retry. */
  public Optional<Reservation> findReservationByIdempotencyKey(
      UUID tenantId, String idempotencyKey) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at, fulfilment,"
                + " created_at FROM reservations WHERE tenant_id = ? AND idempotency_key = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            InventoryRepository::mapReservation,
            "find reservation by idempotency key");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private static Reservation findReservationByIdempotencyKeyTx(
      Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at, fulfilment,"
                + " created_at FROM reservations WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapReservation(rs) : null;
      }
    }
  }

  // ---------------------------------------------------------------- material status

  /** Change the physical condition of a batch; emits MaterialStatusChanged outbox event. */
  public Batch updateMaterialStatus(
      UUID tenantId, UUID batchId, String materialStatus, String reason, OutboxRow event) {
    return inTx(
        c -> {
          Batch updated;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE inventory_batches"
                      + " SET material_status=?, material_status_reason=?,"
                      + " material_status_changed_at=now()"
                      + " WHERE tenant_id=? AND id=?"
                      + " RETURNING id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                      + " remaining_qty, cost_price, expiry_date, created_at, status,"
                      + " material_status, material_status_reason, grade, zone_id, ownership, owner_supplier_id, duty_status")) {
            ps.setString(1, materialStatus);
            ps.setString(2, reason);
            ps.setObject(3, tenantId);
            ps.setObject(4, batchId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound("BATCH_NOT_FOUND", "No such batch");
              }
              updated = mapBatch(rs);
            }
          }
          insertOutbox(c, event);
          return updated;
        },
        "update material status");
  }

  // ---------------------------------------------------------------- cycle counting (Gap #10)
  // Only applyAdjustments stays here — see CycleCountRepository's class javadoc for why.

  /**
   * Apply stock adjustments for all APPROVED lines and mark them ADJUSTED in one transaction.
   * Returns the number of lines adjusted.
   */
  public int applyAdjustments(UUID tenantId, UUID headerId, OutboxRow event, UUID actorId) {
    MovementAttribution attribution =
        MovementAttribution.by(actorId, MovementAttribution.CYCLE_COUNT_VARIANCE);
    return inTx(
        c -> {
          List<CycleCountLine> approved =
              query(
                  "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
                      + " counted_qty, variance, variance_pct, status, counted_at"
                      + " FROM cycle_count_lines"
                      + " WHERE header_id = ? AND status = 'APPROVED'",
                  ps -> ps.setObject(1, headerId),
                  InventoryRepository::mapCycleCountLine,
                  "list approved lines for adjustment");
          for (CycleCountLine line : approved) {
            if (line.variance() == null || line.variance().signum() == 0) continue;
            if (line.variance().signum() > 0) {
              // positive variance: system under-counted — add stock
              Batch adj =
                  new Batch(
                      Ids.newId(),
                      tenantId,
                      line.storeId(),
                      line.variantId(),
                      "CC-" + Ids.shortRef(headerId),
                      line.variance(),
                      line.variance(),
                      null,
                      null,
                      Instant.now(),
                      Batch.STATUS_ACTIVE,
                      Batch.MATERIAL_AVAILABLE,
                      null,
                      null,
                      null);
              insertBatch(c, adj);
              insertMovement(
                  c,
                  tenantId,
                  line.storeId(),
                  line.variantId(),
                  adj.id(),
                  MoveType.ADJUST,
                  line.variance(),
                  "CYCLE_COUNT",
                  headerId,
                  attribution);
            } else {
              // negative variance: system over-counted — deduct stock
              deductFifo(
                  c,
                  tenantId,
                  line.storeId(),
                  line.variantId(),
                  line.variance().negate(),
                  MoveType.ADJUST,
                  "CYCLE_COUNT",
                  headerId,
                  attribution);
            }
          }
          if (!approved.isEmpty()) {
            List<UUID> approvedIds = approved.stream().map(CycleCountLine::id).toList();
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE cycle_count_lines SET status = 'ADJUSTED'"
                        + " WHERE header_id = ? AND id = ANY(?)")) {
              ps.setObject(1, headerId);
              ps.setArray(2, c.createArrayOf("uuid", approvedIds.toArray()));
              ps.executeUpdate();
            }
          }
          insertOutbox(c, event);
          // Mark header ADJUSTED in the same transaction so a crash cannot leave stock adjusted
          // with an open header (which would allow a second adjustment on re-run).
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE cycle_count_headers SET status='ADJUSTED', completed_at=now()"
                      + " WHERE tenant_id=? AND id=?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, headerId);
            ps.executeUpdate();
          }
          return approved.size();
        },
        "apply cycle count adjustments");
  }

  /** Only {@code applyAdjustments} above needs this — see the section comment. */
  private static CycleCountLine mapCycleCountLine(ResultSet rs) throws SQLException {
    OffsetDateTime countedOdt = rs.getObject("counted_at", OffsetDateTime.class);
    return new CycleCountLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("header_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("system_qty"),
        rs.getBigDecimal("counted_qty"),
        rs.getBigDecimal("variance"),
        rs.getBigDecimal("variance_pct"),
        rs.getString("status"),
        countedOdt == null ? null : countedOdt.toInstant());
  }

  // ---------------------------------------------------------------- internals

  /** Available = sum(AVAILABLE remaining batches) − sum(HELD reservations), rows locked. */
  private BigDecimal availableForUpdate(Connection c, UUID tenantId, UUID storeId, UUID variantId)
      throws SQLException {
    BigDecimal onHand = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT remaining_qty FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=?"
                + " AND material_status='AVAILABLE' AND duty_status='DUTY_PAID' FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) onHand = onHand.add(rs.getBigDecimal("remaining_qty"));
      }
    }
    BigDecimal reserved = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(qty),0) AS q FROM reservations"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND status='HELD'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) reserved = rs.getBigDecimal("q");
      }
    }
    return onHand.subtract(reserved);
  }

  /**
   * Batch deduction: walk batches in strategy-defined order WITH FOR UPDATE, decrement remaining,
   * log movement per batch. Strategy defaults to FEFO when null.
   */
  private List<Drawn> deductFifo(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String moveType,
      String refType,
      UUID refId,
      MovementAttribution attribution)
      throws SQLException {
    return deductBatches(
        c,
        tenantId,
        storeId,
        variantId,
        qty,
        moveType,
        refType,
        refId,
        null,
        null,
        null,
        attribution);
  }

  /**
   * @return what was drawn from which batch, in draw order — what a transfer or a move must carry
   *     to where the stock goes (SJ-D71)
   */
  List<Drawn> deductBatches(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String moveType,
      String refType,
      UUID refId,
      String strategy,
      String gradePreference,
      List<UUID> zonePriorityOrder,
      MovementAttribution attribution)
      throws SQLException {
    return deductBatches(
        c,
        tenantId,
        storeId,
        variantId,
        qty,
        moveType,
        refType,
        refId,
        strategy,
        gradePreference,
        zonePriorityOrder,
        null,
        attribution);
  }

  /** As above, drawing {@code firstBatch} before any other when it is given. */
  List<Drawn> deductBatches(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String moveType,
      String refType,
      UUID refId,
      String strategy,
      String gradePreference,
      List<UUID> zonePriorityOrder,
      UUID firstBatch,
      MovementAttribution attribution)
      throws SQLException {
    String orderBy = pickOrderClause(strategy, gradePreference, zonePriorityOrder);
    BigDecimal toDeduct = qty;
    List<Drawn> batches = new ArrayList<>();
    List<Drawn> drawn = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, remaining_qty, batch_no, expiry_date, cost_price, grade, ownership,"
                + " owner_supplier_id, duty_status"
                + " FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND remaining_qty > 0"
                + " AND material_status='AVAILABLE'"
                + dutyFilter(moveType)
                // The named batch first (a cross-dock line's own); with none named every row
                // compares to null alike and the order is the rule's.
                + " ORDER BY (id = CAST(? AS uuid)) DESC NULLS LAST, "
                + orderBy
                + " FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      ps.setObject(4, firstBatch);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          batches.add(
              new Drawn(
                  rs.getObject("id", UUID.class),
                  rs.getBigDecimal("remaining_qty"),
                  rs.getString("batch_no"),
                  rs.getObject("expiry_date", LocalDate.class),
                  rs.getBigDecimal("cost_price"),
                  rs.getString("grade"),
                  rs.getString("ownership"),
                  rs.getObject("owner_supplier_id", UUID.class),
                  rs.getString("duty_status")));
        }
      }
    }
    for (Drawn row : batches) {
      if (toDeduct.signum() <= 0) break;
      UUID batchId = row.batchId();
      BigDecimal take = row.qty().min(toDeduct);
      drawn.add(row.of(take));
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE inventory_batches SET remaining_qty = remaining_qty - ? WHERE id = ?")) {
        ps.setBigDecimal(1, take);
        ps.setObject(2, batchId);
        ps.executeUpdate();
      }
      insertMovement(
          c,
          tenantId,
          storeId,
          variantId,
          batchId,
          moveType,
          take.negate(),
          refType,
          refId,
          attribution);
      toDeduct = toDeduct.subtract(take);
    }
    if (toDeduct.signum() > 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_STOCK", "Short by " + toDeduct.toPlainString() + " during deduction");
    }
    if (MoveType.SALE.equals(moveType)) {
      announceConsignmentSales(c, tenantId, storeId, variantId, refId, drawn);
    }
    return drawn;
  }

  /**
   * Which batches a movement may draw, by duty status. Duty-suspended stock sits in bond: a release
   * draws it and nothing else does — a sale, a transfer or a return to vendor takes duty-paid stock
   * only (release first), while an adjustment may correct either, since losses in bond are real.
   */
  private static String dutyFilter(String moveType) {
    if (MoveType.BOND_RELEASE.equals(moveType)) return " AND duty_status='DUTY_SUSPENDED'";
    if (MoveType.ADJUST.equals(moveType)) return "";
    return " AND duty_status='DUTY_PAID'";
  }

  /**
   * A sale drawn from a batch the supplier still owns is the moment the supplier is owed: one
   * ConsignmentStockSold per consignment batch drawn, at the batch's cost (the order's price), on
   * the deduction's own transaction — so purchase-svc hears of every such sale exactly as often as
   * the stock moved.
   */
  private void announceConsignmentSales(
      Connection c, UUID tenantId, UUID storeId, UUID variantId, UUID orderId, List<Drawn> drawn)
      throws SQLException {
    for (Drawn d : drawn) {
      if (!d.consigned() || d.ownerSupplierId() == null) continue;
      insertOutbox(
          c,
          new OutboxRow(
              "ConsignmentStockSold",
              "storeql.inventory.consignment-stock-sold",
              tenantId,
              d.batchId(),
              com.storeql.inventory.service.Events.consignmentStockSold(
                  tenantId,
                  storeId,
                  variantId,
                  d.batchId(),
                  d.ownerSupplierId(),
                  orderId,
                  d.qty(),
                  d.costPrice())));
    }
  }

  // ---------------------------------------------------------------- provenance (SJ-D71)

  /**
   * What a document drew from which batches, read back from the ledger: the negative movements it
   * left on the source store, each joined to the batch it came from. This is how stock in transit
   * knows what it is when it arrives, and how a return knows which lot its goods were sold from.
   */
  private List<Drawn> drawnBy(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String moveType,
      String refType,
      UUID refId)
      throws SQLException {
    List<Drawn> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT m.batch_id, -m.qty AS qty, b.batch_no, b.expiry_date, b.cost_price, b.grade,"
                + " b.ownership, b.owner_supplier_id, b.duty_status"
                + " FROM stock_movements m JOIN inventory_batches b ON b.id = m.batch_id"
                + " WHERE m.tenant_id=? AND m.store_id=? AND m.variant_id=? AND m.type=?"
                + " AND m.ref_type=? AND m.ref_id=? AND m.qty < 0"
                + " ORDER BY m.created_at, m.id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      ps.setString(4, moveType);
      ps.setString(5, refType);
      ps.setObject(6, refId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new Drawn(
                  rs.getObject("batch_id", UUID.class),
                  rs.getBigDecimal("qty"),
                  rs.getString("batch_no"),
                  rs.getObject("expiry_date", LocalDate.class),
                  rs.getBigDecimal("cost_price"),
                  rs.getString("grade"),
                  rs.getString("ownership"),
                  rs.getObject("owner_supplier_id", UUID.class),
                  rs.getString("duty_status")));
        }
      }
    }
    return out;
  }

  /**
   * What earlier returns and voids of an order already put back, by the batch it was sold from:
   * each came back as a child of that batch, so the genealogy says which parent it counts against.
   */
  private Map<UUID, BigDecimal> givenBack(Connection c, UUID tenantId, UUID variantId, UUID orderId)
      throws SQLException {
    Map<UUID, BigDecimal> out = new HashMap<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT g.parent_batch_id, SUM(m.qty) AS qty"
                + " FROM stock_movements m JOIN lot_genealogy g ON g.child_batch_id = m.batch_id"
                + " WHERE m.tenant_id=? AND m.variant_id=? AND m.ref_id=? AND m.type=?"
                + " AND m.ref_type IN ('RETURN','VOID') GROUP BY g.parent_batch_id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, variantId);
      ps.setObject(3, orderId);
      ps.setString(4, MoveType.RECEIVE);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.put(rs.getObject("parent_batch_id", UUID.class), rs.getBigDecimal("qty"));
        }
      }
    }
    return out;
  }

  /** The link that says which batch this one came from, written beside the arrival. */
  private void insertGenealogy(
      Connection c, UUID tenantId, UUID parentId, UUID childId, BigDecimal qty, String notes)
      throws SQLException {
    insertGenealogy(c, tenantId, parentId, childId, qty, "SPLIT", notes);
  }

  /** A link in the lot genealogy: SPLIT for a share moved on, TRANSFORM for a cut made from it. */
  void insertGenealogy(
      Connection c,
      UUID tenantId,
      UUID parentId,
      UUID childId,
      BigDecimal qty,
      String relation,
      String notes)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO lot_genealogy"
                + " (id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, parentId);
      ps.setObject(4, childId);
      ps.setBigDecimal(5, qty);
      ps.setString(6, relation);
      ps.setString(7, notes);
      ps.executeUpdate();
    }
  }

  /**
   * Receives what a document drew, one batch per source batch, each carrying its source's lot,
   * date, cost and grade and linked to it — so the expiring view, a recall and the margin report
   * see moved stock as what it is (SJ-D71).
   *
   * @param fallbackNo the number for an arrival whose source had no lot to carry
   * @param eventFor the outbox event each arrival announces, or null for documents that announce
   *     themselves once
   * @return the batches that arrived
   */
  private List<Batch> receiveDrawn(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      List<Drawn> drawn,
      String fallbackNo,
      String moveType,
      String refType,
      UUID refId,
      Function<Batch, OutboxRow> eventFor)
      throws SQLException {
    List<Batch> arrived = new ArrayList<>();
    for (Drawn from : drawn) {
      Batch child = Provenance.arrival(tenantId, storeId, variantId, from, fallbackNo);
      insertBatch(c, child);
      insertMovement(
          c,
          tenantId,
          storeId,
          variantId,
          child.id(),
          moveType,
          child.receivedQty(),
          refType,
          refId,
          MovementAttribution.system());
      insertGenealogy(
          c, tenantId, from.batchId(), child.id(), child.receivedQty(), refType + " " + refId);
      if (eventFor != null) insertOutbox(c, eventFor.apply(child));
      arrived.add(child);
    }
    return arrived;
  }

  /** Receives an anonymous batch: stock arriving with no source to carry anything from. */
  private Batch receiveAnonymous(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String fallbackNo,
      String moveType,
      String refType,
      UUID refId,
      Function<Batch, OutboxRow> eventFor)
      throws SQLException {
    Batch batch = Provenance.anonymous(tenantId, storeId, variantId, qty, fallbackNo);
    insertBatch(c, batch);
    insertMovement(
        c,
        tenantId,
        storeId,
        variantId,
        batch.id(),
        moveType,
        qty,
        refType,
        refId,
        MovementAttribution.system());
    if (eventFor != null) insertOutbox(c, eventFor.apply(batch));
    return batch;
  }

  /**
   * Goods coming back from an order — a customer return, or a voided till sale — received under the
   * lot they were sold from, deduped on {@code dedupeId} when one is given.
   *
   * <p>The sale's draws say which batches the goods came from; each takes back its share, less what
   * earlier returns already put back on it, as a child batch carrying its lot, date and cost. What
   * the draws cannot account for — more than was sold, or a sale this service never saw — comes
   * back as the anonymous return it always was, so nothing is refused and nothing is invented.
   *
   * @param reverseRevenue whether to take back the sale's revenue and cost (a return does; a void
   *     has none to take back, since the fulfilment may not have arrived)
   * @return false when {@code dedupeId} was already processed
   */
  public boolean receiveBackOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      String refType,
      String fallbackNo,
      Function<Batch, OutboxRow> eventFor,
      boolean reverseRevenue) {
    return inTx(
        c -> {
          if (dedupeId != null && !markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          if (reverseRevenue) {
            reverseRevenueTx(c, tenantId, storeId, variantId, orderId, qty);
          }
          List<Drawn> sold =
              drawnBy(c, tenantId, storeId, variantId, MoveType.SALE, "ORDER", orderId);
          List<Drawn> back =
              Provenance.allocate(sold, givenBack(c, tenantId, variantId, orderId), qty);
          receiveDrawn(
              c,
              tenantId,
              storeId,
              variantId,
              back,
              fallbackNo,
              MoveType.RECEIVE,
              refType,
              orderId,
              eventFor);
          BigDecimal rest = Provenance.unplaced(qty, back);
          if (rest.signum() > 0) {
            receiveAnonymous(
                c,
                tenantId,
                storeId,
                variantId,
                rest,
                fallbackNo,
                MoveType.RECEIVE,
                refType,
                orderId,
                eventFor);
          }
          return true;
        },
        "receive back from order");
  }

  private Reservation loadReservationForUpdate(Connection c, UUID tenantId, UUID id)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at, fulfilment,"
                + " created_at FROM reservations WHERE tenant_id=? AND id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw ApiException.notFound("RESERVATION_NOT_FOUND", "No such reservation");
        return mapReservation(rs);
      }
    }
  }

  private void setReservationStatus(Connection c, UUID id, String status) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE reservations SET status=? WHERE id=?")) {
      ps.setString(1, status);
      ps.setObject(2, id);
      ps.executeUpdate();
    }
  }

  void insertBatch(Connection c, Batch b) throws SQLException {
    insertBatch(c, b, null);
  }

  /**
   * Inserts a batch and, if an open recall covers it, holds it before the transaction commits.
   * Every way stock enters a store comes through here — a delivery, a transfer, a return, a count —
   * so a recalled lot arriving the day after the recall is never on sale for a moment.
   *
   * @return why a recall now holds the batch, or null when none does
   */
  private String insertBatch(Connection c, Batch b, String idempotencyKey) throws SQLException {
    return insertBatch(c, b, idempotencyKey, true);
  }

  /**
   * A batch that crosses the dock: inserted with no putaway — it leaves on a transfer, it is not
   * shelved — and held like any other if a recall covers it.
   */
  static String insertCrossDockBatchTx(InventoryRepository repo, Connection c, Batch b)
      throws SQLException {
    return repo.insertBatch(c, b, null, false);
  }

  private String insertBatch(Connection c, Batch b, String idempotencyKey, boolean putaway)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO inventory_batches"
                + " (id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                + " remaining_qty, cost_price, expiry_date, created_at, status, material_status,"
                + " grade, zone_id, idempotency_key, ownership, owner_supplier_id, duty_status)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, b.id());
      ps.setObject(2, b.tenantId());
      ps.setObject(3, b.storeId());
      ps.setObject(4, b.variantId());
      ps.setString(5, b.batchNo());
      ps.setBigDecimal(6, b.receivedQty());
      ps.setBigDecimal(7, b.remainingQty());
      ps.setBigDecimal(8, b.costPrice());
      ps.setObject(9, b.expiryDate());
      ps.setObject(10, b.createdAt().atOffset(ZoneOffset.UTC));
      ps.setString(11, b.status() == null ? Batch.STATUS_ACTIVE : b.status());
      ps.setString(12, b.materialStatus() == null ? Batch.MATERIAL_AVAILABLE : b.materialStatus());
      ps.setString(13, b.grade());
      ps.setObject(14, b.zoneId());
      ps.setString(15, idempotencyKey);
      ps.setString(16, b.ownership() == null ? Batch.OWNERSHIP_OWNED : b.ownership());
      ps.setObject(17, b.ownerSupplierId());
      ps.setString(18, b.dutyStatus() == null ? Batch.DUTY_PAID : b.dutyStatus());
      ps.executeUpdate();
    }
    // Directed putaway: a batch that arrives with no zone is placed by the store's rule, or waits
    // on the putaway list for a person to place.
    if (putaway
        && b.zoneId() == null
        && b.remainingQty() != null
        && b.remainingQty().signum() > 0) {
      PutawayRepository.directTx(c, b);
    }
    return RecallRepository.holdOnArrival(c, b);
  }

  private static Batch recalledCopy(Batch b, String reason) {
    return new Batch(
        b.id(),
        b.tenantId(),
        b.storeId(),
        b.variantId(),
        b.batchNo(),
        b.receivedQty(),
        b.remainingQty(),
        b.costPrice(),
        b.expiryDate(),
        b.createdAt(),
        b.status(),
        Batch.MATERIAL_RECALLED,
        reason,
        b.grade(),
        b.zoneId(),
        b.ownership(),
        b.ownerSupplierId(),
        b.dutyStatus());
  }

  private void insertReservation(Connection c, Reservation r, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO reservations"
                + " (id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                + " created_at, idempotency_key, fulfilment)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, r.id());
      ps.setObject(2, r.tenantId());
      ps.setObject(3, r.storeId());
      ps.setObject(4, r.variantId());
      ps.setBigDecimal(5, r.qty());
      ps.setObject(6, r.orderId());
      ps.setString(7, r.status());
      ps.setObject(8, r.expiresAt() == null ? null : r.expiresAt().atOffset(ZoneOffset.UTC));
      ps.setObject(9, r.createdAt().atOffset(ZoneOffset.UTC));
      ps.setString(10, idempotencyKey);
      ps.setString(11, r.fulfilment() == null ? Reservation.STOCK : r.fulfilment());
      ps.executeUpdate();
    }
  }

  /**
   * Writes one append-only stock movement (golden rule #8).
   *
   * <p>System-caused movements pass {@code null} for both {@code reasonCode} and {@code actorId}:
   * they already carry {@code refType}/{@code refId} pointing at the order, GRN or transfer header
   * that caused them, and that record names its own actor. NULL here therefore means "see the
   * referenced record", not "unknown". Adjustments are the exception -- they are written with
   * {@code refId = null}, so without these two columns nothing links a stock correction to a person
   * or a reason (SJ-D4).
   */
  static void insertMovement(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String type,
      BigDecimal qty,
      String refType,
      UUID refId,
      MovementAttribution attribution)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO stock_movements"
                + " (id, tenant_id, store_id, variant_id, batch_id, type, qty, ref_type, ref_id,"
                + "  reason_code, actor_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, storeId);
      ps.setObject(4, variantId);
      ps.setObject(5, batchId);
      ps.setString(6, type);
      ps.setBigDecimal(7, qty);
      ps.setString(8, refType);
      ps.setObject(9, refId);
      ps.setString(10, attribution.reasonCode());
      ps.setObject(11, attribution.actorId());
      ps.executeUpdate();
    }
  }

  /**
   * Lifts (or, on a reversal, lowers) the unit cost of the batches one receipt created, by the
   * per-unit share of a landed charge (07.x), once per event line, and records each change.
   *
   * <p>The batches are found through the RECEIVE movements that cite the receipt, so a receipt
   * inventory has not booked yet is answered with a 503 before anything is marked processed: the
   * event is redelivered, and the charge lands when the goods do. A batch's cost never goes below
   * zero. An AVERAGE costing row for the variant at the store takes the charge into its pool over
   * what is on hand there.
   *
   * @return true when applied now; false when this event line was applied before
   * @throws ApiException 503 {@code INVENTORY_RECEIPT_NOT_YET_BOOKED}
   */
  public boolean revalueReceiptOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID grId,
      BigDecimal perUnit,
      BigDecimal amount,
      String sourceType,
      UUID sourceId) {
    return inTx(
        c -> {
          List<UUID> batches = batchesOfReceiptTx(c, tenantId, storeId, variantId, grId);
          if (batches.isEmpty()) {
            throw new ApiException(
                503,
                "INVENTORY_RECEIPT_NOT_YET_BOOKED",
                "no batch at store "
                    + storeId
                    + " cites receipt "
                    + grId
                    + " for variant "
                    + variantId
                    + " yet — the receipt is still on its way",
                List.of());
          }
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          for (UUID batchId : batches) {
            BigDecimal before = lockBatchCostTx(c, tenantId, batchId);
            BigDecimal after = (before == null ? BigDecimal.ZERO : before).add(perUnit);
            if (after.signum() < 0) after = BigDecimal.ZERO;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE inventory_batches SET cost_price = ? WHERE tenant_id = ? AND id = ?")) {
              ps.setBigDecimal(1, after);
              ps.setObject(2, tenantId);
              ps.setObject(3, batchId);
              ps.executeUpdate();
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO batch_cost_adjustments (id, tenant_id, store_id, variant_id,"
                        + " batch_id, source_type, source_id, event_id, per_unit, cost_before,"
                        + " cost_after, applied_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, storeId);
              ps.setObject(4, variantId);
              ps.setObject(5, batchId);
              ps.setString(6, sourceType);
              ps.setObject(7, sourceId);
              ps.setObject(8, dedupeId);
              ps.setBigDecimal(9, perUnit);
              ps.setBigDecimal(10, before);
              ps.setBigDecimal(11, after);
              ps.setObject(12, now);
              ps.executeUpdate();
            }
          }
          // The pool: an AVERAGE row spreads the charge over what is on hand at the store now.
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE costing_methods cm SET average_cost = GREATEST(0, cm.average_cost + ? / oh.qty),"
                      + " updated_at = ? FROM (SELECT COALESCE(SUM(remaining_qty), 0) AS qty"
                      + " FROM inventory_batches WHERE tenant_id = ? AND store_id = ? AND variant_id = ?"
                      + " AND remaining_qty > 0) oh"
                      + " WHERE cm.tenant_id = ? AND cm.store_id = ? AND cm.variant_id = ?"
                      + " AND cm.method = 'AVERAGE' AND cm.average_cost > 0 AND oh.qty > 0")) {
            ps.setBigDecimal(1, amount);
            ps.setObject(2, now);
            ps.setObject(3, tenantId);
            ps.setObject(4, storeId);
            ps.setObject(5, variantId);
            ps.setObject(6, tenantId);
            ps.setObject(7, storeId);
            ps.setObject(8, variantId);
            ps.executeUpdate();
          }
          return true;
        },
        "revalue receipt (deduped)");
  }

  /** The batches a receipt created for one variant at one store, through its RECEIVE movements. */
  private static List<UUID> batchesOfReceiptTx(
      Connection c, UUID tenantId, UUID storeId, UUID variantId, UUID grId) throws SQLException {
    List<UUID> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT DISTINCT batch_id FROM stock_movements WHERE tenant_id = ? AND store_id = ?"
                + " AND variant_id = ? AND ref_type = 'GRN' AND ref_id = ? AND type = 'RECEIVE'"
                + " AND batch_id IS NOT NULL")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      ps.setObject(4, grId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getObject("batch_id", UUID.class));
        }
      }
    }
    return out;
  }

  /** {@code SELECT ... FOR UPDATE}: two charges landing together on one batch add, not race. */
  private static BigDecimal lockBatchCostTx(Connection c, UUID tenantId, UUID batchId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT cost_price FROM inventory_batches WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, batchId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.notFound("BATCH_NOT_FOUND", "batch " + batchId + " is gone");
        }
        return rs.getBigDecimal("cost_price");
      }
    }
  }

  private static Batch mapBatch(ResultSet rs) throws SQLException {
    return new Batch(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("batch_no"),
        rs.getBigDecimal("received_qty"),
        rs.getBigDecimal("remaining_qty"),
        rs.getBigDecimal("cost_price"),
        rs.getObject("expiry_date", java.time.LocalDate.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getString("status"),
        rs.getString("material_status"),
        rs.getString("material_status_reason"),
        rs.getString("grade"),
        rs.getObject("zone_id", UUID.class),
        rs.getString("ownership"),
        rs.getObject("owner_supplier_id", UUID.class),
        rs.getString("duty_status"));
  }

  private static Reservation mapReservation(ResultSet rs) throws SQLException {
    OffsetDateTime expOdt = rs.getObject("expires_at", OffsetDateTime.class);
    Instant exp = expOdt == null ? null : expOdt.toInstant();
    return new Reservation(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getObject("order_id", UUID.class),
        rs.getString("status"),
        exp,
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getString("fulfilment"));
  }

  // ---------------------------------------------------------------- move orders

  /**
   * Inserts a move order.
   *
   * @param order the order to persist
   * @param lines the lines to store
   * @return the move order as stored
   */
  public MoveOrder createMoveOrder(MoveOrder order, List<MoveOrderLine> lines) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO move_orders"
                      + " (id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                      + "  notes, status, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, order.id());
            ps.setObject(2, order.tenantId());
            ps.setObject(3, order.fromStoreId());
            ps.setObject(4, order.toStoreId());
            ps.setString(5, order.fromZone());
            ps.setString(6, order.toZone());
            ps.setString(7, order.notes());
            ps.setString(8, order.status());
            ps.setObject(9, order.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertLines(c, lines);
          return order;
        },
        "create move order");
  }

  /**
   * Lists the tenant's move orders.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<MoveOrder> listMoveOrders(UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                + " notes, status, created_at, picked_at"
                + " FROM move_orders WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND (from_store_id = ? OR to_store_id = ?)");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) {
            ps.setObject(i++, storeId);
            ps.setObject(i++, storeId);
          }
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapMoveOrder,
        "list move orders");
  }

  /**
   * Looks a move order up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the move order to act on
   * @return the move order, or empty when it does not exist in this tenant
   */
  public Optional<MoveOrder> findMoveOrder(UUID tenantId, UUID id) {
    List<MoveOrder> rows =
        query(
            "SELECT id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                + " notes, status, created_at, picked_at"
                + " FROM move_orders WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            InventoryRepository::mapMoveOrder,
            "find move order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Lists the tenant's move order lines.
   *
   * @param moveOrderId the move order id
   * @return the matching rows
   */
  public List<MoveOrderLine> listMoveOrderLines(UUID moveOrderId) {
    return query(
        "SELECT id, tenant_id, move_order_id, variant_id, requested_qty, picked_qty"
            + " FROM move_order_lines WHERE move_order_id = ? ORDER BY id",
        ps -> ps.setObject(1, moveOrderId),
        InventoryRepository::mapMoveOrderLine,
        "list move order lines");
  }

  /**
   * Execute pick: FIFO-deduct from source store, create receiving batch in destination store,
   * record TRANSFER movements on both sides, mark order COMPLETED.
   */
  public MoveOrder pickMoveOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          MoveOrder order = loadMoveOrderForUpdate(c, tenantId, orderId);
          if (MoveOrder.COMPLETED.equals(order.status())
              || MoveOrder.CANCELLED.equals(order.status())) {
            throw ApiException.unprocessable(
                "MOVE_ORDER_NOT_PICKABLE", "Move order is " + order.status());
          }
          List<MoveOrderLine> lines = listMoveOrderLines(orderId);
          for (MoveOrderLine line : lines) {
            List<Drawn> drawn =
                deductFifo(
                    c,
                    tenantId,
                    order.fromStoreId(),
                    line.variantId(),
                    line.requestedQty(),
                    MoveType.TRANSFER,
                    "MOVE_ORDER",
                    orderId,
                    MovementAttribution.system());
            // What is put down is what was picked: each source batch's lot, date and cost.
            receiveDrawn(
                c,
                tenantId,
                order.toStoreId(),
                line.variantId(),
                drawn,
                "MO-" + Ids.shortRef(orderId),
                MoveType.TRANSFER,
                "MOVE_ORDER",
                orderId,
                null);
          }
          MoveOrder completed;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE move_orders SET status = 'COMPLETED', picked_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                      + " notes, status, created_at, picked_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              completed = mapMoveOrder(rs);
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE move_order_lines SET picked_qty = requested_qty"
                      + " WHERE move_order_id = ?")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return completed;
        },
        "pick move order");
  }

  /**
   * Cancels a move order and writes its event — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the move order to cancel
   * @param event the outbox row to commit alongside
   * @return the cancelled order, or empty when it does not exist or is no longer cancellable
   */
  public Optional<MoveOrder> cancelMoveOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          MoveOrder order = loadMoveOrderForUpdate(c, tenantId, orderId);
          if (MoveOrder.COMPLETED.equals(order.status())
              || MoveOrder.CANCELLED.equals(order.status())) {
            return Optional.<MoveOrder>empty();
          }
          MoveOrder cancelled;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE move_orders SET status = 'CANCELLED'"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                      + " notes, status, created_at, picked_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              cancelled = mapMoveOrder(rs);
            }
          }
          insertOutbox(c, event);
          return Optional.of(cancelled);
        },
        "cancel move order");
  }

  private MoveOrder loadMoveOrderForUpdate(Connection c, UUID tenantId, UUID id)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                + " notes, status, created_at, picked_at"
                + " FROM move_orders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw ApiException.notFound("MOVE_ORDER_NOT_FOUND", "No such move order");
        return mapMoveOrder(rs);
      }
    }
  }

  private void insertLines(Connection c, List<MoveOrderLine> lines) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO move_order_lines"
                + " (id, tenant_id, move_order_id, variant_id, requested_qty)"
                + " VALUES (?,?,?,?,?)")) {
      for (MoveOrderLine l : lines) {
        ps.setObject(1, l.id());
        ps.setObject(2, l.tenantId());
        ps.setObject(3, l.moveOrderId());
        ps.setObject(4, l.variantId());
        ps.setBigDecimal(5, l.requestedQty());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static MoveOrder mapMoveOrder(ResultSet rs) throws SQLException {
    OffsetDateTime pickedOdt = rs.getObject("picked_at", OffsetDateTime.class);
    return new MoveOrder(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        rs.getString("from_zone"),
        rs.getString("to_zone"),
        rs.getString("notes"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        pickedOdt == null ? null : pickedOdt.toInstant());
  }

  private static MoveOrderLine mapMoveOrderLine(ResultSet rs) throws SQLException {
    return new MoveOrderLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("move_order_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("requested_qty"),
        rs.getBigDecimal("picked_qty"));
  }

  // ---------------------------------------------------------------- transfer orders

  /**
   * Inserts a transfer order.
   *
   * @param order the order to persist
   * @param lines the lines to store
   * @return the transfer order as stored
   */
  public TransferOrder createTransferOrder(TransferOrder order, List<TransferOrderLine> lines) {
    return inTx(
        c -> {
          createTransferOrderTx(c, order, lines);
          return order;
        },
        "create transfer order");
  }

  /** The columns every read of a transfer order takes, in {@link #mapTransferOrder}'s order. */
  static final String TRANSFER_COLUMNS =
      "id, tenant_id, from_store_id, to_store_id, transfer_type, status, notes, created_at,"
          + " shipped_at, received_at, source, proposal_run_id, purchase_order_id, goods_receipt_id";

  /**
   * Writes a transfer order and its lines on the caller's transaction: a manual one, or a DRAFT a
   * depot replenishment run proposes with the run's id and a reason on each line.
   */
  static void createTransferOrderTx(
      Connection c, TransferOrder order, List<TransferOrderLine> lines) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO transfer_orders"
                + " (id, tenant_id, from_store_id, to_store_id, transfer_type,"
                + "  status, notes, created_at, source, proposal_run_id, purchase_order_id,"
                + "  goods_receipt_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, order.id());
      ps.setObject(2, order.tenantId());
      ps.setObject(3, order.fromStoreId());
      ps.setObject(4, order.toStoreId());
      ps.setString(5, order.transferType());
      ps.setString(6, order.status());
      ps.setString(7, order.notes());
      ps.setObject(8, order.createdAt().atOffset(ZoneOffset.UTC));
      ps.setString(9, order.source() == null ? TransferOrder.SOURCE_MANUAL : order.source());
      ps.setObject(10, order.proposalRunId());
      ps.setObject(11, order.purchaseOrderId());
      ps.setObject(12, order.goodsReceiptId());
      ps.executeUpdate();
    }
    insertTransferLines(c, lines);
  }

  /**
   * Lists the tenant's transfer orders.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<TransferOrder> listTransferOrders(
      UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT " + TRANSFER_COLUMNS + " FROM transfer_orders WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND (from_store_id = ? OR to_store_id = ?)");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) {
            ps.setObject(i++, storeId);
            ps.setObject(i++, storeId);
          }
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapTransferOrder,
        "list transfer orders");
  }

  /**
   * Looks a transfer order up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the transfer order to act on
   * @return the transfer order, or empty when it does not exist in this tenant
   */
  public Optional<TransferOrder> findTransferOrder(UUID tenantId, UUID id) {
    List<TransferOrder> rows =
        query(
            "SELECT " + TRANSFER_COLUMNS + " FROM transfer_orders WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            InventoryRepository::mapTransferOrder,
            "find transfer order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Lists the tenant's transfer order lines.
   *
   * @param transferOrderId the transfer order id
   * @return the matching rows
   */
  public List<TransferOrderLine> listTransferOrderLines(UUID tenantId, UUID transferOrderId) {
    return query(
        "SELECT id, tenant_id, transfer_order_id, variant_id,"
            + " requested_qty, shipped_qty, received_qty, reason, source_batch_id"
            + " FROM transfer_order_lines WHERE tenant_id = ? AND transfer_order_id = ? ORDER BY id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, transferOrderId);
        },
        InventoryRepository::mapTransferOrderLine,
        "list transfer order lines");
  }

  /**
   * Ship a PENDING transfer: deducts source store stock via FIFO. For DIRECT type: also creates
   * destination batch and marks RECEIVED immediately. For INTRANSIT type: only deducts source;
   * marks SHIPPED (awaiting receive call).
   */
  public TransferOrder shipTransferOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          TransferOrder order = loadTransferOrderForUpdate(c, tenantId, orderId);
          if (!TransferOrder.PENDING.equals(order.status())) {
            throw ApiException.unprocessable(
                "TRANSFER_ORDER_NOT_SHIPPABLE", "Transfer order is " + order.status());
          }
          List<TransferOrderLine> lines = listTransferOrderLines(tenantId, orderId);
          boolean isDirect = TransferOrder.TYPE_DIRECT.equals(order.transferType());

          for (TransferOrderLine line : lines) {
            // A cross-dock line ships the batch the delivery made first: what crosses the dock is
            // what arrived, with its lot, date and cost.
            List<Drawn> drawn =
                deductBatches(
                    c,
                    tenantId,
                    order.fromStoreId(),
                    line.variantId(),
                    line.requestedQty(),
                    MoveType.TRANSFER,
                    "TRANSFER_ORDER",
                    orderId,
                    null,
                    null,
                    null,
                    line.sourceBatchId(),
                    MovementAttribution.system());
            if (isDirect) {
              // What arrives is what left: each source batch's lot, date and cost (SJ-D71).
              receiveDrawn(
                  c,
                  tenantId,
                  order.toStoreId(),
                  line.variantId(),
                  drawn,
                  "TO-" + Ids.shortRef(orderId),
                  MoveType.TRANSFER,
                  "TRANSFER_ORDER",
                  orderId,
                  null);
            }
          }

          // Update lines: shipped_qty = requested_qty (and received_qty for DIRECT)
          try (PreparedStatement ps =
              c.prepareStatement(
                  isDirect
                      ? "UPDATE transfer_order_lines"
                          + " SET shipped_qty = requested_qty, received_qty = requested_qty"
                          + " WHERE transfer_order_id = ?"
                      : "UPDATE transfer_order_lines SET shipped_qty = requested_qty"
                          + " WHERE transfer_order_id = ?")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
          }

          TransferOrder updated;
          String sql =
              isDirect
                  ? "UPDATE transfer_orders SET status = 'RECEIVED',"
                      + " shipped_at = now(), received_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING "
                      + TRANSFER_COLUMNS
                  : "UPDATE transfer_orders SET status = 'SHIPPED', shipped_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING "
                      + TRANSFER_COLUMNS;
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              updated = mapTransferOrder(rs);
            }
          }
          insertOutbox(c, event);
          return updated;
        },
        "ship transfer order");
  }

  /**
   * Receive a SHIPPED INTRANSIT transfer: creates destination batches for each line. Only valid for
   * INTRANSIT type in SHIPPED status.
   */
  public TransferOrder receiveTransferOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          TransferOrder order = loadTransferOrderForUpdate(c, tenantId, orderId);
          if (!TransferOrder.SHIPPED.equals(order.status())) {
            throw ApiException.unprocessable(
                "TRANSFER_ORDER_NOT_RECEIVABLE", "Transfer order is " + order.status());
          }
          if (TransferOrder.TYPE_DIRECT.equals(order.transferType())) {
            throw ApiException.unprocessable(
                "TRANSFER_ORDER_DIRECT_AUTO_RECEIVED",
                "DIRECT transfers are auto-received on ship");
          }
          List<TransferOrderLine> lines = listTransferOrderLines(tenantId, orderId);
          for (TransferOrderLine line : lines) {
            BigDecimal qty = line.shippedQty() == null ? line.requestedQty() : line.shippedQty();
            // What arrives is what left the sending store, read back from the ledger the shipment
            // wrote there: each source batch's lot, date and cost (SJ-D71). A shipment the ledger
            // does not account for arrives as it always did.
            List<Drawn> shipped =
                drawnBy(
                    c,
                    tenantId,
                    order.fromStoreId(),
                    line.variantId(),
                    MoveType.TRANSFER,
                    "TRANSFER_ORDER",
                    orderId);
            String number = "TO-" + Ids.shortRef(orderId);
            if (shipped.isEmpty()) {
              receiveAnonymous(
                  c,
                  tenantId,
                  order.toStoreId(),
                  line.variantId(),
                  qty,
                  number,
                  MoveType.TRANSFER,
                  "TRANSFER_ORDER",
                  orderId,
                  null);
            } else {
              receiveDrawn(
                  c,
                  tenantId,
                  order.toStoreId(),
                  line.variantId(),
                  shipped,
                  number,
                  MoveType.TRANSFER,
                  "TRANSFER_ORDER",
                  orderId,
                  null);
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transfer_order_lines SET received_qty = shipped_qty"
                      + " WHERE transfer_order_id = ?")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
          }
          TransferOrder received;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transfer_orders SET status = 'RECEIVED', received_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING "
                      + TRANSFER_COLUMNS)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              received = mapTransferOrder(rs);
            }
          }
          insertOutbox(c, event);
          return received;
        },
        "receive transfer order");
  }

  /**
   * Cancels a transfer order and writes its event — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the transfer order to cancel
   * @param event the outbox row to commit alongside
   * @return the cancelled order, or empty when it does not exist or has already shipped
   */
  public Optional<TransferOrder> cancelTransferOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          TransferOrder order = loadTransferOrderForUpdate(c, tenantId, orderId);
          // A proposed DRAFT may be discarded as a released PENDING one may be cancelled.
          if (!TransferOrder.PENDING.equals(order.status())
              && !TransferOrder.DRAFT.equals(order.status())) {
            return Optional.<TransferOrder>empty();
          }
          TransferOrder cancelled;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transfer_orders SET status = 'CANCELLED'"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING "
                      + TRANSFER_COLUMNS)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              cancelled = mapTransferOrder(rs);
            }
          }
          insertOutbox(c, event);
          return Optional.of(cancelled);
        },
        "cancel transfer order");
  }

  private TransferOrder loadTransferOrderForUpdate(Connection c, UUID tenantId, UUID id)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + TRANSFER_COLUMNS
                + " FROM transfer_orders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next())
          throw ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order");
        return mapTransferOrder(rs);
      }
    }
  }

  private static void insertTransferLines(Connection c, List<TransferOrderLine> lines)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO transfer_order_lines"
                + " (id, tenant_id, transfer_order_id, variant_id, requested_qty, reason,"
                + " source_batch_id) VALUES (?,?,?,?,?,?,?)")) {
      for (TransferOrderLine l : lines) {
        ps.setObject(1, l.id());
        ps.setObject(2, l.tenantId());
        ps.setObject(3, l.transferOrderId());
        ps.setObject(4, l.variantId());
        ps.setBigDecimal(5, l.requestedQty());
        ps.setString(6, l.reason());
        ps.setObject(7, l.sourceBatchId());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static TransferOrder mapTransferOrder(ResultSet rs) throws SQLException {
    OffsetDateTime shippedOdt = rs.getObject("shipped_at", OffsetDateTime.class);
    OffsetDateTime receivedOdt = rs.getObject("received_at", OffsetDateTime.class);
    return new TransferOrder(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        rs.getString("transfer_type"),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        shippedOdt == null ? null : shippedOdt.toInstant(),
        receivedOdt == null ? null : receivedOdt.toInstant(),
        rs.getString("source"),
        rs.getObject("proposal_run_id", UUID.class),
        rs.getObject("purchase_order_id", UUID.class),
        rs.getObject("goods_receipt_id", UUID.class));
  }

  private static TransferOrderLine mapTransferOrderLine(ResultSet rs) throws SQLException {
    return new TransferOrderLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("transfer_order_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("requested_qty"),
        rs.getBigDecimal("shipped_qty"),
        rs.getBigDecimal("received_qty"),
        rs.getString("reason"),
        rs.getObject("source_batch_id", UUID.class));
  }

  // ── Tier-1 Gap #24: Expiry alert query ────────────────────────────────────

  /**
   * Lists the tenant's expiring batches.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param withinDays the within days
   * @return the matching rows
   */
  public List<Batch> listExpiringBatches(UUID tenantId, UUID storeId, int withinDays) {
    return query(
        "SELECT id,tenant_id,store_id,variant_id,batch_no,received_qty,remaining_qty,"
            + "cost_price,expiry_date,created_at,status,material_status,material_status_reason,grade,zone_id,ownership,owner_supplier_id,duty_status"
            + " FROM inventory_batches"
            + " WHERE tenant_id=? AND store_id=? AND status='ACTIVE'"
            + " AND expiry_date IS NOT NULL"
            + " AND expiry_date <= CURRENT_DATE + make_interval(days => ?)"
            + " ORDER BY expiry_date ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setInt(3, withinDays);
        },
        InventoryRepository::mapBatch,
        "list expiring batches");
  }

  // ── Tier-1 Gap #25: Grade update on batch ────────────────────────────────

  /**
   * Writes a batch grade back with its new values.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param batchId the batch id
   * @param grade the stock grade
   * @return the batch grade as stored
   */
  public Batch updateBatchGrade(UUID tenantId, UUID batchId, String grade) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE inventory_batches SET grade=? WHERE tenant_id=? AND id=?"
                      + " RETURNING id,tenant_id,store_id,variant_id,batch_no,received_qty,"
                      + "remaining_qty,cost_price,expiry_date,created_at,status,"
                      + "material_status,material_status_reason,grade,zone_id,ownership,owner_supplier_id,duty_status")) {
            ps.setString(1, grade);
            ps.setObject(2, tenantId);
            ps.setObject(3, batchId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw ApiException.notFound("BATCH_NOT_FOUND", "No such batch");
              return mapBatch(rs);
            }
          }
        },
        "update batch grade");
  }

  // ── Tier-1 Gap #28: Order modifier updates ───────────────────────────────
  // Both methods moved: ROP → ReorderPointRepository, Kanban → KanbanRepository
  // (same aggregate/table as each).

  // ─────────────────────────────────────────────────── picking rules (Gap #38)
  // CRUD for rules/zone-priorities/assignments moved to PickingRuleRepository.
  // previewPickOrder (+ its pickOrderClause/buildZoneCaseClause helpers) stays here
  // since it needs mapBatch — same family as batches(read)/material-status/grade-update.

  /**
   * Preview pick order for a (tenant, store, variant) — returns available batches in rule order.
   */
  public List<Batch> previewPickOrder(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String strategy,
      String gradePreference,
      List<UUID> zonePriorityOrder) {
    String orderBy = pickOrderClause(strategy, gradePreference, zonePriorityOrder);
    return query(
        "SELECT id,tenant_id,store_id,variant_id,batch_no,received_qty,remaining_qty,"
            + "cost_price,expiry_date,created_at,status,material_status,material_status_reason,grade,zone_id,ownership,owner_supplier_id,duty_status"
            + " FROM inventory_batches"
            + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND remaining_qty>0"
            + " AND material_status='AVAILABLE'"
            + " ORDER BY "
            + orderBy,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setObject(3, variantId);
        },
        InventoryRepository::mapBatch,
        "preview pick order");
  }

  /** Returns the ORDER BY clause for the given picking strategy. */
  static String pickOrderClause(
      String strategy, String gradePreference, List<UUID> zonePriorityOrder) {
    String effectiveStrategy = strategy == null ? PickingRule.FEFO : strategy;
    return switch (effectiveStrategy) {
      case PickingRule.FIFO -> "created_at ASC";
      case PickingRule.LIFO -> "created_at DESC";
      case PickingRule.FEFO_GRADE ->
          gradePreference != null
              ? "CASE WHEN grade='"
                  + gradePreference.replace("'", "''")
                  + "' THEN 0 ELSE 1 END ASC,"
                  + " expiry_date ASC NULLS LAST, created_at ASC"
              : "expiry_date ASC NULLS LAST, created_at ASC";
      case PickingRule.ZONE_PRIORITY ->
          zonePriorityOrder != null && !zonePriorityOrder.isEmpty()
              ? buildZoneCaseClause(zonePriorityOrder)
                  + ", expiry_date ASC NULLS LAST, created_at ASC"
              : "expiry_date ASC NULLS LAST, created_at ASC";
      default -> "expiry_date ASC NULLS LAST, created_at ASC"; // FEFO
    };
  }

  private static String buildZoneCaseClause(List<UUID> zoneOrder) {
    StringBuilder sb = new StringBuilder("CASE zone_id");
    for (int i = 0; i < zoneOrder.size(); i++) {
      sb.append(" WHEN '").append(zoneOrder.get(i)).append("' THEN ").append(i);
    }
    sb.append(" ELSE ").append(zoneOrder.size()).append(" END ASC");
    return sb.toString();
  }

  /**
   * Internal-only copy of the picking-rule resolution used by {@code consumeTx} so FIFO/FEFO/zone
   * deduction picks the right strategy. The public, service-facing CRUD for picking rules lives in
   * {@link PickingRuleRepository}; this duplicates just the read path rather than injecting that
   * repo. Matches the original (pre-extraction) behavior exactly: {@code query()} acquires its own
   * connection, so this was never part of {@code consumeTx}'s transaction even before the split.
   */
  Optional<PickingRule> resolvePickingRule(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT pr.id,pr.tenant_id,pr.name,pr.strategy,pr.grade_preference,pr.status,"
                + "pr.created_at,pr.updated_at"
                + " FROM picking_rule_assignments pra"
                + " JOIN picking_rules pr ON pr.id=pra.rule_id AND pr.status='ACTIVE'"
                + " WHERE pra.tenant_id=?"
                + " AND ((pra.scope_type='PRODUCT' AND pra.scope_id=?)"
                + "   OR (pra.scope_type='STORE' AND pra.scope_id=?)"
                + "   OR (pra.scope_type='GLOBAL'))"
                + " ORDER BY CASE pra.scope_type WHEN 'PRODUCT' THEN 1"
                + "           WHEN 'STORE' THEN 2 ELSE 3 END LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setObject(3, storeId);
            },
            InventoryRepository::mapPickingRule,
            "resolve picking rule")
        .stream()
        .findFirst();
  }

  List<PickingRuleZonePriority> listZonePriorities(UUID tenantId, UUID ruleId) {
    return query(
        "SELECT id,tenant_id,rule_id,zone_id,priority FROM picking_rule_zone_priorities"
            + " WHERE tenant_id=? AND rule_id=? ORDER BY priority ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, ruleId);
        },
        InventoryRepository::mapZonePriority,
        "list zone priorities");
  }

  private static PickingRule mapPickingRule(ResultSet rs) throws SQLException {
    return new PickingRule(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("strategy"),
        rs.getString("grade_preference"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static PickingRuleZonePriority mapZonePriority(ResultSet rs) throws SQLException {
    return new PickingRuleZonePriority(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("rule_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getInt("priority"));
  }

  /**
   * Within an open transaction: if a reorder threshold exists for (tenant, store, variant) and the
   * current available qty is below it, inserts a StockBelowThreshold outbox event. Called after any
   * stock-reducing operation so the alert and the deduction are atomic (golden rule #6).
   */
  void checkThresholdTx(Connection c, UUID tenantId, UUID storeId, UUID variantId)
      throws SQLException {
    BigDecimal threshold = null;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT threshold FROM reorder_thresholds"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) threshold = rs.getBigDecimal("threshold");
      }
    }
    if (threshold == null) return;

    BigDecimal onHand = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(remaining_qty),0) AS q FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=?"
                + " AND material_status='AVAILABLE'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) onHand = rs.getBigDecimal("q");
      }
    }
    BigDecimal reserved = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(qty),0) AS q FROM reservations"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND status='HELD'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) reserved = rs.getBigDecimal("q");
      }
    }
    BigDecimal available = onHand.subtract(reserved);
    if (available.compareTo(threshold) < 0) {
      insertOutbox(
          c,
          new OutboxRow(
              "StockBelowThreshold",
              "storeql.inventory.stock-below-threshold",
              tenantId,
              variantId,
              com.storeql.inventory.service.Events.stockBelowThreshold(
                  tenantId, storeId, variantId, available, threshold)));
    }
  }

  // ---------------------------------------------------------------- sale revenue (19.7)

  static void insertSaleRevenueTx(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID orderId,
      BigDecimal qty,
      BigDecimal netAmount,
      BigDecimal costAmount,
      String kind)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO sale_revenue"
                + " (id, tenant_id, store_id, variant_id, order_id, qty, net_amount, cost_amount, kind)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, storeId);
      ps.setObject(4, variantId);
      ps.setObject(5, orderId);
      ps.setBigDecimal(6, qty);
      ps.setBigDecimal(7, netAmount);
      ps.setBigDecimal(8, costAmount);
      ps.setString(9, kind);
      ps.executeUpdate();
    }
  }

  /** Cost prices are held to {@code inventory_batches.cost_price NUMERIC(18,2)}. */
  private static final int COST_SCALE = 2;

  /**
   * Takes back the revenue and the cost of {@code qty} returned units, at the averages the order's
   * line recorded: its net revenue per unit, and the cost per unit of the costed batches its sale
   * drew down. Never more than the line still has unreturned, however many returns arrive. A sale
   * that recorded no revenue has nothing to take back.
   */
  private static void reverseRevenueTx(
      Connection c, UUID tenantId, UUID storeId, UUID variantId, UUID orderId, BigDecimal qty)
      throws SQLException {
    if (qty == null || qty.signum() <= 0) return;
    BigDecimal soldQty;
    BigDecimal soldNet;
    BigDecimal unreturned;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(qty) FILTER (WHERE kind = 'SALE'), 0) AS sold_qty,"
                + " COALESCE(SUM(net_amount) FILTER (WHERE kind = 'SALE'), 0) AS sold_net,"
                + " COALESCE(SUM(qty), 0) AS unreturned"
                + " FROM sale_revenue WHERE tenant_id = ? AND order_id = ? AND variant_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        // An aggregate always answers one row; the check is for the reader, not the database.
        if (!rs.next()) return;
        soldQty = rs.getBigDecimal("sold_qty");
        soldNet = rs.getBigDecimal("sold_net");
        unreturned = rs.getBigDecimal("unreturned");
      }
    }
    if (soldQty.signum() <= 0 || unreturned.signum() <= 0) return;
    BigDecimal back = qty.min(unreturned);
    // The currency's scale is the one order-svc sent the sale in; this service does not know it.
    BigDecimal netBack =
        soldNet.multiply(back).divide(soldQty, soldNet.scale(), RoundingMode.HALF_UP);
    BigDecimal costBack = null;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT SUM(-m.qty * b.cost_price) AS cost, SUM(-m.qty) AS q"
                + "  FROM stock_movements m JOIN inventory_batches b ON b.id = m.batch_id"
                + " WHERE m.tenant_id = ? AND m.type = 'SALE' AND m.ref_type = 'ORDER'"
                + "   AND m.ref_id = ? AND m.variant_id = ? AND b.cost_price IS NOT NULL")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, orderId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          BigDecimal costedQty = rs.getBigDecimal("q");
          if (costedQty != null && costedQty.signum() > 0) {
            costBack =
                rs.getBigDecimal("cost")
                    .multiply(back)
                    .divide(costedQty, COST_SCALE, RoundingMode.HALF_UP);
          }
        }
      }
    }
    insertSaleRevenueTx(
        c,
        tenantId,
        storeId,
        variantId,
        orderId,
        back.negate(),
        netBack.negate(),
        costBack == null ? null : costBack.negate(),
        "RETURN");
  }
}
