package com.storeql.order.service;

import com.storeql.service.PhoneNumbers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A phone at the till (intent/phone-at-the-till.md), the pure part: what a store's till asks for
 * the customer's phone, whether a till sale lacks the number its store asks for, and how a contact
 * number reads — in the store's own country first, then the business's home and its other stores',
 * never a country named here. The service reads the store and the countries (TenantProfiles);
 * nothing here does I/O.
 */
public final class TillPhone {

  /** The till refuses a sale with neither a number nor a customer. */
  public static final String REQUIRED = "REQUIRED";

  /** The till asks; the customer may say no. Every store's until its owner chooses. */
  public static final String OPTIONAL = "OPTIONAL";

  /** The till never asks. */
  public static final String OFF = "OFF";

  private TillPhone() {}

  /**
   * What the store's till asks, as order-svc acts on it: {@code recorded} when it is REQUIRED or
   * OFF, else OPTIONAL — a store tenant-svc could not be asked about never refuses a sale.
   *
   * @param recorded the store's recorded choice, or {@code null} when unknown
   * @return REQUIRED, OPTIONAL or OFF
   */
  public static String ask(String recorded) {
    return REQUIRED.equals(recorded) || OFF.equals(recorded) ? recorded : OPTIONAL;
  }

  /**
   * Whether a till sale at a store that asks {@code ask} lacks what it asks for.
   *
   * @param ask what the store's till asks ({@link #ask})
   * @param customerId the customer the sale names, who can be reached without a number
   * @param phone the number given, as typed
   * @return true only at a REQUIRED store, for a sale with no number and no customer
   */
  public static boolean missing(String ask, UUID customerId, String phone) {
    return REQUIRED.equals(ask) && customerId == null && blank(phone);
  }

  /**
   * How a contact number read.
   *
   * @param e164 its international form, or {@code null} when it did not read
   * @param unreadable whether it was given, could be judged — it carries its own {@code "+"}, or at
   *     least one of the business's countries was known — and is no phone in any of them. A number
   *     that could not be judged (no country known) is never unreadable: it is kept as typed.
   */
  public record Reading(String e164, boolean unreadable) {}

  /**
   * Reads a contact number: in the store's own country, then the business's home, then its other
   * stores' countries.
   *
   * @param typed the number as given; {@code null} or blank reads as no number
   * @param storeCountry the country of the store the sale is at, or {@code null}
   * @param homeCountry the business's own country, or {@code null}
   * @param otherCountries the business's stores' countries, or {@code null}
   * @return how it read
   */
  public static Reading read(
      String typed, String storeCountry, String homeCountry, Collection<String> otherCountries) {
    if (blank(typed)) return new Reading(null, false);
    List<String> then = new ArrayList<>();
    if (!blank(homeCountry)) then.add(homeCountry);
    if (otherCountries != null) then.addAll(otherCountries);
    String e164 = PhoneNumbers.toE164(typed, storeCountry, then);
    if (e164 != null) return new Reading(e164, false);
    boolean judged =
        typed.strip().startsWith("+")
            || !blank(storeCountry)
            || then.stream().anyMatch(c -> !blank(c));
    return new Reading(null, judged);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
