package com.storeql.notification.service;

import java.util.Optional;
import java.util.UUID;

/** What a message needs to know about the business sending it. */
public interface Businesses {

  /** Its country, ISO 3166 alpha-2, which decides how amounts and dates are written. */
  Optional<String> country(UUID tenantId);

  /** The name it trades under, which signs its messages. */
  Optional<String> name(UUID tenantId);

  /**
   * Whether it is a sandbox (22.8): a stand-in for a live business where no message may reach a
   * real person. False when unknown — an outage never silences a live business.
   */
  boolean sandbox(UUID tenantId);

  /**
   * Its home trading currency, ISO 4217: what a Catalogue sample or preview shows an amount in,
   * never a hard-coded GBP. Default empty so a test double written before this existed still
   * compiles; {@link TenantBusinesses} reads the real one from {@code TenantProfiles}.
   */
  default Optional<String> currency(UUID tenantId) {
    return Optional.empty();
  }
}
