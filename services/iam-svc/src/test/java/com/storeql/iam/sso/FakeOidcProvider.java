package com.storeql.iam.sso;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.storeql.ids.Ids;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * An OpenID Connect provider for tests, on the JDK's own HTTP server: a discovery document, a key
 * set, and a token endpoint that holds a code to everything RFC 6749 and RFC 7636 hold it to — the
 * client's secret, the redirect URI, the PKCE verifier, one use. The authorization step is a method
 * rather than a page: a test says who signs in, and gets the code and state the browser would have
 * brought back.
 */
public final class FakeOidcProvider implements AutoCloseable {

  public static final String CLIENT_ID = "storeql-test-client";
  public static final String CLIENT_SECRET = "a client secret the provider issued";

  /** Who signs in at the provider, and what it says about them. */
  public record Person(String subject, String email, Boolean emailVerified, List<String> amr) {
    public static Person verified(String subject, String email) {
      return new Person(subject, email, true, List.of("pwd"));
    }
  }

  private record Grant(
      Person person, String nonce, String challenge, String redirectUri, String clientId) {}

  private final HttpServer server;
  private final KeyPair keys = rsa();
  private final Map<String, Grant> codes = new ConcurrentHashMap<>();
  private final AtomicInteger tokenCalls = new AtomicInteger();

  /** Changes a token before it is signed; the identity by default. */
  private volatile UnaryOperator<JWTCreator.Builder> tweak = UnaryOperator.identity();

  /** Signs the next tokens with this instead of the published key; null for the published key. */
  private volatile Algorithm signer;

  /**
   * Both endpoints carry a query, as real providers' do (Entra's app-specific keys, a B2C policy):
   * a client must call them exactly as advertised, never with the query escaped into the path.
   */
  static final String JWKS_PATH = "/jwks?appid=storeql";

  static final String TOKEN_PATH = "/token?p=b2c_1_signin";

  private volatile String kid = "key-1";
  private volatile boolean discoveryBroken;

  public FakeOidcProvider() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/.well-known/openid-configuration", this::discovery);
    server.createContext("/jwks", this::jwks);
    server.createContext("/token", this::token);
    server.start();
  }

  public String issuer() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  public int tokenCalls() {
    return tokenCalls.get();
  }

  public void tweakTokens(UnaryOperator<JWTCreator.Builder> tweak) {
    this.tweak = tweak;
  }

  public void signWith(Algorithm signer) {
    this.signer = signer;
  }

  public void breakDiscovery(boolean broken) {
    this.discoveryBroken = broken;
  }

  public void reset() {
    tweak = UnaryOperator.identity();
    signer = null;
    kid = "key-1";
    discoveryBroken = false;
  }

  /**
   * The person signs in at the provider: what the browser would bring back to the callback.
   *
   * @return {@code code} and {@code state}
   */
  public Map<String, String> authorize(String authorizationUrl, Person person) {
    Map<String, String> q = query(URI.create(authorizationUrl).getRawQuery());
    if (!"code".equals(q.get("response_type")) || !"S256".equals(q.get("code_challenge_method"))) {
      throw new IllegalStateException("not a code flow with S256: " + q);
    }
    String code = "code-" + Ids.newId();
    codes.put(
        code,
        new Grant(
            person,
            q.get("nonce"),
            q.get("code_challenge"),
            q.get("redirect_uri"),
            q.get("client_id")));
    return Map.of("code", code, "state", q.get("state"));
  }

  // ── endpoints ───────────────────────────────────────────────────────────────

  private void discovery(HttpExchange ex) throws IOException {
    if (discoveryBroken) {
      send(ex, 500, "{}");
      return;
    }
    String i = issuer();
    send(
        ex,
        200,
        "{\"issuer\":\""
            + i
            + "\",\"authorization_endpoint\":\""
            + i
            + "/authorize\",\"token_endpoint\":\""
            + i
            + TOKEN_PATH
            + "\",\"jwks_uri\":\""
            + i
            + JWKS_PATH
            + "\",\"response_types_supported\":[\"code\"],"
            + "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
            + "\"code_challenge_methods_supported\":[\"S256\"],"
            + "\"token_endpoint_auth_methods_supported\":[\"client_secret_basic\"]}");
  }

  /** Answers 404, and says so, to a request that is not exactly the advertised path and query. */
  private boolean asAdvertised(HttpExchange ex, String advertised) throws IOException {
    URI got = ex.getRequestURI();
    String raw = got.getRawPath() + (got.getRawQuery() == null ? "" : "?" + got.getRawQuery());
    if (raw.equals(advertised)) return true;
    send(ex, 404, "{\"error\":\"not found\",\"path\":\"" + raw + "\"}");
    return false;
  }

  private void jwks(HttpExchange ex) throws IOException {
    if (!asAdvertised(ex, JWKS_PATH)) return;
    RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
    Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
    send(
        ex,
        200,
        "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
            + kid
            + "\",\"n\":\""
            + url.encodeToString(unsigned(pub.getModulus().toByteArray()))
            + "\",\"e\":\""
            + url.encodeToString(unsigned(pub.getPublicExponent().toByteArray()))
            + "\"}]}");
  }

  private void token(HttpExchange ex) throws IOException {
    if (!asAdvertised(ex, TOKEN_PATH)) return;
    tokenCalls.incrementAndGet();
    String auth = ex.getRequestHeaders().getFirst("Authorization");
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (enc(CLIENT_ID) + ":" + enc(CLIENT_SECRET)).getBytes(StandardCharsets.UTF_8));
    if (!expected.equals(auth)) {
      send(ex, 401, "{\"error\":\"invalid_client\"}");
      return;
    }
    Map<String, String> form =
        query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    Grant grant = codes.remove(form.getOrDefault("code", ""));
    if (grant == null
        || !"authorization_code".equals(form.get("grant_type"))
        || !grant.redirectUri().equals(form.get("redirect_uri"))
        || !grant.challenge().equals(Pkce.challenge(form.getOrDefault("code_verifier", "")))) {
      send(ex, 400, "{\"error\":\"invalid_grant\"}");
      return;
    }
    Person p = grant.person();
    Instant now = Instant.now();
    JWTCreator.Builder b =
        JWT.create()
            .withKeyId(kid)
            .withIssuer(issuer())
            .withAudience(grant.clientId())
            .withSubject(p.subject())
            .withClaim("nonce", grant.nonce())
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(300));
    if (p.email() != null) b.withClaim("email", p.email());
    if (p.emailVerified() != null) b.withClaim("email_verified", p.emailVerified());
    if (p.amr() != null) b.withClaim("amr", p.amr());
    Algorithm alg =
        signer != null ? signer : Algorithm.RSA256(null, (RSAPrivateKey) keys.getPrivate());
    String idToken = tweak.apply(b).sign(alg);
    send(
        ex,
        200,
        "{\"access_token\":\"at-"
            + Ids.newId()
            + "\",\"token_type\":\"Bearer\",\"expires_in\":300,\"id_token\":\""
            + idToken
            + "\"}");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A key nobody published, for forgeries. */
  public static Algorithm strangersKey() {
    return Algorithm.RSA256(null, (RSAPrivateKey) rsa().getPrivate());
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] unsigned(byte[] b) {
    return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
  }

  private static String enc(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null || raw.isEmpty()) return out;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static void send(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
