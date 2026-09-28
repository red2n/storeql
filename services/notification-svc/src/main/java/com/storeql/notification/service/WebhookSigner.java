package com.storeql.notification.service;

import com.storeql.notification.domain.Webhooks;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How a delivery is signed (22.6), to the Standard Webhooks specification: three headers — {@code
 * webhook-id} (the delivery, the same on every retry), {@code webhook-timestamp} (unix seconds) and
 * {@code webhook-signature} ({@code v1,<base64>}, an HMAC-SHA256 under the secret's bytes over
 * {@code <id>.<timestamp>.<body>}). The secret is {@code whsec_} and base64, as the specification
 * writes it. A receiver recomputes the digest over the body exactly as received, compares in
 * constant time, and holds the timestamp to a window of its choosing, so a delivery captured cannot
 * be replayed later; any library written to the specification verifies it.
 */
public final class WebhookSigner {

  private static final String ALGORITHM = "HmacSHA256";
  private static final String VERSION = "v1";

  private WebhookSigner() {}

  public static String sign(String secret, String msgId, long epochSeconds, String body) {
    return VERSION + "," + digest(secret, msgId + "." + epochSeconds + "." + body);
  }

  /**
   * Whether a signature header signs a delivery under a secret; false for anything malformed. The
   * header may carry several space-separated signatures (a rotation in flight); one match is
   * enough.
   */
  public static boolean verify(
      String secret, String msgId, String timestamp, String header, String body) {
    if (secret == null || msgId == null || timestamp == null || header == null || body == null) {
      return false;
    }
    if (!timestamp.matches("\\d{1,12}")) return false;
    byte[] expected;
    try {
      expected =
          digest(secret, msgId + "." + timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
    } catch (IllegalArgumentException e) {
      return false;
    }
    for (String candidate : header.trim().split("\\s+")) {
      String[] parts = candidate.split(",", 2);
      if (parts.length != 2 || !VERSION.equals(parts[0])) continue;
      if (MessageDigest.isEqual(expected, parts[1].getBytes(StandardCharsets.US_ASCII)))
        return true;
    }
    return false;
  }

  /** The secret's key bytes: what follows {@code whsec_}, base64 as the specification writes it. */
  static byte[] keyBytes(String secret) {
    String encoded =
        secret.startsWith(Webhooks.SECRET_PREFIX)
            ? secret.substring(Webhooks.SECRET_PREFIX.length())
            : secret;
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      return Base64.getUrlDecoder().decode(encoded);
    }
  }

  private static String digest(String secret, String text) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(keyBytes(secret), ALGORITHM));
      return Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
