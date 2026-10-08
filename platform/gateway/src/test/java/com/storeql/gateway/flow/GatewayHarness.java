package com.storeql.gateway.flow;

import com.storeql.ids.Ids;
import com.storeql.test.SigningKeysFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Everything the gateway talks to, stood up in-process before Helidon boots, once for the JVM: a
 * Consul that lists every service at one address, a stand-in for those services (and for iam-svc's
 * key set and tenant-svc's plan and status reads), and a real Redis behind a pipe a test can cut.
 * Properties are set here, in a static initialiser, because they must be in place before the
 * container starts.
 */
final class GatewayHarness {

  static final String ISSUER = "storeql";
  static final SigningKeysFixture KEYS = SigningKeysFixture.generate("flow-it-key");

  /** Which services Consul will say are up. */
  private static final List<String> SERVICES =
      List.of(
          "order-svc",
          "payment-svc",
          "product-svc",
          "inventory-svc",
          "customer-svc",
          "tenant-svc",
          "reporting-svc",
          "iam-svc");

  /** The route the health screen polls on reporting-svc, as the service sees it. */
  static final String WAITING_WORK = "/admin/reports/system-health/waiting-work";

  /** What the stand-in services were asked, newest last. */
  record Call(String method, String path, String query, Map<String, String> headers) {}

  static final List<Call> CALLS = Collections.synchronizedList(new ArrayList<>());

  /** A business's requests-a-minute allowance, as tenant-svc would state it. */
  static final Map<String, Long> PLAN_RATE = new ConcurrentHashMap<>();

  /**
   * The status reporting-svc's waiting-work read answers a business with (200 when none is set), so
   * a test can make the screen's own poll fail.
   */
  static final Map<String, Integer> WAITING_WORK_STATUS = new ConcurrentHashMap<>();

  static final TcpProxy REDIS;

  static {
    try {
      HttpServer services =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      services.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      services.createContext("/", GatewayHarness::serve);
      services.start();
      int servicesPort = services.getAddress().getPort();

      HttpServer consul =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      consul.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      consul.createContext("/v1/health/service/", ex -> consul(ex, servicesPort));
      consul.start();

      REDIS = new TcpProxy(TestRedis.host(), TestRedis.port());

      System.setProperty("storeql.consul.host", "127.0.0.1");
      System.setProperty("storeql.consul.port", String.valueOf(consul.getAddress().getPort()));
      System.setProperty("storeql.clients.iam-svc.url", "http://127.0.0.1:" + servicesPort);
      System.setProperty("storeql.redis.host", "127.0.0.1");
      System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
      System.setProperty("storeql.redis.password", TestRedis.PASSWORD);
      // The IP limit is not what these tests are about; the plan's rate and the pre-auth refusal
      // are
      // exercised on purpose.
      System.setProperty("storeql.gateway.rate-limit.requests-per-minute", "1000000");
      System.setProperty("storeql.gateway.flow.flush-interval-ms", "100");
      System.setProperty("storeql.gateway.flow.backoff-max-ms", "500");
      System.setProperty("otel.sdk.disabled", "true");
      // The JDK's HTTP client drops Origin as a restricted header, and a preflight has no meaning
      // without it.
      System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private GatewayHarness() {}

  /** Touching the class is enough: the static initialiser does the work. */
  static void start() {
    // see the static initialiser
  }

  /** A token as iam-svc would sign it. Null fields are left out of the token. */
  static String token(
      String tenant, String user, List<String> roles, List<String> storeIds, List<String> perms) {
    Map<String, Object> claims = new java.util.LinkedHashMap<>();
    if (tenant != null) claims.put("tenant", tenant);
    if (roles != null) claims.put("roles", roles);
    if (storeIds != null && !storeIds.isEmpty()) claims.put("storeIds", storeIds);
    if (perms != null) claims.put("perms", perms);
    return KEYS.sign(ISSUER, user, claims, 900);
  }

  static String token(String tenant, String role) {
    return token(tenant, Ids.newId().toString(), List.of(role), null, null);
  }

  /** The calls to the stand-in services for a path, oldest first. */
  static List<Call> callsTo(String path) {
    synchronized (CALLS) {
      return CALLS.stream().filter(c -> c.path().equals(path)).toList();
    }
  }

  // ── the stand-in services ─────────────────────────────────────────────────────────────────

  private static void serve(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    ex.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
    CALLS.add(new Call(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), headers));
    ex.getRequestBody().readAllBytes();

    if (path.equals("/auth/.well-known/jwks.json")) {
      reply(ex, 200, KEYS.jwksJson());
    } else if (path.equals("/storefront/active")) {
      reply(ex, 200, "{\"data\":{\"active\":true}}");
    } else if (path.equals("/admin/tenant/plan/limits")) {
      Long rate = PLAN_RATE.get(headers.get("X-Tenant-Id"));
      reply(
          ex,
          200,
          rate == null
              ? "{\"data\":{\"grants\":[]}}"
              : "{\"data\":{\"grants\":[{\"key\":\"requests.per-minute\",\"limitValue\":"
                  + rate
                  + "}]}}");
    } else if (path.equals(WAITING_WORK)) {
      String tenant = headers.get("X-Tenant-Id");
      int status = tenant == null ? 200 : WAITING_WORK_STATUS.getOrDefault(tenant, 200);
      reply(
          ex,
          status,
          status == 200
              ? "{\"data\":{\"generatedAt\":\"2026-10-07T10:00:00Z\",\"items\":[]}}"
              : error("PERMISSION_DENIED"));
    } else if (path.startsWith("/missing")) {
      reply(ex, 404, error("ORDER_NOT_FOUND"));
    } else if (path.startsWith("/conflict")) {
      reply(ex, 409, error("ORDER_CLOSED"));
    } else if (path.startsWith("/denied")) {
      // Names its code in a header as well, the way common-web's problem filter will once it does.
      ex.getResponseHeaders().set("X-Error-Code", "PERMISSION_DENIED");
      reply(ex, 403, error("PERMISSION_DENIED"));
    } else if (path.startsWith("/boom")) {
      reply(ex, 500, error("INTERNAL_ERROR"));
    } else {
      reply(ex, 200, "{\"data\":\"ok\"}");
    }
  }

  private static String error(String code) {
    return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"no\"}}";
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void consul(HttpExchange ex, int servicesPort) throws IOException {
    String path = ex.getRequestURI().getPath();
    String name = path.substring(path.lastIndexOf('/') + 1);
    String body =
        SERVICES.contains(name)
            ? "[{\"Node\":{\"Address\":\"127.0.0.1\"},\"Service\":{\"Address\":\"127.0.0.1\",\"Port\":"
                + servicesPort
                + "}}]"
            : "[]";
    reply(ex, 200, body);
  }
}
