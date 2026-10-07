package com.storeql.web;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns every error envelope on its way out into RFC 9457 problem details: whatever produced it —
 * an exception mapper, a filter that aborted the request, a resource that returned {@link
 * ApiResponse#error} — the client receives {@code application/problem+json} with {@code type},
 * {@code title}, {@code status}, {@code detail} and {@code instance}, the stable {@code code}, and
 * the legacy {@code error} member it may still read. Responses carrying data are left alone.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.ENTITY_CODER)
public class ProblemResponseFilter implements ContainerResponseFilter {

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    if (!(response.getEntity() instanceof ApiResponse<?> envelope) || envelope.error() == null) {
      return;
    }
    String requestId = request.getHeaderString(HttpHeaders.REQUEST_ID);
    Problem problem =
        Problems.of(
            response.getStatus(),
            envelope.error(),
            Problems.instanceOf(request.getUriInfo()),
            requestId,
            envelope.meta());
    response.setEntity(problem, response.getEntityAnnotations(), Problems.PROBLEM_JSON);
    response.getHeaders().putSingle("Content-Type", Problems.MEDIA_TYPE);
    if (envelope.error().code() != null) {
      response.getHeaders().putSingle(HttpHeaders.ERROR_CODE, envelope.error().code());
    }
  }
}
