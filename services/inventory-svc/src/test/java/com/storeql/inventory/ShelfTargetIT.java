package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.inventory.messaging.ShelfCapacityHandler;
import com.storeql.inventory.repo.ShelfTargetRepository;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shelf capacity as replenishment's target (07.17): the projection of product-svc's planograms, and
 * the gap report read from it.
 *
 * <p>Fed through the handler rather than through Kafka, because an integration test here runs
 * without a broker — and the part worth testing is not the delivery but what the SQL does with what
 * arrives: the version guard, the summing across fixtures, and the join to live stock.
 *
 * <p>The version guard is the assertion this class exists for. Kafka re-delivers, and nothing
 * orders records across partitions, so the same event can arrive twice and an older one can arrive
 * after a newer. A projection that trusted arrival order would eventually hold a shelf nobody ever
 * built.
 */
@HelidonTest
class ShelfTargetIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();

  @Inject WebTarget target;
  @Inject ShelfCapacityHandler handler;
  @Inject ShelfTargetRepository targets;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static String capacityEvent(
      String tenant, String store, String fixture, int version, String positions) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"ShelfCapacityPublished\",\"tenantId\":\""
        + tenant
        + "\",\"aggregateId\":\""
        + fixture
        + "\",\"storeId\":\""
        + store
        + "\",\"fixtureId\":\""
        + fixture
        + "\",\"planogramId\":\""
        + Ids.newId()
        + "\",\"version\":"
        + version
        + ",\"positions\":"
        + positions
        + "}";
  }

  private static String position(String variant, int capacity, int minPresentation) {
    return "{\"variantId\":\""
        + variant
        + "\",\"capacity\":"
        + capacity
        + ",\"minPresentation\":"
        + minPresentation
        + "}";
  }

  private void receive(String store, String variant, int qty, String tenant) {
    Response r =
        target
            .path("/admin/inventory/receive")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .post(
                Entity.entity(
                    "{\"storeId\":\""
                        + store
                        + "\",\"variantId\":\""
                        + variant
                        + "\",\"qty\":"
                        + qty
                        + "}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private String gaps(String store, String tenant) {
    Response r =
        target
            .path("/admin/inventory/reports/shelf-gaps")
            .queryParam("storeId", store)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  /**
   * Every combination the shelf-gap read cares about: a role, an optional {@code X-Store-Ids}
   * assignment, and the store named on the query — with no assertion on the status, so a caller can
   * check a refusal as well as a success.
   */
  private Response gapsAs(String role, String storeIds, String store, String tenant) {
    var b =
        target
            .path("/admin/inventory/reports/shelf-gaps")
            .queryParam("storeId", store)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", role);
    if (storeIds != null) {
      b = b.header("X-Store-Ids", storeIds);
    }
    return b.get();
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A published layout becomes the shelf's target, and the gap is what it would take")
  void theShelfIsTheTarget() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(variant, 60, 12) + "]"));
    receive(store, variant, 20, T);

    String body = gaps(store, T);
    assertThat(body, containsString("\"variantId\":\"" + variant + "\""));
    assertThat(body, containsString("\"capacity\":60"));
    assertThat(body, containsString("\"available\":\"20.000\""));
    // 60 the bay holds, 20 to hand: 40 to fill it. The number a reorder level cannot give, because
    // 20 units is plenty for a bay holding 12 and a gap in one holding 60.
    assertThat(body, containsString("\"gap\":\"40.000\""));
    assertThat(
        "20 is above the presentation minimum of 12",
        body,
        containsString("\"belowMinimum\":false"));

    // A shelf with more stock than it holds has no gap — and the zero carries the same scale as
    // every
    // other quantity in the column, because a report answering "0" beside "40.000" reads as broken.
    String full = Ids.newId().toString();
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(full, 12, 3) + "]"));
    receive(store, full, 40, T);
    assertThat(gaps(store, T), containsString("\"gap\":\"0.000\""));
  }

  @Test
  @DisplayName("A bay with a shelf and no stock at all still appears, and it is below its minimum")
  void anEmptyBayIsTheCase() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(variant, 24, 6) + "]"));

    String body = gaps(store, T);
    assertThat("no batch rows at all, and still reported", body, containsString(variant));
    assertThat(body, containsString("\"available\":\"0.000\""));
    assertThat(body, containsString("\"gap\":\"24.000\""));
    assertThat(body, containsString("\"belowMinimum\":true"));
  }

  @Test
  @DisplayName("A line sited on two fixtures has both bays to fill")
  void multiSitingSums() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(variant, 30, 6) + "]"));
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(variant, 12, 3) + "]"));

    String body = gaps(store, T);
    assertThat("a gondola and an end cap", body, containsString("\"capacity\":42"));
    assertThat(body, containsString("\"minPresentation\":9"));
  }

  @Test
  @DisplayName("The same event twice changes nothing, and an older version never wins")
  void theVersionGuardHolds() {
    String store = Ids.newId().toString();
    String fixture = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String event = capacityEvent(T, store, fixture, 2, "[" + position(variant, 40, 8) + "]");

    handler.handle(event);
    handler.handle(event);
    assertThat(gaps(store, T), containsString("\"capacity\":40"));
    assertThat("one row, not two", targets.versionOf(Ids.parse(T), Ids.parse(fixture)), is(2));

    // Version 1 arriving after version 2 — a re-delivery, or a partition read out of order.
    handler.handle(capacityEvent(T, store, fixture, 1, "[" + position(variant, 8, 2) + "]"));
    assertThat("the newer layout stands", gaps(store, T), containsString("\"capacity\":40"));

    // Version 3 replaces it wholesale, dropped lines and all.
    String other = Ids.newId().toString();
    handler.handle(capacityEvent(T, store, fixture, 3, "[" + position(other, 15, 3) + "]"));
    String after = gaps(store, T);
    assertThat(after, containsString("\"capacity\":15"));
    assertThat("the line the new layout dropped is gone", after, not(containsString(variant)));
  }

  @Test
  @DisplayName("A retired fixture holds nothing")
  void retiringClearsTheBay() {
    // Without this, replenishment would go on filling furniture nobody can see.
    String store = Ids.newId().toString();
    String fixture = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(capacityEvent(T, store, fixture, 1, "[" + position(variant, 18, 4) + "]"));
    assertThat(gaps(store, T), containsString(variant));

    handler.handle(
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"FixtureRetired\",\"tenantId\":\""
            + T
            + "\",\"aggregateId\":\""
            + fixture
            + "\",\"storeId\":\""
            + store
            + "\",\"fixtureId\":\""
            + fixture
            + "\"}");
    assertThat(gaps(store, T), not(containsString(variant)));
  }

  @Test
  @DisplayName("An event with no version, no positions, or no sense at all is dropped")
  void malformedEventsAreDropped() {
    String store = Ids.newId().toString();
    String fixture = Ids.newId().toString();
    String variant = Ids.newId().toString();

    // No version: it can only come from a build that never shipped, and assuming one would let a
    // stale layout overwrite a current shelf.
    handler.handle(
        "{\"eventType\":\"ShelfCapacityPublished\",\"tenantId\":\""
            + T
            + "\",\"storeId\":\""
            + store
            + "\",\"fixtureId\":\""
            + fixture
            + "\",\"planogramId\":\""
            + Ids.newId()
            + "\",\"positions\":["
            + position(variant, 10, 2)
            + "]}");
    handler.handle(capacityEvent(T, store, fixture, 1, "[]"));
    handler.handle("{\"eventType\":\"ShelfCapacityPublished\",\"tenantId\":\"not-a-uuid\"}");
    handler.handle("not json at all");

    assertThat(gaps(store, T), not(containsString(variant)));
  }

  @Test
  @DisplayName("Another business's shelves are not in this one's report")
  void tenantsAreSeparate() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(variant, 20, 5) + "]"));
    assertThat(
        "same store id, different business", gaps(store, RIVAL), not(containsString(variant)));
  }

  // ── Who may read a store's shelf gaps ─────────────────────

  @Test
  @DisplayName("OWNER, MANAGER and STOREKEEPER read it; a store-held caller only at their stores")
  void ownerManagerAndStorekeeperReadItAStorekeeperOnlyAtTheirOwnStore() {
    String storeKept = Ids.newId().toString();
    String storeNotKept = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(
        capacityEvent(
            T, storeKept, Ids.newId().toString(), 1, "[" + position(variant, 20, 5) + "]"));

    for (String role : new String[] {"OWNER", "MANAGER", "STOREKEEPER"}) {
      Response ok = gapsAs(role, storeKept, storeKept, T);
      String body = ok.readEntity(String.class);
      assertThat(role + ": " + body, ok.getStatus(), is(200));
      assertThat(role + " reads their own store's gaps", body, containsString(variant));
    }

    // A storekeeper held to storeKept names a store they do not keep: refused before the query
    // that would otherwise have found nothing to report anyway.
    Response deniedElsewhere = gapsAs("STOREKEEPER", storeKept, storeNotKept, T);
    String deniedBody = deniedElsewhere.readEntity(String.class);
    assertThat(deniedBody, deniedElsewhere.getStatus(), is(403));
    assertThat(deniedBody, containsString("STORE_ACCESS_DENIED"));

    // A manager and an owner, held to no store, read any of the business's stores.
    Response byManager = gapsAs("MANAGER", null, storeKept, T);
    assertThat(byManager.getStatus(), is(200));
  }

  @Test
  @DisplayName("A cashier is refused, even at a store they keep")
  void cashierIsRefusedEvenAtTheirOwnStore() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(variant, 20, 5) + "]"));

    Response denied = gapsAs("CASHIER", store, store, T);
    String body = denied.readEntity(String.class);
    assertThat(body, denied.getStatus(), is(403));

    // Held to no store at all changes nothing: a cashier is refused this report outright.
    Response deniedUnrestricted = gapsAs("CASHIER", null, store, T);
    assertThat(deniedUnrestricted.getStatus(), is(403));
  }

  @Test
  @DisplayName(
      "Another business's OWNER naming this store sees no rows; its STOREKEEPER is refused before"
          + " the query runs")
  void anotherBusinesssRolesOnThisBusinesssStoreId() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    handler.handle(
        capacityEvent(T, store, Ids.newId().toString(), 1, "[" + position(variant, 20, 5) + "]"));

    // Business-wide (held to no store): the tenant filter in the query alone decides — no rows,
    // never a 403 or 404 that would let a caller learn the store id belongs to somebody else.
    Response byOwner = gapsAs("OWNER", null, store, RIVAL);
    String ownerBody = byOwner.readEntity(String.class);
    assertThat(ownerBody, byOwner.getStatus(), is(200));
    assertThat("same store id, RIVAL's own business", ownerBody, not(containsString(variant)));

    // Held to its own store(s), RIVAL's storekeeper names a store that is not one of them: SJ-D74
    // refuses it before the (otherwise empty) query ever runs.
    String rivalsOwnStore = Ids.newId().toString();
    Response byStorekeeper = gapsAs("STOREKEEPER", rivalsOwnStore, store, RIVAL);
    String storekeeperBody = byStorekeeper.readEntity(String.class);
    assertThat(storekeeperBody, byStorekeeper.getStatus(), is(403));
    assertThat(storekeeperBody, containsString("STORE_ACCESS_DENIED"));
  }
}
