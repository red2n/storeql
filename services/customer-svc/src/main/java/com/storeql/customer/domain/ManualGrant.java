package com.storeql.customer.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What management handed out by hand — points or store credit — kept with who did it and why.
 *
 * <p>Pure: the one rule here is what makes a retry the same request. A key that comes back with the
 * same kind, customer, amount and currency is a retry and is answered with the first; the same key
 * for anything else is a client mixing keys up, and is refused rather than silently ignored.
 *
 * @param kind {@code LOYALTY_EARN}, {@code LOYALTY_REDEEM}, {@code LOYALTY_ADJUST} or {@code
 *     STORE_CREDIT_ISSUE}
 * @param customerId the customer credited or debited
 * @param amount points or money; signed for an adjustment
 * @param currency store credit's currency, else null
 * @param reason why, as the person said it
 * @param actorId the signed-in user; null only when a service made the call
 * @param idempotencyKey the request's key in canonical form, null when the call carries none
 */
public record ManualGrant(
    String kind,
    UUID customerId,
    BigDecimal amount,
    String currency,
    String reason,
    UUID actorId,
    String idempotencyKey) {

  public static final String KIND_LOYALTY_EARN = "LOYALTY_EARN";
  public static final String KIND_LOYALTY_REDEEM = "LOYALTY_REDEEM";
  public static final String KIND_LOYALTY_ADJUST = "LOYALTY_ADJUST";
  public static final String KIND_STORE_CREDIT_ISSUE = "STORE_CREDIT_ISSUE";

  /**
   * Whether {@code other} asks for the same thing this one did.
   *
   * @param other the request seen again under the same key
   * @return true for a retry; false for a different request
   */
  public boolean sameRequestAs(ManualGrant other) {
    return kind.equals(other.kind)
        && customerId.equals(other.customerId)
        && amount.compareTo(other.amount) == 0
        && java.util.Objects.equals(currency, other.currency);
  }
}
