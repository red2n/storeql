package com.storeql.test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over the raw body, as webhook and provider signature schemes use it: compute one to
 * send, or check one that arrived. The comparison is constant-time. A scheme that signs more than
 * the body (a timestamp, a dot) builds its message and passes it here; the bytes signed are the
 * bytes sent, never a re-serialisation.
 */
public final class Hmac {

  private Hmac() {}

  public static byte[] sha256(byte[] key, byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA256"));
      return mac.doFinal(message);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256Hex(String key, String message) {
    return HexFormat.of()
        .formatHex(
            sha256(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8)));
  }

  public static String sha256Base64(String key, String message) {
    return Base64.getEncoder()
        .encodeToString(
            sha256(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8)));
  }

  /** True when {@code claimedHex} (case-insensitive) is the HMAC-SHA256 of the message. */
  public static boolean verifyHex(String key, String message, String claimedHex) {
    if (claimedHex == null) return false;
    return MessageDigest.isEqual(
        sha256Hex(key, message).getBytes(StandardCharsets.UTF_8),
        claimedHex.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
  }

  /** True when {@code claimedBase64} is the base64 HMAC-SHA256 of the message. */
  public static boolean verifyBase64(String key, String message, String claimedBase64) {
    if (claimedBase64 == null) return false;
    return MessageDigest.isEqual(
        sha256Base64(key, message).getBytes(StandardCharsets.UTF_8),
        claimedBase64.trim().getBytes(StandardCharsets.UTF_8));
  }
}
