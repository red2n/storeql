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
import java.util.List;
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
        call(
            "POST",
            W + "/time-entries/" + entryId + "/adjust",
            "{\"clockedOutAt\":\"" + iso(0, 17) + "\",\"reason\":\"\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(
        "a correction without a reason is an edit with extra steps", noReason.status(), is(400));

    Answer fixed =
        call(
            "POST",
            W + "/time-entries/" + entryId + "/adjust",
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

    // Correcting the same entry twice is refused: the correction is the one that stands.
    Answer twice =
        call(
            "POST",
            W + "/time-entries/" + entryId + "/adjust",
            "{\"clockedOutAt\":\"" + iso(0, 18) + "\",\"reason\":\"again\"}",
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
            "OWNER");
    assertThat(published.data().getString("status"), is("PUBLISHED"));
    Answer twice =
        call(
            "POST",
            W + "/shifts/" + shiftId + "/publish",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
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
    String fix = "{\"clockedOutAt\":\"" + iso(0, 17) + "\",\"reason\":\"terminal was down\"}";

    // Their own hours: refused for a manager and an owner alike, and nothing is superseded.
    for (String role : new String[] {"MANAGER", "OWNER"}) {
      Answer own =
          call(
              "POST",
              W + "/time-entries/" + entryId + "/adjust",
              fix,
              shop.tenant(),
              shop.person(),
              role);
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
    Answer elsewhere =
        call(
            "POST",
            W + "/time-entries/" + entryId + "/adjust",
            fix,
            shop.tenant(),
            shop.manager(),
            "MANAGER",
            other);
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
      Answer theirs =
          call(
              "POST",
              W + "/time-entries/" + entryId + "/adjust",
              fix,
              rival.tenant(),
              rival.manager(),
              role);
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
    assertThat(
        call(
                "POST",
                W + "/time-entries/" + entryId + "/adjust",
                fix,
                shop.tenant(),
                shop.manager(),
                "CASHIER")
            .status(),
        is(403));

    // A manager who is not the person, at the entry's store, still corrects it.
    Answer ok =
        call(
            "POST",
            W + "/time-entries/" + entryId + "/adjust",
            fix,
            shop.tenant(),
            shop.manager(),
            "MANAGER",
            shop.store());
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
        call(
            "POST",
            W + "/time-entries/" + entryId + "/adjust",
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
}
