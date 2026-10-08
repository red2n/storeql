package com.storeql.gateway.flow;

import com.storeql.gateway.GatewayConfig;
import com.storeql.ids.Ids;
import com.storeql.web.ApiResponse;
import com.storeql.web.ErrorBody;
import com.storeql.web.HttpHeaders;
import com.storeql.web.Problem;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.lang.System.Logger;
import java.time.Clock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The last thing an answer passes: gives it the request id if nothing did, and hands one record of
 * how it ended to the {@link FlowRecorder}. Response filters run from the highest priority number
 * down, so this one, with the lowest, sees the answer as the client will — whoever produced it: a
 * service, the proxy, or a filter that refused the request on the way in.
 *
 * <p>It reads the method, the decoded path (never the query string or the raw address) and the
 * status; the stable error code from an error the gateway itself built or, for an answer relayed
 * from a service, from an {@code X-Error-Code} header if the service sent one — a relayed body is
 * never buffered or parsed; the time since {@link RequestIdFilter} saw the request; and the
 * business and caller that {@code JwtAuthFilter} verified. It never reads {@code X-Tenant-Id} or
 * {@code X-User-Id}: until that filter has run they are whatever the client wrote.
 */
@Provider
@ApplicationScoped
// The lowest priority in the application, so it runs after every other response filter.
@Priority(10)
public class FlowRecordingFilter implements ContainerResponseFilter {

  private static final Logger LOG = System.getLogger(FlowRecordingFilter.class.getName());

  private static final Set<String> METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
  private static final int MAX_CODE = 64;
  private static final long NANOS_PER_MILLI = 1_000_000L;

  @Inject GatewayConfig config;
  @Inject FlowRecorder recorder;

  Clock clock = Clock.systemUTC();
  LongSupplier nanos = System::nanoTime;

  private volatile Set<String> groups;
  private final WarnOnce warn = new WarnOnce(System::nanoTime);

  @Override
  public void filter(ContainerRequestContext req, ContainerResponseContext res) {
    String requestId = requestId(req);
    if (!res.getHeaders().containsKey(HttpHeaders.REQUEST_ID)) {
      res.getHeaders().putSingle(HttpHeaders.REQUEST_ID, requestId);
    }
    if (!config.flowEnabled()) return;
    try {
      FlowRecord record = describe(req, res, requestId);
      // The screen's own polling is the watching, not the traffic: counted, it would be most of a
      // quiet shop's requests and make every window look healthy.
      if (!record.isScreenRead()) recorder.record(record);
    } catch (RuntimeException e) {
      // Recording is there to describe the request, never to fail it.
      warn.warn(LOG, "A request could not be recorded for the health screen", e);
    }
  }

  private FlowRecord describe(
      ContainerRequestContext req, ContainerResponseContext res, String requestId) {
    RoutePattern route = RoutePattern.of(req.getUriInfo().getPath(), groups());
    return new FlowRecord(
        clock.instant(),
        requestId,
        method(req.getMethod()),
        route.pattern(),
        route.group(),
        res.getStatus(),
        code(res),
        FlowAttributes.canonicalId(req.getProperty(FlowAttributes.TENANT_ID)),
        FlowAttributes.canonicalId(req.getProperty(FlowAttributes.USER_ID)),
        millisSince(req.getProperty(FlowAttributes.STARTED_NANOS)));
  }

  private static String requestId(ContainerRequestContext req) {
    return req.getProperty(FlowAttributes.REQUEST_ID) instanceof String id && !id.isBlank()
        ? id
        : Ids.newId().toString();
  }

  private long millisSince(Object startedNanos) {
    return startedNanos instanceof Long started
        ? Math.max(0, (nanos.getAsLong() - started) / NANOS_PER_MILLI)
        : 0;
  }

  private static String method(String method) {
    String upper = method == null ? "" : method.toUpperCase(Locale.ROOT);
    return METHODS.contains(upper) ? upper : "OTHER";
  }

  private static String code(ContainerResponseContext res) {
    String code =
        switch (res.getEntity()) {
          case Problem problem -> problem.code();
          case ApiResponse<?> envelope -> envelope.error() == null ? null : envelope.error().code();
          case ErrorBody error -> error.code();
          case null, default -> null;
        };
    if (code == null
        && res.getHeaders().getFirst(HttpHeaders.ERROR_CODE) instanceof String header) {
      code = header;
    }
    return isCode(code) ? code : null;
  }

  /**
   * A stable code: capitals, digits and underscores, starting with a capital. Nothing free-form.
   */
  static boolean isCode(String s) {
    if (s == null || s.isEmpty() || s.length() > MAX_CODE) return false;
    char first = s.charAt(0);
    if (first < 'A' || first > 'Z') return false;
    for (int i = 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_') return false;
    }
    return true;
  }

  /** The group names a route may take: the routable services, and the gateway's own routes. */
  private Set<String> groups() {
    Set<String> known = groups;
    if (known == null) {
      Set<String> all = new HashSet<>(config.routableServices());
      all.addAll(RoutePattern.OWN_GROUPS);
      known = Set.copyOf(all);
      groups = known;
    }
    return known;
  }
}
