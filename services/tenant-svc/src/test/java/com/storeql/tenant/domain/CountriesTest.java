package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What counts as a country: an ISO 3166-1 alpha-2 code, and nothing that merely looks like one. */
class CountriesTest {

  // Spelled by code point: left as characters they are invisible, or look like the letter they are
  // not, and the formatter turns an escape back into the character.
  private static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000);
  private static final String DOTLESS_I = Character.toString(0x131);
  private static final String LONG_S = Character.toString(0x17f);
  private static final String E_ACUTE = Character.toString(0xc9);

  @Test
  @DisplayName("Every code the JDK knows is a country, in either case and with space around it")
  void everyIsoCodeIsOne() {
    for (String code : Locale.getISOCountries()) {
      assertEquals(code, Countries.require(code));
      assertEquals(code, Countries.require(code.toLowerCase(Locale.ROOT)));
      assertEquals(code, Countries.require("  " + code + "\t"));
    }
    assertEquals("GB", Countries.require("gb"));
    assertEquals("DE", Countries.require(" De "));
  }

  @Test
  @DisplayName(
      "Two characters are not enough: UK, ZZ, EU, digits, ideographic spaces and a dotless i")
  void twoCharactersAreNotACountry() {
    for (String bad :
        new String[] {
          "UK",
          "ZZ",
          "XX",
          "EU",
          "G1",
          "1G",
          "--",
          IDEOGRAPHIC_SPACE.repeat(2),
          DOTLESS_I + "n",
          LONG_S + "E",
          "G" + E_ACUTE
        }) {
      ApiException e = assertThrows(ApiException.class, () -> Countries.require(bad), bad);
      assertEquals(400, e.status(), bad);
      assertEquals("COUNTRY_INVALID", e.code(), bad);
    }
  }

  @Test
  @DisplayName("Nothing, blank or too long is no country, and the refusal is the same one")
  void nothingIsNoCountry() {
    for (String bad :
        new String[] {
          null,
          "",
          " ",
          IDEOGRAPHIC_SPACE,
          "G",
          "GBR",
          "United Kingdom",
          "GB' OR '1'='1",
          "9".repeat(300)
        }) {
      ApiException e = assertThrows(ApiException.class, () -> Countries.require(bad));
      assertEquals("COUNTRY_INVALID", e.code(), String.valueOf(bad));
    }
  }

  @Test
  @DisplayName("A country that may be left out stays out, and one that is named must be one")
  void optionalIsNothingOrACountry() {
    assertNull(Countries.optional(null));
    assertNull(Countries.optional(""));
    assertNull(Countries.optional("   "));
    assertNull(Countries.optional(IDEOGRAPHIC_SPACE.repeat(2)));
    assertEquals("FR", Countries.optional("fr"));
    ApiException e = assertThrows(ApiException.class, () -> Countries.optional("UK"));
    assertEquals("COUNTRY_INVALID", e.code());
    assertThrows(ApiException.class, () -> Countries.optional("Deutschland"));
  }
}
