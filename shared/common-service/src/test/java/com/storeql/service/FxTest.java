package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.Fx.Converted;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FX arithmetic and the cached rate table (03.x). */
class FxTest {

  @Test
  @DisplayName(
      "Converting rounds to the target currency's minor units: pence, whole yen, a dinar's third decimal")
  void minorUnitsPerCurrency() {
    assertEquals(2, Fx.minorUnits("GBP"));
    assertEquals(0, Fx.minorUnits("JPY"));
    assertEquals(3, Fx.minorUnits("KWD"));
    assertEquals(2, Fx.minorUnits("XYZ"));
    // $100 at 0.79 is £79.00; £10 at 0.0053 GBP per yen is ¥1,887, not ¥1,886.79.
    assertEquals(
        0,
        new BigDecimal("79.00")
            .compareTo(Fx.toHome(new BigDecimal("100"), new BigDecimal("0.79"), "GBP")),
        "Fx.toHome(new BigDecimal(100), new Big");
    assertEquals(
        0,
        new BigDecimal("1887")
            .compareTo(Fx.fromHome(new BigDecimal("10"), new BigDecimal("0.0053"), "JPY")),
        "Fx.fromHome(new BigDecimal(10), new Bi");
    assertEquals(
        0,
        new BigDecimal("3.846")
            .compareTo(Fx.fromHome(new BigDecimal("10"), new BigDecimal("2.6"), "KWD")),
        "Fx.fromHome(new BigDecimal(10), new Bi");
    // A third of a penny rounds half up.
    assertEquals(
        0,
        new BigDecimal("0.01")
            .compareTo(Fx.toHome(new BigDecimal("1"), new BigDecimal("0.005"), "GBP")),
        "Fx.toHome(new BigDecimal(1), new BigDe");
  }

  @Test
  @DisplayName(
      "A rate is refused when absent, not positive, too precise or absurd; a currency must be one ISO knows")
  void validation() {
    assertNull(Fx.validateRate(new BigDecimal("0.79")));
    assertTrue(Fx.validateRate(null).contains("required"));
    assertTrue(Fx.validateRate(BigDecimal.ZERO).contains("above zero"));
    assertTrue(Fx.validateRate(new BigDecimal("-1")).contains("above zero"));
    assertTrue(Fx.validateRate(new BigDecimal("0.12345678901")).contains("decimal"));
    assertTrue(Fx.validateRate(new BigDecimal("1000000000001")).contains("large"));
    assertTrue(Fx.isCurrency("usd"));
    assertFalse(Fx.isCurrency("XYZ"));
    assertFalse(Fx.isCurrency("POUNDS"));
    assertFalse(Fx.isCurrency(null));
  }

  private static final String TABLE =
      "{\"data\":{\"home\":\"GBP\",\"rates\":[{\"currency\":\"USD\",\"rate\":0.79,\"effectiveFrom\":\"2026-09-01\"},"
          + "{\"currency\":\"jpy\",\"rate\":0.0053}]}}";

  @Test
  @DisplayName(
      "The table is read once a minute, converts both ways, and the home currency converts to itself")
  void readsCachesAndConverts() {
    UUID tenant = Ids.newId();
    AtomicInteger reads = new AtomicInteger();
    Instant start = Instant.parse("2026-09-23T10:00:00Z");
    AtomicInteger minutes = new AtomicInteger();
    Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return start.plus(Duration.ofMinutes(minutes.get()));
          }
        };
    FxRates rates =
        FxRates.forTest(
            id -> {
              reads.incrementAndGet();
              return Optional.of(TABLE);
            },
            clock);
    assertEquals("GBP", rates.home(tenant).orElseThrow());
    assertEquals(List.of("GBP", "JPY", "USD"), rates.currencies(tenant));
    Converted home = rates.toHome(tenant, new BigDecimal("100"), "usd").orElseThrow();
    assertEquals(0, new BigDecimal("79.00").compareTo(home.amount()), "home.amount()");
    assertEquals("GBP", home.currency());
    assertEquals(0, new BigDecimal("0.79").compareTo(home.rate()), "home.rate()");
    Converted shown = rates.fromHome(tenant, new BigDecimal("79.00"), "USD").orElseThrow();
    assertEquals(0, new BigDecimal("100.00").compareTo(shown.amount()), "shown.amount()");
    Converted same = rates.toHome(tenant, new BigDecimal("5"), "GBP").orElseThrow();
    assertEquals(0, BigDecimal.ONE.compareTo(same.rate()), "same.rate()");
    assertEquals("2026-09-01", rates.rate(tenant, "USD").orElseThrow().effectiveFrom().toString());
    assertFalse(rates.toHome(tenant, BigDecimal.TEN, "EUR").isPresent());
    assertEquals(1, reads.get());
    minutes.set(2);
    rates.home(tenant);
    assertEquals(2, reads.get());
  }

  @Test
  @DisplayName("A table that cannot be read is no table, and is asked for again next time")
  void unreadableIsEmptyAndNotCached() {
    UUID tenant = Ids.newId();
    AtomicInteger reads = new AtomicInteger();
    FxRates rates =
        FxRates.forTest(
            id -> {
              reads.incrementAndGet();
              // The first two reads fail (the table, then the currencies); the third succeeds.
              return reads.get() <= 2 ? Optional.empty() : Optional.of(TABLE);
            },
            Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC));
    assertFalse(rates.table(tenant).isPresent());
    assertTrue(rates.currencies(tenant).isEmpty());
    assertTrue(rates.table(tenant).isPresent());
    assertFalse(FxRates.parse("{\"data\":{}}").isPresent());
    assertFalse(FxRates.parse("not json").isPresent());
  }
}
