package com.storeql.order;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.parse;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finding a sale at the till by its receipt (intent/return-controls.md): the printed fiscal number
 * or the short order reference, only at the caller's stores, only in the caller's business, and
 * never by guessing between two sales.
 */
@HelidonTest
class ReceiptLookupIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(ReceiptLookupIT.T, "USD", "US")
        .with(ReceiptLookupIT.OTHER_T, "USD", "US");
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

  private static final String T = "01a0a2c1-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a2c1-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a2c1-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a2c1-2222-7000-8000-00000000000b";
  private static final String V = "01a0a2c1-3333-7000-8000-000000000001";
  private static final String USER = "01a0a2c1-4444-7000-8000-000000000001";

  @Inject WebTarget target;
  @Inject OrderService orderService;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private Invocation.Builder as(
      String path, String tenant, String roles, String user, String stores) {
    var b = target.path(path).request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (user != null) b = b.header("X-User-Id", user);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return b;
  }

  private Response lookup(String number, String tenant, String roles, String stores) {
    WebTarget t = target.path("/orders/by-receipt");
    if (number != null) t = t.queryParam("number", number);
    var b = t.request(MediaType.APPLICATION_JSON).header("X-Tenant-Id", tenant);
    b = b.header("X-Roles", roles).header("X-User-Id", USER);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return b.get();
  }

  /** A paid two-unit till sale at 10.00 each, so FULFILLED and numbered. */
  private String sale(String tenant, String store) {
    Response r =
        as("/orders", tenant, "MANAGER", USER, null)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"storeId\":\""
                        + store
                        + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":2,\"unitPrice\":10.00}],\"currency\":\"USD\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    String id = parse(body).getJsonObject("data").getString("id");
    orderService.handlePaymentCaptured(
        Ids.parse(tenant), Ids.parse(id), Ids.newId(), new BigDecimal("20.00"));
    return id;
  }

  private String numberOf(String tenant, String order) {
    Response r =
        target
            .path("/orders/" + order + "/fiscal-receipt")
            .queryParam("wait", 10)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "CASHIER")
            .header("X-User-Id", USER)
            .get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonObject("data").getString("fullNumber");
  }

  private static String shortRef(String id) {
    return id.substring(id.length() - 8);
  }

  private static JsonObject found(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonObject("data");
  }

  private static long returnRows(String order) {
    return Long.parseLong(
        scalar(PG, "SELECT count(*) FROM \"order\".returns WHERE order_id='" + order + "'"));
  }

  @Test
  @DisplayName(
      "A sale is found by its printed number and by its short reference, with what can come back")
  void foundByNumberAndByShortReference() {
    String order = sale(T, STORE);
    String number = numberOf(T, order);

    JsonObject byNumber = found(lookup(number, T, "CASHIER", null));
    assertThat(byNumber.getJsonObject("order").getString("id"), is(order));
    assertThat(byNumber.getString("receiptNumber"), is(number));
    JsonObject line = byNumber.getJsonArray("lines").getJsonObject(0);
    assertThat(line.getString("variantId"), is(V));
    assertThat(line.getJsonNumber("soldQty").bigDecimalValue().intValue(), is(2));
    assertThat(line.getJsonNumber("returnedQty").bigDecimalValue().intValue(), is(0));
    assertThat(line.getJsonNumber("returnableQty").bigDecimalValue().intValue(), is(2));
    assertThat(line.getJsonNumber("unitPrice").bigDecimalValue(), is(new BigDecimal("10.00")));

    // Typed by hand: any case, with spaces around it.
    assertThat(
        found(lookup("  " + number.toLowerCase() + " ", T, "STOREKEEPER", null))
            .getJsonObject("order")
            .getString("id"),
        is(order));

    // The short reference the receipt prints, with or without the hash.
    String ref = shortRef(order);
    assertThat(
        found(lookup(ref, T, "CASHIER", null)).getJsonObject("order").getString("id"), is(order));
    assertThat(
        found(lookup("#" + ref.toUpperCase(), T, "CASHIER", null))
            .getJsonObject("order")
            .getString("id"),
        is(order));

    // One comes back: the lookup says one is left.
    Response back =
        as("/orders/" + order + "/returns", T, "CASHIER", USER, null)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"reason\":\"changed mind\",\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"condition\":\"SEALED\"}]}",
                    MediaType.APPLICATION_JSON));
    assertThat(back.getStatus(), is(201));
    JsonObject after =
        found(lookup(number, T, "CASHIER", null)).getJsonArray("lines").getJsonObject(0);
    assertThat(after.getJsonNumber("returnedQty").bigDecimalValue().intValue(), is(1));
    assertThat(after.getJsonNumber("returnableQty").bigDecimalValue().intValue(), is(1));
  }

  @Test
  @DisplayName("A caller held to another store cannot find the sale")
  void aStoreHeldCallerCannotFindAnotherStoresSale() {
    String order = sale(T, STORE);
    String number = numberOf(T, order);
    for (String key : new String[] {number, shortRef(order)}) {
      Response elsewhere = lookup(key, T, "CASHIER", OTHER_STORE);
      assertThat(elsewhere.getStatus(), is(404));
      assertThat(elsewhere.readEntity(String.class), containsString("ORDER_RECEIPT_NOT_FOUND"));
      assertThat(
          found(lookup(key, T, "CASHIER", STORE)).getJsonObject("order").getString("id"),
          is(order));
      assertThat(
          found(lookup(key, T, "CASHIER", STORE + "," + OTHER_STORE))
              .getJsonObject("order")
              .getString("id"),
          is(order));
    }
  }

  @Test
  @DisplayName("Another business's staff of every role find nothing, even naming our store")
  void anotherBusinessFindsNothing() {
    String order = sale(T, STORE);
    String number = numberOf(T, order);
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "MANAGER", "OWNER"}) {
      for (String key : new String[] {number, shortRef(order)}) {
        for (String stores : new String[] {null, STORE}) {
          Response r = lookup(key, OTHER_T, role, stores);
          assertThat(role, r.getStatus(), is(404));
          assertThat(r.readEntity(String.class), containsString("ORDER_RECEIPT_NOT_FOUND"));
        }
      }
    }
    assertThat(returnRows(order), is(0L));
    assertThat(
        scalar(PG, "SELECT status FROM \"order\".orders WHERE id='" + order + "'"),
        is("FULFILLED"));
  }

  @Test
  @DisplayName("Nothing matching is not found; a blank number and a shopper are refused")
  void badLookups() {
    Response none = lookup("NO-SUCH-RECEIPT-1", T, "OWNER", null);
    assertThat(none.getStatus(), is(404));
    assertThat(none.readEntity(String.class), containsString("ORDER_RECEIPT_NOT_FOUND"));
    assertThat(lookup("deadbeef", T, "OWNER", null).getStatus(), is(404));
    assertThat(lookup(null, T, "OWNER", null).getStatus(), is(400));
    assertThat(lookup("   ", T, "OWNER", null).getStatus(), is(400));
    assertThat(lookup("anything", T, "CUSTOMER", null).getStatus(), is(403));
  }

  @Test
  @DisplayName("Two sales sharing a number are never guessed between")
  void ambiguousNumbersAreRefused() {
    String a = sale(T, STORE);
    String b = sale(T, OTHER_STORE);
    String number = numberOf(T, a);
    numberOf(T, b);
    // Two stores may print the same number under different series; make it so.
    exec(
        PG,
        "UPDATE \"order\".fiscal_receipts SET full_number='"
            + number
            + "' WHERE tenant_id='"
            + T
            + "' AND order_id='"
            + b
            + "'");

    Response both = lookup(number, T, "OWNER", null);
    assertThat(both.getStatus(), is(409));
    assertThat(both.readEntity(String.class), containsString("ORDER_RECEIPT_AMBIGUOUS"));

    // A caller held to one store sees only that store's sale, so there is nothing to choose from.
    assertThat(
        found(lookup(number, T, "CASHIER", STORE)).getJsonObject("order").getString("id"), is(a));
    assertThat(
        found(lookup(number, T, "CASHIER", OTHER_STORE)).getJsonObject("order").getString("id"),
        is(b));
  }
}
