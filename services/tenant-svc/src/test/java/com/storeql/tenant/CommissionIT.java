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
    String tenant = TenantOnboarding.onboard(target, "commission", "GB", "GBP");
    String manager = Ids.newId().toString();
    Answer store =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"High Street\",\"code\":\"HS-"
                + Ids.newId().toString().substring(0, 8)
                + "\",\"line1\":\"1 High Street\",\"city\":\"London\",\"country\":\"GB\","
                + "\"pincode\":\"E1 6AN\",\"timezone\":\"Europe/London\"}",
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
}
