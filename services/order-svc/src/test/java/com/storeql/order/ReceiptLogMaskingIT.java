package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
 * The receipt copy log masks the address a copy was emailed to (till-sessions slice 9): management
 * at the sale's store reads it whole, everyone else reads {@code j***@example.org}; the row itself
 * keeps the address whole.
 */
@HelidonTest
class ReceiptLogMaskingIT {

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

  private static final String ADDRESS = "jane.doe@example.org";
  private static final String MASKED = "j***@example.org";

  private static void assertMasked(String body) {
    assertThat(body, containsString(MASKED));
    assertThat(body, not(containsString(ADDRESS)));
  }

  @Test
  @DisplayName("A cashier's own copy answers masked; management at the store reads it whole")
  void maskedForTheCashierWholeForManagement() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    Response made =
        rig()
            .postHeld(
                "/admin/orders/" + order + "/receipts",
                "{\"receiptType\":\"PRINT\",\"emailedTo\":\"" + ADDRESS + "\"}",
                T,
                "CASHIER",
                CASHIER,
                STORE);
    String madeBody = made.readEntity(String.class);
    assertThat(madeBody, made.getStatus(), is(201));
    assertMasked(madeBody);

    // The row keeps the address whole: masking is what a reader is shown, not what is kept.
    assertThat(
        rig().one("SELECT emailed_to FROM \"order\".order_receipts WHERE order_id='" + order + "'"),
        is(ADDRESS));

    for (String role : new String[] {"MANAGER", "OWNER"}) {
      Response log = rig().getHeld("/admin/orders/" + order + "/receipts", T, role, MANAGER, STORE);
      String body = log.readEntity(String.class);
      assertThat(role + body, log.getStatus(), is(200));
      assertThat(role, body, containsString(ADDRESS));
    }
    // Whoever else the filter lets read the log reads it masked, never whole.
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Response log = rig().getHeld("/admin/orders/" + order + "/receipts", T, role, CASHIER, STORE);
      if (log.getStatus() == 200) assertMasked(log.readEntity(String.class));
      else assertThat(role, log.getStatus(), is(403));
    }
  }

  @Test
  @DisplayName("Management held to another store, and another business's staff, read nothing")
  void noAddressLeavesTheStoreOrTheBusiness() {
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    rig()
        .postHeld(
            "/admin/orders/" + order + "/receipts",
            "{\"receiptType\":\"PRINT\",\"emailedTo\":\"" + ADDRESS + "\"}",
            T,
            "MANAGER",
            MANAGER,
            STORE);
    Response elsewhere =
        rig().getHeld("/admin/orders/" + order + "/receipts", T, "MANAGER", MANAGER, OTHER_STORE);
    assertThat(elsewhere.getStatus(), is(403));
    assertThat(elsewhere.readEntity(String.class), not(containsString(ADDRESS)));
    for (String role : ROLES) {
      Response theirs =
          rig()
              .getHeld(
                  "/admin/orders/" + order + "/receipts",
                  OTHER_T,
                  role,
                  Ids.newId().toString(),
                  STORE);
      assertThat(role, theirs.getStatus() == 404 || theirs.getStatus() == 403, is(true));
      assertThat(role, theirs.readEntity(String.class), not(containsString(ADDRESS)));
    }
  }
}
