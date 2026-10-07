package com.storeql.gateway.flow;

import com.storeql.web.HttpHeaders;
import com.storeql.web.SystemHealthAccess;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The caller of a system-health read, as {@code JwtAuthFilter} verified and stamped them. The
 * headers are read the way the services' {@code TenantContextFilter} reads them, with one
 * difference on purpose: a store list that does not parse still holds the caller to stores. The
 * services drop an unreadable id and so can read a store-held caller as unrestricted; a screen that
 * shows the whole business's traffic must fail the other way.
 *
 * @param tenantId the verified business, or null when the request carried none
 * @param roles the roles in the token
 * @param permissions the token's permission claim, or null when it carried none (the roles'
 *     defaults then apply); empty for {@code -}, a role narrowed to nothing
 * @param storeIds the stores the caller is held to, as written; empty for a caller held to none
 */
record SystemHealthCaller(
    String tenantId, Set<String> roles, Set<String> permissions, Set<String> storeIds) {

  static SystemHealthCaller of(ContainerRequestContext ctx) {
    return of(
        FlowAttributes.canonicalId(ctx.getProperty(FlowAttributes.TENANT_ID)),
        ctx.getHeaderString(HttpHeaders.ROLES),
        ctx.getHeaderString(HttpHeaders.PERMISSIONS),
        ctx.getHeaderString(HttpHeaders.STORE_IDS));
  }

  static SystemHealthCaller of(String tenantId, String roles, String permissions, String storeIds) {
    return new SystemHealthCaller(
        tenantId,
        list(roles),
        permissions == null ? null : permissionSet(permissions),
        list(storeIds));
  }

  /**
   * The rule is common-web's, one for every place that asks it.
   *
   * @throws com.storeql.web.ApiException 403 {@code SYSTEM_HEALTH_NOT_PERMITTED}, else 403 {@code
   *     BUSINESS_WIDE_ONLY}
   */
  void requireAccess() {
    SystemHealthAccess.require(roles, permissions, storeIds);
  }

  private static Set<String> permissionSet(String header) {
    String v = header.trim();
    return "-".equals(v) ? Set.of() : list(v);
  }

  private static Set<String> list(String csv) {
    if (csv == null || csv.isBlank()) return Set.of();
    Set<String> out = new LinkedHashSet<>();
    Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
    return Set.copyOf(out);
  }
}
