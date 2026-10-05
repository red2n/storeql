package com.storeql.payment.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.payment.config.ServiceConfig;
import com.storeql.test.JsonStub;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the store-credit redeem says when customer-svc refuses it, against a stub standing for
 * customer-svc: the refusal keeps its own stable code and status, never a 503 and never a success,
 * and is never counted by the circuit breaker as an outage.
 */
class CustomerClientTest {

  private static JsonStub customers;
  private static CustomerClient client;

  @BeforeAll
  static void start() {
    customers = JsonStub.start("customer-svc");
    client = new CustomerClient();
    // No CDI here, so the injected settings are unset: give the (never used) discovery client an
    // address, as the stub's configured URL is what decides where the call goes.
    client.config =
        new ServiceConfig() {
          @Override
          public String consulHost() {
            return "localhost";
          }

          @Override
          public int consulPort() {
            return 8500;
          }
        };
    client.init();
  }

  @AfterAll
  static void stop() {
    customers.close();
  }

  private static String redeemPath(UUID customer) {
    return "/customers/" + customer + "/store-credit/redeem";
  }

  private static void redeem(UUID customer) {
    client.redeemStoreCredit(Ids.newId(), customer, new BigDecimal("5.00"), "GBP", Ids.newId());
  }

  @Test
  @DisplayName("A balance too small is 422 PAYMENT_STORE_CREDIT_INSUFFICIENT")
  void aBalanceTooSmallIsRefused() {
    UUID customer = Ids.newId();
    customers.on("POST", redeemPath(customer), 422, "{\"code\":\"STORE_CREDIT_INSUFFICIENT\"}");

    ApiException e = assertThrows(ApiException.class, () -> redeem(customer));

    assertThat(e.status(), is(422));
    assertThat(e.code(), is("PAYMENT_STORE_CREDIT_INSUFFICIENT"));
  }

  @Test
  @DisplayName("A customer nobody holds is 404 PAYMENT_CUSTOMER_NOT_FOUND")
  void aCustomerNobodyHoldsIsNotFound() {
    UUID customer = Ids.newId();
    customers.on("POST", redeemPath(customer), 404, "{\"code\":\"CUSTOMER_NOT_FOUND\"}");

    ApiException e = assertThrows(ApiException.class, () -> redeem(customer));

    assertThat(e.status(), is(404));
    assertThat(e.code(), is("PAYMENT_CUSTOMER_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "Any other answer is 503 PAYMENT_CUSTOMER_UNAVAILABLE, and a good one is not an error")
  void anyOtherAnswerIsUnavailable() {
    UUID broken = Ids.newId();
    customers.on("POST", redeemPath(broken), 500, "{}");
    ApiException e = assertThrows(ApiException.class, () -> redeem(broken));
    assertThat(e.status(), is(503));
    assertThat(e.code(), is("PAYMENT_CUSTOMER_UNAVAILABLE"));

    UUID fine = Ids.newId();
    customers.on("POST", redeemPath(fine), 200, "{\"data\":{}}");
    redeem(fine);
  }

  @Test
  @DisplayName(
      "An erased customer is 409 CUSTOMER_ANONYMIZED, passed on as customer-svc gave it, never a"
          + " 503 the till would invite a retry of")
  void anErasedCustomerIsRefusedNotUnavailable() {
    UUID customer = Ids.newId();
    customers.on(
        "POST",
        redeemPath(customer),
        409,
        "{\"type\":\"urn:storeql:problem:CUSTOMER_ANONYMIZED\",\"status\":409,"
            + "\"code\":\"CUSTOMER_ANONYMIZED\"}");
    long before = callsTo(customer);

    ApiException e = assertThrows(ApiException.class, () -> redeem(customer));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("CUSTOMER_ANONYMIZED"));
    assertThat(e, instanceOf(CustomerClient.Refusal.class));
    assertThat("asked once", callsTo(customer) - before, is(1L));
  }

  @Test
  @DisplayName(
      "Every refusal is skipped by the circuit breaker and aborts the retry; an outage is neither")
  void refusalsNeverCountAgainstTheBreaker() throws NoSuchMethodException {
    var redeem =
        CustomerClient.class.getMethod(
            "redeemStoreCredit",
            UUID.class,
            UUID.class,
            BigDecimal.class,
            String.class,
            UUID.class);
    assertThat(
        Arrays.asList(redeem.getAnnotation(CircuitBreaker.class).skipOn()),
        hasItem(CustomerClient.Refusal.class));
    assertThat(
        Arrays.asList(redeem.getAnnotation(Retry.class).abortOn()), hasItem(ApiException.class));

    UUID poor = Ids.newId();
    customers.on("POST", redeemPath(poor), 422, "{\"code\":\"STORE_CREDIT_INSUFFICIENT\"}");
    assertThat(
        assertThrows(ApiException.class, () -> redeem(poor)),
        instanceOf(CustomerClient.Refusal.class));
    UUID unknown = Ids.newId();
    customers.on("POST", redeemPath(unknown), 404, "{\"code\":\"CUSTOMER_NOT_FOUND\"}");
    assertThat(
        assertThrows(ApiException.class, () -> redeem(unknown)),
        instanceOf(CustomerClient.Refusal.class));

    UUID down = Ids.newId();
    customers.on("POST", redeemPath(down), 500, "{}");
    ApiException outage = assertThrows(ApiException.class, () -> redeem(down));
    assertThat(outage.status(), is(503));
    assertThat(outage, not(instanceOf(CustomerClient.Refusal.class)));
  }

  private static long callsTo(UUID customer) {
    return customers.calls().stream().filter(c -> c.path().equals(redeemPath(customer))).count();
  }
}
