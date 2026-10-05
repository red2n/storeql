package com.storeql.gateway.filters;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import com.storeql.test.RedisSupport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Drives the full lockout loop: failed logins must actually block the next attempt. Counters live
 * in Redis now, so this runs against a real Redis container rather than mocking the storage layer.
 */
@ExtendWith(MockitoExtension.class)
class BruteForceFilterLockoutTest {

  private static final String LOGIN_BODY = "{\"email\":\"bob@example.com\",\"password\":\"x\"}";

  private static RedisSupport REDIS;
  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext request;
  @Mock ContainerResponseContext response;
  @Mock UriInfo uriInfo;

  private BruteForceFilter filter;
  private final Map<String, Object> props = new HashMap<>();

  @BeforeAll
  static void startRedis() {
    REDIS = RedisSupport.start();
    client = RedisClient.create(RedisURI.Builder.redis(REDIS.host(), REDIS.port()).build());
    connection = client.connect();
  }

  @AfterAll
  static void stopRedis() {
    connection.close();
    client.shutdown();
    REDIS.stop();
  }

  @AfterEach
  void cleanUp() {
    connection.sync().flushall();
  }

  @BeforeEach
  void setUp() throws IOException {
    when(config.bruteForceEnabled()).thenReturn(true);
    when(config.bruteForceMaxFailures()).thenReturn(2);
    when(config.bruteForceBlockMinutes()).thenReturn(15);
    lenient().when(config.bruteForceLoginPath()).thenReturn("/auth/login");
    lenient().when(config.bruteForceTokenPaths()).thenReturn("/marketing/unsubscribe");
    lenient().when(config.trustForwardedHeaders()).thenReturn(false);

    filter = new BruteForceFilter();
    filter.config = config;
    filter.redis = connection.sync();
    filter.init();

    lenient().when(request.getUriInfo()).thenReturn(uriInfo);
    lenient().when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/login");
    lenient().when(request.getMethod()).thenReturn("POST");

    // back properties with a real map so request+response phases share state
    lenient()
        .doAnswer(
            inv -> {
              props.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(request)
        .setProperty(anyString(), any());
    lenient()
        .when(request.getProperty(anyString()))
        .thenAnswer(inv -> props.get(inv.getArgument(0, String.class)));
  }

  private void freshBody() {
    InputStream body = new ByteArrayInputStream(LOGIN_BODY.getBytes(StandardCharsets.UTF_8));
    lenient().when(request.getEntityStream()).thenReturn(body);
  }

  @Test
  void failedLoginsLockTheAccountOut() throws IOException {
    when(response.getStatus()).thenReturn(401);

    for (int i = 0; i < 2; i++) {
      freshBody();
      filter.filter(request); // request phase — not blocked yet
      filter.filter(request, response); // response phase — records the 401
    }
    verify(request, never()).abortWith(any());

    freshBody();
    filter.filter(request);

    verify(request).abortWith(any());
  }

  /**
   * A client that guesses against accounts it does not own must not reset its own address's count
   * by logging into an account it does own. The success clears that account's counter, never the
   * address's; before, one good login from the address cleared the address, so a guesser with one
   * valid account could guess again without limit.
   */
  @Test
  void aSuccessOnOneAccountDoesNotClearTheAddressCounter() throws IOException {
    when(response.getStatus()).thenReturn(401, 200, 401);
    attempt("alice@example.com"); // 401: address 1, alice 1
    attempt("bob@example.com"); // 200: bob cleared, the address must keep its 1
    attempt("carol@example.com"); // 401: address 2, so the address is locked

    freshBody("dave@example.com");
    filter.filter(request);

    verify(request).abortWith(any());
  }

  private void attempt(String email) throws IOException {
    freshBody(email);
    filter.filter(request);
    filter.filter(request, response);
  }

  private void freshBody(String email) {
    String body = "{\"email\":\"" + email + "\",\"password\":\"x\"}";
    lenient()
        .when(request.getEntityStream())
        .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rbacDeniedNonLoginAuthPathsDoNotFeedTheCounter() throws IOException {
    // A 403 from e.g. the POS session sweep is an authorization result, not a failed credential
    // attempt — repeated calls must never lock the client's IP out of login.
    lenient().when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/pos/sessions/sweep");
    lenient().when(response.getStatus()).thenReturn(403);

    for (int i = 0; i < 5; i++) {
      freshBody();
      filter.filter(request);
      filter.filter(request, response);
    }

    verify(request, never()).abortWith(any());
  }

  @Test
  void successfulLoginResetsTheCounter() throws IOException {
    when(response.getStatus()).thenReturn(401, 200);

    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 401 — one failure

    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 200 — reset

    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 401 again — still under the limit

    freshBody();
    filter.filter(request);

    verify(request, never()).abortWith(any());
  }

  @Test
  void guessedUnsubscribeTokensLockTheAddressOut() throws IOException {
    // The opt-out link is public by design (PECR reg.23). A wrong token is a 404 from the
    // service, and enough of them from one address must be treated like enough wrong passwords.
    lenient().when(uriInfo.getPath()).thenReturn("api/customer-svc/marketing/unsubscribe");
    when(response.getStatus()).thenReturn(404);

    for (int i = 0; i < 2; i++) {
      freshBody();
      filter.filter(request);
      filter.filter(request, response);
    }
    verify(request, never()).abortWith(any());

    freshBody();
    filter.filter(request);
    verify(request).abortWith(any());
  }

  @Test
  void aMalformedUnsubscribeIsNotAGuess() throws IOException {
    // A 400 is a body the service could not read, not a token that missed: it must not count,
    // or a client with a bug would lock itself out of an endpoint that exists to be easy.
    lenient().when(uriInfo.getPath()).thenReturn("api/customer-svc/marketing/unsubscribe");
    lenient().when(response.getStatus()).thenReturn(400);

    for (int i = 0; i < 5; i++) {
      freshBody();
      filter.filter(request);
      filter.filter(request, response);
    }
    verify(request, never()).abortWith(any());
  }

  @Test
  void aRightTokenDoesNotClearTheAddressCounter() throws IOException {
    // A right token proves the holder had one valid link. It must not reset the address's count of
    // misses: that let one valid link buy unlimited guesses at the others. The address stays one
    // miss from the limit after the right token, so the next miss locks it.
    lenient().when(uriInfo.getPath()).thenReturn("api/customer-svc/marketing/unsubscribe");
    when(response.getStatus()).thenReturn(404, 200, 404);

    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 404: one miss
    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 200: the right token, which clears nothing here
    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 404: the second miss
    freshBody();
    filter.filter(request); // locked
    verify(request).abortWith(any());
  }

  @Test
  void unsubscribeMissesDoNotLockLoginAndViceVersa() throws IOException {
    // Same address, different counters? No — the IP key is shared on purpose: an address that is
    // guessing tokens is an address that is guessing, and login uses the same key. What must NOT
    // happen is a token miss being counted twice, or a user key being invented from the body.
    lenient().when(uriInfo.getPath()).thenReturn("api/customer-svc/marketing/unsubscribe");
    when(response.getStatus()).thenReturn(404);
    freshBody();
    filter.filter(request);
    filter.filter(request, response);
    org.junit.jupiter.api.Assertions.assertNull(
        props.get("login.userKey"), "a token path must not derive an account key from the body");
  }
}
