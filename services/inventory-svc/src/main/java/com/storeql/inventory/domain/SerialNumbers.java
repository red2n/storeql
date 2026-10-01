package com.storeql.inventory.domain;

import com.storeql.ids.Ids;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure rules for serial numbers: how a generated number is made and how a supplied list is judged.
 * Matching is case-sensitive and exact after trimming, the same as the unique pair {@code
 * (tenant_id, serial_no)} and {@code findSerialByNo}.
 */
public final class SerialNumbers {

  /** How many times one generated number is made again when it clashes, before giving up. */
  public static final int MAX_GENERATION_ATTEMPTS = 5;

  private static final int TAIL_LENGTH = 16;

  private SerialNumbers() {}

  /**
   * Makes one readable, collision-resistant number: the prefix and the random tail of a fresh id.
   *
   * @param prefix the prefix to keep
   * @return {@code prefix-TAIL}, the tail being 16 upper-case hex characters
   */
  public static String generate(String prefix) {
    String hex = Ids.newId().toString().replace("-", "");
    return prefix + "-" + hex.substring(hex.length() - TAIL_LENGTH).toUpperCase(Locale.ROOT);
  }

  /**
   * Trims each supplied number.
   *
   * @param supplied the numbers as sent
   * @return the trimmed numbers, in order
   * @throws IllegalArgumentException when a number is null or blank
   */
  public static List<String> normalise(List<String> supplied) {
    List<String> out = new ArrayList<>(supplied.size());
    for (String s : supplied) {
      if (s == null || s.isBlank()) {
        throw new IllegalArgumentException("blank serial number");
      }
      out.add(s.trim());
    }
    return out;
  }

  /**
   * The numbers that appear more than once in a list.
   *
   * @param serialNos normalised numbers
   * @return each repeated number once, sorted
   */
  public static List<String> repeated(List<String> serialNos) {
    Set<String> seen = new HashSet<>();
    Set<String> repeats = new TreeSet<>();
    for (String s : serialNos) {
      if (!seen.add(s)) {
        repeats.add(s);
      }
    }
    return List.copyOf(repeats);
  }
}
