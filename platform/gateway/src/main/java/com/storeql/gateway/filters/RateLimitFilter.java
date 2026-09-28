package com.storeql.gateway.filters;

import com.storeql.gateway.GatewayConfig;
import io.helidon.webserver.http.ServerRequest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
@ApplicationScoped
// Must run BEFORE authentication (JwtAuthFilter at 999): rate limiting exists precisely to
// shed unauthenticated floods cheaply, so it cannot sit behind the auth check.
@Priority(100)
public class RateLimitFilter implements ContainerRequestFilter {

  /**
   * Fixed window: a key's counter resets WINDOW_SECONDS after the request that first created it
   * (not on a calendar boundary). Counters live in Redis — shared across every gateway replica —
   * instead of per-instance heap, so an attacker cannot dodge the limit by hitting a different
   * replica. Redis's own {@code maxmemory-policy allkeys-lru} (docker-compose) bounds key churn; no
   * bespoke eviction needed here.
   */
  static final long WINDOW_SECONDS = 60;

  @Inject GatewayConfig config;

  /** The counter every gateway rate limit shares; a tenant's plan rate uses it too (21.11). */
  @Inject RateCounter counter;

  /** Helidon binds the underlying server request per-request; gives the socket remote address. */
  @Context ServerRequest serverRequest;

  @SuppressWarnings("PMD.CloseResource")
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!config.rateLimitEnabled()) {
      return;
    }

    String ip = ClientIp.resolve(requestContext, serverRequest, config.trustForwardedHeaders());
    RateCounter.Hit hit = counter.consume("ratelimit:" + ip, WINDOW_SECONDS);
    if (hit.count() > config.rateLimitRequestsPerMinute()) {
      Response resp =
          Response.status(429)
              .header("Retry-After", String.valueOf(hit.secondsLeft()))
              .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
              .entity(
                  com.storeql.web.ApiResponse.error(
                      com.storeql.web.ErrorBody.of(
                          "RATE_LIMITED", "Too many requests - rate limit exceeded")))
              .build();
      requestContext.abortWith(resp);
    }
  }
}
