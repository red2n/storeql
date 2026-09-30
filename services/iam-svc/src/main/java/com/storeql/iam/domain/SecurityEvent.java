package com.storeql.iam.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One row of iam-svc's append-only audit log as a person may read it. The pure rule of what is safe
 * to show lives here: the audit log's free-text {@code detail} is a mixture — an address typed at a
 * sign-in, an API key's id and prefix, an identity provider's issuer, an actor's id — so only the
 * codes whose detail is known to be safe for a business's own owner or manager carry it; every
 * other action's detail (an address, anything unknown) is left out. The audit log holds no IP
 * address, secret, hash, token or reset link, and none is ever added here.
 *
 * @param id the audit row's id (UUIDv7: newest sorts last)
 * @param type the machine action code, e.g. {@code MFA_LOCKED}
 * @param userId the login the event concerns, or null
 * @param email that login's current address, or null (a deleted account has none)
 * @param tenantId the business the login belongs to, or null (a shopper's, or the platform's)
 * @param detail the safe part of the free text, or null
 * @param at when it happened (UTC)
 */
public record SecurityEvent(
    UUID id, String type, UUID userId, String email, UUID tenantId, String detail, Instant at) {

  /** Actions whose {@code detail} carries nothing but the method, name, code or id it names. */
  private static final Set<String> DETAIL_SAFE =
      Set.of(
          "MFA_LOGIN_FAILED",
          "MFA_LOGIN_OK",
          "MFA_FACTOR_REMOVED",
          "MFA_PASSKEY_ADDED",
          "MFA_POLICY_CHANGED",
          "API_KEY_CREATED",
          "API_KEY_REVOKED",
          "SSO_CONNECTION_CHANGED",
          "SSO_LINKED",
          "SSO_UNLINKED",
          "SSO_LOGIN_OK",
          "SSO_LOGIN_PROVED",
          "SSO_LOGIN_REFUSED",
          "SANDBOX_ENTERED",
          "REFRESH_REUSE_DETECTED");

  /**
   * The detail a reader may see for this action.
   *
   * @return {@code detail} for an allow-listed action, null for any other
   */
  public static String visibleDetail(String action, String detail) {
    return action != null && DETAIL_SAFE.contains(action) ? detail : null;
  }
}
