package com.storeql.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A token that <em>is</em> the permission, for the places where asking somebody to sign in would
 * defeat the point.
 *
 * <p>Two of those exist so far, and they are the same problem. The opt-out link in a marketing
 * message (PECR reg.23) reaches a person who may be on a device that was never signed in and may
 * have no password at all, and who must not be made to prove who they are in order to be left
 * alone. The pay link in a dunning notice (21.12) reaches a business the platform has just
 * <em>suspended</em>, so it cannot sign in by definition — and telling it to pay while denying it
 * the means is not a dunning process, it is a dead end.
 *
 * <p>In both cases the token is the whole capability, so it is minted per use and only its hash is
 * kept. What it can do is deliberately narrow: withdraw a permission, or pay a named invoice.
 *
 * <p>Shared rather than copied. The first of these lived in customer-svc; a second copy of a
 * security primitive is one edit away from only one of them being fixed.
 */
public final class CapabilityTokens {

  private CapabilityTokens() {}

  /**
   * DRBG, not the default NativePRNG: NativePRNG reads /dev/urandom under a JVM-wide monitor, which
   * pins a virtual thread's carrier; DRBG keeps its own state and is seeded from the OS source.
   */
  private static final SecureRandom RANDOM = newRandom();

  private static SecureRandom newRandom() {
    try {
      return SecureRandom.getInstance("DRBG");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK since 9 provides DRBG", e);
    }
  }

  /** 256 bits, which is what makes guessing it not worth attempting. */
  private static final int BYTES = 32;

  /**
   * A fresh token, URL-safe so it can go straight into a link.
   *
   * @return 32 random bytes, base64url without padding
   */
  public static String mint() {
    byte[] bytes = new byte[BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * What is stored, and what an incoming token is compared against.
   *
   * <p>SHA-256, not a password hash. The token is 256 bits of randomness rather than something a
   * person chose, so there is nothing to brute-force and no reason to make verification slow — a
   * deliberately slow hash here would only make the honest path slower.
   *
   * @param token the raw token, as a link carries it
   * @return its hash, base64
   */
  public static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return Base64.getEncoder()
          .encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }
}
