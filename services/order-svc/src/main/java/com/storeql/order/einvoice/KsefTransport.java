package com.storeql.order.einvoice;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Fa3;
import com.storeql.einvoice.Invoice;
import com.storeql.einvoice.Violation;
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
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Poland's Krajowy System e-Faktur (KSeF API 2.0). It alone takes no EN 16931 document, only its
 * own FA(3), so the document is read back from the UBL as issued and written again as FA(3). The
 * business authenticates as the taxpayer with its KSeF token — its secret in the settings, its NIP
 * from its VAT number — sealed together with the challenge's timestamp under the ministry's
 * RSA-OAEP key; the access token that comes back opens an interactive session whose AES key is
 * sealed under the ministry's other key; the FA(3) goes in AES-256-CBC-encrypted with its hashes
 * and sizes, the session is closed, and the system's answer is asked after by reference until it
 * gives the invoice its KSeF number or refuses it. Configured with the system's base URL ({@code
 * storeql.einvoice.ksef.base-url}); deployed always, choosable only configured.
 */
@ApplicationScoped
public class KsefTransport implements EInvoiceTransport {

  public static final String NAME = "KSEF";

  /** The system's statuses: taken, processing, accepted, and everything that is a refusal. */
  static final int TAKEN = 100;

  static final int PROCESSING = 150;
  static final int ACCEPTED = 200;
  static final int DUPLICATE = 440;

  private static final String USAGE_TOKEN = "KsefTokenEncryption";
  private static final String USAGE_SYMMETRIC = "SymmetricKeyEncryption";

  @Inject
  @ConfigProperty(name = "storeql.einvoice.ksef.base-url")
  Optional<String> baseUrlConfig;

  @Inject
  @ConfigProperty(name = "storeql.einvoice.ksef.system-info", defaultValue = "StoreQL")
  String systemInfo;

  String baseUrl = "";
  private WebClient web;
  private final SecureRandom random = new SecureRandom();

  /** The ministry's keys, by use, kept for an hour. */
  private record Keys(
      PublicKey token, String tokenId, PublicKey symmetric, String symmetricId, Instant until) {}

  private volatile Keys keys;

  /** An access token per taxpayer and KSeF token, until shortly before it expires. */
  private record Access(String token, Instant until) {}

  private final Map<String, Access> access = new ConcurrentHashMap<>();

  @PostConstruct
  void init() {
    baseUrl = baseUrlConfig.orElse("").strip();
    web = client();
  }

  /** For tests: a transport pointed at a stub system, with no CDI. */
  static KsefTransport forTest(String baseUrl, String systemInfo) {
    KsefTransport t = new KsefTransport();
    t.baseUrl = baseUrl;
    t.systemInfo = systemInfo;
    t.web = client();
    return t;
  }

  private static WebClient client() {
    return WebClient.builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(20))
        .build();
  }

  @Override
  public Set<String> networks() {
    return Set.of(EInvoiceTransports.NETWORK_KSEF);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return !baseUrl.isBlank();
  }

  @Override
  public String configuration() {
    return "storeql.einvoice.ksef.base-url, and the business's KSeF token as its secret";
  }

  @Override
  public boolean needsSecret() {
    return true;
  }

  @Override
  public Dispatch send(Outbound d) {
    String nip = Fa3.nipOf(d.sellerVatId());
    if (nip == null || blank(d.providerSecret())) {
      return new Dispatch(
          null,
          Outcome.rejected(
              "KSeF needs the business's NIP as its VAT number (PL and ten digits) and its KSeF"
                  + " token as the transport secret",
              null));
    }
    // The document, as FA(3): what the system alone takes.
    Invoice inv = EInvoices.read(d.ubl().getBytes(StandardCharsets.UTF_8)).invoice();
    List<Violation> broken = Fa3.check(inv).stream().filter(Violation::isFatal).toList();
    if (!broken.isEmpty()) {
      StringBuilder why = new StringBuilder("the document cannot be written as FA(3): ");
      for (int i = 0; i < broken.size(); i++) {
        if (i > 0) why.append("; ");
        why.append(broken.get(i).rule()).append(' ').append(broken.get(i).message());
      }
      return new Dispatch(null, Outcome.rejected(why.toString(), null));
    }
    byte[] xml = Fa3.write(inv, Instant.now(), systemInfo).getBytes(StandardCharsets.UTF_8);

    Keys k = keys();
    String token = accessToken(nip, d.providerSecret(), k);
    if (token == null) {
      return new Dispatch(null, Outcome.rejected("KSeF refused the business's token", null));
    }
    // A session of its own for the document: opened with a fresh AES key sealed for the ministry.
    byte[] aesKey = new byte[32];
    byte[] iv = new byte[16];
    random.nextBytes(aesKey);
    random.nextBytes(iv);
    JsonObject open =
        Json.createObjectBuilder()
            .add(
                "formCode",
                Json.createObjectBuilder()
                    .add("systemCode", Fa3.SYSTEM_CODE)
                    .add("schemaVersion", Fa3.SCHEMA_VERSION)
                    .add("value", Fa3.FORM_VALUE))
            .add(
                "encryption",
                Json.createObjectBuilder()
                    .add("encryptedSymmetricKey", b64(oaep(k.symmetric(), aesKey)))
                    .add("initializationVector", b64(iv))
                    .add("publicKeyId", k.symmetricId() == null ? "" : k.symmetricId()))
            .build();
    Reply session = call("POST", "/sessions/online", open, token, nip, d.providerSecret());
    if (!session.ok()) return refused(session, "the session could not be opened");
    String sessionRef = session.object().getString("referenceNumber", "");

    byte[] encrypted = aesCbc(aesKey, iv, xml);
    JsonObject body =
        Json.createObjectBuilder()
            .add("invoiceHash", b64(sha256(xml)))
            .add("invoiceSize", xml.length)
            .add("encryptedInvoiceHash", b64(sha256(encrypted)))
            .add("encryptedInvoiceSize", encrypted.length)
            .add("encryptedInvoiceContent", b64(encrypted))
            .add("offlineMode", false)
            .build();
    Reply sent =
        call(
            "POST",
            "/sessions/online/" + sessionRef + "/invoices",
            body,
            token,
            nip,
            d.providerSecret());
    if (!sent.ok()) return refused(sent, "the document was not taken");
    String invoiceRef = sent.object().getString("referenceNumber", "");
    call(
        "POST",
        "/sessions/online/" + sessionRef + "/close",
        Json.createObjectBuilder().build(),
        token,
        nip,
        d.providerSecret());
    return new Dispatch(
        sessionRef + "/" + invoiceRef,
        Outcome.pending("taken by KSeF; awaiting its number", sent.body()));
  }

  @Override
  public Outcome status(Outbound d, String providerRef) {
    // A KSeF number already: nothing left to ask.
    if (providerRef != null && providerRef.matches("[0-9]{10}-[0-9]{8}-[0-9A-F]{12}-[0-9A-F]{2}")) {
      return Outcome.accepted("KSeF number " + providerRef, null);
    }
    String nip = Fa3.nipOf(d.sellerVatId());
    int slash = providerRef == null ? -1 : providerRef.indexOf('/');
    if (nip == null || slash < 0 || blank(d.providerSecret())) {
      return Outcome.rejected(
          "the reference " + providerRef + " is not a KSeF session and invoice", null);
    }
    String token = accessToken(nip, d.providerSecret(), keys());
    if (token == null) return Outcome.rejected("KSeF refused the business's token", null);
    Reply reply =
        call(
            "GET",
            "/sessions/"
                + providerRef.substring(0, slash)
                + "/invoices/"
                + providerRef.substring(slash + 1),
            null,
            token,
            nip,
            d.providerSecret());
    if (!reply.ok()) {
      return Outcome.rejected(
          "KSeF no longer knows " + providerRef + " (HTTP " + reply.status() + ")", reply.body());
    }
    JsonObject o = reply.object();
    JsonObject status =
        o.containsKey("status") ? o.getJsonObject("status") : Json.createObjectBuilder().build();
    int code = status.getInt("code", 0);
    String description = status.getString("description", "");
    if (code == TAKEN || code == PROCESSING || code == 0) {
      return Outcome.pending(
          "with KSeF: " + (description.isBlank() ? "processing" : description), reply.body());
    }
    if (code == ACCEPTED) {
      String number = o.getString("ksefNumber", "");
      return new Outcome(
          EInvoiceTransports.STATUS_ACCEPTED,
          "KSeF number "
              + number
              + (o.containsKey("acquisitionDate")
                  ? ", given " + o.getString("acquisitionDate", "")
                  : ""),
          reply.body(),
          number.isBlank() ? null : number);
    }
    if (code == DUPLICATE) {
      JsonObject ext =
          status.containsKey("extensions")
                  && status.get("extensions").getValueType() == JsonValue.ValueType.OBJECT
              ? status.getJsonObject("extensions")
              : null;
      String number = ext == null ? "" : ext.getString("originalKsefNumber", "");
      if (!number.isBlank()) {
        return new Outcome(
            EInvoiceTransports.STATUS_ACCEPTED,
            "already in KSeF as " + number,
            reply.body(),
            number);
      }
    }
    if (code >= 500) {
      throw new TransportException(
          "KSeF answered " + code + " " + description + "; asking again later", null);
    }
    StringBuilder why = new StringBuilder("KSeF refused (" + code + "): " + description);
    JsonArray details =
        status.containsKey("details")
                && status.get("details").getValueType() == JsonValue.ValueType.ARRAY
            ? status.getJsonArray("details")
            : null;
    if (details != null) {
      for (JsonValue v : details)
        why.append("; ")
            .append(
                v.getValueType() == JsonValue.ValueType.STRING
                    ? ((jakarta.json.JsonString) v).getString()
                    : v.toString());
    }
    return Outcome.rejected(why.toString(), reply.body());
  }

  // ── signing in ───────────────────────────────────────────────────────────────

  /** The access token for the taxpayer's KSeF token, or null when the system refused it. */
  /**
   * Signs the business in and stops there.
   *
   * <p>The whole of what can go wrong before a first invoice: the ministry unreachable, its keys
   * unreadable, the business's token refused. Nothing is sent, and no session is opened for a
   * document that does not exist.
   */
  @Override
  public Readiness check(Outbound credentials) {
    String nip = Fa3.nipOf(credentials.sellerVatId());
    if (nip == null) {
      return Readiness.refused(
          "KSeF knows a business by its NIP, and this one's VAT number is not a Polish number");
    }
    if (credentials.providerSecret() == null || credentials.providerSecret().isBlank()) {
      return Readiness.refused("KSeF signs a business in with its own token, and none is held");
    }
    try {
      String token = accessToken(nip, credentials.providerSecret(), keys());
      return token == null
          ? Readiness.refused("KSeF answered and refused the business's token")
          : Readiness.ready("KSeF signed the business in");
    } catch (TransportException e) {
      return Readiness.unreachable(e.getMessage());
    }
  }

  private String accessToken(String nip, String ksefToken, Keys k) {
    String key = nip + "/" + b64(sha256(ksefToken.getBytes(StandardCharsets.UTF_8)));
    Access a = access.get(key);
    if (a != null && Instant.now().isBefore(a.until())) return a.token();
    Reply challenge =
        call("POST", "/auth/challenge", Json.createObjectBuilder().build(), null, nip, ksefToken);
    if (!challenge.ok())
      throw new TransportException("KSeF gave no challenge: HTTP " + challenge.status(), null);
    JsonObject c = challenge.object();
    long ms =
        c.containsKey("timestampMs")
            ? c.getJsonNumber("timestampMs").longValue()
            : Instant.parse(c.getString("timestamp")).toEpochMilli();
    String sealed = b64(oaep(k.token(), (ksefToken + "|" + ms).getBytes(StandardCharsets.UTF_8)));
    JsonObject init =
        Json.createObjectBuilder()
            .add("challenge", c.getString("challenge"))
            .add(
                "contextIdentifier",
                Json.createObjectBuilder().add("type", "Nip").add("value", nip))
            .add("encryptedToken", sealed)
            .add("publicKeyId", k.tokenId() == null ? "" : k.tokenId())
            .build();
    Reply started = call("POST", "/auth/ksef-token", init, null, nip, ksefToken);
    if (!started.ok()) {
      if (started.status() == 400 || started.status() == 401 || started.status() == 403)
        return null;
      throw new TransportException(
          "KSeF did not start the sign-in: HTTP " + started.status(), null);
    }
    JsonObject s = started.object();
    String ref = s.getString("referenceNumber", "");
    String authToken = s.getJsonObject("authenticationToken").getString("token", "");
    int code = 0;
    String description = "";
    for (int i = 0; i < 20; i++) {
      Reply st = call("GET", "/auth/" + ref, null, authToken, nip, ksefToken);
      if (!st.ok())
        throw new TransportException(
            "KSeF did not answer for the sign-in: HTTP " + st.status(), null);
      JsonObject status = st.object().getJsonObject("status");
      code = status.getInt("code", 0);
      description = status.getString("description", "");
      if (code != TAKEN && code != 0) break;
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TransportException("interrupted while KSeF signed in", e);
      }
    }
    if (code != ACCEPTED) {
      if (code >= 500 || code == TAKEN)
        throw new TransportException("KSeF sign-in " + code + " " + description, null);
      return null;
    }
    Reply redeemed =
        call(
            "POST",
            "/auth/token/redeem",
            Json.createObjectBuilder().build(),
            authToken,
            nip,
            ksefToken);
    if (!redeemed.ok())
      throw new TransportException("KSeF gave no access token: HTTP " + redeemed.status(), null);
    JsonObject t = redeemed.object().getJsonObject("accessToken");
    Instant until;
    try {
      until = Instant.parse(t.getString("validUntil")).minusSeconds(120);
    } catch (RuntimeException e) {
      until = Instant.now().plusSeconds(600);
    }
    Access fresh = new Access(t.getString("token"), until);
    access.put(key, fresh);
    return fresh.token();
  }

  /** The ministry's current keys, for the token and for the session key. */
  private Keys keys() {
    Keys k = keys;
    if (k != null && Instant.now().isBefore(k.until())) return k;
    Reply reply = call("GET", "/security/public-key-certificates", null, null, null, null);
    if (!reply.ok())
      throw new TransportException("KSeF gave no public keys: HTTP " + reply.status(), null);
    PublicKey token = null;
    PublicKey symmetric = null;
    String tokenId = null;
    String symmetricId = null;
    try (JsonReader r = Json.createReader(new StringReader(reply.body()))) {
      for (JsonValue v : r.readArray()) {
        JsonObject cert = v.asJsonObject();
        PublicKey pk = publicKey(cert.getString("certificate", ""));
        JsonArray usage =
            cert.containsKey("usage")
                ? cert.getJsonArray("usage")
                : Json.createArrayBuilder().build();
        String id = cert.getString("publicKeyId", null);
        for (JsonValue u : usage) {
          String use =
              u.getValueType() == JsonValue.ValueType.STRING
                  ? ((jakarta.json.JsonString) u).getString()
                  : u.toString();
          if (USAGE_TOKEN.equals(use) && token == null) {
            token = pk;
            tokenId = id;
          }
          if (USAGE_SYMMETRIC.equals(use) && symmetric == null) {
            symmetric = pk;
            symmetricId = id;
          }
        }
      }
    }
    if (token == null || symmetric == null)
      throw new TransportException("KSeF's keys lack one for the token or the session", null);
    Keys fresh = new Keys(token, tokenId, symmetric, symmetricId, Instant.now().plusSeconds(3600));
    keys = fresh;
    return fresh;
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
        TransportException t =
            new TransportException("KSeF's key could not be read", notACertificate);
        t.addSuppressed(notAKeyEither);
        throw t;
      }
    }
  }

  // ── the wire ─────────────────────────────────────────────────────────────────

  /** What the system answered. */
  record Reply(int status, String body) {
    boolean ok() {
      return status >= 200 && status < 300;
    }

    JsonObject object() {
      try (JsonReader r =
          Json.createReader(new StringReader(body == null || body.isBlank() ? "{}" : body))) {
        return r.readObject();
      } catch (RuntimeException e) {
        return Json.createObjectBuilder().build();
      }
    }
  }

  private Reply call(
      String method, String path, JsonObject body, String bearer, String nip, String secret) {
    HttpClientRequest req =
        "GET".equals(method) ? web.get(baseUrl + path) : web.post(baseUrl + path);
    req = req.header(HeaderNames.ACCEPT, "application/json");
    if (bearer != null) req = req.header(HeaderNames.AUTHORIZATION, "Bearer " + bearer);
    try (HttpClientResponse res =
        "GET".equals(method)
            ? req.request()
            : req.header(HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body == null ? "{}" : body.toString())) {
      int status = res.status().code();
      String answer = res.entity().hasEntity() ? res.as(String.class) : "";
      if (status == 401 && bearer != null && nip != null && secret != null) {
        access.remove(nip + "/" + b64(sha256(secret.getBytes(StandardCharsets.UTF_8))));
        throw new TransportException(
            "KSeF no longer takes the access token; signing in again", null);
      }
      if (status >= 500)
        throw new TransportException("KSeF answered HTTP " + status + " to " + path, null);
      return new Reply(status, answer);
    } catch (TransportException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new TransportException("KSeF could not be reached: " + e.getMessage(), e);
    }
  }

  private static Dispatch refused(Reply reply, String what) {
    JsonObject o = reply.object();
    String why = o.getString("description", o.getString("message", ""));
    if (why.isBlank() && o.containsKey("exception")) why = o.get("exception").toString();
    return new Dispatch(
        null,
        Outcome.rejected(
            "KSeF: " + what + " (HTTP " + reply.status() + ")" + (why.isBlank() ? "" : ": " + why),
            reply.body()));
  }

  // ── the ministry's cryptography ──────────────────────────────────────────────

  /** RSAES-OAEP with SHA-256 and MGF1-SHA256, as the system requires for both keys. */
  static byte[] oaep(PublicKey key, byte[] plain) {
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      c.init(
          Cipher.ENCRYPT_MODE,
          key,
          new OAEPParameterSpec(
              "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return c.doFinal(plain);
    } catch (GeneralSecurityException e) {
      throw new TransportException("could not seal for KSeF", e);
    }
  }

  /** AES-256-CBC with PKCS#7 padding, as the system requires for the document. */
  static byte[] aesCbc(byte[] key, byte[] iv, byte[] plain) {
    try {
      Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
      return c.doFinal(plain);
    } catch (GeneralSecurityException e) {
      throw new TransportException("could not encrypt the document for KSeF", e);
    }
  }

  static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
