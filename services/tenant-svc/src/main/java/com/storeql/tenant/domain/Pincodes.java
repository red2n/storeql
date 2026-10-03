package com.storeql.tenant.domain;

import java.util.Locale;

/**
 * A postal code as delivery areas keep and match it. No country's format is assumed — a code may be
 * digits, letters, with or without a space — so the only normalisation is the one every format
 * survives: surrounding space dropped and runs of white space made one. Matching is then
 * case-insensitive, never by removing spaces (in some places a space separates two codes).
 */
public final class Pincodes {

  private Pincodes() {}

  /** The code as stored and looked up; null for null. */
  public static String normalise(String raw) {
    return raw == null ? null : raw.strip().replaceAll("\\s+", " ");
  }

  /** The form two codes are compared in. */
  public static String key(String raw) {
    String n = normalise(raw);
    return n == null ? null : n.toLowerCase(Locale.ROOT);
  }
}
