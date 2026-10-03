package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
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
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A price is shown in another currency, never charged in it: an order is always placed, totalled
 * and announced in the business's own currency, whatever display currency or currency, or price, a
 * client sends. Tried for businesses in two countries.
 */
@HelidonTest
class DisplayCurrencyIT {

  private static final String T_US = "01a0a1ca-1111-7000-8000-000000000001";
  private static final String T_JP = "01a0a1ca-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a1ca-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0a1ca-4444-7000-8000-000000000001";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T_US, "USD", "US").with(T_JP, "JPY", "JP");
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

  private Response place(String tenant, String extraTop, String extraLine, String key) {
    return rig()
        .post(
            "/orders",
            "{\"storeId\":\""
                + STORE
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V_A
                + "\",\"qty\":2"
                + extraLine
                + "}]"
                + extraTop
                + "}",
            tenant,
            "MANAGER",
            MANAGER,
            key);
  }

  @Test
  @DisplayName("A display currency sent with the basket changes neither the currency nor the total")
  void displayCurrencyIsNeverCharged() {
    for (String[] business : new String[][] {{T_US, "USD"}, {T_JP, "JPY"}}) {
      String other = "USD".equals(business[1]) ? "EUR" : "USD";
      JsonObject order =
          data(
              place(
                  business[0],
                  ",\"displayCurrency\":\"" + other + "\"",
                  ",\"displayCurrency\":\"" + other + "\",\"displayUnitPrice\":0.01",
                  Ids.newId().toString()),
              201);
      assertThat(business[1], order.getString("currency"), is(business[1]));
      // The catalogue's own price for two: what pricing-svc quotes, in the business's currency.
      assertThat(
          order.getJsonNumber("total").bigDecimalValue().compareTo(new BigDecimal("20")), is(0));
      // OrderPlaced names no currency: the order's own row is what every consumer charges from.
      assertThat(
          rig()
              .one(
                  "SELECT currency FROM \"order\".orders WHERE id='" + order.getString("id") + "'"),
          is(business[1]));
    }
  }

  @Test
  @DisplayName("Naming the display currency as the order's currency is refused and writes nothing")
  void chargingInTheDisplayCurrencyIsRefused() {
    long before = rig().count("orders", "tenant_id='" + T_US + "'");
    long beforeJp = rig().count("orders", "tenant_id='" + T_JP + "'");
    Response r = place(T_US, ",\"currency\":\"EUR\"", "", Ids.newId().toString());
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("ORDER_CURRENCY_MISMATCH"));
    // The yen business asked for dollars, and the dollar business for yen.
    assertThat(
        place(T_JP, ",\"currency\":\"USD\"", "", Ids.newId().toString()).getStatus(), is(400));
    assertThat(
        place(T_US, ",\"currency\":\"JPY\"", "", Ids.newId().toString()).getStatus(), is(400));
    assertThat(rig().count("orders", "tenant_id='" + T_US + "'"), is(before));
    assertThat(rig().count("orders", "tenant_id='" + T_JP + "'"), is(beforeJp));
  }

  @Test
  @DisplayName("A client's own unit price, in any currency, is ignored while pricing is enforced")
  void aClientPriceIsNotCharged() {
    JsonObject order =
        data(
            place(T_US, ",\"currency\":\"USD\"", ",\"unitPrice\":0.01", Ids.newId().toString()),
            201);
    assertThat(
        order.getJsonNumber("total").bigDecimalValue().compareTo(new BigDecimal("20")), is(0));
  }
}
