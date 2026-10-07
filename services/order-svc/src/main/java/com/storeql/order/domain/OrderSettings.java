package com.storeql.order.domain;

import com.storeql.web.ApiException;

/**
 * The waits a business sets on an order, one row per business. Every limit is off until the
 * business sets it: a null pending limit means the platform's own default, a null price wait means
 * an order waits for its price as long as it always did.
 *
 * @param pendingLimitHours how long an unpaid order is held, in whole hours of at least 1
 * @param priceWaitFlagMinutes how long an order waits for a price before a manager is told
 * @param priceWaitCancelMinutes how long before it is cancelled, never sooner than the flag
 */
public record OrderSettings(
    Integer pendingLimitHours, Integer priceWaitFlagMinutes, Integer priceWaitCancelMinutes) {

  /** A business that has set nothing. */
  public static final OrderSettings NONE = new OrderSettings(null, null, null);

  /**
   * The unpaid-order limit as hours, the business's own or the platform's default.
   *
   * @param platformDefault what applies while the business has set none
   */
  public int pendingHours(int platformDefault) {
    return pendingLimitHours == null ? platformDefault : pendingLimitHours;
  }

  /**
   * A copy with a new unpaid-order limit.
   *
   * @param hours whole hours of at least 1, or null for the platform default
   * @throws ApiException 400 {@code ORDER_PENDING_LIMIT_INVALID}
   */
  public OrderSettings withPendingLimit(Integer hours) {
    if (hours != null && hours < 1) {
      throw ApiException.badRequest(
          "ORDER_PENDING_LIMIT_INVALID",
          "the unpaid-order limit is a whole number of hours, 1 or more");
    }
    return new OrderSettings(hours, priceWaitFlagMinutes, priceWaitCancelMinutes);
  }

  /**
   * A copy with new price-wait limits.
   *
   * @param flag minutes before a manager is told, or null for never
   * @param cancel minutes before the order is cancelled, or null for never
   * @throws ApiException 400 {@code ORDER_PRICE_WAIT_INVALID} for a limit under 1 minute, or a
   *     cancel limit earlier than the flag
   */
  public OrderSettings withPriceWait(Integer flag, Integer cancel) {
    if ((flag != null && flag < 1)
        || (cancel != null && cancel < 1)
        || (flag != null && cancel != null && cancel < flag)) {
      throw ApiException.badRequest(
          "ORDER_PRICE_WAIT_INVALID",
          "price-wait limits are whole minutes of 1 or more, and the cancel limit is not before"
              + " the flag limit");
    }
    return new OrderSettings(pendingLimitHours, flag, cancel);
  }
}
