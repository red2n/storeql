package com.storeql.iam.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response DTOs for iam-svc. DTOs are the API contract (golden rule #10). No tenant fields
 * in requests.
 *
 * <p>Ids are carried as {@code String} rather than {@code UUID} so the JSON contract stays stable.
 * Bean Validation annotations on the request records are what {@code Validations.validate} enforces
 * at the boundary.
 */
public final class Dtos {

  private Dtos() {}

  /** Customer self-signup. */
  @Schema(name = "RegisterRequest", description = "Customer self-signup.")
  public record RegisterRequest(
      @Schema(description = "Unique login email.") @Email @NotBlank String email,
      @Schema(description = "Plaintext password (hashed server-side before storage).")
          @NotBlank
          @Size(min = 8, max = 128)
          String password,
      @Schema(description = "Optional contact phone number.") String phone) {}

  /**
   * Business sign-up ("Start a business"): the login a new business will be run with. It carries no
   * tenant and no role — the business is created next, through tenant-svc's onboarding, and binds
   * this login as its owner. Nothing here names a business: which one the login belongs to is never
   * the caller's to say.
   */
  @Schema(
      name = "BusinessRegisterRequest",
      description =
          "Business sign-up: a staff login with no business and no role yet. The business is"
              + " created next (tenant-svc POST /onboarding), which makes this login its owner.")
  public record BusinessRegisterRequest(
      @Schema(description = "Unique login email.") @Email @NotBlank String email,
      @Schema(description = "Plaintext password (hashed server-side before storage).")
          @NotBlank
          @Size(min = 8, max = 128)
          String password,
      @Schema(
              description =
                  "Optional contact phone number, kept as the shopper's sign-up keeps one. Unique"
                      + " among business sign-ups of no business; a shopper's account with the same"
                      + " number is a separate identity and does not count.")
          String phone) {}

  /**
   * Admin provisions a staff account by email (find-or-create within the business). The admin
   * supplies the initial password and shares it with the new staff member out-of-band; it is never
   * echoed back in the response.
   */
  @Schema(
      name = "ProvisionStaffRequest",
      description =
          "Admin find-or-create of a staff account by email, within the caller's business: the"
              + " business's own login with this email, or a new one made in the business. A"
              + " shopper's account, an unfinished business sign-up or another business's login"
              + " with the same email is never taken over. tenantId is taken from the caller's"
              + " JWT, never from this body.")
  public record ProvisionStaffRequest(
      @Schema(description = "Staff member's login email.") @Email @NotBlank String email,
      @Schema(
              description =
                  "Initial password, shared with the new staff member out-of-band. Never echoed"
                      + " back in the response.")
          @NotBlank
          @Size(min = 8, max = 128)
          String password) {}

  /** Result of staff provisioning: the userId to assign a store role to. */
  @Schema(
      name = "ProvisionStaffResponse",
      description = "The userId to assign a store role to via tenant-svc.")
  public record ProvisionStaffResponse(
      @Schema(description = "UUID of the staff user.") String userId,
      String email,
      @Schema(
              description =
                  "True if a new login was made in the business; false if the business already"
                      + " had one with this email.")
          boolean created) {}

  /** One of the business's staff, named: what {@code GET /auth/admin/staff-users} answers. */
  @Schema(
      name = "StaffUserResponse",
      description =
          "A login of the caller's business's staff, by id and email. Ids of another business's"
              + " staff, of customers and of nobody are left out of the answer.")
  public record StaffUserResponse(
      @Schema(description = "UUID of the staff user.") String userId,
      @Schema(description = "The login email.") String email) {}

  /**
   * Login with email + password.
   *
   * <p>{@code accountType} says where the person is signing in, because one address may hold a
   * shopper's account and a business account (separate identities, 29 Sep 2026): the storefront
   * sends {@code CUSTOMER}; the admin console and the till send {@code STAFF}, which is also what
   * no value means. The platform console's own sign-in ignores it.
   */
  @Schema(name = "LoginRequest")
  public record LoginRequest(
      @Email @NotBlank String email,
      @NotBlank String password,
      @Schema(
              description =
                  "Which account to sign in to when the address holds a shopper's and a"
                      + " business's: CUSTOMER from a storefront, STAFF (the default) to run a"
                      + " business. The other kind is signed in only when the address holds none"
                      + " of this one.",
              enumeration = {"CUSTOMER", "STAFF"})
          @Pattern(regexp = "CUSTOMER|STAFF")
          String accountType) {}

  // ── Forgotten password (public — no sign-in) ──────────────────────────────

  /** {@code GET /auth/password-policy}: the published rules, before anyone types a password. */
  @Schema(
      name = "PasswordPolicyResponse",
      description = "The password rules in force, so a form can show them before anyone types.")
  public record PasswordPolicyResponse(
      @Schema(description = "Fewest characters accepted.") int minLength,
      @Schema(description = "Most characters accepted.") int maxLength,
      @Schema(description = "Whether a password is screened against known data breaches.")
          boolean breachScreened,
      @Schema(description = "Always true: a password must not be, or contain, the login.")
          boolean mustNotContainLogin) {}

  /** Ask for a password reset link. Public — the same answer whatever the address. */
  @Schema(
      name = "ForgotPasswordRequest",
      description = "Ask for a password reset link. Answered the same whatever the address.")
  public record ForgotPasswordRequest(
      @Schema(description = "The login email every eligible account with it is reset by.")
          @Email
          @NotBlank
          @Size(max = 254)
          String email,
      @Schema(
              description =
                  "ISO 639 language code, [a-z]{2,3}. Anything else, or none, reads as English.")
          String language) {}

  /** What {@code POST /auth/password/forgot} always answers, whatever the address. */
  @Schema(name = "ForgotPasswordResponse")
  public record ForgotPasswordResponse(@Schema(description = "Always true.") boolean accepted) {}

  /** Spend a password reset link. Public — the token from the link is the proof. */
  @Schema(name = "ResetPasswordRequest", description = "Spend a password reset link.")
  public record ResetPasswordRequest(
      @Schema(description = "The token from the reset link.") @NotBlank String token,
      @Schema(description = "The new password, checked against the published policy.") @NotBlank
          String newPassword) {}

  /** What {@code POST /auth/password/reset} answers on success. */
  @Schema(name = "ResetPasswordResponse")
  public record ResetPasswordResponse(@Schema(description = "Always true.") boolean reset) {}

  /** Refresh access token. */
  @Schema(name = "RefreshRequest")
  public record RefreshRequest(
      @Schema(description = "A previously issued, still-valid refresh token.") @NotBlank
          String refreshToken) {}

  /** Logout / revoke a refresh token. */
  @Schema(name = "LogoutRequest")
  public record LogoutRequest(
      @Schema(description = "The refresh token to revoke.") @NotBlank String refreshToken) {}

  /**
   * What a sign-in answers: a token pair — or, when the password was right and a second factor is
   * owed (20.12), what to do next instead of one.
   */
  @Schema(
      name = "TokenResponse",
      description =
          "Access/refresh token pair. When mfaRequired is true there are no tokens yet: answer the"
              + " second factor at POST /auth/mfa/login with mfaToken. When mfaEnrolmentRequired is"
              + " true the access token is good only for setting a second factor up"
              + " (/auth/mfa/**), which then answers with the real pair.")
  public record TokenResponse(
      @Schema(description = "Short-lived JWT used as the Authorization: Bearer credential.")
          String accessToken,
      @Schema(description = "Long-lived token used to mint a new access token via /auth/refresh.")
          String refreshToken,
      @Schema(description = "Always \"Bearer\".") String tokenType,
      @Schema(description = "Access token lifetime in seconds from issuance.")
          Long expiresInSeconds,
      @Schema(description = "True when the password was right and a second factor is owed.")
          Boolean mfaRequired,
      @Schema(description = "Names the waiting sign-in at POST /auth/mfa/login. Minutes, not days.")
          String mfaToken,
      @Schema(description = "The second factors this login can answer with.")
          java.util.List<String> mfaMethods,
      @Schema(
              description =
                  "True when this login must have a second factor and has none: the access token"
                      + " only reaches /auth/mfa/**.")
          Boolean mfaEnrolmentRequired) {

    public TokenResponse {
      mfaMethods = mfaMethods == null ? null : java.util.List.copyOf(mfaMethods);
    }

    /**
     * Builds a {@code Bearer} token pair.
     *
     * @param access the short-lived access token
     * @param refresh the long-lived refresh token
     * @param ttl the access token's lifetime in seconds from issuance
     * @return the response with {@code tokenType} fixed to {@code Bearer}
     */
    public static TokenResponse bearer(String access, String refresh, long ttl) {
      return new TokenResponse(access, refresh, "Bearer", ttl, null, null, null, null);
    }

    /** The password was right; a second factor is owed before any token exists. */
    public static TokenResponse secondFactorOwed(String mfaToken, java.util.List<String> methods) {
      return new TokenResponse(null, null, null, null, true, mfaToken, methods, null);
    }

    /** A token that can only set a second factor up, for a login that must have one. */
    public static TokenResponse enrolmentOwed(String access, long ttl) {
      return new TokenResponse(access, null, "Bearer", ttl, null, null, null, true);
    }
  }

  @Schema(
      name = "SandboxTokenResponse",
      description =
          "A token for the business's sandbox (22.8): names the sandbox as its tenant, an owner"
              + " there, amr [sandbox], no refresh token.")
  public record SandboxTokenResponse(
      String accessToken,
      @Schema(description = "Always \"Bearer\".") String tokenType,
      Long expiresInSeconds,
      @Schema(description = "The sandbox tenant the token names.") String tenantId) {}

  /** The account holder confirming, with their password, that the account should be deleted. */
  @Schema(name = "DeleteAccountRequest")
  public record DeleteAccountRequest(
      @Schema(description = "The account's password, re-verified before deletion.") @NotBlank
          String password) {}

  /** Change password (authenticated user only). */
  @Schema(name = "ChangePasswordRequest")
  public record ChangePasswordRequest(
      @Schema(description = "The user's current password, re-verified before the change.") @NotBlank
          String currentPassword,
      @Schema(description = "The new password to set.") @NotBlank @Size(min = 8, max = 128)
          String newPassword) {}

  /** Current principal (GET /auth/me). */
  @Schema(name = "MeResponse", description = "The authenticated caller's identity and roles.")
  public record MeResponse(
      String userId,
      @Schema(description = "Null for platform-admin users, who are not tenant-scoped.")
          String tenantId,
      @Schema(description = "CUSTOMER, STAFF, or PLATFORM_ADMIN.") String type,
      @Schema(description = "Role names granted to this user, e.g. OWNER, MANAGER, PLATFORM_ADMIN.")
          java.util.List<String> roles,
      @Schema(
              description =
                  "The permissions this login holds (20.10): the token's own claim when a custom"
                      + " role narrowed it, else the defaults of the roles held. An owner holds"
                      + " every permission.")
          java.util.List<String> permissions,
      String email,
      String phone,
      @Schema(description = "Account status, e.g. ACTIVE, DISABLED.") String status,
      String createdAt) {}

  // ── Gap #45: POS session idle timeout ─────────────────────────────────────

  @Schema(name = "StartPosSessionRequest")
  public record StartPosSessionRequest(
      @Schema(description = "UUID of the store this cashier session is opened at.") @NotBlank
          String storeId,
      @Schema(description = "Idle timeout override in seconds; falls back to the store default.")
          Integer idleTimeoutSeconds) {}

  @Schema(name = "PosSessionResponse")
  public record PosSessionResponse(
      String id,
      String tenantId,
      String userId,
      String storeId,
      String startedAt,
      @Schema(description = "Timestamp of the last recorded activity heartbeat.")
          String lastActivityAt,
      @Schema(description = "Null while the session is still open.") String endedAt,
      int idleTimeoutSeconds,
      @Schema(description = "ACTIVE, ENDED, or EXPIRED.") String status) {}

  @Schema(name = "IdleSweepResult", description = "Result of a POS idle-timeout sweep.")
  public record IdleSweepResult(
      @Schema(description = "Number of sessions force-expired by this sweep.")
          int sessionsExpired) {}
}
