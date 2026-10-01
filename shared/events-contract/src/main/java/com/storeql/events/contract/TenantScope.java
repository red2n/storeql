package com.storeql.events.contract;

/** Whether an event names a business. */
public enum TenantScope {
  /** Always carries {@code tenantId}. */
  REQUIRED,
  /** Carries {@code tenantId} unless the subject belongs to no business (platform scope). */
  OPTIONAL,
  /**
   * A platform-scope event: never carries {@code tenantId} (it may name a business in another
   * member).
   */
  NONE
}
