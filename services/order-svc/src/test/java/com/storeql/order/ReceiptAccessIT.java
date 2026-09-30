package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may make a copy of a receipt and read the log of copies: staff at the sale's store, and
 * nobody else. A shopper, a caller of another store and another business's staff of every role get
 * a refusal or a 404, and no receipt row is written.
 */
@HelidonTest
class ReceiptAccessIT {

  private static final String T = "01a0a1c6-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c6-1111-7000-8000-000000000003";
  private static final String STORE = "01a0a1c6-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1c6-2222-7000-8000-00000000000b";
  private static final String MANAGER = "01a0a1c6-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c6-4444-7000-8000-000000000002";
  private static final String SHOPPER = "01a0a1c6-4444-7000-8000-000000000009";
  private static final String[] ROLES = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER"};

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US").with(OTHER_T, "USD", "US");
    PRICING = ReturnsRig.pricing(new AtomicBoolean(false));
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService orderService;

  private ReturnsRig rig;

  @AfterAll
  static void stop() {
    System.clearProperty("storeql.order.pricing.enforce");
    PRICING.close();
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    return rig;
  }

  private long copies(String order) {
    return rig().count("order_receipts", "order_id='" + order + "'");
  }

  @Test
  @DisplayName("A cashier at the sale's store makes a printed copy, and the log lists it")
  void cashierAtTheStoreMakesACopy() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    Response made =
        rig()
            .postHeld(
                "/admin/orders/" + order + "/receipts",
                "{\"receiptType\":\"PRINT\",\"printCount\":2}",
                T,
                "CASHIER",
                CASHIER,
                STORE);
    assertThat(made.readEntity(String.class), made.getStatus(), is(201));
    assertThat(copies(order), is(1L));

    // Reading the log is management's, and at the sale's store.
    Response log =
        rig().getHeld("/admin/orders/" + order + "/receipts", T, "MANAGER", MANAGER, STORE);
    assertThat(log.getStatus(), is(200));
    assertThat(log.readEntity(String.class), containsString("PRINT"));
  }

  @Test
  @DisplayName("Staff held to another store can neither make a copy nor read the log")
  void staffOfAnotherStoreAreRefused() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    for (String type : new String[] {"PRINT", "EMAIL"}) {
      Response r =
          rig()
              .postHeld(
                  "/admin/orders/" + order + "/receipts",
                  "{\"receiptType\":\"" + type + "\",\"emailedTo\":\"someone@example.org\"}",
                  T,
                  "CASHIER",
                  CASHIER,
                  OTHER_STORE);
      assertThat(type, r.getStatus(), is(403));
      assertThat(r.readEntity(String.class), containsString("STORE_ACCESS_DENIED"));
    }
    Response log =
        rig().getHeld("/admin/orders/" + order + "/receipts", T, "MANAGER", MANAGER, OTHER_STORE);
    assertThat(log.getStatus(), is(403));
    assertThat(copies(order), is(0L));
  }

  @Test
  @DisplayName("A shopper can neither make a copy of a receipt nor read the log of copies")
  void aShopperIsRefused() {
    String order = rig().sale(T, STORE, V_A, 1, SHOPPER, MANAGER);
    Response make =
        rig()
            .postHeld(
                "/admin/orders/" + order + "/receipts",
                "{\"receiptType\":\"EMAIL\",\"emailedTo\":\"me@example.org\"}",
                T,
                "CUSTOMER",
                SHOPPER,
                null);
    assertThat(make.getStatus(), is(403));
    assertThat(
        rig()
            .getHeld("/admin/orders/" + order + "/receipts", T, "CUSTOMER", SHOPPER, null)
            .getStatus(),
        is(403));
    assertThat(copies(order), is(0L));
  }

  @Test
  @DisplayName("Another business's staff of every role, naming our order and store, move nothing")
  void otherBusinessGetsNothing() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    for (String role : ROLES) {
      Response make =
          rig()
              .postHeld(
                  "/admin/orders/" + order + "/receipts",
                  "{\"receiptType\":\"PRINT\"}",
                  OTHER_T,
                  role,
                  Ids.newId().toString(),
                  STORE);
      assertThat(role, make.getStatus() == 404 || make.getStatus() == 403, is(true));
      Response log =
          rig()
              .getHeld(
                  "/admin/orders/" + order + "/receipts",
                  OTHER_T,
                  role,
                  Ids.newId().toString(),
                  STORE);
      assertThat(role, log.getStatus() == 404 || log.getStatus() == 403, is(true));
    }
    assertThat(copies(order), is(0L));
  }
}
