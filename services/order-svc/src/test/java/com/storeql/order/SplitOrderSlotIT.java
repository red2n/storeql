package com.storeql.order;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Delivery and collection slots meeting order orchestration (both intent pages): a split delivery's
 * window is resolved once, against the area store's own offering, before routing can decide it must
 * split — so every part of the group carries the same window and the checkout takes exactly one
 * place, never one per part.
 *
 * <p>Its own tenant-svc/inventory-svc stubs, separate from {@code SplitOrderIT}'s ({@code
 * TenantSvcStub} has no way to say a store's time zone — see {@code FulfilmentWindowsIT}'s class
 * comment and the final report), so this suite can set {@code reserve-enforce=true} for its own
 * class without touching what {@code SplitOrderIT} already covers.
 */
@HelidonTest
class SplitOrderSlotIT {

  private static final String T = "01a0e940-611e-702c-a97b-d1b8025478e1";
  private static final String OWNER_USER = "01a0e930-611e-7000-8000-0000000000a1";
  private static final String AREA_STORE = "01a0e940-611e-703c-a378-a4972ea461e1";
  private static final String OTHER_STORE = "01a0e940-611e-703c-a378-a4972ea461e2";
  private static final String APPLES = "01a0e940-611e-7037-a4b7-c854f0266ae1";
  private static final String PEARS = "01a0e940-611e-7037-a4b7-c854f0266ae2";
  private static final String SHOPPER = "01a0e940-611e-700b-bde4-50df0324c3e1";

  private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

  private static final PostgresSupport PG;
  private static final JsonStub TENANTS;
  private static final JsonStub INVENTORY;
  private static final String ENFORCE_BEFORE =
      System.getProperty("storeql.order.inventory.reserve-enforce");

  static {
    PG = PostgresSupport.start();
    TENANTS = JsonStub.start("tenant-svc");
    TENANTS.on(
        "GET",
        "/admin/tenant",
        call ->
            JsonStub.Answer.ok(
                "{\"id\":\"" + call.tenantId() + "\",\"currency\":\"GBP\",\"country\":\"GB\"}"));
    TENANTS.on(
        "GET",
        "/admin/stores",
        call ->
            new JsonStub.Answer(
                200,
                "{\"data\":["
                    + store(AREA_STORE)
                    + ","
                    + store(OTHER_STORE)
                    + "],\"meta\":{\"nextCursor\":null}}"));
    INVENTORY = JsonStub.start("inventory-svc");
    INVENTORY.on(
        "GET",
        "/admin/inventory/network/stock",
        call ->
            new JsonStub.Answer(
                200,
                "{\"data\":{\"levels\":["
                    + level(AREA_STORE, APPLES, 5)
                    + ","
                    + level(OTHER_STORE, PEARS, 5)
                    + "],\"dropship\":[]}}"));
    INVENTORY.on(
        "POST",
        "/inventory/reservations",
        call -> new JsonStub.Answer(201, "{\"data\":{\"id\":\"" + Ids.newId() + "\"}}"));
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "true");
  }

  private static String store(String id) {
    return "{\"id\":\""
        + id
        + "\",\"type\":\"STORE\",\"country\":\"GB\",\"timezone\":\""
        + ZONE.getId()
        + "\"}";
  }

  private static String level(String store, String variant, int qty) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"available\":"
        + qty
        + "}";
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    TENANTS.close();
    INVENTORY.close();
    System.clearProperty("storeql.clients.inventory-svc.url");
    if (ENFORCE_BEFORE == null) System.clearProperty("storeql.order.inventory.reserve-enforce");
    else System.setProperty("storeql.order.inventory.reserve-enforce", ENFORCE_BEFORE);
    PG.stop();
  }

  private Response admin(String method, String path, String json) {
    var b =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .header("X-User-Id", OWNER_USER)
            .header("Idempotency-Key", Ids.newId().toString());
    return "GET".equals(method) ? b.get() : b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static int bookableWeekday() {
    return LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2).getDayOfWeek().getValue();
  }

  private static String window(int weekday) {
    return "{\"storeId\":\""
        + AREA_STORE
        + "\",\"fulfilmentType\":\"DELIVERY\",\"weekday\":"
        + weekday
        + ",\"startTime\":\"17:00\",\"endTime\":\"19:00\",\"capacity\":1,\"cutoffMinutes\":0}";
  }

  @Test
  void aSplitDeliverysPartsAllCarryTheWindowAndItTakesOnePlace() {
    JsonObject created =
        Envelopes.created(admin("POST", "/admin/fulfilment-windows", window(bookableWeekday())));
    Response slotsResponse =
        target
            .path("/storefront/fulfilment-slots")
            .queryParam("store", AREA_STORE)
            .queryParam("type", "DELIVERY")
            .request()
            .header("X-Tenant-Id", T)
            .get();
    JsonObject slots = Envelopes.ok(slotsResponse);
    JsonObject slot = null;
    for (var dayVal : slots.getJsonArray("days")) {
      for (var slotVal : dayVal.asJsonObject().getJsonArray("slots")) {
        JsonObject s = slotVal.asJsonObject();
        if (created.getString("id").equals(s.getString("windowId"))) slot = s;
      }
    }
    assertThat(slot, org.hamcrest.Matchers.notNullValue());
    String startsAt = slot.getString("startsAt");

    String body =
        "{\"storeId\":\""
            + AREA_STORE
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\",\"currency\":\"GBP\","
            + "\"items\":[{\"variantId\":\""
            + APPLES
            + "\",\"qty\":1,\"unitPrice\":1.50},{\"variantId\":\""
            + PEARS
            + "\",\"qty\":1,\"unitPrice\":0.50}],"
            + "\"deliveryLine1\":\"1 Rynek\",\"deliveryCity\":\"Warsaw\","
            + "\"deliveryPostalCode\":\"00-001\",\"deliveryRecipientName\":\"Sam Shopper\","
            + "\"deliveryRecipientPhone\":\"07700900123\","
            + "\"slotWindowId\":\""
            + created.getString("id")
            + "\",\"slotStartsAt\":\""
            + startsAt
            + "\"}";
    Response placed =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", SHOPPER)
            .header("X-Roles", "CUSTOMER")
            .header("Idempotency-Key", Ids.newId().toString())
            .post(Entity.entity(body, MediaType.APPLICATION_JSON));
    JsonObject first = Envelopes.created(placed);
    JsonObject group = first.getJsonObject("group");
    assertThat(group, org.hamcrest.Matchers.notNullValue());
    JsonArray parts = group.getJsonArray("parts");
    assertThat(parts, hasSize(2));
    // Every part names the same window, in the area store's own local time.
    for (var partVal : parts) {
      JsonObject part = partVal.asJsonObject();
      assertThat(part.getJsonObject("slot").getString("startsAt"), is(startsAt));
      assertThat(part.getJsonObject("slot").getString("timeZone"), is(ZONE.getId()));
    }
    assertThat(first.getJsonObject("slot").getString("startsAt"), is(startsAt));

    // One place taken for the whole checkout, not one per part: two orders, one occupant.
    assertThat(
        scalar(
            PG,
            "SELECT count(DISTINCT coalesce(group_id, id)) FROM \"order\".orders"
                + " WHERE tenant_id = '"
                + T
                + "' AND slot_window_id = '"
                + created.getString("id")
                + "' AND status NOT IN ('CANCELLED','VOIDED')"),
        is("1"));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".orders WHERE tenant_id = '"
                + T
                + "' AND slot_window_id = '"
                + created.getString("id")
                + "'"),
        is("2"));

    // A second, distinct checkout at the same occurrence finds it full: the group's two parts
    // took the window's one place between them, not two.
    Response second =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", SHOPPER)
            .header("X-Roles", "CUSTOMER")
            .header("Idempotency-Key", Ids.newId().toString())
            .post(Entity.entity(body, MediaType.APPLICATION_JSON));
    assertThat(second.getStatus(), is(409));
    assertThat(
        Envelopes.parse(Envelopes.bodyOf(second, 409)).getString("code"), is("ORDER_SLOT_FULL"));
  }
}
