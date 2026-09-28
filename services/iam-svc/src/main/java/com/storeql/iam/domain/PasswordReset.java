package com.storeql.iam.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The pure rules of forgotten-password (intent/password-reset.md): no account enumeration, one
 * entry per eligible login, a staff login named by its business, a single-sign-on login named with
 * no link. What can be decided without a database or an HTTP call — {@link
 * com.storeql.iam.service.PasswordResetService} supplies the facts (a login's own status, whether
 * it holds PLATFORM_ADMIN, whether its business is active, whether its business now requires single
 * sign-on of it) and this decides what the login gets.
 */
public final class PasswordReset {

  private PasswordReset() {}

  /**
   * {@code storeql.iam.password/forgot}'s {@code language}: strict, so a bad value is ignored,
   * never refused.
   */
  private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

  /** What one login using the address gets from a forgot-password request. */
  public enum Kind {
    /**
     * Not eligible: no entry at all. The login's own status is not ACTIVE, it holds PLATFORM_ADMIN,
     * or (for staff) its business is not active.
     */
    NONE,
    /** A shopper login (no business): an entry with a link. */
    SHOPPER,
    /** A staff login: an entry with a link, named by its business. */
    STAFF,
    /**
     * A staff login whose business now signs its staff in through its own identity provider: an
     * entry named by its business, with no link — the provider owns its resets.
     */
    STAFF_SSO
  }

  /**
   * What one login gets, from facts read for it.
   *
   * @param active the login's own status is ACTIVE
   * @param platformAdmin the login holds PLATFORM_ADMIN — never reset by a link (intent: "not the
   *     platform administrator")
   * @param businessActive its business is active (irrelevant, and always true, for a shopper login,
   *     which belongs to none)
   * @param staff whether the login belongs to a business, rather than being a shopper's
   * @param ssoRequired the login's business now signs its staff in through its own identity
   *     provider (irrelevant for a shopper)
   * @return the kind of entry the login gets, {@link Kind#NONE} for no entry at all
   */
  public static Kind kindOf(
      boolean active,
      boolean platformAdmin,
      boolean businessActive,
      boolean staff,
      boolean ssoRequired) {
    if (!active || platformAdmin || !businessActive) {
      return Kind.NONE;
    }
    if (!staff) {
      return Kind.SHOPPER;
    }
    return ssoRequired ? Kind.STAFF_SSO : Kind.STAFF;
  }

  /**
   * The address a forgot-password request throttles and mints tokens against — never the address
   * itself, so a database holding this hash holds nothing to leak.
   *
   * @param email as typed; lower-cased and trimmed before hashing, so {@code "A@B.com"} and {@code
   *     " a@b.com "} throttle together and {@code " a@b.com "} finds the same logins {@code
   *     "a@b.com"} does
   * @return the SHA-256 of the normalised address, lower-case hex
   */
  public static String addressHash(String email) {
    String normalised = email.strip().toLowerCase(Locale.ROOT);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }

  /**
   * The language a forgot-password request named, read strictly. A request is never refused over
   * this field: anything that does not match reads as English, exactly as if none had been sent.
   *
   * @param requested as sent; may be {@code null} or blank
   * @return the lower-cased {@code [a-z]{2,3}} language code, or {@code null} for anything else
   */
  public static String language(String requested) {
    if (requested == null) {
      return null;
    }
    String lower = requested.strip().toLowerCase(Locale.ROOT);
    return LANGUAGE.matcher(lower).matches() ? lower : null;
  }

  /**
   * The link a reset email carries: the web app's own page, which needs no sign-in — the token is
   * the proof.
   *
   * @param webUrl the platform's public web address ({@code storeql.platform.web-url}), with or
   *     without a trailing slash
   * @param token the raw token, never its hash, exactly as {@code CapabilityTokens.mint()} gave it
   * @return {@code webUrl} (slash trimmed) + {@code "/#/reset-password/"} + {@code token}
   */
  public static String link(String webUrl, String token) {
    String base = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    return base + "/#/reset-password/" + token;
  }
}
