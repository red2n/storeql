package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stop-sale and certified scales at order placement, against real Postgres. A line an open recall
 * covers — every pack, or the lot or best-before date the pack declared — is refused at the till
 * and online, with nothing placed and nothing held, while every other product and lot sells. A line
 * weighed on a scale the order's store's register does not hold, or holds uncertified, is refused.
 * One business's recalls and scales never reach another's, and another business naming this one's
 * store is refused as always. When inventory-svc or tenant-svc cannot answer, the sale goes
 * through. A till sale replayed from an offline queue within the grace has already happened and is
 * always placed: a line a recall covered, or a scale not fit for trade weighed, when it was rung up
 * leaves one entry on the audit trail, read by the store's management and nobody else, and a
 * retried replay leaves no second. The outcome is only a flag, so doubt leads to one: a recall open
 * when the sale was rung up and closed before the replay still flags the line, and so does a scale
 * the register cannot show was fit then, or cannot be read for at all. Each entry names who the
 * till says rang the sale up only when the business holds them at the store, and keeps who sent it.
 * A replay dated further back than the grace, or after now beyond the tolerance for a till clock
 * ahead, is judged as a sale made now and refused for a manager, with nothing placed or written.
 * Online, a capture time is not even read.
 *
 * <p>inventory-svc is a stub that answers each business's open recalls — and, asked for a replay,
 * those ended since — and takes holds; iam-svc a stub of the staff directory; tenant-svc the shared
 * stub, with each business's register of scales. Recalls are read afresh for every order here, so a
 * test can open one between two sales.
 */
@HelidonTest
// This class's own settings, never system properties: those outlive a class, and a value read
// before
// this class set one (the recall cache at its default ten seconds) let a recall opened or ended in
// one test reach the next only in the full suite.
// Holds on, so an online order that is refused can be seen to hold nothing.
@AddConfig(key = "storeql.order.inventory.reserve-enforce", value = "true")
// Recalls read afresh on every order, so a test can open one between two sales.
@AddConfig(key = "storeql.order.recall-check.cache-seconds", value = "0")
// GRACE, in hours.
@AddConfig(key = "storeql.order.offline-replay.grace-hours", value = "48")
class SaleChecksIT {

  // An Indian business with two shops, a German one, a Brazilian one whose neighbours cannot be
  // read, and a Kenyan one whose recalls change during a test.
  private static final String T = "01a0f2b0-611e-702c-a97b-d1b8025478f1";
  private static final String T2 = "01a0f2b0-611e-702c-a97b-d1b8025478f2";
  private static final String T3 = "01a0f2b0-611e-702c-a97b-d1b8025478f3";
  private static final String T4 = "01a0f2b0-611e-702c-a97b-d1b8025478f4";
  private static final String SHOP = "01a0f2b0-611e-703c-a378-a4972ea461f1";
  private static final String OTHER_SHOP = "01a0f2b0-611e-703c-a378-a4972ea461f2";
  private static final String SHOP2 = "01a0f2b0-611e-703c-a378-a4972ea461f3";
  private static final String SHOP3 = "01a0f2b0-611e-703c-a378-a4972ea461f4";
  private static final String SHOP4 = "01a0f2b0-611e-703c-a378-a4972ea461f5";

  // Recalled at T: every pack; lot L42; best before 1 to 10 October. Recalled at T2 only.
  private static final String RECALLED = "01a0f2b0-611e-7037-a4b7-c854f0266af1";
  private static final String LOT_RECALLED = "01a0f2b0-611e-7037-a4b7-c854f0266af2";
  private static final String DATE_RECALLED = "01a0f2b0-611e-7037-a4b7-c854f0266af3";
  private static final String CLEAN = "01a0f2b0-611e-7037-a4b7-c854f0266af4";
  private static final String RECALLED_ELSEWHERE = "01a0f2b0-611e-7037-a4b7-c854f0266af5";

  // The registers: three scales at T's shop, one at its other shop, one at T2's and one at T3's.
  private static final String CERTIFIED = "01a0f2b0-611e-7050-9000-0000000000d1";
  private static final String OVERDUE = "01a0f2b0-611e-7050-9000-0000000000d2";
  private static final String OUT_OF_SERVICE = "01a0f2b0-611e-7050-9000-0000000000d3";
  private static final String AT_OTHER_SHOP = "01a0f2b0-611e-7050-9000-0000000000d4";
  private static final String UNKNOWN = "01a0f2b0-611e-7050-9000-0000000000d5";
  private static final String T2_SCALE = "01a0f2b0-611e-7050-9000-0000000000d6";
  private static final String T3_SCALE = "01a0f2b0-611e-7050-9000-0000000000d7";

  // The Kenyan shop's scales, for sales replayed from an offline till: one taken out of service
  // half an hour before the suite began, and one whose re-verification fell due yesterday (UTC),
  // verified and last changed a year ago.
  private static final String T4_OFF = "01a0f2b0-611e-7050-9000-0000000000d8";
  private static final String T4_DUE = "01a0f2b0-611e-7050-9000-0000000000d9";
  private static final Instant TAKEN_OFF =
      Instant.now().minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.SECONDS);
  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

  /** When the register stopped counting {@link #T4_DUE} certified: the end of its due date. */
  private static final Instant DUE_ENDED = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant();

  private static final String CASHIER = "01a0f2b0-611e-7000-8000-0000000000a1";
  private static final String MANAGER = "01a0f2b0-611e-7000-8000-0000000000a2";
  private static final String SHOPPER = "01a0f2b0-611e-700b-bde4-50df0324c3f1";

  /** Another of the Kenyan business's stores, for a manager held to it. */
  private static final String T4_ELSEWHERE = "01a0f2b0-611e-703c-a378-a4972ea461f6";

  /**
   * How far back a replay's capture time is honoured here: two days rather than the default one, so
   * a sale rung up just before today's midnight (UTC) is inside it whatever the hour the suite
   * runs. The rule itself, with the default, is {@code OfflineReplayTest}'s.
   */
  private static final Duration GRACE = Duration.ofHours(48);

  /** Each business's open recalls, as inventory-svc's active list gives them. */
  private static final Map<String, List<String>> RECALLS = new ConcurrentHashMap<>();

  /** The businesses whose recalls inventory-svc cannot answer for. */
  private static final Set<String> DOWN = ConcurrentHashMap.newKeySet();

  /** Each business's recalls closed or cancelled, which only a replay's read is answered with. */
  private static final Map<String, List<String>> ENDED = new ConcurrentHashMap<>();

  /** Who iam-svc's staff directory holds at each business's store, as {@code tenant|store}. */
  private static final Map<String, Set<String>> STAFF_AT = new ConcurrentHashMap<>();

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub INVENTORY;
  private static final JsonStub IAM;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "INR", "IN")
            .with(T2, "EUR", "DE")
            .with(T3, "BRL", "BR")
            .with(T4, "KES", "KE")
            .withStore(T, SHOP, "IN")
            .withStore(T, OTHER_SHOP, "IN")
            .withStore(T2, SHOP2, "DE")
            .withStore(T3, SHOP3, "BR")
            .withStore(T4, SHOP4, "KE")
            .withInstrument(T, SHOP, CERTIFIED, "Deli 1", "CERTIFIED")
            .withInstrument(T, SHOP, OVERDUE, "Deli 2", "OVERDUE")
            .withInstrument(T, SHOP, OUT_OF_SERVICE, "Deli 3", "OUT_OF_SERVICE")
            .withInstrument(T, OTHER_SHOP, AT_OTHER_SHOP, "Cheese counter", "CERTIFIED")
            .withInstrument(T2, SHOP2, T2_SCALE, "Waage 1", "CERTIFIED")
            .withInstrument(T3, SHOP3, T3_SCALE, "Balanca 1", "OVERDUE")
            .withInstrument(
                T4, SHOP4, T4_OFF, "Mizani 1", "OUT_OF_SERVICE", null, TAKEN_OFF.toString())
            .withInstrument(
                T4,
                SHOP4,
                T4_DUE,
                "Mizani 2",
                "OVERDUE",
                "{\"kind\":\"RE_VERIFICATION\",\"performedOn\":\""
                    + TODAY.minusYears(1)
                    + "\",\"passed\":true,\"nextDue\":\""
                    + TODAY.minusDays(1)
                    + "\",\"recordedAt\":\""
                    + TODAY.minusYears(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                    + "\"}",
                TODAY.minusYears(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString())
            .instrumentsUnreadable(T3);
    RECALLS.put(
        T,
        List.of(
            recall(RECALLED, null, null, null),
            recall(LOT_RECALLED, "L42", null, null),
            recall(DATE_RECALLED, null, "2026-10-01", "2026-10-10")));
    RECALLS.put(T2, List.of(recall(RECALLED_ELSEWHERE, null, null, null)));
    RECALLS.put(T3, List.of(recall(RECALLED, null, null, null)));
    DOWN.add(T3);
    INVENTORY = JsonStub.start("inventory-svc");
    INVENTORY.on(
        "GET",
        "/admin/inventory/recalls/active",
        call -> {
          String tenant = call.tenantId() == null ? "" : call.tenantId();
          if (DOWN.contains(tenant)) {
            return new JsonStub.Answer(503, "{\"error\":{\"code\":\"DOWN\",\"message\":\"no\"}}");
          }
          List<String> lines = new ArrayList<>(RECALLS.getOrDefault(tenant, List.of()));
          if (JsonStub.query(call.query()).containsKey("endedSince")) {
            lines.addAll(ENDED.getOrDefault(tenant, List.of()));
          }
          return JsonStub.Answer.ok("[" + String.join(",", lines) + "]");
        });
    INVENTORY.on(
        "POST",
        "/inventory/reservations",
        call -> new JsonStub.Answer(201, "{\"data\":{\"id\":\"" + Ids.newId() + "\"}}"));
    // The Kenyan shop's cashier is staff there; anybody else is left out, as the directory does.
    STAFF_AT.put(T4 + "|" + SHOP4, Set.of(CASHIER));
    IAM = JsonStub.start("iam-svc");
    IAM.on(
        "GET",
        "/auth/admin/staff-users",
        call -> {
          String at = call.tenantId() + "|" + call.header("X-Store-Ids");
          String id = JsonStub.query(call.query()).getOrDefault("ids", "");
          boolean held = STAFF_AT.getOrDefault(at, Set.of()).contains(id);
          return JsonStub.Answer.ok(
              held ? "[{\"userId\":\"" + id + "\",\"email\":\"staff@shop.test\"}]" : "[]");
        });
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    INVENTORY.close();
    IAM.close();
    PG.stop();
  }

  @BeforeEach
  void reset() {
    INVENTORY.reset();
    IAM.reset();
  }

  // ── harness ──────────────────────────────────────────────────────────────────

  /** One scope line of an open recall, as inventory-svc's active list gives it. */
  private static String recall(String variant, String lot, String from, String to) {
    return "{\"recallId\":\""
        + Ids.newId()
        + "\",\"reference\":\"R-2026-017\",\"kind\":\"RECALL\",\"hazard\":\"ALLERGEN\","
        + "\"customerNotice\":null,\"variantId\":\""
        + variant
        + "\",\"batchNo\":"
        + quoted(lot)
        + ",\"expiryFrom\":"
        + quoted(from)
        + ",\"expiryTo\":"
        + quoted(to)
        + "}";
  }

  /** The same scope line, saying when its recall opened. */
  private static String recallOpened(String variant, Instant openedAt) {
    String line = recall(variant, null, null, null);
    return line.substring(0, line.length() - 1) + ",\"openedAt\":\"" + openedAt + "\"}";
  }

  private static String quoted(String s) {
    return s == null ? "null" : "\"" + s + "\"";
  }

  private static String key() {
    return Ids.newId().toString();
  }

  private Invocation.Builder as(
      String tenant, String user, String roles, String stores, String key) {
    Invocation.Builder b =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", user)
            .header("X-Roles", roles)
            .header("Idempotency-Key", key);
    return stores == null ? b : b.header("X-Store-Ids", stores);
  }

  private static Entity<String> json(String body) {
    return Entity.entity(body, MediaType.APPLICATION_JSON);
  }

  /** A till sale rung up by a cashier at {@code store}. */
  private Response tillSale(String tenant, String store, String key, String... lines) {
    return as(tenant, CASHIER, "CASHIER", store, key).post(json(sale(store, "POS", lines)));
  }

  /** An online collection order placed by a signed-in shopper. */
  private Response online(String tenant, String store, String key, String... lines) {
    return as(tenant, SHOPPER, "CUSTOMER", null, key).post(json(sale(store, "ONLINE", lines)));
  }

  /**
   * A till sale rung up by the cashier at {@code capturedAt} while the till was offline, sent now
   * by the cashier from its queue, as the till's offline queue sends it.
   */
  private Response replay(
      String tenant, String store, String key, Instant capturedAt, String... lines) {
    String body = rungUpBy(captured(sale(store, "POS", lines), capturedAt.toString()), CASHIER);
    return as(tenant, CASHIER, "CASHIER", store, key).post(json(body));
  }

  /** An order body saying who the till recorded as ringing it up, or nobody for {@code null}. */
  private static String rungUpBy(String body, String userId) {
    if (userId == null) return body;
    return body.substring(0, body.length() - 1) + ",\"rungUpBy\":\"" + userId + "\"}";
  }

  /** The id of the order a replay placed, which must have been placed. */
  private String placedReplay(
      String tenant, String store, String key, Instant capturedAt, String... lines) {
    return Envelopes.created(replay(tenant, store, key, capturedAt, lines)).getString("id");
  }

  /** An order body with a capture time added. */
  private static String captured(String body, String capturedAt) {
    return body.substring(0, body.length() - 1) + ",\"capturedAt\":\"" + capturedAt + "\"}";
  }

  private static String sale(String store, String channel, String... lines) {
    return "{\"storeId\":\""
        + store
        + "\",\"channel\":\""
        + channel
        + "\",\"fulfilmentType\":\""
        + ("POS".equals(channel) ? "INSTORE" : "PICKUP")
        + "\",\"items\":["
        + String.join(",", lines)
        + "]}";
  }

  /** One of it, with whatever the pack declared. */
  private static String line(String variant, String pack) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":1,\"unitPrice\":5.00" + pack + "}";
  }

  private static String plain(String variant) {
    return line(variant, "");
  }

  private static String lot(String variant, String batchNo) {
    return line(variant, ",\"batchNo\":\"" + batchNo + "\"");
  }

  private static String bestBefore(String variant, String expiry) {
    return line(variant, ",\"expiry\":\"" + expiry + "\"");
  }

  /** 375 grams at 12.00 a kilogram, weighed on {@code instrument}. */
  private static String weighed(String variant, String instrument) {
    return "{\"variantId\":\""
        + variant
        + "\",\"qty\":0.375,\"unitPrice\":12.00,\"weighingInstrumentId\":\""
        + instrument
        + "\"}";
  }

  /** The problem a refusal with the expected status answered with. */
  private static JsonObject refused(Response r, int status) {
    return Envelopes.parse(Envelopes.bodyOf(r, status));
  }

  private static String placedWith(String tenant, String key) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM \"order\".orders WHERE tenant_id = '"
            + tenant
            + "' AND idempotency_key = '"
            + key
            + "'");
  }

  private static String ordersAt(String tenant, String storeId) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM \"order\".orders WHERE tenant_id = '"
            + tenant
            + "' AND store_id = '"
            + storeId
            + "'");
  }

  /** order-svc's store-status projection, as tenant-svc's StoreStatusChanged would leave it. */
  private static void storeBelongsTo(String storeId, String tenant) {
    Envelopes.exec(
        PG,
        "INSERT INTO \"order\".store_status (store_id, tenant_id, status) VALUES ('"
            + storeId
            + "', '"
            + tenant
            + "', 'ACTIVE') ON CONFLICT (store_id) DO NOTHING");
  }

  /** The audit trail as a member of staff with {@code roles} at {@code tenant} asks for it. */
  private Response trailAs(String tenant, String roles, String stores, String... query) {
    WebTarget t = target.path("/admin/audit/events").queryParam("limit", "100");
    for (int i = 0; i + 1 < query.length; i += 2) t = t.queryParam(query[i], query[i + 1]);
    Invocation.Builder b =
        t.request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", MANAGER)
            .header("X-Roles", roles);
    return stores == null ? b.get() : b.header("X-Store-Ids", stores).get();
  }

  /** The trail's entries about one order, as {@code roles} at {@code tenant} read it. */
  private List<JsonObject> entriesAbout(
      String orderId, String tenant, String roles, String stores, String... query) {
    return Envelopes.okArray(trailAs(tenant, roles, stores, query))
        .getValuesAs(JsonObject.class)
        .stream()
        .filter(o -> orderId.equals(o.getString("orderId", null)))
        .toList();
  }

  /**
   * An offline sale's entry is read by the business's management at the order's store and by nobody
   * else: not a manager held to another of its stores, not a cashier, and not another business's
   * staff of any role, even naming this store.
   */
  private void onlyThisStoresManagementSees(String orderId, String type) {
    assertThat("the owner", entriesAbout(orderId, T4, "OWNER", null, "type", type).size(), is(1));
    assertThat(
        "a manager held to another store",
        entriesAbout(orderId, T4, "MANAGER", T4_ELSEWHERE, "type", type).size(),
        is(0));
    assertThat(
        "who cannot name this one either",
        trailAs(T4, "MANAGER", T4_ELSEWHERE, "store", SHOP4).getStatus(),
        is(403));
    assertThat("a cashier", trailAs(T4, "CASHIER", SHOP4, "type", type).getStatus(), is(403));
    for (String roles : new String[] {"OWNER", "MANAGER"}) {
      assertThat(roles, entriesAbout(orderId, T2, roles, null, "type", type).size(), is(0));
      assertThat(roles, entriesAbout(orderId, T2, roles, SHOP4, "store", SHOP4).size(), is(0));
    }
    for (String roles : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(roles, trailAs(T2, roles, SHOP4, "type", type).getStatus(), is(403));
    }
  }

  /** How many offline-sale entries one order carries. */
  private static String flagsOn(String tenant, String orderId) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM \"order\".offline_sale_flags WHERE tenant_id = '"
            + tenant
            + "' AND order_id = '"
            + orderId
            + "'");
  }

  /** The offline-sale entries on one order as "KIND:line", comma-separated, in order. */
  private static String flagKindsOn(String tenant, String orderId) {
    return Envelopes.scalar(
        PG,
        "SELECT coalesce(string_agg(kind || ':' || line_no, ',' ORDER BY kind, line_no), '')"
            + " FROM \"order\".offline_sale_flags WHERE tenant_id = '"
            + tenant
            + "' AND order_id = '"
            + orderId
            + "'");
  }

  /** How many offline-sale entries a business's orders carry. */
  private static String flagsOf(String tenant) {
    return Envelopes.scalar(
        PG, "SELECT count(*) FROM \"order\".offline_sale_flags WHERE tenant_id = '" + tenant + "'");
  }

  private static long holds() {
    return INVENTORY.calls().stream()
        .filter(c -> "POST".equals(c.method()) && "/inventory/reservations".equals(c.path()))
        .count();
  }

  // ── stop-sale ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A product recalled in every pack is refused at the till, and nothing is placed")
  void aRecalledProductIsRefusedAtTheTill() {
    String key = key();
    JsonObject no = refused(tillSale(T, SHOP, key, plain(CLEAN), plain(RECALLED)), 409);
    assertThat(no.getString("code"), is("ORDER_LINE_RECALLED"));
    assertThat(
        no.getString("detail"),
        containsString(
            "item 2 is under product recall R-2026-017 (undeclared allergen), every pack"));
    assertThat("nothing was placed", placedWith(T, key), is("0"));
  }

  @Test
  @DisplayName("Online too: the recalled line is refused before any stock is held")
  void aRecalledProductIsRefusedOnlineAndNothingIsHeld() {
    String key = key();
    JsonObject no = refused(online(T, SHOP, key, plain(CLEAN), plain(RECALLED)), 409);
    assertThat(no.getString("code"), is("ORDER_LINE_RECALLED"));
    assertThat("nothing was placed", placedWith(T, key), is("0"));
    assertThat("nothing was held", holds(), is(0L));
  }

  @Test
  @DisplayName("What nobody recalled sells at the same store, at the till and online")
  void whatNobodyRecalledSells() {
    Envelopes.created(tillSale(T, SHOP, key(), plain(CLEAN)));
    Envelopes.created(online(T, SHOP, key(), plain(CLEAN)));
    assertThat("the online order held its stock as always", holds(), is(1L));
  }

  @Test
  @DisplayName("A recalled lot is refused when the pack names it; every other lot sells")
  void aRecalledLotIsRefusedWhenThePackNamesIt() {
    JsonObject no = refused(tillSale(T, SHOP, key(), lot(LOT_RECALLED, "L42")), 409);
    assertThat(no.getString("code"), is("ORDER_LINE_RECALLED"));
    assertThat(no.getString("detail"), containsString("lot L42"));
    assertThat(
        "printed either way, the same lot",
        refused(tillSale(T, SHOP, key(), lot(LOT_RECALLED, " l42 ")), 409).getString("code"),
        is("ORDER_LINE_RECALLED"));
    Envelopes.created(tillSale(T, SHOP, key(), lot(LOT_RECALLED, "L43")));
    // A pack that declared no lot is the cashier's to check, as the till asks them to.
    Envelopes.created(tillSale(T, SHOP, key(), plain(LOT_RECALLED)));
  }

  @Test
  @DisplayName("A recall by best-before date refuses a pack inside it and sells one outside")
  void aRecallByBestBeforeDate() {
    String inside = bestBefore(DATE_RECALLED, "2026-10-05");
    JsonObject no = refused(tillSale(T, SHOP, key(), inside), 409);
    assertThat(no.getString("detail"), containsString("best before 2026-10-01 to 2026-10-10"));
    Envelopes.created(tillSale(T, SHOP, key(), bestBefore(DATE_RECALLED, "2026-10-11")));
    assertThat(
        "an expiry that is not a date is refused at the door",
        refused(tillSale(T, SHOP, key(), bestBefore(CLEAN, "05/10/2026")), 400).getString("code"),
        is("ORDER_LINE_EXPIRY_INVALID"));
  }

  @Test
  @DisplayName("One business's recalls never reach another's sales, either way")
  void anotherBusinesssRecallNeverBlocksThisOne() {
    Envelopes.created(tillSale(T, SHOP, key(), plain(RECALLED_ELSEWHERE)));
    INVENTORY.reset();
    Envelopes.created(tillSale(T2, SHOP2, key(), plain(RECALLED)));
    var asked =
        INVENTORY.calls().stream()
            .filter(c -> "/admin/inventory/recalls/active".equals(c.path()))
            .map(JsonStub.Call::tenantId)
            .toList();
    assertThat("the selling business's own recalls, and only those", asked, is(List.of(T2)));
  }

  @Test
  @DisplayName("Another business naming this one's store is refused as always; nothing moves")
  void anotherBusinessNeverReachesThisOnesStore() {
    storeBelongsTo(SHOP, T);
    String before = ordersAt(T, SHOP);
    for (String roles : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      Response r =
          as(T2, CASHIER, roles, SHOP, key())
              .post(json(sale(SHOP, "POS", plain(RECALLED), weighed(CLEAN, CERTIFIED))));
      assertThat(roles, refused(r, 409).getString("code"), is("STORE_NOT_OPERATIONAL"));
    }
    Response storekeeper =
        as(T2, CASHIER, "STOREKEEPER", SHOP, key()).post(json(sale(SHOP, "POS", plain(CLEAN))));
    assertThat(refused(storekeeper, 403).getString("code"), is("FORBIDDEN"));
    assertThat(
        "nor online",
        refused(online(T2, SHOP, key(), plain(RECALLED)), 409).getString("code"),
        is("STORE_NOT_OPERATIONAL"));
    assertThat("nothing moved here", ordersAt(T, SHOP), is(before));
    assertThat("nor there", ordersAt(T2, SHOP), is("0"));
  }

  @Test
  @DisplayName("When inventory-svc cannot say what is recalled, the sale goes through")
  void inventorySvcThatCannotAnswerNeverRefusesASale() {
    Envelopes.created(tillSale(T3, SHOP3, key(), plain(RECALLED)));
    Envelopes.created(online(T3, SHOP3, key(), plain(RECALLED)));
  }

  @Test
  @DisplayName("A retried sale that stands is answered with it, whatever was recalled since")
  void aRetriedSaleIsAnsweredWithTheSaleThatStands() {
    String key = key();
    String first = Envelopes.created(tillSale(T4, SHOP4, key, plain(CLEAN))).getString("id");
    RECALLS.put(T4, List.of(recall(CLEAN, null, null, null)));
    try {
      String again = Envelopes.created(tillSale(T4, SHOP4, key, plain(CLEAN))).getString("id");
      assertThat(again, is(first));
      assertThat(
          "a new sale of it is refused",
          refused(tillSale(T4, SHOP4, key(), plain(CLEAN)), 409).getString("code"),
          is("ORDER_LINE_RECALLED"));
    } finally {
      RECALLS.remove(T4);
    }
  }

  // ── certified scales ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A line weighed on a scale certified at the order's store is placed and recorded")
  void aCertifiedScaleAtTheOrdersStoreWeighsForTrade() {
    JsonObject order = Envelopes.created(tillSale(T, SHOP, key(), weighed(CLEAN, CERTIFIED)));
    assertThat(
        order.getJsonArray("items").getJsonObject(0).getString("weighingInstrumentId"),
        is(CERTIFIED));
  }

  @Test
  @DisplayName("A line weighed on a scale that is overdue or out of service is refused")
  void aScaleThatIsNotCertifiedIsRefused() {
    String key = key();
    JsonObject no = refused(tillSale(T, SHOP, key, weighed(CLEAN, OVERDUE)), 409);
    assertThat(no.getString("code"), is("ORDER_SCALE_NOT_CERTIFIED"));
    assertThat(
        no.getString("detail"), containsString("Deli 2, which is overdue for re-verification"));
    assertThat("nothing was placed", placedWith(T, key), is("0"));
    JsonObject off = refused(tillSale(T, SHOP, key(), weighed(CLEAN, OUT_OF_SERVICE)), 409);
    assertThat(off.getString("detail"), containsString("Deli 3, which is out of service"));
    assertThat(
        "online too, and nothing is held",
        refused(online(T, SHOP, key(), weighed(CLEAN, OVERDUE)), 409).getString("code"),
        is("ORDER_SCALE_NOT_CERTIFIED"));
    assertThat(holds(), is(0L));
  }

  @Test
  @DisplayName("A scale of another store, or one nobody registered, is refused")
  void aScaleTheStoresRegisterDoesNotHoldIsRefused() {
    for (String scale : new String[] {AT_OTHER_SHOP, UNKNOWN}) {
      JsonObject no = refused(tillSale(T, SHOP, key(), weighed(CLEAN, scale)), 409);
      assertThat(no.getString("code"), is("ORDER_SCALE_NOT_CERTIFIED"));
      assertThat(no.getString("detail"), containsString("not in this store's register"));
    }
  }

  @Test
  @DisplayName("One business's scales never weigh for another's, either way")
  void anotherBusinesssScaleIsRefused() {
    JsonObject theirs = refused(tillSale(T, SHOP, key(), weighed(CLEAN, T2_SCALE)), 409);
    assertThat(theirs.getString("detail"), containsString("not in this store's register"));
    JsonObject ours = refused(tillSale(T2, SHOP2, key(), weighed(CLEAN, CERTIFIED)), 409);
    assertThat(ours.getString("code"), is("ORDER_SCALE_NOT_CERTIFIED"));
    Envelopes.created(tillSale(T2, SHOP2, key(), weighed(CLEAN, T2_SCALE)));
  }

  @Test
  @DisplayName("When the register cannot be read, the weighed sale goes through")
  void aRegisterThatCannotBeReadNeverRefusesASale() {
    Envelopes.created(tillSale(T3, SHOP3, key(), weighed(CLEAN, T3_SCALE)));
  }

  // ── a till sale replayed from the offline queue ───────────────────────────────

  @Test
  @DisplayName("Rung up offline after a recall opened: placed when replayed, flagged for a manager")
  void aSaleRungUpOfflineAfterARecallOpenedIsPlacedAndFlagged() {
    Instant opened = Instant.now().minus(Duration.ofMinutes(10));
    RECALLS.put(T4, List.of(recallOpened(CLEAN, opened)));
    try {
      String before = key();
      String early = placedReplay(T4, SHOP4, before, opened.minusSeconds(300), plain(CLEAN));
      assertThat("rung up before it opened: nothing to flag", flagsOn(T4, early), is("0"));

      // Rung up after it opened, with the recalled jar second in the basket: the sale happened,
      // so it stands, and the line is put in front of a manager.
      String after = key();
      Instant rungUp = opened.plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
      String[] basket = {plain(RECALLED_ELSEWHERE), plain(CLEAN)};
      String late = placedReplay(T4, SHOP4, after, rungUp, basket);
      assertThat("the sale already made stands", placedWith(T4, after), is("1"));

      List<JsonObject> seen =
          entriesAbout(late, T4, "MANAGER", SHOP4, "type", "OFFLINE_SALE_OF_RECALLED_ITEM");
      assertThat("one entry, for the one recalled line", seen.size(), is(1));
      JsonObject entry = seen.get(0);
      assertThat(entry.getString("type"), is("OFFLINE_SALE_OF_RECALLED_ITEM"));
      assertThat(entry.getString("variantId"), is(CLEAN));
      assertThat("the recall's reference", entry.getString("detail"), is("R-2026-017"));
      assertThat("the cashier who rang it up", entry.getString("actorId"), is(CASHIER));
      assertThat("and sent it", entry.getString("replayedBy"), is(CASHIER));
      assertThat(entry.getString("storeId"), is(SHOP4));
      assertThat("when it was rung up", Instant.parse(entry.getString("occurredAt")), is(rungUp));
      assertThat(
          entry.getString("reason"),
          containsString(
              "when it was rung up, item 2 was under product recall R-2026-017 (undeclared"
                  + " allergen), every pack"));
      onlyThisStoresManagementSees(late, "OFFLINE_SALE_OF_RECALLED_ITEM");

      String again = placedReplay(T4, SHOP4, after, rungUp, basket);
      assertThat("a retried replay gets the sale that stands", again, is(late));
      assertThat("and it is flagged once", flagsOn(T4, late), is("1"));

      assertThat(
          "a sale made now is refused, as before",
          refused(tillSale(T4, SHOP4, key(), plain(CLEAN)), 409).getString("code"),
          is("ORDER_LINE_RECALLED"));
      String online = key();
      String body = captured(sale(SHOP4, "ONLINE", plain(CLEAN)), rungUp.toString());
      Response shopper = as(T4, SHOPPER, "CUSTOMER", null, online).post(json(body));
      assertThat(
          "a capture time means nothing online",
          refused(shopper, 409).getString("code"),
          is("ORDER_LINE_RECALLED"));
      assertThat(placedWith(T4, online), is("0"));
      assertThat("nothing was held", holds(), is(0L));
    } finally {
      RECALLS.remove(T4);
    }
  }

  @Test
  @DisplayName("A capture time older than the grace is judged as a sale made now: refused, parked")
  void aCaptureTimeOlderThanTheGraceIsJudgedAsASaleMadeNow() {
    Instant opened = Instant.now().minus(Duration.ofHours(1));
    RECALLS.put(T4, List.of(recallOpened(CLEAN, opened)));
    String flaggedBefore = flagsOf(T4);
    try {
      // Claimed to be rung up long before the recall opened, but further back than the grace, so
      // the claim cannot be told from a forged one.
      String key = key();
      Instant claimed = Instant.now().minus(GRACE).minus(Duration.ofHours(1));
      JsonObject no = refused(replay(T4, SHOP4, key, claimed, plain(CLEAN)), 409);
      assertThat(no.getString("code"), is("ORDER_LINE_RECALLED"));
      assertThat(
          no.getString("detail"), containsString("too long ago to be taken on the till's word"));
      assertThat(no.getString("detail"), containsString("Hand the sale to a manager."));
      assertThat("nothing was placed", placedWith(T4, key), is("0"));

      String ahead = key();
      Instant later = Instant.now().plus(Duration.ofHours(1));
      assertThat(
          "a capture time later than now is not taken either",
          refused(replay(T4, SHOP4, ahead, later, plain(CLEAN)), 409).getString("code"),
          is("ORDER_LINE_RECALLED"));
      assertThat(placedWith(T4, ahead), is("0"));
    } finally {
      RECALLS.remove(T4);
    }
    String scale = key();
    Instant claimed = TAKEN_OFF.minus(GRACE).minus(Duration.ofHours(1));
    JsonObject no = refused(replay(T4, SHOP4, scale, claimed, weighed(CLEAN, T4_OFF)), 409);
    assertThat(no.getString("code"), is("ORDER_SCALE_NOT_CERTIFIED"));
    assertThat(no.getString("detail"), containsString("Hand the sale to a manager."));
    assertThat(placedWith(T4, scale), is("0"));
    assertThat("and no entry was written", flagsOf(T4), is(flaggedBefore));
  }

  @Test
  @DisplayName("A capture time that is not an instant, or a cashier who is not an id, is refused")
  void aCaptureTimeThatIsNotAnInstantIsRefused() {
    String body = captured(sale(SHOP4, "POS", plain(CLEAN)), "yesterday");
    Response r = as(T4, CASHIER, "CASHIER", SHOP4, key()).post(json(body));
    assertThat(refused(r, 400).getString("code"), is("ORDER_CAPTURED_AT_INVALID"));
    String key = key();
    String notAnId =
        rungUpBy(captured(sale(SHOP4, "POS", plain(CLEAN)), aMinuteAgo()), "1-1-1-1-1");
    Response who = as(T4, CASHIER, "CASHIER", SHOP4, key).post(json(notAnId));
    assertThat(refused(who, 400).getString("code"), is("INVALID_UUID"));
    assertThat("nothing was placed", placedWith(T4, key), is("0"));
  }

  private static String aMinuteAgo() {
    return Instant.now().minusSeconds(60).toString();
  }

  @Test
  @DisplayName("Another business replaying a sale at this one's store is refused; nothing moves")
  void anotherBusinessReplayingAtThisOnesStoreIsRefused() {
    storeBelongsTo(SHOP, T);
    String before = ordersAt(T, SHOP);
    String earlier = Instant.now().minus(Duration.ofMinutes(20)).toString();
    for (String roles : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      String body =
          captured(sale(SHOP, "POS", plain(RECALLED), weighed(CLEAN, CERTIFIED)), earlier);
      Response r = as(T2, CASHIER, roles, SHOP, key()).post(json(body));
      assertThat(roles, refused(r, 409).getString("code"), is("STORE_NOT_OPERATIONAL"));
    }
    assertThat("nothing moved here", ordersAt(T, SHOP), is(before));
    assertThat("nor there", ordersAt(T2, SHOP), is("0"));
  }

  // ── who may replay a sale: the key opens nothing that the caller could not already reach ──

  @Test
  @DisplayName("A sale's key replayed by another business at this store reads nothing of the sale")
  void aSalesKeyReplayedByAnotherBusinessReadsNothingOfTheSale() {
    storeBelongsTo(SHOP, T);
    String key = key();
    String saleId = Envelopes.created(tillSale(T, SHOP, key, plain(CLEAN))).getString("id");
    String before = ordersAt(T, SHOP);

    // Every role of another business, naming this store and this sale's own key and basket: the
    // store is not theirs, so it is refused, and the refusal says nothing of the sale.
    for (String roles : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      Response r = as(T2, CASHIER, roles, SHOP, key).post(json(sale(SHOP, "POS", plain(CLEAN))));
      String body = Envelopes.bodyOf(r, 409);
      assertThat(roles, Envelopes.parse(body).getString("code"), is("STORE_NOT_OPERATIONAL"));
      assertThat(roles, body, not(containsString(saleId)));
    }
    for (String roles : new String[] {"STOREKEEPER", "CUSTOMER"}) {
      Response r = as(T2, CASHIER, roles, SHOP, key).post(json(sale(SHOP, "POS", plain(CLEAN))));
      String body = Envelopes.bodyOf(r, 403);
      assertThat(roles, Envelopes.parse(body).getString("code"), is("FORBIDDEN"));
      assertThat(roles, body, not(containsString(saleId)));
    }
    String onlineBody = Envelopes.bodyOf(online(T2, SHOP, key, plain(CLEAN)), 409);
    assertThat(Envelopes.parse(onlineBody).getString("code"), is("STORE_NOT_OPERATIONAL"));
    assertThat(onlineBody, not(containsString(saleId)));

    assertThat("the sale stands, once", placedWith(T, key), is("1"));
    assertThat("nothing moved here", ordersAt(T, SHOP), is(before));
    assertThat("nothing was placed for them", placedWith(T2, key), is("0"));
    assertThat("nor at their store", ordersAt(T2, SHOP), is("0"));
  }

  @Test
  @DisplayName("A key is per business, and not per case: the same key elsewhere is its own sale")
  void aKeyIsPerBusinessAndNotPerCase() {
    String key = key();
    String ours = Envelopes.created(tillSale(T, SHOP, key, plain(CLEAN))).getString("id");
    String theirs = Envelopes.created(tillSale(T2, SHOP2, key, plain(CLEAN))).getString("id");
    assertThat("two businesses, two sales", theirs, not(is(ours)));
    assertThat(placedWith(T, key), is("1"));
    assertThat(placedWith(T2, key), is("1"));

    // Each is replayed to its own, never to the other's.
    assertThat(Envelopes.created(tillSale(T, SHOP, key, plain(CLEAN))).getString("id"), is(ours));
    assertThat(
        Envelopes.created(tillSale(T2, SHOP2, key, plain(CLEAN))).getString("id"), is(theirs));
    // A till that sends the key in capitals is sending the same key: the same sale, not a second.
    String shouted = key.toUpperCase(Locale.ROOT);
    assertThat(
        Envelopes.created(tillSale(T, SHOP, shouted, plain(CLEAN))).getString("id"), is(ours));
    assertThat(placedWith(T, key), is("1"));
    assertThat(placedWith(T2, key), is("1"));
  }

  @Test
  @DisplayName("Staff held to another store replaying a sale are refused; it is not read back")
  void staffHeldToAnotherStoreCannotReplayASale() {
    String key = key();
    String saleId = Envelopes.created(tillSale(T, SHOP, key, plain(CLEAN))).getString("id");
    String before = ordersAt(T, SHOP);

    for (String roles : new String[] {"CASHIER", "MANAGER"}) {
      Response r =
          as(T, CASHIER, roles, OTHER_SHOP, key).post(json(sale(SHOP, "POS", plain(CLEAN))));
      String body = Envelopes.bodyOf(r, 403);
      assertThat(roles, Envelopes.parse(body).getString("code"), is("STORE_ACCESS_DENIED"));
      assertThat(roles, body, not(containsString(saleId)));
    }
    // An online order is no different: held to another store, they may not place at this one.
    Response online =
        as(T, CASHIER, "STOREKEEPER", OTHER_SHOP, key)
            .post(json(sale(SHOP, "ONLINE", plain(CLEAN))));
    String heldOnline = Envelopes.bodyOf(online, 403);
    assertThat(Envelopes.parse(heldOnline).getString("code"), is("STORE_ACCESS_DENIED"));
    assertThat(heldOnline, not(containsString(saleId)));
    assertThat("nothing moved", ordersAt(T, SHOP), is(before));
    assertThat(placedWith(T, key), is("1"));

    // A manager held to this store, syncing the cashier's queue, gets the sale that stands.
    Response syncing =
        as(T, MANAGER, "MANAGER", SHOP, key).post(json(sale(SHOP, "POS", plain(CLEAN))));
    assertThat(Envelopes.created(syncing).getString("id"), is(saleId));
    assertThat(ordersAt(T, SHOP), is(before));
  }

  @Test
  @DisplayName("A shopper cannot place or replay a till sale, whatever time it claims to be from")
  void aShopperCannotReplayATillSale() {
    String key = key();
    String saleId = Envelopes.created(tillSale(T, SHOP, key, plain(CLEAN))).getString("id");
    String before = ordersAt(T, SHOP);
    String flagged = flagsOf(T);
    String aSale = sale(SHOP, "POS", plain(CLEAN));

    String own = Envelopes.bodyOf(as(T, SHOPPER, "CUSTOMER", null, key).post(json(aSale)), 403);
    assertThat(Envelopes.parse(own).getString("code"), is("FORBIDDEN"));
    assertThat(own, not(containsString(saleId)));
    // Nor does a capture time, which only the till's replay carries, open the till to a shopper.
    String claimed = captured(aSale, aMinuteAgo());
    String fresh = key();
    Response replay = as(T, SHOPPER, "CUSTOMER", null, fresh).post(json(claimed));
    assertThat(Envelopes.parse(Envelopes.bodyOf(replay, 403)).getString("code"), is("FORBIDDEN"));
    assertThat(placedWith(T, fresh), is("0"));
    assertThat("nothing moved", ordersAt(T, SHOP), is(before));
    assertThat("and nothing was flagged for a manager", flagsOf(T), is(flagged));
  }

  @Test
  @DisplayName("A sale sent with no key, or a key that is not a UUIDv7, is refused and not placed")
  void aSaleWithoutAProperKeyIsNotPlaced() {
    String before = ordersAt(T, SHOP);
    String body = sale(SHOP, "POS", plain(CLEAN));

    // No key at all, in the header or the body.
    Response none =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", CASHIER)
            .header("X-Roles", "CASHIER")
            .header("X-Store-Ids", SHOP)
            .post(json(body));
    assertThat(
        Envelopes.parse(Envelopes.bodyOf(none, 400)).getString("code"),
        is("MISSING_IDEMPOTENCY_KEY"));

    // A version 4 id, and a key made from a clock reading: neither is a UUIDv7.
    for (String bad :
        new String[] {"7c9e6679-7425-40de-944b-e07fc1f90ae7", "pos-1760000000000-order"}) {
      Response r = as(T, CASHIER, "CASHIER", SHOP, bad).post(json(body));
      assertThat(bad, Envelopes.bodyOf(r, 400), containsString("IDEMPOTENCY_KEY_INVALID"));
    }
    // The body's own key is held to the same rule.
    String inBody = body.substring(0, body.length() - 1) + ",\"idempotencyKey\":\"not-a-key\"}";
    Response viaBody =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", CASHIER)
            .header("X-Roles", "CASHIER")
            .header("X-Store-Ids", SHOP)
            .post(json(inBody));
    assertThat(Envelopes.bodyOf(viaBody, 400), containsString("IDEMPOTENCY_KEY_INVALID"));
    assertThat("nothing was placed", ordersAt(T, SHOP), is(before));
  }

  @Test
  @DisplayName("Weighed offline after its scale lapsed: placed on replay, flagged for a manager")
  void aWeighedSaleRungUpOfflineAfterItsScaleLapsedIsPlacedAndFlagged() {
    String line = weighed(CLEAN, T4_OFF);
    // Before its last change the register cannot say whether it was already out of service — it
    // keeps no time of a change of status, and any edit moves the last change — so that sale is
    // flagged as one nobody can show was fit, never passed.
    String early = placedReplay(T4, SHOP4, key(), TAKEN_OFF.minusSeconds(300), line);
    List<JsonObject> doubt =
        entriesAbout(early, T4, "MANAGER", SHOP4, "type", "OFFLINE_SALE_ON_UNFIT_SCALE");
    assertThat("weighed before its last change: in doubt, so flagged", doubt.size(), is(1));
    assertThat(doubt.get(0).getString("detail"), is("UNKNOWN_AT_SALE"));
    assertThat(
        doubt.get(0).getString("reason"),
        containsString(
            "item 1 was weighed on Mizani 1, which is out of service now; whether it could be used"
                + " for trade here when the sale was rung up cannot be shown"));

    String after = key();
    Instant rungUp = TAKEN_OFF.plusSeconds(300);
    String late = placedReplay(T4, SHOP4, after, rungUp, line);
    assertThat("the sale already made stands", placedWith(T4, after), is("1"));
    List<JsonObject> seen =
        entriesAbout(late, T4, "MANAGER", SHOP4, "type", "OFFLINE_SALE_ON_UNFIT_SCALE");
    assertThat(seen.size(), is(1));
    JsonObject entry = seen.get(0);
    assertThat(entry.getString("variantId"), is(CLEAN));
    assertThat("the register's standing", entry.getString("detail"), is("OUT_OF_SERVICE"));
    assertThat(entry.getString("actorId"), is(CASHIER));
    assertThat(Instant.parse(entry.getString("occurredAt")), is(rungUp));
    assertThat(
        entry.getString("reason"),
        containsString("item 1 was weighed on Mizani 1 when it could not be used for trade here"));
    onlyThisStoresManagementSees(late, "OFFLINE_SALE_ON_UNFIT_SCALE");

    String again = placedReplay(T4, SHOP4, after, rungUp, line);
    assertThat("a retried replay gets the sale that stands", again, is(late));
    assertThat(
        "and it is flagged once", flagKindsOn(T4, late), is("OFFLINE_SALE_ON_UNFIT_SCALE:1"));
    assertThat(
        "a sale made now is refused, as before",
        refused(tillSale(T4, SHOP4, key(), line), 409).getString("code"),
        is("ORDER_SCALE_NOT_CERTIFIED"));
  }

  @Test
  @DisplayName("Weighed before midnight on a scale due that day: not flagged; after it: flagged")
  void aWeighedSaleRungUpBeforeTheScaleFellDueIsNotFlagged() {
    String due = weighed(CLEAN, T4_DUE);
    String before = placedReplay(T4, SHOP4, key(), DUE_ENDED.minusSeconds(600), due);
    assertThat(flagsOn(T4, before), is("0"));
    String after = placedReplay(T4, SHOP4, key(), DUE_ENDED.plusSeconds(1), due);
    List<JsonObject> seen =
        entriesAbout(after, T4, "OWNER", null, "type", "OFFLINE_SALE_ON_UNFIT_SCALE");
    assertThat(seen.size(), is(1));
    assertThat(seen.get(0).getString("detail"), is("OVERDUE"));
    assertThat(seen.get(0).getString("reason"), containsString("Mizani 2"));
  }

  @Test
  @DisplayName("Weighed offline on a scale whose register cannot be read: placed, flagged in doubt")
  void aReplayWeighedOnAScaleWhoseRegisterCannotBeReadIsFlaggedInDoubt() {
    // The Brazilian business's register cannot be read. A sale made now goes through unchecked, as
    // every unreadable answer does; a replay is never refused either, so the line weighed on the
    // scale is flagged as one nobody can show was fit, never passed in silence.
    String line = weighed(CLEAN, T3_SCALE);
    String order = placedReplay(T3, SHOP3, key(), Instant.now().minusSeconds(600), line);
    List<JsonObject> seen =
        entriesAbout(order, T3, "OWNER", null, "type", "OFFLINE_SALE_ON_UNFIT_SCALE");
    assertThat("placed, and the weighed line flagged", seen.size(), is(1));
    JsonObject entry = seen.get(0);
    assertThat(entry.getString("detail"), is("UNKNOWN_AT_SALE"));
    assertThat(entry.getString("variantId"), is(CLEAN));
    assertThat(entry.getString("actorId"), is(CASHIER));
    assertThat(
        entry.getString("reason"),
        containsString(
            "item 1 was weighed on a scale; whether it could be used for trade here when the sale"
                + " was rung up cannot be shown, because the register could not be read when the"
                + " sale was synced."));
    assertThat("and only that entry", flagsOn(T3, order), is("1"));
    assertThat(
        "another business sees none of it",
        entriesAbout(order, T2, "OWNER", null, "type", "OFFLINE_SALE_ON_UNFIT_SCALE").size(),
        is(0));
    Envelopes.created(tillSale(T3, SHOP3, key(), line));
  }

  @Test
  @DisplayName("A recall open when the sale was rung up, closed before the replay, still flags it")
  void aRecallClosedBeforeTheReplayStillFlagsALineItCoveredWhenRungUp() {
    Instant opened = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
    Instant closed = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
    ENDED.put(T4, List.of(recallEnded(CLEAN, opened, closed, "CLOSED")));
    try {
      Instant rungUp = opened.plus(Duration.ofMinutes(30));
      String during = placedReplay(T4, SHOP4, key(), rungUp, plain(CLEAN));
      List<JsonObject> seen =
          entriesAbout(during, T4, "MANAGER", SHOP4, "type", "OFFLINE_SALE_OF_RECALLED_ITEM");
      assertThat("rung up while it was open: flagged, though closed since", seen.size(), is(1));
      assertThat(Instant.parse(seen.get(0).getString("occurredAt")), is(rungUp));
      assertThat(seen.get(0).getString("reason"), containsString("since been closed"));
      var asked =
          INVENTORY.calls().stream()
              .filter(c -> "/admin/inventory/recalls/active".equals(c.path()))
              .map(c -> JsonStub.query(c.query()).get("endedSince"))
              .filter(Objects::nonNull)
              .toList();
      assertThat("the replay asked for recalls ended since", asked.isEmpty(), is(false));
      assertThat(
          "reaching back at least to when the sale was rung up",
          Instant.parse(asked.get(0)),
          lessThanOrEqualTo(rungUp));

      String after = placedReplay(T4, SHOP4, key(), closed.plusSeconds(60), plain(CLEAN));
      assertThat("rung up after it closed: nothing to flag", flagsOn(T4, after), is("0"));
      String before = placedReplay(T4, SHOP4, key(), opened.minusSeconds(60), plain(CLEAN));
      assertThat("rung up before it opened: nothing to flag", flagsOn(T4, before), is("0"));
      Envelopes.created(tillSale(T4, SHOP4, key(), plain(CLEAN)));
    } finally {
      ENDED.remove(T4);
    }
  }

  @Test
  @DisplayName("A manager syncing a cashier's queue: the entry names the cashier, and the manager")
  void anEntryNamesWhoRangTheSaleUpOnlyWhenTheBusinessHoldsThemAtTheStore() {
    Instant opened = Instant.now().minus(Duration.ofHours(2));
    RECALLS.put(T4, List.of(recallOpened(CLEAN, opened)));
    try {
      Instant rungUp = opened.plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.SECONDS);
      JsonObject byCashier = syncedEntry(CASHIER, rungUp);
      assertThat("who rang it up, as recorded", byCashier.getString("actorId"), is(CASHIER));
      assertThat("who sent it", byCashier.getString("replayedBy"), is(MANAGER));
      JsonStub.Call asked = IAM.calls().get(0);
      assertThat("asked of this business's directory", asked.tenantId(), is(T4));
      assertThat("at this store", asked.header("X-Store-Ids"), is(SHOP4));

      // Another business's login, a login nobody holds, and a queue kept by an older till that
      // recorded nobody: placed and flagged all the same, naming an unknown member of staff.
      for (String named : new String[] {SHOPPER, Ids.newId().toString(), null}) {
        JsonObject entry = syncedEntry(named, rungUp);
        assertThat(String.valueOf(named), entry.getString("actorId", null), is((String) null));
        assertThat(entry.getString("replayedBy"), is(MANAGER));
      }
      List<JsonObject> own =
          entriesAbout(byCashier.getString("orderId"), T4, "MANAGER", SHOP4, "actor", CASHIER);
      assertThat("the actor filter finds the cashier's own entry", own.size(), is(1));
    } finally {
      RECALLS.remove(T4);
    }
  }

  /**
   * One replay of the recalled jam, rung up at {@code rungUp} by {@code named} as the till
   * recorded, sent by a manager at the store; its one entry on the trail.
   */
  private JsonObject syncedEntry(String named, Instant rungUp) {
    String body = rungUpBy(captured(sale(SHOP4, "POS", plain(CLEAN)), rungUp.toString()), named);
    Response r = as(T4, MANAGER, "MANAGER", SHOP4, key()).post(json(body));
    String order = Envelopes.created(r).getString("id");
    List<JsonObject> seen =
        entriesAbout(order, T4, "OWNER", null, "type", "OFFLINE_SALE_OF_RECALLED_ITEM");
    assertThat(seen.size(), is(1));
    return seen.get(0);
  }

  @Test
  @DisplayName("A till clock a little ahead is honoured as captured now; a moment ago, too")
  void aTillClockALittleAheadIsHonouredAsCapturedNow() {
    RECALLS.put(T4, List.of(recallOpened(CLEAN, Instant.now().minus(Duration.ofHours(1)))));
    try {
      Instant sent = Instant.now();
      String ahead = placedReplay(T4, SHOP4, key(), sent.plusSeconds(60), plain(CLEAN));
      List<JsonObject> seen =
          entriesAbout(ahead, T4, "OWNER", null, "type", "OFFLINE_SALE_OF_RECALLED_ITEM");
      assertThat("placed and flagged, not refused", seen.size(), is(1));
      Instant rungUp = Instant.parse(seen.get(0).getString("occurredAt"));
      assertThat("as rung up now, never in the future", rungUp, lessThanOrEqualTo(Instant.now()));
      assertThat(rungUp.isBefore(sent), is(false));

      // Accepted on purpose: a sale that says it was rung up a moment ago is recorded, and the
      // flag is the control.
      String moment = placedReplay(T4, SHOP4, key(), Instant.now().minusSeconds(2), plain(CLEAN));
      assertThat(flagsOn(T4, moment), is("1"));
    } finally {
      RECALLS.remove(T4);
    }
  }

  @Test
  @DisplayName("Online, a capture time is not even read: one that is not an instant is placed")
  void anOnlineOrderWithACaptureTimeThatIsNotAnInstantIsPlaced() {
    String key = key();
    String body = rungUpBy(captured(sale(SHOP, "ONLINE", plain(CLEAN)), "yesterday"), "nobody");
    Response r = as(T, SHOPPER, "CUSTOMER", null, key).post(json(body));
    String order = Envelopes.created(r).getString("id");
    assertThat(placedWith(T, key), is("1"));
    assertThat("and nothing is flagged", flagsOn(T, order), is("0"));
  }

  /** A scope line of every pack of {@code variant}, open from {@code opened} to {@code ended}. */
  private static String recallEnded(String variant, Instant opened, Instant ended, String as) {
    String line = recallOpened(variant, opened);
    return line.substring(0, line.length() - 1)
        + ",\"endedAt\":\""
        + ended
        + "\",\"endedAs\":\""
        + as
        + "\"}";
  }
}
