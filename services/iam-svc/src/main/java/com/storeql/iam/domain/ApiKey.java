package com.storeql.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A business's API key (22.7): a credential its systems present instead of a person's sign-in,
 * acting in one staff tier for some stores or all.
 *
 * @param prefix the first characters of the key, what the owner tells keys apart by
 * @param keyHash the SHA-256 of the whole key; the key itself is never kept
 * @param role the tier it acts in: one of {@link #TIERS}, never OWNER
 * @param storeIds the stores it may work in; empty for every store
 * @param expiresAt the moment it stops, or null for until revoked
 * @param lastUsedAt the last request it made, to the minute
 */
public record ApiKey(
    UUID id,
    UUID tenantId,
    /** The live business that made the key and may revoke it; the tenant itself for a live key. */
    UUID ownerTenantId,
    /** Whether it acts in the business's sandbox (22.8): {@code sqk_test_}, and never live data. */
    boolean sandbox,
    String name,
    String prefix,
    String keyHash,
    String role,
    List<UUID> storeIds,
    UUID createdBy,
    Instant createdAt,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant revokedAt,
    UUID revokedBy) {

  /** What a key may act as: a staff tier, never an owner and never the platform. */
  public static final Set<String> TIERS = Set.of("MANAGER", "STOREKEEPER", "CASHIER");

  /** Every key starts with this, so the gateway knows a key from a token at a glance. */
  public static final String PREFIX = "sqk_";

  /**
   * A sandbox key starts with this instead (22.8): told apart at a glance, in a log or a config.
   */
  public static final String TEST_PREFIX = "sqk_test_";

  /** The key: the prefix and 40 characters of 30 random bytes. */
  public static final int LENGTH = 44;

  /** A sandbox key: its longer prefix and the same 40 characters. */
  public static final int TEST_LENGTH = 49;

  /** How many characters of it are shown on the list. */
  public static final int SHOWN = 12;

  public ApiKey {
    storeIds = List.copyOf(storeIds);
  }

  public boolean revoked() {
    return revokedAt != null;
  }

  public boolean expired(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }

  /** Whether a string is even shaped like a key, before anything is looked up. */
  public static boolean looksLikeKey(String text) {
    if (text == null) return false;
    return (text.length() == LENGTH && text.startsWith(PREFIX))
        || (text.length() == TEST_LENGTH && text.startsWith(TEST_PREFIX));
  }
}
