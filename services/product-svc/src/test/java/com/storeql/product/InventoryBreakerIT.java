package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A supplier import when inventory-svc is listed in discovery but gives no answer (2 Oct 2026).
 *
 * <p>The client's circuit breaker counted only what its method threw, and it threw nothing: every
 * chunk's failure was swallowed and reported, so a dead inventory-svc never opened the breaker and
 * each import waited out the timeouts, chunk after chunk. Here discovery lists inventory-svc at a
 * port nobody listens on: each import stops at its first chunk and says so, the breaker hears of
 * each one, and once it opens an import is told at once that inventory-svc is not being asked.
 */
@HelidonTest
class InventoryBreakerIT {

  private static final String T = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String PATH = "/admin/import/supplier-csv";

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final JsonStub CONSUL;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "product");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
    TenantSvcStub.start().with(T, "GBP", "GB").withStore(T, STORE, "GB");

    // inventory-svc as discovery lists it, at a port that was free a moment ago and is closed now.
    JsonStub gone = JsonStub.start();
    URI dead = URI.create(gone.baseUrl());
    gone.close();
    CONSUL = JsonStub.start();
    CONSUL.on(
        "GET",
        "/v1/health/service/inventory-svc",
        200,
        "[{\"Node\":{\"Address\":\""
            + dead.getHost()
            + "\"},\"Service\":{\"Address\":\""
            + dead.getHost()
            + "\",\"Port\":"
            + dead.getPort()
            + "}}]");
    URI consul = URI.create(CONSUL.baseUrl());
    System.setProperty("storeql.consul.host", consul.getHost());
    System.setProperty("storeql.consul.port", String.valueOf(consul.getPort()));
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    try {
      CONSUL.close();
      System.clearProperty("storeql.consul.host");
      System.clearProperty("storeql.consul.port");
    } finally {
      try {
        PG.stop();
      } finally {
        REDIS.stop();
      }
    }
  }

  private Response importQuantities(String name, String sku) {
    String json =
        "{\"csv\":\"Product ID,Product Description,Quantity\\n"
            + sku
            + ","
            + name
            + ",5\",\"storeId\":\""
            + STORE
            + "\"}";
    return target
        .path(PATH)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", Ids.newId().toString())
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String body(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private static int count(String sql, String... params) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  private static final String NAMED =
      "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid AND name = ?";

  @Test
  @DisplayName("A dead inventory-svc is reported per import, and then the breaker opens on it")
  void aDeadInventoryOpensTheBreaker() {
    // The breaker's window is five calls, and it opens at 60% failures: five imports that could not
    // reach inventory-svc. Each is the catalogue imported, with its stock reported as not received.
    for (int i = 0; i < 5; i++) {
      String name = "Tea " + Ids.newId();
      String answer = body(importQuantities(name, "BRK-" + Ids.newId()), 200);
      assertThat(answer, containsString("\"productsCreated\":1"));
      assertThat(answer, containsString("\"stockReceived\":0"));
      assertThat(
          answer, containsString("1 lines not received: inventory-svc could not be reached"));
      assertThat("the catalogue is kept", count(NAMED, T, name), is(1));
    }

    // Open: the next import is not kept waiting on inventory-svc, and is told why.
    String name = "Tea " + Ids.newId();
    String answer = body(importQuantities(name, "BRK-" + Ids.newId()), 200);
    assertThat(answer, containsString("\"stockReceived\":0"));
    assertThat(
        answer,
        containsString(
            "1 lines not received: inventory-svc is not being asked for now, after too many recent"
                + " failures"));
    assertThat(count(NAMED, T, name), is(1));
  }
}
