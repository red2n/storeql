package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parked sales: park, list, resume and discard, each held to the caller's stores and business, and
 * each finished sale kept with who finished it. Tried with the wrong role, the wrong store and the
 * wrong business beside the right ones.
 */
@HelidonTest
class ParkedSaleIT {

  private static final String T = "01a0a1c8-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c8-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a1c8-2222-7000-8000-00000000000a";
  private static final String STORE_2 = "01a0a1c8-2222-7000-8000-00000000000b";
  private static final String ALICE = "01a0a1c8-4444-7000-8000-000000000001";
  private static final String BOB = "01a0a1c8-4444-7000-8000-000000000002";
  private static final String[] ROLES = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER"};

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

  private static String parkBody(String store, String name) {
    return "{\"storeId\":\""
        + store
        + "\",\"customerName\":\""
        + name
        + "\",\"items\":[{\"variantId\":\""
        + V_A
        + "\",\"qty\":2,\"unitPrice\":12.50,\"discountAmount\":1.00}]}";
  }

  private JsonObject parked(String store, String user) {
    return data(
        rig().post("/pos/parked-sales", parkBody(store, "Queue"), T, "CASHIER", user, null), 201);
  }

  private Response act(
      String method, String path, String tenant, String role, String user, String stores) {
    var b = rig().as(path, tenant, role, user, stores);
    return switch (method) {
      case "GET" -> b.get();
      case "DELETE" -> b.delete();
      default -> b.post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    };
  }

  private JsonArray listOf(String query, String tenant, String role, String user, String stores) {
    Response r = act("GET", "/pos/parked-sales" + query, tenant, role, user, stores);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Envelopes.parse(body).getJsonArray("data");
  }

  private static boolean hasId(JsonArray list, String id) {
    return list.stream().anyMatch(v -> id.equals(v.asJsonObject().getString("id")));
  }

  private String column(String id, String column) {
    return rig().one("SELECT " + column + " FROM \"order\".parked_sales WHERE id='" + id + "'");
  }

  @Test
  @DisplayName("Park, list and read: the basket is totalled and named for who parked it")
  void parkListAndRead() {
    JsonObject sale = parked(STORE, ALICE);
    String id = sale.getString("id");
    // 2 x 12.50 less 1.00 off the line.
    assertThat(sale.getJsonNumber("subtotal").bigDecimalValue(), is(new BigDecimal("24.00")));
    assertThat(sale.getJsonNumber("discountAmount").bigDecimalValue(), is(new BigDecimal("1.00")));
    assertThat(sale.getString("parkedBy"), is(ALICE));
    assertThat(sale.getJsonArray("items").size(), is(1));

    assertThat(hasId(listOf("?storeId=" + STORE, T, "CASHIER", BOB, null), id), is(true));
    assertThat(hasId(listOf("?storeId=" + STORE_2, T, "CASHIER", BOB, null), id), is(false));
    Response one = act("GET", "/pos/parked-sales/" + id, T, "CASHIER", BOB, null);
    assertThat(one.getStatus(), is(200));
  }

  @Test
  @DisplayName("The open list gives each parked sale its own lines, read together")
  void theListCarriesEachSalesOwnLines() {
    String one = parked(STORE, ALICE).getString("id");
    String two =
        data(
                rig()
                    .post(
                        "/pos/parked-sales",
                        "{\"storeId\":\""
                            + STORE
                            + "\",\"customerName\":\"Two\",\"items\":["
                            + "{\"variantId\":\""
                            + V_A
                            + "\",\"qty\":1,\"unitPrice\":5.00},"
                            + "{\"variantId\":\""
                            + V_A
                            + "\",\"qty\":3,\"unitPrice\":2.00}]}",
                        T,
                        "CASHIER",
                        ALICE,
                        null),
                201)
            .getString("id");
    JsonArray list = listOf("?storeId=" + STORE, T, "CASHIER", BOB, null);
    for (var v : list) {
      JsonObject sale = v.asJsonObject();
      if (sale.getString("id").equals(one)) {
        assertThat(sale.getJsonArray("items").size(), is(1));
      } else if (sale.getString("id").equals(two)) {
        assertThat(sale.getJsonArray("items").size(), is(2));
      }
    }
    assertThat(hasId(list, one) && hasId(list, two), is(true));
  }

  @Test
  @DisplayName("A basket with no lines cannot be parked, and a storekeeper or shopper cannot park")
  void badInputAndWrongRoles() {
    Response empty =
        rig()
            .post(
                "/pos/parked-sales",
                "{\"storeId\":\"" + STORE + "\",\"items\":[]}",
                T,
                "CASHIER",
                ALICE,
                null);
    assertThat(empty.getStatus(), is(400));
    assertThat(empty.readEntity(String.class), containsString("PARK_EMPTY"));

    long before = rig().count("parked_sales", "tenant_id='" + T + "'");
    for (String role : new String[] {"STOREKEEPER", "CUSTOMER"}) {
      Response park = rig().post("/pos/parked-sales", parkBody(STORE, "x"), T, role, ALICE, null);
      assertThat(role, park.getStatus(), is(403));
      assertThat(role, act("GET", "/pos/parked-sales", T, role, ALICE, null).getStatus(), is(403));
    }
    assertThat(rig().count("parked_sales", "tenant_id='" + T + "'"), is(before));
  }

  @Test
  @DisplayName("Resuming takes the sale off the list and records who picked it up, once")
  void resumeRecordsWhoPickedItUp() {
    JsonObject sale = parked(STORE, ALICE);
    String id = sale.getString("id");

    // Bob picks up what Alice parked.
    JsonObject resumed =
        data(act("POST", "/pos/parked-sales/" + id + "/resume", T, "CASHIER", BOB, null), 200);
    assertThat(resumed.getString("parkedBy"), is(ALICE));
    assertThat(resumed.getString("resumedBy"), is(BOB));
    assertThat(resumed.getJsonArray("items").size(), is(1));
    assertThat(column(id, "resumed_by"), is(BOB));
    assertThat(column(id, "cashier_id"), is(ALICE));

    assertThat(hasId(listOf("?storeId=" + STORE, T, "CASHIER", BOB, null), id), is(false));
    assertThat(act("GET", "/pos/parked-sales/" + id, T, "CASHIER", BOB, null).getStatus(), is(404));

    // A second till cannot pick it up again, and it cannot be thrown away once resumed: a sale
    // that is no longer held is not found (409 PARKED_SALE_NOT_OPEN is only for two tills racing).
    Response again = act("POST", "/pos/parked-sales/" + id + "/resume", T, "CASHIER", ALICE, null);
    assertThat(again.getStatus(), is(404));
    assertThat(again.readEntity(String.class), containsString("PARKED_SALE_NOT_FOUND"));
    assertThat(
        act("DELETE", "/pos/parked-sales/" + id, T, "CASHIER", ALICE, null).getStatus(), is(404));
    assertThat(column(id, "resumed_by"), is(BOB));
    assertThat(column(id, "discarded_by"), nullValue());
  }

  @Test
  @DisplayName("Discarding keeps the row with who threw it away, and it cannot be resumed after")
  void discardRecordsWho() {
    JsonObject sale = parked(STORE, ALICE);
    String id = sale.getString("id");

    Response gone = act("DELETE", "/pos/parked-sales/" + id, T, "MANAGER", BOB, null);
    assertThat(gone.getStatus(), is(204));
    assertThat(column(id, "discarded_by"), is(BOB));
    assertThat(column(id, "resumed_by"), nullValue());
    assertThat(rig().count("parked_sales", "id='" + id + "' AND discarded_at IS NOT NULL"), is(1L));
    assertThat(rig().count("parked_sale_items", "sale_id='" + id + "'"), is(1L));

    assertThat(hasId(listOf("?storeId=" + STORE, T, "CASHIER", BOB, null), id), is(false));
    assertThat(act("GET", "/pos/parked-sales/" + id, T, "CASHIER", BOB, null).getStatus(), is(404));
    assertThat(
        act("POST", "/pos/parked-sales/" + id + "/resume", T, "CASHIER", BOB, null).getStatus(),
        is(404));
    assertThat(
        act("DELETE", "/pos/parked-sales/" + id, T, "CASHIER", BOB, null).getStatus(), is(404));
    assertThat(column(id, "discarded_by"), is(BOB));
  }

  @Test
  @DisplayName("A caller held to another store cannot park, list, read, resume or discard here")
  void storeScoping() {
    JsonObject sale = parked(STORE, ALICE);
    String id = sale.getString("id");
    JsonObject other = parked(STORE_2, ALICE);

    Response park =
        rig().postHeld("/pos/parked-sales", parkBody(STORE, "x"), T, "CASHIER", BOB, STORE_2);
    assertThat(park.getStatus(), is(403));
    assertThat(park.readEntity(String.class), containsString("STORE_ACCESS_DENIED"));

    // Naming our store is refused; naming none lists only their own store's sales.
    assertThat(
        act("GET", "/pos/parked-sales?storeId=" + STORE, T, "CASHIER", BOB, STORE_2).getStatus(),
        is(403));
    JsonArray theirs = listOf("", T, "CASHIER", BOB, STORE_2);
    assertThat(hasId(theirs, id), is(false));
    assertThat(hasId(theirs, other.getString("id")), is(true));

    assertThat(
        act("GET", "/pos/parked-sales/" + id, T, "CASHIER", BOB, STORE_2).getStatus(), is(403));
    assertThat(
        act("POST", "/pos/parked-sales/" + id + "/resume", T, "CASHIER", BOB, STORE_2).getStatus(),
        is(403));
    assertThat(
        act("DELETE", "/pos/parked-sales/" + id, T, "CASHIER", BOB, STORE_2).getStatus(), is(403));
    // Nothing moved, and a caller held to no store still sees it.
    assertThat(column(id, "resumed_by"), nullValue());
    assertThat(column(id, "discarded_by"), nullValue());
    assertThat(hasId(listOf("", T, "OWNER", ALICE, null), id), is(true));
  }

  @Test
  @DisplayName("Another business's staff of every role, naming our sale and store, move nothing")
  void otherBusinessMovesNothing() {
    JsonObject sale = parked(STORE, ALICE);
    String id = sale.getString("id");
    for (String role : ROLES) {
      boolean pos = !"STOREKEEPER".equals(role) && !"CUSTOMER".equals(role);
      String who = Ids.newId().toString();
      Response read = act("GET", "/pos/parked-sales/" + id, OTHER_T, role, who, STORE);
      Response resume =
          act("POST", "/pos/parked-sales/" + id + "/resume", OTHER_T, role, who, STORE);
      Response discard = act("DELETE", "/pos/parked-sales/" + id, OTHER_T, role, who, STORE);
      for (Response r : new Response[] {read, resume, discard}) {
        assertThat(role, r.getStatus(), is(pos ? 404 : 403));
      }
      if (pos) {
        assertThat(
            role, hasId(listOf("?storeId=" + STORE, OTHER_T, role, who, null), id), is(false));
      }
    }
    assertThat(column(id, "resumed_by"), nullValue());
    assertThat(column(id, "discarded_by"), nullValue());
    assertThat(hasId(listOf("?storeId=" + STORE, T, "CASHIER", BOB, null), id), is(true));
  }
}
