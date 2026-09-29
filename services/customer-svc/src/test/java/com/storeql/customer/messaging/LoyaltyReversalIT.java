package com.storeql.customer.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.customer.service.CustomerService;
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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Loyalty follows the money (return controls): a return takes back the share of the points its sale
 * earned, a void takes back the rest, once per event; points already spent leave the balance below
 * zero and redemption is refused until it is earned back; another business's event and a sale that
 * earned nothing touch nothing.
 */
@HelidonTest
class LoyaltyReversalIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  static {
    System.setProperty("storeql.customer.loyalty.sweep-seconds", "0");
  }

  private static final TenantSvcStub TENANTS = TenantSvcStub.start();
  private static final String OWNER = "01a090ae-7f1e-7f05-bde4-50df0324c37c";

  @Inject WebTarget target;
  @Inject CustomerService service;
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

  private jakarta.ws.rs.client.Invocation.Builder as(String path, UUID tenantId) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenantId.toString())
        .header("X-User-Id", OWNER)
        .header("X-Roles", "OWNER");
  }

  private UUID customer(String email) {
    Response r =
        as("/customers", tenant)
            .post(
                Entity.entity(
                    "{\"email\":\""
                        + email
                        + "\",\"firstName\":\"Loyal\",\"lastName\":\"Shopper\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Ids.parse(
        Json.createReader(new StringReader(body))
            .readObject()
            .getJsonObject("data")
            .getString("id"));
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

  private String balance(UUID customerId) throws SQLException {
    String v =
        sql(
            "SELECT points_balance::text FROM customer.loyalty_accounts"
                + " WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            customerId);
    return v == null ? "none" : v;
  }

  private String reversals(UUID orderId) throws SQLException {
    return sql(
        "SELECT COUNT(*) FROM customer.loyalty_ledger WHERE tenant_id = ? AND order_id = ?"
            + " AND type = 'REVERSE'",
        tenant,
        orderId);
  }

  private void earn(UUID customerId, UUID orderId, String total) {
    service.accrueLoyaltyFromOrder(
        Ids.newId(), tenant, customerId, orderId, new BigDecimal(total), BigDecimal.ZERO);
  }

  private static String returned(UUID eventId, UUID tenantId, UUID orderId, String refund) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderReturned\",\"tenantId\":\""
        + tenantId
        + "\",\"orderId\":\""
        + orderId
        + "\",\"returnId\":\""
        + Ids.newId()
        + "\",\"refundAmount\":"
        + refund
        + ",\"refundMethod\":\"ORIGINAL\",\"currency\":\"GBP\",\"items\":[]}";
  }

  private static String voided(UUID eventId, UUID tenantId, UUID orderId) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderVoided\",\"tenantId\":\""
        + tenantId
        + "\",\"orderId\":\""
        + orderId
        + "\",\"items\":[]}";
  }

  private Response redeem(UUID customerId, String points) {
    return as("/customers/" + customerId + "/loyalty/redeem", tenant)
        .post(Entity.entity("{\"points\":" + points + "}", MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName(
      "A partial return takes back its share of the points, and the same event twice takes them once")
  void partialReturnTakesBackItsShare() throws SQLException {
    UUID c = customer("part@example.com");
    UUID order = Ids.newId();
    earn(c, order, "100.00");
    assertThat(balance(c), is("100.00"));

    String first = returned(Ids.newId(), tenant, order, "25.00");
    handler.handleReturned(first);
    handler.handleReturned(first);
    assertThat(balance(c), is("75.00"));
    assertThat(reversals(order), is("1"));

    handler.handleReturned(returned(Ids.newId(), tenant, order, "25.00"));
    assertThat(balance(c), is("50.00"));
    assertThat(
        sql(
            "SELECT points::text FROM customer.loyalty_ledger WHERE tenant_id = ? AND order_id = ?"
                + " AND type = 'REVERSE' ORDER BY created_at DESC LIMIT 1",
            tenant,
            order),
        is("-25.00"));
    // The lots follow the balance, oldest first.
    assertThat(
        sql(
            "SELECT SUM(remaining)::text FROM customer.loyalty_point_lots"
                + " WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            c),
        is("50.00"));
  }

  @Test
  @DisplayName("A void takes back everything not yet taken back, once")
  void voidTakesTheRest() throws SQLException {
    UUID c = customer("void@example.com");
    UUID order = Ids.newId();
    earn(c, order, "100.00");
    handler.handleReturned(returned(Ids.newId(), tenant, order, "40.00"));
    assertThat(balance(c), is("60.00"));

    String v = voided(Ids.newId(), tenant, order);
    handler.handleVoided(v);
    handler.handleVoided(v);
    assertThat(balance(c), is("0.00"));
    assertThat(reversals(order), is("2"));

    // Nothing left to take back: a further void or return moves nothing.
    handler.handleVoided(voided(Ids.newId(), tenant, order));
    handler.handleReturned(returned(Ids.newId(), tenant, order, "100.00"));
    assertThat(balance(c), is("0.00"));
    assertThat(reversals(order), is("2"));
  }

  @Test
  @DisplayName("Returns never take back more than the sale earned")
  void neverMoreThanEarned() throws SQLException {
    UUID c = customer("cap@example.com");
    UUID order = Ids.newId();
    earn(c, order, "100.00");
    handler.handleReturned(returned(Ids.newId(), tenant, order, "100.00"));
    handler.handleReturned(returned(Ids.newId(), tenant, order, "100.00"));
    assertThat(balance(c), is("0.00"));
    assertThat(reversals(order), is("1"));
  }

  @Test
  @DisplayName(
      "Points already spent: the balance goes below zero, redemption is refused until it is earned back")
  void spentPointsLeaveADebt() throws SQLException {
    UUID c = customer("spent@example.com");
    UUID order = Ids.newId();
    earn(c, order, "100.00");
    Response spend = redeem(c, "80");
    assertThat(spend.readEntity(String.class), spend.getStatus(), is(200));

    handler.handleVoided(voided(Ids.newId(), tenant, order));
    assertThat(balance(c), is("-80.00"));

    Response refused = redeem(c, "1");
    String body = refused.readEntity(String.class);
    assertThat(body, refused.getStatus(), is(422));
    assertThat(body, containsString("LOYALTY_INSUFFICIENT_POINTS"));
    assertThat(balance(c), is("-80.00"));

    // Earning again pays the debt first; still refused while at or below zero.
    earn(c, Ids.newId(), "30.00");
    assertThat(balance(c), is("-50.00"));
    assertThat(redeem(c, "1").getStatus(), is(422));
    earn(c, Ids.newId(), "50.00");
    assertThat(balance(c), is("0.00"));
    assertThat(redeem(c, "1").getStatus(), is(422));

    // Earned back: spendable again, and only what the new lots really hold.
    earn(c, Ids.newId(), "50.00");
    assertThat(balance(c), is("50.00"));
    assertThat(
        sql(
            "SELECT SUM(remaining)::text FROM customer.loyalty_point_lots"
                + " WHERE tenant_id = ? AND customer_id = ?",
            tenant,
            c),
        is("50.00"));
    Response ok = redeem(c, "20");
    assertThat(ok.readEntity(String.class), ok.getStatus(), is(200));
    assertThat(balance(c), is("30.00"));
  }

  @Test
  @DisplayName("Another business's events, even naming our order, take nothing back")
  void anotherBusinessTouchesNothing() throws SQLException {
    UUID c = customer("mine@example.com");
    UUID order = Ids.newId();
    earn(c, order, "100.00");
    UUID other = Ids.newId();
    TENANTS.with(other.toString(), "GBP", "GB");

    handler.handleReturned(returned(Ids.newId(), other, order, "100.00"));
    handler.handleVoided(voided(Ids.newId(), other, order));

    assertThat(balance(c), is("100.00"));
    assertThat(reversals(order), is("0"));
  }

  @Test
  @DisplayName(
      "A sale that earned nothing (guest, or never accrued), and malformed events, do nothing")
  void noEarningNothingToTakeBack() throws SQLException {
    UUID c = customer("none@example.com");
    UUID order = Ids.newId();
    handler.handleReturned(returned(Ids.newId(), tenant, order, "10.00"));
    handler.handleVoided(voided(Ids.newId(), tenant, order));
    handler.handleReturned("{not json");
    handler.handleVoided("{}");

    assertThat(balance(c), is("none"));
    assertThat(reversals(order), is("0"));
  }

  @Test
  @DisplayName("A customer's other sales are untouched by a return of one")
  void otherSalesUntouched() throws SQLException {
    UUID c = customer("other-sales@example.com");
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    earn(c, a, "40.00");
    earn(c, b, "60.00");
    handler.handleVoided(voided(Ids.newId(), tenant, a));
    assertThat(balance(c), is("60.00"));
    assertThat(reversals(b), is("0"));
  }
}
