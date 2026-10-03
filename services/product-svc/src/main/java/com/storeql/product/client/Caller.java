package com.storeql.product.client;

import com.storeql.web.HttpHeaders;
import com.storeql.web.TenantContext;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who a follow-up is made for: the caller of this request, handed on to the service that is asked
 * to act, so that it judges the person and not a bare role tier.
 *
 * <p>A supplier import receives stock through inventory-svc and sets prices through pricing-svc.
 * Each of those holds a caller to the stores they keep and to the permissions their role leaves
 * them, but only if it is told which they are: given the tenant and the tier alone, it read a
 * manager held to one branch as a manager of the whole business, and a role narrowed to exclude
 * {@code pricing.write} as the tier's defaults, which are everything.
 *
 * <p>The headers are the gateway's own ({@link HttpHeaders}), stamped from the verified token and
 * read here from {@link TenantContext}; this service adds nothing to them.
 *
 * @param tenantId the caller's business
 * @param userId the caller's login, or null when the request named none
 * @param roles the caller's role tiers
 * @param storeIds the stores the caller is held to; empty for one held to none
 * @param permissions the permissions the caller holds, as this service judged them
 * @param requestId the request's correlation id, or null
 */
public record Caller(
    UUID tenantId,
    UUID userId,
    Set<String> roles,
    Set<UUID> storeIds,
    Set<String> permissions,
    String requestId) {

  public Caller {
    Objects.requireNonNull(tenantId, "tenantId");
    roles = Set.copyOf(roles);
    storeIds = Set.copyOf(storeIds);
    permissions = Set.copyOf(permissions);
  }

  /**
   * The caller of the current request.
   *
   * <p>The permissions handed on are the ones this service holds them to: the token's claim where
   * it carries one, else the tier's defaults. The service asked judges them the same way, so the
   * answer there is the answer here.
   *
   * @param ctx the request's context
   * @return the caller
   * @throws com.storeql.web.ApiException 401 {@code NO_TENANT} when the request carried no tenant
   */
  public static Caller of(TenantContext ctx) {
    return new Caller(
        ctx.requireTenantId(),
        ctx.userId(),
        ctx.roles(),
        ctx.storeIds(),
        ctx.permissions(),
        ctx.requestId());
  }

  /**
   * Puts the caller on a request to another service.
   *
   * <p>A caller held to no store sends no {@code X-Store-Ids} at all, which is how the gateway says
   * "unrestricted"; one holding no permission sends {@code -}, which is a token naming none and
   * never "use the tier's defaults".
   *
   * @param req the request
   * @return the request, with the caller's headers
   */
  HttpClientRequest stamp(HttpClientRequest req) {
    HttpClientRequest out =
        req.header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), joined(roles))
            .header(
                HeaderNames.create(HttpHeaders.PERMISSIONS),
                permissions.isEmpty() ? "-" : joined(permissions));
    if (userId != null) {
      out = out.header(HeaderNames.create(HttpHeaders.USER_ID), userId.toString());
    }
    if (!storeIds.isEmpty()) {
      out =
          out.header(
              HeaderNames.create(HttpHeaders.STORE_IDS),
              joined(storeIds.stream().map(UUID::toString).collect(Collectors.toSet())));
    }
    if (requestId != null && !requestId.isBlank()) {
      out = out.header(HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
    }
    return out;
  }

  /** Comma-separated, sorted, so the same caller is always written the same way. */
  private static String joined(Set<String> values) {
    return values.stream().sorted().collect(Collectors.joining(","));
  }
}
