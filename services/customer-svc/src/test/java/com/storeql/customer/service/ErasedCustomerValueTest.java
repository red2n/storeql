package com.storeql.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.StoreCreditAccount;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.customer.dto.Dtos.IssueStoreCreditRequest;
import com.storeql.customer.dto.Dtos.RedeemPointsRequest;
import com.storeql.customer.dto.Dtos.RedeemStoreCreditRequest;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.customer.repo.LoyaltyProgrammeRepository;
import com.storeql.ids.Ids;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Value goes to a person, and an erased customer is no longer one: store credit issued by hand is
 * refused {@code 409 CUSTOMER_ANONYMIZED} before anything is written, as a manual award of points
 * is. Spending is judged on the write's own transaction, because a spend recorded before the
 * erasure must still answer its retry (the tender it paid is recorded from that answer) while any
 * new spend is refused there — so the service hands a redemption to the repository unjudged and
 * passes its refusal on unchanged.
 */
@ExtendWith(MockitoExtension.class)
class ErasedCustomerValueTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID MANAGER = Ids.newId();

  @Mock CustomerRepository repo;
  @Mock LoyaltyProgrammeRepository programmes;
  private CustomerService service;

  @BeforeEach
  void setUp() {
    service = new CustomerService();
    service.repo = repo;
    service.programmes = programmes;
    service.profiles =
        TenantProfiles.forTest(
            id ->
                Optional.of(
                    "{\"data\":{\"currency\":\"GBP\",\"country\":\"GB\",\"mode\":\"LIVE\"}}"),
            Clock.systemUTC());
    Mockito.lenient()
        .when(programmes.programme(any()))
        .thenAnswer(inv -> LoyaltyProgramme.defaults(inv.getArgument(0)));
  }

  private void customerIs(String status) {
    when(repo.findById(eq(TENANT), eq(CUSTOMER)))
        .thenReturn(
            Optional.of(
                new Customer(
                    CUSTOMER,
                    TENANT,
                    null,
                    "anon-" + CUSTOMER + "@deleted",
                    null,
                    "Deleted",
                    "User",
                    null,
                    null,
                    status,
                    null,
                    Customer.STATUS_ANONYMIZED.equals(status) ? Instant.now() : null,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null)));
  }

  private static StoreCreditAccount account() {
    return new StoreCreditAccount(
        Ids.newId(), TENANT, CUSTOMER, BigDecimal.TEN, "GBP", Instant.now(), Instant.now());
  }

  private void issue(String key) {
    service.issueStoreCredit(
        TENANT,
        CUSTOMER,
        new IssueStoreCreditRequest(new BigDecimal("15.00"), null, null, "damaged goods"),
        MANAGER,
        key);
  }

  @Test
  @DisplayName("Store credit issued by hand to an erased customer is refused 409; nothing written")
  void issuingToAnErasedCustomerIsRefused() {
    customerIs(Customer.STATUS_ANONYMIZED);

    ApiException e = assertThrows(ApiException.class, () -> issue(Ids.newId().toString()));

    assertEquals(409, e.status());
    assertEquals("CUSTOMER_ANONYMIZED", e.code());
    verify(repo, never()).issueStoreCredit(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("The erased refusal comes before the amount is judged: one answer for the record")
  void theErasedRefusalComesFirst() {
    customerIs(Customer.STATUS_ANONYMIZED);

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                service.issueStoreCredit(
                    TENANT,
                    CUSTOMER,
                    new IssueStoreCreditRequest(new BigDecimal("10.005"), null, null, "goodwill"),
                    MANAGER,
                    Ids.newId().toString()));

    assertEquals("CUSTOMER_ANONYMIZED", e.code());
  }

  @Test
  @DisplayName("A customer who is not erased is still issued store credit by hand")
  void aLiveCustomerIsStillIssued() {
    customerIs(Customer.STATUS_ACTIVE);
    when(repo.issueStoreCredit(any(), any(), any(), anyString(), any(), any(), any(), any()))
        .thenReturn(account());

    issue(Ids.newId().toString());

    verify(repo)
        .issueStoreCredit(
            eq(TENANT),
            eq(CUSTOMER),
            eq(new BigDecimal("15.00")),
            eq("GBP"),
            any(),
            eq("damaged goods"),
            any(),
            any());
  }

  @Test
  @DisplayName(
      "A store-credit spend for an erased customer reaches the transaction, and its refusal is"
          + " passed on unchanged")
  void aStoreCreditSpendIsJudgedOnItsTransaction() {
    customerIs(Customer.STATUS_ANONYMIZED);
    ApiException refused = CustomerRepository.erasedForSpending();
    when(repo.redeemStoreCredit(any(), any(), any(), anyString(), any(), any(), any()))
        .thenThrow(refused);

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                service.redeemStoreCredit(
                    TENANT,
                    CUSTOMER,
                    new RedeemStoreCreditRequest(
                        new BigDecimal("5.00"), null, Ids.newId().toString(), "tender")));

    assertSame(refused, e);
    assertEquals(409, e.status());
    assertEquals("CUSTOMER_ANONYMIZED", e.code());
  }

  @Test
  @DisplayName(
      "A replayed store-credit spend for an erased customer answers as the repository does")
  void aReplayedStoreCreditSpendAnswers() {
    customerIs(Customer.STATUS_ANONYMIZED);
    StoreCreditAccount asItStands = account();
    when(repo.redeemStoreCredit(any(), any(), any(), anyString(), any(), any(), any()))
        .thenReturn(asItStands);

    assertSame(
        asItStands,
        service.redeemStoreCredit(
            TENANT,
            CUSTOMER,
            new RedeemStoreCreditRequest(
                new BigDecimal("5.00"), null, Ids.newId().toString(), "tender")));
  }

  @Test
  @DisplayName(
      "A points spend for an erased customer reaches the transaction, and its refusal is passed"
          + " on unchanged")
  void aPointsSpendIsJudgedOnItsTransaction() {
    customerIs(Customer.STATUS_ANONYMIZED);
    ApiException refused = CustomerRepository.erasedForSpending();
    when(repo.redeemPoints(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(refused);

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                service.redeemPoints(
                    TENANT,
                    CUSTOMER,
                    new RedeemPointsRequest(
                        new BigDecimal("10"), Ids.newId().toString(), "discount"),
                    Ids.newId(),
                    null));

    assertSame(refused, e);
  }

  @Test
  @DisplayName("The refusals name one state with one code, whatever was attempted")
  void oneStateOneCode() {
    for (ApiException e :
        new ApiException[] {
          CustomerRepository.erasedForManualPoints(),
          CustomerRepository.erasedForStoreCredit(),
          CustomerRepository.erasedForSpending()
        }) {
      assertEquals(409, e.status());
      assertEquals("CUSTOMER_ANONYMIZED", e.code());
    }
  }
}
