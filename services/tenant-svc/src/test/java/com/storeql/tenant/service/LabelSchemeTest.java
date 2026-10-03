package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A labelling scale's scheme is kept as it was sent, so it is judged as it was sent: JSON-P's
 * {@code getInt} cuts a number down ({@code 4294967300} is 4, {@code 4.9} is 4), and the cut figure
 * passed the check while the scheme kept, and the till later read, the one that was sent.
 */
class LabelSchemeTest {

  private static String scheme(String itemDigits, String valueDecimals) {
    return "{\"prefixes\":[\"20\"],\"itemDigits\":"
        + itemDigits
        + ",\"valueKind\":\"WEIGHT\",\"valueDecimals\":"
        + valueDecimals
        + "}";
  }

  private static void refused(String raw) {
    ApiException e =
        assertThrows(
            ApiException.class, () -> WeighingInstrumentService.schemeOf("LABELLING", raw), raw);
    assertEquals(400, e.status(), raw);
    assertEquals("INSTRUMENT_SCHEME_INVALID", e.code(), raw);
  }

  @Test
  @DisplayName(
      "A scheme's digits and places are judged as sent: 4294967300 and 4.9 are not 4, 4294967296"
          + " is not 0, 5.0 is no whole number as written, and 1E+80000000 is refused without"
          + " being expanded")
  void aSchemesNumbersAreJudgedAsSent() {
    for (String cut :
        List.of("4294967300", "4294967301", "4.9", "5.5", "5.0", "1E+80000000", "\"4\"")) {
      refused(scheme(cut, "3"));
    }
    for (String cut : List.of("4294967296", "4294967298", "2.5", "1E-80000000", "-0.5")) {
      refused(scheme("4", cut));
    }
  }

  @Test
  @DisplayName(
      "A scheme is taken with its digits and places written as whole numbers, which is how a till"
          + " reads them back (5.0 would be kept and read as a fraction)")
  void aWholeNumberIsTaken() {
    assertEquals(
        scheme("4", "3"), WeighingInstrumentService.schemeOf("LABELLING", scheme("4", "3")));
    assertEquals(
        scheme("5", "0"), WeighingInstrumentService.schemeOf("LABELLING", scheme("5", "0")));
  }
}
