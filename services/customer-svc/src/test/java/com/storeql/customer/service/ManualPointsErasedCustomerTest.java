package com.storeql.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.customer.dto.Dtos.AdjustPointsRequest;
import com.storeql.customer.dto.Dtos.EarnPointsRequest;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.customer.repo.LoyaltyProgrammeRepository;
import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Points awarded or corrected by hand go to a person; an erased customer is no longer one this
 * business may hold anything for, so management's manual earn and adjust are refused with the code
 * customer-svc already answers every write to an erased record with — before anything is written.
 */
@ExtendWith(MockitoExtension.class)
class ManualPointsErasedCustomerTest {

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
    org.mockito.Mockito.lenient()
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

  private static void assertErased(ApiException e) {
    assertEquals(409, e.status());
    assertEquals("CUSTOMER_ANONYMIZED", e.code());
  }

  @Test
  @DisplayName("A manual earn for an erased customer is refused 409 and nothing is written")
  void aManualEarnForAnErasedCustomerIsRefused() {
    customerIs(Customer.STATUS_ANONYMIZED);

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                service.earnPoints(
                    TENANT,
                    CUSTOMER,
                    new EarnPointsRequest(new BigDecimal("40"), null, "welcome"),
                    MANAGER,
                    Ids.newId().toString()));

    assertErased(e);
    verify(repo, never())
        .earnPoints(any(), any(), any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("A manual adjustment, up or down, for an erased customer is refused 409; none runs")
  void aManualAdjustmentForAnErasedCustomerIsRefused() {
    customerIs(Customer.STATUS_ANONYMIZED);

    for (String points : new String[] {"25", "-25"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () ->
                  service.adjustPoints(
                      TENANT,
                      CUSTOMER,
                      new AdjustPointsRequest(new BigDecimal(points), "a correction"),
                      MANAGER,
                      Ids.newId().toString()));
      assertErased(e);
    }
    verify(repo, never())
        .adjustPoints(any(), any(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("A customer who is not erased is still awarded and adjusted by hand")
  void aLiveCustomerIsStillAwarded() {
    customerIs(Customer.STATUS_ACTIVE);

    service.earnPoints(
        TENANT,
        CUSTOMER,
        new EarnPointsRequest(new BigDecimal("40"), null, "welcome"),
        MANAGER,
        Ids.newId().toString());
    service.adjustPoints(
        TENANT,
        CUSTOMER,
        new AdjustPointsRequest(new BigDecimal("5"), "a correction"),
        MANAGER,
        Ids.newId().toString());

    verify(repo)
        .earnPoints(
            eq(TENANT), eq(CUSTOMER), any(), any(), anyString(), any(), any(), any(), any());
    verify(repo)
        .adjustPoints(eq(TENANT), eq(CUSTOMER), any(), anyString(), any(), any(), any(), any());
  }
}
