package com.storeql.order;

import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Delivery and collection slots (intent/delivery-and-collection-slots.md), against real Postgres: a
 * manager sets a store's windows; the storefront lists the next seven days in the store's own zone
 * with what each has left; placing an order takes a place, the last one taken once under a race; a
 * cancelled or replayed checkout takes no second place; a store with none checks out as before; a
 * split delivery's parts all carry the window and take one place; the order answer and {@code
 * ?sort=slot} carry it; and another business — even naming this one's store or window id — reads,
 * sets and takes none of it.
 *
 * <p>tenant-svc is a hand-built {@link JsonStub} rather than {@code TenantSvcStub}: the shared stub
 * has no way to say a store's time zone, which {@code TenantProfiles.Stores.zoneOf} and every
 * window here need. See the final report for the gap this leaves in {@code shared/common-test}.
 */
@HelidonTest
class FulfilmentWindowsIT {

  private static final String T = "01a0e930-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0e930-611e-702c-a97b-d1b8025478e2";
  private static final String WARSAW = "01a0e930-611e-703c-a378-a4972ea461e1";
  private static final String KOLKATA = "01a0e930-611e-703c-a378-a4972ea461e2";
  private static final String T2_STORE = "01a0e930-611e-703c-a378-a4972ea461e3";
  // Each test below that does not care which store it uses gets its own, so that no two tests'
  // windows can ever overlap by sharing a weekday and a time — every test in this class shares
  // one tenant and one Postgres database. All are Europe/Warsaw, like WARSAW itself.
  private static final String RACE_STORE = "01a0e930-611e-703c-a378-a4972ea461f1";
  private static final String CLOSED_STORE = "01a0e930-611e-703c-a378-a4972ea461f2";
  private static final String MISSING_STORE = "01a0e930-611e-703c-a378-a4972ea461f3";
  private static final String CANCEL_STORE = "01a0e930-611e-703c-a378-a4972ea461f4";
  private static final String POS_STORE = "01a0e930-611e-703c-a378-a4972ea461f5";
  private static final String HISTORY_STORE = "01a0e930-611e-703c-a378-a4972ea461f6";
  private static final String SORT_STORE = "01a0e930-611e-703c-a378-a4972ea461f7";
  private static final String ISOLATION_STORE = "01a0e930-611e-703c-a378-a4972ea461f8";
  // Never given a window by any test: the windowless checkouts run here, so no other test's
  // windows (the suite shares one tenant) can make it offer them — whatever order JUnit picks.
  private static final String PLAIN_STORE = "01a0e930-611e-703c-a378-a4972ea461f9";
  private static final String VARIANT = "01a0e930-611e-7037-a4b7-c854f0266ae1";
  private static final String SHOPPER = "01a0e930-611e-700b-bde4-50df0324c3e1";
  private static final String OWNER_USER = "01a0e930-611e-7000-8000-0000000000a1";
  private static final String MANAGER_USER = "01a0e930-611e-7000-8000-0000000000a2";
  private static final String STAFF_USER = "01a0e930-611e-7000-8000-0000000000a3";

  private static final ZoneId WARSAW_ZONE = ZoneId.of("Europe/Warsaw");

  private static final PostgresSupport PG;
  private static final JsonStub TENANTS;

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
        call -> {
          String stores;
          if (T.equals(call.tenantId())) {
            stores =
                String.join(
                    ",",
                    store(WARSAW, "Europe/Warsaw"),
                    store(KOLKATA, "Asia/Kolkata"),
                    store(RACE_STORE, "Europe/Warsaw"),
                    store(CLOSED_STORE, "Europe/Warsaw"),
                    store(MISSING_STORE, "Europe/Warsaw"),
                    store(CANCEL_STORE, "Europe/Warsaw"),
                    store(POS_STORE, "Europe/Warsaw"),
                    store(HISTORY_STORE, "Europe/Warsaw"),
                    store(SORT_STORE, "Europe/Warsaw"),
                    store(ISOLATION_STORE, "Europe/Warsaw"),
                    store(PLAIN_STORE, "Asia/Kolkata"));
          } else if (T2.equals(call.tenantId())) {
            stores = store(T2_STORE, "Europe/Berlin");
          } else {
            stores = "";
          }
          return new JsonStub.Answer(
              200, "{\"data\":[" + stores + "],\"meta\":{\"nextCursor\":null}}");
        });
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  private static String store(String id, String timezone) {
    return "{\"id\":\""
        + id
        + "\",\"type\":\"STORE\",\"country\":\"GB\",\"timezone\":\""
        + timezone
        + "\"}";
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    TENANTS.close();
    PG.stop();
  }

  // ── harness ──────────────────────────────────────────────────────────────────

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String storeIds) {
    Invocation.Builder b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("Idempotency-Key", Ids.newId().toString());
    if (roles != null) b = b.header("X-Roles", roles);
    // Every member of staff's token names who they are; only a guest's call carries no user.
    if (user == null && roles != null && !roles.contains("CUSTOMER")) user = STAFF_USER;
    if (user != null) b = b.header("X-User-Id", user);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
    };
  }

  private Response asOwner(String method, String path, String json, String tenant) {
    return call(method, path, json, tenant, OWNER_USER, "OWNER", null);
  }

  private Response asManager(String method, String path, String json, String storeIds) {
    return call(method, path, json, T, MANAGER_USER, "MANAGER", storeIds);
  }

  private static String code(Response r, int status) {
    return Envelopes.parse(Envelopes.bodyOf(r, status)).getString("code");
  }

  private static String window(
      String storeId,
      String type,
      int weekday,
      String start,
      String end,
      int capacity,
      int cutoffMinutes) {
    return "{\"storeId\":\""
        + storeId
        + "\",\"fulfilmentType\":\""
        + type
        + "\",\"weekday\":"
        + weekday
        + ",\"startTime\":\""
        + start
        + "\",\"endTime\":\""
        + end
        + "\",\"capacity\":"
        + capacity
        + ",\"cutoffMinutes\":"
        + cutoffMinutes
        + "}";
  }

  private static String updateWindow(
      int weekday, String start, String end, int capacity, int cutoffMinutes, boolean active) {
    return "{\"weekday\":"
        + weekday
        + ",\"startTime\":\""
        + start
        + "\",\"endTime\":\""
        + end
        + "\",\"capacity\":"
        + capacity
        + ",\"cutoffMinutes\":"
        + cutoffMinutes
        + ",\"active\":"
        + active
        + "}";
  }

  /**
   * ISO weekday two days from now, measured in UTC: safely "the future" in every zone this suite
   * uses (Warsaw is at most UTC+2, Kolkata UTC+5:30), so a window on it, spanning the whole local
   * day, is never already under way and never past a small cut-off, whatever moment the suite runs.
   */
  private static int bookableWeekday() {
    return LocalDate.now(ZoneOffset.UTC).plusDays(2).getDayOfWeek().getValue();
  }

  private JsonObject slots(String tenant, String storeId, String type) {
    Response r =
        call(
            "GET",
            "/storefront/fulfilment-slots?store=" + storeId + "&type=" + type,
            null,
            tenant,
            null,
            null,
            null);
    return Envelopes.ok(r);
  }

  /** The first slot of {@code windowId}, wherever in the seven days it falls. */
  private static JsonObject slotOf(JsonObject slots, String windowId) {
    for (JsonValue dayVal : slots.getJsonArray("days")) {
      for (JsonValue slotVal : dayVal.asJsonObject().getJsonArray("slots")) {
        JsonObject slot = slotVal.asJsonObject();
        if (windowId.equals(slot.getString("windowId"))) return slot;
      }
    }
    throw new AssertionError("no slot for window " + windowId + " in " + slots);
  }

  private Response place(
      String tenant, String storeId, String windowId, String startsAt, String key) {
    String body =
        "{\"storeId\":\""
            + storeId
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\",\"currency\":\"GBP\","
            + "\"items\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qty\":1,\"unitPrice\":5.00}],"
            + "\"deliveryLine1\":\"1 Rynek\",\"deliveryCity\":\"Warsaw\","
            + "\"deliveryPostalCode\":\"00-001\",\"deliveryRecipientName\":\"Sam Shopper\","
            + "\"deliveryRecipientPhone\":\"07700900123\""
            + (windowId == null
                ? ""
                : ",\"slotWindowId\":\"" + windowId + "\",\"slotStartsAt\":\"" + startsAt + "\"")
            + "}";
    return target
        .path("/orders")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", SHOPPER)
        .header("X-Roles", "CUSTOMER")
        .header("Idempotency-Key", key)
        .post(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private Response posSale(String tenant, String storeId, String windowId, String startsAt) {
    String body =
        "{\"storeId\":\""
            + storeId
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"currency\":\"GBP\","
            + "\"items\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qty\":1,\"unitPrice\":5.00}]"
            + (windowId == null
                ? ""
                : ",\"slotWindowId\":\"" + windowId + "\",\"slotStartsAt\":\"" + startsAt + "\"")
            + "}";
    return target
        .path("/orders")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  // ── management: setting windows ───────────────────────────────────────────────

  @Test
  void aManagerSetsDeliveryAndCollectionWindowsSeparately() {
    int weekday = bookableWeekday();
    JsonObject delivery =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", weekday, "00:00", "01:00", 5, 30),
                T));
    JsonObject pickup =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "PICKUP", weekday, "09:00", "10:00", 3, 15),
                T));
    assertThat(delivery.getString("fulfilmentType"), is("DELIVERY"));
    assertThat(pickup.getString("fulfilmentType"), is("PICKUP"));
    assertThat(delivery.getString("timeZone"), is("Europe/Warsaw"));

    JsonArray listed =
        Envelopes.okArray(asOwner("GET", "/admin/fulfilment-windows?storeId=" + WARSAW, null, T));
    assertThat(listed, hasSize(greaterThanOrEqualTo(2)));
  }

  @Test
  void overlappingBackToFrontOrZeroCapacityWindowsAreRefused() {
    int weekday = bookableWeekday();
    // Back-to-front (start not before end).
    assertThat(
        code(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", weekday, "19:00", "17:00", 5, 0),
                T),
            400),
        is("ORDER_SLOT_WINDOW_INVALID"));
    // Zero capacity.
    assertThat(
        code(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", weekday, "17:00", "19:00", 0, 0),
                T),
            400),
        is("ORDER_SLOT_WINDOW_INVALID"));
    // Overlapping an existing ACTIVE window of the same store, type and weekday.
    Envelopes.created(
        asOwner(
            "POST",
            "/admin/fulfilment-windows",
            window(WARSAW, "DELIVERY", weekday, "10:00", "12:00", 5, 0),
            T));
    assertThat(
        code(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", weekday, "11:00", "13:00", 5, 0),
                T),
            400),
        is("ORDER_SLOT_WINDOW_INVALID"));
    // Touching windows (12:00-13:00 starting exactly when the first ends) do not overlap.
    Envelopes.created(
        asOwner(
            "POST",
            "/admin/fulfilment-windows",
            window(WARSAW, "DELIVERY", weekday, "12:00", "13:00", 5, 0),
            T));
  }

  @Test
  void aStoreHeldManagerCannotSetAnotherStoresWindows() {
    // MANAGER, held to Warsaw only, may set Warsaw's windows...
    JsonObject w =
        Envelopes.created(
            asManager(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", bookableWeekday(), "08:00", "09:00", 2, 0),
                WARSAW));
    // ...but not Kolkata's, even in the same tenant.
    assertThat(
        asManager(
                "POST",
                "/admin/fulfilment-windows",
                window(KOLKATA, "DELIVERY", bookableWeekday(), "08:00", "09:00", 2, 0),
                WARSAW)
            .getStatus(),
        is(403));
    // Nor may they change Kolkata's window by id even if they knew it.
    JsonObject kolkataWindow =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(KOLKATA, "DELIVERY", bookableWeekday(), "08:00", "09:00", 2, 0),
                T));
    assertThat(
        asManager(
                "PUT",
                "/admin/fulfilment-windows/" + kolkataWindow.getString("id"),
                updateWindow(bookableWeekday(), "08:00", "10:00", 2, 0, true),
                WARSAW)
            .getStatus(),
        is(403));
    // And Warsaw's own window they may change.
    JsonObject updated =
        Envelopes.ok(
            asManager(
                "PUT",
                "/admin/fulfilment-windows/" + w.getString("id"),
                updateWindow(bookableWeekday(), "08:00", "10:00", 4, 5, true),
                WARSAW));
    assertThat(updated.getInt("capacity"), is(4));
  }

  // ── storefront ─────────────────────────────────────────────────────────────────

  @Test
  void theStorefrontListsSevenDaysInTheStoresOwnZone() {
    int weekday = bookableWeekday();
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", weekday, "17:00", "19:00", 5, 0),
                T));
    JsonObject slots = slots(T, WARSAW, "DELIVERY");
    assertThat(slots.getString("timeZone"), is("Europe/Warsaw"));
    assertThat(slots.getBoolean("offered"), is(true));
    assertThat(slots.getJsonArray("days"), hasSize(7));
    JsonObject slot = slotOf(slots, window.getString("id"));
    assertThat(slot.getString("startTime"), is("17:00"));
    assertThat(slot.getString("endTime"), is("19:00"));
    assertThat(slot.getInt("left"), is(5));
    assertThat(slot.getBoolean("full"), is(false));

    // A store with no active window of the OTHER type says so, and offers nothing.
    JsonObject pickupSlots = slots(T, WARSAW, "PICKUP");
    assertThat(pickupSlots.getBoolean("offered"), is(false));
    for (JsonValue day : pickupSlots.getJsonArray("days")) {
      assertThat(day.asJsonObject().getJsonArray("slots"), hasSize(0));
    }
  }

  @Test
  void twoStoresInTwoZonesEachShowTheirOwnLocalTime() {
    int weekday = bookableWeekday();
    // A time distinct from every other window this suite sets at either store, so this test's
    // occurrences cannot collide with another test's.
    JsonObject warsawWindow =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", weekday, "20:00", "22:00", 5, 0),
                T));
    JsonObject kolkataWindow =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(KOLKATA, "DELIVERY", weekday, "20:00", "22:00", 5, 0),
                T));
    JsonObject warsawSlot = slotOf(slots(T, WARSAW, "DELIVERY"), warsawWindow.getString("id"));
    JsonObject kolkataSlot = slotOf(slots(T, KOLKATA, "DELIVERY"), kolkataWindow.getString("id"));
    // Both stores show 20:00-22:00 local...
    assertThat(warsawSlot.getString("startTime"), is("20:00"));
    assertThat(kolkataSlot.getString("startTime"), is("20:00"));
    // ...but Warsaw (UTC+1/+2) and Kolkata (UTC+5:30, no DST) mean it at different UTC instants.
    assertThat(warsawSlot.getString("startsAt"), not(kolkataSlot.getString("startsAt")));
  }

  @Test
  void aFullWindowSaysSoAndAPastCutoffOccurrenceIsLeftOut() {
    int weekday = bookableWeekday();
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "DELIVERY", weekday, "14:00", "16:00", 1, 0),
                T));
    JsonObject slot = slotOf(slots(T, WARSAW, "DELIVERY"), window.getString("id"));
    Envelopes.created(
        place(
            T, WARSAW, window.getString("id"), slot.getString("startsAt"), Ids.newId().toString()));
    JsonObject afterOneTaken = slotOf(slots(T, WARSAW, "DELIVERY"), window.getString("id"));
    assertThat(afterOneTaken.getInt("left"), is(0));
    assertThat(afterOneTaken.getBoolean("full"), is(true));

    // A window whose cut-off can never be met (a huge cutoffMinutes) is always left out.
    JsonObject neverBookable =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(WARSAW, "PICKUP", weekday, "08:00", "09:00", 5, 100_000),
                T));
    JsonObject pickupSlots = slots(T, WARSAW, "PICKUP");
    for (JsonValue day : pickupSlots.getJsonArray("days")) {
      for (JsonValue s : day.asJsonObject().getJsonArray("slots")) {
        assertThat(s.asJsonObject().getString("windowId"), not(neverBookable.getString("id")));
      }
    }
  }

  // ── placing takes a place ─────────────────────────────────────────────────────

  @Test
  void placingTakesAPlaceAndTheLastPlaceIsTakenOnceUnderARace() throws Exception {
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(RACE_STORE, "DELIVERY", bookableWeekday(), "10:00", "11:00", 1, 0),
                T));
    JsonObject slot = slotOf(slots(T, RACE_STORE, "DELIVERY"), window.getString("id"));
    String windowId = window.getString("id");
    String startsAt = slot.getString("startsAt");

    int callers = 6;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        String key = Ids.newId().toString();
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  Response r = place(T, RACE_STORE, windowId, startsAt, key);
                  int status = r.getStatus();
                  r.close();
                  return status;
                }));
      }
      start.countDown();
      for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 201).count(), is(1L));
    assertThat(
        statuses.toString(), statuses.stream().filter(s -> s == 409).count(), is(callers - 1L));
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".orders WHERE tenant_id = '"
                + T
                + "' AND slot_window_id = '"
                + windowId
                + "' AND status NOT IN ('CANCELLED','VOIDED')"),
        is("1"));
  }

  @Test
  void aPastCutOffOccurrenceIs409Closed() {
    // A cut-off no wait can ever satisfy (100,000 minutes) drops the window from every list the
    // storefront gives — proving the checkout is refused needs the occurrence's instant computed
    // the same way the server does: the next date on this weekday, at the window's own local time.
    int weekday = bookableWeekday();
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(CLOSED_STORE, "DELIVERY", weekday, "10:00", "11:00", 5, 100_000),
                T));
    String startsAt = nextOccurrenceInstant(WARSAW_ZONE, weekday, 10, 0);
    assertThat(
        code(place(T, CLOSED_STORE, window.getString("id"), startsAt, Ids.newId().toString()), 409),
        is("ORDER_SLOT_CLOSED"));
  }

  /**
   * The UTC instant of the next date (today included) on {@code weekday} at {@code hh:mm} local.
   */
  private static String nextOccurrenceInstant(ZoneId zone, int weekday, int hh, int mm) {
    LocalDate date = LocalDate.now(zone);
    while (date.getDayOfWeek().getValue() != weekday) {
      date = date.plusDays(1);
    }
    return date.atTime(hh, mm).atZone(zone).toInstant().toString();
  }

  @Test
  void aMissingSlotWhereOfferedIs400RequiredAndAnUnknownOneIs400Unknown() {
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(MISSING_STORE, "DELIVERY", bookableWeekday(), "10:00", "11:00", 5, 0),
                T));
    // Nothing named at all where the store offers windows.
    assertThat(
        code(place(T, MISSING_STORE, null, null, Ids.newId().toString()), 400),
        is("ORDER_SLOT_REQUIRED"));
    // A well-formed but unrelated window id.
    JsonObject slot = slotOf(slots(T, MISSING_STORE, "DELIVERY"), window.getString("id"));
    assertThat(
        code(
            place(
                T,
                MISSING_STORE,
                Ids.newId().toString(),
                slot.getString("startsAt"),
                Ids.newId().toString()),
            400),
        is("ORDER_SLOT_UNKNOWN"));
    // A startsAt that is not this window's occurrence.
    assertThat(
        code(
            place(
                T,
                MISSING_STORE,
                window.getString("id"),
                "2099-01-01T00:00:00Z",
                Ids.newId().toString()),
            400),
        is("ORDER_SLOT_UNKNOWN"));
  }

  @Test
  void aCancelledOrderGivesItsPlaceBackAndAReplayTakesNoSecondPlace() {
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(CANCEL_STORE, "DELIVERY", bookableWeekday(), "12:00", "13:00", 1, 0),
                T));
    JsonObject slot = slotOf(slots(T, CANCEL_STORE, "DELIVERY"), window.getString("id"));
    String windowId = window.getString("id");
    String startsAt = slot.getString("startsAt");

    // Placed, then replayed with the SAME key: no second place taken.
    String key = Ids.newId().toString();
    JsonObject first = Envelopes.created(place(T, CANCEL_STORE, windowId, startsAt, key));
    JsonObject replay = Envelopes.created(place(T, CANCEL_STORE, windowId, startsAt, key));
    assertThat(replay.getString("id"), is(first.getString("id")));
    // A genuinely new order at the same occurrence is refused: only one place was ever taken.
    assertThat(
        code(place(T, CANCEL_STORE, windowId, startsAt, Ids.newId().toString()), 409),
        is("ORDER_SLOT_FULL"));

    // Cancel the first order: its place is given back.
    assertThat(
        asOwner(
                "POST",
                "/orders/" + first.getString("id") + "/cancel",
                "{\"reason\":\"changed mind\"}",
                T)
            .getStatus(),
        is(200));
    JsonObject afterCancel =
        Envelopes.created(place(T, CANCEL_STORE, windowId, startsAt, Ids.newId().toString()));
    assertThat(afterCancel.getString("storeId"), is(CANCEL_STORE));
  }

  @Test
  void aStoreWithNoWindowsChecksOutExactlyAsBefore() {
    JsonObject order = Envelopes.created(place(T, PLAIN_STORE, null, null, Ids.newId().toString()));
    assertThat(order.get("slot"), nullValue());
  }

  @Test
  void aSlotOnAPosSaleIs400NotApplicable() {
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(POS_STORE, "DELIVERY", bookableWeekday(), "10:00", "11:00", 5, 0),
                T));
    JsonObject slot = slotOf(slots(T, POS_STORE, "DELIVERY"), window.getString("id"));
    assertThat(
        code(posSale(T, POS_STORE, window.getString("id"), slot.getString("startsAt")), 400),
        is("ORDER_SLOT_NOT_APPLICABLE"));
  }

  // ── the order answer carries the window ───────────────────────────────────────

  @Test
  void theOrderAnswerTheShoppersHistoryAndTheEventsCarryTheWindow() {
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(HISTORY_STORE, "DELIVERY", bookableWeekday(), "16:00", "18:00", 5, 0),
                T));
    JsonObject slot = slotOf(slots(T, HISTORY_STORE, "DELIVERY"), window.getString("id"));
    JsonObject placed =
        Envelopes.created(
            place(
                T,
                HISTORY_STORE,
                window.getString("id"),
                slot.getString("startsAt"),
                Ids.newId().toString()));
    JsonObject placedSlot = placed.getJsonObject("slot");
    assertThat(placedSlot.getString("startsAt"), is(slot.getString("startsAt")));
    assertThat(placedSlot.getString("timeZone"), is("Europe/Warsaw"));
    assertThat(placedSlot.getString("startTime"), is("16:00"));
    assertThat(placedSlot.getString("endTime"), is("18:00"));

    JsonObject byId =
        Envelopes.ok(
            call("GET", "/orders/" + placed.getString("id"), null, T, SHOPPER, "CUSTOMER", null));
    assertThat(byId.getJsonObject("slot").getString("startTime"), is("16:00"));

    JsonArray mine =
        Envelopes.okArray(call("GET", "/orders/mine", null, T, SHOPPER, "CUSTOMER", null));
    JsonObject mineRow = Envelopes.find(mine, "id", placed.getString("id"));
    assertThat(mineRow.getJsonObject("slot").getString("date"), is(placedSlot.getString("date")));

    // OrderPlaced's outbox row carries the three fields.
    assertThat(
        scalar(
            PG,
            "SELECT count(*) FROM \"order\".outbox WHERE event_type = 'OrderPlaced'"
                + " AND aggregate_id = '"
                + placed.getString("id")
                + "' AND payload LIKE '%\"slotStartsAt\":\""
                + slot.getString("startsAt")
                + "\"%'"),
        is("1"));
  }

  // ── sort=slot ──────────────────────────────────────────────────────────────────

  @Test
  void sortSlotOrdersBySlotThenIdWithWindowlessOrdersLastAndPagesCorrectly() {
    // Other tests in this suite place their own orders against the same tenant, so this proves
    // relative order and page-walking integrity rather than absolute positions or page counts.
    JsonObject earlyWindow =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(SORT_STORE, "DELIVERY", bookableWeekday(), "08:00", "09:00", 5, 0),
                T));
    JsonObject lateWindow =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(SORT_STORE, "DELIVERY", bookableWeekday(), "20:00", "21:00", 5, 0),
                T));
    JsonObject lateSlot = slotOf(slots(T, SORT_STORE, "DELIVERY"), lateWindow.getString("id"));
    JsonObject earlySlot = slotOf(slots(T, SORT_STORE, "DELIVERY"), earlyWindow.getString("id"));

    JsonObject withNoWindow =
        Envelopes.created(place(T, PLAIN_STORE, null, null, Ids.newId().toString()));
    JsonObject withLateSlot =
        Envelopes.created(
            place(
                T,
                SORT_STORE,
                lateWindow.getString("id"),
                lateSlot.getString("startsAt"),
                Ids.newId().toString()));
    JsonObject withEarlySlot =
        Envelopes.created(
            place(
                T,
                SORT_STORE,
                earlyWindow.getString("id"),
                earlySlot.getString("startsAt"),
                Ids.newId().toString()));

    // Walk every page with a small limit: proves the two-key cursor advances without skipping or
    // repeating a row, whatever else this tenant has placed.
    List<String> seen = new ArrayList<>();
    String after = null;
    int guard = 0;
    do {
      String path = "/orders?sort=slot&limit=5" + (after == null ? "" : "&after=" + after);
      JsonObject body =
          Envelopes.parse(Envelopes.bodyOf(call("GET", path, null, T, null, "OWNER", null), 200));
      for (JsonValue v : body.getJsonArray("data")) seen.add(v.asJsonObject().getString("id"));
      JsonObject meta = body.getJsonObject("meta");
      // The last page leaves nextCursor out (a null field is not written), not null.
      after =
          !meta.containsKey("nextCursor") || meta.isNull("nextCursor")
              ? null
              : meta.getString("nextCursor");
      guard++;
    } while (after != null && guard < 200);

    assertThat(seen.size(), is(new java.util.LinkedHashSet<>(seen).size()));
    int early = seen.indexOf(withEarlySlot.getString("id"));
    int late = seen.indexOf(withLateSlot.getString("id"));
    int none = seen.indexOf(withNoWindow.getString("id"));
    assertThat("early slot must be found: " + seen, early, greaterThanOrEqualTo(0));
    assertThat("a later slot sorts after an earlier one", late, greaterThan(early));
    assertThat("a windowless order sorts after every slotted one", none, greaterThan(late));
  }

  // ── hard isolation ─────────────────────────────────────────────────────────────

  @Test
  void anotherBusinesssStaffReadNoWindowsSetNoneAndTakeNoPlaceEvenNamingOurStore() {
    JsonObject window =
        Envelopes.created(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(ISOLATION_STORE, "DELIVERY", bookableWeekday(), "10:00", "11:00", 5, 0),
                T));

    // OWNER and MANAGER of business B pass the management-role tier, then are refused by tenant:
    // naming OUR store id is 404, naming OUR window id is 404.
    assertThat(
        code(asOwner("GET", "/admin/fulfilment-windows?storeId=" + ISOLATION_STORE, null, T2), 404),
        is("ORDER_SLOT_STORE_NOT_FOUND"));
    assertThat(
        code(
            asOwner(
                "POST",
                "/admin/fulfilment-windows",
                window(ISOLATION_STORE, "DELIVERY", bookableWeekday(), "08:00", "09:00", 2, 0),
                T2),
            404),
        is("ORDER_SLOT_STORE_NOT_FOUND"));
    assertThat(
        code(
            call(
                "PUT",
                "/admin/fulfilment-windows/" + window.getString("id"),
                updateWindow(bookableWeekday(), "08:00", "10:00", 9, 0, true),
                T2,
                null,
                "MANAGER",
                null),
            404),
        is("ORDER_SLOT_WINDOW_NOT_FOUND"));

    // CASHIER and STOREKEEPER of business B never even reach the tenant check: the management
    // tier itself refuses them first — read no windows, set none, all the same.
    assertThat(
        call(
                "GET",
                "/admin/fulfilment-windows?storeId=" + ISOLATION_STORE,
                null,
                T2,
                null,
                "CASHIER",
                null)
            .getStatus(),
        is(403));
    assertThat(
        call(
                "POST",
                "/admin/fulfilment-windows",
                window(ISOLATION_STORE, "DELIVERY", bookableWeekday(), "08:00", "09:00", 2, 0),
                T2,
                null,
                "STOREKEEPER",
                null)
            .getStatus(),
        is(403));

    // Business B's storefront lists none of ours, even naming our store id.
    assertThat(
        code(
            call(
                "GET",
                "/storefront/fulfilment-slots?store=" + ISOLATION_STORE + "&type=DELIVERY",
                null,
                T2,
                null,
                null,
                null),
            404),
        is("ORDER_SLOT_STORE_NOT_FOUND"));

    // And business B's checkout, naming our window id at its own store, is unknown to it.
    assertThat(
        code(
            place(
                T2,
                T2_STORE,
                window.getString("id"),
                "2099-01-01T00:00:00Z",
                Ids.newId().toString()),
            400),
        is("ORDER_SLOT_UNKNOWN"));

    // None of this touched our own window: it stands exactly as we set it.
    JsonArray stillOurs =
        Envelopes.okArray(
            asOwner("GET", "/admin/fulfilment-windows?storeId=" + ISOLATION_STORE, null, T));
    JsonObject unchanged = Envelopes.find(stillOurs, "id", window.getString("id"));
    assertThat(unchanged.getInt("capacity"), is(5));
  }
}
