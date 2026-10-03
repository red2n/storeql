package com.storeql.order.support;

import com.storeql.test.JsonStub;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.MGF1ParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Poland's KSeF as a test sees it: the ministry's own RSA key pair published as its two keys, a
 * challenge, a token sign-in that opens what the client sealed (the KSeF token with the challenge's
 * timestamp), an access token, an interactive session whose AES key it opens, an invoice it
 * decrypts, checks the hashes of and reads as FA(3), and a status it answers by {@code mode}:
 * accept (taken, processing, then a KSeF number), reject (a semantic error), duplicate (already in
 * with its number), or down.
 */
public final class KsefStub {

  public static final String PREFIX = "/ksef";

  private final KeyPair keys;
  private final Map<String, String> tokens = new ConcurrentHashMap<>();
  private final Map<String, Boolean> authOk = new ConcurrentHashMap<>();
  private final Map<String, byte[][]> sessions = new ConcurrentHashMap<>();
  private final AtomicInteger sessionsOpened = new AtomicInteger();
  private final AtomicInteger statusAsked = new AtomicInteger();
  private final AtomicInteger signIns = new AtomicInteger();
  private volatile String mode = "accept";
  private volatile String lastInvoice;
  private volatile long challengeMs;

  private KsefStub(KeyPair keys) {
    this.keys = keys;
  }

  /** Routes the system on the stub under {@link #PREFIX}, knowing one taxpayer's KSeF token. */
  public static KsefStub on(JsonStub stub, String nip, String ksefToken) {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      KsefStub k = new KsefStub(gen.generateKeyPair());
      k.tokens.put(nip, ksefToken);
      String pub = Base64.getEncoder().encodeToString(k.keys.getPublic().getEncoded());
      stub.on(
          "GET",
          PREFIX + "/security/public-key-certificates",
          200,
          "[{\"certificate\":\""
              + pub
              + "\",\"certificateId\":\"c1\",\"publicKeyId\":\"k1\",\"usage\":[\"KsefTokenEncryption\"]},"
              + "{\"certificate\":\""
              + pub
              + "\",\"certificateId\":\"c2\",\"publicKeyId\":\"k2\",\"usage\":[\"SymmetricKeyEncryption\"]}]");
      stub.on("POST", PREFIX + "/auth/challenge", k::challenge);
      stub.on("POST", PREFIX + "/auth/ksef-token", k::signIn);
      stub.on("GET", PREFIX + "/auth/AUTH-1", k::authStatus);
      stub.on("POST", PREFIX + "/auth/token/redeem", k::redeem);
      stub.on("POST", PREFIX + "/sessions/online", k::openSession);
      stub.on("POST", PREFIX + "/sessions/online/SES-1/invoices", k::sendInvoice);
      stub.on("POST", PREFIX + "/sessions/online/SES-1/close", 204, "");
      stub.on("GET", PREFIX + "/sessions/SES-1/invoices/INV-1", k::invoiceStatus);
      return k;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public void mode(String mode) {
    this.mode = mode;
    statusAsked.set(0);
  }

  public int signIns() {
    return signIns.get();
  }

  public int sessionsOpened() {
    return sessionsOpened.get();
  }

  /** The last FA(3) the system decrypted, as XML text. */
  public String lastInvoice() {
    return lastInvoice;
  }

  private JsonStub.Answer challenge(JsonStub.Call call) {
    challengeMs = Instant.now().toEpochMilli();
    return new JsonStub.Answer(
        200,
        "{\"challenge\":\"CH-1\",\"timestamp\":\""
            + Instant.ofEpochMilli(challengeMs)
            + "\",\"timestampMs\":"
            + challengeMs
            + "}");
  }

  private JsonStub.Answer signIn(JsonStub.Call call) {
    signIns.incrementAndGet();
    JsonObject o = object(call.body());
    String nip = o.getJsonObject("contextIdentifier").getString("value", "");
    String opened = new String(oaepOpen(o.getString("encryptedToken", "")), StandardCharsets.UTF_8);
    boolean ok =
        "CH-1".equals(o.getString("challenge", ""))
            && opened.equals(tokens.get(nip) + "|" + challengeMs);
    authOk.put("AUTH-1", ok);
    return new JsonStub.Answer(
        202,
        "{\"referenceNumber\":\"AUTH-1\",\"authenticationToken\":{\"token\":\"AT-1\",\"validUntil\":\""
            + Instant.now().plusSeconds(600)
            + "\"}}");
  }

  private JsonStub.Answer authStatus(JsonStub.Call call) {
    if (!"Bearer AT-1".equals(call.header("Authorization"))) return new JsonStub.Answer(401, "{}");
    boolean ok = authOk.getOrDefault("AUTH-1", false);
    return new JsonStub.Answer(
        200,
        ok
            ? "{\"status\":{\"code\":200,\"description\":\"Uwierzytelnianie zakończone sukcesem\"},\"isTokenRedeemed\":false}"
            : "{\"status\":{\"code\":450,\"description\":\"Uwierzytelnianie zakończone niepowodzeniem z powodu błędnego tokenu\",\"details\":[\"Nieprawidłowy token\"]}}");
  }

  private JsonStub.Answer redeem(JsonStub.Call call) {
    if (!"Bearer AT-1".equals(call.header("Authorization"))
        || !authOk.getOrDefault("AUTH-1", false)) {
      return new JsonStub.Answer(401, "{}");
    }
    String until = Instant.now().plusSeconds(900).toString();
    return new JsonStub.Answer(
        200,
        "{\"accessToken\":{\"token\":\"ACC-1\",\"validUntil\":\""
            + until
            + "\"},\"refreshToken\":{\"token\":\"REF-1\",\"validUntil\":\""
            + until
            + "\"}}");
  }

  private JsonStub.Answer openSession(JsonStub.Call call) {
    if (!"Bearer ACC-1".equals(call.header("Authorization"))) return new JsonStub.Answer(401, "{}");
    if ("down".equals(mode)) return new JsonStub.Answer(503, "{\"message\":\"maintenance\"}");
    JsonObject o = object(call.body());
    JsonObject form = o.getJsonObject("formCode");
    if (!"FA (3)".equals(form.getString("systemCode", ""))
        || !"1-0E".equals(form.getString("schemaVersion", ""))) {
      return new JsonStub.Answer(400, "{\"description\":\"unsupported form code\"}");
    }
    JsonObject enc = o.getJsonObject("encryption");
    byte[] key = oaepOpen(enc.getString("encryptedSymmetricKey"));
    byte[] iv = Base64.getDecoder().decode(enc.getString("initializationVector"));
    sessions.put("SES-1", new byte[][] {key, iv});
    sessionsOpened.incrementAndGet();
    return new JsonStub.Answer(
        201,
        "{\"referenceNumber\":\"SES-1\",\"validUntil\":\""
            + Instant.now().plusSeconds(3600)
            + "\"}");
  }

  private JsonStub.Answer sendInvoice(JsonStub.Call call) {
    if (!"Bearer ACC-1".equals(call.header("Authorization"))) return new JsonStub.Answer(401, "{}");
    byte[][] s = sessions.get("SES-1");
    if (s == null) return new JsonStub.Answer(400, "{\"description\":\"no session\"}");
    JsonObject o = object(call.body());
    byte[] encrypted = Base64.getDecoder().decode(o.getString("encryptedInvoiceContent"));
    if (!b64(sha256(encrypted)).equals(o.getString("encryptedInvoiceHash"))
        || encrypted.length != o.getInt("encryptedInvoiceSize")) {
      return new JsonStub.Answer(400, "{\"description\":\"encrypted hash or size wrong\"}");
    }
    byte[] xml;
    try {
      Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(s[0], "AES"), new IvParameterSpec(s[1]));
      xml = c.doFinal(encrypted);
    } catch (GeneralSecurityException e) {
      return new JsonStub.Answer(400, "{\"description\":\"cannot decrypt\"}");
    }
    if (!b64(sha256(xml)).equals(o.getString("invoiceHash"))
        || xml.length != o.getInt("invoiceSize")) {
      return new JsonStub.Answer(400, "{\"description\":\"invoice hash or size wrong\"}");
    }
    String text = new String(xml, StandardCharsets.UTF_8);
    lastInvoice = text;
    if (!text.contains("<Faktura xmlns=\"http://crd.gov.pl/wzor/2025/06/25/13775/\">")
        || !text.contains("kodSystemowy=\"FA (3)\"")) {
      return new JsonStub.Answer(400, "{\"description\":\"not FA(3)\"}");
    }
    return new JsonStub.Answer(202, "{\"referenceNumber\":\"INV-1\"}");
  }

  private JsonStub.Answer invoiceStatus(JsonStub.Call call) {
    if (!"Bearer ACC-1".equals(call.header("Authorization"))) return new JsonStub.Answer(401, "{}");
    int asked = statusAsked.incrementAndGet();
    switch (mode) {
      case "reject":
        return new JsonStub.Answer(
            200,
            "{\"referenceNumber\":\"INV-1\",\"status\":{\"code\":450,\"description\":\"Błąd weryfikacji semantyki dokumentu faktury\",\"details\":[\"P_15 niezgodne z sumą wierszy\"]}}");
      case "duplicate":
        return new JsonStub.Answer(
            200,
            "{\"referenceNumber\":\"INV-1\",\"status\":{\"code\":440,\"description\":\"Duplikat faktury\",\"extensions\":{\"originalKsefNumber\":\"5260250274-20260916-0A1B2C3D4E5F-01\"}}}");
      case "flaky":
        return new JsonStub.Answer(
            200,
            "{\"referenceNumber\":\"INV-1\",\"status\":{\"code\":550,\"description\":\"Operacja została anulowana przez system\"}}");
      default:
        if (asked == 1)
          return new JsonStub.Answer(
              200,
              "{\"referenceNumber\":\"INV-1\",\"status\":{\"code\":150,\"description\":\"Trwa przetwarzanie\"}}");
        return new JsonStub.Answer(
            200,
            "{\"referenceNumber\":\"INV-1\",\"invoiceNumber\":\"x\",\"ksefNumber\":\"5260250274-20260916-010203ABCDEF-01\",\"acquisitionDate\":\"2026-09-16T10:00:00Z\",\"status\":{\"code\":200,\"description\":\"Sukces\"}}");
    }
  }

  private byte[] oaepOpen(String base64) {
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      c.init(
          Cipher.DECRYPT_MODE,
          keys.getPrivate(),
          new OAEPParameterSpec(
              "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return c.doFinal(Base64.getDecoder().decode(base64));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return new byte[0];
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  private static JsonObject object(String json) {
    try (JsonReader r =
        Json.createReader(new StringReader(json == null || json.isBlank() ? "{}" : json))) {
      return r.readObject();
    }
  }
}
