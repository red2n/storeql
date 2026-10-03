package com.storeql.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.StoreCreditAccount;
import com.storeql.customer.dto.Dtos.IssueStoreCreditRequest;
import com.storeql.customer.dto.Dtos.RedeemStoreCreditRequest;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.ids.Ids;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Store credit is money in its account's currency, so an amount issued or redeemed is no finer than
 * that currency's minor unit: whole yen, a dinar's three places, a pound's two. A finer one is
 * refused before anything is written, never rounded — the DTO cannot know the currency, so the
 * service judges it, the DTO keeping only a four-place sanity bound.
 */
class StoreCreditMinorUnitsTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();

  private static CustomerRepository repo() {
    CustomerRepository repo = mock(CustomerRepository.class);
    when(repo.findById(eq(TENANT), eq(CUSTOMER)))
        .thenReturn(
            Optional.of(
                new Customer(
                    CUSTOMER,
                    TENANT,
                    null,
                    "a@example.com",
                    null,
                    "A",
                    "B",
                    null,
                    null,
                    Customer.STATUS_ACTIVE,
                    null,
                    null,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null)));
    StoreCreditAccount account =
        new StoreCreditAccount(
            Ids.newId(), TENANT, CUSTOMER, BigDecimal.TEN, "GBP", Instant.now(), Instant.now());
    when(repo.issueStoreCredit(any(), any(), any(), anyString(), any(), any(), any(), any()))
        .thenReturn(account);
    when(repo.redeemStoreCredit(any(), any(), any(), anyString(), any(), any(), any()))
        .thenReturn(account);
    return repo;
  }

  private static CustomerService service(String home, CustomerRepository repo) {
    CustomerService svc = new CustomerService();
    svc.repo = repo;
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

  private static void issue(CustomerService svc, String amount, String currency) {
    svc.issueStoreCredit(
        TENANT,
        CUSTOMER,
        new IssueStoreCreditRequest(new BigDecimal(amount), currency, null, "goodwill"),
        Ids.newId(),
        Ids.newId().toString());
  }

  private static void redeem(CustomerService svc, String amount, String currency) {
    svc.redeemStoreCredit(
        TENANT,
        CUSTOMER,
        new RedeemStoreCreditRequest(
            new BigDecimal(amount), currency, Ids.newId().toString(), null));
  }

  @Test
  @DisplayName("Issued: a dinar's third place stands, whole yen stand, pounds keep two")
  void issuedAtTheCurrencysUnits() {
    CustomerRepository kwd = repo();
    issue(service("KWD", kwd), "1.125", null);
    verify(kwd)
        .issueStoreCredit(
            any(), any(), eq(new BigDecimal("1.125")), eq("KWD"), any(), any(), any(), any());

    CustomerRepository jpy = repo();
    issue(service("JPY", jpy), "500", null);
    verify(jpy).issueStoreCredit(any(), any(), any(), eq("JPY"), any(), any(), any(), any());

    CustomerRepository named = repo();
    issue(service("GBP", named), "1.125", "KWD");
    verify(named).issueStoreCredit(any(), any(), any(), eq("KWD"), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "Issued finer than the currency: refused with STORE_CREDIT_AMOUNT_INVALID, nothing written")
  void issuedTooFineIsRefused() {
    for (String[] bad : new String[][] {{"KWD", "1.1255"}, {"JPY", "500.5"}, {"GBP", "10.005"}}) {
      CustomerRepository repo = repo();
      ApiException e =
          assertThrows(ApiException.class, () -> issue(service(bad[0], repo), bad[1], null));
      assertEquals(400, e.status(), bad[0]);
      assertEquals("STORE_CREDIT_AMOUNT_INVALID", e.code(), bad[0]);
      verify(repo, never())
          .issueStoreCredit(any(), any(), any(), any(), any(), any(), any(), any());
    }
  }

  @Test
  @DisplayName(
      "Redeemed: KWD 0.125 and whole yen are spent; half a yen or a fourth fils is refused")
  void redeemedAtTheCurrencysUnits() {
    CustomerRepository kwd = repo();
    redeem(service("KWD", kwd), "0.125", null);
    verify(kwd).redeemStoreCredit(any(), any(), any(), eq("KWD"), any(), any(), any());

    CustomerRepository jpy = repo();
    redeem(service("JPY", jpy), "300", null);
    verify(jpy).redeemStoreCredit(any(), any(), any(), eq("JPY"), any(), any(), any());

    for (String[] bad : new String[][] {{"KWD", "0.1255"}, {"JPY", "0.5"}, {"GBP", "0.005"}}) {
      CustomerRepository repo = repo();
      ApiException e =
          assertThrows(ApiException.class, () -> redeem(service(bad[0], repo), bad[1], null));
      assertEquals("STORE_CREDIT_AMOUNT_INVALID", e.code(), bad[0]);
      verify(repo, never()).redeemStoreCredit(any(), any(), any(), any(), any(), any(), any());
    }
  }
}
