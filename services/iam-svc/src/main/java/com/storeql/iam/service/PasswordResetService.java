package com.storeql.iam.service;

import com.storeql.iam.auth.Passwords;
import com.storeql.iam.domain.PasswordReset;
import com.storeql.iam.domain.User;
import com.storeql.iam.dto.Dtos.PasswordPolicyResponse;
import com.storeql.iam.repo.PasswordResetRepository;
import com.storeql.iam.repo.PasswordResetRepository.MintTarget;
import com.storeql.iam.repo.PasswordResetRepository.TokenOwner;
import com.storeql.iam.repo.SsoRepository;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import com.storeql.service.CapabilityTokens;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantProfiles;
import com.storeql.service.TenantStatusRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Forgotten password (intent/password-reset.md): the same {@code 202} whether or not the address is
 * known, one link per eligible login using it, a staff login named by its business, a
 * single-sign-on login named with no link. The brain of the feature (golden rule #9) — {@link
 * com.storeql.iam.api.PasswordResetResource} is thin.
 */
@ApplicationScoped
public class PasswordResetService {

  private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

  @Inject UserRepository users;
  @Inject PasswordResetRepository resetRepo;
  @Inject SsoRepository sso;
  @Inject TenantStatusRepository tenantStatus;
  @Inject TenantProfiles tenantProfiles;
  @Inject PasswordPolicy policy;
  @Inject Passwords passwords;

  /**
   * Where the web app is reached from outside — the reset link opens there, as tenant-svc's
   * PayLinks does pattern.
   */
  @Inject
  @ConfigProperty(name = "storeql.platform.web-url", defaultValue = "http://localhost:8088")
  String webUrl;

  /** How long a minted link stays good: 30 minutes, once (intent's confirmed decision). */
  @Inject
  @ConfigProperty(name = "storeql.iam.password-reset.ttl-minutes", defaultValue = "30")
  long ttlMinutes;

  /** At most this many accepted requests an hour, per address — never per login. */
  @Inject
  @ConfigProperty(name = "storeql.iam.password-reset.max-per-hour", defaultValue = "3")
  int maxPerHour;

  /**
   * {@code GET /auth/password-policy}: the published rules, so a sign-up or reset page shows them
   * before anyone types.
   */
  public PasswordPolicyResponse policy() {
    return new PasswordPolicyResponse(
        policy.minLength(), policy.maxLength(), policy.breachCheckEnabled(), true);
  }

  /**
   * {@code POST /auth/password/forgot}: the same work, and the same {@code 202}, whatever the
   * address — known or not, suspended, or throttled.
   *
   * @param email the address every eligible login using it is reset by
   * @param rawLanguage as sent; read strictly by {@link PasswordReset#language}
   */
  public void forgot(String email, String rawLanguage) {
    Instant now = Instant.now();
    String addressHash = PasswordReset.addressHash(email);
    resetRepo.sweep(now);
    if (resetRepo.recentRequestCount(addressHash, now.minus(Duration.ofHours(1))) >= maxPerHour) {
      // Over the limit: still 202, nothing minted, no event, not recorded (intent's throttle) —
      // this request itself does not extend the window it was refused for.
      return;
    }
    String language = PasswordReset.language(rawLanguage);
    Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));

    List<MintTarget> targets = new ArrayList<>();
    List<User> toAudit = new ArrayList<>();
    JsonArrayBuilder entries = Json.createArrayBuilder();
    boolean anyEntry = false;

    for (User user : users.findAllByEmail(email)) {
      Set<String> roles = users.rolesOf(user.id());
      boolean staff = user.tenantId() != null;
      boolean businessActive = !staff || tenantStatus.isActive(user.tenantId());
      boolean ssoRequired = staff && ssoRequired(user.tenantId(), roles);
      PasswordReset.Kind kind =
          PasswordReset.kindOf(
              User.STATUS_ACTIVE.equals(user.status()),
              roles.contains(PLATFORM_ADMIN),
              businessActive,
              staff,
              ssoRequired);
      if (kind == PasswordReset.Kind.NONE) {
        continue;
      }
      anyEntry = true;
      String businessName =
          staff ? tenantProfiles.businessName(user.tenantId()).orElse(null) : null;
      // The login's own id on every entry (SHOPPER, STAFF, STAFF_SSO alike): notification-svc
      // records its log row against it (AccountDeleted carries only the id, by design), so an
      // erased account's log erases too.
      JsonObjectBuilder entry =
          Json.createObjectBuilder().add("kind", kind.name()).add("userId", user.id().toString());
      if (staff) {
        // Always present for staff, string or explicit null — never omitted, unlike a shopper's
        // entry, which carries no business at all.
        if (businessName != null) {
          entry.add("businessName", businessName);
        } else {
          entry.addNull("businessName");
        }
      }
      if (kind != PasswordReset.Kind.STAFF_SSO) {
        String token = CapabilityTokens.mint();
        targets.add(
            new MintTarget(user.id(), user.tenantId(), CapabilityTokens.hash(token), expiresAt));
        entry.add("link", PasswordReset.link(webUrl, token));
      }
      entries.add(entry);
      toAudit.add(user);
    }
    // Same work whether or not the address is known: a token is minted and hashed at least once
    // either way — this one is simply never stored when nothing ended up eligible for a link.
    if (targets.isEmpty()) {
      CapabilityTokens.hash(CapabilityTokens.mint());
    }

    OutboxRow outbox = anyEntry ? outboxRow(email, language, expiresAt, entries) : null;
    resetRepo.mint(targets, addressHash, now, outbox);
    // Never the address for an unknown login — there is none here to begin with; each entry
    // audits only the login it belongs to.
    for (User audited : toAudit) {
      users.audit(audited.tenantId(), audited.id(), "PASSWORD_RESET_REQUESTED", null);
    }
  }

  /**
   * {@code POST /auth/password/reset}: spends a token once. The new password is checked against the
   * policy before anything is spent — a refusal leaves the token usable — and the login's
   * eligibility is checked again, since minutes may have passed since the link was sent.
   *
   * @param rawToken the token from the link
   * @param newPassword the replacement, checked against {@link PasswordPolicy}
   * @throws ApiException {@code 400 PASSWORD_RESET_TOKEN_INVALID} for an unknown, used, expired or
   *     replaced token, or a login no longer eligible; the policy's own code for a refused password
   */
  public void reset(String rawToken, String newPassword) {
    String tokenHash = CapabilityTokens.hash(rawToken);
    TokenOwner owner = resetRepo.find(tokenHash).orElseThrow(PasswordResetService::invalidToken);
    User user = users.findById(owner.userId()).orElseThrow(PasswordResetService::invalidToken);
    if (!stillEligible(user)) {
      throw invalidToken();
    }
    // The policy is judged before anything is spent: a password it refuses leaves the link
    // exactly as it was, so the person can try again with a better one.
    policy.check(newPassword, user.email());
    String hash = passwords.hash(newPassword);
    resetRepo.reset(tokenHash, hash).orElseThrow(PasswordResetService::invalidToken);
  }

  /** Re-checked at reset time, not only when the link was minted: minutes may have passed. */
  private boolean stillEligible(User user) {
    if (!User.STATUS_ACTIVE.equals(user.status())) {
      return false;
    }
    Set<String> roles = users.rolesOf(user.id());
    if (roles.contains(PLATFORM_ADMIN)) {
      return false;
    }
    if (user.tenantId() != null) {
      if (!tenantStatus.isActive(user.tenantId())) {
        return false;
      }
      if (ssoRequired(user.tenantId(), roles)) {
        return false;
      }
    }
    return true;
  }

  /** The same test {@code AuthService.login()} uses (ssoRequiredOf / Sso.requires). */
  private boolean ssoRequired(UUID tenantId, Set<String> roles) {
    return sso.connection(tenantId).map(c -> c.requiredOf(roles)).orElse(false);
  }

  private static ApiException invalidToken() {
    return ApiException.badRequest(
        "PASSWORD_RESET_TOKEN_INVALID",
        "This link no longer works — it may already be used, expired or replaced, or the login it"
            + " named is no longer eligible. Ask for a new one.");
  }

  /**
   * The {@code PasswordResetRequested} outbox row: belongs to no business (tenant NULL), keyed by a
   * fresh request id, exactly the shape notification-svc's consumer reads.
   */
  private static OutboxRow outboxRow(
      String email, String language, Instant expiresAt, JsonArrayBuilder entries) {
    UUID requestId = Ids.newId();
    JsonObjectBuilder payload =
        Json.createObjectBuilder()
            .add("eventType", "PasswordResetRequested")
            .add("eventId", Ids.newId().toString())
            .add("requestId", requestId.toString())
            .add("email", email);
    if (language == null) {
      payload.addNull("language");
    } else {
      payload.add("language", language);
    }
    payload.add("expiresAt", expiresAt.toString());
    payload.add("entries", entries);
    return new OutboxRow(
        "PasswordResetRequested",
        "storeql.iam.password-reset-requested",
        null,
        requestId,
        payload.build().toString());
  }
}
