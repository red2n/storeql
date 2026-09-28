package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spend authority: who may commit the business to how much, in which currency.
 *
 * <p>The currency cases are the ones worth writing. A ceiling is a number with a unit, StoreQL has
 * no FX handling to convert with, and a limit table that is right for sterling and silently applied
 * to yen is not a smaller version of the control — it is a different control, off by a factor of
 * roughly 190.
 */
class SpendAuthorityTest {

  private static Map<String, Map<String, BigDecimal>> limits(String... entries) {
    Map<String, Map<String, BigDecimal>> byCurrency = new LinkedHashMap<>();
    for (String e : entries) {
      String[] p = e.split(":");
      Map<String, BigDecimal> byRole = byCurrency.computeIfAbsent(p[0], k -> new LinkedHashMap<>());
      byRole.put(p[1], "UNLIMITED".equals(p[2]) ? null : new BigDecimal(p[2]));
    }
    return byCurrency;
  }

  private static SpendAuthority decide(
      String net, String currency, Map<String, Map<String, BigDecimal>> limits, String... roles) {
    return SpendAuthority.decide(
        net == null ? null : new BigDecimal(net), currency, Set.of(roles), limits);
  }

  // ── the control itself ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Under the ceiling submits; over it needs approval")
  void theBoundary() {
    var l = limits("GBP:MANAGER:5000");
    assertThat(decide("4999.99", "GBP", l, "MANAGER").authorised(), is(true));
    assertThat(decide("5000.00", "GBP", l, "MANAGER").authorised(), is(true));
    assertThat(decide("5000.01", "GBP", l, "MANAGER").authorised(), is(false));
  }

  @Test
  @DisplayName("A cashier holding no purchase authority cannot commit anything")
  void noAuthorityAtAll() {
    var decision = decide("1.00", "GBP", limits("GBP:MANAGER:5000"), "CASHIER");
    assertThat(decision.authorised(), is(false));
    assertThat(decision.role(), is(nullValue()));
    assertThat(decision.reason(), containsString("none of your roles"));
  }

  @Test
  @DisplayName("UNLIMITED authority approves any amount, including one with no ceiling to compare")
  void unlimited() {
    var decision = decide("99999999", "GBP", limits("GBP:OWNER:UNLIMITED"), "OWNER");
    assertThat(decision.authorised(), is(true));
    assertThat(decision.unlimited(), is(true));
    assertThat(decision.ceiling(), is(nullValue()));
  }

  @Test
  @DisplayName("The caller's most generous role decides, not whichever the set iterates first")
  void mostGenerousRoleWins() {
    var l = limits("GBP:STOREKEEPER:500", "GBP:MANAGER:5000");
    // A bug here would only appear for multi-role users, which is the hardest kind to notice.
    var decision = decide("3000", "GBP", l, "STOREKEEPER", "MANAGER");
    assertThat(decision.authorised(), is(true));
    assertThat(decision.role(), is("MANAGER"));
    assertThat(decision.ceiling(), comparesEqualTo(new BigDecimal("5000")));
  }

  @Test
  @DisplayName("Unlimited beats a finite ceiling regardless of which role is seen first")
  void unlimitedBeatsFinite() {
    var l = limits("GBP:STOREKEEPER:500", "GBP:OWNER:UNLIMITED");
    assertThat(decide("1000000", "GBP", l, "STOREKEEPER", "OWNER").unlimited(), is(true));
    assertThat(decide("1000000", "GBP", l, "OWNER", "STOREKEEPER").unlimited(), is(true));
  }

  // ── multi-currency, which is the whole point ────────────────────────────────

  @Test
  @DisplayName("Each currency has its own ceiling — 800,000 JPY is not 800,000 GBP")
  void ceilingsArePerCurrency() {
    // Roughly equivalent authorities in each market, which is what a real tenant would configure.
    var l =
        limits(
            "GBP:MANAGER:5000",
            "JPY:MANAGER:800000",
            "INR:MANAGER:500000",
            "USD:MANAGER:6000",
            "CNY:MANAGER:45000");

    // ¥700,000 is about £3,600 — inside a Japanese manager's authority.
    assertThat(decide("700000", "JPY", l, "MANAGER").authorised(), is(true));
    // The same NUMBER in sterling is £700,000, and must not be.
    assertThat(decide("700000", "GBP", l, "MANAGER").authorised(), is(false));

    assertThat(decide("450000", "INR", l, "MANAGER").authorised(), is(true));
    assertThat(decide("550000", "INR", l, "MANAGER").authorised(), is(false));
    assertThat(decide("44000", "CNY", l, "MANAGER").authorised(), is(true));
    assertThat(decide("5999", "USD", l, "MANAGER").authorised(), is(true));
  }

  @Test
  @DisplayName("A currency with no configured ceiling fails CLOSED, not open")
  void unconfiguredCurrencyFailsClosed() {
    // The tenant configured sterling and then bought from a Japanese supplier. The first overseas
    // order is precisely the one nobody reviewed, so it must not be the one that sails through.
    var decision = decide("1", "JPY", limits("GBP:OWNER:UNLIMITED"), "OWNER");
    assertThat(decision.authorised(), is(false));
    assertThat(decision.reason(), containsString("no purchase authority is configured for JPY"));
  }

  @Test
  @DisplayName("A role's authority in one currency grants nothing in another")
  void authorityDoesNotLeakAcrossCurrencies() {
    var l = limits("GBP:MANAGER:5000", "JPY:OWNER:UNLIMITED");
    // The manager is unlimited in nothing; the owner's JPY authority is not theirs.
    assertThat(decide("10", "JPY", l, "MANAGER").authorised(), is(false));
    // And the owner's sterling authority does not exist at all.
    assertThat(decide("10", "GBP", l, "OWNER").authorised(), is(false));
  }

  @Test
  @DisplayName("Currency and role match case-insensitively")
  void caseInsensitive() {
    assertThat(decide("10", "gbp", limits("GBP:MANAGER:5000"), "manager").authorised(), is(true));
  }

  // ── the off switch, and the edges ───────────────────────────────────────────

  @Test
  @DisplayName("No configuration at all means approval is off and any staff role may submit")
  void approvalOffByDefault() {
    assertThat(
        SpendAuthority.decide(new BigDecimal("1000000"), "GBP", Set.of("CASHIER"), Map.of())
            .authorised(),
        is(true));
    assertThat(
        SpendAuthority.decide(new BigDecimal("1000000"), "GBP", Set.of("CASHIER"), null)
            .authorised(),
        is(true));
  }

  @Test
  @DisplayName("An order with no total needs approval — an unknown figure is not a small one")
  void nullTotalNeedsApproval() {
    var decision = decide(null, "GBP", limits("GBP:MANAGER:5000"), "MANAGER");
    assertThat(decision.authorised(), is(false));
    assertThat(decision.reason(), containsString("no total"));
  }

  @Test
  @DisplayName("A zero-value order is within any ceiling, including a ceiling of zero")
  void zeroValue() {
    assertThat(decide("0", "GBP", limits("GBP:CASHIER:0"), "CASHIER").authorised(), is(true));
    assertThat(decide("0.01", "GBP", limits("GBP:CASHIER:0"), "CASHIER").authorised(), is(false));
  }

  @Test
  @DisplayName("The refusal names both figures and the currency, so it can be acted on")
  void reasonIsActionable() {
    var decision = decide("7500", "JPY", limits("JPY:MANAGER:5000"), "MANAGER");
    assertThat(decision.reason(), containsString("7500"));
    assertThat(decision.reason(), containsString("5000"));
    assertThat(decision.reason(), containsString("JPY"));
  }

  // ── the translation (03.x) ────────────────────────────────────────────────

  @Test
  @DisplayName(
      "An order in a currency with no ceiling is measured at the translated figure in the home currency, and the reason says so")
  void translatedIntoTheHomeCurrency() {
    var sterlingOnly = limits("GBP:STOREKEEPER:500", "GBP:MANAGER:5000");
    var translation =
        new SpendAuthority.Translation(new BigDecimal("3160.00"), "GBP", new BigDecimal("0.79"));
    SpendAuthority held =
        SpendAuthority.decide(
            new BigDecimal("4000.00"),
            "USD",
            java.util.List.of("STOREKEEPER"),
            sterlingOnly,
            translation);
    assertThat(held.authorised(), is(false));
    assertThat(held.role(), is("STOREKEEPER"));
    assertThat(held.ceiling(), comparesEqualTo(new BigDecimal("500")));
    assertThat(
        held.reason(), containsString("USD 4000.00 translated to GBP 3160.00 at 0.79 GBP per USD"));
    assertThat(held.translation(), is(translation));

    SpendAuthority fine =
        SpendAuthority.decide(
            new BigDecimal("500.00"),
            "USD",
            java.util.List.of("STOREKEEPER"),
            sterlingOnly,
            new SpendAuthority.Translation(
                new BigDecimal("395.00"), "GBP", new BigDecimal("0.79")));
    assertThat(fine.authorised(), is(true));
    assertThat(fine.reason(), is(nullValue()));
  }

  @Test
  @DisplayName(
      "A currency with a ceiling of its own is not translated, and without a translation an unconfigured one still fails closed")
  void ownCeilingWinsAndNoRateFailsClosed() {
    var both = limits("GBP:MANAGER:5000", "USD:MANAGER:100");
    SpendAuthority own =
        SpendAuthority.decide(
            new BigDecimal("150.00"),
            "USD",
            java.util.List.of("MANAGER"),
            both,
            new SpendAuthority.Translation(
                new BigDecimal("118.50"), "GBP", new BigDecimal("0.79")));
    assertThat(own.authorised(), is(false));
    assertThat(own.translation(), is(nullValue()));
    assertThat(own.ceiling(), comparesEqualTo(new BigDecimal("100")));

    SpendAuthority closed =
        SpendAuthority.decide(
            new BigDecimal("10.00"), "JPY", java.util.List.of("MANAGER"), both, null);
    assertThat(closed.authorised(), is(false));
    assertThat(closed.translation(), is(nullValue()));
  }
}
