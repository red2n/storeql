package com.storeql.gateway;

import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import com.storeql.web.ApiResponse;
import com.storeql.web.ErrorBody;
import com.storeql.web.HttpHeaders;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The single public door. Routes {@code /api/{service}/{path...}} to the upstream resolved from
 * Consul.
 *
 * <p>Phase-0 scope: discovery-based routing + {@code X-Request-Id} propagation for GET/POST. This
 * is also the single place where, in later phases, JWT validation runs and verified identity is
 * injected downstream as {@code X-Tenant-Id}/{@code X-User-Id}/{@code X-Roles} (see {@link
 * #stampIdentity}). Business services trust those headers precisely because nothing else can reach
 * them (golden rule #2).
 */
@Path("/api")
@ApplicationScoped
@Tag(name = "Proxy")
public class ProxyResource {

  @Inject ServiceRegistry registry;
  @Inject WebClient webClient;
  @Inject GatewayConfig config;
  @Inject UpstreamCircuitBreaker breaker;

  @Operation(
      summary = "Proxy a GET request to a business service",
      description =
          "Forwards to the upstream resolved via Consul for {service} (optionally prefixed with a"
              + " /v1 version segment). {service} must be in the routable allowlist.")
  @APIResponse(responseCode = "200", description = "Upstream response, relayed as-is")
  @APIResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token")
  @APIResponse(responseCode = "403", description = "Caller lacks a required role or tenant scope")
  @APIResponse(responseCode = "500", description = "Unexpected gateway failure")
  @APIResponse(responseCode = "502", description = "Upstream returned a connectivity error")
  @APIResponse(responseCode = "504", description = "Upstream did not answer in time")
  @APIResponse(
      responseCode = "503",
      description = "No healthy upstream instance, or its circuit breaker is open")
  @GET
  @Path("/{service}/{path: .*}")
  @Produces(MediaType.WILDCARD)
  public Response proxyGet(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    if (breaker.isOpen(service)) return circuitOpenResponse(service);
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .get(instance.baseUri() + "/" + path)
                      .header(
                          io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(req::request, service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @Operation(
      summary = "Proxy a POST request to a business service",
      description = "Forwards the request body to the upstream resolved via Consul for {service}.")
  @APIResponse(responseCode = "200", description = "Upstream response, relayed as-is")
  @APIResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token")
  @APIResponse(responseCode = "403", description = "Caller lacks a required role or tenant scope")
  @APIResponse(responseCode = "500", description = "Unexpected gateway failure")
  @APIResponse(responseCode = "502", description = "Upstream returned a connectivity error")
  @APIResponse(responseCode = "504", description = "Upstream did not answer in time")
  @APIResponse(
      responseCode = "503",
      description = "No healthy upstream instance, or its circuit breaker is open")
  @POST
  @Path("/{service}/{path: .*}")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.WILDCARD)
  public Response proxyPost(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders,
      byte[] body) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    if (breaker.isOpen(service)) return circuitOpenResponse(service);
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .post(instance.baseUri() + "/" + path)
                      .header(
                          io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
              forwardContentType(req, inboundHeaders);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(() -> req.submit(body == null ? new byte[0] : body), service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @Operation(
      summary = "Proxy a PUT request to a business service",
      description = "Forwards the request body to the upstream resolved via Consul for {service}.")
  @APIResponse(responseCode = "200", description = "Upstream response, relayed as-is")
  @APIResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token")
  @APIResponse(responseCode = "403", description = "Caller lacks a required role or tenant scope")
  @APIResponse(responseCode = "500", description = "Unexpected gateway failure")
  @APIResponse(responseCode = "502", description = "Upstream returned a connectivity error")
  @APIResponse(responseCode = "504", description = "Upstream did not answer in time")
  @APIResponse(
      responseCode = "503",
      description = "No healthy upstream instance, or its circuit breaker is open")
  @PUT
  @Path("/{service}/{path: .*}")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.WILDCARD)
  public Response proxyPut(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders,
      byte[] body) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    if (breaker.isOpen(service)) return circuitOpenResponse(service);
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .put(instance.baseUri() + "/" + path)
                      .header(
                          io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
              forwardContentType(req, inboundHeaders);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(() -> req.submit(body == null ? new byte[0] : body), service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @Operation(
      summary = "Proxy a PATCH request to a business service",
      description = "Forwards the request body to the upstream resolved via Consul for {service}.")
  @APIResponse(responseCode = "200", description = "Upstream response, relayed as-is")
  @APIResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token")
  @APIResponse(responseCode = "403", description = "Caller lacks a required role or tenant scope")
  @APIResponse(responseCode = "500", description = "Unexpected gateway failure")
  @APIResponse(responseCode = "502", description = "Upstream returned a connectivity error")
  @APIResponse(responseCode = "504", description = "Upstream did not answer in time")
  @APIResponse(
      responseCode = "503",
      description = "No healthy upstream instance, or its circuit breaker is open")
  @jakarta.ws.rs.PATCH
  @Path("/{service}/{path: .*}")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.WILDCARD)
  public Response proxyPatch(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders,
      byte[] body) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    if (breaker.isOpen(service)) return circuitOpenResponse(service);
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .patch(instance.baseUri() + "/" + path)
                      .header(
                          io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
              forwardContentType(req, inboundHeaders);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(() -> req.submit(body == null ? new byte[0] : body), service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  @Operation(
      summary = "Proxy a DELETE request to a business service",
      description = "Forwards to the upstream resolved via Consul for {service}.")
  @APIResponse(responseCode = "200", description = "Upstream response, relayed as-is")
  @APIResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token")
  @APIResponse(responseCode = "403", description = "Caller lacks a required role or tenant scope")
  @APIResponse(responseCode = "500", description = "Unexpected gateway failure")
  @APIResponse(responseCode = "502", description = "Upstream returned a connectivity error")
  @APIResponse(responseCode = "504", description = "Upstream did not answer in time")
  @APIResponse(
      responseCode = "503",
      description = "No healthy upstream instance, or its circuit breaker is open")
  @DELETE
  @Path("/{service}/{path: .*}")
  @Produces(MediaType.WILDCARD)
  public Response proxyDelete(
      @PathParam("service") String rawService,
      @PathParam("path") String rawPath,
      @Context UriInfo uriInfo,
      @Context jakarta.ws.rs.core.HttpHeaders inboundHeaders) {
    Route route = Route.of(rawService, rawPath);
    String service = route.service();
    String path = route.path();
    if (breaker.isOpen(service)) return circuitOpenResponse(service);
    return resolve(service)
        .map(
            instance -> {
              String requestId = newRequestId();
              var req =
                  webClient
                      .delete(instance.baseUri() + "/" + path)
                      .header(
                          io.helidon.http.HeaderNames.create(HttpHeaders.REQUEST_ID), requestId);
              addQueryParams(req, uriInfo);
              stampIdentity(req, inboundHeaders);
              return relay(req::request, service, requestId);
            })
        .orElseGet(() -> serviceUnavailable(service));
  }

  // --- helpers ---

  /**
   * The effective upstream target after peeling off an optional API version segment. {@code
   * /api/v1/{service}/{path}} resolves to exactly the same upstream as the unversioned {@code
   * /api/{service}/{path}} alias, so versioning is a gateway-level concern and business services
   * stay version-agnostic. The version is currently informational (only {@code v1} exists);
   * version-specific routing can branch on it here later.
   */
  record Route(String service, String path) {
    static Route of(String service, String path) {
      String rest = path == null ? "" : path;
      if (service != null && service.matches("v\\d+")) {
        int slash = rest.indexOf('/');
        if (slash < 0) {
          return new Route(rest, "");
        }
        return new Route(rest.substring(0, slash), rest.substring(slash + 1));
      }
      return new Route(service, rest);
    }
  }

  private Optional<ServiceInstance> resolve(String service) {
    // Allowlist gate: only declared business services are routable. An internal service that
    // happens to register in Consul (config, discovery, observability) must not be reachable
    // from the internet just because the proxy can resolve it (golden rule #2).
    if (!config.routableServices().contains(service)) {
      return Optional.empty();
    }
    return registry.resolve(service);
  }

  /**
   * Forwards the verified identity headers to the upstream service. By the time this runs, {@link
   * JwtAuthFilter} has already stripped any client-supplied copies and replaced them with values
   * extracted from the validated JWT. Downstream services trust these headers because only the
   * gateway can reach them (golden rule #2).
   */
  private void stampIdentity(
      io.helidon.webclient.api.HttpClientRequest req, jakarta.ws.rs.core.HttpHeaders inbound) {
    for (String header : FORWARDED_HEADERS) {
      forward(req, inbound, header);
    }
  }

  /**
   * Every header the proxy passes upstream. An explicit list, because everything else a client
   * sends stops here — and that is why two identity headers went missing for as long as they did:
   * {@link JwtAuthFilter} stamped {@code X-Store-Ids} on every request and this list never carried
   * it, so no service ever saw a store restriction and {@code TenantContext.requireStoreAccess} was
   * a no-op for everyone (SJ-D46). {@code X-User-Email} was added to the filter the same way and
   * would have gone the same way (SJ-D44). The list is package-visible so a test can hold it
   * against the filter's.
   */
  static final java.util.List<String> FORWARDED_HEADERS =
      java.util.List.of(
          HttpHeaders.TENANT_ID,
          HttpHeaders.USER_ID,
          HttpHeaders.USER_EMAIL,
          HttpHeaders.ROLES,
          HttpHeaders.STORE_IDS,
          HttpHeaders.PERMISSIONS,
          // What a limited token is good for (20.12): stamped by JwtAuthFilter from the token's
          // scope, never taken from the client.
          HttpHeaders.AUTH_SCOPE,
          // How the session was authenticated (20.12, SSO): stamped from the token's amr claim.
          HttpHeaders.AUTH_METHODS,
          // Which of the person's sessions is asking: stamped from the token's sid claim.
          HttpHeaders.SESSION_ID,
          // Client-controlled, not identity — forwarded so downstream writes can dedupe retries
          // (golden rule #11). Not stripped/overwritten: the client owns this value.
          HttpHeaders.IDEMPOTENCY_KEY,
          // A network delivering an e-invoice (07.13): the key it presents, which purchase-svc
          // holds against the deployment's own, and its reference for the delivery. Client-owned,
          // like the idempotency key; worthless to anyone who does not hold the key.
          HttpHeaders.EINVOICE_KEY,
          HttpHeaders.EINVOICE_REFERENCE);

  /**
   * The response headers the proxy passes back from a service, beyond the Content-Type it always
   * relays. An explicit list for the same reason as {@link #FORWARDED_HEADERS}: everything a
   * service sets stops here unless named. Before this list existed, a service's download reached
   * the client without its file name and a sensitive response without its no-store rule — the
   * payment-run bank file and the fiscal receipt export among them. {@code Set-Cookie}, {@code
   * Server} and anything internal still stay behind.
   */
  static final java.util.List<String> RELAYED_RESPONSE_HEADERS =
      java.util.List.of("Content-Disposition", "Cache-Control");

  /** The allowlisted headers present on a service's response, by name, in list order. */
  static java.util.Map<String, String> relayedResponseHeaders(io.helidon.http.Headers upstream) {
    java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
    for (String name : RELAYED_RESPONSE_HEADERS) {
      upstream.first(io.helidon.http.HeaderNames.create(name)).ifPresent(v -> out.put(name, v));
    }
    return out;
  }

  /**
   * Where a service's redirect sends the browser — relayed for a 3xx and for nothing else. A
   * redirect with its {@code Location} stripped is a dead end (iam-svc's single sign-on callback
   * sends the browser back to the app with one). But a {@code Location} on any other answer, such
   * as a JAX-RS {@code 201 Created}, is built from the service's own base address and would tell
   * the internet what the cluster's hosts are called.
   *
   * @param status the service's status code
   * @param upstream the service's response headers
   * @return the redirect target, when the answer is a redirect that has one
   */
  static java.util.Optional<String> redirectTarget(int status, io.helidon.http.Headers upstream) {
    if (status < 300 || status >= 400) return java.util.Optional.empty();
    return upstream.first(io.helidon.http.HeaderNames.LOCATION);
  }

  private void forward(
      io.helidon.webclient.api.HttpClientRequest req,
      jakarta.ws.rs.core.HttpHeaders inbound,
      String header) {
    String value = inbound.getHeaderString(header);
    if (value != null && !value.isBlank()) {
      req.header(io.helidon.http.HeaderNames.create(header), value);
    }
  }

  /**
   * Forwards the client's real Content-Type so a binary body (e.g. an uploaded product image)
   * reaches the upstream service labelled correctly instead of being coerced to JSON. Defaults to
   * JSON when the client didn't set one, matching every existing JSON-only caller's behavior.
   */
  private void forwardContentType(
      io.helidon.webclient.api.HttpClientRequest req, jakarta.ws.rs.core.HttpHeaders inbound) {
    String contentType = inbound.getHeaderString(jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE);
    req.header(
        io.helidon.http.HeaderNames.CONTENT_TYPE,
        contentType == null || contentType.isBlank() ? MediaType.APPLICATION_JSON : contentType);
  }

  /**
   * Executes the upstream call and copies status + body back. The call is passed as a supplier so
   * connect/read failures (including the WebClient timeouts configured in {@link GatewayBeans})
   * surface as 504/502 envelopes instead of leaking as container 500s.
   */
  // upstream IS closed below via `try (upstream) { ... }` — PMD's CloseResource analysis doesn't
  // track a resource assigned in one try/catch and closed via try-with-resources on the
  // already-initialized variable in a later block, hence the false positive here.
  @SuppressWarnings("PMD.CloseResource")
  private Response relay(
      java.util.function.Supplier<HttpClientResponse> call, String service, String requestId) {
    HttpClientResponse upstream;
    try {
      upstream = call.get();
    } catch (RuntimeException e) {
      // Only a connectivity/timeout failure (the service didn't answer at all) counts against its
      // circuit — see UpstreamCircuitBreaker's class doc.
      breaker.recordFailure(service);
      boolean timeout = hasCause(e, java.net.SocketTimeoutException.class);
      return Response.status(timeout ? 504 : 502)
          .header(HttpHeaders.REQUEST_ID, requestId)
          .type(MediaType.APPLICATION_JSON)
          .entity(
              ApiResponse.error(
                  ErrorBody.of(
                      timeout ? "UPSTREAM_TIMEOUT" : "UPSTREAM_ERROR",
                      "'" + service + "' did not answer" + (timeout ? " in time" : ""))))
          .build();
    }
    breaker.recordSuccess(service);
    try (upstream) {
      int status = upstream.status().code();
      Response.ResponseBuilder rb =
          Response.status(status).header(HttpHeaders.REQUEST_ID, requestId);
      relayedResponseHeaders(upstream.headers()).forEach(rb::header);
      redirectTarget(status, upstream.headers()).ifPresent(to -> rb.header("Location", to));
      // Some upstream responses carry no body at all (e.g. a 405 from a path/method mismatch, or
      // any handler that returns a bare status) even when the status isn't 204/205/304.
      // HttpClientResponse.as(String.class) throws IllegalStateException — not an empty string —
      // for a truly absent entity, so probe hasEntity() first instead of relying on status alone.
      if (status != 204 && status != 205 && status != 304 && upstream.entity().hasEntity()) {
        // Read as raw bytes (not .as(String.class)) so a binary body — e.g. a served product
        // image — round-trips intact instead of being mangled by string decode/re-encode, and
        // forward the upstream's own Content-Type instead of hardcoding JSON.
        byte[] bytes;
        try {
          bytes = upstream.entity().inputStream().readAllBytes();
        } catch (java.io.IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
        String contentType =
            upstream
                .headers()
                .contentType()
                .map(Object::toString)
                .orElse(MediaType.APPLICATION_JSON);
        rb.type(contentType).entity(bytes);
      }
      return rb.build();
    }
  }

  private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (type.isInstance(c)) return true;
    }
    return false;
  }

  private Response serviceUnavailable(String service) {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(
                ErrorBody.of(
                    "UPSTREAM_UNAVAILABLE",
                    "No healthy instance of '" + service + "' in discovery")))
        .build();
  }

  /**
   * Fails fast instead of dispatching to a service whose circuit is open (see {@link
   * UpstreamCircuitBreaker}) — every other proxied service is unaffected.
   */
  private Response circuitOpenResponse(String service) {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(
                ErrorBody.of(
                    "UPSTREAM_CIRCUIT_OPEN",
                    "'" + service + "' has failed repeatedly and is temporarily bypassed")))
        .build();
  }

  private static void addQueryParams(
      io.helidon.webclient.api.HttpClientRequest req, UriInfo uriInfo) {
    uriInfo
        .getQueryParameters()
        .forEach((key, values) -> req.queryParam(key, values.toArray(String[]::new)));
  }

  private static String newRequestId() {
    return Ids.newId().toString();
  }
}
