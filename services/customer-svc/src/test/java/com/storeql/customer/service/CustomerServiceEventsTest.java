package com.storeql.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.LoyaltyAccount;
import com.storeql.customer.domain.Domain.StoreCreditAccount;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.customer.dto.Dtos.AdjustPointsRequest;
import com.storeql.customer.dto.Dtos.IssueStoreCreditRequest;
import com.storeql.customer.dto.Dtos.RedeemStoreCreditRequest;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.customer.repo.LoyaltyProgrammeRepository;
import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Gap #76 — adjustPoints/issueStoreCredit/redeemStoreCredit must publish an outbox event like every
 * other write in this service, so reporting-svc/notification-svc can observe loyalty adjustments
 * and store-credit money movement.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceEventsTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();

  @Mock CustomerRepository repo;
  @Mock LoyaltyProgrammeRepository programmes;
  @Mock com.storeql.service.TenantProfiles profiles;
  private CustomerService service;

  @BeforeEach
  void setUp() {
    service = new CustomerService();
    service.profiles = profiles;
    service.programmes = programmes;
    // The platform's default programme unless a test says otherwise.
    org.mockito.Mockito.lenient()
        .when(programmes.programme(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> LoyaltyProgramme.defaults(inv.getArgument(0)));
    // The named currency, or the tenant's own as tenant-svc would answer (SJ-D53).
    org.mockito.Mockito.lenient()
        .when(
            profiles.currencyOr(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              String named = inv.getArgument(1);
              return named == null || named.isBlank()
                  ? "GBP"
                  : named.trim().toUpperCase(java.util.Locale.ROOT);
            });
    service.repo = repo;
    // Lenient: an accrual never looks the customer up.
    org.mockito.Mockito.lenient()
        .when(repo.findById(eq(TENANT), eq(CUSTOMER)))
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
  }

  @Test
  void anAccrualCarriesItsOwnIdAndTheSaleItCameFrom() {
    service.pointsPerUnitRaw = "1";
    UUID order = Ids.newId();
    service.accrueLoyaltyFromOrder(
        Ids.newId(), TENANT, CUSTOMER, order, new BigDecimal("24.00"), new BigDecimal("4.00"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Function<BigDecimal, OutboxRow>> captor =
        ArgumentCaptor.forClass(Function.class);
    verify(repo)
        .accrueFromOrderOnce(
            any(),
            any(),
            eq(TENANT),
            eq(CUSTOMER),
            eq(order),
            eq(new BigDecimal("24.00")),
            any(LoyaltyProgramme.class),
            captor.capture(),
            any());
    // The event is built for the points actually awarded, once the tier's multiplier is known.
    OutboxRow row = captor.getValue().apply(new BigDecimal("24.00"));
    JsonObject p = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals(0, new BigDecimal("24.00").compareTo(p.getJsonNumber("points").bigDecimalValue()));
    assertEquals("LoyaltyEarned", p.getString("eventType"));
    assertNotNull(Ids.parse(p.getString("eventId")));
    assertEquals(order.toString(), p.getString("orderId"));
    assertEquals(
        0, new BigDecimal("24.00").compareTo(p.getJsonNumber("orderTotal").bigDecimalValue()));
    assertEquals(
        0, new BigDecimal("4.00").compareTo(p.getJsonNumber("orderTaxAmount").bigDecimalValue()));
  }

  @Test
  void everyLoyaltyEventCarriesADistinctId() {
    ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
    when(repo.adjustPoints(
            eq(TENANT),
            eq(CUSTOMER),
            any(),
            anyString(),
            any(LoyaltyProgramme.class),
            captor.capture(),
            any()))
        .thenReturn(null);
    service.adjustPoints(TENANT, CUSTOMER, new AdjustPointsRequest(BigDecimal.ONE, "one"));
    service.adjustPoints(TENANT, CUSTOMER, new AdjustPointsRequest(BigDecimal.ONE, "two"));

    List<String> ids =
        captor.getAllValues().stream()
            .map(e -> Json.createReader(new StringReader(e.payload())).readObject())
            .peek(p -> assertEquals("LoyaltyAdjusted", p.getString("eventType")))
            .map(p -> p.getString("eventId"))
            .toList();
    assertEquals(2, ids.size());
    assertNotEquals(ids.get(0), ids.get(1));
  }

  @Test
  void adjustPointsPublishesLoyaltyAdjustedEvent() {
    ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
    when(repo.adjustPoints(
            eq(TENANT),
            eq(CUSTOMER),
            any(),
            anyString(),
            any(LoyaltyProgramme.class),
            captor.capture(),
            any()))
        .thenReturn(
            new LoyaltyAccount(
                Ids.newId(),
                TENANT,
                CUSTOMER,
                BigDecimal.TEN,
                BigDecimal.TEN,
                LoyaltyAccount.TIER_BRONZE,
                Instant.now(),
                Instant.now(),
                BigDecimal.TEN,
                Instant.now()));

    service.adjustPoints(TENANT, CUSTOMER, new AdjustPointsRequest(BigDecimal.TEN, "manual"));

    OutboxRow event = captor.getValue();
    assertNotNull(event);
    assertEquals("LoyaltyAdjusted", event.eventType());
    assertEquals("storeql.customer.loyalty-adjusted", event.topic());
    assertEquals(CUSTOMER, event.aggregateId());
  }

  @Test
  void issueStoreCreditPublishesStoreCreditIssuedEvent() {
    ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
    when(repo.issueStoreCredit(
            eq(TENANT), eq(CUSTOMER), any(), anyString(), any(), any(), captor.capture()))
        .thenReturn(
            new StoreCreditAccount(
                Ids.newId(),
                TENANT,
                CUSTOMER,
                BigDecimal.TEN,
                "GBP",
                Instant.now(),
                Instant.now()));

    service.issueStoreCredit(
        TENANT, CUSTOMER, new IssueStoreCreditRequest(BigDecimal.TEN, "GBP", null, "refund"));

    OutboxRow event = captor.getValue();
    assertNotNull(event);
    assertEquals("StoreCreditIssued", event.eventType());
    assertEquals("storeql.customer.store-credit-issued", event.topic());
  }

  @Test
  void redeemStoreCreditPublishesStoreCreditRedeemedEvent() {
    ArgumentCaptor<OutboxRow> captor = ArgumentCaptor.forClass(OutboxRow.class);
    when(repo.redeemStoreCredit(
            eq(TENANT), eq(CUSTOMER), any(), anyString(), any(), any(), captor.capture()))
        .thenReturn(
            new StoreCreditAccount(
                Ids.newId(),
                TENANT,
                CUSTOMER,
                BigDecimal.ZERO,
                "GBP",
                Instant.now(),
                Instant.now()));

    service.redeemStoreCredit(
        TENANT, CUSTOMER, new RedeemStoreCreditRequest(BigDecimal.TEN, "GBP", null, "purchase"));

    OutboxRow event = captor.getValue();
    assertNotNull(event);
    assertEquals("StoreCreditRedeemed", event.eventType());
    assertEquals("storeql.customer.store-credit-redeemed", event.topic());
  }
}
