package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The day report: a day is the store's own local day (a store east and a store west of UTC each
 * land on their own date, a DST day is 23 hours, an unreadable zone is UTC and says so); it counts
 * only its own store with every term of the cash formula; it is stored once, a re-run answers the
 * stored one and a correction is a new version that names what it replaces; a day with a till still
 * open is refused; and another business and a manager held to another store are kept out.
 *
 * <p>tenant-svc is a stub that gives each business its currency and its stores' time zones. A
 * tender carries no currency of its own, so "two currencies" here is two businesses, each reported
 * in its own.
 */
@HelidonTest
class ZReportIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");
  private static final JsonStub TENANTS;

  /** tenant id -> its currency; a business not listed is GBP. */
  private static final Map<String, String> CURRENCIES = new ConcurrentHashMap<>();

  /** tenant id -> the JSON of its stores. */
  private static final Map<String, String> STORES = new ConcurrentHashMap<>();

  static {
    TENANTS = JsonStub.start("tenant-svc");
    TENANTS.on(
        "GET",
        "/admin/tenant",
        call ->
            JsonStub.Answer.ok(
                "{\"id\":\""
                    + call.tenantId()
                    + "\",\"currency\":\""
                    + CURRENCIES.getOrDefault(call.tenantId(), "GBP")
                    + "\",\"country\":\"GB\"}"));
    TENANTS.on(
        "GET",
        "/admin/stores",
        call ->
            new JsonStub.Answer(
                200,
                "{\"data\":["
                    + STORES.getOrDefault(call.tenantId(), "")
                    + "],\"meta\":{\"nextCursor\":null}}"));
  }

  @Inject WebTarget target;
  @Inject PaymentRepository payments;

  @AfterAll
  static void stop() {
    TENANTS.close();
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static void storeIn(UUID tenant, UUID store, String zone) {
    String one =
        "{\"id\":\""
            + store
            + "\",\"type\":\"STORE\",\"country\":\"GB\",\"timezone\":\""
            + zone
            + "\"}";
    STORES.merge(tenant.toString(), one, (a, b) -> a + "," + b);
  }

  private void sale(UUID tenant, UUID store, String method, String amount, Instant at) {
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenant,
            Ids.newId(),
            new BigDecimal(amount),
            method,
            null,
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            at,
            store),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenant, id, "{}"));
  }

  private void refund(UUID tenant, UUID store, String method, String amount) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO payment.refund_tenders"
                    + " (id, tenant_id, order_id, payment_id, amount, method, created_at, store_id)"
                    + " VALUES (?,?,?,?,?,?, now(), ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenant);
      ps.setObject(3, Ids.newId());
      ps.setObject(4, Ids.newId());
      ps.setBigDecimal(5, new BigDecimal(amount));
      ps.setString(6, method);
      ps.setObject(7, store);
      ps.executeUpdate();
    }
  }

  private Answer settle(Caller who, UUID store, String date, String counted) {
    return ItCalls.post(
        target,
        "/admin/cash/z-report",
        who,
        "{\"storeId\":\""
            + store
            + "\",\"businessDate\":\""
            + date
            + "\",\"countedCash\":"
            + counted
            + "}");
  }

  private Answer settle(Caller who, UUID store, String date, String counted, String extra) {
    return ItCalls.post(
        target,
        "/admin/cash/z-report",
        who,
        "{\"storeId\":\""
            + store
            + "\",\"businessDate\":\""
            + date
            + "\",\"countedCash\":"
            + counted
            + ","
            + extra
            + "}");
  }

  private Answer read(Caller who, UUID store, String date, String extra) {
    return ItCalls.get(
        target, "/admin/cash/z-report?storeId=" + store + "&businessDate=" + date + extra, who);
  }

  private static void eq(JsonObject o, String key, String expected) {
    assertThat(
        key + " in " + o,
        o.getJsonNumber(key).bigDecimalValue().compareTo(new BigDecimal(expected)),
        is(0));
  }

  private static Instant at(String iso) {
    return Instant.parse(iso);
  }

  // ── the store's own day ────────────────────────────────────────────────────

  @Test
  @DisplayName("A UTC+13 store and a UTC-8 store each land on their own local date")
  void theStoresOwnDay() {
    UUID tenant = Ids.newId();
    UUID east = Ids.newId();
    UUID west = Ids.newId();
    storeIn(tenant, east, "Pacific/Auckland"); // NZDT, UTC+13 in January
    storeIn(tenant, west, "America/Los_Angeles"); // PST, UTC-8 in January
    // East's 15 Jan is [14 Jan 11:00Z, 15 Jan 11:00Z); its 16 Jan starts then.
    sale(tenant, east, "CASH", "10.00", at("2026-01-14T12:00:00Z")); // 15 Jan east, 14 Jan UTC
    sale(tenant, east, "CASH", "20.00", at("2026-01-15T12:00:00Z")); // 16 Jan east, 15 Jan UTC
    // West's 15 Jan is [15 Jan 08:00Z, 16 Jan 08:00Z).
    sale(tenant, west, "CASH", "30.00", at("2026-01-16T05:00:00Z")); // 15 Jan west, 16 Jan UTC
    sale(tenant, west, "CASH", "40.00", at("2026-01-15T07:00:00Z")); // 14 Jan west, 15 Jan UTC
    Caller owner = Caller.owner(tenant);

    Answer e15 = settle(owner, east, "2026-01-15", "0");
    Answer e16 = settle(owner, east, "2026-01-16", "0");
    Answer w15 = settle(owner, west, "2026-01-15", "0");
    Answer w14 = settle(owner, west, "2026-01-14", "0");

    assertThat(e15.body().toString(), e15.status(), is(201));
    eq(e15.data(), "totalSales", "10");
    eq(e16.data(), "totalSales", "20");
    eq(w15.data(), "totalSales", "30");
    eq(w14.data(), "totalSales", "40");
    assertThat(e15.data().getString("timeZone"), is("Pacific/Auckland"));
    assertThat(e15.data().getBoolean("zoneAssumed"), is(false));
    assertThat(w15.data().getString("timeZone"), is("America/Los_Angeles"));
  }

  @Test
  @DisplayName("With no date named, the day settled and read is today where the store is")
  void noDateNamedIsTheStoresToday() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    // Kiritimati is UTC+14: for ten hours of every UTC day its date is a day ahead of UTC's, and
    // it is never behind, so the store's today is told apart from the server's at those hours.
    storeIn(tenant, store, "Pacific/Kiritimati");
    Caller owner = Caller.owner(tenant);
    String before = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati")).toString();

    Answer settled =
        ItCalls.post(
            target,
            "/admin/cash/z-report",
            owner,
            "{\"storeId\":\"" + store + "\",\"countedCash\":0}");
    String after = LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati")).toString();

    assertThat(settled.body().toString(), settled.status(), is(201));
    assertThat(settled.data().getString("timeZone"), is("Pacific/Kiritimati"));
    assertThat(
        settled.data().getString("businessDate"),
        org.hamcrest.Matchers.anyOf(is(before), is(after)));
    Answer stored = ItCalls.get(target, "/admin/cash/z-report?storeId=" + store, owner);
    // Unless the store's midnight fell between the two calls, reading with no date finds it.
    if (LocalDate.now(java.time.ZoneId.of("Pacific/Kiritimati"))
        .toString()
        .equals(settled.data().getString("businessDate"))) {
      assertThat(stored.body().toString(), stored.status(), is(200));
      assertThat(stored.data().getString("id"), is(settled.data().getString("id")));
    }
  }

  @Test
  @DisplayName("A day the clocks go forward is 23 hours: the window follows the zone's own rules")
  void aDstDayIsTwentyThreeHours() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    storeIn(tenant, store, "America/New_York");
    // 8 Mar 2026: EST until 02:00, so the local day is [05:00Z, 9 Mar 04:00Z).
    sale(tenant, store, "CASH", "5.00", at("2026-03-08T04:59:00Z")); // 7 Mar 23:59 local
    sale(tenant, store, "CASH", "7.00", at("2026-03-08T05:00:00Z")); // first minute
    sale(tenant, store, "CASH", "11.00", at("2026-03-09T03:59:00Z")); // last minute, 23:59 EDT
    sale(tenant, store, "CASH", "13.00", at("2026-03-09T04:00:00Z")); // 9 Mar

    Answer a = settle(Caller.owner(tenant), store, "2026-03-08", "0");

    assertThat(a.body().toString(), a.status(), is(201));
    eq(a.data(), "totalSales", "18");
  }

  @Test
  @DisplayName("A store whose zone cannot be read is counted in UTC, and the report says so")
  void anUnreadableZoneIsUtcAndSaysSo() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId(); // tenant-svc has no record of it
    sale(tenant, store, "CASH", "9.00", at("2026-02-10T23:30:00Z"));
    sale(tenant, store, "CASH", "1.00", at("2026-02-11T00:30:00Z"));

    Answer a = settle(Caller.owner(tenant), store, "2026-02-10", "0");

    assertThat(a.body().toString(), a.status(), is(201));
    eq(a.data(), "totalSales", "9");
    assertThat(a.data().getString("timeZone"), is("UTC"));
    assertThat(a.data().getBoolean("zoneAssumed"), is(true));
  }

  // ── every term, and only this store ────────────────────────────────────────

  @Test
  @DisplayName("Two stores of one business: each report counts its own store and every term")
  void eachStoresDayCountsOnlyItsOwnAndEveryTerm() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId(); // another business, same store ids, another currency
    CURRENCIES.put(other.toString(), "JPY");
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    Caller manager = new Caller(tenant, Ids.newId(), "MANAGER");
    UUID tillA = openTill(manager, storeA, "100.00");
    UUID tillB = openTill(manager, storeB, "10.00");
    Instant now = Instant.now();
    sale(tenant, storeA, "CASH", "50.00", now);
    sale(tenant, storeA, "CARD", "30.00", now);
    sale(tenant, storeB, "CASH", "999.00", now);
    refund(tenant, storeA, "CASH", "10.00");
    refund(tenant, storeA, "CARD", "5.00");
    refund(tenant, storeB, "CASH", "777.00");
    sale(other, storeA, "CASH", "12345.00", now);
    refund(other, storeA, "CASH", "1000.00");
    assertThat(
        ItCalls.post(
                target,
                "/admin/cash/till-sessions/" + tillA + "/drops",
                manager,
                "{\"amount\":20.00}")
            .status(),
        is(201));
    assertThat(movement(manager, storeA, tillA, "PAY_IN", "15.00").status(), is(201));
    assertThat(movement(manager, storeA, tillA, "PAY_OUT", "8.00").status(), is(201));
    for (UUID till : new UUID[] {tillA, tillB}) {
      assertThat(
          ItCalls.post(
                  target,
                  "/admin/cash/till-sessions/" + till + "/close",
                  manager,
                  "{\"countedCash\":0}")
              .status(),
          is(200));
    }
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    Answer a = settle(manager, storeA, today, "130.00");
    Answer b = settle(manager, storeB, today, "0");

    assertThat(a.body().toString(), a.status(), is(201));
    JsonObject r = a.data();
    eq(r, "openingFloat", "100");
    eq(r, "cashSales", "50");
    eq(r, "cardSales", "30");
    eq(r, "totalSales", "80");
    eq(r, "cashRefunds", "10");
    eq(r, "totalRefunds", "15");
    eq(r, "payIns", "15");
    eq(r, "payOuts", "8");
    eq(r, "cashDrops", "20");
    eq(r, "expectedCash", "127"); // 100 + 50 - 10 + 15 - 8 - 20
    eq(r, "overShort", "3");
    assertThat(r.getString("currency"), is("GBP"));
    eq(b.data(), "expectedCash", "232"); // 10 + 999 - 777
    eq(b.data(), "cashRefunds", "777");

    // The other business, in yen, sees only its own money at the same store id.
    Answer theirs = settle(Caller.owner(other), storeA, today, "0");
    assertThat(theirs.body().toString(), theirs.status(), is(201));
    eq(theirs.data(), "totalSales", "12345");
    eq(theirs.data(), "cashRefunds", "1000");
    eq(theirs.data(), "openingFloat", "0");
    assertThat(theirs.data().getString("currency"), is("JPY"));
    // And ours was not moved by theirs.
    eq(read(manager, storeA, today, "").data(), "totalSales", "80");
  }

  private UUID openTill(Caller who, UUID store, String floatAmount) {
    Answer a =
        ItCalls.post(
            target,
            "/admin/cash/till-sessions",
            who,
            "{\"storeId\":\"" + store + "\",\"floatAmount\":" + floatAmount + "}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(a.data().getString("id"));
  }

  private Answer movement(Caller who, UUID store, UUID session, String direction, String amount) {
    return ItCalls.post(
        target,
        "/admin/cash/movements",
        who,
        "{\"tillSessionId\":\""
            + session
            + "\",\"storeId\":\""
            + store
            + "\",\"direction\":\""
            + direction
            + "\",\"amount\":"
            + amount
            + ",\"reason\":\"it\"}");
  }

  // ── once, and corrected by a new version ───────────────────────────────────

  @Test
  @DisplayName(
      "A settled day answers the stored report; a correction is a new version naming its predecessor")
  void generatedOnce() throws Exception {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    storeIn(tenant, store, "Europe/Warsaw");
    Caller owner = Caller.owner(tenant);
    sale(tenant, store, "CASH", "10.00", at("2026-04-10T10:00:00Z"));

    Answer first = settle(owner, store, "2026-04-10", "10.00");
    assertThat(first.body().toString(), first.status(), is(201));
    assertThat(first.data().getInt("version"), is(1));
    assertThat(first.data().getBoolean("regenerated"), is(true));
    String firstId = first.data().getString("id");

    // Money arrives late; a second ask, with another count, still answers the day as settled.
    sale(tenant, store, "CASH", "5.00", at("2026-04-10T11:00:00Z"));
    Answer again = settle(owner, store, "2026-04-10", "999.00");
    assertThat(again.status(), is(200));
    assertThat(again.data().getString("id"), is(firstId));
    assertThat(again.data().getBoolean("regenerated"), is(false));
    eq(again.data(), "totalSales", "10");
    eq(again.data(), "countedCash", "10");

    // A correction needs a reason and names the report it replaces.
    Answer noReason =
        settle(owner, store, "2026-04-10", "15.00", "\"correctionOf\":\"" + firstId + "\"");
    assertThat(noReason.status(), is(400));
    assertThat(noReason.code(), is("Z_REPORT_CORRECTION_REASON_REQUIRED"));
    Answer fixed =
        settle(
            owner,
            store,
            "2026-04-10",
            "15.00",
            "\"correctionOf\":\"" + firstId + "\",\"reason\":\"a late card slip\"");
    assertThat(fixed.body().toString(), fixed.status(), is(201));
    assertThat(fixed.data().getInt("version"), is(2));
    assertThat(fixed.data().getString("replacesId"), is(firstId));
    assertThat(fixed.data().getString("correctionReason"), is("a late card slip"));
    eq(fixed.data(), "totalSales", "15");

    // Only the latest can be corrected.
    Answer stale =
        settle(
            owner,
            store,
            "2026-04-10",
            "15.00",
            "\"correctionOf\":\"" + firstId + "\",\"reason\":\"x\"");
    assertThat(stale.status(), is(409));
    assertThat(stale.code(), is("Z_REPORT_NOT_LATEST"));

    // Reads: latest by default, any version by number, and the first is exactly as it was.
    assertThat(read(owner, store, "2026-04-10", "").data().getInt("version"), is(2));
    JsonObject v1 = read(owner, store, "2026-04-10", "&version=1").data();
    assertThat(v1.getString("id"), is(firstId));
    eq(v1, "totalSales", "10");
    eq(v1, "countedCash", "10");
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM payment.z_reports WHERE tenant_id = '"
                + tenant
                + "' AND store_id = '"
                + store
                + "'"),
        is("2"));

    // A correction of a day never settled is not found.
    Answer never =
        settle(
            owner,
            store,
            "2026-04-11",
            "0",
            "\"correctionOf\":\"" + firstId + "\",\"reason\":\"x\"");
    assertThat(never.status(), is(404));
    assertThat(never.code(), is("Z_REPORT_NOT_FOUND"));
  }

  @Test
  @DisplayName("A day with a till still open cannot be settled; once it is closed it can")
  void openSessionBlocksTheDay() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller owner = Caller.owner(tenant);
    UUID till = openTill(owner, store, "20.00");
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    Answer blocked = settle(owner, store, today, "0");
    assertThat(blocked.body().toString(), blocked.status(), is(409));
    assertThat(blocked.code(), is("Z_REPORT_SESSIONS_OPEN"));
    assertThat(
        "nothing stored",
        Envelopes.scalar(
            PG, "SELECT count(*) FROM payment.z_reports WHERE tenant_id = '" + tenant + "'"),
        is("0"));

    assertThat(
        ItCalls.post(
                target,
                "/admin/cash/till-sessions/" + till + "/close",
                owner,
                "{\"countedCash\":20}")
            .status(),
        is(200));
    Answer ok = settle(owner, store, today, "20.00");
    assertThat(ok.body().toString(), ok.status(), is(201));
    eq(ok.data(), "expectedCash", "20");
    eq(ok.data(), "overShort", "0");
  }

  // ── who may ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A manager held to store A is refused store B's report, to write and to read")
  void aManagerHeldToOneStoreIsRefusedTheOthers() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    sale(tenant, storeB, "CASH", "10.00", at("2026-05-05T10:00:00Z"));
    Caller heldToA = new Caller(tenant, Ids.newId(), "MANAGER", storeA);

    Answer w = settle(heldToA, storeB, "2026-05-05", "0");
    Answer r = read(heldToA, storeB, "2026-05-05", "");

    assertThat(w.body().toString(), w.status(), is(403));
    assertThat(w.code(), is("STORE_ACCESS_DENIED"));
    assertThat(r.status(), is(403));
    assertThat(r.code(), is("STORE_ACCESS_DENIED"));
    assertThat(
        "nothing written",
        Envelopes.scalar(
            PG, "SELECT count(*) FROM payment.z_reports WHERE tenant_id = '" + tenant + "'"),
        is("0"));
    assertThat(settle(heldToA, storeA, "2026-05-05", "0").status(), is(201));
  }
}
