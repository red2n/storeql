package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.client.TenantClient;
import com.storeql.order.client.TenantClient.RatedBand;
import com.storeql.order.client.TenantClient.RatedSegment;
import com.storeql.order.client.TenantClient.RatedSeller;
import com.storeql.order.client.TenantClient.SchemeTerms;
import com.storeql.order.domain.SalesAttribution;
import com.storeql.order.domain.SalesAttribution.Statement;
import com.storeql.order.domain.SalesAttribution.StatementLine;
import com.storeql.order.repo.CommissionRepository;
import com.storeql.service.FxRates;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A statement is counted in one currency: a per-unit commission in another is translated at the
 * business's own rate, line by line, or the statement is refused by name — never added up as if it
 * were the statement's money. A band's threshold is kept as the arrangement rated it, never rounded
 * as money.
 */
class CommissionStatementServiceTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ALICE = Ids.newId();
  private static final UUID PER_UNIT = Ids.newId();
  private static final UUID PERCENT = Ids.newId();
  private static final LocalDate FROM =
      LocalDate.now(ZoneOffset.UTC).minusMonths(2).withDayOfMonth(1);
  private static final LocalDate TO = FROM.plusDays(27);

  private CommissionRepository repo;
  private TenantClient tenants;
  private TenantContext ctx;
  private CommissionStatementService svc;
  private Statement recorded;
  private String fxAnswer;
  private int fxReads;

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @BeforeEach
  void setUp() {
    repo = mock(CommissionRepository.class);
    tenants = mock(TenantClient.class);
    ctx = mock(TenantContext.class);
    svc = new CommissionStatementService();
    svc.repo = repo;
    svc.tenants = tenants;
    svc.fx =
        FxRates.forTest(
            id -> {
              fxReads++;
              return Optional.ofNullable(fxAnswer);
            },
            Clock.systemUTC());
    when(repo.standing(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    when(repo.sellerDays(eq(TENANT), any(), any(), eq(FROM), eq(TO)))
        .thenReturn(
            List.of(new SalesAttribution.SellerDay(ALICE, FROM, d("100.00"), d("150.000"))));
    lenient()
        .when(repo.record(any()))
        .thenAnswer(
            inv -> {
              recorded = inv.getArgument(0);
              return recorded;
            });
    lenient()
        .when(repo.statement(eq(TENANT), any()))
        .thenAnswer(inv -> Optional.ofNullable(recorded));
  }

  /** Alice's period, rated by tenant-svc as one stretch under {@code scheme} with these bands. */
  private void rated(UUID scheme, String sellerCurrency, RatedBand... bands) {
    BigDecimal total = BigDecimal.ZERO;
    for (RatedBand b : bands) total = total.add(b.commission());
    RatedSegment segment =
        new RatedSegment(scheme, "counter", FROM, TO, d("150.000"), List.of(bands), total);
    when(tenants.rateCommission(eq(TENANT), eq(ctx), eq(FROM), eq(TO), anyMap()))
        .thenReturn(
            Optional.of(List.of(new RatedSeller(ALICE, List.of(segment), total, sellerCurrency))));
  }

  private void schemes() {
    when(tenants.commissionSchemes(TENANT, ctx))
        .thenReturn(
            Optional.of(
                Map.of(
                    PER_UNIT, new SchemeTerms("PER_UNIT", "EUR"),
                    PERCENT, new SchemeTerms("PERCENT_OF_NET", null))));
  }

  private static RatedBand band(String threshold, String rate, String units, String commission) {
    return new RatedBand(d(threshold), d(rate), d(units), d(commission));
  }

  private Statement draft(String currency) {
    return svc.draft(TENANT, null, FROM, TO, currency, null, null, ctx, Ids.newId());
  }

  @Test
  void aPerUnitCommissionInAnotherCurrencyIsTranslatedAtTheBusinesssRateLineByLine() {
    // €0.10 a unit for the first hundred, €0.1234 above: €10.00 + €6.17 as tenant-svc rated them.
    rated(
        PER_UNIT,
        "EUR",
        band("0.000", "0.10", "100.000", "10.00"),
        band("100.000", "0.1234", "50.000", "6.17"));
    schemes();
    fxAnswer =
        "{\"data\":{\"home\":\"GBP\",\"rates\":[{\"currency\":\"EUR\",\"rate\":0.85,"
            + "\"effectiveFrom\":\"2026-01-01\"}]}}";

    Statement s = draft("GBP");

    List<StatementLine> lines = s.lines();
    assertEquals(2, lines.size());
    // €10.00 at 0.85 is £8.50; €6.17 is 5.2445, £5.24 — each line at the pound's units.
    assertEquals(d("8.50"), lines.get(0).commission());
    assertEquals(d("5.24"), lines.get(1).commission());
    assertEquals("EUR", lines.get(0).rateCurrency());
    assertEquals(d("10.00"), lines.get(0).ratedCommission());
    assertEquals(d("6.17"), lines.get(1).ratedCommission());
    // The statement's commission is its lines added up, in pounds — never €16.17 read as pounds.
    assertEquals(d("13.74"), s.commission());
    assertEquals(1, fxReads, "the rates are read once per statement");
  }

  @Test
  void aDinarAndAYenStatementAreTranslatedToTheirOwnUnits() {
    rated(PER_UNIT, "EUR", band("0.000", "0.10", "150.000", "15.00"));
    schemes();
    // At home in dinars, a euro at 0.3312: €15.00 is KWD 4.968.
    fxAnswer = "{\"data\":{\"home\":\"KWD\",\"rates\":[{\"currency\":\"EUR\",\"rate\":0.3312}]}}";
    Statement kwd = draft("KWD");
    assertEquals(d("4.968"), kwd.lines().get(0).commission());
    assertEquals(d("4.968"), kwd.commission());
  }

  @Test
  void aYenStatementIsWholeYen() {
    rated(PER_UNIT, "EUR", band("0.000", "0.10", "150.000", "15.00"));
    schemes();
    // At home in yen, a euro at 161.37: €15.00 is 2420.55, ¥2,421.
    fxAnswer = "{\"data\":{\"home\":\"JPY\",\"rates\":[{\"currency\":\"EUR\",\"rate\":161.37}]}}";
    Statement jpy = draft("JPY");
    assertEquals(d("2421"), jpy.lines().get(0).commission());
    assertEquals(d("2421"), jpy.commission());
  }

  @Test
  void withoutARateTheStatementIsRefusedByNameAndNothingIsWritten() {
    rated(PER_UNIT, "EUR", band("0.000", "0.10", "150.000", "15.00"));
    schemes();
    fxAnswer = "{\"data\":{\"home\":\"GBP\",\"rates\":[]}}";

    ApiException e = assertThrows(ApiException.class, () -> draft("GBP"));
    assertEquals(409, e.status());
    assertEquals("COMMISSION_FX_RATE_MISSING", e.code());
    assertEquals(List.of("sellerUserId: " + ALICE), e.details());
    verify(repo, never()).record(any());
  }

  @Test
  void ratesThatCannotBeReadRefuseRatherThanGuess() {
    rated(PER_UNIT, "EUR", band("0.000", "0.10", "150.000", "15.00"));
    schemes();
    fxAnswer = null;

    ApiException e = assertThrows(ApiException.class, () -> draft("GBP"));
    assertEquals(503, e.status());
    assertEquals("COMMISSION_FX_UNAVAILABLE", e.code());
    verify(repo, never()).record(any());
  }

  @Test
  void arrangementsThatCannotBeReadRefuseWhenAPerUnitSchemeIsInPlay() {
    rated(PER_UNIT, "EUR", band("0.000", "0.10", "150.000", "15.00"));
    when(tenants.commissionSchemes(TENANT, ctx)).thenReturn(Optional.empty());

    ApiException e = assertThrows(ApiException.class, () -> draft("GBP"));
    assertEquals(503, e.status());
    assertEquals("COMMISSION_RATES_UNAVAILABLE", e.code());
    verify(repo, never()).record(any());
  }

  @Test
  void aStretchUnderAnArrangementTheListDoesNotHoldIsNotGuessedAt() {
    rated(Ids.newId(), "EUR", band("0.000", "0.10", "150.000", "15.00"));
    schemes();

    ApiException e = assertThrows(ApiException.class, () -> draft("GBP"));
    assertEquals(503, e.status());
    assertEquals("COMMISSION_RATES_UNAVAILABLE", e.code());
    verify(repo, never()).record(any());
  }

  /**
   * A per-unit arrangement in the statement's own currency is stated as rated, its rate's currency
   * named — and its thresholds are counts of units, kept as rated: a band written before whole
   * units were required (2.125) stays 2.125 on a yen statement, and 100 is never 100 yen.
   */
  @Test
  void aPerUnitThresholdIsKeptAsRatedNeverRoundedAsMoney() {
    rated(
        PER_UNIT,
        "JPY",
        band("0.000", "10", "2.125", "21"),
        band("2.125", "12", "97.875", "1175"),
        band("100.000", "15", "50.000", "750"));
    when(tenants.commissionSchemes(TENANT, ctx))
        .thenReturn(Optional.of(Map.of(PER_UNIT, new SchemeTerms("PER_UNIT", "JPY"))));

    Statement s = draft("JPY");

    assertEquals(d("2.125"), s.lines().get(1).thresholdFrom());
    assertEquals(d("100.000"), s.lines().get(2).thresholdFrom());
    assertEquals("JPY", s.lines().get(0).rateCurrency());
    assertNull(s.lines().get(0).ratedCommission());
    assertEquals(d("1946"), s.commission());
    assertEquals(0, fxReads, "nothing to translate, no rates read");
  }

  /** A business on percentages alone is rated as before: no scheme list read, no rates. */
  @Test
  void aPercentageIsTheStatementsOwnMoneyAndReadsNothingMore() {
    rated(PERCENT, null, band("0.00", "2", "100.00", "2.00"));

    Statement s = draft("GBP");

    assertEquals(d("2.00"), s.commission());
    assertNull(s.lines().get(0).rateCurrency());
    assertNull(s.lines().get(0).ratedCommission());
    verify(tenants, never()).commissionSchemes(any(), any());
    assertEquals(0, fxReads);
  }
}
