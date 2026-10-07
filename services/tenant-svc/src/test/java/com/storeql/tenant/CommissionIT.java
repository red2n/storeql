package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Commission arrangements over HTTP and a real database.
 *
 * <p>Three things here cannot be had from a unit test. <b>A correction supersedes and moves the
 * people across</b>, which takes two writes under a deferrable foreign key and a dated assignment
 * per person. <b>One arrangement per person per day</b> is a unique index, and the answer a manager
 * gets when it fires has to name the day rather than say "database error". And <b>rating a
 * period</b> is the call the service that holds the sales makes, so it is driven here exactly as
 * that service will drive it.
 */
@HelidonTest
class CommissionIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String C = "/admin/workforce/commission";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private record Answer(int status, JsonObject body, String text) {

    JsonObject data() {
      return body.getJsonObject("data");
    }

    List<JsonObject> list() {
      return body.getJsonArray("data").getValuesAs(JsonObject.class);
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  private Answer call(
      String method, String path, String json, String tenant, String user, String roles) {
    return call(method, path, json, tenant, user, roles, null);
  }

  private Answer call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String stores) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        t =
            eq < 0
                ? t.queryParam(pair, "")
                : t.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    Invocation.Builder b = t.request(MediaType.APPLICATION_JSON);
    if (user != null) b = b.header("X-User-Id", user);
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    Entity<String> body = Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON);
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "PUT" -> b.put(body);
          case "DELETE" -> b.delete();
          default -> b.post(body);
        };
    String text = r.readEntity(String.class);
    return new Answer(r.getStatus(), asObject(text), text);
  }

  private static JsonObject asObject(String text) {
    if (text == null || text.isBlank()) return JsonObject.EMPTY_JSON_OBJECT;
    try {
      return Json.createReader(new StringReader(text)).readObject();
    } catch (RuntimeException e) {
      return JsonObject.EMPTY_JSON_OBJECT;
    }
  }

  /** A business with one store and one person on its staff. */
  private record Shop(String tenant, String store, String person, String manager) {}

  private Shop shop() {
    return shop("GB", "GBP", "London", "E1 6AN", "Europe/London");
  }

  /** A business in a country and currency of the test's choosing, with one store and one person. */
  private Shop shop(String country, String currency, String city, String pincode, String zone) {
    String tenant = TenantOnboarding.onboard(target, "commission", country, currency);
    String manager = Ids.newId().toString();
    Answer store =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"High Street\",\"code\":\"HS-"
                + Ids.newId().toString().substring(0, 8)
                + "\",\"line1\":\"1 High Street\",\"city\":\""
                + city
                + "\",\"country\":\""
                + country
                + "\",\"pincode\":\""
                + pincode
                + "\",\"timezone\":\""
                + zone
                + "\"}",
            tenant,
            manager,
            "OWNER");
    assertThat(store.text(), store.status(), is(201));
    String storeId = store.data().getString("id");
    String person = Ids.newId().toString();
    Answer assigned =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + person + "\",\"storeId\":\"" + storeId + "\",\"role\":\"CASHIER\"}",
            tenant,
            manager,
            "OWNER");
    assertThat(assigned.text(), assigned.status(), is(201));
    return new Shop(tenant, storeId, person, manager);
  }

  private Answer scheme(Shop shop, String body) {
    return call("POST", C + "/schemes", body, shop.tenant(), shop.manager(), "OWNER");
  }

  private static String flat(String name, String rate) {
    return "{\"name\":\""
        + name
        + "\",\"basis\":\"PERCENT_OF_NET\",\"bands\":[{\"thresholdFrom\":0,\"rate\":"
        + rate
        + "}]}";
  }

  private Answer assign(Shop shop, String schemeId, String from) {
    return call(
        "PUT",
        C + "/staff/" + shop.person(),
        "{"
            + (schemeId == null ? "" : "\"schemeId\":\"" + schemeId + "\",")
            + "\"effectiveFrom\":\""
            + from
            + "\"}",
        shop.tenant(),
        shop.manager(),
        "OWNER");
  }

  private Answer rate(Shop shop, String from, String to, String days) {
    return call(
        "POST",
        C + "/rate",
        "{\"from\":\""
            + from
            + "\",\"to\":\""
            + to
            + "\",\"sellers\":[{\"userId\":\""
            + shop.person()
            + "\",\"days\":"
            + days
            + "}]}",
        shop.tenant(),
        shop.manager(),
        "OWNER");
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("An arrangement is recorded, somebody is put on it, and their sales earn under it")
  void earns() {
    Shop shop = shop();
    Answer created = scheme(shop, flat("Counter 2%", "2"));
    assertThat(created.text(), created.status(), is(201));
    String schemeId = created.data().getString("id");
    assertThat(created.data().getJsonArray("bands"), hasSize(1));
    assertThat(created.data().getString("status"), is("ACTIVE"));
    // A percentage is a ratio: no currency comes back, because an amount would be a different
    // thing.
    assertThat(
        created.data().containsKey("currency") && !created.data().isNull("currency"), is(false));

    assertThat(assign(shop, schemeId, "2026-09-01").status(), is(201));
    Answer rated =
        rate(
            shop,
            "2026-09-01",
            "2026-09-30",
            "[{\"day\":\"2026-09-10\",\"net\":600.00},{\"day\":\"2026-09-11\",\"net\":400.00}]");
    assertThat(rated.text(), rated.status(), is(200));
    JsonObject person = rated.list().get(0);
    assertThat(person.getString("commission"), is("20.00"));
    assertThat(person.getJsonArray("segments"), hasSize(1));
    JsonObject segment = person.getJsonArray("segments").getJsonObject(0);
    assertThat(segment.getString("schemeId"), is(schemeId));
    assertThat("a statement reads as words", segment.getString("schemeName"), is("Counter 2%"));
    assertThat(segment.getString("amount"), is("1000.00"));
    assertThat(segment.getJsonArray("bands").getJsonObject(0).getString("commission"), is("20.00"));

    // Days outside the period are not rated: a statement for September cannot pay for August.
    Answer august =
        rate(shop, "2026-08-01", "2026-08-31", "[{\"day\":\"2026-09-10\",\"net\":600.00}]");
    assertThat(august.list().get(0).getString("commission"), is("0.00"));
  }

  @Test
  @DisplayName("A correction leaves the old rates alone and moves the people across from a day")
  void corrections() {
    Shop shop = shop();
    String first = scheme(shop, flat("Counter", "2")).data().getString("id");
    assertThat(assign(shop, first, "2026-09-01").status(), is(201));

    Answer corrected =
        call(
            "POST",
            C + "/schemes/" + first + "/corrections",
            "{\"name\":\"Counter\",\"basis\":\"PERCENT_OF_NET\",\"effectiveFrom\":\"2026-09-16\","
                + "\"bands\":[{\"thresholdFrom\":0,\"rate\":3}]}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(corrected.text(), corrected.status(), is(201));
    String second = corrected.data().getString("id");
    assertThat(corrected.data().getString("supersedes"), is(first));

    // The old version is left exactly as it was, and says what replaced it.
    Answer old = call("GET", C + "/schemes/" + first, null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(old.data().getJsonArray("bands").getJsonObject(0).getString("rate"), is("2.0000"));
    assertThat(old.data().getString("supersededBy"), is(second));
    assertThat(old.data().getString("status"), is("WITHDRAWN"));

    // The person moved across on the day the correction took effect — so the first half of the
    // month
    // earns 2% and the second half 3%, as two segments.
    Answer rated =
        rate(
            shop,
            "2026-09-01",
            "2026-09-30",
            "[{\"day\":\"2026-09-10\",\"net\":1000.00},{\"day\":\"2026-09-20\",\"net\":1000.00}]");
    List<JsonObject> segments =
        rated.list().get(0).getJsonArray("segments").getValuesAs(JsonObject.class);
    assertThat(rated.text(), segments, hasSize(2));
    assertThat(segments.get(0).getString("schemeId"), is(first));
    assertThat(segments.get(0).getString("commission"), is("20.00"));
    assertThat(segments.get(1).getString("schemeId"), is(second));
    assertThat(segments.get(1).getString("commission"), is("30.00"));
    assertThat(rated.list().get(0).getString("commission"), is("50.00"));

    // A superseded version cannot be corrected again: the one that replaced it is the live one.
    Answer again =
        call(
            "POST",
            C + "/schemes/" + first + "/corrections",
            flat("Counter", "4"),
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("COMMISSION_SCHEME_SUPERSEDED"));
    // Nor can anybody be put on it.
    Answer onOld = assign(shop, first, "2026-10-01");
    assertThat(onOld.status(), is(409));
    assertThat(onOld.code(), is("COMMISSION_SCHEME_NOT_CURRENT"));
  }

  @Test
  @DisplayName(
      "Sales before an arrangement, and after it ended, earn nothing and are still counted")
  void endedAndUnstarted() {
    Shop shop = shop();
    String schemeId = scheme(shop, flat("Counter", "2")).data().getString("id");
    assertThat(assign(shop, schemeId, "2026-09-10").status(), is(201));
    // Taken off commission from the 21st: the arrangement ended, which is not the absence of one.
    assertThat(assign(shop, null, "2026-09-21").status(), is(201));

    Answer rated =
        rate(
            shop,
            "2026-09-01",
            "2026-09-30",
            "[{\"day\":\"2026-09-05\",\"net\":100.00},{\"day\":\"2026-09-15\",\"net\":100.00},"
                + "{\"day\":\"2026-09-25\",\"net\":100.00}]");
    List<JsonObject> segments =
        rated.list().get(0).getJsonArray("segments").getValuesAs(JsonObject.class);
    assertThat(rated.text(), segments, hasSize(3));
    assertThat(segments.get(0).containsKey("schemeId"), is(false));
    assertThat("counted, not dropped", segments.get(0).getString("amount"), is("100.00"));
    assertThat(segments.get(1).getString("commission"), is("2.00"));
    assertThat(segments.get(2).containsKey("schemeId"), is(false));
    assertThat(rated.list().get(0).getString("commission"), is("2.00"));
  }

  @Test
  @DisplayName(
      "A scheme nobody could be paid on is refused, and so is a second arrangement on a day")
  void refusals() {
    Shop shop = shop();
    Answer noBands = scheme(shop, "{\"name\":\"Bad\",\"basis\":\"PERCENT_OF_NET\",\"bands\":[]}");
    assertThat(noBands.status(), is(400));
    Answer above =
        scheme(
            shop,
            "{\"name\":\"Bad\",\"basis\":\"PERCENT_OF_NET\",\"bands\":[{\"thresholdFrom\":500,\"rate\":2}]}");
    assertThat(above.status(), is(400));
    assertThat(above.code(), is("COMMISSION_SCHEME_INVALID"));
    assertThat(above.text(), containsString("starts at zero"));
    Answer perUnitNoCurrency =
        scheme(
            shop,
            "{\"name\":\"Bad\",\"basis\":\"PER_UNIT\",\"bands\":[{\"thresholdFrom\":0,\"rate\":1}]}");
    assertThat(perUnitNoCurrency.code(), is("COMMISSION_SCHEME_INVALID"));
    Answer unknownBasis =
        scheme(
            shop,
            "{\"name\":\"Bad\",\"basis\":\"MARGIN\",\"bands\":[{\"thresholdFrom\":0,\"rate\":2}]}");
    assertThat(unknownBasis.code(), is("COMMISSION_SCHEME_INVALID"));

    String schemeId = scheme(shop, flat("Counter", "2")).data().getString("id");
    assertThat(assign(shop, schemeId, "2026-09-01").status(), is(201));
    Answer twice = assign(shop, schemeId, "2026-09-01");
    assertThat(twice.status(), is(409));
    assertThat(
        "the index decides, and the answer names the day",
        twice.code(),
        is("COMMISSION_ARRANGEMENT_EXISTS"));

    // Somebody who is not staff of this business cannot be put on its schemes.
    Answer stranger =
        call(
            "PUT",
            C + "/staff/" + Ids.newId(),
            "{\"schemeId\":\"" + schemeId + "\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(stranger.status(), is(409));
    assertThat(stranger.code(), is("COMMISSION_NOT_STAFF"));

    // A scheme still in use cannot be withdrawn, and says how many people are on it.
    Answer inUse =
        call("DELETE", C + "/schemes/" + schemeId, null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(inUse.status(), is(409));
    assertThat(inUse.code(), is("COMMISSION_SCHEME_IN_USE"));
    assertThat(inUse.text(), containsString("1 member"));

    Answer unknown =
        call("GET", C + "/schemes/" + Ids.newId(), null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(unknown.status(), is(404));
    assertThat(unknown.code(), is("COMMISSION_SCHEME_NOT_FOUND"));
    Answer badPeriod = rate(shop, "2026-09-30", "2026-09-01", "[]");
    assertThat(badPeriod.status(), is(400));
    assertThat(badPeriod.code(), is("COMMISSION_PERIOD_INVALID"));
  }

  @Test
  @DisplayName("Commission is management's business, and only its own tenant's")
  void authorisation() {
    Shop shop = shop();
    String schemeId = scheme(shop, flat("Counter", "2")).data().getString("id");

    Answer cashierReads =
        call("GET", C + "/schemes", null, shop.tenant(), shop.manager(), "CASHIER");
    assertThat(cashierReads.status(), is(403));
    Answer cashierWrites =
        call(
            "POST", C + "/schemes", flat("Sneaky", "50"), shop.tenant(), shop.manager(), "CASHIER");
    assertThat(cashierWrites.status(), is(403));

    Shop rival = shop();
    Answer theirs =
        call("GET", C + "/schemes/" + schemeId, null, rival.tenant(), rival.manager(), "OWNER");
    assertThat("another business's arrangement is not there at all", theirs.status(), is(404));
    assertThat(theirs.code(), is("COMMISSION_SCHEME_NOT_FOUND"));
    Answer rivalList = call("GET", C + "/schemes", null, rival.tenant(), rival.manager(), "OWNER");
    assertThat(rivalList.list(), hasSize(0));
    // And a rival cannot rate against this business's arrangements.
    Answer rivalRate =
        call(
            "POST",
            C + "/rate",
            "{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"sellers\":[{\"userId\":\""
                + shop.person()
                + "\",\"days\":[{\"day\":\"2026-09-10\",\"net\":1000.00}]}]}",
            rival.tenant(),
            rival.manager(),
            "OWNER");
    assertThat(rivalRate.status(), is(200));
    assertThat(
        "their staff, their arrangements: nothing earns across the boundary",
        rivalRate.list().get(0).getString("commission"),
        is("0.00"));
    assertThat(
        rivalRate.list().get(0).getJsonArray("segments").getJsonObject(0).containsKey("schemeId"),
        is(false));
  }

  @Test
  @DisplayName("A per-unit arrangement pays by the unit, in the currency it names")
  void perUnit() {
    Shop shop = shop();
    Answer created =
        scheme(
            shop,
            "{\"name\":\"Fish counter\",\"basis\":\"PER_UNIT\",\"currency\":\"GBP\","
                + "\"bands\":[{\"thresholdFrom\":0,\"rate\":0.50},{\"thresholdFrom\":100,\"rate\":0.75}]}");
    assertThat(created.text(), created.status(), is(201));
    assertThat(created.data().getString("currency"), is("GBP"));
    assertThat(assign(shop, created.data().getString("id"), "2026-09-01").status(), is(201));

    Answer rated =
        rate(
            shop,
            "2026-09-01",
            "2026-09-30",
            "[{\"day\":\"2026-09-10\",\"net\":900.00,\"units\":80},"
                + "{\"day\":\"2026-09-11\",\"net\":450.00,\"units\":40}]");
    JsonObject person = rated.list().get(0);
    // 120 units: the first hundred at 50p, the next twenty at 75p.
    assertThat(rated.text(), person.getString("commission"), is("65.00"));
    assertThat(person.getString("currency"), is("GBP"));
    JsonObject segment = person.getJsonArray("segments").getJsonObject(0);
    assertThat("units, at a quantity's scale", segment.getString("amount"), is("120.000"));
    assertThat(segment.getJsonArray("bands"), hasSize(2));
  }

  @Test
  @DisplayName(
      "A per-unit band starts at a whole number of units, so no statement currency rounds it")
  void aPerUnitBandStartsAtAWholeUnit() {
    // order-svc keeps a statement line's threshold at the statement currency's minor units: a
    // band at 2.125 units would read 2.13 on a pound statement and a band at 0.5 would read 1 on a
    // yen one. Refused here, in every business, before anything is written.
    for (Shop shop : List.of(shop(), shop("JP", "JPY", "Tokyo", "10001", "Asia/Tokyo"))) {
      for (String fractional : List.of("2.125", "0.5")) {
        Answer refused =
            scheme(
                shop,
                "{\"name\":\"Deli\",\"basis\":\"PER_UNIT\",\"currency\":\"GBP\","
                    + "\"bands\":[{\"thresholdFrom\":0,\"rate\":0.50},{\"thresholdFrom\":"
                    + fractional
                    + ",\"rate\":0.75}]}");
        assertThat(refused.text(), refused.status(), is(400));
        assertThat(refused.code(), is("COMMISSION_SCHEME_INVALID"));
      }
      Answer schemes = call("GET", C + "/schemes", null, shop.tenant(), shop.manager(), "OWNER");
      assertThat("nothing was written", schemes.list(), hasSize(0));

      Answer whole =
          scheme(
              shop,
              "{\"name\":\"Deli\",\"basis\":\"PER_UNIT\",\"currency\":\"GBP\","
                  + "\"bands\":[{\"thresholdFrom\":0,\"rate\":0.50},{\"thresholdFrom\":100.000,"
                  + "\"rate\":0.75}]}");
      assertThat(whole.text(), whole.status(), is(201));
      assertThat(
          "a count, shown at a quantity's scale",
          whole.data().getJsonArray("bands").getJsonObject(1).getString("thresholdFrom"),
          is("100.000"));
    }
  }

  @Test
  @DisplayName("A withdrawn arrangement stops being offered once nobody is on it")
  void withdrawal() {
    Shop shop = shop();
    String schemeId = scheme(shop, flat("Seasonal", "2")).data().getString("id");
    // Both days are in the past, deliberately: "still on it" is asked of today, so a leaving date
    // in
    // the future would mean the person is on the scheme now — which is the refusal below, not this.
    assertThat(assign(shop, schemeId, "2026-01-01").status(), is(201));
    assertThat(assign(shop, null, "2026-02-01").status(), is(201));

    Answer withdrawn =
        call("DELETE", C + "/schemes/" + schemeId, null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(withdrawn.text(), withdrawn.status(), is(200));
    assertThat(withdrawn.data().getString("status"), is("WITHDRAWN"));
    // Gone from what is offered, and still there for a statement to explain itself with.
    Answer offered = call("GET", C + "/schemes", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(offered.list(), hasSize(0));
    Answer all = call("GET", C + "/schemes?all=true", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(all.list(), hasSize(1));
    assertThat(all.list().get(0).getString("status"), is("WITHDRAWN"));
    // What was earned under it before it was withdrawn is still earned.
    Answer rated =
        rate(shop, "2026-01-01", "2026-01-31", "[{\"day\":\"2026-01-10\",\"net\":1000.00}]");
    assertThat(rated.text(), rated.list().get(0).getString("commission"), is("20.00"));
    assertThat(assign(shop, schemeId, "2026-10-01").code(), is("COMMISSION_SCHEME_NOT_CURRENT"));
  }

  @Test
  @DisplayName("A person's arrangements read back as a history, newest first")
  void history() {
    Shop shop = shop();
    String first = scheme(shop, flat("Counter", "2")).data().getString("id");
    String second = scheme(shop, flat("Counter plus", "3")).data().getString("id");
    assertThat(assign(shop, first, "2026-01-01").status(), is(201));
    assertThat(assign(shop, second, "2026-06-01").status(), is(201));
    assertThat(assign(shop, null, "2026-12-01").status(), is(201));

    Answer history =
        call("GET", C + "/staff/" + shop.person(), null, shop.tenant(), shop.manager(), "OWNER");
    List<JsonObject> rows = history.list();
    assertThat(rows, hasSize(3));
    assertThat(rows.get(0).getString("effectiveFrom"), is("2026-12-01"));
    assertThat("the day it stopped", rows.get(0).containsKey("schemeId"), is(false));
    assertThat(rows.get(1).getString("schemeId"), is(second));
    assertThat(rows.get(2).getString("schemeId"), is(first));
    assertThat(history.text(), not(containsString("null")));
    assertThat(rows.get(0).get("note"), is(nullValue()));
  }

  @Test
  @DisplayName(
      "A rating call with more day-rows than one call takes is refused, and nothing is rated")
  void aRatingCallTooLargeIsRefused() {
    Shop shop = shop();
    Answer made = scheme(shop, flat("Counter", "2"));
    assertThat(made.text(), made.status(), is(201));

    // 26 sellers of 390 days each is 10,140 rows: every figure valid, the total over the limit.
    StringBuilder sellers = new StringBuilder();
    for (int s = 0; s < 26; s++) {
      if (s > 0) sellers.append(',');
      sellers.append("{\"userId\":\"").append(Ids.newId()).append("\",\"days\":[");
      for (int d = 0; d < 390; d++) {
        if (d > 0) sellers.append(',');
        sellers
            .append("{\"day\":\"")
            .append(LocalDate.of(2025, 1, 1).plusDays(d))
            .append("\",\"net\":1,\"units\":1}");
      }
      sellers.append("]}");
    }
    Answer refused =
        call(
            "POST",
            C + "/rate",
            "{\"from\":\"2025-01-01\",\"to\":\"2026-01-31\",\"sellers\":[" + sellers + "]}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(refused.status(), is(400));
    assertThat(refused.code(), is("COMMISSION_PERIOD_TOO_LARGE"));
    assertThat(
        "the arrangements are as they were",
        call("GET", C + "/schemes", null, shop.tenant(), shop.manager(), "OWNER").list(),
        hasSize(1));
  }

  /** A person's arrangements, read as a caller of some roles held to some stores. */
  private Answer arrangementsOf(Shop shop, String person, String roles, String heldTo) {
    return call("GET", C + "/staff/" + person, null, shop.tenant(), shop.manager(), roles, heldTo);
  }

  @Test
  @DisplayName(
      "A person's arrangements are read only for somebody at a store the caller is held to")
  void arrangementsAreReadOnlyForPeopleAtTheCallersStores() {
    Shop a = shop();
    Answer second =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Low Street\",\"code\":\"LS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Low Street\",\"city\":\"London\",\"country\":\"GB\","
                + "\"pincode\":\"E1 6AN\",\"timezone\":\"Europe/London\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(second.text(), second.status(), is(201));
    String storeB = second.data().getString("id");
    String atB = Ids.newId().toString();
    Answer assigned =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + atB + "\",\"storeId\":\"" + storeB + "\",\"role\":\"CASHIER\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(assigned.text(), assigned.status(), is(201));
    String scheme = scheme(a, flat("Counter", "2")).data().getString("id");
    for (String person : List.of(a.person(), atB)) {
      Answer on =
          call(
              "PUT",
              C + "/staff/" + person,
              "{\"schemeId\":\"" + scheme + "\",\"effectiveFrom\":\"2026-01-01\"}",
              a.tenant(),
              a.manager(),
              "OWNER");
      assertThat(on.text(), on.status(), is(201));
    }

    // Held to A: A's person, never B's or a stranger, and nothing of theirs leaks.
    Answer own = arrangementsOf(a, a.person(), "MANAGER", a.store());
    assertThat(own.text(), own.status(), is(200));
    assertThat(own.list(), hasSize(1));
    for (String other : List.of(atB, Ids.newId().toString())) {
      Answer refused = arrangementsOf(a, other, "MANAGER", a.store());
      assertThat(refused.text(), refused.status(), is(403));
      assertThat(refused.code(), is("STORE_ACCESS_DENIED"));
      assertThat(refused.text(), not(containsString(scheme)));
    }
    // A manager of both branches, and a caller held to none: B's person too.
    assertThat(arrangementsOf(a, atB, "MANAGER", a.store() + "," + storeB).list(), hasSize(1));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer whole = arrangementsOf(a, atB, role, null);
      assertThat(role + " -> " + whole.text(), whole.status(), is(200));
      assertThat(whole.list(), hasSize(1));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, arrangementsOf(a, a.person(), role, a.store()).status(), is(403));
    }

    // Another business: management held to none reads nothing of ours; held to its own store it is
    // refused; its staff and shoppers are refused outright.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs = arrangementsOf(rival, a.person(), role, null);
      assertThat(theirs.text(), theirs.status(), is(200));
      assertThat(theirs.list(), hasSize(0));
    }
    Answer held = arrangementsOf(rival, a.person(), "MANAGER", rival.store());
    assertThat(held.status(), is(403));
    assertThat(held.code(), is("STORE_ACCESS_DENIED"));
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, arrangementsOf(rival, a.person(), role, null).status(), is(403));
    }
    assertThat("reading moved nothing", arrangementsOf(a, atB, "OWNER", null).list(), hasSize(1));
  }

  /** A second store of the business, with one person assigned at it. */
  private record Branch(String store, String person) {}

  private Branch secondStore(Shop a) {
    Answer store =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Low Street\",\"code\":\"LS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Low Street\",\"city\":\"London\",\"country\":\"GB\","
                + "\"pincode\":\"E1 6AN\",\"timezone\":\"Europe/London\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(store.text(), store.status(), is(201));
    String storeId = store.data().getString("id");
    String person = Ids.newId().toString();
    Answer assigned =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + person + "\",\"storeId\":\"" + storeId + "\",\"role\":\"CASHIER\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(assigned.text(), assigned.status(), is(201));
    return new Branch(storeId, person);
  }

  /** What these people's September sales earn, asked as a caller of some roles at some stores. */
  private Answer rateAs(Shop shop, String roles, String heldTo, String... people) {
    StringBuilder sellers = new StringBuilder();
    for (String p : people) {
      if (sellers.length() > 0) sellers.append(',');
      sellers
          .append("{\"userId\":\"")
          .append(p)
          .append("\",\"days\":[{\"day\":\"2026-09-10\",\"net\":100.00}]}");
    }
    return call(
        "POST",
        C + "/rate",
        "{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"sellers\":[" + sellers + "]}",
        shop.tenant(),
        shop.manager(),
        roles,
        heldTo);
  }

  @Test
  @DisplayName(
      "What somebody's sales earn is worked out for a caller held to stores only for people at"
          + " those stores")
  void ratingIsHeldToTheCallersStores() {
    Shop a = shop();
    Branch b = secondStore(a);
    String head = Ids.newId().toString();
    Answer headOffice =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + head + "\",\"businessWide\":true,\"role\":\"MANAGER\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(headOffice.text(), headOffice.status(), is(201));
    String scheme = scheme(a, flat("Head office plan", "2")).data().getString("id");
    for (String person : List.of(a.person(), b.person(), head)) {
      Answer on =
          call(
              "PUT",
              C + "/staff/" + person,
              "{\"schemeId\":\"" + scheme + "\",\"effectiveFrom\":\"2026-01-01\"}",
              a.tenant(),
              a.manager(),
              "OWNER");
      assertThat(on.text(), on.status(), is(201));
    }

    // Held to A: A's person is rated under their arrangement.
    Answer own = rateAs(a, "MANAGER", a.store(), a.person());
    assertThat(own.text(), own.status(), is(200));
    assertThat(own.list(), hasSize(1));
    assertThat(own.list().get(0).getString("commission"), is("2.00"));

    // Anybody else — B's person, the business-wide manager above them, a stranger — refuses the
    // whole call, alone or beside A's person, and names no scheme, rate or band.
    String stranger = Ids.newId().toString();
    for (List<String> sellers :
        List.of(
            List.of(b.person()),
            List.of(head),
            List.of(stranger),
            List.of(a.person(), b.person()),
            List.of(a.person(), head))) {
      Answer refused = rateAs(a, "MANAGER", a.store(), sellers.toArray(String[]::new));
      assertThat(sellers + " -> " + refused.text(), refused.status(), is(403));
      assertThat(refused.code(), is("STORE_ACCESS_DENIED"));
      assertThat("no arrangement leaks", refused.text(), not(containsString(scheme)));
      assertThat(refused.text(), not(containsString("Head office plan")));
      assertThat(
          "only the people refused are named", refused.text(), not(containsString(a.person())));
    }

    // A manager of both branches: both branches' people, still not head office.
    String both = a.store() + "," + b.store();
    Answer branches = rateAs(a, "MANAGER", both, a.person(), b.person());
    assertThat(branches.text(), branches.status(), is(200));
    assertThat(branches.list(), hasSize(2));
    Answer above = rateAs(a, "MANAGER", both, head);
    assertThat(above.status(), is(403));
    assertThat(above.code(), is("STORE_ACCESS_DENIED"));

    // Held to no store — an owner, a business-wide manager, and order-svc producing a statement,
    // which forwards no stores: anybody in the business.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer whole = rateAs(a, role, null, a.person(), b.person(), head);
      assertThat(role + " -> " + whole.text(), whole.status(), is(200));
      assertThat(whole.list(), hasSize(3));
      for (JsonObject rated : whole.list()) {
        assertThat(rated.getString("commission"), is("2.00"));
      }
    }

    // Below management, and a shopper: refused outright.
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Answer refused = rateAs(a, role, a.store(), a.person());
      assertThat(role, refused.status(), is(403));
      assertThat(refused.text(), not(containsString(scheme)));
    }

    // Another business: its management held to none finds none of our arrangements; held to its
    // own store it is refused; its staff and shoppers are refused outright.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs = rateAs(rival, role, null, a.person(), b.person(), head);
      assertThat(theirs.text(), theirs.status(), is(200));
      assertThat(theirs.text(), not(containsString(scheme)));
      for (JsonObject rated : theirs.list()) {
        assertThat(
            role + " earns nothing across the boundary", rated.getString("commission"), is("0.00"));
      }
    }
    Answer held = rateAs(rival, "MANAGER", rival.store(), a.person());
    assertThat(held.text(), held.status(), is(403));
    assertThat(held.code(), is("STORE_ACCESS_DENIED"));
    assertThat(held.text(), not(containsString(scheme)));
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, rateAs(rival, role, null, a.person()).status(), is(403));
    }

    // Asking moved nothing: the arrangements stand as they were.
    for (String person : List.of(a.person(), b.person(), head)) {
      assertThat(
          call("GET", C + "/staff/" + person, null, a.tenant(), a.manager(), "OWNER").list(),
          hasSize(1));
    }
    assertThat(
        call("GET", C + "/schemes", null, a.tenant(), a.manager(), "OWNER").list(), hasSize(1));
  }

  @Test
  @DisplayName("Rating sales: a period that is not one is 400 before a seller elsewhere is 403")
  void aPeriodIsJudgedBeforeTheSellers() {
    Shop a = shop();
    Branch b = secondStore(a);
    Shop rival = shop();
    String backwards =
        "{\"from\":\"2026-09-30\",\"to\":\"2026-09-01\",\"sellers\":[{\"userId\":\""
            + b.person()
            + "\",\"days\":[{\"day\":\"2026-09-10\",\"net\":100.00}]}]}";
    // Our branch manager of A, and another business's manager naming our store among its own.
    for (String[] who :
        new String[][] {
          {a.tenant(), a.manager(), a.store()}, {rival.tenant(), rival.manager(), a.store()}
        }) {
      Answer refused = call("POST", C + "/rate", backwards, who[0], who[1], "MANAGER", who[2]);
      assertThat(refused.text(), refused.status(), is(400));
      assertThat(refused.code(), is("COMMISSION_PERIOD_INVALID"));
      assertThat("nothing about the seller leaks", refused.text(), not(containsString(b.person())));
    }
    // With a period that is one, the seller elsewhere is the refusal.
    Answer held = rateAs(a, "MANAGER", a.store(), b.person());
    assertThat(held.text(), held.status(), is(403));
    assertThat(held.code(), is("STORE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName(
      "Commission and its bands are in the business's own currency's minor units: whole yen, a"
          + " dinar's third decimal, two places of a pound")
  void commissionIsInTheBusinesssOwnMinorUnits() {
    // Yen: none.
    Shop tokyo = shop("JP", "JPY", "Tokyo", "10001", "Asia/Tokyo");
    Answer half =
        scheme(
            tokyo,
            "{\"name\":\"Half a yen\",\"basis\":\"PERCENT_OF_NET\","
                + "\"bands\":[{\"thresholdFrom\":0,\"rate\":2},{\"thresholdFrom\":1000.5,\"rate\":3}]}");
    assertThat(half.text(), half.status(), is(400));
    assertThat(half.code(), is("COMMISSION_SCHEME_INVALID"));
    Answer yen =
        scheme(
            tokyo,
            "{\"name\":\"Counter\",\"basis\":\"PERCENT_OF_NET\","
                + "\"bands\":[{\"thresholdFrom\":0,\"rate\":2.5},{\"thresholdFrom\":100000,\"rate\":3}]}");
    assertThat(yen.text(), yen.status(), is(201));
    var bands = yen.data().getJsonArray("bands");
    assertThat(bands.getJsonObject(0).getString("thresholdFrom"), is("0"));
    assertThat(bands.getJsonObject(1).getString("thresholdFrom"), is("100000"));
    assertThat(assign(tokyo, yen.data().getString("id"), "2026-09-01").status(), is(201));
    Answer rated =
        rate(tokyo, "2026-09-01", "2026-09-30", "[{\"day\":\"2026-09-10\",\"net\":12345}]");
    assertThat(rated.text(), rated.status(), is(200));
    JsonObject person = rated.list().get(0);
    // 2.5% of 12 345 yen is 308.625: paid as whole yen.
    assertThat(rated.text(), person.getString("commission"), is("309"));
    assertThat(person.getJsonArray("segments").getJsonObject(0).getString("amount"), is("12345"));

    // A Kuwaiti dinar: three.
    Shop kuwait = shop("KW", "KWD", "Kuwait City", "10001", "Asia/Kuwait");
    Answer dinar =
        scheme(
            kuwait,
            "{\"name\":\"Counter\",\"basis\":\"PERCENT_OF_NET\","
                + "\"bands\":[{\"thresholdFrom\":0,\"rate\":2.5},{\"thresholdFrom\":250.125,\"rate\":3}]}");
    assertThat(dinar.text(), dinar.status(), is(201));
    assertThat(
        "a dinar's third decimal is kept, not rounded away by the column",
        dinar.data().getJsonArray("bands").getJsonObject(1).getString("thresholdFrom"),
        is("250.125"));
    assertThat(assign(kuwait, dinar.data().getString("id"), "2026-09-01").status(), is(201));
    Answer fils =
        rate(kuwait, "2026-09-01", "2026-09-30", "[{\"day\":\"2026-09-10\",\"net\":200.500}]");
    assertThat(fils.text(), fils.status(), is(200));
    // 2.5% of 200.500 is 5.0125: 5.013 dinars, never 5.01.
    assertThat(fils.text(), fils.list().get(0).getString("commission"), is("5.013"));

    // A pound: two, as before.
    Shop london = shop();
    Answer pounds =
        scheme(
            london,
            "{\"name\":\"Tiered\",\"basis\":\"PERCENT_OF_NET\","
                + "\"bands\":[{\"thresholdFrom\":0,\"rate\":1},{\"thresholdFrom\":1000,\"rate\":5}]}");
    assertThat(pounds.text(), pounds.status(), is(201));
    assertThat(
        pounds.data().getJsonArray("bands").getJsonObject(1).getString("thresholdFrom"),
        is("1000.00"));
    // A per-unit amount is in a currency ISO 4217 knows.
    Answer made =
        scheme(
            london,
            "{\"name\":\"Made up\",\"basis\":\"PER_UNIT\",\"currency\":\"XYZ\","
                + "\"bands\":[{\"thresholdFrom\":0,\"rate\":1}]}");
    assertThat(made.text(), made.status(), is(400));
    assertThat(made.code(), is("COMMISSION_SCHEME_INVALID"));
  }
}
