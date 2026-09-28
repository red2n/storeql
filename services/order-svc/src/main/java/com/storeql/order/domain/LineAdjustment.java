package com.storeql.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A line of an online order closed short or substituted by the store (substitutions for
 * out-of-stock online lines), append-only.
 *
 * @param kind {@link #SHORT_CLOSED} or {@link #SUBSTITUTED}
 * @param itemId the line adjusted
 * @param qty how much of it was closed, or replaced
 * @param substituteItemId the new line, for a substitution
 * @param chargedAmount what the substitute is charged, gross, for a substitution; zero for a close
 * @param refundAmount what the shopper is owed back, gross: the order's total before less after
 */
public record LineAdjustment(
    UUID id,
    UUID tenantId,
    UUID orderId,
    String kind,
    UUID itemId,
    UUID variantId,
    BigDecimal qty,
    UUID substituteItemId,
    UUID substituteVariantId,
    BigDecimal chargedAmount,
    BigDecimal refundAmount,
    String reason,
    UUID adjustedBy,
    String idempotencyKey,
    Instant adjustedAt) {

  public static final String SHORT_CLOSED = "SHORT_CLOSED";
  public static final String SUBSTITUTED = "SUBSTITUTED";
}
