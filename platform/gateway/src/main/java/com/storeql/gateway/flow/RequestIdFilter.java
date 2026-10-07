package com.storeql.gateway.flow;

import com.storeql.ids.Ids;
import com.storeql.web.HttpHeaders;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Mints the request's id before anything else looks at it, so that a request refused by the first
 * filter to touch it — a preflight, an unknown API version, a rate limit, a bad token — still has
 * an id its sender can quote and the health screen can list.
 *
 * <p>The id is the gateway's. Whatever {@code X-Request-Id} the client sent is replaced, never
 * trusted or echoed: a value chosen by the caller could collide with another request's, forge a
 * trace, or carry a line break into a log. {@code ProxyResource} sends this same id to the service,
 * and {@link FlowRecordingFilter} puts it on the answer.
 */
@Provider
@PreMatching
@ApplicationScoped
// Before CorsFilter (50), the first filter that can answer a request.
@Priority(10)
public class RequestIdFilter implements ContainerRequestFilter {

  @Override
  public void filter(ContainerRequestContext ctx) {
    String id = Ids.newId().toString();
    ctx.getHeaders().putSingle(HttpHeaders.REQUEST_ID, id);
    ctx.setProperty(FlowAttributes.REQUEST_ID, id);
    ctx.setProperty(FlowAttributes.STARTED_NANOS, System.nanoTime());
  }
}
