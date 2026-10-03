package com.storeql.purchase.client;

import com.storeql.purchase.config.Jsons;
import com.storeql.purchase.domain.EInvoiceInbox.Waiting;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Fetching a Polish buyer's invoices out of KSeF (07.13).
 *
 * <p>The mirror image of the sending client in order-svc, and much the smaller half: signing in is
 * the same dance — a challenge, the business's token sealed under the ministry's RSA key with the
 * challenge's own timestamp, a reference to poll, an access token redeemed — and then two calls.
 * The metadata query says which invoices the system is holding for this buyer over a date range;
 * each is downloaded by its KSeF number. A buyer's download is not session-encrypted, which is why
 * there is no AES here and a great deal of it in the sender.
 *
 * <p>Written against {@code java.net.http} rather than a framework client on purpose: this talks to
 * one external system with a fixed shape, and a test points it at a stub by changing one URL.
 */
@ApplicationScoped
public class KsefInbox {

  /** The usage the ministry marks the key that seals a token with. */
  static final String USAGE_TOKEN = "KsefTokenEncryption";

  /** The sign-in status the system reports while it is still thinking. */
  static final int TAKEN = 100;

  static final int ACCEPTED = 200;

  private static final int PAGE = 100;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.ksef.base-url")
  Optional<String> baseUrlConfig;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.ksef.timeout-seconds", defaultValue = "20")
  long timeoutSeconds;

  private volatile HttpClient http;

  /** The network could not be reached, or refused. Distinguished so a fetch can be tried again. */
  public static class KsefException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public KsefException(String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.retryable = retryable;
    }

    /** Whether asking again later is worth anything: a network down, not a token refused. */
    public boolean retryable() {
      return retryable;
    }
  }

  /** Whether this deployment can reach KSeF at all. */
  public boolean isConfigured() {
    return baseUrlConfig.filter(u -> !u.isBlank()).isPresent();
  }

  /** For tests: a client pointed at a stub, with no CDI. */
  public static KsefInbox forTest(String baseUrl) {
    KsefInbox k = new KsefInbox();
    k.baseUrlConfig = Optional.ofNullable(baseUrl);
    k.timeoutSeconds = 10;
    return k;
  }

  private HttpClient http() {
    if (http == null) {
      // Built under a lock (no I/O inside it) so two first fetches share one client, and its
      // selector thread, rather than each making one.
      synchronized (this) {
        if (http == null) {
          http =
              HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .build();
        }
      }
    }
    return http;
  }

  /** The client owns a selector thread; closed with the application. */
  @PreDestroy
  void close() {
    synchronized (this) {
      if (http != null) {
        http.close();
        http = null;
      }
    }
  }

  private String baseUrl() {
    String url = baseUrlConfig.orElse("");
    if (url.isBlank()) {
      throw new KsefException("this deployment has no KSeF endpoint configured", false, null);
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /**
   * Which invoices the system is holding for a buyer over a window.
   *
   * @param nip the buyer's Polish tax number
   * @param ksefToken the business's own token, opened
   */
  public List<Waiting> waiting(String nip, String ksefToken, LocalDate from, LocalDate to) {
    String access = accessToken(nip, ksefToken);
    List<Waiting> out = new ArrayList<>();
    JsonObject body =
        Jsons.PROVIDER
            .createObjectBuilder()
            .add("subjectType", "Subject2")
            .add(
                "dateRange",
                Jsons.PROVIDER
                    .createObjectBuilder()
                    .add("dateType", "Issue")
                    .add("from", from.toString())
                    .add("to", to.toString()))
            .build();
    Reply reply =
        call("/invoices/query/metadata?pageOffset=0&pageSize=" + PAGE, body.toString(), access);
    if (!reply.ok()) {
      throw new KsefException(
          "KSeF would not list the invoices: HTTP " + reply.status(), reply.status() >= 500, null);
    }
    JsonObject answer = reply.object();
    JsonArray invoices =
        answer.containsKey("invoices")
            ? answer.getJsonArray("invoices")
            : Jsons.PROVIDER.createArrayBuilder().build();
    for (JsonValue v : invoices) {
      JsonObject o = v.asJsonObject();
      String reference = o.getString("ksefNumber", o.getString("referenceNumber", null));
      if (reference == null) continue;
      out.add(
          new Waiting(
              reference,
              date(o.getString("invoicingDate", o.getString("issueDate", null))),
              o.containsKey("seller") ? o.getJsonObject("seller").getString("nip", null) : null,
              o.getString("invoiceNumber", null)));
    }
    return out;
  }

  /**
   * One invoice, as FA(3).
   *
   * @return the document's bytes, exactly as the system gave them: what is kept is what arrived
   */
  public byte[] download(String nip, String ksefToken, String reference) {
    return download(accessToken(nip, ksefToken), reference);
  }

  /**
   * Signs in for a fetch: the access token a run of {@link #download(String, String)} calls shares,
   * so a fetch of many invoices signs in once rather than once per invoice. Held by the caller for
   * the length of the fetch only, never cached here.
   */
  public String signIn(String nip, String ksefToken) {
    return accessToken(nip, ksefToken);
  }

  /** One invoice, as FA(3), for a caller that has already signed in. */
  public byte[] download(String access, String reference) {
    Reply reply = call("/invoices/ksef/" + reference, null, access);
    if (!reply.ok()) {
      throw new KsefException(
          "KSeF would not give invoice " + reference + ": HTTP " + reply.status(),
          reply.status() >= 500,
          null);
    }
    return reply.body().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Signs in and stops there: everything that can be wrong before a first fetch.
   *
   * <p>The ministry unreachable, its keys unreadable, the business's token refused — three
   * different problems with three different remedies, and a check that could not tell them apart
   * would be no better than the fetch failing.
   *
   * @return null when the sign-in worked; otherwise why it did not, and whether asking again may
   *     help
   */
  public KsefException checkSignIn(String nip, String ksefToken) {
    try {
      accessToken(nip, ksefToken);
      return null;
    } catch (KsefException e) {
      return e;
    }
  }

  // ── signing in ──────────────────────────────────────────────────────────────

  /**
   * The access token for this business, signing in if need be.
   *
   * <p>Not cached: a fetch happens at most a few times a day, and a token held across them is a
   * credential kept in memory for hours to save a round trip nobody is waiting for.
   */
  private String accessToken(String nip, String ksefToken) {
    PublicKey tokenKey = tokenKey();
    Reply challenge = call("/auth/challenge", "{}", null);
    if (!challenge.ok()) {
      throw new KsefException(
          "KSeF gave no challenge: HTTP " + challenge.status(), challenge.status() >= 500, null);
    }
    JsonObject c = challenge.object();
    long ms =
        c.containsKey("timestampMs")
            ? c.getJsonNumber("timestampMs").longValue()
            : Instant.parse(c.getString("timestamp")).toEpochMilli();
    String sealed = seal(tokenKey, ksefToken + "|" + ms);
    JsonObject init =
        Jsons.PROVIDER
            .createObjectBuilder()
            .add("challenge", c.getString("challenge"))
            .add(
                "contextIdentifier",
                Jsons.PROVIDER.createObjectBuilder().add("type", "Nip").add("value", nip))
            .add("encryptedToken", sealed)
            .build();
    Reply started = call("/auth/ksef-token", init.toString(), null);
    if (!started.ok()) {
      throw new KsefException(
          "KSeF did not accept the business's token: HTTP " + started.status(),
          started.status() >= 500,
          null);
    }
    JsonObject s = started.object();
    String ref = s.getString("referenceNumber", "");
    String authToken =
        s.containsKey("authenticationToken")
            ? s.getJsonObject("authenticationToken").getString("token", "")
            : "";
    for (int i = 0; i < 20; i++) {
      Reply status = call("/auth/" + ref, null, authToken);
      if (!status.ok()) {
        throw new KsefException(
            "KSeF did not answer for the sign-in: HTTP " + status.status(),
            status.status() >= 500,
            null);
      }
      JsonObject st = status.object().getJsonObject("status");
      int code = st == null ? 0 : st.getInt("code", 0);
      if (code == ACCEPTED) break;
      if (code != TAKEN && code != 0) {
        throw new KsefException(
            "KSeF refused the sign-in: "
                + code
                + " "
                + (st == null ? "" : st.getString("description", "")),
            false,
            null);
      }
      sleep();
    }
    Reply redeemed = call("/auth/token/redeem", "{}", authToken);
    if (!redeemed.ok()) {
      throw new KsefException(
          "KSeF gave no access token: HTTP " + redeemed.status(), redeemed.status() >= 500, null);
    }
    JsonObject t = redeemed.object().getJsonObject("accessToken");
    if (t == null || t.getString("token", "").isBlank()) {
      throw new KsefException("KSeF's access token was empty", true, null);
    }
    return t.getString("token");
  }

  /** The ministry's key for sealing a token, from the certificates it publishes. */
  private PublicKey tokenKey() {
    Reply reply = call("/security/public-key-certificates", null, null);
    if (!reply.ok()) {
      throw new KsefException(
          "KSeF gave no public keys: HTTP " + reply.status(), reply.status() >= 500, null);
    }
    try (JsonReader r = Jsons.PROVIDER.createReader(new StringReader(reply.body()))) {
      for (JsonValue v : r.readArray()) {
        JsonObject cert = v.asJsonObject();
        JsonArray usage =
            cert.containsKey("usage")
                ? cert.getJsonArray("usage")
                : Jsons.PROVIDER.createArrayBuilder().build();
        for (JsonValue u : usage) {
          String use =
              u.getValueType() == JsonValue.ValueType.STRING
                  ? ((jakarta.json.JsonString) u).getString()
                  : u.toString();
          if (USAGE_TOKEN.equals(use)) return publicKey(cert.getString("certificate", ""));
        }
      }
    } catch (RuntimeException e) {
      throw new KsefException("KSeF's keys could not be read: " + e.getMessage(), false, e);
    }
    throw new KsefException("KSeF published no key for sealing a token", false, null);
  }

  /** The ministry publishes X.509 certificates; a test stands a bare public key in their place. */
  static PublicKey publicKey(String base64) {
    byte[] der = Base64.getDecoder().decode(base64.strip());
    try {
      X509Certificate cert =
          (X509Certificate)
              CertificateFactory.getInstance("X.509")
                  .generateCertificate(new ByteArrayInputStream(der));
      return cert.getPublicKey();
    } catch (GeneralSecurityException | RuntimeException notACertificate) {
      try {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      } catch (GeneralSecurityException notAKeyEither) {
        KsefException e = new KsefException("KSeF's key could not be read", false, notACertificate);
        e.addSuppressed(notAKeyEither);
        throw e;
      }
    }
  }

  /** RSA-OAEP with SHA-256, as KSeF asks for the token. */
  private static String seal(PublicKey key, String plain) {
    try {
      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          key,
          new OAEPParameterSpec(
              "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return Base64.getEncoder()
          .encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new KsefException("the business's token could not be sealed for KSeF", false, e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(250);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KsefException("interrupted while KSeF signed in", true, e);
    }
  }

  // ── the wire ────────────────────────────────────────────────────────────────

  /** What the system answered. */
  record Reply(int status, String body) {
    boolean ok() {
      return status >= 200 && status < 300;
    }

    JsonObject object() {
      try (JsonReader r =
          Jsons.PROVIDER.createReader(
              new StringReader(body == null || body.isBlank() ? "{}" : body))) {
        return r.readObject();
      } catch (RuntimeException e) {
        return Jsons.PROVIDER.createObjectBuilder().build();
      }
    }
  }

  /**
   * One call. A null body is a GET and a body is a POST — the body decides, rather than a verb
   * passed beside it that could disagree with it.
   */
  private Reply call(String path, String body, String bearer) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
            .header("Accept", "application/json");
    if (bearer != null && !bearer.isBlank()) b.header("Authorization", "Bearer " + bearer);
    if (body == null) {
      b.GET();
    } else {
      b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }
    try {
      HttpResponse<String> response = http().send(b.build(), HttpResponse.BodyHandlers.ofString());
      return new Reply(response.statusCode(), response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KsefException("interrupted while talking to KSeF", true, e);
    } catch (java.io.IOException e) {
      throw new KsefException("KSeF could not be reached: " + e.getMessage(), true, e);
    }
  }

  private static LocalDate date(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
