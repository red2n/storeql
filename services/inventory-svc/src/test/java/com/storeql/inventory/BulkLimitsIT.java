package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * A bulk call takes at most the configured number of lines; beyond it is a 400 and nothing moves.
 */
@HelidonTest
class BulkLimitsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  static {
    System.setProperty("storeql.inventory.bulk.receive-max-lines", "2");
    System.setProperty("storeql.inventory.bulk.reserve-max-lines", "2");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    System.clearProperty("storeql.inventory.bulk.receive-max-lines");
    System.clearProperty("storeql.inventory.bulk.reserve-max-lines");
    PG.stop();
  }

  private Response post(String path, String json, UUID tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant.toString())
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String line(UUID store, UUID variant) {
    return "{\"storeId\":\"" + store + "\",\"variantId\":\"" + variant + "\",\"qty\":1}";
  }

  @Test
  void bulkReceiveAndReserveRefuseMoreLinesThanTheLimit() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID variant = Ids.newId();
    String three = line(store, variant) + "," + line(store, variant) + "," + line(store, variant);

    Response receive =
        post("/admin/inventory/receive/batch", "{\"items\":[" + three + "]}", tenant);
    String body = receive.readEntity(String.class);
    assertThat(body, receive.getStatus(), is(400));
    assertThat(body, containsString("INVENTORY_BULK_TOO_LARGE"));

    Response reserve =
        post("/inventory/reservations/batch", "{\"reservations\":[" + three + "]}", tenant);
    body = reserve.readEntity(String.class);
    assertThat(body, reserve.getStatus(), is(400));
    assertThat(body, containsString("INVENTORY_BULK_TOO_LARGE"));

    // nothing moved: no stock was booked in
    Response levels =
        target
            .path("/admin/inventory/levels")
            .queryParam("store", store.toString())
            .request()
            .header("X-Tenant-Id", tenant.toString())
            .header("X-Roles", "OWNER")
            .get();
    assertThat(levels.readEntity(String.class), not(containsString(variant.toString())));

    // at the limit it works
    String two = line(store, variant) + "," + line(store, variant);
    assertThat(
        post("/admin/inventory/receive/batch", "{\"items\":[" + two + "]}", tenant).getStatus(),
        is(200));
  }
}
