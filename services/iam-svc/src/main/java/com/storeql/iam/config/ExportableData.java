package com.storeql.iam.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * What iam-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): staff logins,
 * their roles at each store, till sessions and the audit log. Every table and column of the iam
 * schema is exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "iam";
  }

  @Override
  public Map<String, String> excludedTables() {
    String secondFactor =
        "a login's second factor: a credential; it is set up again at the destination";
    return Map.ofEntries(
        Map.entry("mfa_totp", secondFactor),
        Map.entry("mfa_recovery_codes", secondFactor),
        Map.entry("mfa_passkeys", secondFactor),
        Map.entry("mfa_challenges", "sign-ins waiting on a second factor, minutes old: not data"),
        Map.entry(
            "otp_codes",
            "one-time sign-in codes sent to a phone or email address and valid for minutes: not"
                + " data, and not tied to a business"),
        Map.entry("refresh_tokens", "login session tokens: a credential"),
        Map.entry(
            "roles",
            "the platform's built-in roles, the same for every business; a business's own roles"
                + " are in tenant-svc"),
        Map.entry(
            "signing_keys",
            "the platform's token signing keys: a credential of the deployment, not a business's"
                + " data"),
        Map.entry(
            "sso_flows",
            "sign-ins in flight through the business's identity provider, minutes old: not data"),
        Map.entry(
            "password_reset_tokens",
            "a forgotten-password link's hash: a credential, spent or expired within the hour"),
        Map.entry(
            "password_reset_requests",
            "the abuse throttle on forgotten-password requests, keyed by no business and no"
                + " address — not data"));
  }

  @Override
  public Map<String, String> excludedColumns() {
    return Map.of(
        "users.password_hash",
        "a staff member's password hash: a credential; imported staff set a password at the destination",
        "sso_connections.client_secret_sealed",
        "the client secret the business's identity provider issued: a credential; entered again at the destination");
  }

  @Override
  public Map<String, String> tenantPredicates() {
    return Map.of(
        "user_roles",
        "user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = ?)",
        // A business's sandboxes are its own data (22.8): the pair is keyed by the sandbox and
        // belongs to the live business.
        "tenant_sandboxes",
        "live_tenant_id = ?");
  }

  @Override
  public Map<String, String> erasurePredicates() {
    String staffOfTheBusiness = "user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = ?)";
    return Map.of(
        "refresh_tokens", staffOfTheBusiness,
        "mfa_totp", staffOfTheBusiness,
        "mfa_recovery_codes", staffOfTheBusiness,
        "mfa_passkeys", staffOfTheBusiness,
        "mfa_challenges", staffOfTheBusiness,
        // Erased with the sandbox it names, and with the live business that had it.
        "tenant_sandboxes", "? IN (sandbox_tenant_id, live_tenant_id)");
  }

  @Override
  public Map<String, String> importSkipped() {
    return Map.of(
        "tenant_status",
        "the destination business's own status is kept, set when it signs up",
        "sso_connections",
        "the sign-in name is unique on the platform and the secret is not exported: the provider is connected again at the destination",
        "sso_identities",
        "links to the identity provider's people: each is made again at that person's first sign-in there",
        "tenant_sandboxes",
        "a sandbox is a tenant of the platform it was made on; the destination business makes its own");
  }

  @Override
  public Set<String> derivedTables() {
    return Set.of("store_status", "tenant_status");
  }
}
