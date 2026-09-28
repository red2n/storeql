package com.storeql.gateway.filters;

import com.storeql.gateway.GatewayConfig;
import com.storeql.web.ApiResponse;
import com.storeql.web.ErrorBody;
import com.storeql.web.HttpHeaders;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.OptionalLong;

/**
 * The plan's rate: so many requests a minute for one business, across every login it has and its
 * online shop (21.11).
 *
 * <p>Different from {@link RateLimitFilter}, which sheds floods by IP before anybody is identified:
 * this one runs after {@link JwtAuthFilter} has said whose request it is — the tenant the token
 * carries, or the shop a guest is browsing — and counts it against what that business pays for. The
 * platform's own administrators have no tenant and are not counted. A business on a plan with no
 * rate, or on no plan, is not limited; a counter that cannot be reached counts nothing. The refusal
 * is a 429 that says how long until the minute turns, so a client can wait rather than retry blind.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION + 10)
public class TenantRateLimitFilter implements ContainerRequestFilter {

  static final String CODE = "PLAN_RATE_LIMIT_REACHED";
  static final long WINDOW_SECONDS = 60;

  @Inject GatewayConfig config;
  @Inject TenantAllowances allowances;
  @Inject RateCounter counter;

  @Override
  public void filter(ContainerRequestContext ctx) {
    if (!config.rateLimitEnabled()) return;
    String tenantId = ctx.getHeaderString(HttpHeaders.TENANT_ID);
    if (tenantId == null || tenantId.isBlank()) return;
    OptionalLong perMinute = allowances.requestsPerMinute(tenantId);
    if (perMinute.isEmpty()) return;
    RateCounter.Hit hit = counter.consume("ratelimit:tenant:" + tenantId, WINDOW_SECONDS);
    if (hit.count() <= perMinute.getAsLong()) return;
    ctx.abortWith(
        Response.status(429)
            .header("Retry-After", String.valueOf(hit.secondsLeft()))
            .type(MediaType.APPLICATION_JSON)
            .entity(
                ApiResponse.error(
                    ErrorBody.of(
                        CODE,
                        "This plan allows "
                            + perMinute.getAsLong()
                            + " requests a minute; try again in "
                            + hit.secondsLeft()
                            + " seconds, or move to a larger plan")))
            .build());
  }
}
