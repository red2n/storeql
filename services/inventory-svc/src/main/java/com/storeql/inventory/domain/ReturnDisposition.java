package com.storeql.inventory.domain;

import com.storeql.inventory.domain.Domain.Batch;
import java.util.Locale;

/**
 * Where returned goods go, by the condition the till recorded for them (return controls). Pure.
 *
 * <p>Sealed goods, and goods from an event that names no condition (from before the till was
 * asked), go back on sale. Opened goods wait for a person to check them. Damaged and faulty goods
 * are off sale as damaged — writing them off stays a separate stock adjustment. A return that
 * settles a recall goes to RECALLED whatever its condition.
 *
 * @param materialStatus the batch's material status
 * @param reason the reason in words, null when the goods go back on sale
 */
public record ReturnDisposition(String materialStatus, String reason) {

  /** Back on sale: what a return always was. */
  public static final ReturnDisposition ON_SALE =
      new ReturnDisposition(Batch.MATERIAL_AVAILABLE, null);

  /**
   * The disposition for one returned line.
   *
   * @param condition SEALED, OPENED, DAMAGED or FAULTY; null, blank or unknown means back on sale
   * @param recall whether the return settles a recall notice
   */
  public static ReturnDisposition of(String condition, boolean recall) {
    if (recall) {
      return new ReturnDisposition(
          Batch.MATERIAL_RECALLED, "Returned against a recall — held, not for sale");
    }
    String c = condition == null ? "" : condition.trim().toUpperCase(Locale.ROOT);
    return switch (c) {
      case "OPENED" ->
          new ReturnDisposition(
              Batch.MATERIAL_INSPECTION, "Returned opened — check before it goes back on sale");
      case "DAMAGED" ->
          new ReturnDisposition(Batch.MATERIAL_DAMAGED, "Returned damaged — not for sale");
      case "FAULTY" ->
          new ReturnDisposition(Batch.MATERIAL_DAMAGED, "Returned faulty — not for sale");
      default -> ON_SALE;
    };
  }

  /** Whether the goods go back on sale. */
  public boolean onSale() {
    return Batch.MATERIAL_AVAILABLE.equals(materialStatus);
  }

  /** The batch, placed as this disposition says: same lot, cost, date and grade, its own status. */
  public Batch place(Batch b) {
    if (onSale()) return b;
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
        materialStatus,
        reason,
        b.grade(),
        b.zoneId(),
        b.ownership(),
        b.ownerSupplierId(),
        b.dutyStatus());
  }
}
