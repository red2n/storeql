package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The card-number detector the PCI scope rests on. Every positive is a scheme test number; every
 * negative is something retail data is actually full of, because a detector that refuses a product
 * barcode is switched off within the week and protects nothing after that.
 */
class CardDataTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "4111111111111111", // Visa
        "4111 1111 1111 1111", // as people type it
        "4111-1111-1111-1111",
        "try 7 4111 1111 1111 1111", // a stray number in front does not hide it
        "4111 1111 1111 1111 1234", // nor a stray number after
        "5555555555554444", // Mastercard
        "2223003122003222", // Mastercard 2-series
        "378282246310005", // American Express, 15 digits
        "6011111111111117", // Discover
        "3530111333300000", // JCB
        "6200000000000005", // UnionPay
        "4111111111111111110", // Visa, 19 digits (check digit computed below)
      })
  @DisplayName("Scheme test numbers are found, however they are spaced")
  void findsCardNumbers(String pan) {
    assertTrue(CardData.containsPan("card: " + pan + " exp 12/28"), pan);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "5012345678900", // EAN-13, UK prefix — 13 digits are never a card
        "4006381333931", // EAN-13, German prefix, Luhn-valid by chance
        "36123456789012", // GTIN-14 with a Diners-looking prefix
        "353918050478917", // an IMEI: 15 digits, Luhn-valid, not an Amex range
        "4111111111111112", // Visa-shaped, fails Luhn
        "1234567890123456", // 16 digits, no issuer range
        "01a090ae-611e-702a-9bdf-bcc7032115c4", // a UUIDv7
        "01234567-8901-7234-8567-890123456789", // an all-digit UUID: one long run, not a card
        "+44 7911 123456", // a phone number
        "+1 415 555 0132 ext 4111", // spaced digits that add up to nothing
        "12 4006381333931 99", // a barcode between two counts is still a barcode
        "2026-09-12T10:00:00.123456789Z", // a timestamp
        "ORD-2026-000042", // an order number
        "41111111111111111234", // a PAN embedded in a longer run is a longer number
        "GB-LDN-01-2026-000042", // a receipt number
      })
  @DisplayName("Barcodes, identifiers, phone numbers and timestamps are not cards")
  void ignoresRetailData(String text) {
    assertFalse(CardData.containsPan("value: " + text + " end"), text);
  }

  @Test
  @DisplayName("Masking keeps the last four and the shape, and nothing else")
  void masks() {
    assertEquals(
        "paid with **** **** **** 1111 today",
        CardData.mask("paid with 4111 1111 1111 1111 today"));
    assertEquals("***********0005", CardData.mask("378282246310005"));
    assertEquals(
        "two: ************4444 and ************1117",
        CardData.mask("two: 5555555555554444 and 6011111111111117"));
    // Nothing to mask: the text comes back as it was, barcode intact.
    assertEquals("barcode 5012345678900", CardData.mask("barcode 5012345678900"));
  }

  @Test
  @DisplayName("Empty and null are not cards")
  void emptyAndNull() {
    assertFalse(CardData.containsPan(null));
    assertFalse(CardData.containsPan(""));
    assertEquals(null, CardData.mask(null));
  }

  @Test
  @DisplayName("The 19-digit Visa in the positives really is Luhn-valid")
  void nineteenDigitVisaIsLuhnValid() {
    assertTrue(CardData.luhn("4111111111111111110"));
  }

  /**
   * SJ-D67: a UUID's all-digit groups can line up to a Luhn-valid run in an issuer's range — one v7
   * id in fifty thousand does — and an ordinary request carrying ids was refused as a card. A run
   * inside a UUID's 8-4-4-4-12 shape is an identifier, whatever its digits add up to.
   */
  @Test
  void uuidsAreIdentifiersNotCards() {
    String[] ids = {
      "019993e0-5100-7069-8187-0918e7284e47", // 5100 7069 8187 0918: Mastercard 51, Luhn-valid
      "01998bcd-4236-7684-9322-3069a2373271", // 4236 7684 9322 3069: Visa
      "01999912-4338-7254-9644-4289627cc2e4", // 4338725496444289627: a 19-digit Visa
      "01a0f3c4-4111-7111-9111-007abcdef012" // 4 4111 7111 9111 007: a run ending mid-group
    };
    for (String id : ids) {
      String body = "{\"userId\":\"" + id + "\",\"storeId\":\"" + id + "\",\"role\":\"CASHIER\"}";
      assertFalse(CardData.containsPan(body), id);
      assertEquals(body, CardData.mask(body), id);
      assertFalse(CardData.containsPan(id.toUpperCase(java.util.Locale.ROOT)), id);
    }
    // A card beside a UUID is still a card, and only the card is masked.
    String note = "ref 019993e0-5100-7069-8187-0918e7284e47 card 4111 1111 1111 1111";
    assertTrue(CardData.containsPan(note));
    assertEquals(
        "ref 019993e0-5100-7069-8187-0918e7284e47 card **** **** **** 1111", CardData.mask(note));
    // The shape has to be whole: a UUID missing a group is a run of digits like any other.
    assertTrue(CardData.containsPan("5100-7069-8187-0918"));
  }
}
