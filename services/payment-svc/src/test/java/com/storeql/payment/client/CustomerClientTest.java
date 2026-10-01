package com.storeql.payment.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.payment.config.ServiceConfig;
import com.storeql.test.JsonStub;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the store-credit redeem says when customer-svc refuses it, against a stub standing for
 * customer-svc: the refusal keeps its own stable code and status, never a 503 and never a success.
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
}
