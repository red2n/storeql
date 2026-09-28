package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.iam.messaging.StoreTypeHandler;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * No till opens at a dark store (ship-from-store and dark-store picking): iam-svc learns each
 * store's type from tenant-svc's StoreStatusChanged and refuses a session there; a shop's opens,
 * and a store whose type was never announced is taken for a shop.
 */
@HelidonTest
class PosSessionStoreTypeIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.jwt.secret", "integration-test-secret-of-at-least-32-chars");
  }

  private static final UUID TENANT = Ids.newId();
  private static final UUID SHOP = Ids.newId();
  private static final UUID DARK = Ids.newId();
  private static final UUID UNKNOWN = Ids.newId();
  private static final UUID CASHIER = Ids.newId();

  @Inject WebTarget target;
  @Inject StoreTypeHandler storeTypes;
  @Inject com.storeql.service.StoreStatusChangedHandler storeStatus;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static String announced(UUID storeId, String type, Instant at) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"StoreStatusChanged\",\"tenantId\":\""
        + TENANT
        + "\",\"aggregateId\":\""
        + storeId
        + "\",\"occurredAt\":\""
        + at
        + "\",\"storeId\":\""
        + storeId
        + "\",\"status\":\"ACTIVE\""
        + (type == null ? "" : ",\"type\":\"" + type + "\"")
        + "}";
  }

  private Response start(UUID storeId) {
    return startAs(TENANT, storeId);
  }

  private Response startAs(UUID tenant, UUID storeId) {
    return target
        .path("/auth/pos/sessions")
        .request()
        .header("X-Tenant-Id", tenant.toString())
        .header("X-User-Id", CASHIER.toString())
        .header("X-Roles", "CASHIER")
        .post(Entity.entity("{\"storeId\":\"" + storeId + "\"}", MediaType.APPLICATION_JSON));
  }

  /** The announcement reaches both projections, as the consumer delivers it. */
  private void announce(UUID storeId, String type, Instant at) {
    String json = announced(storeId, type, at);
    storeStatus.handle(json);
    storeTypes.handle(json);
  }

  @Test
  void aTillOpensAtAShopAndNeverAtADarkStore() {
    Instant now = Instant.now();
    announce(SHOP, "STORE", now);
    announce(DARK, "DARK_STORE", now);
    announce(UNKNOWN, null, now);

    assertThat(start(SHOP).getStatus(), is(201));
    assertThat(start(UNKNOWN).getStatus(), is(201));
    Response refused = start(DARK);
    String body = refused.readEntity(String.class);
    assertThat(body, refused.getStatus(), is(409));
    assertThat(body, containsString("POS_STORE_HAS_NO_TILL"));

    // Another business's cashier, naming our shop, our dark store or the store whose type was
    // never said: not theirs, so not trading for them — refused before the type is even asked,
    // and no session opens for them anywhere.
    UUID other = Ids.newId();
    for (UUID store : new UUID[] {SHOP, DARK, UNKNOWN}) {
      Response theirs = startAs(other, store);
      String why = theirs.readEntity(String.class);
      assertThat(why, theirs.getStatus(), is(409));
      assertThat(why, containsString("STORE_NOT_OPERATIONAL"));
    }

    // An older announcement never turns a dark store back into a shop; a newer one does.
    announce(DARK, "STORE", now.minusSeconds(60));
    assertThat(start(DARK).getStatus(), is(409));
    announce(DARK, "STORE", now.plusSeconds(60));
    assertThat(start(DARK).getStatus(), is(201));
    // A malformed announcement is skipped, not fatal.
    storeTypes.handle("{\"type\":\"DARK_STORE\",\"storeId\":\"not-an-id\"}");
  }
}
