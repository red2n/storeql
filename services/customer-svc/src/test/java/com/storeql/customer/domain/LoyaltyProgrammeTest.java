package com.storeql.customer.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.customer.domain.LoyaltyProgramme.Tier;
import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The loyalty programme's rules (13.x): tiers, their benefits, and when points die. */
class LoyaltyProgrammeTest {

  private static final List<Tier> TIERS =
      List.of(
          new Tier("BRONZE", new BigDecimal("0"), new BigDecimal("1")),
          new Tier("SILVER", new BigDecimal("500"), new BigDecimal("1.25")),
          new Tier("GOLD", new BigDecimal("2000"), new BigDecimal("1.5")));

  private static LoyaltyProgramme programme(Integer expiryMonths, Integer qualifyingMonths) {
    return new LoyaltyProgramme(
        Ids.newId(), expiryMonths, qualifyingMonths, TIERS, "test", null, Instant.now());
  }

  @Test
  @DisplayName("The tier is the highest threshold reached, the next one says how far to go")
  void tiersByThreshold() {
    LoyaltyProgramme p = programme(null, 12);
    assertThat(p.tierFor(new BigDecimal("0")).name(), is("BRONZE"));
    assertThat(p.tierFor(new BigDecimal("499.99")).name(), is("BRONZE"));
    assertThat(p.tierFor(new BigDecimal("500")).name(), is("SILVER"));
    assertThat(p.tierFor(new BigDecimal("9999")).name(), is("GOLD"));
    assertThat(p.nextTier(new BigDecimal("120")).orElseThrow().name(), is("SILVER"));
    assertThat(p.pointsToNextTier(new BigDecimal("120")), comparesEqualTo(new BigDecimal("380")));
    assertThat(p.nextTier(new BigDecimal("2000")).isPresent(), is(false));
    assertThat(p.pointsToNextTier(new BigDecimal("2000")), is(nullValue()));
    assertThat(p.multiplierFor("GOLD"), comparesEqualTo(new BigDecimal("1.5")));
    assertThat(p.multiplierFor("NO_SUCH_TIER"), comparesEqualTo(BigDecimal.ONE));
  }

  @Test
  @DisplayName(
      "Without a programme of its own a business has today's four tiers, no expiry and lifetime qualification")
  void theDefaultKeepsTodaysBehaviour() {
    LoyaltyProgramme d = LoyaltyProgramme.defaults(Ids.newId());
    assertThat(d.isDefault(), is(true));
    assertThat(d.expires(), is(false));
    assertThat(d.qualifyingMonths(), is(nullValue()));
    assertThat(d.tierFor(new BigDecimal("999")).name(), is("BRONZE"));
    assertThat(d.tierFor(new BigDecimal("1000")).name(), is("SILVER"));
    assertThat(d.tierFor(new BigDecimal("5000")).name(), is("GOLD"));
    assertThat(d.tierFor(new BigDecimal("20000")).name(), is("PLATINUM"));
    assertThat(d.multiplierFor("PLATINUM"), comparesEqualTo(BigDecimal.ONE));
    assertThat(d.expiryFor(Instant.now()), is(nullValue()));
  }

  @Test
  @DisplayName(
      "Points earned today die after the rule's months; a rule that changes gives a month's notice")
  void expiryAndNotice() {
    Instant earned = Instant.parse("2026-01-15T10:00:00Z");
    LoyaltyProgramme twelve = programme(12, null);
    assertThat(twelve.expires(), is(true));
    assertThat(twelve.expiryFor(earned), is(Instant.parse("2027-01-15T10:00:00Z")));
    // Earned two years ago under a new twelve-month rule: not dead tonight, dead in thirty days.
    Instant now = Instant.parse("2026-09-23T12:00:00Z");
    Instant longAgo = now.minus(730, ChronoUnit.DAYS);
    assertThat(LoyaltyProgramme.reexpiry(longAgo, 12, now), is(now.plus(30, ChronoUnit.DAYS)));
    // Earned last month: the rule's own date stands.
    Instant lastMonth = Instant.parse("2026-08-20T09:00:00Z");
    assertThat(
        LoyaltyProgramme.reexpiry(lastMonth, 12, now), is(Instant.parse("2027-08-20T09:00:00Z")));
  }

  @Test
  @DisplayName(
      "A programme is refused for a shape that cannot be honoured, and each refusal says why")
  void validation() {
    assertThat(LoyaltyProgramme.validate(12, 12, TIERS), is(nullValue()));
    assertThat(LoyaltyProgramme.validate(null, null, TIERS), is(nullValue()));
    assertThat(LoyaltyProgramme.validate(0, 12, TIERS), containsString("expiryMonths"));
    assertThat(LoyaltyProgramme.validate(121, 12, TIERS), containsString("expiryMonths"));
    assertThat(LoyaltyProgramme.validate(12, 0, TIERS), containsString("qualifyingMonths"));
    assertThat(LoyaltyProgramme.validate(12, 37, TIERS), containsString("qualifyingMonths"));
    assertThat(LoyaltyProgramme.validate(12, 12, List.of()), containsString("tier"));
    assertThat(
        LoyaltyProgramme.validate(
            12, 12, List.of(new Tier("SILVER", new BigDecimal("10"), BigDecimal.ONE))),
        containsString("first tier"));
    assertThat(
        LoyaltyProgramme.validate(
            12,
            12,
            List.of(
                new Tier("BRONZE", BigDecimal.ZERO, BigDecimal.ONE),
                new Tier("GOLD", new BigDecimal("500"), BigDecimal.ONE),
                new Tier("SILVER", new BigDecimal("400"), BigDecimal.ONE))),
        containsString("ascending"));
    assertThat(
        LoyaltyProgramme.validate(
            12,
            12,
            List.of(
                new Tier("BRONZE", BigDecimal.ZERO, BigDecimal.ONE),
                new Tier("BRONZE", new BigDecimal("500"), BigDecimal.ONE))),
        containsString("name"));
    assertThat(
        LoyaltyProgramme.validate(
            12, 12, List.of(new Tier("BRONZE", BigDecimal.ZERO, new BigDecimal("0.5")))),
        containsString("multiplier"));
    assertThat(
        LoyaltyProgramme.validate(
            12, 12, List.of(new Tier("BRONZE", BigDecimal.ZERO, new BigDecimal("10.001")))),
        containsString("multiplier"));
    assertThat(
        LoyaltyProgramme.validate(
            12, 12, List.of(new Tier("bronze tier!", BigDecimal.ZERO, BigDecimal.ONE))),
        containsString("name"));
    List<Tier> seven = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) {
      seven.add(new Tier("T" + i, BigDecimal.valueOf(i * 100L), BigDecimal.ONE));
    }
    assertThat(LoyaltyProgramme.validate(12, 12, seven), containsString("six"));
  }
}
