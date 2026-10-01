package com.storeql.notification.provider;

import com.storeql.notification.json.Jsons;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Push through Firebase Cloud Messaging's HTTP v1 API. Configured with a project id and a service
 * account: the account's key signs a short-lived JWT that is exchanged for an access token, which
 * is cached until shortly before it expires. Both endpoints are overridable so the tests can stand
 * fakes at the other end and read what was sent.
 */
@ApplicationScoped
public class FcmPushProvider implements PushProvider {

  public static final String NAME = "FCM";
  private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

  @Inject
  @ConfigProperty(
      name = "storeql.notification.push.fcm.base-url",
      defaultValue = "https://fcm.googleapis.com")
  String baseUrl;

  @Inject
  @ConfigProperty(
      name = "storeql.notification.push.fcm.token-url",
      defaultValue = "https://oauth2.googleapis.com/token")
  volatile String tokenUrl;

  @Inject
  @ConfigProperty(name = "storeql.notification.push.fcm.project-id")
  Optional<String> projectIdConfig;

  /** The service account as its JSON, or the path of the file that holds it. */
  @Inject
  @ConfigProperty(name = "storeql.notification.push.fcm.service-account")
  Optional<String> serviceAccountConfig;

  String projectId = "";
  String clientEmail = "";
  PrivateKey privateKey;
  private WebClient webClient;

  /** A token and the moment it stops being good: read whole, so a reader never sees half of one. */
  private record Token(String value, Instant expiry) {
    boolean fresh() {
      return Instant.now().isBefore(expiry.minusSeconds(60));
    }
  }

  private volatile Token token;
  private final java.util.concurrent.locks.ReentrantLock renewal =
      new java.util.concurrent.locks.ReentrantLock();

  @PostConstruct
  void init() {
    projectId = projectIdConfig.orElse("").trim();
    serviceAccountConfig.filter(s -> !s.isBlank()).ifPresent(this::loadServiceAccount);
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(15))
            .build();
  }

  static FcmPushProvider forTest(
      String baseUrl, String tokenUrl, String projectId, String serviceAccountJson) {
    FcmPushProvider p = new FcmPushProvider();
    p.baseUrl = baseUrl;
    p.tokenUrl = tokenUrl;
    p.projectId = projectId;
    p.loadServiceAccount(serviceAccountJson);
    p.webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    return p;
  }

  void loadServiceAccount(String jsonOrPath) {
    String json = jsonOrPath.trim();
    try {
      if (!json.startsWith("{")) {
        json = Files.readString(Path.of(json), StandardCharsets.UTF_8);
      }
      try (JsonReader r = Jsons.reader(new StringReader(json))) {
        JsonObject o = r.readObject();
        clientEmail = o.getString("client_email", "");
        if (projectId.isEmpty()) projectId = o.getString("project_id", "");
        String pem =
            o.getString("private_key", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      }
    } catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
      throw new IllegalStateException(
          "FCM service account could not be loaded: " + e.getMessage(), e);
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return !projectId.isEmpty() && !clientEmail.isEmpty() && privateKey != null;
  }

  @Override
  public String send(String token, String title, String body, Map<String, String> data) {
    if (!isConfigured()) {
      throw new ProviderException("PUSH_NOT_CONFIGURED", "FCM is not configured", false);
    }
    JsonObjectBuilder payload = Jsons.object();
    data.forEach(payload::add);
    String message =
        Jsons.object()
            .add(
                "message",
                Jsons.object()
                    .add("token", token)
                    .add("notification", Jsons.object().add("title", title).add("body", body))
                    .add("data", payload))
            .build()
            .toString();
    try (HttpClientResponse res =
        webClient
            .post(baseUrl + "/v1/projects/" + projectId + "/messages:send")
            .header(HeaderNames.AUTHORIZATION, "Bearer " + accessToken())
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(message)) {
      int status = res.status().code();
      String text = Bodies.text(res);
      if (status == 200) {
        return field(text, "name", "unknown");
      }
      String fcmError = errorStatus(text);
      if (status == 404 || "UNREGISTERED".equals(fcmError) || "NOT_FOUND".equals(fcmError)) {
        throw new ProviderException(
            ProviderException.UNREGISTERED, "FCM no longer knows this device", false);
      }
      if (status >= 500 || status == 429) {
        throw new ProviderException("PUSH_PROVIDER_UNAVAILABLE", "FCM answered " + status, true);
      }
      throw new ProviderException(
          "PUSH_REJECTED_" + fcmError, "FCM answered " + status + ": " + fcmError, false);
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProviderException(
          "PUSH_PROVIDER_UNREACHABLE", "FCM could not be reached: " + e.getMessage(), true, e);
    }
  }

  /** A cached OAuth2 access token, renewed a minute before it expires. */
  String accessToken() {
    Token current = token;
    if (current != null && current.fresh()) {
      return current.value();
    }
    // One caller renews, the rest wait for it and use what it fetched. A ReentrantLock, not
    // synchronized: the exchange is a blocking HTTP call and a monitor would pin the carrier.
    renewal.lock();
    try {
      current = token;
      if (current != null && current.fresh()) {
        return current.value();
      }
      token = exchange();
      return token.value();
    } finally {
      renewal.unlock();
    }
  }

  private Token exchange() {
    String assertion = signedJwt();
    String form =
        "grant_type="
            + URLEncoder.encode(
                "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&assertion="
            + assertion;
    try (HttpClientResponse res =
        webClient
            .post(tokenUrl)
            .header(HeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded")
            .submit(form)) {
      String text = Bodies.text(res);
      if (res.status().code() != 200) {
        throw new ProviderException(
            "PUSH_AUTH_FAILED", "FCM token exchange answered " + res.status().code(), true);
      }
      try (JsonReader r = Jsons.reader(new StringReader(text))) {
        JsonObject o = r.readObject();
        return new Token(
            o.getString("access_token"),
            Instant.now().plusSeconds(o.getJsonNumber("expires_in").longValue()));
      }
    }
  }

  /** The service account's RS256-signed assertion, valid for an hour. */
  String signedJwt() {
    long now = Instant.now().getEpochSecond();
    String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        b64(
            Jsons.object()
                .add("iss", clientEmail)
                .add("scope", SCOPE)
                .add("aud", tokenUrl)
                .add("iat", now)
                .add("exp", now + 3600)
                .build()
                .toString()
                .getBytes(StandardCharsets.UTF_8));
    try {
      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initSign(privateKey);
      sig.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
      return header + "." + claims + "." + b64(sig.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new ProviderException(
          "PUSH_AUTH_FAILED", "could not sign the FCM assertion: " + e.getMessage(), false, e);
    }
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String errorStatus(String json) {
    try (JsonReader r = Jsons.reader(new StringReader(json))) {
      JsonObject o = r.readObject();
      JsonObject err = o.getJsonObject("error");
      return err == null ? "UNKNOWN" : err.getString("status", "UNKNOWN");
    } catch (RuntimeException e) {
      return "UNKNOWN";
    }
  }

  private static String field(String json, String key, String fallback) {
    try (JsonReader r = Jsons.reader(new StringReader(json))) {
      JsonObject o = r.readObject();
      return o.containsKey(key) && !o.isNull(key) ? o.getString(key) : fallback;
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
