package com.storeql.iam.sso;

import com.storeql.iam.config.ServiceConfig;
import com.storeql.service.Egress;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to a business's identity provider: reads its discovery document and keys, trades an
 * authorization code for an ID token, and checks that token.
 *
 * <p>Every address goes through {@link Egress} first, and redirects are never followed — a redirect
 * is an address the check did not see. Answers are read to a bound, and nothing a provider says is
 * repeated back to the person asking, only a stable code.
 *
 * <p>No circuit breaker: every business has its own provider, and a breaker shared between them
 * would let one business's broken provider sign every other business's staff out of theirs. Each
 * call is bounded by its timeout instead, the documents are cached so a sign-in costs one call to
 * the provider, and a code is never retried — the provider spends it on the first attempt.
 */
@ApplicationScoped
public class OidcClient {

  private static final int MAX_ANSWER_BYTES = 256 * 1024;
  private static final Duration CACHE_FOR = Duration.ofHours(1);

  /** How soon a key set may be read again because a token named a key it lacks. */
  private static final Duration REFETCH_AFTER = Duration.ofSeconds(30);

  @Inject ServiceConfig config;

  private WebClient http;
  private Egress egress;
  private Clock clock = Clock.systemUTC();

  private record Cached<T>(T value, Instant at) {}

  private final Map<String, Cached<Discovery>> discoveries = new ConcurrentHashMap<>();
  private final Map<String, Cached<Jwks>> keySets = new ConcurrentHashMap<>();

  @PostConstruct
  void init() {
    Duration timeout = Duration.ofSeconds(config.ssoHttpTimeoutSeconds());
    use(new Egress(config.ssoInsecureHosts()), timeout, Clock.systemUTC());
  }

  /** For tests: the egress rule, the timeout and the clock this client works to. */
  void use(Egress egress, Duration timeout, Clock clock) {
    this.egress = egress;
    this.clock = clock;
    this.http =
        WebClient.builder()
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .followRedirects(false)
            .build();
  }

  /** An address held to the egress rule, refused in this service's own words. */
  private URI check(String url) {
    try {
      return egress.check(url);
    } catch (Egress.Refused e) {
      throw new SsoRefused(
          e.kind() == Egress.Kind.UNRESOLVED ? SsoRefused.UNREACHABLE : SsoRefused.ADDRESS_REFUSED,
          "provider " + e.getMessage(),
          e);
    }
  }

  /** The provider's discovery document, from the cache when it is fresh. */
  public Discovery discover(String issuer) {
    Cached<Discovery> hit = discoveries.get(issuer);
    if (hit != null && hit.at().plus(CACHE_FOR).isAfter(clock.instant())) return hit.value();
    return discoverFresh(issuer);
  }

  /** The provider's discovery document, read now. */
  public Discovery discoverFresh(String issuer) {
    Discovery d = Discovery.parse(get(Discovery.location(issuer)), issuer);
    // Every address it names is one this service will call: each is held to the same rule.
    check(d.authorizationEndpoint());
    check(d.tokenEndpoint());
    check(d.jwksUri());
    d.userinfoEndpoint().ifPresent(this::check);
    discoveries.put(issuer, new Cached<>(d, clock.instant()));
    return d;
  }

  /** The provider's signing keys, read now. */
  public Jwks keysFresh(Discovery d) {
    Jwks keys = Jwks.parse(get(d.jwksUri()));
    keySets.put(d.jwksUri(), new Cached<>(keys, clock.instant()));
    return keys;
  }

  /**
   * The key a token names. A key the cached set lacks sends the set to be read again — that is how
   * a provider's rotation reaches here — but not more than every half-minute, so a stream of tokens
   * naming keys nobody published cannot make this service hammer the provider.
   */
  private Optional<RSAPublicKey> key(Discovery d, String kid) {
    Cached<Jwks> hit = keySets.get(d.jwksUri());
    Instant now = clock.instant();
    if (hit == null || hit.at().plus(CACHE_FOR).isBefore(now)) {
      return keysFresh(d).key(kid);
    }
    Optional<RSAPublicKey> key = hit.value().key(kid);
    if (key.isEmpty() && hit.at().plus(REFETCH_AFTER).isBefore(now)) {
      return keysFresh(d).key(kid);
    }
    return key;
  }

  /**
   * Trades the code the provider sent the browser back with for an ID token, and checks it.
   *
   * @param redirectUri the callback the code was issued for, exactly as the authorization request
   *     named it
   * @param verifier the PKCE verifier this sign-in's challenge was made from
   * @param nonce the nonce this sign-in was sent with
   */
  public IdToken exchange(
      Discovery d,
      String clientId,
      String clientSecret,
      String code,
      String redirectUri,
      String verifier,
      String nonce) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("code_verifier", verifier);
    HttpClientRequest request =
        http.post(check(d.tokenEndpoint()).toString())
            .header(HeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded")
            .header(HeaderNames.ACCEPT, "application/json");
    if (Discovery.BASIC.equals(d.tokenAuthMethod())) {
      // RFC 6749 §2.3.1: each half form-encoded before the pair is base64'd.
      String pair = formEncode(clientId) + ":" + formEncode(clientSecret);
      request =
          request.header(
              HeaderNames.AUTHORIZATION,
              "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    } else {
      form.put("client_id", clientId);
      form.put("client_secret", clientSecret);
    }
    JsonObject answer;
    try (HttpClientResponse res = request.submit(form(form))) {
      int status = res.status().code();
      String body = read(res);
      if (status >= 500) {
        throw new SsoRefused(SsoRefused.UNREACHABLE, "token endpoint answered HTTP " + status);
      }
      if (status != 200) {
        throw new SsoRefused(
            SsoRefused.EXCHANGE_REFUSED,
            "token endpoint refused the code: HTTP " + status + " " + errorOf(body));
      }
      answer = object(body, SsoRefused.EXCHANGE_REFUSED);
    } catch (SsoRefused e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SsoRefused(SsoRefused.UNREACHABLE, "token endpoint unreachable", e);
    }
    if (!(answer.get("id_token") instanceof JsonString idToken)) {
      throw new SsoRefused(SsoRefused.EXCHANGE_REFUSED, "no ID token in the provider's answer");
    }
    IdToken proved =
        IdToken.verify(idToken.getString(), kid -> key(d, kid), d.issuer(), clientId, nonce, clock);
    if (proved.email() != null || d.userinfoEndpoint().isEmpty()) return proved;
    // Some providers keep the address out of the token and give it at userinfo. Asked only when
    // it is missing, and believed only for the same subject (OpenID Connect Core §5.3.2).
    return answer.get("access_token") instanceof JsonString access
        ? contact(d.userinfoEndpoint().get(), access.getString(), proved)
        : proved;
  }

  private IdToken contact(String userinfo, String accessToken, IdToken proved) {
    try (HttpClientResponse res =
        http.get(check(userinfo).toString())
            .header(HeaderNames.AUTHORIZATION, "Bearer " + accessToken)
            .header(HeaderNames.ACCEPT, "application/json")
            .request()) {
      if (res.status().code() != 200) return proved;
      JsonObject info = object(read(res), SsoRefused.EXCHANGE_REFUSED);
      if (!proved.subject().equals(info.getString("sub", null))) {
        throw new SsoRefused(SsoRefused.ID_TOKEN_INVALID, "userinfo names another subject");
      }
      String email = info.get("email") instanceof JsonString s ? s.getString().trim() : null;
      return email == null || email.isEmpty()
          ? proved
          : proved.withContact(email, verifiedOf(info.get("email_verified")));
    } catch (SsoRefused e) {
      throw e;
    } catch (RuntimeException e) {
      // Userinfo is a second chance at an address, not a requirement: without it the sign-in is
      // judged on what the ID token said.
      return proved;
    }
  }

  /** True, or the string of it: some providers send {@code "true"}. Anything else is not. */
  private static boolean verifiedOf(jakarta.json.JsonValue v) {
    if (v instanceof JsonString s) return "true".equalsIgnoreCase(s.getString().trim());
    return v != null && v.getValueType() == jakarta.json.JsonValue.ValueType.TRUE;
  }

  private String get(String url) {
    URI checked = check(url);
    try (HttpClientResponse res =
        http.get(checked.toString()).header(HeaderNames.ACCEPT, "application/json").request()) {
      int status = res.status().code();
      if (status != 200) {
        throw new SsoRefused(
            status >= 500 || status == 404 || (status >= 300 && status < 400)
                ? SsoRefused.UNREACHABLE
                : SsoRefused.DISCOVERY_INVALID,
            "GET " + checked.getHost() + checked.getPath() + " answered HTTP " + status);
      }
      return read(res);
    } catch (SsoRefused e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SsoRefused(
          SsoRefused.UNREACHABLE, "GET " + checked.getHost() + checked.getPath() + " failed", e);
    }
  }

  private static String read(HttpClientResponse res) {
    if (!res.entity().hasEntity()) return "";
    try (InputStream in = res.entity().inputStream()) {
      byte[] body = in.readNBytes(MAX_ANSWER_BYTES + 1);
      if (body.length > MAX_ANSWER_BYTES) {
        throw new SsoRefused(SsoRefused.DISCOVERY_INVALID, "the provider's answer is too large");
      }
      return new String(body, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new SsoRefused(SsoRefused.UNREACHABLE, "the provider's answer broke off", e);
    }
  }

  private static JsonObject object(String body, String code) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    } catch (JsonException | IllegalStateException e) {
      throw new SsoRefused(code, "the provider's answer is not a JSON object", e);
    }
  }

  /** The OAuth error code, and only if it looks like one: nothing else of the body is logged. */
  private static String errorOf(String body) {
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      String error = reader.readObject().getString("error", "");
      return error.matches("[a-z_]{1,64}") ? error : "";
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static String form(Map<String, String> fields) {
    StringBuilder out = new StringBuilder();
    fields.forEach(
        (k, v) -> {
          if (!out.isEmpty()) out.append('&');
          out.append(formEncode(k)).append('=').append(formEncode(v));
        });
    return out.toString();
  }

  private static String formEncode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
