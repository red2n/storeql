package com.storeql.gateway.filters;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

/**
 * Stamps OWASP-recommended security headers on every non-preflight response. Applied at lowest
 * priority so that service-specific headers (e.g. CORS, Location) are already present and we cannot
 * accidentally overwrite them.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.USER + 100)
public class SecurityHeadersFilter implements ContainerResponseFilter {

  @Override
  public void filter(ContainerRequestContext req, ContainerResponseContext res) throws IOException {
    if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
      return;
    }
    var h = res.getHeaders();
    h.putSingle("X-Content-Type-Options", "nosniff");
    h.putSingle("X-Frame-Options", "DENY");
    h.putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
    h.putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
    h.putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    // The gateway only ever returns JSON (frontends are a separate static app/container) — no
    // script/style/frame source is ever legitimate, so lock everything down rather than enumerate.
    h.putSingle("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");

    String path = req.getUriInfo().getPath();
    if (path != null
        && (path.contains("/auth/login")
            || path.contains("/auth/refresh")
            || path.contains("/auth/password/"))) {
      h.putSingle("Cache-Control", "no-store");
      h.putSingle("Pragma", "no-cache");
    }
  }
}
