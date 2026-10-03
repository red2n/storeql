package com.storeql.customer.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A no-receipt return refunded as store credit (return controls): {@code NoReceiptReturnRecorded}
 * with {@code refundMethod} STORE_CREDIT credits the named customer once, in the return's currency;
 * a gift card credits nothing here, nor does a return naming no customer; another business's event
 * touches none of our customers.
 */
@HelidonTest
class NoReceiptStoreCreditIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  static {
    System.setProperty("storeql.customer.loyalty.sweep-seconds", "0");
  }

  private static final TenantSvcStub TENANTS = TenantSvcStub.start();
  private static final String OWNER = "01a090ae-7f1e-7f05-bde4-50df0324c37c";

  @Inject WebTarget target;
  @Inject ReturnEventsHandler handler;

  private UUID tenant;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    TENANTS.with(tenant.toString(), "GBP", "GB");
  }

  private String customer(String email) {
    Response r =
        target
            .path("/customers")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant.toString())
            .header("X-User-Id", OWNER)
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"firstName\":\"Ada\",\"lastName\":\"Lee\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  private String sql(String statement, Object... params) throws SQLException {
    try (var c = PG.dataSource().getConnection();
        var ps = c.prepareStatement(statement)) {
      for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? String.valueOf(rs.getObject(1)) : null;
      }
    }
  }

  private String balance(UUID tenantId, String customerId, String currency) throws SQLException {
    String v =
        sql(
            "SELECT balance FROM customer.store_credit_accounts"
                + " WHERE tenant_id = ? AND customer_id = ? AND currency = ?",
            tenantId,
            Ids.parse(customerId),
            currency);
    // The column holds four places for any currency; the balance is read as it is written, at
    // the currency's own minor units.
    return v == null
        ? "none"
        : com.storeql.customer.mapper.Mappers.money(new java.math.BigDecimal(v), currency)
            .toPlainString();
  }

  private static String event(
      UUID eventId, UUID tenantId, String method, String customerId, String amount) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"NoReceiptReturnRecorded\",\"tenantId\":\""
        + tenantId
        + "\",\"returnId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"currency\":\"GBP\",\"amount\":"
        + amount
        + ",\"taxAmount\":1.00,\"refundMethod\":\""
        + method
        + "\",\"customerId\":"
        + (customerId == null ? "null" : "\"" + customerId + "\"")
        + ",\"giftCardId\":null,\"approvedBy\":\""
        + Ids.newId()
        + "\",\"items\":[]}";
  }

  @Test
  @DisplayName(
      "A STORE_CREDIT no-receipt return credits the customer once, and a replay adds nothing")
  void creditsOnce() throws SQLException {
    String c = customer("nr@example.com");
    String event = event(Ids.newId(), tenant, "STORE_CREDIT", c, "8.00");

    handler.handleNoReceiptReturn(event);
    handler.handleNoReceiptReturn(event);

    assertThat(balance(tenant, c, "GBP"), is("8.00"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.store_credit_ledger WHERE tenant_id = ? AND customer_id = ?"
                + " AND type = 'ISSUE' AND amount = 8.00",
            tenant,
            Ids.parse(c)),
        is("1"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.outbox WHERE tenant_id = ?"
                + " AND event_type = 'StoreCreditIssued'",
            tenant),
        is("1"));
  }

  @Test
  @DisplayName("A GIFT_CARD no-receipt return credits no store credit")
  void giftCardCreditsNothing() throws SQLException {
    String c = customer("gc@example.com");
    handler.handleNoReceiptReturn(event(Ids.newId(), tenant, "GIFT_CARD", c, "8.00"));
    assertThat(balance(tenant, c, "GBP"), is("none"));
  }

  @Test
  @DisplayName("A return naming no customer, or a malformed one, credits nobody and does not throw")
  void noCustomerNothing() throws SQLException {
    handler.handleNoReceiptReturn(event(Ids.newId(), tenant, "STORE_CREDIT", null, "8.00"));
    handler.handleNoReceiptReturn("{not json");
    assertThat(
        sql("SELECT COUNT(*) FROM customer.store_credit_ledger WHERE tenant_id = ?", tenant),
        is("0"));
  }

  @Test
  @DisplayName("Another business's event, even naming our customer, touches nothing")
  void anotherBusinessTouchesNothing() throws SQLException {
    String c = customer("mine@example.com");
    UUID other = Ids.newId();
    TENANTS.with(other.toString(), "GBP", "GB");

    handler.handleNoReceiptReturn(event(Ids.newId(), other, "STORE_CREDIT", c, "50.00"));

    assertThat(balance(tenant, c, "GBP"), is("none"));
    assertThat(balance(other, c, "GBP"), is("none"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.store_credit_ledger WHERE customer_id = ?",
            Ids.parse(c)),
        is("0"));
  }
}
