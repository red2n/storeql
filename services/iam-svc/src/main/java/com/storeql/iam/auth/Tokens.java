package com.storeql.iam.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque refresh-token generation + hashing (we store only the hash). */
public final class Tokens {

  private static final SecureRandom RNG = new SecureRandom();

  private Tokens() {}

  /** A new high-entropy opaque token (returned to the client; never stored raw). */
  public static String newOpaqueToken() {
    return newOpaqueToken(32);
  }

  /** A new opaque token of {@code bytes} random bytes, base64url without padding. */
  public static String newOpaqueToken(int bytes) {
    byte[] random = new byte[bytes];
    RNG.nextBytes(random);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }

  /** SHA-256 hash of a token (what we store / look up by). */
  public static String hash(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
