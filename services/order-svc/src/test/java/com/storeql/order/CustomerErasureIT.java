package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A customer a shop erased stops being identifiable in that shop's orders (SJ-D43).
 *
 * <p>Settled orders lose the customer's name, phone and address at once. An open order keeps its
 * delivery details until it finishes — they are needed to deliver it — and is redacted then. The
 * sale itself stays: amounts and lines are tax records.
 */
@HelidonTest
class CustomerErasureIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(CustomerErasureIT.T, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
  }

  private static final String T = "01a090ae-611e-702b-8e05-b3c421241865";
  private static final String S = "01a090ae-611e-7036-97eb-b0b2629f1654";
  private static final String V = "01a090ae-611e-703b-9569-17e55100ef17";

  @Inject WebTarget target;
  @Inject OrderService orderService;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private Response post(String path, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String id(String json) {
    var m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private UUID place(String customer, String channel, String fulfilment, String extra) {
    Response r =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\""
                + channel
                + "\",\"fulfilmentType\":\""
                + fulfilment
                + "\",\"customerId\":\""
                + customer
                + "\","
                + extra
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}]}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Ids.parse(id(body));
  }

  private void pay(UUID order) {
    orderService.handlePaymentCaptured(
        Ids.parse(T), order, Ids.newId(), new BigDecimal("10.00"), "CARD");
  }

  private static String column(String table, UUID id, String column) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement("SELECT " + column + " FROM \"order\"." + table + " WHERE id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String DELIVERY =
      "\"deliveryLine1\":\"12 High Street\",\"deliveryCity\":\"London\","
          + "\"deliveryPostalCode\":\"EC1A 1BB\",\"deliveryRecipientName\":\"Chris Carter\","
          + "\"deliveryRecipientPhone\":\"07700900123\",";

  @Test
  @DisplayName("A finished sale loses the customer's details at once, and keeps the sale")
  void settledOrdersAreRedactedNow() {
    String customer = Ids.newId().toString();
    UUID order = place(customer, "POS", "INSTORE", "\"contactPhone\":\"07400900999\",");
    pay(order); // a till sale: FULFILLED
    assertThat(column("orders", order, "contact_phone"), is("07400900999"));
    assertThat(column("orders", order, "contact_phone_e164"), is("+447400900999"));

    orderService.handleCustomerErased(Ids.parse(T), Ids.parse(customer), null, Ids.newId());

    assertThat(column("orders", order, "contact_phone"), nullValue());
    assertThat(
        "both forms go (a phone at the till)",
        column("orders", order, "contact_phone_e164"),
        nullValue());
    // The tax record stays.
    assertThat(column("orders", order, "total"), notNullValue());
    assertThat(column("orders", order, "status"), is("FULFILLED"));
  }

  @Test
  @DisplayName("An open delivery keeps its address until it is done, then loses it")
  void openOrdersAreHeldUntilTheyFinish() {
    String customer = Ids.newId().toString();
    UUID order = place(customer, "ONLINE", "DELIVERY", DELIVERY);
    pay(order); // online: CONFIRMED, not yet delivered

    orderService.handleCustomerErased(Ids.parse(T), Ids.parse(customer), null, Ids.newId());
    // Still needed to deliver it.
    assertThat(column("orders", order, "delivery_line1"), is("12 High Street"));
    assertThat(column("orders", order, "delivery_recipient_phone"), is("07700900123"));

    assertThat(post("/orders/" + order + "/fulfil", "{}").getStatus(), is(200));
    orderService.sweepErasures();

    assertThat(column("orders", order, "delivery_line1"), nullValue());
    assertThat(column("orders", order, "delivery_postal_code"), nullValue());
    assertThat(column("orders", order, "delivery_recipient_name"), nullValue());
    assertThat(column("orders", order, "delivery_recipient_phone"), nullValue());
  }

  @Test
  @DisplayName("A redelivered erasure changes nothing a second time")
  void redeliveryIsANoOp() {
    String customer = Ids.newId().toString();
    UUID event = Ids.newId();
    assertThat(
        orderService.handleCustomerErased(Ids.parse(T), Ids.parse(customer), null, event),
        is(true));
    assertThat(
        orderService.handleCustomerErased(Ids.parse(T), Ids.parse(customer), null, event),
        is(false));
  }

  @Test
  @DisplayName("Another customer's orders are untouched")
  void onlyTheErasedCustomer() {
    String erased = Ids.newId().toString();
    String kept = Ids.newId().toString();
    UUID theirs = place(kept, "POS", "INSTORE", "\"contactPhone\":\"07400900555\",");
    pay(theirs);

    orderService.handleCustomerErased(Ids.parse(T), Ids.parse(erased), null, Ids.newId());
    orderService.sweepErasures();

    assertThat(column("orders", theirs, "contact_phone"), is("07400900555"));
    assertThat(column("orders", theirs, "contact_phone_e164"), is("+447400900555"));
  }

  /**
   * A signed-in shopper's own checkout: the order carries their login. customer-svc is not running
   * here, so the link fails and customer_id stays null — which is the fail-open path in
   * CustomerLinkClient, and exactly the state an erasure has to cope with.
   */
  private UUID placeAsShopper(UUID login, String fulfilment, String extra) {
    Response r =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", login.toString())
            .header("X-User-Email", "chris@example.com")
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\""
                        + fulfilment
                        + "\","
                        + extra
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"unitPrice\":10.00}]}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Ids.parse(id(body));
  }

  @Test
  @DisplayName("The shopper's online order is filed under their login, not a customer id")
  void anOnlineOrderRecordsTheLogin() {
    UUID login = Ids.newId();
    UUID order = placeAsShopper(login, "DELIVERY", DELIVERY);

    assertThat(column("orders", order, "login_id"), is(login.toString()));
    // The link could not be made (customer-svc is not running), and the sale still stands.
    assertThat(column("orders", order, "customer_id"), nullValue());
  }

  @Test
  @DisplayName("Erasing the customer reaches the online orders filed under their login (SJ-D44)")
  void erasureReachesOrdersFiledUnderTheLogin() {
    UUID login = Ids.newId();
    UUID customer = Ids.newId();
    UUID order = placeAsShopper(login, "DELIVERY", DELIVERY);
    pay(order);
    assertThat(post("/orders/" + order + "/fulfil", "{}").getStatus(), is(200));

    // The shop erases the customer record. Before SJ-D44 this named the customer id alone, and the
    // delivery address on this order — which carries no customer id at all — survived it.
    orderService.handleCustomerErased(Ids.parse(T), customer, login, Ids.newId());

    assertThat(column("orders", order, "delivery_line1"), nullValue());
    assertThat(column("orders", order, "delivery_recipient_name"), nullValue());
    assertThat(column("orders", order, "delivery_recipient_phone"), nullValue());
    // The sale itself is a tax record and stays.
    assertThat(column("orders", order, "total"), notNullValue());
  }

  @Test
  @DisplayName("An open order filed under a login is swept once it finishes")
  void openLoginOrderIsSweptWhenItFinishes() {
    UUID login = Ids.newId();
    UUID order = placeAsShopper(login, "DELIVERY", DELIVERY);
    pay(order);

    orderService.handleCustomerErased(Ids.parse(T), Ids.newId(), login, Ids.newId());
    assertThat(column("orders", order, "delivery_line1"), is("12 High Street"));

    assertThat(post("/orders/" + order + "/fulfil", "{}").getStatus(), is(200));
    orderService.sweepErasures();

    assertThat(column("orders", order, "delivery_line1"), nullValue());
  }

  @Test
  @DisplayName("The export route is staff-only, needs a subject, and matches by either id")
  void theExportRouteIsGuarded() {
    UUID login = Ids.newId();
    UUID order = placeAsShopper(login, "DELIVERY", DELIVERY);

    // A shopper token gets 403 from the read guard: the route names a subject, so it is not a
    // self-read and must not be open to customers.
    Response asShopper =
        target
            .path("/orders/export")
            .queryParam("login", login.toString())
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", login.toString())
            .get();
    assertThat(asShopper.getStatus(), is(403));

    // Staff with neither id: a 400, not the whole order book.
    Response nobody =
        target
            .path("/orders/export")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(nobody.getStatus(), is(400));
    assertThat(nobody.readEntity(String.class), containsString("ORDER_EXPORT_NO_SUBJECT"));

    // A malformed id is a 400, not a 500.
    Response malformed =
        target
            .path("/orders/export")
            .queryParam("customer", "not-a-uuid")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(malformed.getStatus(), is(400));

    // By login finds the shopper's order; by an unrelated customer id finds nothing.
    Response byLogin =
        target
            .path("/orders/export")
            .queryParam("login", login.toString())
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(byLogin.getStatus(), is(200));
    assertThat(byLogin.readEntity(String.class), containsString(order.toString()));
    Response byStranger =
        target
            .path("/orders/export")
            .queryParam("customer", Ids.newId().toString())
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(byStranger.readEntity(String.class), not(containsString(order.toString())));

    // Another tenant's staff cannot see it either: the tenant is the first condition.
    Response otherTenant =
        target
            .path("/orders/export")
            .queryParam("login", login.toString())
            .request()
            .header("X-Tenant-Id", Ids.newId().toString())
            .header("X-Roles", "OWNER")
            .get();
    assertThat(otherTenant.readEntity(String.class), not(containsString(order.toString())));
  }

  @Test
  @DisplayName("A shopper reads only the orders their own login placed, on every self-read")
  void aShopperReadsOnlyTheirOwn() {
    UUID mine = Ids.newId();
    UUID theirs = Ids.newId();
    UUID myOrder = placeAsShopper(mine, "PICKUP", "");
    UUID theirOrder = placeAsShopper(theirs, "PICKUP", "");

    Response history =
        target
            .path("/orders/mine")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", mine.toString())
            .get();
    String body = history.readEntity(String.class);
    assertThat(history.getStatus(), is(200));
    assertThat(body, containsString(myOrder.toString()));
    assertThat(body, not(containsString(theirOrder.toString())));

    Response theirsById =
        target
            .path("/orders/" + theirOrder)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", mine.toString())
            .get();
    assertThat("a 404, so ids cannot be probed", theirsById.getStatus(), is(404));
  }

  @Test
  @DisplayName("A receipt's email address and a held basket's name go at once, even on open sales")
  void receiptsAndParkedSalesAtOnce() {
    String customer = Ids.newId().toString();
    UUID open = place(customer, "ONLINE", "DELIVERY", DELIVERY);
    // The row an emailed receipt leaves behind. Written directly: sending one calls
    // notification-svc,
    // which is not running here, and the send is not what this test is about.
    UUID receiptId = Ids.newId();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO \"order\".order_receipts"
                    + " (id, tenant_id, order_id, receipt_type, emailed_to, print_count)"
                    + " VALUES (?, ?, ?, 'EMAIL', 'chris@example.com', 1)")) {
      ps.setObject(1, receiptId);
      ps.setObject(2, Ids.parse(T));
      ps.setObject(3, open);
      ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }

    Response parked =
        post(
            "/pos/parked-sales",
            "{\"storeId\":\""
                + S
                + "\",\"customerId\":\""
                + customer
                + "\",\"customerName\":\"Chris Carter\",\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}]}");
    assertThat(parked.getStatus(), is(201));
    UUID parkedId = Ids.parse(id(parked.readEntity(String.class)));

    orderService.handleCustomerErased(Ids.parse(T), Ids.parse(customer), null, Ids.newId());

    assertThat(column("order_receipts", receiptId, "emailed_to"), nullValue());
    assertThat(column("parked_sales", parkedId, "customer_name"), nullValue());
    // The open order itself is still deliverable.
    assertThat(column("orders", open, "delivery_line1"), is("12 High Street"));
  }
}
