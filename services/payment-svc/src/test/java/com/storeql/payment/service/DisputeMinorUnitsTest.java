package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.dto.DisputeDtos.RecordDisputeRequest;
import com.storeql.payment.repo.DisputeRepository;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A dispute recorded from the acquirer's letter is money in the dispute's currency: the amount and
 * the fee are no finer than its minor unit — whole yen, a dinar's three places — and a finer one is
 * refused before anything is opened.
 */
class DisputeMinorUnitsTest {

  private final UUID tenant = Ids.newId();
  private final UUID paymentId = Ids.newId();

  private DisputeService service(String home, String tendered, DisputeRepository repo) {
    DisputeService svc = new DisputeService();
    svc.repo = repo;
    svc.payments = mock(PaymentRepository.class);
    when(svc.payments.findTender(tenant, paymentId))
        .thenReturn(
            Optional.of(
                new PaymentTender(
                    paymentId,
                    tenant,
                    Ids.newId(),
                    new BigDecimal(tendered),
                    "CARD",
                    null,
                    null,
                    "CAPTURED",
                    null,
                    Instant.now(),
                    Ids.newId())));
    svc.profiles =
        TenantProfiles.forTest(
            id ->
                Optional.of(
                    "{\"data\":{\"currency\":\""
                        + home
                        + "\",\"country\":\"GB\",\"mode\":\"LIVE\"}}"),
            Clock.systemUTC());
    return svc;
  }

  private static DisputeRepository opening() {
    DisputeRepository repo = mock(DisputeRepository.class);
    when(repo.open(any(), any(), any()))
        .thenAnswer(
            inv ->
                new DisputeRepository.OpenResult(
                    DisputeRepository.Opened.CREATED, inv.getArgument(0)));
    return repo;
  }

  private RecordDisputeRequest req(String amount, String fee) {
    return new RecordDisputeRequest(
        paymentId,
        amount == null ? null : new BigDecimal(amount),
        fee == null ? null : new BigDecimal(fee),
        null,
        "FRAUDULENT",
        "10.4",
        "CASE-1",
        Instant.now().plus(10, ChronoUnit.DAYS),
        true);
  }

  @Test
  @DisplayName("A dinar dispute keeps three places; a fourth, in the amount or the fee, is refused")
  void dinars() {
    DisputeRepository repo = opening();
    var d = service("KWD", "10.000", repo).record(tenant, Ids.newId(), req("2.125", "1.500"), null);
    assertEquals(0, d.amount().compareTo(new BigDecimal("2.125")));

    for (String[] bad : new String[][] {{"2.1255", null}, {"2.125", "1.5005"}}) {
      DisputeRepository none = opening();
      ApiException e =
          assertThrows(
              ApiException.class,
              () ->
                  service("KWD", "10.000", none)
                      .record(tenant, Ids.newId(), req(bad[0], bad[1]), null));
      assertEquals("DISPUTE_AMOUNT_INVALID", e.code());
      verify(none, never()).open(any(), any(), any());
    }
  }

  @Test
  @DisplayName("A yen dispute is whole yen; half a yen is refused; pounds keep two places")
  void yenAndPounds() {
    DisputeRepository repo = opening();
    service("JPY", "5000", repo).record(tenant, Ids.newId(), req("5000", "1500"), null);
    verify(repo).open(any(), any(), any());

    DisputeRepository none = opening();
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                service("JPY", "5000", none)
                    .record(tenant, Ids.newId(), req("2500.5", null), null));
    assertEquals("DISPUTE_AMOUNT_INVALID", e.code());

    DisputeRepository gbp = opening();
    ApiException p =
        assertThrows(
            ApiException.class,
            () ->
                service("GBP", "50.00", gbp)
                    .record(tenant, Ids.newId(), req("10.005", null), null));
    assertEquals("DISPUTE_AMOUNT_INVALID", p.code());
    verify(gbp, never()).open(any(), any(), any());
  }
}
