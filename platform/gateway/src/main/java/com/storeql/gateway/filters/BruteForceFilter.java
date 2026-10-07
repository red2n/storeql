package com.storeql.gateway.filters;

import com.storeql.gateway.GatewayConfig;
import io.helidon.webserver.http.ServerRequest;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Locks out repeated failed logins, keyed BOTH per account and per client IP: the account key stops
 * a distributed (many-IP) attack on one user; the IP key stops one machine spraying many users.
 * Both keys are checked on the way in and both are recorded on the way out.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class BruteForceFilter implements ContainerRequestFilter, ContainerResponseFilter {

  private static final String PROP_USER_KEY = "login.userKey";
  private static final String PROP_IP_KEY = "login.ipKey";
  private static final String PROP_TOKEN_PATH = "login.tokenPath";

  /** One shared (thread-safe) instance — building a Jsonb per login request is expensive. */
  private static final Jsonb JSONB = JsonbBuilder.create();

  /** Login bodies are tiny; anything bigger is not worth buffering for key extraction. */
  private static final int MAX_PARSEABLE_BODY_BYTES = 8 * 1024;

  @Inject GatewayConfig config;

  @Inject RedisCommands<String, String> redis;

  @Context ServerRequest serverRequest;

  private BruteForceProtectionService protection;

  @PostConstruct
  void init() {
    protection =
        new BruteForceProtectionService(
            redis,
            config.bruteForceMaxFailures(),
            java.time.Duration.ofMinutes(config.bruteForceBlockMinutes()));
  }

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!config.bruteForceEnabled()) {
      return;
    }

    String path = requestContext.getUriInfo().getPath();
    boolean login = isLoginPath(path);
    boolean tokenPath = !login && isTokenPath(path);
    if ((!login && !tokenPath) || !"POST".equalsIgnoreCase(requestContext.getMethod())) {
      return;
    }

    String ipKey = ClientIp.resolve(requestContext, serverRequest, config.trustForwardedHeaders());
    requestContext.setProperty(PROP_IP_KEY, ipKey);
    // A token path has no account to key on: the secret is the whole request, and reading it out
    // to count per token would only help an attacker learn which ones exist. The IP is the key.
    String userKey = tokenPath ? null : extractUserKey(requestContext);
    if (userKey != null) {
      requestContext.setProperty(PROP_USER_KEY, userKey);
    }
    requestContext.setProperty(PROP_TOKEN_PATH, tokenPath);

    if (protection.isBlocked(ipKey) || protection.isBlocked(userKey)) {
      requestContext.abortWith(
          Response.status(429)
              .header("Retry-After", String.valueOf(config.bruteForceBlockMinutes() * 60L))
              .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
              .entity(
                  com.storeql.web.ApiResponse.error(
                      com.storeql.web.ErrorBody.of(
                          tokenPath ? "TOKEN_LOCKED" : "LOGIN_LOCKED",
                          tokenPath
                              ? "Too many invalid tokens from this address - try later"
                              : "Too many failed login attempts - try later")))
              .build());
    }
  }

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext)
      throws IOException {
    if (!config.bruteForceEnabled()) {
      return;
    }

    String path = requestContext.getUriInfo().getPath();
    if ((!isLoginPath(path) && !isTokenPath(path))
        || !"POST".equalsIgnoreCase(requestContext.getMethod())) {
      return;
    }

    String userKey = (String) requestContext.getProperty(PROP_USER_KEY);
    String ipKey = (String) requestContext.getProperty(PROP_IP_KEY);
    boolean tokenPath = Boolean.TRUE.equals(requestContext.getProperty(PROP_TOKEN_PATH));

    int status = responseContext.getStatus();
    // A wrong password is a 401; a wrong token is a 404, because the service answers "not one we
    // issued" rather than "forbidden". Both are a guess that missed. A 400 is a malformed body,
    // not a guess, and is not counted.
    boolean missed = tokenPath ? status == 404 : (status == 401 || status == 403);
    if (missed) {
      protection.recordFailure(userKey);
      protection.recordFailure(ipKey);
    } else if (status >= 200 && status < 300) {
      // Only the account's own counter is cleared. Clearing the address's too let a client that
      // had one valid account reset its guesses against every other account it tried.
      protection.recordSuccess(userKey);
    }
  }

  /**
   * @param path the request path
   * @return whether it is one of the configured token-bearing public paths
   */
  private boolean isTokenPath(String path) {
    if (path == null || config.bruteForceTokenPaths() == null) {
      return false;
    }
    String normalizedPath = path.toLowerCase(Locale.ROOT);
    while (normalizedPath.endsWith("/")) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }
    for (String configured : config.bruteForceTokenPaths().split(",")) {
      String suffix = configured.trim().toLowerCase(Locale.ROOT);
      if (!suffix.isEmpty() && normalizedPath.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Buffers the login body and pulls the account identifier — iam-svc logins carry {@code email};
   * {@code username} is kept for compatibility with other auth shapes. The stream is restored for
   * the proxy regardless.
   */
  @SuppressWarnings("PMD.CloseResource") // handed on to the proxy when the body is oversized
  String extractUserKey(ContainerRequestContext requestContext) throws IOException {
    InputStream in = requestContext.getEntityStream();
    // At most one byte past the cap is read: a login body never needs more, and an oversized one
    // is handed on unread (head + the rest of the stream) rather than copied whole into heap.
    byte[] body = in == null ? new byte[0] : in.readNBytes(MAX_PARSEABLE_BODY_BYTES + 1);
    if (body.length > MAX_PARSEABLE_BODY_BYTES) {
      requestContext.setEntityStream(new SequenceInputStream(new ByteArrayInputStream(body), in));
      return null;
    }
    requestContext.setEntityStream(new ByteArrayInputStream(body));
    if (in != null) in.close();
    if (body.length == 0) {
      return null;
    }
    try {
      Map<String, Object> map = JSONB.fromJson(new String(body, StandardCharsets.UTF_8), Map.class);
      Object account = map.get("email");
      if (account == null) {
        account = map.get("username");
      }
      if (account != null && !account.toString().isBlank()) {
        return "user:" + account.toString().trim().toLowerCase(Locale.ROOT);
      }
    } catch (Exception ignored) {
      // unparseable body — IP key alone still protects
    }
    return null;
  }

  /**
   * Only credential-checking endpoints count. A broader match (e.g. contains "/auth") would treat
   * RBAC 403s on other auth-prefixed paths (POS session sweep, token admin) as failed logins and
   * lock out a legitimate client's IP.
   */
  private boolean isLoginPath(String path) {
    if (path == null) {
      return false;
    }
    String normalizedPath = path.toLowerCase(Locale.ROOT);
    while (normalizedPath.endsWith("/")) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }
    String loginPath = config.bruteForceLoginPath().toLowerCase(Locale.ROOT);
    return normalizedPath.endsWith(loginPath)
        || normalizedPath.endsWith("/login")
        || normalizedPath.endsWith("/authenticate");
  }
}
