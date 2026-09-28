package com.storeql.gateway.filters;

import com.storeql.gateway.ApiVersions;
import com.storeql.gateway.GatewayConfig;
import com.storeql.web.ApiResponse;
import com.storeql.web.ErrorBody;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.time.Clock;

/**
 * Applies the versioning policy at the door (22.8), before any credential is looked at: a version
 * nobody published is {@code 404 API_VERSION_UNKNOWN} with a {@code Link} to the latest, and the
 * unversioned alias from its sunset day is {@code 410 API_VERSION_RETIRED} with its successor. What
 * passes is what {@link com.storeql.gateway.ProxyResource} already routes; the policy itself is
 * {@link ApiVersions}.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 2)
public class ApiVersionFilter implements ContainerRequestFilter {

  @Inject GatewayConfig config;

  Clock clock = Clock.systemUTC();

  @Override
  public void filter(ContainerRequestContext ctx) throws IOException {
    ApiVersions.Policy policy = config.apiVersions();
    switch (ApiVersions.check(policy, ctx.getUriInfo().getPath(), clock.instant())) {
      case ApiVersions.Unknown unknown ->
          ctx.abortWith(
              Response.status(Response.Status.NOT_FOUND)
                  .type(MediaType.APPLICATION_JSON)
                  .header("Link", ApiVersions.latestLink(policy))
                  .entity(
                      ApiResponse.error(
                          ErrorBody.of(
                              "API_VERSION_UNKNOWN",
                              "There is no API version "
                                  + unknown.version()
                                  + "; the current one is "
                                  + policy.current()
                                  + " — see GET /api/versions")))
                  .build());
      case ApiVersions.Retired retired ->
          ctx.abortWith(
              Response.status(Response.Status.GONE)
                  .type(MediaType.APPLICATION_JSON)
                  .header("Link", ApiVersions.successorLink(policy))
                  .header("Sunset", ApiVersions.sunsetHeader(policy))
                  .entity(
                      ApiResponse.error(
                          ErrorBody.of(
                              "API_VERSION_RETIRED",
                              "The unversioned /api/{service}/ form was retired on "
                                  + policy.aliasSunset()
                                  + "; call /api/"
                                  + policy.current()
                                  + "/{service}/ instead — see GET /api/versions")))
                  .build());
      case ApiVersions.Ok ok -> {
        // Routed as before; the alias is marked on the way out by ApiVersionDeprecationFilter.
      }
    }
  }
}
