package com.storeql.payment.domain;

/**
 * What waits for a person in payments, for one business as a whole (the system-health screen).
 *
 * @param cardRefundDuesNeedingAttention money owed back to a card that the card machine could not
 *     put back (unreachable, refused, or no answer) and a manager has yet to settle; counted to at
 *     most {@link com.storeql.web.PendingWorkCount#CAP}, which means "that many or more"
 */
public record PendingWork(long cardRefundDuesNeedingAttention) {}
