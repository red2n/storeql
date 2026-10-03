package com.storeql.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A batch's date is the last day it may be sold, read at the store's own day. */
class ExpiryTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-09-30");

  @Test
  void aBatchIsSellableOnItsLastDayAndNotTheDayAfter() {
    assertTrue(Expiry.sellable(TODAY, TODAY));
    assertTrue(Expiry.sellable(TODAY.plusDays(1), TODAY));
    assertFalse(Expiry.sellable(TODAY.minusDays(1), TODAY));
  }

  @Test
  void aBatchWithNoDateIsUnaffected() {
    assertTrue(Expiry.sellable(null, TODAY));
    assertTrue(Expiry.sellable(null, LocalDate.parse("2099-01-01")));
  }

  @Test
  void aStoreWithNoKnownZoneIsReadInUtc() {
    UUID store = Ids.newId();
    Expiry x = Expiry.utc(Instant.parse("2026-09-30T23:30:00Z"));
    assertEquals(TODAY, x.today(store));
    assertTrue(x.sellableAt(store, TODAY));
    assertFalse(x.sellableAt(store, TODAY.minusDays(1)));
  }

  @Test
  void eachStoreIsReadInItsOwnZone() {
    UUID auckland = Ids.newId();
    UUID honolulu = Ids.newId();
    UUID london = Ids.newId();
    UUID unknown = Ids.newId();
    // 23:30 UTC on the 30th: already 1 October in Auckland, still the 30th in Honolulu and London
    // (BST is 00:30 on the 1st).
    Expiry x =
        Expiry.at(
            Instant.parse("2026-09-30T23:30:00Z"),
            Map.of(
                auckland, ZoneId.of("Pacific/Auckland"),
                honolulu, ZoneId.of("Pacific/Honolulu"),
                london, ZoneId.of("Europe/London")));
    assertEquals(LocalDate.parse("2026-10-01"), x.today(auckland));
    assertEquals(LocalDate.parse("2026-09-30"), x.today(honolulu));
    assertEquals(LocalDate.parse("2026-10-01"), x.today(london));
    assertEquals(TODAY, x.today(unknown));

    // The same batch, dated the 30th: sold in Honolulu and elsewhere unknown, not in Auckland.
    assertFalse(x.sellableAt(auckland, TODAY));
    assertTrue(x.sellableAt(honolulu, TODAY));
    assertTrue(x.sellableAt(unknown, TODAY));
  }

  @Test
  void theSqlNamesTheDayAndIsTheNegationOfItsOpposite() {
    Expiry x = Expiry.utc(Instant.parse("2026-09-30T12:00:00Z"));
    assertEquals(
        "(b.expiry_date IS NULL OR b.expiry_date >= DATE '2026-09-30')", x.sellableSql("b"));
    assertEquals(
        "(b.expiry_date IS NOT NULL AND b.expiry_date < DATE '2026-09-30')", x.expiredSql("b"));
    assertEquals("(expiry_date IS NULL OR expiry_date >= DATE '2026-09-30')", x.sellableSql(""));
  }

  @Test
  void theSqlPicksTheStoresDayWhereItDiffersFromUtc() {
    UUID auckland = Ids.newId();
    Expiry x =
        Expiry.at(
            Instant.parse("2026-09-30T23:30:00Z"), Map.of(auckland, ZoneId.of("Pacific/Auckland")));
    String sql = x.sellableSql("b");
    assertTrue(sql.contains("CASE WHEN b.store_id IN ('" + auckland + "') THEN DATE '2026-10-01'"));
    assertTrue(sql.contains("ELSE DATE '2026-09-30' END"));
  }
}
