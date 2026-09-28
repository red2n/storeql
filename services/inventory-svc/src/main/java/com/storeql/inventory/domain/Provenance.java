package com.storeql.inventory.domain;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What moved stock still is (SJ-D71).
 *
 * <p>A transfer, a move order and a return draw from batches that have a lot, a use-by date and a
 * cost, and what arrives used to be one anonymous batch per line — {@code TO-}, {@code MO-}, {@code
 * RET-} and a short reference — with none of the three. So moved stock dropped out of the expiring
 * view, a recall could only hold it as <em>lot unknown</em>, and the margin report did not know
 * what it cost. Now what arrives is one batch per source batch drawn, carrying each one's lot,
 * date, cost and grade, tied to it by a genealogy link; the system number is kept only for stock
 * whose source had no lot to carry.
 */
public final class Provenance {

  private Provenance() {}

  /**
   * One source batch a document drew from, and what it drew.
   *
   * @param qty what was taken from this batch, positive
   */
  public record Drawn(
      UUID batchId,
      BigDecimal qty,
      String batchNo,
      LocalDate expiryDate,
      BigDecimal costPrice,
      String grade,
      /** Whose the stock was: OWNED or CONSIGNMENT — ownership rides with what is drawn. */
      String ownership,
      UUID ownerSupplierId,
      /** DUTY_PAID or DUTY_SUSPENDED: the duty status rides with what is drawn. */
      String dutyStatus) {

    /** A draw from the business's own, duty-paid stock. */
    public Drawn(
        UUID batchId,
        BigDecimal qty,
        String batchNo,
        LocalDate expiryDate,
        BigDecimal costPrice,
        String grade) {
      this(
          batchId,
          qty,
          batchNo,
          expiryDate,
          costPrice,
          grade,
          Batch.OWNERSHIP_OWNED,
          null,
          Batch.DUTY_PAID);
    }

    /** A draw of duty-paid stock of the given ownership. */
    public Drawn(
        UUID batchId,
        BigDecimal qty,
        String batchNo,
        LocalDate expiryDate,
        BigDecimal costPrice,
        String grade,
        String ownership,
        UUID ownerSupplierId) {
      this(
          batchId,
          qty,
          batchNo,
          expiryDate,
          costPrice,
          grade,
          ownership,
          ownerSupplierId,
          Batch.DUTY_PAID);
    }

    /** The same source, a different quantity of it. */
    public Drawn of(BigDecimal quantity) {
      return new Drawn(
          batchId,
          quantity,
          batchNo,
          expiryDate,
          costPrice,
          grade,
          ownership,
          ownerSupplierId,
          dutyStatus);
    }

    /** Whether the supplier owns what was drawn. */
    public boolean consigned() {
      return Batch.OWNERSHIP_CONSIGNMENT.equals(ownership);
    }
  }

  /**
   * Splits a quantity coming back across the batches it was drawn from, in the order they were
   * drawn, skipping what each has already had back.
   *
   * <p>A return names an order, not a batch, so the batches its goods came from are the ones the
   * sale drew, and what each can take back is what it gave less what earlier returns already put
   * back on it. Whatever the draws cannot account for is the caller's to receive without provenance
   * — a return of more than was sold, or of a sale this service never saw.
   *
   * @param drawn the sale's draws, oldest first
   * @param givenBack what earlier returns already put back, by source batch
   * @param qty what is coming back now
   * @return the share of {@code qty} each source batch takes back, in draw order, only those that
   *     take some; their quantities sum to at most {@code qty}
   */
  public static List<Drawn> allocate(
      List<Drawn> drawn, Map<UUID, BigDecimal> givenBack, BigDecimal qty) {
    List<Drawn> out = new ArrayList<>();
    BigDecimal left = qty;
    for (Drawn d : drawn) {
      if (left.signum() <= 0) break;
      BigDecimal room = d.qty().subtract(givenBack.getOrDefault(d.batchId(), BigDecimal.ZERO));
      if (room.signum() <= 0) continue;
      BigDecimal take = room.min(left);
      out.add(d.of(take));
      left = left.subtract(take);
    }
    return out;
  }

  /** What {@link #allocate} could not place: {@code qty} less what the allocations sum to. */
  public static BigDecimal unplaced(BigDecimal qty, List<Drawn> allocated) {
    BigDecimal placed = BigDecimal.ZERO;
    for (Drawn d : allocated) placed = placed.add(d.qty());
    return qty.subtract(placed);
  }

  /**
   * The batch that arrives from one source batch: its quantity, and the source's lot, use-by date,
   * cost and grade. A source whose number is not a supplier's lot — one this service wrote itself —
   * has no lot to carry, so the arrival takes the document's own number instead, and a recall
   * treats it as it always did: a lot nobody knows.
   *
   * @param fallbackNo the system number for stock with no lot: {@code TO-}, {@code MO-} or {@code
   *     RET-} and the document's short reference
   */
  public static Batch arrival(
      UUID tenantId, UUID storeId, UUID variantId, Drawn from, String fallbackNo) {
    String number = Recall.isSupplierLot(from.batchNo()) ? from.batchNo() : fallbackNo;
    return new Batch(
        Ids.newId(),
        tenantId,
        storeId,
        variantId,
        number,
        from.qty(),
        from.qty(),
        from.costPrice(),
        from.expiryDate(),
        Instant.now(),
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        from.grade(),
        null,
        from.ownership() == null ? Batch.OWNERSHIP_OWNED : from.ownership(),
        from.ownerSupplierId(),
        from.dutyStatus() == null ? Batch.DUTY_PAID : from.dutyStatus());
  }

  /**
   * The duty-paid batch a release to home use makes from what it drew out of bond: the same lot,
   * cost and date, at the same store, its duty now paid.
   */
  public static Batch released(UUID tenantId, UUID storeId, UUID variantId, Drawn from) {
    return new Batch(
        Ids.newId(),
        tenantId,
        storeId,
        variantId,
        from.batchNo(),
        from.qty(),
        from.qty(),
        from.costPrice(),
        from.expiryDate(),
        Instant.now(),
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        from.grade(),
        null,
        from.ownership() == null ? Batch.OWNERSHIP_OWNED : from.ownership(),
        from.ownerSupplierId(),
        Batch.DUTY_PAID);
  }

  /** Stock arriving with no source to carry anything from: the anonymous batch of before. */
  public static Batch anonymous(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, String fallbackNo) {
    return new Batch(
        Ids.newId(),
        tenantId,
        storeId,
        variantId,
        fallbackNo,
        qty,
        qty,
        null,
        null,
        Instant.now(),
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        null,
        null);
  }
}
