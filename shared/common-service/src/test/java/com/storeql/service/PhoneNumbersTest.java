package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link PhoneNumbers}: pure, no tenant lookup, no country ever named by this class itself — every
 * region tried comes from what the caller (the business's own data) hands in. Every number here is
 * one libphonenumber's own validity rules accept, checked against the library directly before being
 * used, so a metadata update that changes what counts as valid fails this test rather than passing
 * on a stale assumption.
 */
class PhoneNumbersTest {

  @ParameterizedTest(name = "{0} national form {1} -> {2}")
  @CsvSource({
    // Not 07700 900xxx: Ofcom's reserved "drama" mobile block, shaped like a UK mobile but not
    // one libphonenumber's own isValidNumber accepts (only isPossibleNumber, which this class
    // deliberately never uses) — the k6 suite's own GB example, so both agree.
    "GB, '07400 123456',         +447400123456",
    "PL, '512 345 678',          +48512345678",
    "IN, '98765 43210',          +919876543210",
    "US, '(650) 253-0000',       +16502530000",
    "DE, '030 12345678',         +493012345678",
    "AE, '050 123 4567',         +971501234567",
    "NG, '0803 123 4567',        +2348031234567",
    "BR, '(11) 91234-5678',      +5511912345678",
    "AU, '0412 345 678',         +61412345678",
    "JP, '090-1234-5678',        +819012345678",
  })
  void nationalFormsResolveUnderTheirOwnCountry(String country, String national, String e164) {
    assertEquals(
        e164,
        PhoneNumbers.toE164(national, country, List.of()),
        "spaces, dashes and brackets are just formatting");
  }

  @ParameterizedTest(name = "international form {0} -> {1}")
  @CsvSource({
    "'+44 7400 123456',     +447400123456",
    "'+48 512 345 678',     +48512345678",
    "'+91 98765 43210',     +919876543210",
    "'+1 650-253-0000',     +16502530000",
    "'+49 30 12345678',     +493012345678",
    "'+971 50 123 4567',    +971501234567",
    "'+234 803 123 4567',   +2348031234567",
    "'+55 11 91234-5678',   +5511912345678",
    "'+61 412 345 678',     +61412345678",
    "'+81 90-1234-5678',    +819012345678",
  })
  void aPlusNumberNeedsNoRegionAtAll(String international, String e164) {
    // No home country, no stores at all — a "+" number carries its own country calling code.
    assertEquals(e164, PhoneNumbers.toE164(international, null, null));
    // And it is unmoved by a region being offered that has nothing to do with it.
    assertEquals(e164, PhoneNumbers.toE164(international, "JP", List.of("BR")));
  }

  @Test
  void aPlusNumberThatIsNotAValidNumberIsNullNotARegionGuess() {
    // Ofcom's reserved "drama" mobile range: shaped like a UK mobile, not one libphonenumber
    // accepts — proving this returns null rather than silently accepting anything with a "+".
    assertNull(PhoneNumbers.toE164("+44 7700 900111", "GB", List.of()));
  }

  @Test
  void theHomeCountryIsTriedBeforeAnyStoreCountry() {
    // "512 345 678" is a valid number shape under both DE and PL (the library's own metadata),
    // so whichever wins tells us the trying order, not luck.
    assertEquals(
        "+49512345678",
        PhoneNumbers.toE164("512 345 678", "DE", List.of("PL")),
        "the home country, DE, wins over the store country, PL");
    assertEquals(
        "+48512345678",
        PhoneNumbers.toE164("512 345 678", "PL", List.of("DE")),
        "swap them: the home country, PL, wins over the store country, DE");
  }

  @Test
  void aTillReadsTheNumberInItsOwnStoresCountryFirst() {
    // "06 12 34 56 78" is a mobile in both the Netherlands and France (the library's own
    // metadata), so the order decides it. A business at home in France with a shop in the
    // Netherlands: a number given at the Dutch till is Dutch, the same digits at a French till
    // French — the store a sale is made at is the first country order-svc hands in.
    assertEquals(
        "+31612345678",
        PhoneNumbers.toE164("06 12 34 56 78", "NL", List.of("FR")),
        "at the Dutch shop of a French business");
    assertEquals(
        "+33612345678",
        PhoneNumbers.toE164("06 12 34 56 78", "FR", List.of("NL")),
        "at its French shop");
  }

  @Test
  void aStoreCountryIsTriedWhenTheHomeCountryDoesNotParseIt() {
    // A ten-digit Indian mobile shape: not a valid number under GB, so the business's own home
    // country alone (no stores) finds nothing —
    assertNull(PhoneNumbers.toE164("98765 43210", "GB", List.of()));
    // — but a store trading in IN is enough for the very same typed number to resolve.
    assertEquals(
        "+919876543210",
        PhoneNumbers.toE164("98765 43210", "GB", List.of("IN")),
        "a business based in GB with a store in IN: the store's country is tried too");
  }

  @Test
  void duplicateAndBlankRegionsAreSkippedHarmlessly() {
    assertEquals(
        "+48512345678",
        PhoneNumbers.toE164("512 345 678", "PL", Arrays.asList(null, "", "  ", "pl", "PL", "de")));
  }

  @Test
  void noRegionListMeansOnlyPlusNumbersParse() {
    assertNull(PhoneNumbers.toE164("512 345 678", null, null));
    assertNull(PhoneNumbers.toE164("512 345 678", null, List.of()));
    assertEquals("+48512345678", PhoneNumbers.toE164("+48 512 345 678", null, null));
  }

  @Test
  void garbageIsNullNotAGuess() {
    assertNull(PhoneNumbers.toE164("not a phone number", "GB", List.of()));
    assertNull(PhoneNumbers.toE164("banana", "GB", List.of()));
    assertNull(PhoneNumbers.toE164("12345", "GB", List.of()));
    assertNull(PhoneNumbers.toE164("++123", "GB", List.of()));
  }

  @Test
  void nullOrBlankRawIsNullWithNoAttemptAtAll() {
    assertNull(PhoneNumbers.toE164(null, "GB", List.of()));
    assertNull(PhoneNumbers.toE164("", "GB", List.of()));
    assertNull(PhoneNumbers.toE164("   ", "GB", List.of()));
  }
}
