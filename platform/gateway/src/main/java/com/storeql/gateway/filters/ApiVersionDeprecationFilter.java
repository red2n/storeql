package com.storeql.gateway.filters;

import com.storeql.gateway.ApiVersions;
import com.storeql.gateway.GatewayConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.time.Clock;

/**
 * Marks every answer served on the unversioned {@code /api/{service}/…} alias as deprecated (22.8):
 * {@code Deprecation} (RFC 9745, the day as {@code @<seconds>}), {@code Sunset} (RFC 8594, the day
 * it stops) and {@code Link} to the successor, so a client library that reads either standard warns
 * its owner. Requests that carry a version segment are left untouched, and so is {@code
 * /api/versions}, which describes the policy and is not a service call.
 *
 * <p>Routing itself is identical for the two forms (see {@link
 * com.storeql.gateway.ProxyResource.Route}); on the sunset day {@link ApiVersionFilter} starts
 * refusing the alias and this filter has nothing left to mark.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.USER + 90)
public class ApiVersionDeprecationFilter implements ContainerResponseFilter {

  @Inject GatewayConfig config;

  Clock clock = Clock.systemUTC();

  @Override
  public void filter(ContainerRequestContext req, ContainerResponseContext res) throws IOException {
    String path = req.getUriInfo().getPath();
    if (path == null) {
      return;
    }
    ApiVersions.Policy policy = config.apiVersions();
    if (ApiVersions.check(policy, path, clock.instant()) instanceof ApiVersions.Ok ok
        && ok.alias()) {
      res.getHeaders().putSingle("Deprecation", ApiVersions.deprecationHeader(policy));
      res.getHeaders().putSingle("Sunset", ApiVersions.sunsetHeader(policy));
      res.getHeaders().putSingle("Link", ApiVersions.successorLink(policy));
    }
  }
}
