package com.storeql.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** The shapes of cross-docking at the warehouse (intent/cross-docking.md). */
public final class CrossDock {

  private CrossDock() {}

  /** What a purchase order still owes a shop of a product, to cross the warehouse's dock. */
  public record Owed(UUID warehouseId, UUID storeId, UUID variantId, BigDecimal qty) {}

  /** One shop's claim on a product of a purchase order. */
  public record Expected(UUID storeId, UUID variantId, BigDecimal qty) {}

  /** A delivered line crossing the dock, and what each shop is given of it and why. */
  public record DockLine(
      UUID variantId,
      BigDecimal received,
      String batchNo,
      BigDecimal costPrice,
      LocalDate expiry,
      Map<UUID, BigDecimal> shares,
      Map<UUID, String> reasons) {
    public DockLine {
      shares = Map.copyOf(shares);
      reasons = Map.copyOf(reasons);
    }
  }
}
