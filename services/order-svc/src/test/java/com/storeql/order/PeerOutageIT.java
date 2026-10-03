package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * What order-svc does when a service it needs cannot answer: inventory-svc while an online checkout
 * takes its stock holds, and notification-svc while a cashier emails a receipt. Each is refused
 * with its own code and a 503, nothing is left behind (no order, no event, no hold that was made
 * and not given back, no receipt row for a mail that never left), and the same request made again
 * once the service answers does what it asked, once. A refusal by the peer that is its answer, not
 * an outage (a rejected address, a role it will not serve), is told as it is.
 *
 * <p>Both peers are stubs the test breaks and mends. Each guarded call sits behind a circuit
 * breaker, so the tests run in a fixed order: the ones that count calls come first, while the
 * breaker is closed, and everything after them asserts only the answer, which is the same whether
 * the circuit has opened or not. That an open circuit is also the 503 is the point of the last of
 * them in each group.
 *
 * <p>With the stubs gone altogether (the connection refused, or no address left to try) the answer
 * is the same again. This relies on nothing answering a lookup of either service at the discovery
 * address, as on any build machine and CI runner.
 */
@HelidonTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PeerOutageIT {

  private static final String T = "01a0b7e1-1111-7000-8000-000000000001";
  private static final String STORE = "01a0b7e1-2222-7000-8000-00000000000a";
  private static final String APPLES = "01a0b7e1-3333-7000-8000-000000000001";
  private static final String PEARS = "01a0b7e1-3333-7000-8000-000000000002";
  private static final String MANAGER = "01a0b7e1-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0b7e1-4444-7000-8000-000000000002";

  private static final PostgresSupport PG;
  private static final JsonStub INVENTORY;
  private static final JsonStub NOTIFICATIONS;

  /** The variants whose hold inventory-svc cannot take, and what it answers instead. */
  private static final Set<String> BROKEN = ConcurrentHashMap.newKeySet();

  private static volatile int brokenStatus = 500;
  private static volatile String brokenBody = "{}";

  /** The holds inventory-svc made, by id. */
  private static final List<String> HELD = new CopyOnWriteArrayList<>();

  /** What notification-svc answers a send. */
  private static volatile int sendStatus = 202;

  private static volatile boolean inventoryClosed;
  private static volatile boolean notificationsClosed;

  /** What the suite found, put back when it ends: system properties outlive a test class. */
  private static final String ENFORCE_BEFORE =
      System.getProperty("storeql.order.inventory.reserve-enforce");

  static {
    PG = PostgresSupport.start().wire("order");
    TenantSvcStub.start().with(T, "GBP", "GB").withStore(T, STORE, "GB");
    INVENTORY = JsonStub.start("inventory-svc");
    INVENTORY.on("POST", "/inventory/reservations", PeerOutageIT::hold);
    NOTIFICATIONS = JsonStub.start("notification-svc");
    NOTIFICATIONS.on("POST", "/notifications/send", call -> new JsonStub.Answer(sendStatus, "{}"));
    System.setProperty("storeql.order.pricing.enforce", "false");
    // Holds on: an online order takes them, a till sale does not.
    System.setProperty("storeql.order.inventory.reserve-enforce", "true");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    if (!inventoryClosed) INVENTORY.close();
    if (!notificationsClosed) NOTIFICATIONS.close();
    if (ENFORCE_BEFORE == null) System.clearProperty("storeql.order.inventory.reserve-enforce");
    else System.setProperty("storeql.order.inventory.reserve-enforce", ENFORCE_BEFORE);
    PG.stop();
  }

  @BeforeEach
  void reset() {
    INVENTORY.reset();
    NOTIFICATIONS.reset();
    BROKEN.clear();
    HELD.clear();
    brokenStatus = 500;
    brokenBody = "{}";
    sendStatus = 202;
  }

  // ── the stubs ──────────────────────────────────────────────────────────────────

  /** A hold, taken and given an id that can be released, unless the variant is broken. */
  private static JsonStub.Answer hold(JsonStub.Call call) {
    JsonObject asked = Json.createReader(new StringReader(call.body())).readObject();
    if (BROKEN.contains(asked.getString("variantId"))) {
      return new JsonStub.Answer(brokenStatus, brokenBody);
    }
    String id = Ids.newId().toString();
    HELD.add(id);
    INVENTORY.on("POST", "/inventory/reservations/" + id + "/release", 200, "{\"data\":{}}");
    return new JsonStub.Answer(201, "{\"data\":{\"id\":\"" + id + "\"}}");
  }

  // ── harness ────────────────────────────────────────────────────────────────────

  private static String line(String variant) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":1,\"unitPrice\":5.00}";
  }

  /** An online collection order of one unit of each variant, placed by a manager at the store. */
  private Response placeOnline(String key, String... variants) {
    List<String> lines = new ArrayList<>();
    for (String variant : variants) lines.add(line(variant));
    String body =
        "{\"storeId\":\""
            + STORE
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\",\"items\":["
            + String.join(",", lines)
            + "]}";
    return target
        .path("/orders")
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", MANAGER)
        .header("X-Roles", "MANAGER")
        .header("Idempotency-Key", key)
        .post(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  /** A till sale at the store, rung up by a cashier held to it; the order's id. */
  private String tillSale() {
    String body =
        "{\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":["
            + line(APPLES)
            + "]}";
    Response r =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", CASHIER)
            .header("X-Roles", "CASHIER")
            .header("X-Store-Ids", STORE)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(Entity.entity(body, MediaType.APPLICATION_JSON));
    return Envelopes.created(r).getString("id");
  }

  /** The cashier asking for a copy of the sale to be emailed. */
  private Response emailReceipt(String order, String address) {
    return target
        .path("/admin/orders/" + order + "/receipts")
        .request()
        .header("X-Tenant-Id", T)
        .header("X-User-Id", CASHIER)
        .header("X-Roles", "CASHIER")
        .header("X-Store-Ids", STORE)
        .post(
            Entity.entity(
                "{\"receiptType\":\"EMAIL\",\"emailedTo\":\"" + address + "\"}",
                MediaType.APPLICATION_JSON));
  }

  /** The problem a refusal with the expected status answered with. */
  private static JsonObject refused(Response r, int status) {
    return Envelopes.parse(Envelopes.bodyOf(r, status));
  }

  private static String count(String table, String where) {
    return Envelopes.scalar(PG, "SELECT count(*) FROM \"order\"." + table + " WHERE " + where);
  }

  private static String ordersOfTheBusiness() {
    return count("orders", "tenant_id = '" + T + "'");
  }

  private static String eventsOfTheBusiness() {
    return count("outbox", "tenant_id = '" + T + "'");
  }

  private static String placedWith(String key) {
    return count("orders", "tenant_id = '" + T + "' AND idempotency_key = '" + key + "'");
  }

  private static String receiptsOf(String order) {
    return count("order_receipts", "tenant_id = '" + T + "' AND order_id = '" + order + "'");
  }

  private static List<JsonStub.Call> holdCalls() {
    return INVENTORY.calls().stream()
        .filter(c -> "POST".equals(c.method()) && "/inventory/reservations".equals(c.path()))
        .toList();
  }

  private static List<String> releaseCalls() {
    return INVENTORY.calls().stream()
        .filter(c -> "POST".equals(c.method()) && c.path().endsWith("/release"))
        .map(JsonStub.Call::path)
        .toList();
  }

  // ── inventory-svc, while an online checkout holds stock ───────────────────────

  @Test
  @Order(1)
  @DisplayName("A failed hold refuses the checkout with nothing placed; the retry places it")
  void aHoldThatFailsRefusesTheCheckoutAndTheRetryPlacesIt() {
    String key = Ids.newId().toString();
    BROKEN.add(APPLES);
    String ordersBefore = ordersOfTheBusiness();
    String eventsBefore = eventsOfTheBusiness();

    JsonObject refused = refused(placeOnline(key, APPLES), 503);
    assertThat(refused.getString("code"), is("ORDER_INVENTORY_UNAVAILABLE"));
    assertThat("no order was placed", placedWith(key), is("0"));
    assertThat(ordersOfTheBusiness(), is(ordersBefore));
    assertThat("no event was announced", eventsOfTheBusiness(), is(eventsBefore));
    assertThat("inventory was asked once", holdCalls(), hasSize(1));
    assertThat("for this business", holdCalls().get(0).tenantId(), is(T));
    assertThat("and holds nothing", HELD, hasSize(0));
    assertThat("so there is nothing to give back", releaseCalls(), hasSize(0));

    // Inventory answers again: the same key places the order, once, and its hold carries the key
    // the failed attempt's did, so a hold that did land would be replayed and not doubled.
    BROKEN.clear();
    JsonObject placed = Envelopes.created(placeOnline(key, APPLES));
    assertThat(placed.getString("status"), is("PENDING"));
    assertThat(placedWith(key), is("1"));
    List<JsonStub.Call> asked = holdCalls();
    assertThat(asked, hasSize(2));
    assertThat(asked.get(1).header("Idempotency-Key"), is(asked.get(0).header("Idempotency-Key")));
    assertThat(
        "a key inventory-svc accepts: a UUIDv7",
        Ids.parse(asked.get(0).header("Idempotency-Key")).toString(),
        is(asked.get(0).header("Idempotency-Key")));
    assertThat(HELD, hasSize(1));
  }

  @Test
  @Order(2)
  @DisplayName("When one of two holds fails, the other is given back and nothing is placed")
  void aHoldThatFailsGivesBackTheOthersAndPlacesNothing() {
    String key = Ids.newId().toString();
    BROKEN.add(PEARS);
    String ordersBefore = ordersOfTheBusiness();
    String eventsBefore = eventsOfTheBusiness();

    JsonObject refused = refused(placeOnline(key, APPLES, PEARS), 503);
    assertThat(refused.getString("code"), is("ORDER_INVENTORY_UNAVAILABLE"));
    assertThat(placedWith(key), is("0"));
    assertThat(ordersOfTheBusiness(), is(ordersBefore));
    assertThat(eventsOfTheBusiness(), is(eventsBefore));
    // The one hold that was made is the one given back, by its own id.
    assertThat(HELD, hasSize(1));
    assertThat(releaseCalls(), is(List.of("/inventory/reservations/" + HELD.get(0) + "/release")));
  }

  @Test
  @Order(3)
  @DisplayName("An answer that makes no sense is the same refusal, and nothing is placed")
  void anAnswerThatMakesNoSenseRefusesTheCheckout() {
    String[][] answers = {
      {"200", "this is not json"},
      {"200", "{}"},
      {"201", "{\"data\":{}}"},
      {"201", "{\"data\":{\"id\":\"not-an-id\"}}"},
      {"502", "<html>bad gateway</html>"}
    };
    String ordersBefore = ordersOfTheBusiness();
    for (String[] answer : answers) {
      BROKEN.add(APPLES);
      brokenStatus = Integer.parseInt(answer[0]);
      brokenBody = answer[1];
      String key = Ids.newId().toString();
      JsonObject refused = refused(placeOnline(key, APPLES), 503);
      assertThat(answer[1], refused.getString("code"), is("ORDER_INVENTORY_UNAVAILABLE"));
      assertThat(placedWith(key), is("0"));
    }
    assertThat(ordersOfTheBusiness(), is(ordersBefore));
  }

  @Test
  @Order(4)
  @DisplayName("However often it fails, and with its circuit open or not, it is the same 503")
  void anOpenCircuitIsTheSameRefusal() {
    BROKEN.add(APPLES);
    String ordersBefore = ordersOfTheBusiness();
    int attempts = 8;
    for (int i = 0; i < attempts; i++) {
      String key = Ids.newId().toString();
      JsonObject refused = refused(placeOnline(key, APPLES), 503);
      assertThat("attempt " + i, refused.getString("code"), is("ORDER_INVENTORY_UNAVAILABLE"));
      assertThat(placedWith(key), is("0"));
    }
    assertThat(ordersOfTheBusiness(), is(ordersBefore));
    assertThat(
        "never more than one try a checkout, and fewer once the circuit is open",
        (long) holdCalls().size(),
        lessThanOrEqualTo((long) attempts));
  }

  // ── notification-svc, while a cashier emails a receipt ────────────────────────

  @Test
  @Order(5)
  @DisplayName("An emailed receipt is sent once, as the cashier, and recorded once")
  void anEmailedReceiptIsSentOnceAndRecordedOnce() {
    String order = tillSale();
    Response r = emailReceipt(order, "sam@example.org");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    assertThat(receiptsOf(order), is("1"));

    List<JsonStub.Call> sent = NOTIFICATIONS.calls();
    assertThat(sent, hasSize(1));
    JsonObject mail = Envelopes.parse(sent.get(0).body());
    assertThat(mail.getString("recipient"), is("sam@example.org"));
    assertThat(mail.getString("type"), is("POS_RECEIPT"));
    assertThat(
        "named by an id notification-svc can dedupe on",
        Ids.parse(mail.getString("eventId")).toString(),
        is(mail.getString("eventId")));
    assertThat(sent.get(0).tenantId(), is(T));
    assertThat(sent.get(0).header("X-Roles"), containsString("CASHIER"));
  }

  @Test
  @Order(6)
  @DisplayName("A refusal by notification-svc is told as it is, and no receipt is recorded")
  void aRefusalByNotificationIsToldAsItIsAndRecordsNothing() {
    String order = tillSale();
    sendStatus = 400;
    assertThat(
        refused(emailReceipt(order, "sam@example.org"), 400).getString("code"),
        is("ORDER_RECEIPT_EMAIL_INVALID"));
    sendStatus = 403;
    assertThat(
        refused(emailReceipt(order, "sam@example.org"), 403).getString("code"),
        is("ORDER_RECEIPT_EMAIL_FORBIDDEN"));
    assertThat("no receipt for a mail that was refused", receiptsOf(order), is("0"));
    assertThat("each was a try", NOTIFICATIONS.calls(), hasSize(2));
  }

  @Test
  @Order(7)
  @DisplayName("When notification-svc fails no receipt is recorded, and the retry records it once")
  void aFailedEmailLeavesNoReceiptAndTheRetryRecordsOne() {
    String order = tillSale();
    sendStatus = 500;
    JsonObject refused = refused(emailReceipt(order, "sam@example.org"), 503);
    assertThat(refused.getString("code"), is("ORDER_NOTIFICATION_UNAVAILABLE"));
    assertThat("no receipt row for a mail never sent", receiptsOf(order), is("0"));
    assertThat("it was tried once, and no more", NOTIFICATIONS.calls(), hasSize(1));

    sendStatus = 202;
    Response again = emailReceipt(order, "sam@example.org");
    assertThat(again.readEntity(String.class), again.getStatus(), is(201));
    assertThat("the retry is recorded once", receiptsOf(order), is("1"));
  }

  @Test
  @Order(8)
  @DisplayName("A provider conflict, any server error or an open circuit: the same 503")
  void everyOtherFailureIsTheSame503() {
    String order = tillSale();
    for (int status : new int[] {409, 404, 500, 502, 503, 504, 409}) {
      sendStatus = status;
      JsonObject refused = refused(emailReceipt(order, "sam@example.org"), 503);
      assertThat(
          "notification-svc answered " + status,
          refused.getString("code"),
          is("ORDER_NOTIFICATION_UNAVAILABLE"));
    }
    assertThat(receiptsOf(order), is("0"));
  }

  @Test
  @Order(9)
  @DisplayName("With notification-svc gone altogether, no receipt is recorded either")
  void notificationThatIsGoneRecordsNoReceipt() {
    String order = tillSale();
    NOTIFICATIONS.close();
    notificationsClosed = true;
    JsonObject refused = refused(emailReceipt(order, "sam@example.org"), 503);
    assertThat(refused.getString("code"), is("ORDER_NOTIFICATION_UNAVAILABLE"));
    assertThat(receiptsOf(order), is("0"));
  }

  // ── inventory-svc gone altogether: last, since nothing after it can reach it ──

  @Test
  @Order(10)
  @DisplayName("With inventory-svc gone altogether, the checkout is refused the same way")
  void inventoryThatIsGoneRefusesTheCheckout() {
    INVENTORY.close();
    inventoryClosed = true;
    String key = Ids.newId().toString();
    String ordersBefore = ordersOfTheBusiness();
    String eventsBefore = eventsOfTheBusiness();

    JsonObject refused = refused(placeOnline(key, APPLES), 503);
    assertThat(refused.getString("code"), is("ORDER_INVENTORY_UNAVAILABLE"));
    assertThat(placedWith(key), is("0"));
    assertThat(ordersOfTheBusiness(), is(ordersBefore));
    assertThat(eventsOfTheBusiness(), is(eventsBefore));
  }
}
