package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cash refused at the limit the law sets where the store trades (09.17): France's EUR 1,000 today,
 * the Union's EUR 10,000 from 10 July 2027, India's two lakh rupees; a split payment for the same
 * goods counted as one; a country with no limit taking any amount.
 */
@HelidonTest
class CashLimitIT {

  private static final PostgresSupport PG;

  private static final String FR = Ids.newId().toString();
  private static final String FR_STORE = Ids.newId().toString();
  private static final String GB = Ids.newId().toString();
  private static final String GB_STORE = Ids.newId().toString();
  private static final String IN = Ids.newId().toString();
  private static final String IN_STORE = Ids.newId().toString();
  private static final String OWNER = Ids.newId().toString();

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(FR, "EUR", "FR")
        .withStore(FR, FR_STORE, "FR")
        .withCashLimit("FR", "FR", "EUR", "1000.00", "2015-09-01", null, "CMF art. L112-6")
        .withCashLimit("FR", "EU", "EUR", "10000.00", "2027-07-10", null, "AMLR art.80")
        .with(GB, "GBP", "GB")
        .withStore(GB, GB_STORE, "GB")
        .with(IN, "INR", "IN")
        .withStore(IN, IN_STORE, "IN")
        .withCashLimit(
            "IN", "IN", "INR", "200000.00", "2017-04-01", null, "Income-tax Act s.269ST");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "payment");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response tender(
      String tenant, String store, String order, String amount, String method, String currency) {
    return target
        .path("/payments")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", OWNER)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", Ids.newId().toString())
        .post(
            Entity.entity(
                "{\"orderId\":\""
                    + order
                    + "\",\"amount\":"
                    + amount
                    + ",\"method\":\""
                    + method
                    + "\",\"storeId\":\""
                    + store
                    + "\",\"currency\":\""
                    + currency
                    + "\""
                    // a card typed at a till carries the machine's receipt reference
                    + ("CARD".equals(method) ? ",\"reference\":\"AUTH 1\"" : "")
                    + "}",
                MediaType.APPLICATION_JSON));
  }

  private static String refused(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(409));
    assertThat(body, containsString("PAYMENT_CASH_LIMIT_EXCEEDED"));
    return body;
  }

  private static void taken(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
  }

  @Test
  @DisplayName("A French till refuses cash of EUR 1,000 or more, in one payment or in pieces")
  void franceRefusesCashAtItsLimit() {
    String order = Ids.newId().toString();
    String body = refused(tender(FR, FR_STORE, order, "1200.00", "CASH", "EUR"));
    assertThat(body, containsString("CMF art. L112-6"));
    assertThat(body, containsString("EUR 1000.00 or more"));
    taken(tender(FR, FR_STORE, order, "999.99", "CASH", "EUR"));
    // The same goods, paid in pieces: the pieces are one payment.
    String linked = refused(tender(FR, FR_STORE, order, "0.01", "CASH", "EUR"));
    assertThat(linked, containsString("999.99 already taken in cash"));
    // The balance another way is fine, and cash under the limit on another sale is fine.
    taken(tender(FR, FR_STORE, order, "1200.00", "CARD", "EUR"));
    taken(tender(FR, FR_STORE, Ids.newId().toString(), "999.99", "CASH", "EUR"));
    // Exactly the limit is refused: the law says "or more".
    refused(tender(FR, FR_STORE, Ids.newId().toString(), "1000.00", "CASH", "EUR"));
  }

  @Test
  @DisplayName("India refuses two lakh rupees or more in cash; Britain sets no limit")
  void indiaRefusesAndBritainDoesNot() {
    refused(tender(IN, IN_STORE, Ids.newId().toString(), "250000.00", "CASH", "INR"));
    taken(tender(IN, IN_STORE, Ids.newId().toString(), "199999.00", "CASH", "INR"));
    taken(tender(GB, GB_STORE, Ids.newId().toString(), "50000.00", "CASH", "GBP"));
  }

  @Test
  @DisplayName(
      "A limit in another currency does not bind, and the business's own country binds a tender with no store")
  void currencyAndCountryOfTheLimit() {
    // A French business taking pounds meets no EUR limit: the equivalent is a rate, not a law.
    taken(tender(FR, FR_STORE, Ids.newId().toString(), "5000.00", "CASH", "GBP"));
    // No store named: the business's own country, France, binds.
    Response r =
        target
            .path("/payments")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", FR)
            .header("X-User-Id", OWNER)
            .header("X-Roles", "OWNER")
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"orderId\":\"" + Ids.newId() + "\",\"amount\":1500,\"method\":\"CASH\"}",
                    MediaType.APPLICATION_JSON));
    refused(r);
  }
}
