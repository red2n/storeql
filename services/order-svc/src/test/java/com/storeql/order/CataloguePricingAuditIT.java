package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who priced an order that was placed without prices (SJ-D41), and when: the price is given by a
 * manager, the history says who and the audit trail carries it as a PRICED entry, held to the
 * caller's business and stores like every other entry.
 */
@HelidonTest
class CataloguePricingAuditIT {

  private static final String T = "01a0a1c9-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c9-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a1c9-2222-7000-8000-00000000000a";
  private static final String STORE_2 = "01a0a1c9-2222-7000-8000-00000000000b";
  private static final String MANAGER = "01a0a1c9-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c9-4444-7000-8000-000000000002";

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US").with(OTHER_T, "USD", "US");
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

  @Inject WebTarget target;
  @Inject OrderService orderService;

  private ReturnsRig rig;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    return rig;
  }

  private String awaitingPrice() {
    JsonObject o =
        data(
            rig()
                .post(
                    "/orders",
                    "{\"storeId\":\""
                        + STORE
                        + "\",\"channel\":\"POS\",\"fulfilmentType\":\"PICKUP\","
                        + "\"awaitingPrice\":true,\"items\":[{\"variantId\":\""
                        + V_A
                        + "\",\"qty\":3,\"unitPrice\":0}],\"currency\":\"USD\"}",
                    T,
                    "CASHIER",
                    CASHIER,
                    com.storeql.ids.Ids.newId().toString()),
            201);
    assertThat(o.getString("status"), is("AWAITING_PRICE"));
    return o.getString("id");
  }

  private static String priceBody() {
    return "{\"lines\":[{\"variantId\":\"" + V_A + "\",\"unitPrice\":4.50}],\"taxAmount\":2.70}";
  }

  private JsonArray priced(String tenant, String role, String stores, String extra) {
    Response r =
        rig()
            .getHeld(
                "/admin/audit/events?type=PRICED" + extra,
                tenant,
                role,
                com.storeql.ids.Ids.newId().toString(),
                stores);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Envelopes.parse(body).getJsonArray("data");
  }

  private static boolean has(JsonArray rows, String order) {
    return rows.stream().anyMatch(v -> order.equals(v.asJsonObject().getString("orderId")));
  }

  @Test
  @DisplayName("Pricing records who priced the order and when, in the history and on the trail")
  void pricingIsAttributed() {
    String order = awaitingPrice();
    Response done =
        rig().post("/orders/" + order + "/price", priceBody(), T, "MANAGER", MANAGER, null);
    assertThat(done.readEntity(String.class), done.getStatus(), is(200));

    assertThat(
        rig()
            .one(
                "SELECT changed_by FROM \"order\".order_status_history WHERE order_id='"
                    + order
                    + "' AND from_status='AWAITING_PRICE' AND to_status='PENDING'"),
        is(MANAGER));

    JsonArray rows = priced(T, "OWNER", null, "");
    JsonObject entry =
        rows.stream()
            .map(v -> v.asJsonObject())
            .filter(o -> order.equals(o.getString("orderId")))
            .findFirst()
            .orElseThrow();
    assertThat(entry.getString("type"), is("PRICED"));
    assertThat(entry.getString("actorId"), is(MANAGER));
    assertThat(entry.getString("storeId"), is(STORE));
    assertThat(entry.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("16.20")));
    assertThat(entry.getString("reason"), is("priced: total 16.20"));

    // Priced once: a second attempt is refused and the trail still has the one entry.
    Response again =
        rig().post("/orders/" + order + "/price", priceBody(), T, "OWNER", CASHIER, null);
    assertThat(again.getStatus(), is(409));
    assertThat(
        priced(T, "OWNER", null, "").stream()
            .filter(v -> order.equals(v.asJsonObject().getString("orderId")))
            .count(),
        is(1L));
    // The same type filter finds nothing that is not a pricing.
    assertThat(
        priced(T, "OWNER", null, "").stream()
            .allMatch(v -> "PRICED".equals(v.asJsonObject().getString("type"))),
        is(true));
  }

  @Test
  @DisplayName("A cashier cannot price, and a refusal leaves no entry")
  void aCashierCannotPrice() {
    String order = awaitingPrice();
    Response r =
        rig().post("/orders/" + order + "/price", priceBody(), T, "CASHIER", CASHIER, null);
    assertThat(r.getStatus(), is(403));
    assertThat(has(priced(T, "OWNER", null, ""), order), is(false));
    assertThat(
        rig().one("SELECT status FROM \"order\".orders WHERE id='" + order + "'"),
        is("AWAITING_PRICE"));
  }

  @Test
  @DisplayName("The entry is seen only by the business and the stores it belongs to")
  void tenantAndStoreIsolation() {
    String order = awaitingPrice();
    assertThat(
        rig()
            .post("/orders/" + order + "/price", priceBody(), T, "MANAGER", MANAGER, null)
            .getStatus(),
        is(200));

    // Another business's management of every role that may read the trail, naming our store.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      assertThat(role, priced(OTHER_T, role, STORE, "&store=" + STORE).size(), is(0));
      assertThat(role, has(priced(OTHER_T, role, null, ""), order), is(false));
    }
    // Their staff cannot price our order either.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Response r =
          rig().post("/orders/" + order + "/price", priceBody(), OTHER_T, role, MANAGER, null);
      assertThat(role, r.getStatus(), is(404));
    }
    // A manager held to another store reads nothing of ours, and cannot ask for our store.
    assertThat(has(priced(T, "MANAGER", STORE_2, ""), order), is(false));
    Response asked =
        rig()
            .getHeld(
                "/admin/audit/events?type=PRICED&store=" + STORE, T, "MANAGER", MANAGER, STORE_2);
    assertThat(asked.getStatus(), is(403));
    // A cashier reads no trail at all.
    assertThat(
        rig().getHeld("/admin/audit/events?type=PRICED", T, "CASHIER", CASHIER, null).getStatus(),
        is(403));
  }
}
