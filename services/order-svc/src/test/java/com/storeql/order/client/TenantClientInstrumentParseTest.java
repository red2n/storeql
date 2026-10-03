package com.storeql.order.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.TradeScales;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reading tenant-svc's answer for one weighing instrument, {@code GET
 * /admin/stores/{storeId}/weighing-instruments/{id}}, as it answers today: the register's own
 * {@code certified} and {@code standing}, and a refusal told apart by its stable code, so a route
 * that is simply missing is never taken for "not registered". When its latest history entry was
 * recorded, when that falls due and when the instrument last changed are read too, leniently, so a
 * replayed sale can be judged as at the moment it was rung up.
 */
class TenantClientInstrumentParseTest {

  private static final UUID SHOP = Ids.newId();
  private static final UUID DELI = Ids.newId();

  private static String answer(UUID storeId, boolean certified, String standing) {
    return "{\"data\":{\"id\":\""
        + DELI
        + "\",\"storeId\":\""
        + storeId
        + "\",\"identifier\":\"Deli 1\",\"status\":\"IN_SERVICE\",\"certified\":"
        + certified
        + ",\"standing\":\""
        + standing
        + "\",\"latestVerification\":null}}";
  }

  @Test
  void readsTheRegistersOwnStanding() {
    var read = TenantClient.instrumentOf(DELI, SHOP, answer(SHOP, false, "OVERDUE"));
    var entry = read.orElseThrow();
    assertThat(entry.registered(), is(true));
    assertThat(entry.identifier(), is("Deli 1"));
    assertThat(entry.certified(), is(false));
    assertThat(entry.standing(), is("OVERDUE"));
    assertThat(entry.fitForTrade(), is(false));
    var certified = TenantClient.instrumentOf(DELI, SHOP, answer(SHOP, true, "CERTIFIED"));
    assertThat(certified.orElseThrow().fitForTrade(), is(true));
  }

  @Test
  void anInstrumentStandingInAnotherStoreIsNotHeldHere() {
    var entry = TenantClient.instrumentOf(DELI, SHOP, answer(Ids.newId(), true, "CERTIFIED"));
    assertThat(entry.orElseThrow().registered(), is(false));
  }

  @Test
  void anAnswerThatCannotBeReadIsNoAnswer() {
    assertThat(TenantClient.instrumentOf(DELI, SHOP, "<html>busy</html>").isPresent(), is(false));
  }

  @Test
  void aRefusalIsToldApartByItsCodeInEitherShape() {
    assertThat(
        TenantClient.errorCode("{\"code\":\"INSTRUMENT_NOT_FOUND\",\"status\":404}"),
        is("INSTRUMENT_NOT_FOUND"));
    assertThat(
        TenantClient.errorCode("{\"error\":{\"code\":\"INSTRUMENT_NOT_FOUND\"}}"),
        is("INSTRUMENT_NOT_FOUND"));
    assertThat(TenantClient.errorCode("{\"error\":{\"code\":\"NOT_FOUND\"}}"), is("NOT_FOUND"));
    assertThat(TenantClient.errorCode("not json"), is(nullValue()));
  }

  /** A verification passed a year ago, due again on 28 September 2026. */
  private static final String DUE_28_SEPTEMBER =
      "{\"kind\":\"RE_VERIFICATION\",\"performedOn\":\"2025-09-28\",\"passed\":true,"
          + "\"nextDue\":\"2026-09-28\",\"recordedAt\":\"2025-09-28T10:00:00Z\"}";

  /** An unfit scale at the shop, with its latest history entry and its last change. */
  private static String unfit(String standing, String latest, String updatedAt) {
    return "{\"data\":{\"id\":\""
        + DELI
        + "\",\"storeId\":\""
        + SHOP
        + "\",\"identifier\":\"Deli 1\",\"status\":\"IN_SERVICE\",\"certified\":false,"
        + "\"standing\":\""
        + standing
        + "\",\"latestVerification\":"
        + latest
        + ",\"updatedAt\":"
        + updatedAt
        + "}}";
  }

  @Test
  void readsWhenItsLatestEntryWasRecordedAndFallsDueAndWhenItLastChanged() {
    var overdue =
        TenantClient.instrumentOf(
            DELI, SHOP, unfit("OVERDUE", DUE_28_SEPTEMBER, "\"2025-09-28T10:05:00Z\""));
    var entry = overdue.orElseThrow();
    assertThat(entry.checkedAt(), is(Instant.parse("2025-09-28T10:00:00Z")));
    assertThat(entry.nextDue(), is(LocalDate.parse("2026-09-28")));
    assertThat(entry.changedAt(), is(Instant.parse("2025-09-28T10:05:00Z")));
    assertThat(
        "certified through its due date, by the date in UTC",
        entry.atSale(Instant.parse("2026-09-28T23:59:59Z")).fit(),
        is(true));
    assertThat(entry.atSale(Instant.parse("2026-09-29T00:00:00Z")).fit(), is(false));
    var failed =
        TenantClient.instrumentOf(
            DELI,
            SHOP,
            unfit(
                "FAILED",
                "{\"kind\":\"INSPECTION\",\"passed\":false,"
                    + "\"recordedAt\":\"2026-09-29T08:15:00Z\"}",
                "null"));
    assertThat(failed.orElseThrow().checkedAt(), is(Instant.parse("2026-09-29T08:15:00Z")));
    assertThat(failed.get().nextDue(), is(nullValue()));
    var off =
        TenantClient.instrumentOf(
            DELI, SHOP, unfit("OUT_OF_SERVICE", "null", "\"2026-09-29T07:00:00Z\""));
    assertThat(off.orElseThrow().changedAt(), is(Instant.parse("2026-09-29T07:00:00Z")));
    assertThat(off.get().checkedAt(), is(nullValue()));
  }

  @Test
  void timesThatCannotBeReadAreTakenAsNotSaidAndTheScaleIsInDoubt() {
    var entry =
        TenantClient.instrumentOf(
            DELI, SHOP, unfit("OUT_OF_SERVICE", "\"not an entry\"", "\"last week\""));
    assertThat("the scale is still read", entry.isPresent(), is(true));
    assertThat(entry.get().standing(), is("OUT_OF_SERVICE"));
    assertThat(entry.get().checkedAt(), is(nullValue()));
    assertThat(entry.get().changedAt(), is(nullValue()));
    assertThat(
        "a replay weighed on it is flagged as one nobody can show was fit, never let through",
        entry.get().atSale(Instant.parse("2026-09-29T06:00:00Z")).doubt(),
        is(TradeScales.Doubt.CHANGE_UNDATED));
  }
}
