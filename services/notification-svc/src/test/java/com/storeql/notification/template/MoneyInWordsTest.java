package com.storeql.notification.template;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A message says money at its currency's own minor units — whole yen, a dinar's three places, a
 * pound's two — and a figure that arrives finer is rounded half up, as every other figure on the
 * platform is (common-service {@code Fx}), never half-even.
 */
class MoneyInWordsTest {

  private static final Locale EN_GB = Locale.forLanguageTag("en-GB");

  private static String say(String amount, String currency) {
    // Spacing between a code and its figure is the JDK's CLDR data's to decide; the digits are
    // ours.
    return Values.money(new BigDecimal(amount), currency, EN_GB)
        .replaceAll("[\\s\\u00a0\\u202f]", "");
  }

  @Test
  @DisplayName("A dinar keeps three places, a yen none, a pound two")
  void theCurrencysOwnUnits() {
    assertEquals("KWD1.125", say("1.125", "KWD"));
    assertEquals("KWD1.100", say("1.1", "KWD"));
    assertEquals("JP¥4,442", say("4442.0000", "JPY"));
    assertEquals("£12.50", say("12.5000", "GBP"));
  }

  @Test
  @DisplayName("A finer figure is rounded half up at the currency's units")
  void halfUp() {
    assertEquals("£2.35", say("2.345", "GBP"));
    assertEquals("KWD2.346", say("2.3455", "KWD"));
    assertEquals("JP¥1,001", say("1000.5", "JPY"));
  }

  @Test
  @DisplayName("A code that is not ISO 4217 is said as it came")
  void notACurrency() {
    assertEquals("12.50XYZ", say("12.50", "XYZ"));
  }
}
