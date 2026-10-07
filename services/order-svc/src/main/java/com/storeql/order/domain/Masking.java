package com.storeql.order.domain;

/** Hides most of a personal address where a reader has no need to see it whole. Pure. */
public final class Masking {

  private Masking() {}

  /**
   * An email address with everything but its first character and its domain hidden: {@code
   * jane.doe@example.com} reads {@code j***@example.com}. Text with no {@code @} is not an address
   * that can be shown in part, so it is hidden whole.
   *
   * @param address the address, possibly null
   * @return the masked form, or null for null
   */
  public static String email(String address) {
    if (address == null) return null;
    String a = address.strip();
    int at = a.lastIndexOf('@');
    if (at < 1) return "***";
    return a.substring(0, a.offsetByCodePoints(0, 1)) + "***" + a.substring(at);
  }
}
