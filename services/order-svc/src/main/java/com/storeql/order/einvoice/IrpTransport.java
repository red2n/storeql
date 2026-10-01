package com.storeql.order.einvoice;

import com.storeql.order.config.Json;
import com.storeql.order.domain.EInvoiceTransports;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * India's Invoice Registration Portal (NIC e-invoice API 1.04, as a GSP exposes it). The business
 * signs in as the taxpayer: its API user and password, the password and a fresh 32-byte AppKey
 * sealed with the portal's RSA public key; the portal answers with a token and a session key (SEK)
 * sealed with the AppKey. The INV-01 is then sent as base64, AES-encrypted with the SEK, and the
 * portal's answer — the IRN, the acknowledgement, the signed invoice and QR code — comes back the
 * same way. Configured with the platform's credentials ({@code storeql.einvoice.irp.base-url},
 * {@code .client-id}, {@code .client-secret}, {@code .public-key}); the business's own user and
 * password are its account and secret in the settings. Deployed always, choosable only configured.
 */
@ApplicationScoped
public class IrpTransport implements EInvoiceTransport {

  public static final String NAME = "NIC";

  /** The portal's error code for an invoice already registered; the IRN is in the alert. */
  static final String DUPLICATE_IRN = "2150";

  private static final DateTimeFormatter EXPIRY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Inject
  @ConfigProperty(name = "storeql.einvoice.irp.base-url")
  Optional<String> baseUrlConfig;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.irp.auth-path", defaultValue = "/eivital/v1.04/auth")
  String authPath;

  @Inject
  @ConfigProperty(
      name = "storeql.einvoice.irp.invoice-path",
      defaultValue = "/eicore/v1.03/Invoice")
  String invoicePath;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.irp.client-id")
  Optional<String> clientIdConfig;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.irp.client-secret")
  Optional<String> clientSecretConfig;

  /** The portal's RSA public key, base64 (X.509 SubjectPublicKeyInfo). */
  @Inject
  @ConfigProperty(name = "storeql.einvoice.irp.public-key")
  Optional<String> publicKeyConfig;

  String baseUrl = "";
  String clientId = "";
  String clientSecret = "";
  PublicKey publicKey;
  private WebClient web;
  private final SecureRandom random = new SecureRandom();

  /** A session per taxpayer and user, kept until shortly before the portal expires it. */
  private record Session(String token, byte[] sek, Instant until) {}

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  @PostConstruct
  void init() {
    baseUrl = baseUrlConfig.orElse("").strip();
    clientId = clientIdConfig.orElse("").strip();
    clientSecret = clientSecretConfig.orElse("").strip();
    publicKey = publicKeyConfig.filter(k -> !k.isBlank()).map(IrpTransport::keyOf).orElse(null);
    web = client();
  }

  /** For tests: a transport pointed at a stub portal, with no CDI. */
  static IrpTransport forTest(
      String baseUrl,
      String authPath,
      String invoicePath,
      String clientId,
      String clientSecret,
      String publicKeyBase64) {
    IrpTransport t = new IrpTransport();
    t.baseUrl = baseUrl;
    t.authPath = authPath;
    t.invoicePath = invoicePath;
    t.clientId = clientId;
    t.clientSecret = clientSecret;
    t.publicKey = keyOf(publicKeyBase64);
    t.web = client();
    return t;
  }

  private static WebClient client() {
    return WebClient.builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(20))
        .build();
  }

  private static PublicKey keyOf(String base64) {
    try {
      return KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "storeql.einvoice.irp.public-key is not an RSA public key", e);
    }
  }

  @Override
  public Set<String> networks() {
    return Set.of(EInvoiceTransports.NETWORK_IRP);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return !baseUrl.isBlank()
        && !clientId.isBlank()
        && !clientSecret.isBlank()
        && publicKey != null;
  }

  @Override
  public String configuration() {
    return "storeql.einvoice.irp.base-url, client-id, client-secret and public-key, and the"
        + " business's portal user as its account and password as its secret";
  }

  @Override
  public boolean needsSecret() {
    return true;
  }

  @Override
  public Dispatch send(Outbound d) {
    if (d.irpJson() == null || d.irpJson().isBlank()) {
      return new Dispatch(
          null,
          Outcome.rejected(
              "the portal would refuse this document; see its objections on the invoice", null));
    }
    if (blank(d.sellerVatId()) || blank(d.providerAccount()) || blank(d.providerSecret())) {
      return new Dispatch(
          null,
          Outcome.rejected(
              "the portal needs the business's GSTIN, API user and password; set them in the"
                  + " transport settings",
              null));
    }
    Session session = session(d.sellerVatId(), d.providerAccount(), d.providerSecret());
    String sessionKey = key(d.sellerVatId(), d.providerAccount(), d.providerSecret());
    if (session == null) {
      // The portal refused the credentials: no attempt will do better until they are changed.
      return new Dispatch(
          null, Outcome.rejected("the portal refused the business's credentials", null));
    }
    String payload =
        Base64.getEncoder().encodeToString(aes(session.sek(), true, base64(d.irpJson())));
    JsonObject body = Json.createObjectBuilder().add("Data", payload).build();
    String answer;
    int status;
    try (HttpClientResponse res =
        signed(
                web.post(baseUrl + invoicePath),
                d.sellerVatId(),
                d.providerAccount(),
                session.token())
            .submit(body.toString())) {
      status = res.status().code();
      answer = res.as(String.class);
    } catch (RuntimeException e) {
      throw new TransportException("the portal could not be reached: " + e.getMessage(), e);
    }
    if (status == 401 || status == 403) {
      sessions.remove(sessionKey);
      throw new TransportException(
          "the portal no longer takes the session; signing in again", null);
    }
    if (status < 200 || status >= 300) {
      throw new TransportException("the portal answered HTTP " + status, null);
    }
    JsonObject o = object(answer);
    if (ok(o)) {
      String data =
          new String(
              aes(session.sek(), false, Base64.getDecoder().decode(o.getString("Data"))),
              StandardCharsets.UTF_8);
      JsonObject registered = objectOrBase64(data);
      String irn = registered.getString("Irn", null);
      if (blank(irn))
        throw new TransportException("the portal registered the invoice without an IRN", null);
      return new Dispatch(
          irn,
          Outcome.accepted(
              "registered: IRN "
                  + irn
                  + ", acknowledgement "
                  + registered.getString("AckNo", "")
                  + " at "
                  + registered.getString("AckDt", ""),
              registered.toString()));
    }
    JsonArray errors = errors(o);
    String alert =
        o.containsKey("InfoDtls") && !o.isNull("InfoDtls") ? o.get("InfoDtls").toString() : "";
    for (JsonValue v : errors) {
      JsonObject e = v.asJsonObject();
      if (DUPLICATE_IRN.equals(e.getString("ErrorCode", ""))) {
        String irn = irnIn(alert);
        if (irn != null) {
          return new Dispatch(irn, Outcome.accepted("already registered: IRN " + irn, answer));
        }
      }
    }
    StringBuilder why = new StringBuilder();
    for (JsonValue v : errors) {
      JsonObject e = v.asJsonObject();
      if (why.length() > 0) why.append("; ");
      why.append(e.getString("ErrorCode", "")).append(' ').append(e.getString("ErrorMessage", ""));
    }
    return new Dispatch(
        null,
        Outcome.rejected("the portal refused: " + (why.length() == 0 ? answer : why), answer));
  }

  /** The portal registers at once: a document with an IRN is registered. */
  @Override
  public Outcome status(Outbound d, String providerRef) {
    return Outcome.accepted("registered: IRN " + providerRef, null);
  }

  // ── signing in ───────────────────────────────────────────────────────────────

  /**
   * Signs in to the portal and stops there.
   *
   * <p>India's portal refuses a wrong credential rather than failing, so a check answers the
   * question a business actually has the day it registers: will my next invoice reach the IRP.
   */
  @Override
  public Readiness check(Outbound credentials) {
    if (credentials.sellerVatId() == null || credentials.sellerVatId().isBlank()) {
      return Readiness.refused("the portal knows a business by its GSTIN, and none is recorded");
    }
    if (credentials.providerAccount() == null || credentials.providerSecret() == null) {
      return Readiness.refused(
          "the portal signs a business in as its own user, and the user or the password is missing");
    }
    try {
      Session opened =
          session(
              credentials.sellerVatId(),
              credentials.providerAccount(),
              credentials.providerSecret());
      // The portal refuses a sign-in with a 200 and a Status of its own, so a null session is a
      // refusal and not an absence: reporting it as ready would be the check lying.
      return opened == null
          ? Readiness.refused(
              "the portal answered and would not sign the business in: check the user, the password"
                  + " and that the GSTIN is enrolled for e-invoicing")
          : Readiness.ready("the portal signed the business in");
    } catch (SignInRefused e) {
      return Readiness.refused(e.getMessage());
    } catch (TransportException e) {
      return Readiness.unreachable(e.getMessage());
    } catch (RuntimeException e) {
      return Readiness.refused("the portal would not sign the business in: " + e.getMessage());
    }
  }

  private Session session(String gstin, String user, String password) {
    String k = key(gstin, user, password);
    Session s = sessions.get(k);
    if (s != null && Instant.now().isBefore(s.until())) return s;
    byte[] appKey = new byte[32];
    random.nextBytes(appKey);
    JsonObject data =
        Json.createObjectBuilder()
            .add("UserName", user)
            .add(
                "Password",
                Base64.getEncoder().encodeToString(rsa(password.getBytes(StandardCharsets.UTF_8))))
            .add("AppKey", Base64.getEncoder().encodeToString(rsa(appKey)))
            .add("ForceRefreshAccessToken", false)
            .build();
    String answer;
    int status;
    try (HttpClientResponse res =
        web.post(baseUrl + authPath)
            .header(HeaderNames.create("client_id"), clientId)
            .header(HeaderNames.create("client_secret"), clientSecret)
            .header(HeaderNames.create("Gstin"), gstin)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .header(HeaderNames.ACCEPT, "application/json")
            .submit(Json.createObjectBuilder().add("data", data).build().toString())) {
      status = res.status().code();
      answer = res.as(String.class);
    } catch (RuntimeException e) {
      throw new TransportException("the portal could not be reached: " + e.getMessage(), e);
    }
    if (status == 401 || status == 403)
      throw new SignInRefused(
          "the portal answered HTTP "
              + status
              + " to the sign-in: the credentials it holds for this"
              + " business are not the ones we sent");
    if (status < 200 || status >= 300)
      throw new TransportException("the portal answered HTTP " + status + " to the sign-in", null);
    JsonObject o = object(answer);
    if (!ok(o)) return null;
    JsonObject d = o.getJsonObject("Data");
    byte[] sek = aes(appKey, false, Base64.getDecoder().decode(d.getString("Sek")));
    Instant until;
    try {
      until =
          LocalDateTime.parse(d.getString("TokenExpiry"), EXPIRY)
              .atZone(IST)
              .toInstant()
              .minusSeconds(300);
    } catch (RuntimeException e) {
      until = Instant.now().plusSeconds(3000);
    }
    Session fresh = new Session(d.getString("AuthToken"), sek, until);
    sessions.put(k, fresh);
    return fresh;
  }

  /**
   * The portal turned the sign-in away at the door.
   *
   * <p>A {@link TransportException} still, so the sending path treats it exactly as it always has —
   * a sign-in that failed is worth trying again, credentials do get fixed. Only the readiness check
   * looks for it, because there it is the difference between "wait" and "someone must act".
   */
  static final class SignInRefused extends TransportException {
    private static final long serialVersionUID = 1L;

    SignInRefused(String message) {
      super(message, null);
    }
  }

  private HttpClientRequest signed(HttpClientRequest req, String gstin, String user, String token) {
    return req.header(HeaderNames.create("client_id"), clientId)
        .header(HeaderNames.create("client_secret"), clientSecret)
        .header(HeaderNames.create("Gstin"), gstin)
        .header(HeaderNames.create("user_name"), user)
        .header(HeaderNames.create("AuthToken"), token)
        .header(HeaderNames.CONTENT_TYPE, "application/json")
        .header(HeaderNames.ACCEPT, "application/json");
  }

  /** The session's owner: the taxpayer, the user, and the password that opened it — hashed. */
  private static String key(String gstin, String user, String password) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
      return gstin + "/" + user + "/" + Base64.getEncoder().encodeToString(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── the portal's cryptography ────────────────────────────────────────────────

  private byte[] rsa(byte[] plain) {
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      c.init(Cipher.ENCRYPT_MODE, publicKey);
      return c.doFinal(plain);
    } catch (GeneralSecurityException e) {
      throw new TransportException("could not seal the sign-in for the portal", e);
    }
  }

  /** AES-256/ECB/PKCS5, as the portal specifies for the SEK and the payloads. */
  static byte[] aes(byte[] key, boolean encrypt, byte[] data) {
    try {
      Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
      c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
      return c.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new TransportException(
          "the portal's payload could not be " + (encrypt ? "sealed" : "opened"), e);
    }
  }

  private static byte[] base64(String s) {
    return Base64.getEncoder().encode(s.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean ok(JsonObject o) {
    JsonValue v = o.get("Status");
    return v != null && ("1".equals(v.toString().replace("\"", "")));
  }

  /** ErrorDetails as the portal sends it: a JSON array, or one as text or base64 text. */
  private static JsonArray errors(JsonObject o) {
    JsonValue v = o.get("ErrorDetails");
    if (v == null || v.getValueType() == JsonValue.ValueType.NULL)
      return Json.createArrayBuilder().build();
    if (v.getValueType() == JsonValue.ValueType.ARRAY) return v.asJsonArray();
    String text =
        v.getValueType() == JsonValue.ValueType.STRING
            ? ((jakarta.json.JsonString) v).getString()
            : v.toString();
    try {
      return array(text);
    } catch (RuntimeException e) {
      try {
        return array(new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8));
      } catch (RuntimeException e2) {
        return Json.createArrayBuilder()
            .add(Json.createObjectBuilder().add("ErrorCode", "").add("ErrorMessage", text))
            .build();
      }
    }
  }

  private static String irnIn(String text) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\\b[0-9a-f]{64}\\b").matcher(text == null ? "" : text);
    return m.find() ? m.group() : null;
  }

  private static JsonObject objectOrBase64(String text) {
    try {
      return object(text);
    } catch (RuntimeException e) {
      return object(new String(Base64.getDecoder().decode(text.strip()), StandardCharsets.UTF_8));
    }
  }

  private static JsonObject object(String json) {
    try (JsonReader r =
        Json.createReader(new StringReader(json == null || json.isBlank() ? "{}" : json))) {
      return r.readObject();
    }
  }

  private static JsonArray array(String json) {
    try (JsonReader r = Json.createReader(new StringReader(json))) {
      return r.readArray();
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
