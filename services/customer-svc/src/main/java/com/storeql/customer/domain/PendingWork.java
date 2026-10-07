package com.storeql.customer.domain;

/**
 * What waits for a person in customer records, for one business as a whole (the system-health
 * screen).
 *
 * @param privacyRequestsOpen privacy requests (access, correction, erasure, nomination, grievance)
 *     opened by a customer and not yet resolved or refused, read up to {@link
 *     com.storeql.web.PendingWorkCount#CAP}: a count equal to the cap means that many or more
 */
public record PendingWork(long privacyRequestsOpen) {}
