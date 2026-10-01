package com.storeql.iam.service;

import com.storeql.iam.auth.JwtService;
import com.storeql.iam.auth.Passwords;
import com.storeql.iam.auth.Tokens;
import com.storeql.iam.client.MqttSessionRevoker;
import com.storeql.iam.config.ServiceConfig;
import com.storeql.iam.domain.PasswordReset;
import com.storeql.iam.domain.TokenIdentity;
import com.storeql.iam.domain.User;
import com.storeql.iam.dto.Dtos.ProvisionStaffResponse;
import com.storeql.iam.dto.Dtos.TokenResponse;
import com.storeql.iam.repo.RefreshTokenRepository;
import com.storeql.iam.repo.SsoRepository;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantProfiles;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core authentication logic: register, login, refresh, logout. The brain of iam-svc (golden rule
 * #9).
 *
 * <p>On register, the user row and the {@code UserRegistered} outbox event are written in one
 * transaction (golden rule #6). Refresh tokens are opaque + rotated on every refresh; only their
 * hash is stored.
 */
@ApplicationScoped
public class AuthService {

  @Inject ServiceConfig config;
  @Inject Passwords passwords;
  @Inject PasswordPolicy policy;
  @Inject JwtService jwt;
  @Inject UserRepository users;
  @Inject RefreshTokenRepository refreshTokens;
  @Inject MqttSessionRevoker mqttSessions;
  @Inject com.storeql.iam.config.ClientInfo clientInfo;
  @Inject TenantStatusRepository tenantStatus;
  @Inject MfaService mfa;
  @Inject SsoRepository sso;
  @Inject TenantProfiles tenantProfiles;

  /** RFC 8176's name for a password. */
  public static final String AMR_PASSWORD = "pwd";

  /**
   * A business's identity provider vouched for the person (20.x, SSO). RFC 8176 registers no value
   * for "an identity provider signed them in", and OpenID Connect Core §2 allows values outside the
   * register; this one is ours.
   */
  public static final String AMR_SSO = "sso";

  /** RFC 8176's multiple-factor authentication: what a provider says when it asked for two. */
  public static final String AMR_MFA = "mfa";

  /**
   * Customer self-signup → creates a CUSTOMER (global, tenantId null) and returns a token pair. An
   * address or phone is refused only when another shopper's login holds it (uq_users_unbound_email,
   * uq_users_unbound_phone): the same person's business account is a separate identity.
   */
  public TokenResponse register(String email, String password, String phone) {
    policy.check(password, email);
    String hash = passwords.hash(password);
    UUID userId = Ids.newId();
    Instant now = Instant.now();
    var user =
        new User(
            userId, null, User.TYPE_CUSTOMER, email, phone, hash, User.STATUS_ACTIVE, now, now);

    users.createUserWithOutbox(user, "CUSTOMER", userRegistered(user));
    users.audit(null, userId, "USER_REGISTERED", email);

    return issueTokens(user, PASSWORD_ONLY, Instant.now());
  }

  /**
   * Business sign-up ("Start a business"): a STAFF login that belongs to no business yet and holds
   * no role, with its first token pair.
   *
   * <p>The business is created next, by tenant-svc's onboarding, whose {@code TenantCreated} makes
   * this login its OWNER here. Until then the token names no tenant and no role — which is what the
   * app reads as "set the business up" and opens its wizard for — and every business's data is
   * closed to it. A shopper's sign-up ({@link #register}) is untouched: it stays a CUSTOMER.
   *
   * <p>The same password policy as a shopper's sign-up, and its own duplicate refusal: a shopper's
   * account and a business account are separate identities (29 Sep 2026), so an address or phone a
   * person already shops with is free here, while one another business sign-up of no business
   * already holds — or the platform administrator — is {@code 409 USER_ALREADY_EXISTS}
   * (uq_users_unbound_email, uq_users_unbound_phone). It is written with its {@code UserRegistered}
   * (type STAFF) on one transaction, as {@link #register} is.
   *
   * @param phone optional, kept exactly as {@link #register} keeps a shopper's: the platform
   *     console's assisted onboarding records the owner's with it, and the two sign-ups must not
   *     read one number two ways
   */
  public TokenResponse registerBusiness(String email, String password, String phone) {
    User user = newStaffLogin(null, email, phone, password);
    // No role row: a login that is to own a business holds nothing until the business exists, and
    // OWNER is granted by the TenantCreated handler (bindOwnerOnce), never by this request.
    users.createUserWithOutbox(user, null, userRegistered(user));
    users.audit(null, user.id(), "BUSINESS_SIGNED_UP", email);
    return issueTokens(user, PASSWORD_ONLY, Instant.now());
  }

  /**
   * Admin-driven staff provisioning: find this business's login by email or make one, returning the
   * userId the caller (tenant-svc) then assigns a store role to. The role is bound asynchronously
   * when tenant-svc publishes {@code StaffAssigned}; here we only ensure the login exists so the
   * admin never has to know a UUID. This is the only way a login becomes a business's staff: {@code
   * StaffAssigned} binds a role only to a login already in the business, and stamps nobody.
   *
   * <ul>
   *   <li>Email already this business's → reuse that login (one provisioned before and never
   *       assigned, or let go of its last store, comes back the same).
   *   <li>Otherwise → a new STAFF login, made already in this business, with the given password.
   * </ul>
   *
   * <p>Only this business's logins are ever read (tenant first), and nobody else's is ever touched
   * (29 Sep 2026). A shopper's account is the person's own and a separate identity from any job; a
   * business sign-up not yet onboarded is someone's own business to be, which a stranger adding the
   * address as staff first must not be able to capture; and another business's login is that
   * business's. All are left exactly as they were, beside the new login.
   *
   * <p>Nor does another business's login with the address refuse the request, as {@code 409
   * EMAIL_IN_OTHER_TENANT} once did, when provisioning adopted the login it found and had to stop
   * short of poaching one. Now it poaches nothing, and the refusal only let whichever business
   * added an address first hold it against every other: a login provisioned and never assigned —
   * refused by a plan's staff limit, say — stranded the person out of every other job, and a
   * hostile business could squat any address with one call. A person may work for several
   * businesses, each with its own login, as at Square and Shopify.
   */
  public ProvisionStaffResponse provisionStaff(UUID tenantId, String email, String rawPassword) {
    var ours = users.findByEmail(tenantId, email);
    if (ours.isPresent()) {
      return provisionedBefore(tenantId, ours.get(), email);
    }
    User user = newStaffLogin(tenantId, email, null, rawPassword);
    UUID userId = user.id();
    // No role yet — "STAFF" is a user `type`, not a row in `roles`; the real store-scoped role
    // (MANAGER/CASHIER/...) is granted when tenant-svc publishes StaffAssigned (see bindStaffOnce).
    try {
      users.createUserWithOutbox(user, null, userRegistered(user));
    } catch (ApiException e) {
      // Two requests adding one address at once — a button pressed twice: the business holds one
      // login per address (uq_users_business_email), and the one that won is the answer to both.
      if (e.status() != 409) throw e;
      return users
          .findByEmail(tenantId, email)
          .map(won -> provisionedBefore(tenantId, won, email))
          .orElseThrow(() -> e);
    }
    users.audit(tenantId, userId, "STAFF_PROVISIONED", email);
    return new ProvisionStaffResponse(userId.toString(), email, true);
  }

  /** The business's own login for the address, found again: nothing about it changes. */
  private ProvisionStaffResponse provisionedBefore(UUID tenantId, User login, String email) {
    users.audit(tenantId, login.id(), "STAFF_PROVISIONED_REUSE", email);
    return new ProvisionStaffResponse(login.id().toString(), email, false);
  }

  /**
   * Login with email + password for tenant staff, POS, and customers. An address may name several
   * logins — one per business it works for, and outside any business one shopper's account and one
   * business account — so the kind is chosen first and the password then says which of that kind.
   *
   * <p>The kind is where the person is signing in ({@code accountType}): a storefront asks for the
   * shopper's account (CUSTOMER), the admin console and the till for the business's (STAFF). No
   * value means STAFF: every caller that runs a business — the app's admin and POS shells, API
   * clients, scripts — signs in here without needing to say, while the storefront is the one place
   * that signs a shopper in and says so; and a storefront that forgot would get a business token
   * its app refuses to use, never a shopper's session silently standing in for the business. A
   * sign-in that names a kind opens only that kind ({@link User#ofKind}); one that names none tries
   * STAFF first and the other kind only when the address holds no staff login ({@link
   * User#signInCandidates}), as before.
   *
   * <p>PLATFORM_ADMIN accounts are deliberately excluded here — the platform admin is a separate
   * identity from any store/tenant, so it must not be a valid credential on a store-scoped login
   * screen (admin console, POS). It authenticates only via {@link #platformLogin}. Its password
   * opening it here is refused and audited, and the address's other logins are still tried, since
   * the same person may also work for a business under their own login.
   *
   * @param accountType {@code CUSTOMER} or {@code STAFF}; null means STAFF
   */
  public TokenResponse login(String email, String password, String accountType) {
    // A sign-in that says what it is (CUSTOMER or STAFF) opens only that kind: a shopper's sign-in
    // never returns a staff token nor a staff sign-in a shopper's, and an address holding only the
    // other kind is answered as an unknown one (below: same Argon2 work, same 401). One that
    // says nothing keeps the older behaviour: STAFF first, the other kind only when none exists.
    var logins = users.findAllByEmail(email);
    var candidates =
        accountType == null
            ? User.signInCandidates(logins, User.TYPE_STAFF)
            : User.ofKind(logins, accountType);
    boolean audited = false;
    for (User user : candidates) {
      if (!User.STATUS_ACTIVE.equals(user.status())) {
        // Burn the same Argon2 cost a real verify would pay, so a non-ACTIVE (e.g. SUSPENDED)
        // account doesn't answer faster than an ACTIVE one with a wrong password — same
        // timing-oracle concern as the unknown-email case below.
        passwords.burn(password);
        continue;
      }
      if (passwords.verify(user.passwordHash(), password)) {
        if (users.rolesOf(user.id()).contains("PLATFORM_ADMIN")) {
          users.audit(user.tenantId(), user.id(), "LOGIN_FAILED", email);
          audited = true;
          continue;
        }
        // A staff user whose tenant has been deactivated must not be able to log in, even with the
        // right password and an ACTIVE user row. (Customers carry tenantId=null and are
        // unaffected.)
        if (user.tenantId() != null && !tenantStatus.isActive(user.tenantId())) {
          users.audit(user.tenantId(), user.id(), "LOGIN_BLOCKED_TENANT_INACTIVE", email);
          throw ApiException.forbidden(
              "TENANT_INACTIVE", "This business account is suspended. Contact support.");
        }
        // A business that signs these staff in through its identity provider (20.x, SSO): the
        // password is right and still does not sign them in — switching someone off at the provider
        // must switch them off here. Said only after the password was right, so it tells nobody
        // anything about an account they could not already sign into.
        refuseIfSsoRequired(user);
        users.audit(user.tenantId(), user.id(), "LOGIN_OK", email);
        return afterFirstFactor(user, PASSWORD_ONLY);
      }
    }
    if (candidates.isEmpty()) {
      // Equalize timing with the verify above so response time doesn't reveal whether the
      // email exists (account-enumeration oracle).
      passwords.burn(password);
    } else if (!audited) {
      User first = candidates.get(0);
      users.audit(first.tenantId(), first.id(), "LOGIN_FAILED", email);
    }
    throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
  }

  /**
   * Login for the platform console only. Mirrors {@link #login} but requires the PLATFORM_ADMIN
   * role — a tenant staff or customer account must not authenticate here, same generic error so
   * neither endpoint leaks which kind of account an email belongs to.
   */
  public TokenResponse platformLogin(String email, String password) {
    var candidates = users.findAllByEmail(email);
    for (User user : candidates) {
      if (!User.STATUS_ACTIVE.equals(user.status())) {
        // See login()'s identical timing-equalization comment.
        passwords.burn(password);
        continue;
      }
      if (passwords.verify(user.passwordHash(), password)
          && users.rolesOf(user.id()).contains("PLATFORM_ADMIN")) {
        users.audit(null, user.id(), "PLATFORM_LOGIN_OK", email);
        return afterFirstFactor(user, PASSWORD_ONLY);
      }
    }
    if (candidates.isEmpty()) {
      passwords.burn(password);
    } else {
      User first = candidates.get(0);
      users.audit(first.tenantId(), first.id(), "PLATFORM_LOGIN_FAILED", email);
    }
    throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
  }

  /** Rotate a refresh token → new access + new refresh token; old one is revoked. */
  public TokenResponse refresh(String refreshToken) {
    String hash = Tokens.hash(refreshToken);
    // Refuse a suspended tenant's staff before the token is spent. Spending it would leave them a
    // dead token whose every retry reads as theft below, revoking all their sessions and auditing
    // a reuse that never happened, when all that should happen is a refusal until reactivation.
    refreshTokens.ownerOfActive(hash).flatMap(users::findById).ifPresent(this::requireTenantActive);
    // Atomic consume: validate + revoke in one statement, so a token can be rotated exactly once
    // even under concurrent requests.
    RefreshTokenRepository.Session session =
        refreshTokens
            .consume(hash)
            .orElseThrow(
                () -> {
                  // Reuse of an already-revoked token is the classic stolen-token signal: either
                  // the attacker or the legitimate user holds a now-dead token. Revoke the whole
                  // session family so the holder of the stolen token is cut off too.
                  refreshTokens
                      .ownerOfRevoked(hash)
                      .ifPresent(
                          owner -> {
                            refreshTokens.revokeAllForUser(owner);
                            users.audit(
                                null,
                                owner,
                                "REFRESH_REUSE_DETECTED",
                                "revoked token presented - all sessions revoked");
                          });
                  return ApiException.unauthorized(
                      "INVALID_REFRESH", "Refresh token invalid or expired");
                });
    User user =
        users
            .findById(session.userId())
            .orElseThrow(
                () -> ApiException.unauthorized("INVALID_REFRESH", "User no longer exists"));
    // Again after the consume: the tenant may have been suspended since the check above.
    requireTenantActive(user);
    List<String> amr = amrOf(session.amr());
    Set<String> roles = users.rolesOf(user.id());
    // A business that has required a second factor since this session began: a session that was
    // only ever a password's is not renewed — its holder signs in again and is walked through
    // setting a factor up. Without this the rule would not bite for a fortnight.
    if (amr.size() < 2 && mfa.required(user.tenantId(), roles)) {
      throw ApiException.unauthorized(
          "MFA_REQUIRED", "A second factor is now required of this login: sign in again");
    }
    if (amr.contains(AMR_SSO)) {
      // The provider is asked again after a working day: until then this platform cannot know
      // whether the person was switched off there, and a fortnight of refreshes would let someone
      // who has left keep working.
      if (session.authenticatedAt() != null
          && session
              .authenticatedAt()
              .plusSeconds(config.ssoSessionMaxSeconds())
              .isBefore(Instant.now())) {
        users.audit(user.tenantId(), user.id(), "SSO_SESSION_EXPIRED", null);
        throw ApiException.unauthorized(
            "SSO_REAUTH_REQUIRED", "Sign in through your identity provider again");
      }
    } else if (ssoRequiredOf(user, roles).isPresent()) {
      // Required since this session began: the password session is not renewed.
      throw ApiException.unauthorized(
          "SSO_REQUIRED", "This business now signs its staff in through its identity provider");
    }
    return issueTokens(user, amr, session.authenticatedAt(), session);
  }

  /**
   * A first factor held: the password, or the business's identity provider (20.x, SSO). A provider
   * that says it asked for a second factor has proved both, and the person is in. Otherwise a login
   * that holds a second factor here owes it before any token exists; one that must hold one and
   * does not gets a token good only for setting one up; anyone else is in.
   *
   * @param proved what the sign-in proved, as the session will record it: {@code [pwd]}, {@code
   *     [sso]}, or {@code [sso, mfa]}
   */
  public TokenResponse afterFirstFactor(User user, List<String> proved) {
    if (proved.size() >= 2) {
      return issueTokens(user, proved, Instant.now());
    }
    String first = proved.get(0);
    List<String> methods = mfa.methods(user.id());
    if (!methods.isEmpty()) {
      return TokenResponse.secondFactorOwed(mfa.openLogin(user.id(), first), methods);
    }
    if (mfa.required(user.tenantId(), users.rolesOf(user.id()))) {
      users.audit(user.tenantId(), user.id(), "MFA_ENROLMENT_REQUIRED", null);
      return TokenResponse.enrolmentOwed(
          jwt.issueEnrolmentToken(user.id(), user.type(), user.email(), first),
          JwtService.ENROLMENT_TTL_SECONDS);
    }
    return issueTokens(user, proved, Instant.now());
  }

  /** The second factor of a waiting sign-in, judged; the token pair if it held. */
  public TokenResponse completeMfaLogin(com.storeql.iam.dto.MfaDtos.MfaLoginRequest req) {
    MfaService.Proved proved = mfa.verifyLogin(req);
    return issueAfterSecondFactor(proved.userId(), proved.first(), proved.amr());
  }

  /**
   * The token pair for a login that has just set up the factor it owed, or answered one: the
   * account and its business are checked again, because minutes have passed since the first factor.
   *
   * @param first what the sign-in proved before: {@code pwd} or {@code sso}
   * @param amr the second factor: {@code otp} or {@code hwk}
   */
  public TokenResponse issueAfterSecondFactor(UUID userId, String first, String amr) {
    User user =
        users
            .findById(userId)
            .filter(u -> User.STATUS_ACTIVE.equals(u.status()))
            .orElseThrow(
                () -> ApiException.unauthorized("INVALID_CREDENTIALS", "User no longer exists"));
    requireTenantActive(user);
    String proved = AMR_SSO.equals(first) ? AMR_SSO : AMR_PASSWORD;
    // The password rule is judged again here, not only at the password: a business may have
    // required its provider in the minutes a second factor was being set up.
    if (AMR_PASSWORD.equals(proved)) refuseIfSsoRequired(user);
    return issueTokens(user, List.of(proved, amr), Instant.now());
  }

  private static final List<String> PASSWORD_ONLY = List.of(AMR_PASSWORD);

  private static List<String> amrOf(String stored) {
    if (stored == null || stored.isBlank()) return PASSWORD_ONLY;
    return List.of(stored.split(","));
  }

  /** The business's connection, when it requires its provider of this login. */
  private java.util.Optional<com.storeql.iam.domain.Sso.Connection> ssoRequiredOf(
      User user, Set<String> roles) {
    if (user.tenantId() == null) return java.util.Optional.empty();
    return sso.connection(user.tenantId()).filter(c -> c.requiredOf(roles));
  }

  private void refuseIfSsoRequired(User user) {
    ssoRequiredOf(user, users.rolesOf(user.id()))
        .ifPresent(
            c -> {
              users.audit(user.tenantId(), user.id(), "LOGIN_REFUSED_SSO_REQUIRED", null);
              throw new ApiException(
                  403,
                  "SSO_REQUIRED",
                  "This business signs its staff in through its identity provider",
                  List.of("slug=" + c.slug()));
            });
  }

  /**
   * Block token refresh for a suspended tenant — otherwise a staff member with a live refresh token
   * could keep minting access tokens after their business was deactivated. Customers carry no
   * tenant and are unaffected.
   */
  private void requireTenantActive(User user) {
    if (user.tenantId() != null && !tenantStatus.isActive(user.tenantId())) {
      throw ApiException.forbidden(
          "TENANT_INACTIVE", "This business account is suspended. Contact support.");
    }
  }

  /**
   * Revoke a refresh token (logout) and, best-effort, kick the user's live MQTT push session (see
   * {@link MqttSessionRevoker}) so they stop receiving device pushes immediately rather than until
   * the access token naturally expires.
   */
  public void logout(String refreshToken) {
    refreshTokens
        .revoke(Tokens.hash(refreshToken))
        .flatMap(users::findById)
        .filter(user -> user.tenantId() != null)
        .ifPresent(user -> mqttSessions.revoke(user.tenantId(), user.id()));
  }

  /**
   * The caller's own live sign-ins, most recently used first.
   *
   * @param userId the caller's login, from the token
   * @param currentSessionId the session making the request (its token's {@code sid}), or null
   * @return its live sessions
   */
  public List<com.storeql.iam.dto.Dtos.SessionResponse> sessionsOf(
      UUID userId, UUID currentSessionId) {
    return refreshTokens.liveSessions(userId).stream()
        .map(
            s ->
                new com.storeql.iam.dto.Dtos.SessionResponse(
                    s.id().toString(),
                    s.deviceLabel() == null
                        ? com.storeql.iam.domain.ClientLabel.UNKNOWN
                        : s.deviceLabel(),
                    s.network(),
                    s.startedAt().toString(),
                    s.lastUsedAt().toString(),
                    s.amr() == null ? "pwd" : s.amr().replace(',', '+'),
                    s.id().equals(currentSessionId)))
        .toList();
  }

  /**
   * Signs one of the caller's own sessions out: its refresh chain is revoked, the login's push
   * session is kicked and the act is audited. An access token already issued lives out its minutes
   * (the gateway's deny list is the next step).
   *
   * @param userId the caller's login, from the token
   * @param sessionId the session to end
   * @throws ApiException 404 {@code SESSION_NOT_FOUND} when the login holds no such live session
   *     (another login's, an unknown one, one already ended)
   */
  public void endSession(UUID userId, UUID sessionId) {
    if (refreshTokens.endSession(userId, sessionId) == 0) {
      throw ApiException.notFound("SESSION_NOT_FOUND", "session not found");
    }
    users
        .findById(userId)
        .ifPresent(
            u -> {
              users.audit(u.tenantId(), userId, "SESSION_ENDED", sessionId.toString());
              if (u.tenantId() != null) mqttSessions.revoke(u.tenantId(), userId);
            });
  }

  /**
   * One-shot bootstrap: creates the first PLATFORM_ADMIN. Rejects if one already exists so the
   * endpoint is safe to leave enabled after first use.
   *
   * @param totpSecret the administrator's authenticator secret in base 32, or null (20.12). Given
   *     with the account, the most powerful login on the platform never exists with a password
   *     alone; left out, its first sign-in is made to set a factor up.
   */
  public UUID bootstrapAdmin(String email, String rawPassword, String totpSecret) {
    if (users.platformAdminExists()) {
      throw new ApiException(
          409,
          "BOOTSTRAP_ALREADY_DONE",
          "A PLATFORM_ADMIN already exists. Use the login endpoint.",
          java.util.List.of(),
          null);
    }
    // Judged before the account exists: a secret refused after it would leave behind exactly the
    // password-only administrator the secret is given to prevent.
    byte[] secondFactor =
        totpSecret == null || totpSecret.isBlank() ? null : mfa.parseSecret(totpSecret);
    User user = newStaffLogin(null, email, null, rawPassword);
    UUID userId = user.id();
    users.createPlatformAdmin(user);
    if (secondFactor != null) {
      mfa.provisionTotp(userId, secondFactor);
    }
    users.audit(null, userId, "PLATFORM_ADMIN_BOOTSTRAPPED", email);
    return userId;
  }

  /** Fetch a user by id — used by MeResource to resolve the current principal. */
  public User lookupUser(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User no longer exists"));
  }

  /** Change password for an authenticated user (requires current password). */
  public void changePassword(
      UUID userId, String currentPassword, String newPassword, String rawLanguage) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
    if (!passwords.verify(user.passwordHash(), currentPassword)) {
      throw ApiException.unauthorized("INVALID_CREDENTIALS", "Current password is incorrect");
    }
    policy.check(newPassword, user.email());
    // The "your password was changed" notice is written with the change itself, never for the
    // platform administrator (whose credentials the operator's bootstrap owns).
    boolean staff = user.tenantId() != null || !User.TYPE_CUSTOMER.equals(user.type());
    OutboxRow changed =
        PasswordChangedEvent.announces(
                user.email(), users.rolesOf(userId).contains("PLATFORM_ADMIN"))
            ? PasswordChangedEvent.row(
                userId,
                user.email(),
                staff,
                user.tenantId() != null
                    ? tenantProfiles.businessName(user.tenantId()).orElse(null)
                    : null,
                PasswordChangedEvent.VIA_CHANGE,
                PasswordReset.language(rawLanguage),
                Instant.now())
            : null;
    users.updatePassword(userId, passwords.hash(newPassword), changed);
    // Revoke every outstanding refresh token: a password change must invalidate sessions that
    // may have been established with the old (possibly compromised) credentials.
    refreshTokens.revokeAllForUser(userId);
    users.audit(user.tenantId(), userId, "PASSWORD_CHANGED", user.email());
  }

  /**
   * "Sign out everywhere": ends every renewable session of the caller's own login, the one they are
   * using included. Access tokens already issued live out their few minutes. Audited as {@code
   * SESSIONS_REVOKED_ALL}.
   *
   * @return how many sessions (refresh tokens still valid) were ended
   */
  public int revokeAllSessions(UUID userId) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
    int ended = refreshTokens.revokeLiveForUser(userId);
    users.audit(user.tenantId(), userId, "SESSIONS_REVOKED_ALL", null);
    return ended;
  }

  /**
   * The account holder deletes their own login (SJ-D43).
   *
   * <p>The password is asked for again: a session left signed in on a shared device must not be
   * enough to delete someone's account. A staff account is refused — it belongs to the business
   * that employs its holder, which removes it.
   */
  public void deleteAccount(UUID userId, String password) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
    if (!User.TYPE_CUSTOMER.equals(user.type())) {
      throw ApiException.forbidden(
          "ACCOUNT_MANAGED_BY_EMPLOYER",
          "A staff account is removed by the business that employs you, not deleted here");
    }
    if (!User.STATUS_ACTIVE.equals(user.status())
        || user.passwordHash() == null
        || !passwords.verify(user.passwordHash(), password)) {
      users.audit(null, userId, "ACCOUNT_DELETE_REFUSED", null);
      throw ApiException.unauthorized("INVALID_CREDENTIALS", "Password is incorrect");
    }
    // Ids only: the event outlives its handling, so it must not carry what it erases.
    String payload =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "AccountDeleted")
            .add("aggregateId", userId.toString())
            .add("occurredAt", Instant.now().toString())
            .build()
            .toString();
    users.deleteCustomerAccount(
        user,
        new OutboxRow("AccountDeleted", "storeql.iam.account-deleted", null, userId, payload));
    users.audit(null, userId, "ACCOUNT_DELETED", null);
  }

  // --- helpers ---

  /**
   * The {@code UserRegistered} outbox row for a login just made, written on the same transaction as
   * the login itself. It names the login's business — a provisioned member of staff is made in
   * theirs — or none, for a shopper's or a business sign-up's; its {@code type} says which kind of
   * login it is (CUSTOMER or STAFF).
   */
  private static OutboxRow userRegistered(User user) {
    var event =
        Json.createObjectBuilder()
            .add("eventId", Ids.newId().toString())
            .add("eventType", "UserRegistered");
    if (user.tenantId() == null) {
      event.addNull("tenantId");
    } else {
      event.add("tenantId", user.tenantId().toString());
    }
    String payload =
        event
            .add("aggregateId", user.id().toString())
            .add("occurredAt", user.createdAt().toString())
            .add("email", user.email())
            .add("type", user.type())
            .build()
            .toString();
    return new OutboxRow(
        "UserRegistered", "storeql.iam.user-registered", user.tenantId(), user.id(), payload);
  }

  /**
   * A staff login, its password checked against the policy.
   *
   * @param tenantId the business it is made in, or null for one that belongs to no business yet (a
   *     business sign-up, the platform administrator)
   * @param phone the business sign-up's optional phone, as given; null for every other staff login
   */
  private User newStaffLogin(UUID tenantId, String email, String phone, String rawPassword) {
    policy.check(rawPassword, email);
    Instant now = Instant.now();
    return new User(
        Ids.newId(),
        tenantId,
        User.TYPE_STAFF,
        email,
        phone,
        passwords.hash(rawPassword),
        User.STATUS_ACTIVE,
        now,
        now);
  }

  /**
   * @param authenticatedAt when the session was signed into, carried unchanged across refreshes;
   *     null for a session older than the record of it
   */
  private TokenResponse issueTokens(User user, List<String> amr, Instant authenticatedAt) {
    return issueTokens(user, amr, authenticatedAt, null);
  }

  /**
   * @param renewing the session being renewed, whose id and start carry over; null for a new
   *     sign-in, which begins a session of its own
   */
  private TokenResponse issueTokens(
      User user,
      List<String> amr,
      Instant authenticatedAt,
      RefreshTokenRepository.Session renewing) {
    UUID sessionId = renewing != null ? renewing.sessionId() : Ids.newId();
    Instant startedAt = renewing != null ? renewing.startedAt() : Instant.now();
    // Read again, and all at once (SJ-D63). The row in hand was read before the password was
    // checked, and that check takes long enough for a staff removal to commit meanwhile: the old
    // row's tenant beside the new roles made a token naming a business the login had just left.
    // An owner is never narrowed by a custom role, so the permission claim is omitted for one
    // whatever the rows say; anyone else carries it as soon as one of their roles is a custom one.
    TokenIdentity who =
        users
            .tokenIdentity(user.id())
            .orElseThrow(
                () -> ApiException.unauthorized("INVALID_CREDENTIALS", "User no longer exists"));
    Set<String> permissions =
        com.storeql.web.Permissions.unrestricted(who.roles()) ? null : who.permissions();
    String access =
        jwt.issueAccessToken(
            user.id(),
            who.tenantId(),
            who.type(),
            who.email(),
            who.roles(),
            who.storeIds(),
            permissions,
            amr,
            sessionId);

    String refresh = Tokens.newOpaqueToken();
    refreshTokens.store(
        user.id(),
        Tokens.hash(refresh),
        Instant.now().plusSeconds(config.refreshTtlSeconds()),
        String.join(",", amr),
        authenticatedAt,
        sessionId,
        startedAt,
        renewing != null && renewing.deviceLabel() != null
            ? renewing.deviceLabel()
            : clientInfo.device(),
        renewing != null && renewing.network() != null ? renewing.network() : clientInfo.network());

    return TokenResponse.bearer(access, refresh, config.accessTtlSeconds());
  }
}
