package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * A transaction does its reads on its own connection. With a pool of two, several consumes in
 * flight at once used to hold both connections and each wait for a third (a pool deadlock that
 * ended in a connection timeout); on one connection each, they all finish.
 */
@HelidonTest
class PoolConnectionsIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "inventory");
    System.setProperty("storeql.db.pool-max-size", "2");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    System.clearProperty("storeql.db.pool-max-size");
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

  @Test
  void concurrentConsumesFinishOnAPoolOfTwo() throws Exception {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID variant = Ids.newId();
    assertThat(
        post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + store
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"qty\":100,\"batchNo\":\"P1\"}",
                tenant)
            .getStatus(),
        is(201));
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Response r =
          post(
              "/inventory/reservations",
              "{\"storeId\":\""
                  + store
                  + "\",\"variantId\":\""
                  + variant
                  + "\",\"qty\":1,\"orderId\":\""
                  + Ids.newId()
                  + "\"}",
              tenant);
      assertThat(r.getStatus(), is(201));
      String body = r.readEntity(String.class);
      int at = body.indexOf("\"id\":\"") + 6;
      ids.add(body.substring(at, body.indexOf('"', at)));
    }
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<Integer>> done = new ArrayList<>();
      for (String id : ids) {
        Callable<Integer> call =
            () -> post("/inventory/reservations/" + id + "/consume", "", tenant).getStatus();
        done.add(pool.submit(call));
      }
      for (Future<Integer> f : done) {
        assertThat(f.get(), is(200));
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
