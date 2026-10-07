package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.pricing.messaging.CatalogueEventHandler;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A CATEGORY-scoped promotion is resolved on the one connection the scope read holds: the pool here
 * has a single connection, so a second checkout from inside the row mapper (the old shape) could
 * never be served and the read would fail after the connection timeout.
 */
@HelidonTest
class CategoryScopePoolIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final String T = Ids.newId().toString();

  static {
    PG = PostgresSupport.start();
    TENANTS = TenantSvcStub.start().with(T, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.db.pool-max-size", "1");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject CatalogueEventHandler catalogue;
  @Inject PricingRepository repo;

  @AfterAll
  static void stopDb() {
    System.clearProperty("storeql.db.pool-max-size");
    PG.stop();
  }

  private Response post(String path, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("a category scope is resolved to its variants without a second connection")
  void categoryScopeNeedsOneConnection() {
    UUID category = Ids.newId();
    UUID product = Ids.newId();
    UUID variant = Ids.newId();
    String event =
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"ProductCategorised\",\"tenantId\":\""
            + T
            + "\",\"aggregateId\":\""
            + product
            + "\",\"occurredAt\":\"2026-09-13T00:00:00Z\",\"productId\":\""
            + product
            + "\",\"categoryPath\":[\""
            + category
            + "\"],\"variantIds\":[\""
            + variant
            + "\"]}";
    assertThat(catalogue.handle(event), is(true));

    Response created =
        post(
            "/admin/promotions",
            "{\"name\":\"Cat\",\"type\":\"PERCENT\",\"value\":10,\"startsAt\":\"2020-01-01T00:00:00Z\"}");
    String body = created.readEntity(String.class);
    int start = body.indexOf("\"id\":\"") + 6;
    String promo = body.substring(start, body.indexOf('"', start));
    Response scoped =
        post(
            "/admin/promotions/" + promo + "/items",
            "{\"scopeType\":\"CATEGORY\",\"scopeId\":\"" + category + "\"}");
    assertThat(scoped.readEntity(String.class), scoped.getStatus(), is(201));

    var scopes = repo.findPromotionVariantScopes(Ids.parse(T), List.of(Ids.parse(promo)));
    assertThat(List.copyOf(scopes.get(Ids.parse(promo))), contains(variant));
  }
}
