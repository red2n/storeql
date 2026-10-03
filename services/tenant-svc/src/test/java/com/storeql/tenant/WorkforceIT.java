package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The roster and the clock, over HTTP and a real database.
 *
 * <p>Three things here are the database's and cannot be had from a unit test, and they are the
 * reason this class exists. <b>One open entry per person</b> is a partial unique index, which is
 * what makes two taps on a slow terminal one entry rather than two afternoons' pay. <b>A correction
 * supersedes under a deferred foreign key</b>, and only one statement order satisfies the index.
 * And <b>the attendance report joins a plan to hours</b> across two tables, where an absence is the
 * absence of a row — the case a stubbed repository cannot show.
 */
@HelidonTest
class WorkforceIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String W = "/admin/workforce";
  private static final String CLOCK = "/workforce/clock";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

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
    return call(method, path, json, tenant, user, roles, stores, null);
  }

  /** As {@link #call}, with an Idempotency-Key header when {@code key} is not null. */
  private Answer call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String stores,
      String key) {
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
    if (key != null) b = b.header("Idempotency-Key", key);
    Entity<String> body = Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON);
    Response r = "GET".equals(method) ? b.get() : b.post(body);
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

  /** A business with one store, and one person who works there. */
  private record Shop(String tenant, String store, String person, String manager) {}

  private Shop shop() {
    return shop("GB", "GBP", "London", "Europe/London", "E1 6AN");
  }

  /** A business with one store in a country and zone of the test's choosing. */
  private Shop shop(String country, String currency, String city, String zone, String pincode) {
    String tenant = TenantOnboarding.onboard(target, "workforce", country, currency);
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
                + "\","
                + "\"pincode\":\""
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

  private Answer plan(Shop shop, String from, String to) {
    return call(
        "POST",
        W + "/shifts",
        "{\"storeId\":\""
            + shop.store()
            + "\",\"userId\":\""
            + shop.person()
            + "\",\"startsAt\":\""
            + from
            + "\",\"endsAt\":\""
            + to
            + "\"}",
        shop.tenant(),
        shop.manager(),
        "OWNER");
  }

  /**
   * Moves an entry's times back, so a window that has passed has hours in it.
   *
   * <p>The fixture, not the subject: nothing clocks in yesterday, and what is under test is the
   * arithmetic of a day that is over.
   */
  private static void backdate(String tenant, String entryId, int days) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE time_entries SET clocked_in_at = clocked_in_at - make_interval(days => ?),"
                  + " clocked_out_at = clocked_out_at - make_interval(days => ?)"
                  + " WHERE tenant_id = ?::uuid AND id = ?::uuid")) {
        ps.setInt(1, days);
        ps.setInt(2, days);
        ps.setString(3, tenant);
        ps.setString(4, entryId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE time_entry_breaks SET started_at = started_at - make_interval(days => ?),"
                  + " ended_at = ended_at - make_interval(days => ?)"
                  + " WHERE tenant_id = ?::uuid AND time_entry_id = ?::uuid")) {
        ps.setInt(1, days);
        ps.setInt(2, days);
        ps.setString(3, tenant);
        ps.setString(4, entryId);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not backdate " + entryId, e);
    }
  }

  private static String iso(int daysFromNow, int hour) {
    return LocalDate.now(ZoneOffset.UTC)
            .plusDays(daysFromNow)
            .atStartOfDay(ZoneOffset.UTC)
            .plusHours(hour)
            .toInstant()
        + "";
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A person clocks themselves in once, and a second tap is not a second entry")
  void oneOpenEntry() {
    // Two taps on a slow terminal would otherwise be two afternoons' pay. The index decides it, so
    // two requests at once cannot both win.
    Shop shop = shop();
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(in.text(), in.status(), is(201));
    assertThat(in.data().getString("source"), is("CLOCK"));
    assertThat("an open entry has no hours yet", in.data().get("hoursWorked"), is(nullValue()));

    Answer again =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("WORKFORCE_ALREADY_CLOCKED_IN"));

    Answer open = call("GET", CLOCK + "/open", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(open.data().getString("id"), is(in.data().getString("id")));
  }

  @Test
  @DisplayName("An unpaid break comes off the hours, and going home closes one left running")
  void breaksAndGoingHome() {
    Shop shop = shop();
    call(
        "POST",
        CLOCK + "/in",
        "{\"storeId\":\"" + shop.store() + "\"}",
        shop.tenant(),
        shop.person(),
        "CASHIER");
    Answer started =
        call(
            "POST",
            CLOCK + "/breaks/start",
            "{\"kind\":\"MEAL\",\"paid\":false}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(started.text(), started.status(), is(200));
    assertThat(started.data().getJsonArray("breaks").size(), is(1));

    Answer second =
        call(
            "POST",
            CLOCK + "/breaks/start",
            "{\"kind\":\"REST\",\"paid\":true}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat("one break at a time", second.code(), is("WORKFORCE_BREAK_OPEN"));

    // Going home closes the break somebody forgot to end, rather than refusing to let them go.
    Answer out = call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(out.text(), out.status(), is(200));
    assertThat(out.data().get("clockedOutAt"), not(nullValue()));
    assertThat(
        "the break was closed with the entry",
        out.data().getJsonArray("breaks").getJsonObject(0).get("endedAt"),
        not(nullValue()));
    assertThat(
        "hours are known once the entry is closed",
        out.data().getString("hoursWorked"),
        not(nullValue()));

    Answer noneOpen = call("GET", CLOCK + "/open", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(noneOpen.body().get("data"), is(nullValue()));
    Answer outAgain = call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(outAgain.code(), is("WORKFORCE_NOT_CLOCKED_IN"));
  }

  @Test
  @DisplayName("A correction supersedes the entry it replaces, and both stay")
  void correctionsSupersede() {
    Shop shop = shop();
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    String entryId = in.data().getString("id");
    call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");

    Answer noReason =
        adjust(
            entryId,
            "{\"clockedOutAt\":\"" + iso(0, 17) + "\",\"reason\":\"\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(
        "a correction without a reason is an edit with extra steps", noReason.status(), is(400));

    Answer fixed =
        adjust(
            entryId,
            "{\"clockedInAt\":\""
                + iso(0, 9)
                + "\",\"clockedOutAt\":\""
                + iso(0, 17)
                + "\",\"reason\":\"terminal was down at the end of the shift\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(fixed.text(), fixed.status(), is(200));
    assertThat(fixed.data().getString("supersedes"), is(entryId));
    assertThat(fixed.data().getString("source"), is("MANAGER"));
    assertThat(fixed.data().getString("hoursWorked"), is("8.0"));

    // Correcting the same entry twice is refused: the correction is the one that stands. The window
    // is named whole: the window is judged before whether the entry stands, and a clock-out alone
    // would be read against the real clock-in — the moment this test ran — so from 18:00 UTC it
    // would be refused as a window, not as a second correction.
    Answer twice =
        adjust(
            entryId,
            "{\"clockedInAt\":\""
                + iso(0, 9)
                + "\",\"clockedOutAt\":\""
                + iso(0, 18)
                + "\",\"reason\":\"again\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(twice.code(), is("WORKFORCE_ENTRY_NOT_STANDING"));

    // The hours read for the window count the correction once, not both entries.
    Answer entries =
        call(
            "GET",
            W + "/time-entries?from=" + iso(0, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(entries.list().size(), is(1));
    assertThat(entries.list().get(0).getString("id"), is(fixed.data().getString("id")));
  }

  @Test
  @DisplayName("A rota is planned, published, and says what is worth saying about it")
  void theRoster() {
    // The concerns are the law's, read through the store's country: an EU store (workforce-rules
    // slice 1). A store outside every pack gets none, below.
    Shop shop = shop("DE", "EUR", "Berlin", "Europe/Berlin", "10115");
    Answer late = plan(shop, iso(1, 14), iso(1, 22));
    assertThat(late.text(), late.status(), is(201));
    assertThat(late.data().getString("status"), is("PLANNED"));
    assertThat(late.data().getString("hours"), is("8.0"));
    Answer early = plan(shop, iso(2, 6), iso(2, 12));
    assertThat(early.status(), is(201));

    Answer roster =
        call(
            "GET",
            W + "/shifts?from=" + iso(0, 0) + "&to=" + iso(5, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(roster.text(), roster.status(), is(200));
    assertThat(roster.data().getJsonArray("shifts").size(), is(2));
    String concerns = roster.data().getJsonArray("concerns").toString();
    assertThat(
        "eight hours between two shifts is said, not refused",
        concerns,
        containsString("DAILY_REST_SHORT"));
    assertThat("and a long shift asks for a break", concerns, containsString("BREAK_EXPECTED"));
    assertThat("saying who says so", concerns, containsString("Directive 2003/88/EC art. 3"));
    assertThat(concerns, containsString("\"severity\":\"ADVISORY\""));
    assertThat(concerns, containsString("\"source\":\"LAW\""));

    String shiftId = late.data().getString("id");
    Answer published =
        call(
            "POST",
            W + "/shifts/" + shiftId + "/publish",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER",
            null,
            Ids.newId().toString());
    assertThat(published.data().getString("status"), is("PUBLISHED"));
    // A second attempt — a new key, so not a retry of the first — finds nothing planned.
    Answer twice =
        call(
            "POST",
            W + "/shifts/" + shiftId + "/publish",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER",
            null,
            Ids.newId().toString());
    assertThat(twice.code(), is("WORKFORCE_SHIFT_NOT_PLANNED"));

    Answer noReason =
        call(
            "POST",
            W + "/shifts/" + shiftId + "/cancel",
            "{\"reason\":\"\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(noReason.status(), is(400));
    Answer cancelled =
        call(
            "POST",
            W + "/shifts/" + shiftId + "/cancel",
            "{\"reason\":\"store closed for a delivery\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(cancelled.data().getString("status"), is("CANCELLED"));
    assertThat(cancelled.data().getString("cancelledReason"), containsString("delivery"));
  }

  @Test
  @DisplayName(
      "A store whose country has no rule gets no legal flag; overlapping shifts flag everywhere")
  void noPackNoLegalFlag() {
    // Britain left the EU regime on 31 January 2020, and no other pack is carried: the eight hours
    // of rest and the long shift that an EU store is told about are not said here.
    Shop shop = shop();
    assertThat(plan(shop, iso(1, 14), iso(1, 22)).status(), is(201));
    assertThat(plan(shop, iso(2, 6), iso(2, 12)).status(), is(201));
    assertThat(plan(shop, iso(2, 10), iso(2, 13)).status(), is(201));
    Answer roster =
        call(
            "GET",
            W + "/shifts?from=" + iso(0, 0) + "&to=" + iso(5, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(roster.text(), roster.status(), is(200));
    String concerns = roster.data().getJsonArray("concerns").toString();
    assertThat(concerns, not(containsString("DAILY_REST_SHORT")));
    assertThat(concerns, not(containsString("BREAK_EXPECTED")));
    assertThat("overlap holds anywhere", concerns, containsString("SHIFTS_OVERLAP"));
    assertThat(concerns, containsString("\"source\":\"ROSTER\""));
  }

  @Test
  @DisplayName(
      "Every working-time rule carries a citation and an effective date, and only the EU pack ships")
  void workingTimeRulesAreCited() throws Exception {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT scope, code, citation, effective_from FROM tenant.working_time_rules"
                    + " ORDER BY code")) {
      var rs = ps.executeQuery();
      int rows = 0;
      while (rs.next()) {
        rows++;
        assertThat(rs.getString("scope"), is("EU"));
        assertThat(rs.getString("citation"), containsString("Directive 2003/88/EC"));
        assertThat(rs.getDate("effective_from"), not(nullValue()));
      }
      assertThat("the two rules the code always applied", rows, is(2));
    }
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO tenant.working_time_rules (scope_kind, scope, code, applies_to,"
                    + " effective_from, rule_value, unit, severity, citation, summary)"
                    + " VALUES ('COUNTRY', 'XX', 'MIN_DAILY_REST', 'ALL', DATE '2020-01-01', 11,"
                    + " 'HOURS', 'ADVISORY', '  ', 'no citation')")) {
      try {
        ps.executeUpdate();
        throw new AssertionError("a rule with no citation must be refused by the database");
      } catch (SQLException expected) {
        assertThat(expected.getMessage(), containsString("chk_wtr_citation"));
      }
    }
  }

  @Test
  @DisplayName(
      "Nobody corrects or hand-clocks their own hours; adjust and a manual clock-in judge the entry's own store; another business is 404")
  void selfAndStore() {
    Shop shop = shop();
    Shop rival = shop();
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    String entryId = in.data().getString("id");
    call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");
    // A whole window, so the correction that is allowed below is valid at any hour the test runs: a
    // clock-out alone is read against the real clock-in, and from 17:00 UTC would end before it.
    String fix =
        "{\"clockedInAt\":\""
            + iso(0, 9)
            + "\",\"clockedOutAt\":\""
            + iso(0, 17)
            + "\",\"reason\":\"terminal was down\"}";

    // Their own hours: refused for a manager and an owner alike, and nothing is superseded.
    for (String role : new String[] {"MANAGER", "OWNER"}) {
      Answer own = adjust(entryId, fix, shop.tenant(), shop.person(), role);
      assertThat(own.text(), own.status(), is(403));
      assertThat(own.code(), is("WORKFORCE_SELF_ADJUST_REFUSED"));
    }
    Answer handClock =
        call(
            "POST",
            W + "/time-entries?user=" + shop.person(),
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "MANAGER");
    assertThat(handClock.text(), handClock.status(), is(403));
    assertThat(handClock.code(), is("WORKFORCE_SELF_ADJUST_REFUSED"));

    // A manager held to another store of the business cannot correct this store's entry.
    String other = Ids.newId().toString();
    Answer elsewhere = adjust(entryId, fix, shop.tenant(), shop.manager(), "MANAGER", other);
    assertThat(elsewhere.text(), elsewhere.status(), is(403));
    assertThat(elsewhere.code(), is("STORE_ACCESS_DENIED"));
    Answer clockElsewhere =
        call(
            "POST",
            W + "/time-entries?user=" + Ids.newId(),
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.manager(),
            "MANAGER",
            other);
    assertThat(clockElsewhere.status(), is(403));
    assertThat(clockElsewhere.code(), is("STORE_ACCESS_DENIED"));

    // Another business, naming our entry or our store, finds nothing and writes nothing.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs = adjust(entryId, fix, rival.tenant(), rival.manager(), role);
      assertThat(theirs.status(), is(404));
      Answer theirClock =
          call(
              "POST",
              W + "/time-entries?user=" + rival.person(),
              "{\"storeId\":\"" + shop.store() + "\"}",
              rival.tenant(),
              rival.manager(),
              role);
      assertThat(theirClock.status(), is(404));
    }
    // A cashier and a storekeeper cannot correct hours at all.
    assertThat(adjust(entryId, fix, shop.tenant(), shop.manager(), "CASHIER").status(), is(403));

    // A manager who is not the person, at the entry's store, still corrects it.
    Answer ok = adjust(entryId, fix, shop.tenant(), shop.manager(), "MANAGER", shop.store());
    assertThat(ok.text(), ok.status(), is(200));
    assertThat(ok.data().getString("supersedes"), is(entryId));
    Answer entries =
        call(
            "GET",
            W + "/time-entries?from=" + iso(0, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat("only the one correction was written", entries.list().size(), is(1));
  }

  @Test
  @DisplayName("Attendance shows the day nobody turned up, and the day nobody planned")
  void attendance() {
    Shop shop = shop();
    // Rostered yesterday and not worked: the case the report exists for.
    Answer missed = plan(shop, iso(-1, 9), iso(-1, 17));
    assertThat(missed.text(), missed.status(), is(201));

    // And worked today with nothing rostered.
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");

    Answer report =
        call(
            "GET",
            W
                + "/attendance?from="
                + LocalDate.now(ZoneOffset.UTC).minusDays(2)
                + "&to="
                + LocalDate.now(ZoneOffset.UTC).plusDays(1),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(report.text(), report.status(), is(200));
    List<JsonObject> days = report.list();
    JsonObject absent =
        days.stream()
            .filter(
                d ->
                    d.getString("day")
                        .equals(LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()))
            .findFirst()
            .orElseThrow();
    assertThat(absent.getBoolean("absent"), is(true));
    assertThat(absent.getString("plannedHours"), is("8.0"));
    assertThat(absent.getString("workedHours"), is("0.0"));

    JsonObject worked =
        days.stream()
            .filter(d -> d.getString("day").equals(LocalDate.now(ZoneOffset.UTC).toString()))
            .findFirst()
            .orElseThrow();
    assertThat(
        "worked with nothing rostered is a management fact too",
        worked.getBoolean("unplanned"),
        is(true));
    assertThat(worked.getBoolean("absent"), is(false));
    assertThat(in.data().getString("id"), not(nullValue()));
  }

  @Test
  @DisplayName("Somebody who does not work at the store is neither rostered nor clocked there")
  void assignmentIsRequired() {
    Shop shop = shop();
    String stranger = Ids.newId().toString();
    Answer rostered =
        call(
            "POST",
            W + "/shifts",
            "{\"storeId\":\""
                + shop.store()
                + "\",\"userId\":\""
                + stranger
                + "\",\"startsAt\":\""
                + iso(1, 9)
                + "\",\"endsAt\":\""
                + iso(1, 17)
                + "\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(rostered.code(), is("WORKFORCE_NOT_ASSIGNED"));

    Answer clocked =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            stranger,
            "CASHIER");
    assertThat(clocked.code(), is("WORKFORCE_NOT_ASSIGNED"));
  }

  @Test
  @DisplayName("A shift rostered for somebody else is not one you can clock on to")
  void aShiftIsNotTransferable() {
    Shop shop = shop();
    String other = Ids.newId().toString();
    call(
        "POST",
        "/admin/staff",
        "{\"userId\":\"" + other + "\",\"storeId\":\"" + shop.store() + "\",\"role\":\"CASHIER\"}",
        shop.tenant(),
        shop.manager(),
        "OWNER");
    Answer theirs =
        call(
            "POST",
            W + "/shifts",
            "{\"storeId\":\""
                + shop.store()
                + "\",\"userId\":\""
                + other
                + "\",\"startsAt\":\""
                + iso(0, 9)
                + "\",\"endsAt\":\""
                + iso(0, 17)
                + "\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(theirs.text(), theirs.status(), is(201));
    Answer clocked =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\""
                + shop.store()
                + "\",\"shiftId\":\""
                + theirs.data().getString("id")
                + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(clocked.code(), is("WORKFORCE_SHIFT_NOT_THEIRS"));
  }

  @Test
  @DisplayName(
      "A manager may write somebody's hours, and it is recorded as theirs, not the person's")
  void managerWrittenHours() {
    Shop shop = shop();
    Answer written =
        call(
            "POST",
            W + "/time-entries?user=" + shop.person(),
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(written.text(), written.status(), is(201));
    assertThat(
        "an audit of hours must tell who pressed what",
        written.data().getString("source"),
        is("MANAGER"));
    assertThat(written.data().getString("userId"), is(shop.person()));
  }

  @Test
  @DisplayName("Another business cannot see or touch this one's hours")
  void tenantsAreSeparate() {
    Shop shop = shop();
    Shop other = shop();
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    String entryId = in.data().getString("id");
    Answer theirs =
        adjust(
            entryId,
            "{\"clockedOutAt\":\"" + iso(0, 17) + "\",\"reason\":\"not mine to fix\"}",
            other.tenant(),
            other.manager(),
            "OWNER");
    assertThat(theirs.code(), is("WORKFORCE_ENTRY_NOT_FOUND"));
    Answer read =
        call(
            "GET",
            W + "/time-entries?from=" + iso(0, 0),
            null,
            other.tenant(),
            other.manager(),
            "OWNER");
    assertThat(read.text(), not(containsString(entryId)));
  }

  @Test
  @DisplayName("A cashier keeps their own clock and reads nobody's roster but their own")
  void whoMayWhat() {
    Shop shop = shop();
    Answer roster =
        call("GET", W + "/shifts?from=" + iso(0, 0), null, shop.tenant(), shop.person(), "CASHIER");
    assertThat("the management roster is management's", roster.status(), is(403));

    Answer mine = call("GET", CLOCK + "/shifts", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(mine.text(), mine.status(), is(200));

    Answer anonymous =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            null,
            null);
    assertThat("no roles, no clock", anonymous.status(), is(403));
  }

  @Test
  @DisplayName("Hours of a window that has passed are read from the day they were worked")
  void hoursOfAPastWindow() {
    Shop shop = shop();
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    call(
        "POST",
        CLOCK + "/breaks/start",
        "{\"kind\":\"MEAL\",\"paid\":false}",
        shop.tenant(),
        shop.person(),
        "CASHIER");
    call("POST", CLOCK + "/breaks/end", null, shop.tenant(), shop.person(), "CASHIER");
    call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");
    String entryId = in.data().getString("id");
    backdate(shop.tenant(), entryId, 3);

    Answer entries =
        call(
            "GET",
            W + "/time-entries?from=" + iso(-4, 0) + "&to=" + iso(-2, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(entries.text(), entries.list().size(), is(1));
    assertThat(entries.list().get(0).getString("id"), is(entryId));

    Answer today =
        call(
            "GET",
            W + "/time-entries?from=" + iso(0, 0) + "&to=" + iso(1, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat("and not in a window it does not belong to", today.list().size(), is(0));
  }

  // ── refusals: who may read, what is a date, what is an id ─────────────────

  /** A second store in the shop's business, and somebody who works there. */
  private Shop secondStore(Shop shop) {
    Answer store =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Low Street\",\"code\":\"LS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Low Street\",\"city\":\"London\",\"country\":\"GB\","
                + "\"pincode\":\"E1 6AN\",\"timezone\":\"Europe/London\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(store.text(), store.status(), is(201));
    String storeId = store.data().getString("id");
    String person = Ids.newId().toString();
    Answer assigned =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + person + "\",\"storeId\":\"" + storeId + "\",\"role\":\"CASHIER\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(assigned.text(), assigned.status(), is(201));
    return new Shop(shop.tenant(), storeId, person, shop.manager());
  }

  private Answer attendanceOf(Shop shop, String roles, String heldTo, String store) {
    String path =
        W
            + "/attendance?from="
            + LocalDate.now(ZoneOffset.UTC).minusDays(2)
            + "&to="
            + LocalDate.now(ZoneOffset.UTC).plusDays(1)
            + (store == null ? "" : "&store=" + store);
    return call("GET", path, null, shop.tenant(), shop.manager(), roles, heldTo);
  }

  private static Set<String> storesIn(Answer a) {
    Set<String> out = new HashSet<>();
    for (JsonObject d : a.list()) out.add(d.getString("storeId"));
    return out;
  }

  /** The status of a shift, read back through the roster of the whole business. */
  private String shiftStatus(Shop shop, String shiftId) {
    Answer roster =
        call(
            "GET",
            W + "/shifts?from=" + iso(-3, 0) + "&to=" + iso(5, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(roster.text(), roster.status(), is(200));
    for (JsonObject sh : roster.data().getJsonArray("shifts").getValuesAs(JsonObject.class)) {
      if (shiftId.equals(sh.getString("id"))) return sh.getString("status");
    }
    throw new AssertionError("shift " + shiftId + " is not on the roster");
  }

  @Test
  @DisplayName("Attendance is read only at the stores the caller keeps, and by management only")
  void attendanceIsKeptToTheCallersStores() {
    Shop a = shop();
    Shop b = secondStore(a);
    assertThat(plan(a, iso(-1, 9), iso(-1, 17)).status(), is(201));
    assertThat(plan(b, iso(-1, 9), iso(-1, 17)).status(), is(201));

    Answer whole = attendanceOf(a, "OWNER", null, null);
    assertThat(whole.text(), whole.status(), is(200));
    assertThat(
        "a caller held to no store reads the whole business",
        storesIn(whole),
        is(Set.of(a.store(), b.store())));

    Answer heldNone = attendanceOf(a, "MANAGER", a.store(), null);
    assertThat(heldNone.text(), heldNone.status(), is(200));
    assertThat(
        "naming none is exactly the caller's stores", storesIn(heldNone), is(Set.of(a.store())));

    Answer ownStore = attendanceOf(a, "MANAGER", a.store(), a.store());
    assertThat(ownStore.status(), is(200));
    assertThat(storesIn(ownStore), is(Set.of(a.store())));

    Answer elsewhere = attendanceOf(a, "MANAGER", a.store(), b.store());
    assertThat(elsewhere.text(), elsewhere.status(), is(403));
    assertThat(elsewhere.code(), is("STORE_ACCESS_DENIED"));
    assertThat(
        "nothing of the other store leaks", elsewhere.text(), not(containsString(b.person())));

    Answer both = attendanceOf(a, "MANAGER", a.store() + "," + b.store(), null);
    assertThat(
        "a manager of two branches reads both", storesIn(both), is(Set.of(a.store(), b.store())));

    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Answer staff = attendanceOf(a, role, null, null);
      assertThat(role, staff.status(), is(403));
    }

    // Another business naming our store reads nothing of ours.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs = attendanceOf(rival, role, null, a.store());
      assertThat(theirs.text(), theirs.status(), is(200));
      assertThat(role + " finds none of our days", theirs.list().size(), is(0));
    }
  }

  /** The management roster of the last few days and the next, as a caller held to some stores. */
  private Answer rosterOf(Shop shop, String roles, String heldTo, String store) {
    return call(
        "GET",
        W
            + "/shifts?from="
            + iso(-3, 0)
            + "&to="
            + iso(3, 0)
            + (store == null ? "" : "&store=" + store),
        null,
        shop.tenant(),
        shop.manager(),
        roles,
        heldTo);
  }

  /** The hours of the last few days and the next, as a caller held to some stores. */
  private Answer entriesOf(Shop shop, String roles, String heldTo, String store) {
    return call(
        "GET",
        W
            + "/time-entries?from="
            + iso(-3, 0)
            + "&to="
            + iso(3, 0)
            + (store == null ? "" : "&store=" + store),
        null,
        shop.tenant(),
        shop.manager(),
        roles,
        heldTo);
  }

  private static Set<String> shiftStoresIn(Answer roster) {
    Set<String> out = new HashSet<>();
    for (JsonObject s : roster.data().getJsonArray("shifts").getValuesAs(JsonObject.class)) {
      out.add(s.getString("storeId"));
    }
    return out;
  }

  /** The shop's person on the clock at its store, and off it again. */
  private void clockedOnAndOff(Shop shop) {
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(in.text(), in.status(), is(201));
    Answer out = call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(out.text(), out.status(), is(200));
  }

  @Test
  @DisplayName("The roster and the hours are read only at the stores the caller keeps")
  void rosterAndHoursAreKeptToTheCallersStores() {
    Shop a = shop();
    Shop b = secondStore(a);
    assertThat(plan(a, iso(1, 9), iso(1, 17)).status(), is(201));
    assertThat(plan(b, iso(1, 9), iso(1, 17)).status(), is(201));
    clockedOnAndOff(a);
    clockedOnAndOff(b);
    Set<String> both = Set.of(a.store(), b.store());

    // Held to no store (the owner, and a business-wide manager): the whole business.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer roster = rosterOf(a, role, null, null);
      assertThat(roster.text(), roster.status(), is(200));
      assertThat(role + ": every store's roster", shiftStoresIn(roster), is(both));
      Answer hours = entriesOf(a, role, null, null);
      assertThat(hours.text(), hours.status(), is(200));
      assertThat(role + ": every store's hours", storesIn(hours), is(both));
    }

    // Held to A and naming none: exactly A, and nothing of B's person anywhere in the answer.
    Answer heldRoster = rosterOf(a, "MANAGER", a.store(), null);
    assertThat(heldRoster.text(), heldRoster.status(), is(200));
    assertThat(shiftStoresIn(heldRoster), is(Set.of(a.store())));
    assertThat(heldRoster.text(), not(containsString(b.person())));
    Answer heldHours = entriesOf(a, "MANAGER", a.store(), null);
    assertThat(heldHours.text(), heldHours.status(), is(200));
    assertThat(storesIn(heldHours), is(Set.of(a.store())));
    assertThat(heldHours.text(), not(containsString(b.person())));

    // Held to A and naming A: A.
    assertThat(shiftStoresIn(rosterOf(a, "MANAGER", a.store(), a.store())), is(Set.of(a.store())));
    assertThat(storesIn(entriesOf(a, "MANAGER", a.store(), a.store())), is(Set.of(a.store())));

    // Held to A and naming B: refused, and nothing of B leaks.
    for (Answer elsewhere :
        List.of(
            rosterOf(a, "MANAGER", a.store(), b.store()),
            entriesOf(a, "MANAGER", a.store(), b.store()))) {
      assertThat(elsewhere.text(), elsewhere.status(), is(403));
      assertThat(elsewhere.code(), is("STORE_ACCESS_DENIED"));
      assertThat(elsewhere.text(), not(containsString(b.person())));
    }

    // A manager of both branches reads both together.
    String twoBranches = a.store() + "," + b.store();
    assertThat(shiftStoresIn(rosterOf(a, "MANAGER", twoBranches, null)), is(both));
    assertThat(storesIn(entriesOf(a, "MANAGER", twoBranches, null)), is(both));

    // Below management, and a shopper, read neither.
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, rosterOf(a, role, null, null).status(), is(403));
      assertThat(role, entriesOf(a, role, null, null).status(), is(403));
    }

    // Another business: its management naming our stores, or none, finds nothing of ours; its
    // manager held to its own store is refused ours; its staff and shoppers are refused outright.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String named : new String[] {a.store(), b.store(), null}) {
        Answer roster = rosterOf(rival, role, null, named);
        assertThat(roster.text(), roster.status(), is(200));
        assertThat(roster.text(), not(containsString(a.person())));
        assertThat(roster.text(), not(containsString(b.person())));
        Answer hours = entriesOf(rival, role, null, named);
        assertThat(hours.text(), hours.status(), is(200));
        assertThat(hours.text(), not(containsString(a.person())));
        assertThat(hours.text(), not(containsString(b.person())));
      }
    }
    for (Answer held :
        List.of(
            rosterOf(rival, "MANAGER", rival.store(), a.store()),
            entriesOf(rival, "MANAGER", rival.store(), a.store()))) {
      assertThat(held.text(), held.status(), is(403));
      assertThat(held.code(), is("STORE_ACCESS_DENIED"));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, rosterOf(rival, role, null, a.store()).status(), is(403));
      assertThat(role, entriesOf(rival, role, null, a.store()).status(), is(403));
    }

    // Reading moved nothing: one shift and one entry at each store, as before.
    Answer after = rosterOf(a, "OWNER", null, null);
    assertThat(after.data().getJsonArray("shifts").size(), is(2));
    assertThat(entriesOf(a, "OWNER", null, null).list().size(), is(2));
  }

  @Test
  @DisplayName("A day or an instant that is not one is refused, and nothing is written")
  void aDayThatIsNotADateIsRefused() {
    Shop shop = shop();
    Answer roster =
        call("GET", W + "/shifts?from=next-tuesday", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(roster.status(), is(400));
    assertThat(roster.code(), is("WORKFORCE_DATE_INVALID"));

    Answer report =
        call(
            "GET", W + "/attendance?from=14/09/2026", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(report.status(), is(400));
    assertThat(report.code(), is("WORKFORCE_DATE_INVALID"));

    Answer rate =
        call(
            "POST",
            W + "/pay-rates",
            "{\"userId\":\""
                + shop.person()
                + "\",\"hourlyRate\":\"12.50\",\"effectiveFrom\":\"14/09/2026\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(rate.status(), is(400));
    assertThat(rate.code(), is("WORKFORCE_DATE_INVALID"));
    Answer rates =
        call(
            "GET",
            W + "/pay-rates?user=" + shop.person(),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat("no rate was recorded", rates.list().size(), is(0));
  }

  @Test
  @DisplayName("An id that is not an id, or is missing, is refused before anything is read")
  void anIdThatIsNotAnIdIsRefused() {
    Shop shop = shop();
    Answer clock =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"not-a-store\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(clock.status(), is(400));
    assertThat(clock.code(), is("WORKFORCE_ID_INVALID"));
    Answer none = call("GET", CLOCK + "/open", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat("nobody was clocked in", none.body().get("data"), is(nullValue()));

    Answer roster =
        call("GET", W + "/shifts?store=nope", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(roster.status(), is(400));
    assertThat(roster.code(), is("WORKFORCE_ID_INVALID"));

    // An id of another version is no id of ours either.
    Answer oldId =
        call(
            "GET",
            W + "/shifts?user=00000000-0000-4000-8000-000000000000",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(oldId.status(), is(400));
  }

  @Test
  @DisplayName("Whose hours must be named: a missing person is refused and nobody is clocked in")
  void whoseHoursMustBeNamed() {
    Shop shop = shop();
    Answer rates = call("GET", W + "/pay-rates", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(rates.status(), is(400));
    assertThat(rates.code(), is("WORKFORCE_ID_REQUIRED"));

    Answer entry =
        call(
            "POST",
            W + "/time-entries",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(entry.status(), is(400));
    assertThat(entry.code(), is("WORKFORCE_ID_REQUIRED"));
    Answer hours =
        call(
            "GET",
            W + "/time-entries?from=" + iso(-1, 0) + "&to=" + iso(1, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat("no entry was written", hours.list().size(), is(0));
  }

  @Test
  @DisplayName("A break is REST or MEAL, and ending one that is not running is refused")
  void aBreakIsRestOrMeal() {
    Shop shop = shop();
    // Not on the clock: neither a break starts nor ends.
    Answer notIn =
        call("POST", CLOCK + "/breaks/end", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(notIn.status(), is(409));
    assertThat(notIn.code(), is("WORKFORCE_NOT_CLOCKED_IN"));
    Answer startNotIn =
        call(
            "POST",
            CLOCK + "/breaks/start",
            "{\"kind\":\"MEAL\",\"paid\":false}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(startNotIn.code(), is("WORKFORCE_NOT_CLOCKED_IN"));

    assertThat(
        call(
                "POST",
                CLOCK + "/in",
                "{\"storeId\":\"" + shop.store() + "\"}",
                shop.tenant(),
                shop.person(),
                "CASHIER")
            .status(),
        is(201));

    // On the clock with no break running.
    Answer noBreak =
        call("POST", CLOCK + "/breaks/end", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat(noBreak.status(), is(409));
    assertThat(noBreak.code(), is("WORKFORCE_NO_BREAK"));

    Answer nap =
        call(
            "POST",
            CLOCK + "/breaks/start",
            "{\"kind\":\"NAP\",\"paid\":false}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(nap.status(), is(400));
    assertThat(nap.code(), is("WORKFORCE_BREAK_KIND_UNKNOWN"));
    Answer open = call("GET", CLOCK + "/open", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat("no break was recorded", open.data().getJsonArray("breaks").size(), is(0));
  }

  @Test
  @DisplayName(
      "A break body that breaks its constraints is refused at the boundary, and no break is kept")
  void aBreakBodyBreakingItsConstraintsIsRefused() {
    Shop shop = shop();
    assertThat(
        call(
                "POST",
                CLOCK + "/in",
                "{\"storeId\":\"" + shop.store() + "\"}",
                shop.tenant(),
                shop.person(),
                "CASHIER")
            .status(),
        is(201));

    for (String kind : List.of("MEAL_BREAK_", "x".repeat(200))) {
      Answer refused =
          call(
              "POST",
              CLOCK + "/breaks/start",
              "{\"kind\":\"" + kind + "\",\"paid\":true}",
              shop.tenant(),
              shop.person(),
              "CASHIER");
      assertThat(kind.length() + " characters -> " + refused.text(), refused.status(), is(400));
      assertThat(kind.length() + " characters", refused.code(), is("VALIDATION_FAILED"));
    }
    Answer open = call("GET", CLOCK + "/open", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat("no break was kept", open.data().getJsonArray("breaks").size(), is(0));

    // The edge of the limit is the service's to judge, as before: ten characters pass the
    // boundary and are still not a kind of break.
    Answer ten =
        call(
            "POST",
            CLOCK + "/breaks/start",
            "{\"kind\":\"NAPPING123\",\"paid\":false}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(ten.text(), ten.status(), is(400));
    assertThat(ten.code(), is("WORKFORCE_BREAK_KIND_UNKNOWN"));

    // The same login under another business has no clock there, so it starts no break on this
    // one's.
    String other = TenantOnboarding.onboard(target, "workforce-other", "GB", "GBP");
    Answer stranger =
        call(
            "POST",
            CLOCK + "/breaks/start",
            "{\"kind\":\"MEAL\",\"paid\":false}",
            other,
            shop.person(),
            "CASHIER");
    assertThat(stranger.text(), stranger.status(), is(409));
    assertThat(stranger.code(), is("WORKFORCE_NOT_CLOCKED_IN"));
    // A shopper's login is no member of staff, whoever's clock it names.
    Answer shopper =
        call(
            "POST",
            CLOCK + "/breaks/start",
            "{\"kind\":\"MEAL\",\"paid\":false}",
            shop.tenant(),
            shop.person(),
            "CUSTOMER");
    assertThat(shopper.text(), shopper.status(), is(403));
    Answer after = call("GET", CLOCK + "/open", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat("still no break", after.data().getJsonArray("breaks").size(), is(0));
  }

  @Test
  @DisplayName("A window that is not one is refused, for a shift and for corrected hours")
  void aWindowThatIsNotOneIsRefused() {
    Shop shop = shop();
    Answer backwards = plan(shop, iso(2, 17), iso(2, 9));
    assertThat(backwards.status(), is(400));
    assertThat(backwards.code(), is("WORKFORCE_WINDOW_INVALID"));
    Answer tooLong = plan(shop, iso(2, 9), iso(4, 9));
    assertThat(tooLong.status(), is(400));
    assertThat(tooLong.code(), is("WORKFORCE_WINDOW_INVALID"));
    Answer roster =
        call(
            "GET",
            W + "/shifts?from=" + iso(0, 0) + "&to=" + iso(6, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat("neither shift was rostered", roster.data().getJsonArray("shifts").size(), is(0));

    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    String entryId = in.data().getString("id");
    call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");
    Answer fixBackwards =
        adjust(
            entryId,
            "{\"clockedInAt\":\""
                + iso(-1, 17)
                + "\",\"clockedOutAt\":\""
                + iso(-1, 9)
                + "\",\"reason\":\"fix\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(fixBackwards.status(), is(400));
    assertThat(fixBackwards.code(), is("WORKFORCE_WINDOW_INVALID"));
    Answer fixTooLong =
        adjust(
            entryId,
            "{\"clockedInAt\":\""
                + iso(-3, 9)
                + "\",\"clockedOutAt\":\""
                + iso(-1, 9)
                + "\",\"reason\":\"fix\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(fixTooLong.status(), is(400));
    assertThat(fixTooLong.code(), is("WORKFORCE_WINDOW_INVALID"));
    Answer hours =
        call(
            "GET",
            W + "/time-entries?from=" + iso(-1, 0) + "&to=" + iso(1, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat("the entry was not corrected", hours.list().size(), is(1));
    assertThat(hours.list().get(0).getString("id"), is(entryId));
  }

  @Test
  @DisplayName("A called-off shift stays called off, and nobody clocks on to it")
  void aCalledOffShiftStaysCalledOff() {
    Shop shop = shop();
    Answer shift = plan(shop, iso(1, 9), iso(1, 17));
    String id = shift.data().getString("id");
    assertThat(
        call(
                "POST",
                W + "/shifts/" + id + "/cancel",
                "{\"reason\":\"closed\"}",
                shop.tenant(),
                shop.manager(),
                "OWNER")
            .status(),
        is(200));

    Answer again =
        call(
            "POST",
            W + "/shifts/" + id + "/cancel",
            "{\"reason\":\"again\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("WORKFORCE_SHIFT_CANCELLED"));

    Answer clock =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\",\"shiftId\":\"" + id + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(clock.status(), is(409));
    assertThat(clock.code(), is("WORKFORCE_SHIFT_CANCELLED"));
    Answer open = call("GET", CLOCK + "/open", null, shop.tenant(), shop.person(), "CASHIER");
    assertThat("nobody was clocked in", open.body().get("data"), is(nullValue()));
    assertThat(shiftStatus(shop, id), is("CANCELLED"));
  }

  @Test
  @DisplayName("Another business cannot publish, cancel or clock on to our shift")
  void anotherBusinessCannotPublishOrCancelOurShift() {
    Shop shop = shop();
    Shop rival = shop();
    String id = plan(shop, iso(1, 9), iso(1, 17)).data().getString("id");

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer publish =
          call(
              "POST",
              W + "/shifts/" + id + "/publish",
              null,
              rival.tenant(),
              rival.manager(),
              role,
              null,
              Ids.newId().toString());
      assertThat(role, publish.status(), is(404));
      assertThat(publish.code(), is("WORKFORCE_SHIFT_NOT_FOUND"));
      Answer cancel =
          call(
              "POST",
              W + "/shifts/" + id + "/cancel",
              "{\"reason\":\"x\"}",
              rival.tenant(),
              rival.manager(),
              role);
      assertThat(role, cancel.status(), is(404));
      assertThat(cancel.code(), is("WORKFORCE_SHIFT_NOT_FOUND"));
    }
    // The rival's own staff, naming our shift on their own store, or a shift nobody rostered.
    Answer theirClock =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + rival.store() + "\",\"shiftId\":\"" + id + "\"}",
            rival.tenant(),
            rival.person(),
            "CASHIER");
    assertThat(theirClock.status(), is(404));
    assertThat(theirClock.code(), is("WORKFORCE_SHIFT_NOT_FOUND"));
    Answer unknown =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\",\"shiftId\":\"" + Ids.newId() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(unknown.status(), is(404));
    assertThat(unknown.code(), is("WORKFORCE_SHIFT_NOT_FOUND"));
    assertThat("our shift is as it was", shiftStatus(shop, id), is("PLANNED"));
    Answer open = call("GET", CLOCK + "/open", null, rival.tenant(), rival.person(), "CASHIER");
    assertThat("nobody was clocked in", open.body().get("data"), is(nullValue()));
  }

  @Test
  @DisplayName("Ten cancels at once call a shift off once")
  void tenCancelsAtOnceCallAShiftOffOnce() throws Exception {
    Shop shop = shop();
    String id = plan(shop, iso(1, 9), iso(1, 17)).data().getString("id");
    int n = 10;
    var pool = Executors.newFixedThreadPool(n);
    var go = new CountDownLatch(1);
    List<Future<Answer>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return call(
                    "POST",
                    W + "/shifts/" + id + "/cancel",
                    "{\"reason\":\"x\"}",
                    shop.tenant(),
                    shop.manager(),
                    "OWNER");
              }));
    }
    go.countDown();
    int done = 0;
    for (Future<Answer> f : results) {
      Answer a = f.get();
      if (a.status() == 200) {
        done++;
      } else {
        assertThat(a.text(), a.status(), is(409));
        assertThat(
            a.text(),
            a.code(),
            org.hamcrest.Matchers.either(is("WORKFORCE_SHIFT_CHANGED"))
                .or(is("WORKFORCE_SHIFT_CANCELLED")));
      }
    }
    pool.shutdown();
    assertThat("one cancel wins", done, is(1));
    assertThat(shiftStatus(shop, id), is("CANCELLED"));
  }

  @Test
  @DisplayName("A roster, hours or attendance window longer than the limit is refused")
  void aWindowLongerThanTheLimitIsRefused() {
    Shop shop = shop();
    for (String path :
        List.of(
            "/shifts?from=2026-01-01&to=2026-12-31",
            "/time-entries?from=2026-01-01&to=2026-12-31",
            "/attendance?from=2026-01-01&to=2026-12-31")) {
      Answer a = call("GET", W + path, null, shop.tenant(), shop.manager(), "OWNER");
      assertThat(path, a.status(), is(400));
      assertThat(path, a.code(), is("WORKFORCE_WINDOW_INVALID"));
    }
    Answer fine =
        call(
            "GET",
            W + "/shifts?from=2026-01-01&to=2026-02-15",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(fine.text(), fine.status(), is(200));
  }

  @Test
  @DisplayName("A roster, hours or attendance window that ends before it begins is refused")
  void aWindowRunningBackwardsIsRefused() {
    Shop shop = shop();
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(in.text(), in.status(), is(201));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);

    // Run the right way round, each read finds what is there: today's clock-on.
    Answer forward =
        call(
            "GET",
            W + "/time-entries?from=" + iso(-3, 0) + "&to=" + iso(3, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(forward.text(), forward.status(), is(200));
    assertThat(forward.list().size(), is(1));

    for (String path :
        List.of(
            "/shifts?from=" + iso(3, 0) + "&to=" + iso(-3, 0),
            "/time-entries?from=" + iso(3, 0) + "&to=" + iso(-3, 0),
            "/attendance?from=" + today.plusDays(3) + "&to=" + today.minusDays(3),
            // A start left out is a week ago, which is after an end that is already further back.
            "/time-entries?to=" + iso(-30, 0),
            "/attendance?to=" + today.minusDays(30))) {
      Answer a = call("GET", W + path, null, shop.tenant(), shop.manager(), "OWNER");
      assertThat(path + " -> " + a.text(), a.status(), is(400));
      assertThat(path, a.code(), is("WORKFORCE_WINDOW_INVALID"));
      assertThat(
          path + ": no rows, so nothing reads as 'nobody worked'",
          a.body().containsKey("data"),
          is(false));
    }

    // The caller's own roster is held to the same window, backwards or longer than the limit.
    for (String path :
        List.of(
            "/shifts?from=" + iso(3, 0) + "&to=" + iso(-3, 0),
            "/shifts?to=" + iso(-30, 0),
            "/shifts?from=2026-01-01&to=2026-12-31")) {
      Answer own = call("GET", CLOCK + path, null, shop.tenant(), shop.person(), "CASHIER");
      assertThat(path + " -> " + own.text(), own.status(), is(400));
      assertThat(path, own.code(), is("WORKFORCE_WINDOW_INVALID"));
    }
    Answer ownFine =
        call(
            "GET",
            CLOCK + "/shifts?from=" + iso(-3, 0) + "&to=" + iso(3, 0),
            null,
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(ownFine.text(), ownFine.status(), is(200));

    // One day is a window: the same date at both ends is not backwards.
    Answer oneDay =
        call(
            "GET",
            W + "/attendance?from=" + today + "&to=" + today,
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(oneDay.text(), oneDay.status(), is(200));
    Answer sameInstant =
        call(
            "GET",
            W + "/time-entries?from=" + iso(0, 6) + "&to=" + iso(0, 6),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(sameInstant.text(), sameInstant.status(), is(200));
  }

  /** Publishes or calls off a shift as a caller of some roles, held to some stores. */
  private Answer shiftAct(
      String tenant, String user, String shiftId, String act, String roles, String heldTo) {
    // Publishing is a retryable write: every attempt here is a new one, under a fresh key.
    return call(
        "POST",
        W + "/shifts/" + shiftId + "/" + act,
        "cancel".equals(act) ? "{\"reason\":\"the delivery moved\"}" : null,
        tenant,
        user,
        roles,
        heldTo,
        "publish".equals(act) ? Ids.newId().toString() : null);
  }

  @Test
  @DisplayName("A shift is published or called off only at a store the caller is held to")
  void aShiftIsChangedOnlyAtTheCallersStores() {
    Shop a = shop();
    Shop b = secondStore(a);
    String atA = plan(a, iso(1, 9), iso(1, 17)).data().getString("id");
    String alsoAtA = plan(a, iso(2, 9), iso(2, 17)).data().getString("id");
    String atB = plan(b, iso(1, 9), iso(1, 17)).data().getString("id");
    String alsoAtB = plan(b, iso(2, 9), iso(2, 17)).data().getString("id");
    String m = a.manager();

    // Held to A, acting on B's shifts: refused before anything moves, and nothing of B leaks.
    for (String act : new String[] {"publish", "cancel"}) {
      Answer refused = shiftAct(a.tenant(), m, atB, act, "MANAGER", a.store());
      assertThat(act + " -> " + refused.text(), refused.status(), is(403));
      assertThat(act, refused.code(), is("STORE_ACCESS_DENIED"));
      assertThat(refused.text(), not(containsString(b.person())));
    }
    assertThat("B's shift is as it was", shiftStatus(a, atB), is("PLANNED"));

    // Held to A, acting on A's: allowed.
    Answer published = shiftAct(a.tenant(), m, atA, "publish", "MANAGER", a.store());
    assertThat(published.text(), published.status(), is(200));
    Answer cancelled = shiftAct(a.tenant(), m, alsoAtA, "cancel", "MANAGER", a.store());
    assertThat(cancelled.text(), cancelled.status(), is(200));
    assertThat(shiftStatus(a, atA), is("PUBLISHED"));
    assertThat(shiftStatus(a, alsoAtA), is("CANCELLED"));

    // Held to no store (a business-wide manager, the owner): any store's shift.
    assertThat(shiftAct(a.tenant(), m, atB, "publish", "MANAGER", null).status(), is(200));
    assertThat(shiftAct(a.tenant(), m, alsoAtB, "cancel", "OWNER", null).status(), is(200));
    assertThat(shiftStatus(a, atB), is("PUBLISHED"));
    assertThat(shiftStatus(a, alsoAtB), is("CANCELLED"));

    // A manager of both branches: either.
    String laterAtB = plan(b, iso(3, 9), iso(3, 17)).data().getString("id");
    Answer both =
        shiftAct(a.tenant(), m, laterAtB, "publish", "MANAGER", a.store() + "," + b.store());
    assertThat(both.text(), both.status(), is(200));

    // Below management, of our business or another, and a shopper: refused outright.
    String untouched = plan(a, iso(4, 9), iso(4, 17)).data().getString("id");
    Shop rival = shop();
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      for (String act : new String[] {"publish", "cancel"}) {
        assertThat(
            role + " " + act,
            shiftAct(a.tenant(), a.person(), untouched, act, role, a.store()).status(),
            is(403));
        assertThat(
            "another business's " + role + " " + act,
            shiftAct(rival.tenant(), rival.person(), untouched, act, role, rival.store()).status(),
            is(403));
      }
    }
    // Another business's management, held to its own store or to none: no such shift.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {null, rival.store()}) {
        for (String act : new String[] {"publish", "cancel"}) {
          Answer theirs = shiftAct(rival.tenant(), rival.manager(), untouched, act, role, held);
          assertThat(role + " " + act + " -> " + theirs.text(), theirs.status(), is(404));
          assertThat(theirs.code(), is("WORKFORCE_SHIFT_NOT_FOUND"));
        }
      }
    }
    assertThat("nobody else moved our shift", shiftStatus(a, untouched), is("PLANNED"));
  }

  /** A person's pay rates, read as a caller of some roles held to some stores. */
  private Answer ratesOf(Shop shop, String person, String roles, String heldTo) {
    return call(
        "GET", W + "/pay-rates?user=" + person, null, shop.tenant(), shop.manager(), roles, heldTo);
  }

  @Test
  @DisplayName("A pay rate is read only for somebody at a store the caller is held to")
  void payRatesAreReadOnlyForPeopleAtTheCallersStores() {
    Shop a = shop();
    Shop b = secondStore(a);
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
    // The currency is named: left out, the business's own is read from tenant-svc over HTTP,
    // which this rig does not serve (503 TENANT_PROFILE_UNAVAILABLE).
    for (String person : List.of(a.person(), b.person(), head)) {
      Answer added =
          call(
              "POST",
              W + "/pay-rates",
              "{\"userId\":\""
                  + person
                  + "\",\"hourlyRate\":\"17.25\",\"currency\":\"GBP\","
                  + "\"effectiveFrom\":\"2026-01-01\"}",
              a.tenant(),
              a.manager(),
              "OWNER");
      assertThat(added.text(), added.status(), is(201));
    }

    // Held to A: A's person; not B's, not the business-wide manager above them, not a stranger.
    Answer own = ratesOf(a, a.person(), "MANAGER", a.store());
    assertThat(own.text(), own.status(), is(200));
    assertThat(own.list().size(), is(1));
    for (String other : List.of(b.person(), head, Ids.newId().toString())) {
      Answer refused = ratesOf(a, other, "MANAGER", a.store());
      assertThat(refused.text(), refused.status(), is(403));
      assertThat(refused.code(), is("STORE_ACCESS_DENIED"));
      assertThat("no rate leaks", refused.text(), not(containsString("17.25")));
    }

    // A manager of both branches: both branches' people, still not head office.
    String twoBranches = a.store() + "," + b.store();
    assertThat(ratesOf(a, b.person(), "MANAGER", twoBranches).list().size(), is(1));
    assertThat(ratesOf(a, head, "MANAGER", twoBranches).status(), is(403));

    // Held to no store: anybody in the business.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String person : List.of(a.person(), b.person(), head)) {
        Answer whole = ratesOf(a, person, role, null);
        assertThat(role + " -> " + whole.text(), whole.status(), is(200));
        assertThat(whole.list().size(), is(1));
      }
    }

    // Below management, and a shopper: refused.
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, ratesOf(a, a.person(), role, a.store()).status(), is(403));
    }

    // Another business: its management held to none reads nothing of ours; held to its own store
    // it is refused; its staff and shoppers are refused outright.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String person : List.of(a.person(), b.person(), head)) {
        Answer theirs = ratesOf(rival, person, role, null);
        assertThat(theirs.text(), theirs.status(), is(200));
        assertThat(role + " finds none of our rates", theirs.list().size(), is(0));
      }
    }
    Answer held = ratesOf(rival, a.person(), "MANAGER", rival.store());
    assertThat(held.text(), held.status(), is(403));
    assertThat(held.code(), is("STORE_ACCESS_DENIED"));
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, ratesOf(rival, a.person(), role, null).status(), is(403));
    }

    // Reading moved nothing.
    assertThat(ratesOf(a, a.person(), "OWNER", null).list().size(), is(1));
  }

  // ── store before caller; a shift's own store; publishing under a key ──────

  /** How many shifts the business has rostered, read through the roster of the whole business. */
  private int shiftsOf(Shop shop) {
    Answer roster =
        call(
            "GET",
            W + "/shifts?from=" + iso(-3, 0) + "&to=" + iso(5, 0),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(roster.text(), roster.status(), is(200));
    return roster.data().getJsonArray("shifts").size();
  }

  private String shiftBody(String store, String person) {
    return "{\"storeId\":\""
        + store
        + "\",\"userId\":\""
        + person
        + "\",\"startsAt\":\""
        + iso(1, 9)
        + "\",\"endsAt\":\""
        + iso(1, 17)
        + "\"}";
  }

  private boolean onTheClock(String tenant, String person) {
    Answer open = call("GET", CLOCK + "/open", null, tenant, person, "CASHIER");
    assertThat(open.text(), open.status(), is(200));
    return open.body().get("data") != null
        && open.body().get("data").getValueType() != jakarta.json.JsonValue.ValueType.NULL;
  }

  @Test
  @DisplayName(
      "Rostering and clocking in: a store not the business's is 404 before one not the caller's is"
          + " 403, and nothing is written")
  void theStoreIsTheBusinessesBeforeItIsTheCallers() {
    Shop a = shop();
    Shop b = secondStore(a);
    Shop rival = shop();
    String unknown = Ids.newId().toString();

    // Another business's management, its headers naming OUR store, or held to none: not found.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {a.store(), null}) {
        Answer planned =
            call(
                "POST",
                W + "/shifts",
                shiftBody(a.store(), a.person()),
                rival.tenant(),
                rival.manager(),
                role,
                held);
        assertThat(role + " -> " + planned.text(), planned.status(), is(404));
        assertThat(planned.code(), is("STORE_NOT_FOUND"));
        Answer byHand =
            call(
                "POST",
                W + "/time-entries?user=" + a.person(),
                "{\"storeId\":\"" + a.store() + "\"}",
                rival.tenant(),
                rival.manager(),
                role,
                held);
        assertThat(role + " -> " + byHand.text(), byHand.status(), is(404));
        assertThat(byHand.code(), is("STORE_NOT_FOUND"));
      }
    }
    // Another business's staff, naming our store among their own: not found, nobody clocked in.
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Answer in =
          call(
              "POST",
              CLOCK + "/in",
              "{\"storeId\":\"" + a.store() + "\"}",
              rival.tenant(),
              rival.person(),
              role,
              a.store());
      assertThat(role + " -> " + in.text(), in.status(), is(404));
      assertThat(in.code(), is("STORE_NOT_FOUND"));
    }
    // A shopper is no staff at all.
    Answer shopper =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + a.store() + "\"}",
            rival.tenant(),
            Ids.newId().toString(),
            "CUSTOMER",
            a.store());
    assertThat(shopper.status(), is(403));
    assertThat(onTheClock(rival.tenant(), rival.person()), is(false));

    // Our branch manager of A: B is the business's but not theirs (403); a store that is nobody's
    // is not found (404), even listed among their own ids.
    Answer sister =
        call(
            "POST",
            W + "/shifts",
            shiftBody(b.store(), b.person()),
            a.tenant(),
            a.manager(),
            "MANAGER",
            a.store());
    assertThat(sister.text(), sister.status(), is(403));
    assertThat(sister.code(), is("STORE_ACCESS_DENIED"));
    assertThat("nothing of B leaks", sister.text(), not(containsString(b.person())));
    Answer nowhere =
        call(
            "POST",
            W + "/shifts",
            shiftBody(unknown, a.person()),
            a.tenant(),
            a.manager(),
            "MANAGER",
            a.store() + "," + unknown);
    assertThat(nowhere.text(), nowhere.status(), is(404));
    assertThat(nowhere.code(), is("STORE_NOT_FOUND"));
    // Our cashier at A, clocking in at B.
    Answer elsewhere =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + b.store() + "\"}",
            a.tenant(),
            a.person(),
            "CASHIER",
            a.store());
    assertThat(elsewhere.text(), elsewhere.status(), is(403));
    assertThat(elsewhere.code(), is("STORE_ACCESS_DENIED"));
    // A window that is not one is a bad request whoever sends it, before any store is judged.
    Answer backwards =
        call(
            "POST",
            W + "/shifts",
            "{\"storeId\":\""
                + a.store()
                + "\",\"userId\":\""
                + a.person()
                + "\",\"startsAt\":\""
                + iso(1, 17)
                + "\",\"endsAt\":\""
                + iso(1, 9)
                + "\"}",
            rival.tenant(),
            rival.manager(),
            "MANAGER",
            a.store());
    assertThat(backwards.text(), backwards.status(), is(400));
    assertThat(backwards.code(), is("WORKFORCE_WINDOW_INVALID"));

    assertThat("nothing was rostered", shiftsOf(a), is(0));
    assertThat("nobody was clocked in", onTheClock(a.tenant(), a.person()), is(false));
    assertThat(onTheClock(a.tenant(), b.person()), is(false));

    // Where it is theirs, it is done.
    Answer own =
        call(
            "POST",
            W + "/shifts",
            shiftBody(a.store(), a.person()),
            a.tenant(),
            a.manager(),
            "MANAGER",
            a.store());
    assertThat(own.text(), own.status(), is(201));
  }

  @Test
  @DisplayName("Hours tied to a shift are at its store: a clock-in naming one elsewhere is refused")
  void aClockInNamesAShiftAtItsOwnStore() {
    Shop a = shop();
    Shop b = secondStore(a);
    // The person works at both stores, and is rostered at B.
    Answer both =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\""
                + a.person()
                + "\",\"storeId\":\""
                + b.store()
                + "\",\"role\":\"CASHIER\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(both.text(), both.status(), is(201));
    Answer atB =
        call(
            "POST",
            W + "/shifts",
            "{\"storeId\":\""
                + b.store()
                + "\",\"userId\":\""
                + a.person()
                + "\",\"startsAt\":\""
                + iso(0, 9)
                + "\",\"endsAt\":\""
                + iso(0, 17)
                + "\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(atB.text(), atB.status(), is(201));
    String shift = atB.data().getString("id");
    String atA = "{\"storeId\":\"" + a.store() + "\",\"shiftId\":\"" + shift + "\"}";

    Answer self = call("POST", CLOCK + "/in", atA, a.tenant(), a.person(), "CASHIER");
    assertThat(self.text(), self.status(), is(400));
    assertThat(self.code(), is("WORKFORCE_SHIFT_AT_ANOTHER_STORE"));
    Answer byHand =
        call(
            "POST",
            W + "/time-entries?user=" + a.person(),
            "{\"storeId\":\"" + a.store() + "\",\"shiftId\":\"" + shift + "\"}",
            a.tenant(),
            a.manager(),
            "OWNER");
    assertThat(byHand.text(), byHand.status(), is(400));
    assertThat(byHand.code(), is("WORKFORCE_SHIFT_AT_ANOTHER_STORE"));
    assertThat("nobody was clocked in", onTheClock(a.tenant(), a.person()), is(false));

    // At the shift's own store it is clocked on to.
    Answer there =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + b.store() + "\",\"shiftId\":\"" + shift + "\"}",
            a.tenant(),
            a.person(),
            "CASHIER");
    assertThat(there.text(), there.status(), is(201));
    assertThat(there.data().getString("shiftId"), is(shift));
    assertThat(there.data().getString("storeId"), is(b.store()));
  }

  /**
   * Corrects an entry as a caller. A retryable write: every attempt here is a new one, under a
   * fresh key; {@link #adjustUnder} sends the key a test chooses.
   */
  private Answer adjust(String entryId, String json, String tenant, String user, String roles) {
    return adjust(entryId, json, tenant, user, roles, null);
  }

  private Answer adjust(
      String entryId, String json, String tenant, String user, String roles, String heldTo) {
    return call(
        "POST",
        W + "/time-entries/" + entryId + "/adjust",
        json,
        tenant,
        user,
        roles,
        heldTo,
        Ids.newId().toString());
  }

  /** Corrects an entry as the shop's owner, under the key given (none when null). */
  private Answer adjustUnder(Shop shop, String entryId, String json, String key) {
    return call(
        "POST",
        W + "/time-entries/" + entryId + "/adjust",
        json,
        shop.tenant(),
        shop.manager(),
        "OWNER",
        null,
        key);
  }

  /** A closed entry of the shop's person today, clocked by them; its id. */
  private String clockedToday(Shop shop) {
    Answer in =
        call(
            "POST",
            CLOCK + "/in",
            "{\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.person(),
            "CASHIER");
    assertThat(in.text(), in.status(), is(201));
    call("POST", CLOCK + "/out", null, shop.tenant(), shop.person(), "CASHIER");
    return in.data().getString("id");
  }

  private static int countOf(String sql, String tenant) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setString(1, tenant);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int adjustmentsOf(String tenant) {
    return countOf("SELECT count(*) FROM time_entry_adjustments WHERE tenant_id = ?::uuid", tenant);
  }

  /** Corrections written: entries that supersede another. */
  private static int correctionsOf(String tenant) {
    return countOf(
        "SELECT count(*) FROM time_entries WHERE tenant_id = ?::uuid AND supersedes IS NOT NULL",
        tenant);
  }

  /** LabourRecorded announcements of a correction (they carry the entry they replace). */
  private static int correctionLabourOf(String tenant) {
    return countOf(
        "SELECT count(*) FROM outbox WHERE tenant_id = ?::uuid AND event_type = 'LabourRecorded'"
            + " AND payload::jsonb ->> 'supersedes' IS NOT NULL",
        tenant);
  }

  @Test
  @DisplayName(
      "Correcting hours needs an Idempotency-Key; a retry answers the first correction, and the key"
          + " used for another correction is refused")
  void correctingHoursIsARetryableWrite() throws Exception {
    Shop shop = shop();
    String entry = clockedToday(shop);
    String fix =
        "{\"clockedInAt\":\""
            + iso(0, 9)
            + "\",\"clockedOutAt\":\""
            + iso(0, 17)
            + "\",\"reason\":\"terminal was down\"}";

    Answer none = adjustUnder(shop, entry, fix, null);
    assertThat(none.text(), none.status(), is(400));
    assertThat(none.code(), is("IDEMPOTENCY_KEY_REQUIRED"));
    Answer bad = adjustUnder(shop, entry, fix, "adjust-" + entry);
    assertThat(bad.text(), bad.status(), is(400));
    assertThat(bad.code(), is("IDEMPOTENCY_KEY_INVALID"));
    assertThat("a refused correction writes nothing", correctionsOf(shop.tenant()), is(0));

    String key = Ids.newId().toString();
    Answer fixed = adjustUnder(shop, entry, fix, key);
    assertThat(fixed.text(), fixed.status(), is(200));
    String correction = fixed.data().getString("id");
    assertThat(fixed.data().getString("supersedes"), is(entry));
    // The retry — the answer to the first was lost — is answered the same, not 409.
    Answer retried = adjustUnder(shop, entry, fix, key.toUpperCase(java.util.Locale.ROOT));
    assertThat(retried.text(), retried.status(), is(200));
    assertThat(retried.data().getString("id"), is(correction));
    assertThat(retried.data().getString("hoursWorked"), is(fixed.data().getString("hoursWorked")));
    // The same key with other hours, or for another entry: another request, refused, unmoved.
    Answer otherHours = adjustUnder(shop, entry, fix.replace(iso(0, 17), iso(0, 18)), key);
    assertThat(otherHours.text(), otherHours.status(), is(409));
    assertThat(otherHours.code(), is("IDEMPOTENCY_KEY_REUSED"));
    String another = clockedToday(shop);
    Answer otherEntry = adjustUnder(shop, another, fix, key);
    assertThat(otherEntry.text(), otherEntry.status(), is(409));
    assertThat(otherEntry.code(), is("IDEMPOTENCY_KEY_REUSED"));
    // A new key on the entry already corrected is a second correction: refused as before.
    Answer twice = adjustUnder(shop, entry, fix, Ids.newId().toString());
    assertThat(twice.code(), is("WORKFORCE_ENTRY_NOT_STANDING"));
    assertThat("one correction, under one key", correctionsOf(shop.tenant()), is(1));
    assertThat(adjustmentsOf(shop.tenant()), is(1));
    assertThat("its cost announced once", correctionLabourOf(shop.tenant()), is(1));

    // Another business, held to our store or to none, with its own key or ours: no such entry.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {shop.store(), null}) {
        for (String k : new String[] {key, Ids.newId().toString()}) {
          Answer theirs =
              call(
                  "POST",
                  W + "/time-entries/" + another + "/adjust",
                  fix,
                  rival.tenant(),
                  rival.manager(),
                  role,
                  held,
                  k);
          assertThat(role + " -> " + theirs.text(), theirs.status(), is(404));
          assertThat(theirs.code(), is("WORKFORCE_ENTRY_NOT_FOUND"));
        }
      }
    }
    assertThat(correctionsOf(shop.tenant()), is(1));
    assertThat(adjustmentsOf(rival.tenant()), is(0));

    // Ten retries of one attempt at once: one correction, and every one answered with it.
    String once = Ids.newId().toString();
    int n = 10;
    var pool = Executors.newFixedThreadPool(n);
    var go = new CountDownLatch(1);
    List<Future<Answer>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return adjustUnder(shop, another, fix, once);
              }));
    }
    go.countDown();
    Set<String> answered = new HashSet<>();
    for (Future<Answer> f : results) {
      Answer a = f.get();
      assertThat(a.text(), a.status(), is(200));
      answered.add(a.data().getString("id"));
    }
    pool.shutdown();
    assertThat("every retry answered with the one correction", answered.size(), is(1));
    assertThat(correctionsOf(shop.tenant()), is(2));
    assertThat(adjustmentsOf(shop.tenant()), is(2));
    assertThat(correctionLabourOf(shop.tenant()), is(2));
  }

  private Answer publishUnder(Shop shop, String shiftId, String key) {
    return call(
        "POST",
        W + "/shifts/" + shiftId + "/publish",
        null,
        shop.tenant(),
        shop.manager(),
        "OWNER",
        null,
        key);
  }

  private static int publicationsOf(String tenant) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement("SELECT count(*) FROM shift_publications WHERE tenant_id = ?::uuid")) {
        ps.setString(1, tenant);
        try (java.sql.ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName(
      "Publishing a shift needs an Idempotency-Key; a retry answers the first outcome, and a key"
          + " reused for another shift is refused")
  void publishingIsARetryableWrite() throws Exception {
    Shop shop = shop();
    String first = plan(shop, iso(1, 9), iso(1, 17)).data().getString("id");
    String second = plan(shop, iso(2, 9), iso(2, 17)).data().getString("id");

    Answer none = publishUnder(shop, first, null);
    assertThat(none.text(), none.status(), is(400));
    assertThat(none.code(), is("IDEMPOTENCY_KEY_REQUIRED"));
    Answer bad = publishUnder(shop, first, "shift-" + first);
    assertThat(bad.text(), bad.status(), is(400));
    assertThat(bad.code(), is("IDEMPOTENCY_KEY_INVALID"));
    assertThat("a refused publish moves nothing", shiftStatus(shop, first), is("PLANNED"));

    String key = Ids.newId().toString();
    Answer published = publishUnder(shop, first, key);
    assertThat(published.text(), published.status(), is(200));
    assertThat(published.data().getString("status"), is("PUBLISHED"));
    // The retry — the answer to the first was lost — is answered the same, not 409.
    Answer retried = publishUnder(shop, first, key.toUpperCase(java.util.Locale.ROOT));
    assertThat(retried.text(), retried.status(), is(200));
    assertThat(retried.data().getString("id"), is(first));
    assertThat(retried.data().getString("status"), is("PUBLISHED"));
    // The same key for another shift is another write under a used key: refused, nothing moved.
    Answer reused = publishUnder(shop, second, key);
    assertThat(reused.text(), reused.status(), is(409));
    assertThat(reused.code(), is("IDEMPOTENCY_KEY_REUSED"));
    assertThat(shiftStatus(shop, second), is("PLANNED"));
    assertThat("one publication, under one key", publicationsOf(shop.tenant()), is(1));

    // Another business, held to our store or to none, with its own key or ours: no such shift.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {shop.store(), null}) {
        for (String k : new String[] {key, Ids.newId().toString()}) {
          Answer theirs =
              call(
                  "POST",
                  W + "/shifts/" + second + "/publish",
                  null,
                  rival.tenant(),
                  rival.manager(),
                  role,
                  held,
                  k);
          assertThat(role + " -> " + theirs.text(), theirs.status(), is(404));
          assertThat(theirs.code(), is("WORKFORCE_SHIFT_NOT_FOUND"));
        }
      }
    }
    assertThat(shiftStatus(shop, second), is("PLANNED"));
    assertThat(publicationsOf(rival.tenant()), is(0));

    // Ten retries of one attempt at once: one publication, and every one answered with the shift.
    String third = plan(shop, iso(3, 9), iso(3, 17)).data().getString("id");
    String once = Ids.newId().toString();
    int n = 10;
    var pool = Executors.newFixedThreadPool(n);
    var go = new CountDownLatch(1);
    List<Future<Answer>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return publishUnder(shop, third, once);
              }));
    }
    go.countDown();
    for (Future<Answer> f : results) {
      Answer a = f.get();
      assertThat(a.text(), a.status(), is(200));
      assertThat(a.data().getString("status"), is("PUBLISHED"));
    }
    pool.shutdown();
    assertThat(publicationsOf(shop.tenant()), is(2));
  }
}
