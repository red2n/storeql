package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.client.CustomerClient;
import com.storeql.payment.client.TenantStoreClient;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.dto.Dtos.RecordRefundRequest;
import com.storeql.payment.dto.Dtos.RecordTenderRequest;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.Jurisdictions;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Money a till sends with a tender, and a refund a person types, in the business's own currency.
 *
 * <p>A tender is taken at its currency's own minor units — whole yen, three places of a dinar, two
 * of a pound — rounded half up as order-svc rounds a sale's lines. The till adds a sale up in
 * binary floating point (three items at 1.10 come to 3.3000000000000003, a weighed 0.375 kg at
 * 12.99 to 4.87125), and refusing that figure stopped an ordinary cash sale with change at the
 * till; only a tender that comes to nothing at the currency's units is refused. A refund is typed
 * by a person in the back office and is refused when finer than the currency, never rounded. When
 * the business's currency cannot be read a tender is not stopped: it is kept at the columns' four
 * places.
 */
class TenderMinorUnitsTest {

  private final UUID tenant = Ids.newId();
  private final UUID order = Ids.newId();

  private static TenantProfiles homeIn(String currency) {
    return TenantProfiles.forTest(
        id ->
            Optional.of(
                "{\"data\":{\"currency\":\""
                    + currency
                    + "\",\"country\":\"GB\",\"mode\":\"LIVE\"}}"),
        Clock.systemUTC());
  }

  private static TenantProfiles unreadable() {
    return TenantProfiles.forTest(id -> Optional.empty(), Clock.systemUTC());
  }

  private static TenantContext staff(UUID tenantId) {
    TenantContext ctx = mock(TenantContext.class);
    when(ctx.requireTenantId()).thenReturn(tenantId);
    return ctx;
  }

  private PaymentService service(TenantProfiles profiles, PaymentRepository repo) {
    PaymentService svc = new PaymentService();
    svc.profiles = profiles;
    svc.repo = repo;
    svc.storeClient = mock(TenantStoreClient.class);
    when(svc.storeClient.enabledMethods(any(), any())).thenReturn(Optional.empty());
    svc.customerClient = mock(CustomerClient.class);
    Jurisdictions none = mock(Jurisdictions.class);
    when(none.cashLimit(any(), any(), any(), any())).thenReturn(Optional.empty());
    svc.jurisdictions = none;
    // A till's card rule: no card machine at the store, no standalone permission, no order to ask.
    svc.terminalRepo = mock(com.storeql.payment.repo.TerminalRepository.class);
    svc.cardSettings = mock(com.storeql.payment.repo.CardSettingsRepository.class);
    svc.orderClient = mock(com.storeql.payment.client.OrderClient.class);
    return svc;
  }

  private static PaymentRepository recording() {
    PaymentRepository repo = mock(PaymentRepository.class);
    when(repo.createTender(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(repo.createTender(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(repo.createRefundGuarded(any(), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(repo.findTendersByOrder(any(), any())).thenReturn(List.of());
    return repo;
  }

  private RecordTenderRequest tender(String amount, String method) {
    return tender(new BigDecimal(amount), method, null, null);
  }

  private RecordTenderRequest tender(
      BigDecimal amount, String method, String customerId, String currency) {
    return new RecordTenderRequest(
        order.toString(),
        amount,
        method,
        "CARD".equals(method) ? "AUTH 1" : null,
        null,
        null,
        null,
        customerId,
        currency,
        null,
        null,
        null);
  }

  /** What a Dart double is written as on the wire: its shortest round-trip decimal. */
  private static BigDecimal onTheWire(double tillFigure) {
    return new BigDecimal(Double.toString(tillFigure));
  }

  private RecordRefundRequest refund(String amount) {
    return new RecordRefundRequest(
        Ids.newId().toString(), new BigDecimal(amount), "CARD", null, null, "faulty", null);
  }

  /** Records the tender and answers what was written: the row, and the event's amount. */
  private PaymentTender taken(
      TenantProfiles profiles, RecordTenderRequest req, String expected, String eventAmount) {
    PaymentRepository repo = recording();
    PaymentTender answered =
        service(profiles, repo).recordTender(req, staff(tenant), Ids.newId().toString());
    ArgumentCaptor<PaymentTender> row = ArgumentCaptor.forClass(PaymentTender.class);
    ArgumentCaptor<OutboxRow> event = ArgumentCaptor.forClass(OutboxRow.class);
    if ("CARD".equals(req.method())) {
      verify(repo).createTender(row.capture(), event.capture(), eq("STANDALONE"));
    } else {
      verify(repo).createTender(row.capture(), event.capture());
    }
    // Exactly the currency's units, scale and all: what is stored, answered and announced agree.
    assertEquals(new BigDecimal(expected), row.getValue().amount(), req.amount().toPlainString());
    assertEquals(new BigDecimal(expected), answered.amount());
    assertTrue(
        event.getValue().payload().contains("\"amount\":" + eventAmount + ","),
        event.getValue().payload());
    return answered;
  }

  private void refusedTender(TenantProfiles profiles, String amount) {
    PaymentRepository repo = recording();
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                service(profiles, repo)
                    .recordTender(tender(amount, "CASH"), staff(tenant), Ids.newId().toString()));
    assertEquals(400, e.status(), amount);
    assertEquals("PAYMENT_AMOUNT_INVALID", e.code(), amount);
    verify(repo, never()).createTender(any(), any());
  }

  @Test
  @DisplayName(
      "Three items at 1.10 paid with a 5.00 note: the till's 3.3000000000000003 is taken as 3.30")
  void aCashSaleWithChangeIsTaken() {
    BigDecimal sent = onTheWire(3 * 1.10);
    assertEquals("3.3000000000000003", sent.toPlainString(), "the till's own figure");
    taken(homeIn("GBP"), tender(sent, "CASH", null, null), "3.30", "3.30");
    // The back office's "collect outstanding": 25.99 less 10.00 already paid.
    taken(homeIn("GBP"), tender(onTheWire(25.99 - 10), "CASH", null, null), "15.99", "15.99");
    // A weighed line the till never rounds: 0.375 kg at 12.99 is 4.87125, the line order-svc
    // totals half up to 4.87.
    taken(homeIn("GBP"), tender(onTheWire(0.375 * 12.99), "CASH", null, null), "4.87", "4.87");
  }

  @Test
  @DisplayName("The till's figure passes the request's own checks: no 400 for its binary digits")
  void theTillsFigureIsAValidBody() {
    for (double figure : new double[] {3 * 1.10, 25.99 - 10, 0.375 * 12.99, 0.1 + 0.2}) {
      Validations.validate(tender(onTheWire(figure), "CASH", null, null));
    }
    // The body keeps a sanity bound: the columns' ten whole digits.
    ApiException tooBig =
        assertThrows(ApiException.class, () -> Validations.validate(tender("10000000000", "CASH")));
    assertEquals("VALIDATION_FAILED", tooBig.code());
  }

  @Test
  @DisplayName(
      "Every double a till can send for a real amount passes the body's bound on decimal places")
  void theBoundOnPlacesTakesEveryTillDouble() {
    // Seventeen significant digits from the fourth place, the finest minor unit ISO 4217 has
    // (CLF): twenty places, which is the most a till's double for a real amount is written with.
    for (double figure : new double[] {0.0012345678901234567, 0.30000000000000004, 1234.5678}) {
      Validations.validate(tender(onTheWire(figure), "CASH", null, null));
    }
    assertEquals(20, onTheWire(0.00012345678901234567).scale(), "the longest one");
    Validations.validate(tender(onTheWire(0.00012345678901234567), "CASH", null, null));
    // A KWD tender of the most finely written till double still comes to its currency's units.
    taken(
        homeIn("KWD"),
        tender(onTheWire(0.0012345678901234567), "CASH", null, null),
        "0.001",
        "0.001");
  }

  @Test
  @DisplayName(
      "A tiny amount with a huge exponent (1E-80000000, twelve characters) is a fast 400 at the"
          + " body, and the service never builds its power of ten either")
  void aHugeExponentIsAFast400() {
    BigDecimal tiny = new BigDecimal("1E-80000000");
    assertEquals(80_000_000, tiny.scale(), "what Yasson binds the twelve characters to");
    ApiException atTheBody =
        assertTimeoutPreemptively(
            Duration.ofSeconds(2),
            () ->
                assertThrows(
                    ApiException.class,
                    () -> Validations.validate(tender(tiny, "CASH", null, null))));
    assertEquals(400, atTheBody.status());
    assertEquals("VALIDATION_FAILED", atTheBody.code());
    // Twenty-one places is past the bound too, though nowhere near as costly.
    assertThrows(
        ApiException.class, () -> Validations.validate(tender("0.000000000000000000001", "CASH")));
    // Reached by any other road, the service refuses it as nothing without rounding it the long
    // way round: pounds, dinars, yen, and the four places kept when the currency is unreadable.
    for (TenantProfiles profiles :
        new TenantProfiles[] {homeIn("GBP"), homeIn("KWD"), homeIn("JPY"), unreadable()}) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2), () -> refusedTender(profiles, "1E-80000000"));
    }
  }

  @Test
  @DisplayName("A dinar tender keeps its three places; the till's binary digits round to them")
  void dinars() {
    taken(homeIn("KWD"), tender("10.125", "CASH"), "10.125", "10.125");
    // Three items at 1.100: 3.3000000000000003 on the wire, 3.300 dinars; a weighed 0.375 kg at
    // 1.299 is 0.487125, the line order-svc totals to 0.487.
    taken(homeIn("KWD"), tender(onTheWire(3 * 1.10), "CASH", null, null), "3.300", "3.300");
    taken(homeIn("KWD"), tender(onTheWire(0.375 * 1.299), "CASH", null, null), "0.487", "0.487");
    taken(homeIn("KWD"), tender("10.1255", "CASH"), "10.126", "10.126");
    // Five fils is a tender a dinar has, and is taken.
    taken(homeIn("KWD"), tender("0.005", "CASH"), "0.005", "0.005");
  }

  @Test
  @DisplayName("A yen tender is whole: the till's figure rounds to the yen")
  void yen() {
    taken(homeIn("JPY"), tender("1000", "CASH"), "1000", "1000");
    taken(homeIn("JPY"), tender("1000.00", "CASH"), "1000", "1000");
    // A weighed 0.375 kg at 1299 yen is 487.125: the line order-svc totals to 487.
    taken(homeIn("JPY"), tender(onTheWire(0.375 * 1299), "CASH", null, null), "487", "487");
    taken(homeIn("JPY"), tender("1000.5", "CASH"), "1001", "1001");
  }

  @Test
  @DisplayName("A pound tender keeps two places; a third rounds half up, as a sale's lines do")
  void pounds() {
    taken(homeIn("GBP"), tender("10.50", "CASH"), "10.50", "10.50");
    taken(homeIn("GBP"), tender("10.005", "CASH"), "10.01", "10.01");
    taken(homeIn("GBP"), tender("10.004", "CASH"), "10.00", "10.00");
  }

  @Test
  @DisplayName("A tender that comes to nothing at the currency's units is refused, nothing written")
  void aTenderOfNothingIsRefused() {
    refusedTender(homeIn("GBP"), "0.004");
    refusedTender(homeIn("JPY"), "0.4");
    refusedTender(homeIn("KWD"), "0.0004");
  }

  @Test
  @DisplayName("A currency the tender names decides its minor units")
  void aNamedCurrencyDecides() {
    taken(homeIn("GBP"), tender(new BigDecimal("1.125"), "CASH", null, "KWD"), "1.125", "1.125");
    taken(homeIn("KWD"), tender(new BigDecimal("1.125"), "CASH", null, "GBP"), "1.13", "1.13");
  }

  @Test
  @DisplayName(
      "A store-credit tender clamped to the till's remaining balance redeems the currency's units")
  void storeCreditRedeemsTheCurrencysUnits() {
    UUID customer = Ids.newId();
    for (String[] c :
        new String[][] {
          {"GBP", Double.toString(3 * 1.10), "3.30"},
          {"KWD", Double.toString(0.1 + 0.2), "0.300"},
          {"JPY", Double.toString(0.375 * 1299), "487"}
        }) {
      PaymentRepository repo = recording();
      PaymentService svc = service(homeIn(c[0]), repo);
      svc.recordTender(
          tender(new BigDecimal(c[1]), "STORE_CREDIT", customer.toString(), c[0]),
          staff(tenant),
          Ids.newId().toString());
      // customer-svc holds credit at the currency's units and refuses anything finer.
      verify(svc.customerClient)
          .redeemStoreCredit(tenant, customer, new BigDecimal(c[2]), c[0], order);
      ArgumentCaptor<PaymentTender> row = ArgumentCaptor.forClass(PaymentTender.class);
      verify(repo).createTender(row.capture(), any());
      assertEquals(new BigDecimal(c[2]), row.getValue().amount(), c[0]);
    }
  }

  @Test
  @DisplayName(
      "When the business's currency cannot be read, a tender is not stopped: four places are kept")
  void anUnreadableCurrencyFailsOpen() {
    // (A card: cash asks the business's currency of its own, for the law's cash limit.)
    taken(unreadable(), tender("10.125", "CARD"), "10.1250", "10.1250");
    taken(unreadable(), tender(onTheWire(3 * 1.10), "CARD", null, null), "3.3000", "3.3000");
  }

  @Test
  @DisplayName("A refund is no finer than the currency either: KWD and JPY, with nothing written")
  void refunds() {
    PaymentRepository kwd = recording();
    service(homeIn("KWD"), kwd).recordRefund(staff(tenant), order, refund("2.125"), null);
    verify(kwd).createRefundGuarded(any(), any(), any(), any());

    PaymentRepository jpy = recording();
    service(homeIn("JPY"), jpy).recordRefund(staff(tenant), order, refund("500"), null);
    verify(jpy).createRefundGuarded(any(), any(), any(), any());

    for (String[] bad : new String[][] {{"KWD", "2.1255"}, {"JPY", "500.5"}, {"GBP", "2.125"}}) {
      PaymentRepository repo = recording();
      ApiException e =
          assertThrows(
              ApiException.class,
              () ->
                  service(homeIn(bad[0]), repo)
                      .recordRefund(staff(tenant), order, refund(bad[1]), null));
      assertEquals("PAYMENT_AMOUNT_INVALID", e.code(), bad[0] + " " + bad[1]);
      verify(repo, never()).createRefundGuarded(any(), any(), any(), any());
    }
  }

  @Test
  @DisplayName("The cash-limit refusal says the figures at the currency's own units")
  void theCashLimitSaysItInTheCurrencysUnits() {
    PaymentRepository repo = recording();
    PaymentService svc = service(homeIn("JPY"), repo);
    Jurisdictions limit = mock(Jurisdictions.class);
    when(limit.cashLimit(eq(tenant), any(), eq("JPY"), any()))
        .thenReturn(
            Optional.of(
                new Jurisdictions.CashLimit(
                    "COUNTRY",
                    "JPY",
                    new BigDecimal("1000000"),
                    LocalDate.of(2020, 1, 1),
                    null,
                    "a cash law")));
    svc.jurisdictions = limit;
    when(repo.findTendersByOrder(tenant, order))
        .thenReturn(
            List.of(
                new PaymentTender(
                    Ids.newId(),
                    tenant,
                    order,
                    new BigDecimal("600000.0000"),
                    "CASH",
                    null,
                    null,
                    "CAPTURED",
                    null,
                    Instant.now(),
                    null)));
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordTender(tender("500000", "CASH"), staff(tenant), Ids.newId().toString()));
    assertEquals("PAYMENT_CASH_LIMIT_EXCEEDED", e.code());
    assertTrue(
        e.getMessage().startsWith("cash for this sale would come to JPY 1100000 (600000 already"),
        e.getMessage());
  }
}
