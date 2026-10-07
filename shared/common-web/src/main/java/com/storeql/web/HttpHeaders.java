package com.storeql.web;

/**
 * Internal header names the gateway injects downstream after validating the JWT, and the request-id
 * header.
 *
 * <p>Business services trust these because traffic only reaches them through the gateway (golden
 * rule #2). The gateway is responsible for stripping any client-supplied copies of these headers on
 * the way in.
 */
public final class HttpHeaders {

  private HttpHeaders() {}

  /** Correlation id propagated from the gateway through every hop and into events. */
  public static final String REQUEST_ID = "X-Request-Id";

  /**
   * The stable code of an error answer, also in its body. A header so the gateway can record what
   * went wrong with a request without reading, or buffering, a body it only relays.
   */
  public static final String ERROR_CODE = "X-Error-Code";

  /** Tenant id extracted from the verified JWT by the gateway. */
  public static final String TENANT_ID = "X-Tenant-Id";

  /** User id extracted from the verified JWT by the gateway. */
  public static final String USER_ID = "X-User-Id";

  /**
   * Email address extracted from the verified JWT by the gateway — the caller's own, never a
   * subject they named. A service needs it to reach the person behind a login: customer-svc matches
   * a shopper's login to the shop's customer record with it (SJ-D44), and without it an online
   * order belongs to nobody the shop can email, credit or erase.
   */
  public static final String USER_EMAIL = "X-User-Email";

  /** Comma-separated roles extracted from the verified JWT by the gateway. */
  public static final String ROLES = "X-Roles";

  /**
   * Comma-separated store ids the caller may operate in, extracted from the verified JWT's {@code
   * storeIds} claim. Absent (no header at all) means unrestricted — a tenant-wide role like
   * OWNER/PLATFORM_ADMIN — never an empty-but-present value.
   */
  public static final String STORE_IDS = "X-Store-Ids";

  /**
   * The permissions the caller's token carries (20.10), comma-separated; {@code -} when the token
   * names none. Absent when the token carries no permission claim at all, in which case a service
   * judges the caller by the defaults of their roles. Stamped by the gateway from the verified
   * token, never trusted from a client.
   */
  public static final String PERMISSIONS = "X-Permissions";

  /**
   * What a token is good for when it is not good for everything (20.12): {@code mfa-enrol} marks a
   * sign-in that owes a second factor it has yet to set up. The gateway stamps it from the token's
   * {@code scope} claim and lets such a token reach the second-factor routes and nothing else.
   */
  public static final String AUTH_SCOPE = "X-Auth-Scope";

  /** The scope of a token that may only set up a second factor. */
  public static final String SCOPE_MFA_ENROL = "mfa-enrol";

  /**
   * How the caller's session was authenticated, comma-separated, as the token's {@code amr} claim
   * says (20.12, and 20.x SSO): {@code pwd} for a password, {@code sso} for a business's identity
   * provider, then {@code otp}, {@code hwk} or {@code mfa} for a second factor. Stamped by the
   * gateway from the verified token, never trusted from a client. iam-svc reads it when a sign-in
   * that owed a second factor sets one up, so the session it ends in records how it began.
   */
  public static final String AUTH_METHODS = "X-Auth-Methods";

  /**
   * The caller's own signed-in session, as the access token's {@code sid} claim names it. Stamped
   * by the gateway from the verified token, never trusted from a client. iam-svc reads it to mark
   * which of a person's sessions is the one asking.
   */
  public static final String SESSION_ID = "X-Session-Id";

  /** Idempotency key for retryable writes (checkout, payment capture, stock receipt). */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /**
   * The key a network's provider presents when it delivers a supplier's e-invoice into the platform
   * (07.13, the transport seam). Client-controlled, like the idempotency key: purchase-svc holds it
   * against the deployment's own before it reads a byte of the document.
   */
  public static final String EINVOICE_KEY = "X-EInvoice-Key";

  /** The network's own reference for a delivery, kept beside the document it delivered. */
  public static final String EINVOICE_REFERENCE = "X-EInvoice-Reference";
}
