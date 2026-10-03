package com.storeql.customer.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualGrantTest {

  private static final UUID CUSTOMER = Ids.newId();

  private static ManualGrant grant(String kind, UUID customer, String amount, String currency) {
    return new ManualGrant(
        kind,
        customer,
        new BigDecimal(amount),
        currency,
        "why",
        Ids.newId(),
        Ids.newId().toString());
  }

  @Test
  void aRetryIsTheSameRequestWhateverTheAmountsScale() {
    var first = grant(ManualGrant.KIND_LOYALTY_EARN, CUSTOMER, "40", null);
    var retry = grant(ManualGrant.KIND_LOYALTY_EARN, CUSTOMER, "40.00", null);
    assertTrue(first.sameRequestAs(retry));
  }

  @Test
  void aRetryMayCarryADifferentReasonOrActor() {
    var first = grant(ManualGrant.KIND_LOYALTY_ADJUST, CUSTOMER, "-5", null);
    var retry =
        new ManualGrant(
            ManualGrant.KIND_LOYALTY_ADJUST,
            CUSTOMER,
            new BigDecimal("-5"),
            null,
            "reworded",
            Ids.newId(),
            first.idempotencyKey());
    assertTrue(first.sameRequestAs(retry));
  }

  @Test
  void anotherKindCustomerAmountOrCurrencyIsAnotherRequest() {
    var first = grant(ManualGrant.KIND_STORE_CREDIT_ISSUE, CUSTOMER, "10", "GBP");
    assertFalse(first.sameRequestAs(grant(ManualGrant.KIND_LOYALTY_EARN, CUSTOMER, "10", "GBP")));
    assertFalse(
        first.sameRequestAs(grant(ManualGrant.KIND_STORE_CREDIT_ISSUE, Ids.newId(), "10", "GBP")));
    assertFalse(
        first.sameRequestAs(grant(ManualGrant.KIND_STORE_CREDIT_ISSUE, CUSTOMER, "11", "GBP")));
    assertFalse(
        first.sameRequestAs(grant(ManualGrant.KIND_STORE_CREDIT_ISSUE, CUSTOMER, "10", "JPY")));
  }
}
