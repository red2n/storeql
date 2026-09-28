package com.storeql.web;

import jakarta.enterprise.context.RequestScoped;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Request-scoped holder of the authenticated principal.
 *
 * <p><strong>This is the ONLY sanctioned source of {@code tenantId} (golden rule #3).</strong>
 * Services read the tenant and user from here — never from the request body, path, or query. It is
 * populated by {@link TenantContextFilter} from identity the gateway forwards after it validates
 * the JWT.
 *
 * <p>Inject it where you need the caller's identity:
 *
 * <pre>{@code
 * @Inject TenantContext ctx;
 * ... ctx.requireTenantId() ...
 * }</pre>
 */
@RequestScoped
public class TenantContext {

  private UUID tenantId;
  private UUID userId;
  private String email;
  private Set<String> roles = Set.of();
  private Set<UUID> storeIds = Set.of();
  private Set<String> permissions;
  private String requestId;

  /**
   * @return the caller's tenant id, or {@code null} if the request carried none
   */
  public UUID tenantId() {
    return tenantId;
  }

  /**
   * @return the caller's user id, or {@code null} if the request carried no authenticated user
   */
  public UUID userId() {
    return userId;
  }

  /**
   * The caller's own email address, as the gateway read it from the verified JWT. Present only for
   * a request that carried a token with an email claim: a guest storefront request, a
   * service-to-service call and a token minted before the claim existed all have none.
   *
   * @return the caller's email address, or {@code null} if the request carried none
   */
  public String email() {
    return email;
  }

  /**
   * @return the caller's roles; empty (never {@code null}) if the request carried none
   */
  public Set<String> roles() {
    return roles;
  }

  /** Stores the caller may operate in. Empty means unrestricted (e.g. OWNER/PLATFORM_ADMIN). */
  public Set<UUID> storeIds() {
    return storeIds;
  }

  /**
   * @return the correlation id for this request ({@link HttpHeaders#REQUEST_ID})
   */
  public String requestId() {
    return requestId;
  }

  /**
   * The permissions this caller holds (20.10): the token's own claim when it carries one, else the
   * defaults of the roles held. An owner or platform admin holds every permission whatever the
   * claim says — they are the tenant's root and the platform, and a custom role narrows staff, not
   * them.
   *
   * @return the effective permission codes; never null
   */
  public Set<String> permissions() {
    if (Permissions.unrestricted(roles)) return Permissions.ALL;
    return permissions != null ? permissions : Permissions.effective(roles);
  }

  /**
   * Whether the caller may take the named decision.
   *
   * @param permission a code from {@link Permissions}
   * @return {@code true} when the caller holds it
   */
  public boolean hasPermission(String permission) {
    return permissions().contains(permission);
  }

  /**
   * Refuses the request unless the caller holds the permission. Checked at the one place the action
   * happens, after the tier gate the shared filter applies by path: a custom role can only be
   * narrower than its tier, so this only ever refuses what the tier would have allowed.
   *
   * @param permission a code from {@link Permissions}
   * @throws ApiException 403 {@code PERMISSION_DENIED}, naming the permission
   */
  public void requirePermission(String permission) {
    if (!hasPermission(permission)) {
      throw ApiException.forbidden(
          "PERMISSION_DENIED", "This action needs the " + permission + " permission");
    }
  }

  /**
   * @return the tenant id
   * @throws ApiException 401 {@code NO_TENANT} if the request carried no tenant (e.g. a protected
   *     route reached unauthenticated)
   */
  public UUID requireTenantId() {
    if (tenantId == null) {
      throw ApiException.unauthorized("NO_TENANT", "No tenant in request context");
    }
    return tenantId;
  }

  /**
   * @return the user id
   * @throws ApiException 401 {@code NO_USER} if the request carried no authenticated user (e.g. a
   *     route that requires a principal reached without one)
   */
  public UUID requireUserId() {
    if (userId == null) {
      throw ApiException.unauthorized("NO_USER", "No authenticated user in request context");
    }
    return userId;
  }

  /**
   * @param role the role to check for, e.g. {@code "MANAGER"}
   * @return {@code true} if the caller has {@code role}
   */
  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /**
   * Throw 403 unless the caller has at least one of the supplied roles.
   *
   * <pre>{@code ctx.requireAnyRole("ADMIN", "STAFF"); }</pre>
   *
   * @param required the acceptable roles; the caller must have at least one
   * @throws ApiException 403 {@code FORBIDDEN} if the caller has none of {@code required}
   */
  public void requireAnyRole(String... required) {
    for (String r : required) {
      if (roles.contains(r)) return;
    }
    throw ApiException.forbidden("FORBIDDEN", "Insufficient role for this operation");
  }

  /**
   * Throw 403 if the caller is store-restricted and {@code storeId} is not one of their assigned
   * stores. A caller with no store restriction (empty {@link #storeIds()} — e.g. OWNER,
   * PLATFORM_ADMIN) may operate on any store in their tenant, so this is a no-op for them.
   *
   * <pre>{@code ctx.requireStoreAccess(storeId); }</pre>
   *
   * @param storeId the store the caller is about to operate on
   * @throws ApiException 403 {@code STORE_ACCESS_DENIED} if the caller is store-restricted and
   *     {@code storeId} is not one of their assigned stores
   */
  public void requireStoreAccess(UUID storeId) {
    if (!hasStoreAccess(storeId)) {
      throw ApiException.forbidden("STORE_ACCESS_DENIED", "Caller is not assigned to this store");
    }
  }

  /**
   * Whether the caller may act at a store: assigned to it, or assigned to no store at all (an
   * owner, a manager of the whole business, a service).
   *
   * @param storeId the store
   * @return true when the caller is held to no store or to this one
   */
  public boolean hasStoreAccess(UUID storeId) {
    return storeIds.isEmpty() || storeIds.contains(storeId);
  }

  /**
   * Requires access to either of two stores — the two ends of a transfer, where the sender's keeper
   * and the receiver's may each read the order.
   *
   * @param first one store
   * @param second the other
   * @throws ApiException 403 STORE_ACCESS_DENIED when the caller keeps neither
   */
  public void requireAnyStoreAccess(UUID first, UUID second) {
    if (!hasStoreAccess(first) && !hasStoreAccess(second)) {
      throw ApiException.forbidden("STORE_ACCESS_DENIED", "Caller is not assigned to this store");
    }
  }

  /**
   * The store a read is scoped to (SJ-D74). A store that is named is checked against the caller's
   * stores; when none is named, a caller assigned to one store reads that store, a caller assigned
   * to several must say which, and a caller assigned to none reads them all.
   *
   * @param requested the store named on the request, or null
   * @return the store to read, or null for every store
   * @throws ApiException 403 STORE_ACCESS_DENIED for a store the caller does not keep; 400
   *     STORE_REQUIRED when a caller of several stores names none
   */
  public UUID scopeStore(UUID requested) {
    if (requested != null) {
      requireStoreAccess(requested);
      return requested;
    }
    if (storeIds.isEmpty()) {
      return null;
    }
    if (storeIds.size() == 1) {
      return storeIds.iterator().next();
    }
    throw ApiException.badRequest(
        "STORE_REQUIRED", "Name one of the stores you are assigned to (store=...)");
  }

  /**
   * Populates this request-scoped context from identity headers. Called once per request by {@link
   * TenantContextFilter}; not for use outside the filter chain.
   *
   * @param tenantId the caller's tenant, or {@code null} if unauthenticated
   * @param userId the caller's user id, or {@code null} if unauthenticated
   * @param roles the caller's roles; {@code null} is stored as empty
   * @param storeIds the stores the caller may operate in; {@code null} is stored as empty (=
   *     unrestricted)
   * @param requestId the correlation id for this request
   */
  void set(UUID tenantId, UUID userId, Set<String> roles, Set<UUID> storeIds, String requestId) {
    set(tenantId, userId, roles, storeIds, null, requestId);
  }

  /**
   * Populates this request-scoped context, permissions included.
   *
   * @param tenantId the caller's tenant, or {@code null} if unauthenticated
   * @param userId the caller's user id, or {@code null} if unauthenticated
   * @param roles the caller's roles; {@code null} is stored as empty
   * @param storeIds the stores the caller may operate in; {@code null} is stored as empty
   * @param permissions the token's permission claim; {@code null} when the token carries none, in
   *     which case the roles' defaults apply; an empty set when it carries none by choice
   * @param requestId the correlation id for this request
   */
  void set(
      UUID tenantId,
      UUID userId,
      Set<String> roles,
      Set<UUID> storeIds,
      Set<String> permissions,
      String requestId) {
    this.tenantId = tenantId;
    this.userId = userId;
    this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    this.storeIds = storeIds == null ? Set.of() : Set.copyOf(storeIds);
    this.permissions = permissions == null ? null : Set.copyOf(permissions);
    this.requestId = requestId;
  }

  /**
   * Makes this request act for a tenant the service resolved itself, with one role and no user.
   *
   * <p>A network delivering a supplier's e-invoice names the receiver inside the document, and the
   * request that carries it holds no token (07.13, the transport seam). Only a resource that has
   * already verified the delivery's own credential may call this; from then on every query the
   * request makes is scoped to the tenant named here, and what it writes is written by nobody.
   *
   * @param tenantId the tenant the request now acts for
   * @param role the one role it acts with
   */
  public void assume(UUID tenantId, String role) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(role, "role");
    this.tenantId = tenantId;
    this.userId = null;
    this.roles = Set.of(role);
    this.storeIds = Set.of();
    this.permissions = null;
  }

  /**
   * Records the caller's email address. Separate from {@link #set} so the identity a service
   * already trusts is not re-plumbed through every test that builds a context.
   *
   * @param email the email claim from the verified JWT, or {@code null}
   */
  void setEmail(String email) {
    this.email = email == null || email.isBlank() ? null : email.trim();
  }
}
