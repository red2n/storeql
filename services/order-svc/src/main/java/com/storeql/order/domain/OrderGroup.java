package com.storeql.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One checkout placed as several orders, one per store (order orchestration): the shopper's reading
 * of it. Each part is an ordinary order with its own store, lines, holds and total; the group's
 * total is theirs added up.
 *
 * @param parts the orders, the delivery-area store's first
 */
public record OrderGroup(
    UUID id,
    UUID tenantId,
    UUID customerId,
    UUID loginId,
    BigDecimal total,
    String currency,
    Instant createdAt,
    List<Part> parts) {

  public OrderGroup {
    parts = List.copyOf(parts);
  }

  /**
   * One order of the group, as the group lists it.
   *
   * @param units how many items it carries, its lines' quantities added up
   * @param slotStartsAt the delivery or collection window this checkout holds, in UTC — the same
   *     for every part (delivery and collection slots); null when the checkout carries none
   * @param slotEndsAt the window's end, in UTC
   * @param slotTimeZone the IANA zone the window was resolved in
   */
  public record Part(
      UUID orderId,
      UUID storeId,
      String status,
      BigDecimal total,
      BigDecimal units,
      Instant slotStartsAt,
      Instant slotEndsAt,
      String slotTimeZone) {

    /** A part as read before delivery and collection slots existed: no window. */
    public Part(UUID orderId, UUID storeId, String status, BigDecimal total, BigDecimal units) {
      this(orderId, storeId, status, total, units, null, null, null);
    }
  }
}
