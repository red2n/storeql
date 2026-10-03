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
 * A refund the shopper took as store credit (return controls): {@code PaymentRefunded} with {@code
 * refundMethod} STORE_CREDIT credits the sale's customer once, in the refund's currency; the other
 * methods credit nothing; another business's event touches none of our customers.
 */
@HelidonTest
class StoreCreditRefundIT {

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

  private static String refund(
      UUID eventId,
      UUID tenantId,
      UUID orderId,
      String method,
      String customerId,
      String amount,
      String currency) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\""
        + tenantId
        + "\",\"refundId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + orderId
        + "\",\"amount\":"
        + amount
        + ",\"tenders\":[]"
        + (method == null ? "" : ",\"refundMethod\":\"" + method + "\"")
        + ",\"returnId\":\""
        + Ids.newId()
        + "\""
        + (customerId == null ? "" : ",\"customerId\":\"" + customerId + "\"")
        + (currency == null ? "" : ",\"currency\":\"" + currency + "\"")
        + "}";
  }

  @Test
  @DisplayName(
      "A STORE_CREDIT refund credits the sale's customer, naming the order, and a replay credits once")
  void storeCreditRefundCreditsOnce() throws SQLException {
    String c = customer("ada@example.com");
    UUID order = Ids.newId();
    String event = refund(Ids.newId(), tenant, order, "STORE_CREDIT", c, "12.50", "GBP");

    handler.handleRefunded(event);
    handler.handleRefunded(event);

    assertThat(balance(tenant, c, "GBP"), is("12.50"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.store_credit_ledger WHERE tenant_id = ? AND customer_id = ?"
                + " AND type = 'ISSUE' AND order_id = ? AND amount = 12.50",
            tenant,
            Ids.parse(c),
            order),
        is("1"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.outbox WHERE tenant_id = ?"
                + " AND event_type = 'StoreCreditIssued'",
            tenant),
        is("1"));
  }

  @Test
  @DisplayName("A second refund is a second credit, on the same account")
  void twoRefundsAddUp() throws SQLException {
    String c = customer("two@example.com");
    handler.handleRefunded(
        refund(Ids.newId(), tenant, Ids.newId(), "STORE_CREDIT", c, "5.00", "GBP"));
    handler.handleRefunded(
        refund(Ids.newId(), tenant, Ids.newId(), "STORE_CREDIT", c, "7.25", "GBP"));
    assertThat(balance(tenant, c, "GBP"), is("12.25"));
  }

  @Test
  @DisplayName("The credit is in the refund's currency; without one, the business's own")
  void currencyFollowsTheRefund() throws SQLException {
    String c = customer("cur@example.com");
    handler.handleRefunded(
        refund(Ids.newId(), tenant, Ids.newId(), "STORE_CREDIT", c, "3.00", "EUR"));
    handler.handleRefunded(
        refund(Ids.newId(), tenant, Ids.newId(), "STORE_CREDIT", c, "4.00", null));
    assertThat(balance(tenant, c, "EUR"), is("3.00"));
    assertThat(balance(tenant, c, "GBP"), is("4.00"));
  }

  @Test
  @DisplayName("A dinar credit keeps its third place and a yen credit is whole: nothing is rounded")
  void minorUnitsAreTheCurrencysOwn() throws SQLException {
    String c = customer("units@example.com");
    handler.handleRefunded(
        refund(Ids.newId(), tenant, Ids.newId(), "STORE_CREDIT", c, "1.125", "KWD"));
    handler.handleRefunded(
        refund(Ids.newId(), tenant, Ids.newId(), "STORE_CREDIT", c, "500", "JPY"));
    // A two-place column would have kept KWD 1.13: five fils of credit out of nothing.
    assertThat(
        sql(
            "SELECT (balance = 1.125)::text FROM customer.store_credit_accounts"
                + " WHERE tenant_id = ? AND customer_id = ? AND currency = 'KWD'",
            tenant,
            Ids.parse(c)),
        is("true"));
    assertThat(balance(tenant, c, "KWD"), is("1.125"));
    assertThat(balance(tenant, c, "JPY"), is("500"));
    assertThat(balance(tenant, c, "GBP"), is("none"));
  }

  @Test
  @DisplayName("ORIGINAL and GIFT_CARD refunds, and refunds naming no method, credit nothing")
  void otherMethodsCreditNothing() throws SQLException {
    String c = customer("orig@example.com");
    handler.handleRefunded(refund(Ids.newId(), tenant, Ids.newId(), "ORIGINAL", c, "9.00", "GBP"));
    handler.handleRefunded(refund(Ids.newId(), tenant, Ids.newId(), "GIFT_CARD", c, "9.00", "GBP"));
    handler.handleRefunded(refund(Ids.newId(), tenant, Ids.newId(), null, c, "9.00", "GBP"));
    assertThat(balance(tenant, c, "GBP"), is("none"));
  }

  @Test
  @DisplayName(
      "A STORE_CREDIT refund naming no customer, or a malformed one, credits nobody and does not throw")
  void noCustomerNothing() throws SQLException {
    handler.handleRefunded(
        refund(Ids.newId(), tenant, Ids.newId(), "STORE_CREDIT", null, "9.00", "GBP"));
    handler.handleRefunded("{not json");
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

    handler.handleRefunded(
        refund(Ids.newId(), other, Ids.newId(), "STORE_CREDIT", c, "50.00", "GBP"));

    assertThat(balance(tenant, c, "GBP"), is("none"));
    assertThat(balance(other, c, "GBP"), is("none"));
    assertThat(
        sql(
            "SELECT COUNT(*) FROM customer.store_credit_ledger WHERE customer_id = ?",
            Ids.parse(c)),
        is("0"));
  }
}
