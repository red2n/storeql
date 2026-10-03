package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.tenant.service.StoreTaskService;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shop's lists and its day, over HTTP and a real database (store operations & workforce).
 *
 * <p>What only this test can show: that a day is generated <b>once</b> however many ask (the unique
 * constraint, not a service check); that a list falls due on the <b>store's own clock</b>, so the
 * same 08:00 is a different instant in Mumbai; that the sweep marks what fell due and was never
 * done and <b>announces it in the same transaction</b>; and who may write a list, who may work it,
 * and that a stranger to the store may do neither.
 */
@HelidonTest
class StoreTaskIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String ADMIN = "/admin/workforce/tasks";
  private static final String WORK = "/workforce/tasks";

  @Inject WebTarget target;
  @Inject StoreTaskService service;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

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

  /** A business with one store on a given clock, a cashier who works there, and a manager. */
  private record Shop(
      String tenant, String store, String cashier, String manager, String timezone) {}

  private Shop shop(String timezone) {
    String tenant = TenantOnboarding.onboard(target, "tasks", "GB", "GBP");
    String manager = Ids.newId().toString();
    Answer store =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"High Street\",\"code\":\"HS-"
                + Ids.newId().toString().substring(0, 8)
                + "\",\"line1\":\"1 High Street\",\"city\":\"London\",\"country\":\"GB\","
                + "\"pincode\":\"E1 6AN\",\"timezone\":\""
                + timezone
                + "\"}",
            tenant,
            manager,
            "OWNER");
    assertThat(store.text(), store.status(), is(201));
    String storeId = store.data().getString("id");
    String cashier = Ids.newId().toString();
    Answer assigned =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + cashier + "\",\"storeId\":\"" + storeId + "\",\"role\":\"CASHIER\"}",
            tenant,
            manager,
            "OWNER");
    assertThat(assigned.text(), assigned.status(), is(201));
    return new Shop(tenant, storeId, cashier, manager, timezone);
  }

  private Answer writeList(Shop shop, String json) {
    return call("POST", ADMIN + "/lists", json, shop.tenant(), shop.manager(), "OWNER");
  }

  private static String opening(String dueTime, int grace, String... lines) {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) items.append(',');
      // A line starting with '?' is optional.
      boolean optional = lines[i].startsWith("?");
      items
          .append("{\"text\":\"")
          .append(optional ? lines[i].substring(1) : lines[i])
          .append("\",\"required\":")
          .append(!optional)
          .append('}');
    }
    return "{\"title\":\"Open up\",\"kind\":\"OPENING\",\"dueTime\":\""
        + dueTime
        + "\",\"graceMinutes\":"
        + grace
        + ",\"lines\":["
        + items
        + "]}";
  }

  private int generate(Shop shop, LocalDate day) {
    Answer a =
        call(
            "POST",
            ADMIN + "/days",
            "{\"storeId\":\""
                + shop.store()
                + "\""
                + (day == null ? "" : ",\"businessDate\":\"" + day + "\"")
                + "}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(a.text(), a.status(), is(200));
    return a.body().getInt("data");
  }

  private List<JsonObject> today(Shop shop, String user, String role) {
    Answer a = call("GET", WORK + "?storeId=" + shop.store(), null, shop.tenant(), user, role);
    assertThat(a.text(), a.status(), is(200));
    return a.list();
  }

  private Answer work(Shop shop, String path, String json) {
    return call("POST", WORK + "/" + path, json, shop.tenant(), shop.cashier(), "CASHIER");
  }

  /** The manager's summary of a store's today. */
  private JsonObject summaryToday(Shop shop) {
    String day = today(shop.timezone()).toString();
    Answer a =
        call(
            "GET",
            ADMIN + "/summary?storeId=" + shop.store() + "&from=" + day + "&to=" + day,
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(a.text(), a.status(), is(200));
    return a.list().get(0);
  }

  /** A list put on a store's day by hand. */
  private Answer raise(Shop shop, String listId, String storeId) {
    return call(
        "POST",
        ADMIN + "/raise",
        "{\"listId\":\"" + listId + "\",\"storeId\":\"" + storeId + "\"}",
        shop.tenant(),
        shop.manager(),
        "OWNER");
  }

  private static LocalDate today(String timezone) {
    return LocalDate.now(ZoneId.of(timezone));
  }

  private static List<String> outboxEventsFor(String tenant) {
    List<String> out = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT event_type, aggregate_id, payload FROM outbox WHERE tenant_id = ?::uuid ORDER BY created_at")) {
        ps.setString(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next())
            out.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A checklist is written, the day generated once, worked line by line, and finished")
  void checklist() {
    Shop shop = shop("Europe/London");
    Answer written =
        writeList(shop, opening("08:00", 60, "Unlock", "Count the float", "?Water the plant"));
    assertThat(written.text(), written.status(), is(201));
    assertThat(written.data().getBoolean("checklist"), is(true));
    assertThat(written.data().getJsonArray("lines"), hasSize(3));
    assertThat(written.data().getString("status"), is("ACTIVE"));

    // The day appears once however many ask: the unique constraint decides, not the caller.
    assertThat(generate(shop, null), is(1));
    assertThat(generate(shop, null), is(0));
    List<JsonObject> list = today(shop, shop.cashier(), "CASHIER");
    assertThat(list, hasSize(1));
    JsonObject task = list.get(0);
    assertThat(task.getString("status"), is("OPEN"));
    assertThat(task.getJsonArray("items"), hasSize(3));
    assertThat("two required lines to tick", task.getJsonNumber("outstanding").longValue(), is(2L));
    assertThat(task.getString("businessDate"), is(today("Europe/London").toString()));
    String id = task.getString("id");

    // Finished with lines outstanding is refused: a closing list signed off with the safe still
    // open is what the required flag exists for.
    Answer early = work(shop, id + "/complete", "{}");
    assertThat(early.status(), is(409));
    assertThat(early.code(), is("TASK_LINES_OUTSTANDING"));

    assertThat(work(shop, id + "/lines/1/tick", null).status(), is(200));
    Answer second = work(shop, id + "/lines/2/tick", null);
    assertThat(second.status(), is(200));
    assertThat(second.data().getJsonNumber("outstanding").longValue(), is(0L));
    assertThat(
        second.data().getJsonArray("items").getJsonObject(1).getString("tickedBy"),
        is(shop.cashier()));
    Answer twice = work(shop, id + "/lines/2/tick", null);
    assertThat(twice.status(), is(409));
    assertThat(twice.code(), is("TASK_LINE_TICKED"));
    Answer noLine = work(shop, id + "/lines/9/tick", null);
    assertThat(noLine.status(), is(404));
    assertThat(noLine.code(), is("TASK_LINE_NOT_FOUND"));

    Answer done = work(shop, id + "/complete", "{\"note\":\"all quiet\"}");
    assertThat(done.text(), done.status(), is(200));
    assertThat(done.data().getString("status"), is("DONE"));
    assertThat(done.data().getString("completedBy"), is(shop.cashier()));
    assertThat(done.data().getString("note"), is("all quiet"));
    // The optional line never had to be ticked, and the list is done without it.
    assertThat(
        done.data().getJsonArray("items").getJsonObject(2).containsKey("tickedAt"), is(false));
    Answer again = work(shop, id + "/complete", "{}");
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("TASK_NOT_OPEN"));
    assertThat(work(shop, id + "/lines/3/tick", null).code(), is("TASK_NOT_OPEN"));

    JsonObject day = summaryToday(shop);
    assertThat(day.getInt("done"), is(1));
    assertThat(day.getBoolean("settled"), is(true));
  }

  @Test
  @DisplayName("A list nobody did is missed by the sweep, and the manager's alert goes out with it")
  void missed() {
    Shop shop = shop("Europe/London");
    // Due at the end of the day with no grace: yesterday's occurrence is past due, today's is not.
    // The sweep generates today's too, and a list due at 00:00 would have made that one missed as
    // well — correct, and not what this test is about.
    assertThat(writeList(shop, opening("23:59", 0, "Unlock")).status(), is(201));
    LocalDate yesterday = today("Europe/London").minusDays(1);
    assertThat(generate(shop, yesterday), is(1));
    int touched = service.sweep(Instant.now());
    assertThat("at least the one occurrence was marked", touched >= 1, is(true));

    Answer days =
        call(
            "GET",
            ADMIN + "/days?storeId=" + shop.store() + "&from=" + yesterday + "&to=" + yesterday,
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    JsonObject task = days.list().get(0);
    assertThat(task.getString("status"), is("MISSED"));
    assertThat(task.get("completedBy"), is(nullValue()));
    // Marked and announced in one transaction: the alert is the reason the mark exists.
    List<String> events = outboxEventsFor(shop.tenant());
    assertThat(
        events.stream()
            .filter(e -> e.startsWith("StoreTaskMissed " + task.getString("id")))
            .count(),
        is(1L));
    assertThat(
        events.stream().filter(e -> e.startsWith("StoreTaskMissed")).findFirst().orElseThrow(),
        containsString("\"title\":\"Open up\""));
    // Sweeping again marks nothing twice and announces nothing twice.
    service.sweep(Instant.now());
    assertThat(
        outboxEventsFor(shop.tenant()).stream()
            .filter(e -> e.startsWith("StoreTaskMissed " + task.getString("id")))
            .count(),
        is(1L));
    // And today's occurrence, generated by the same sweep, is open: not yet due, so not missed.
    assertThat(today(shop, shop.cashier(), "CASHIER").get(0).getString("status"), is("OPEN"));
    // A missed task cannot be quietly done afterwards.
    assertThat(work(shop, task.getString("id") + "/complete", "{}").code(), is("TASK_NOT_OPEN"));
    Answer summary =
        call(
            "GET",
            ADMIN + "/summary?storeId=" + shop.store() + "&from=" + yesterday + "&to=" + yesterday,
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(summary.list().get(0).getInt("missed"), is(1));
    assertThat(summary.list().get(0).getBoolean("settled"), is(false));
  }

  @Test
  @DisplayName(
      "Done after it fell due is late, not missed; skipped needs a reason and settles the day")
  void lateAndSkipped() {
    Shop shop = shop("Europe/London");
    // Due at midnight with a day's grace: not yet missed, and anything done now is late.
    assertThat(writeList(shop, opening("00:00", 1440, "Unlock")).status(), is(201));
    assertThat(
        writeList(shop, "{\"title\":\"Empty the bins\",\"kind\":\"DAILY\",\"dueTime\":\"23:59\"}")
            .status(),
        is(201));
    assertThat(generate(shop, null), is(2));
    List<JsonObject> list = today(shop, shop.cashier(), "CASHIER");
    JsonObject unlock =
        list.stream().filter(t -> "Open up".equals(t.getString("title"))).findFirst().orElseThrow();
    JsonObject bins =
        list.stream()
            .filter(t -> "Empty the bins".equals(t.getString("title")))
            .findFirst()
            .orElseThrow();
    assertThat("a task without lines is not a checklist", bins.getJsonArray("items"), hasSize(0));

    assertThat(work(shop, unlock.getString("id") + "/lines/1/tick", null).status(), is(200));
    Answer done = work(shop, unlock.getString("id") + "/complete", "{}");
    assertThat(done.data().getString("status"), is("DONE"));
    assertThat(
        "after it fell due, which is late and not missed",
        done.data().getBoolean("late"),
        is(true));

    Answer noReason = work(shop, bins.getString("id") + "/skip", "{\"reason\":\"\"}");
    assertThat(noReason.status(), is(400));
    Answer skipped =
        work(shop, bins.getString("id") + "/skip", "{\"reason\":\"Bin lorry did not come\"}");
    assertThat(skipped.text(), skipped.status(), is(200));
    assertThat(skipped.data().getString("status"), is("SKIPPED"));
    assertThat(skipped.data().getString("skippedReason"), is("Bin lorry did not come"));

    JsonObject day = summaryToday(shop);
    assertThat(day.getInt("late"), is(1));
    assertThat(day.getInt("skipped"), is(1));
    assertThat("explained counts as settled", day.getBoolean("settled"), is(true));
  }

  @Test
  @DisplayName("A list falls due on the store's own clock")
  void storesOwnClock() {
    Shop mumbai = shop("Asia/Kolkata");
    assertThat(writeList(mumbai, opening("08:00", 60, "Unlock")).status(), is(201));
    LocalDate day = today("Asia/Kolkata");
    assertThat(generate(mumbai, day), is(1));
    JsonObject task = today(mumbai, mumbai.cashier(), "CASHIER").get(0);
    // 08:00 in Mumbai is 02:30Z, and today's list is Mumbai's today, which may not be London's.
    assertThat(task.getString("dueAt"), is(day + "T02:30:00Z"));
    assertThat(task.getString("businessDate"), is(day.toString()));
  }

  @Test
  @DisplayName(
      "A weekly list only on its day, a store's own list only at its store, a job raised by hand once")
  void schedule() {
    Shop shop = shop("Europe/London");
    int notToday = today("Europe/London").getDayOfWeek().getValue() % 7 + 1;
    Answer weekly =
        writeList(
            shop,
            "{\"title\":\"Sweep the yard\",\"kind\":\"WEEKLY\",\"daysOfWeek\":["
                + notToday
                + "],\"dueTime\":\"17:00\"}");
    assertThat(weekly.text(), weekly.status(), is(201));
    // Another store's own list: never on this store's day.
    Answer other =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Side Street\",\"code\":\"SS-"
                + Ids.newId().toString().substring(0, 8)
                + "\",\"line1\":\"2 Side Street\",\"city\":\"Leeds\",\"country\":\"GB\",\"pincode\":\"LS1 4AB\",\"timezone\":\"Europe/London\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    String otherStore = other.data().getString("id");
    assertThat(
        writeList(
                shop,
                "{\"title\":\"Wash the van\",\"kind\":\"DAILY\",\"storeId\":\""
                    + otherStore
                    + "\",\"dueTime\":\"12:00\"}")
            .status(),
        is(201));
    Answer adHoc =
        writeList(
            shop,
            "{\"title\":\"Put the delivery away\",\"kind\":\"AD_HOC\",\"dueTime\":\"15:00\"}");
    assertThat(adHoc.status(), is(201));

    assertThat(
        "nothing falls due here today: the weekly list is another day's, the van is another store's, the delivery is raised by hand",
        generate(shop, null),
        is(0));

    Answer raised = raise(shop, adHoc.data().getString("id"), shop.store());
    assertThat(raised.text(), raised.status(), is(201));
    assertThat(raised.data().getString("kind"), is("AD_HOC"));
    Answer twice = raise(shop, adHoc.data().getString("id"), shop.store());
    assertThat(twice.status(), is(409));
    assertThat(twice.code(), is("TASK_ALREADY_RAISED"));
    Answer wrongStore = raise(shop, weekly.data().getString("id"), otherStore);
    assertThat("a business-wide list may be raised at any store", wrongStore.status(), is(201));

    // Withdrawn: no more days for it, and the one already raised stands.
    Answer withdrawn =
        call(
            "DELETE",
            ADMIN + "/lists/" + adHoc.data().getString("id"),
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(withdrawn.status(), is(200));
    assertThat(withdrawn.data().getString("status"), is("WITHDRAWN"));
    assertThat(
        call(
                "DELETE",
                ADMIN + "/lists/" + adHoc.data().getString("id"),
                null,
                shop.tenant(),
                shop.manager(),
                "OWNER")
            .code(),
        is("TASK_LIST_WITHDRAWN"));
    Answer raiseWithdrawn = raise(shop, adHoc.data().getString("id"), otherStore);
    assertThat(raiseWithdrawn.code(), is("TASK_LIST_WITHDRAWN"));
    assertThat(today(shop, shop.cashier(), "CASHIER"), hasSize(1));
    assertThat(
        call("GET", ADMIN + "/lists", null, shop.tenant(), shop.manager(), "OWNER").list(),
        hasSize(2));
    assertThat(
        call("GET", ADMIN + "/lists?all=true", null, shop.tenant(), shop.manager(), "OWNER").list(),
        hasSize(3));
  }

  @Test
  @DisplayName("A list nobody could work is refused, and the reason says what to do")
  void refusals() {
    Shop shop = shop("Europe/London");
    assertThat(
        writeList(shop, "{\"title\":\"X\",\"kind\":\"ROUTINE\",\"dueTime\":\"08:00\"}").code(),
        is("TASK_LIST_INVALID"));
    assertThat(
        writeList(shop, "{\"title\":\"X\",\"kind\":\"DAILY\",\"dueTime\":\"eight\"}").code(),
        is("TASK_TIME_INVALID"));
    assertThat(
        writeList(
                shop,
                "{\"title\":\"X\",\"kind\":\"DAILY\",\"dueTime\":\"08:00\",\"daysOfWeek\":[8]}")
            .code(),
        is("TASK_LIST_INVALID"));
    assertThat(
        writeList(shop, "{\"title\":\"X\",\"kind\":\"WEEKLY\",\"dueTime\":\"08:00\"}").code(),
        is("TASK_LIST_INVALID"));
    // A blank line is refused at the door by validation; the service's own TASK_LINE_BLANK stands
    // behind it for callers that are not HTTP.
    assertThat(writeList(shop, opening("08:00", 60, "Unlock", " ")).status(), is(400));
    assertThat(
        writeList(
                shop,
                "{\"title\":\"X\",\"kind\":\"DAILY\",\"dueTime\":\"08:00\",\"graceMinutes\":5000}")
            .status(),
        is(400));
    assertThat(
        writeList(
                shop,
                "{\"title\":\"X\",\"kind\":\"DAILY\",\"dueTime\":\"08:00\",\"storeId\":\""
                    + Ids.newId()
                    + "\"}")
            .code(),
        is("STORE_NOT_FOUND"));
    Answer range =
        call(
            "GET",
            ADMIN + "/days?storeId=" + shop.store() + "&from=2026-01-01&to=2026-12-31",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(range.code(), is("TASK_RANGE_INVALID"));
    Answer noList =
        call("GET", ADMIN + "/lists/" + Ids.newId(), null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(noList.status(), is(404));
    assertThat(noList.code(), is("TASK_LIST_NOT_FOUND"));
    assertThat(
        call("GET", WORK + "/" + Ids.newId(), null, shop.tenant(), shop.cashier(), "CASHIER")
            .status(),
        is(404));
  }

  @Test
  @DisplayName(
      "Management writes the list, the shop works it, and a stranger to the store does neither")
  void whoMayPressWhat() {
    Shop shop = shop("Europe/London");
    assertThat(writeList(shop, opening("08:00", 60, "Unlock")).status(), is(201));
    assertThat(generate(shop, null), is(1));
    String id = today(shop, shop.cashier(), "CASHIER").get(0).getString("id");

    // A cashier does not write the list, nor read the manager's day.
    assertThat(
        call(
                "POST",
                ADMIN + "/lists",
                opening("09:00", 60, "Sneak"),
                shop.tenant(),
                shop.cashier(),
                "CASHIER")
            .status(),
        is(403));
    assertThat(
        call(
                "GET",
                ADMIN
                    + "/summary?storeId="
                    + shop.store()
                    + "&from="
                    + today("Europe/London")
                    + "&to="
                    + today("Europe/London"),
                null,
                shop.tenant(),
                shop.cashier(),
                "CASHIER")
            .status(),
        is(403));
    // Somebody not on this store's staff does not tick its list.
    String stranger = Ids.newId().toString();
    Answer notHere =
        call("POST", WORK + "/" + id + "/lines/1/tick", null, shop.tenant(), stranger, "CASHIER");
    assertThat(notHere.status(), is(409));
    assertThat(notHere.code(), is("WORKFORCE_NOT_ASSIGNED"));
    // Another business sees none of it.
    Shop rival = shop("Europe/London");
    assertThat(
        call("GET", WORK + "/" + id, null, rival.tenant(), rival.cashier(), "CASHIER").status(),
        is(404));
    assertThat(
        call(
                "POST",
                WORK + "/" + id + "/complete",
                "{}",
                rival.tenant(),
                rival.cashier(),
                "CASHIER")
            .status(),
        is(404));
    assertThat(
        call("GET", ADMIN + "/lists", null, rival.tenant(), rival.manager(), "OWNER").list(),
        hasSize(0));
    // And nobody at all is refused at the door.
    assertThat(
        call("GET", WORK + "?storeId=" + shop.store(), null, shop.tenant(), null, null).status(),
        is(not(200)));
  }

  // ── refusals: ids, dates, and whose list it is ─────────────────────────────

  @Test
  @DisplayName("An id that is not an id is refused before anything is read, and nothing is written")
  void anIdThatIsNotAnIdIsRefused() {
    Shop shop = shop("Europe/London");
    assertThat(writeList(shop, opening("08:00", 60, "Unlock")).status(), is(201));

    Answer noStore = call("GET", WORK, null, shop.tenant(), shop.cashier(), "CASHIER");
    assertThat(noStore.status(), is(400));
    assertThat(noStore.code(), is("TASK_ID_INVALID"));

    Answer badList =
        call(
            "POST",
            ADMIN + "/raise",
            "{\"listId\":\"not-an-id\",\"storeId\":\"" + shop.store() + "\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(badList.status(), is(400));
    assertThat(badList.code(), is("TASK_ID_INVALID"));

    Answer badStore =
        call(
            "POST",
            ADMIN + "/days",
            "{\"storeId\":\"not-a-store\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(badStore.status(), is(400));
    assertThat(badStore.code(), is("TASK_ID_INVALID"));

    // A body with no store at all is caught by validation before the id is read.
    Answer missing = call("POST", ADMIN + "/days", "{}", shop.tenant(), shop.manager(), "OWNER");
    assertThat(missing.status(), is(400));
    assertThat(missing.code(), is("VALIDATION_FAILED"));

    Answer range =
        call(
            "GET",
            ADMIN + "/days?from=2026-01-01&to=2026-01-02",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(range.status(), is(400));
    assertThat(range.code(), is("TASK_ID_INVALID"));

    assertThat(
        "no day was generated by any of it", today(shop, shop.cashier(), "CASHIER"), hasSize(0));
  }

  @Test
  @DisplayName("A day that is not a date is refused, for the range, the summary and a generation")
  void aDayThatIsNotADateIsRefused() {
    Shop shop = shop("Europe/London");
    assertThat(writeList(shop, opening("08:00", 60, "Unlock")).status(), is(201));

    Answer days =
        call(
            "GET",
            ADMIN + "/days?storeId=" + shop.store() + "&from=yesterday&to=2026-09-30",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(days.status(), is(400));
    assertThat(days.code(), is("TASK_DATE_INVALID"));

    Answer noFrom =
        call(
            "GET",
            ADMIN + "/summary?storeId=" + shop.store() + "&to=2026-09-30",
            null,
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(noFrom.status(), is(400));
    assertThat(noFrom.code(), is("TASK_DATE_INVALID"));

    Answer generated =
        call(
            "POST",
            ADMIN + "/days",
            "{\"storeId\":\"" + shop.store() + "\",\"businessDate\":\"30/09/2026\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(generated.status(), is(400));
    assertThat(generated.code(), is("TASK_DATE_INVALID"));

    Answer raised =
        call(
            "POST",
            ADMIN + "/raise",
            "{\"listId\":\""
                + Ids.newId()
                + "\",\"storeId\":\""
                + shop.store()
                + "\",\"businessDate\":\"tomorrow\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(raised.status(), is(400));
    assertThat(raised.code(), is("TASK_DATE_INVALID"));

    Answer cashierDate =
        call(
            "GET",
            WORK + "?storeId=" + shop.store() + "&date=soon",
            null,
            shop.tenant(),
            shop.cashier(),
            "CASHIER");
    assertThat(cashierDate.status(), is(400));
    assertThat(cashierDate.code(), is("TASK_DATE_INVALID"));
    assertThat("nothing was generated", today(shop, shop.cashier(), "CASHIER"), hasSize(0));
  }

  @Test
  @DisplayName(
      "A store's own list is not raised at another store, and an unknown list is not found")
  void aStoresOwnListIsNotRaisedAtAnother() {
    Shop shop = shop("Europe/London");
    Answer other =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Side Street\",\"code\":\"SS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Side Street\",\"city\":\"Leeds\",\"country\":\"GB\",\"pincode\":\"LS1 4AB\",\"timezone\":\"Europe/London\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(other.text(), other.status(), is(201));
    String otherStore = other.data().getString("id");
    Answer van =
        writeList(
            shop,
            "{\"title\":\"Wash the van\",\"kind\":\"AD_HOC\",\"storeId\":\""
                + otherStore
                + "\",\"dueTime\":\"12:00\"}");
    assertThat(van.text(), van.status(), is(201));

    Answer refused = raise(shop, van.data().getString("id"), shop.store());
    assertThat(refused.status(), is(409));
    assertThat(refused.code(), is("TASK_LIST_OTHER_STORE"));
    assertThat("no task was created", today(shop, shop.cashier(), "CASHIER"), hasSize(0));

    Answer unknown = raise(shop, Ids.newId().toString(), shop.store());
    assertThat(unknown.status(), is(404));
    assertThat(unknown.code(), is("TASK_LIST_NOT_FOUND"));

    // Another business's list, named by id, is not found either.
    Shop rival = shop("Europe/London");
    Answer theirs = writeList(rival, opening("08:00", 60, "Unlock"));
    assertThat(theirs.status(), is(201));
    Answer stolen = raise(shop, theirs.data().getString("id"), shop.store());
    assertThat(stolen.status(), is(404));
    assertThat(stolen.code(), is("TASK_LIST_NOT_FOUND"));
    assertThat(today(shop, shop.cashier(), "CASHIER"), hasSize(0));
  }

  @Test
  @DisplayName("Generating a store's day is management's, at its own stores, in its own business")
  void generatingADayIsRefusedToAnyoneElse() {
    Shop shop = shop("Europe/London");
    Answer other =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Side Street\",\"code\":\"SS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Side Street\",\"city\":\"Leeds\",\"country\":\"GB\",\"pincode\":\"LS1 4AB\",\"timezone\":\"Europe/London\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    String otherStore = other.data().getString("id");
    assertThat(writeList(shop, opening("08:00", 60, "Unlock")).status(), is(201));
    String body = "{\"storeId\":\"" + shop.store() + "\"}";
    String otherBody = "{\"storeId\":\"" + otherStore + "\"}";

    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      Answer staff = call("POST", ADMIN + "/days", body, shop.tenant(), shop.cashier(), role);
      assertThat(role, staff.status(), is(403));
    }
    // A manager held to the other store may not generate this store's day.
    Answer held =
        call("POST", ADMIN + "/days", body, shop.tenant(), shop.manager(), "MANAGER", otherStore);
    assertThat(held.status(), is(403));
    assertThat(held.code(), is("STORE_ACCESS_DENIED"));
    // Another business's staff of every role, naming our store, find no such store.
    Shop rival = shop("Europe/London");
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs = call("POST", ADMIN + "/days", body, rival.tenant(), rival.manager(), role);
      assertThat(role, theirs.status(), is(404));
      assertThat(theirs.code(), is("STORE_NOT_FOUND"));
    }
    Answer theirOther =
        call("POST", ADMIN + "/days", otherBody, rival.tenant(), rival.manager(), "OWNER");
    assertThat(theirOther.status(), is(404));
    assertThat(
        "nothing was generated at our store", today(shop, shop.cashier(), "CASHIER"), hasSize(0));
  }

  /** An ad-hoc list for one store; its id. */
  private String adHocAt(Shop shop, String storeId, String title) {
    Answer a =
        writeList(
            shop,
            "{\"title\":\""
                + title
                + "\",\"kind\":\"AD_HOC\",\"storeId\":\""
                + storeId
                + "\",\"dueTime\":\"12:00\"}");
    assertThat(a.text(), a.status(), is(201));
    return a.data().getString("id");
  }

  /** The business's lists as its owner sees them: id to status. */
  private java.util.Map<String, String> listStatuses(Shop shop) {
    Answer all =
        call("GET", ADMIN + "/lists?all=true", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(all.text(), all.status(), is(200));
    java.util.Map<String, String> out = new java.util.HashMap<>();
    for (JsonObject t : all.list()) out.put(t.getString("id"), t.getString("status"));
    return out;
  }

  @Test
  @DisplayName(
      "A branch manager writes, reads and withdraws their own store's lists, reads every store's,"
          + " and never touches another store's")
  void heldToTheCallersStores() {
    Shop shop = shop("Europe/London");
    Answer other =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Side Street\",\"code\":\"SS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Side Street\",\"city\":\"Leeds\",\"country\":\"GB\",\"pincode\":\"LS1 4AB\",\"timezone\":\"Europe/London\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(other.text(), other.status(), is(201));
    String storeB = other.data().getString("id");
    String atA = adHocAt(shop, shop.store(), "Wash the van");
    String atB = adHocAt(shop, storeB, "Sweep the yard");
    Answer opening = writeList(shop, opening("08:00", 60, "Unlock"));
    assertThat(opening.text(), opening.status(), is(201));
    String everywhere = opening.data().getString("id");
    String m = shop.manager();
    String heldA = shop.store();

    // Writing: their store; never another store, never every store.
    String forA =
        "{\"title\":\"Mop\",\"kind\":\"AD_HOC\",\"storeId\":\""
            + shop.store()
            + "\",\"dueTime\":\"12:00\"}";
    String forB = forA.replace(shop.store(), storeB);
    String forAll = "{\"title\":\"Mop\",\"kind\":\"DAILY\",\"dueTime\":\"12:00\"}";
    Answer own = call("POST", ADMIN + "/lists", forA, shop.tenant(), m, "MANAGER", heldA);
    assertThat(own.text(), own.status(), is(201));
    Answer elsewhere = call("POST", ADMIN + "/lists", forB, shop.tenant(), m, "MANAGER", heldA);
    assertThat(elsewhere.text(), elsewhere.status(), is(403));
    assertThat(elsewhere.code(), is("STORE_ACCESS_DENIED"));
    Answer all = call("POST", ADMIN + "/lists", forAll, shop.tenant(), m, "MANAGER", heldA);
    assertThat(all.text(), all.status(), is(403));
    assertThat(all.code(), is("BUSINESS_WIDE_ONLY"));
    assertThat("the refusals wrote nothing", listStatuses(shop).size(), is(4));

    // The list: A's own and every store's, never B's.
    Answer listed =
        call("GET", ADMIN + "/lists?all=true", null, shop.tenant(), m, "MANAGER", heldA);
    assertThat(listed.text(), listed.status(), is(200));
    List<String> ids = listed.list().stream().map(t -> t.getString("id")).toList();
    assertThat(ids.contains(atA) && ids.contains(everywhere), is(true));
    assertThat("B's list is not listed", ids.contains(atB), is(false));
    assertThat(listed.text(), not(containsString("Sweep the yard")));

    // One list: B's refused; A's and every store's read.
    Answer theirs = call("GET", ADMIN + "/lists/" + atB, null, shop.tenant(), m, "MANAGER", heldA);
    assertThat(theirs.text(), theirs.status(), is(403));
    assertThat(theirs.code(), is("STORE_ACCESS_DENIED"));
    assertThat(theirs.text(), not(containsString("Sweep the yard")));
    for (String id : List.of(atA, everywhere)) {
      Answer read = call("GET", ADMIN + "/lists/" + id, null, shop.tenant(), m, "MANAGER", heldA);
      assertThat(read.text(), read.status(), is(200));
    }

    // One task: B's refused, A's read.
    Answer taskAtB = raise(shop, atB, storeB);
    assertThat(taskAtB.text(), taskAtB.status(), is(201));
    Answer taskAtA = raise(shop, atA, shop.store());
    assertThat(taskAtA.text(), taskAtA.status(), is(201));
    Answer bTask =
        call(
            "GET",
            ADMIN + "/" + taskAtB.data().getString("id"),
            null,
            shop.tenant(),
            m,
            "MANAGER",
            heldA);
    assertThat(bTask.text(), bTask.status(), is(403));
    assertThat(bTask.code(), is("STORE_ACCESS_DENIED"));
    assertThat(
        call(
                "GET",
                ADMIN + "/" + taskAtA.data().getString("id"),
                null,
                shop.tenant(),
                m,
                "MANAGER",
                heldA)
            .status(),
        is(200));

    // Withdrawing: A's own; B's and every store's are refused and stay active.
    Answer withdrawB =
        call("DELETE", ADMIN + "/lists/" + atB, null, shop.tenant(), m, "MANAGER", heldA);
    assertThat(withdrawB.text(), withdrawB.status(), is(403));
    assertThat(withdrawB.code(), is("STORE_ACCESS_DENIED"));
    Answer withdrawAll =
        call("DELETE", ADMIN + "/lists/" + everywhere, null, shop.tenant(), m, "MANAGER", heldA);
    assertThat(withdrawAll.text(), withdrawAll.status(), is(403));
    assertThat(withdrawAll.code(), is("BUSINESS_WIDE_ONLY"));
    assertThat(listStatuses(shop).get(atB), is("ACTIVE"));
    assertThat(listStatuses(shop).get(everywhere), is("ACTIVE"));
    Answer withdrawA =
        call("DELETE", ADMIN + "/lists/" + atA, null, shop.tenant(), m, "MANAGER", heldA);
    assertThat(withdrawA.text(), withdrawA.status(), is(200));

    // A manager held to no store: every list, and withdraws any.
    Answer whole = call("GET", ADMIN + "/lists?all=true", null, shop.tenant(), m, "MANAGER", null);
    assertThat(whole.list(), hasSize(4));
    assertThat(
        call("DELETE", ADMIN + "/lists/" + atB, null, shop.tenant(), m, "MANAGER", null).status(),
        is(200));

    // Another business: its management, held to its own store or to none, finds no such list or
    // task; its staff and shoppers are refused outright; its list holds none of ours.
    Shop rival = shop("Europe/London");
    String ourTask = taskAtA.data().getString("id");
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {null, rival.store()}) {
        Answer list =
            call(
                "GET",
                ADMIN + "/lists/" + everywhere,
                null,
                rival.tenant(),
                rival.manager(),
                role,
                held);
        assertThat(role + " -> " + list.text(), list.status(), is(404));
        assertThat(list.code(), is("TASK_LIST_NOT_FOUND"));
        Answer withdraw =
            call(
                "DELETE",
                ADMIN + "/lists/" + everywhere,
                null,
                rival.tenant(),
                rival.manager(),
                role,
                held);
        assertThat(role + " -> " + withdraw.text(), withdraw.status(), is(404));
        Answer task =
            call("GET", ADMIN + "/" + ourTask, null, rival.tenant(), rival.manager(), role, held);
        assertThat(role + " -> " + task.text(), task.status(), is(404));
        assertThat(task.code(), is("TASK_NOT_FOUND"));
        Answer lists =
            call(
                "GET",
                ADMIN + "/lists?all=true",
                null,
                rival.tenant(),
                rival.manager(),
                role,
                held);
        assertThat(lists.text(), lists.status(), is(200));
        assertThat(lists.list(), hasSize(0));
      }
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(
          role,
          call("GET", ADMIN + "/lists/" + everywhere, null, rival.tenant(), rival.cashier(), role)
              .status(),
          is(403));
      assertThat(
          role,
          call("DELETE", ADMIN + "/lists/" + everywhere, null, shop.tenant(), shop.cashier(), role)
              .status(),
          is(403));
    }
    assertThat("nobody else withdrew it", listStatuses(shop).get(everywhere), is("ACTIVE"));
  }

  /** A one-line list at a store, put on its day by hand; the task's id. */
  private String taskAt(Shop shop, String storeId, String title) {
    Answer list =
        writeList(
            shop,
            "{\"title\":\""
                + title
                + "\",\"kind\":\"AD_HOC\",\"storeId\":\""
                + storeId
                + "\",\"dueTime\":\"12:00\",\"lines\":[{\"text\":\"Safe\",\"required\":true}]}");
    assertThat(list.text(), list.status(), is(201));
    Answer task = raise(shop, list.data().getString("id"), storeId);
    assertThat(task.text(), task.status(), is(201));
    return task.data().getString("id");
  }

  private JsonObject taskAsOwner(Shop shop, String id) {
    Answer a = call("GET", ADMIN + "/" + id, null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(a.text(), a.status(), is(200));
    return a.data();
  }

  @Test
  @DisplayName(
      "Working a task at another store is 403 for a store-held caller; management held to no store"
          + " works any store's; another business finds none")
  void workingATaskIsHeldToTheCallersStores() {
    Shop shop = shop("Europe/London");
    Answer other =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Side Street\",\"code\":\"SS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Side Street\",\"city\":\"Leeds\",\"country\":\"GB\",\"pincode\":\"LS1 4AB\",\"timezone\":\"Europe/London\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(other.text(), other.status(), is(201));
    String storeB = other.data().getString("id");
    String atB = taskAt(shop, storeB, "Lock the yard");
    String alsoAtB = taskAt(shop, storeB, "Bins out");
    String heldA = shop.store();

    // Our cashier at A, and a branch manager of A: B's task is not theirs to work — 403, not 409.
    for (String[] who : new String[][] {{shop.cashier(), "CASHIER"}, {shop.manager(), "MANAGER"}}) {
      for (String[] act :
          new String[][] {
            {atB + "/lines/1/tick", null},
            {atB + "/complete", "{}"},
            {atB + "/skip", "{\"reason\":\"rain\"}"}
          }) {
        Answer refused =
            call("POST", WORK + "/" + act[0], act[1], shop.tenant(), who[0], who[1], heldA);
        assertThat(who[1] + " " + act[0] + " -> " + refused.text(), refused.status(), is(403));
        assertThat(refused.code(), is("STORE_ACCESS_DENIED"));
      }
    }
    JsonObject untouched = taskAsOwner(shop, atB);
    assertThat(untouched.getString("status"), is("OPEN"));
    assertThat(untouched.getJsonArray("items").getJsonObject(0).containsKey("tickedAt"), is(false));

    // Another business: its staff and management, naming our store among their own or held to
    // none, find no such task; a shopper is refused at the door.
    Shop rival = shop("Europe/London");
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "MANAGER", "OWNER"}) {
      for (String held : new String[] {heldA, storeB, null}) {
        Answer theirs =
            call(
                "POST",
                WORK + "/" + atB + "/lines/1/tick",
                null,
                rival.tenant(),
                rival.cashier(),
                role,
                held);
        assertThat(role + " -> " + theirs.text(), theirs.status(), is(404));
        assertThat(theirs.code(), is("TASK_NOT_FOUND"));
        Answer skip =
            call(
                "POST",
                WORK + "/" + atB + "/skip",
                "{\"reason\":\"x\"}",
                rival.tenant(),
                rival.cashier(),
                role,
                held);
        assertThat(role + " -> " + skip.text(), skip.status(), is(404));
      }
    }
    assertThat(
        call(
                "POST",
                WORK + "/" + atB + "/complete",
                "{}",
                rival.tenant(),
                Ids.newId().toString(),
                "CUSTOMER")
            .status(),
        is(403));
    assertThat(taskAsOwner(shop, atB).getString("status"), is("OPEN"));

    // A manager held to no store — assigned at none — ticks and finishes B's task; the owner skips
    // the other one.
    String headOffice = Ids.newId().toString();
    Answer ticked =
        call(
            "POST",
            WORK + "/" + atB + "/lines/1/tick",
            null,
            shop.tenant(),
            headOffice,
            "MANAGER",
            null);
    assertThat(ticked.text(), ticked.status(), is(200));
    Answer done =
        call(
            "POST",
            WORK + "/" + atB + "/complete",
            "{}",
            shop.tenant(),
            headOffice,
            "MANAGER",
            null);
    assertThat(done.text(), done.status(), is(200));
    assertThat(done.data().getString("status"), is("DONE"));
    assertThat(done.data().getString("completedBy"), is(headOffice));
    Answer skipped =
        call(
            "POST",
            WORK + "/" + alsoAtB + "/skip",
            "{\"reason\":\"yard flooded\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER",
            null);
    assertThat(skipped.text(), skipped.status(), is(200));
    assertThat(skipped.data().getString("status"), is("SKIPPED"));

    // A cashier whose token names no store is still held to where they are assigned.
    String atA = taskAt(shop, shop.store(), "Count the float");
    Answer stranger =
        call(
            "POST",
            WORK + "/" + atA + "/lines/1/tick",
            null,
            shop.tenant(),
            Ids.newId().toString(),
            "CASHIER");
    assertThat(stranger.status(), is(409));
    assertThat(stranger.code(), is("WORKFORCE_NOT_ASSIGNED"));
  }

  private static int instancesOf(String tenant) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement("SELECT count(*) FROM task_instances WHERE tenant_id = ?::uuid")) {
        ps.setString(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Every write and read of a store's lists, naming one store, as one caller; its answers. */
  private List<Answer> everyListActAt(
      String tenant, String user, String role, String heldTo, String store, String listId) {
    String day = LocalDate.now(ZoneId.of("Europe/London")).toString();
    return List.of(
        call(
            "POST",
            ADMIN + "/lists",
            "{\"title\":\"Spill\",\"kind\":\"AD_HOC\",\"storeId\":\""
                + store
                + "\",\"dueTime\":\"12:00\"}",
            tenant,
            user,
            role,
            heldTo),
        call(
            "POST", ADMIN + "/days", "{\"storeId\":\"" + store + "\"}", tenant, user, role, heldTo),
        call(
            "POST",
            ADMIN + "/raise",
            "{\"listId\":\"" + listId + "\",\"storeId\":\"" + store + "\"}",
            tenant,
            user,
            role,
            heldTo),
        call(
            "GET",
            ADMIN + "/days?storeId=" + store + "&from=" + day + "&to=" + day,
            null,
            tenant,
            user,
            role,
            heldTo),
        call(
            "GET",
            ADMIN + "/summary?storeId=" + store + "&from=" + day + "&to=" + day,
            null,
            tenant,
            user,
            role,
            heldTo),
        call("GET", WORK + "?storeId=" + store, null, tenant, user, role, heldTo));
  }

  @Test
  @DisplayName(
      "Writing, generating, raising and reading a store's lists judge the store first: not the"
          + " business's is 404, not the caller's is 403, and nothing is written")
  void theStoreIsJudgedBeforeTheCaller() {
    Shop shop = shop("Europe/London");
    Shop rival = shop("Europe/London");
    Answer other =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Side Street\",\"code\":\"SS-"
                + Ids.newId().toString().substring(28)
                + "\",\"line1\":\"2 Side Street\",\"city\":\"Leeds\",\"country\":\"GB\",\"pincode\":\"LS1 4AB\",\"timezone\":\"Europe/London\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(other.text(), other.status(), is(201));
    String otherStore = other.data().getString("id");
    String list = adHocAt(shop, shop.store(), "Put the delivery away");
    String theirList = adHocAt(rival, rival.store(), "Their delivery");
    int lists = listStatuses(shop).size();
    String unknown = Ids.newId().toString();

    // Our branch manager naming a store that is nobody's: not found (it was 403).
    for (Answer a :
        everyListActAt(shop.tenant(), shop.manager(), "MANAGER", shop.store(), unknown, list)) {
      assertThat(a.text(), a.status(), is(404));
      assertThat(a.code(), is("STORE_NOT_FOUND"));
    }
    // Our branch manager of the other store naming this one: the business's, not theirs.
    for (Answer a :
        everyListActAt(shop.tenant(), shop.manager(), "MANAGER", otherStore, shop.store(), list)) {
      assertThat(a.text(), a.status(), is(403));
      assertThat(a.code(), is("STORE_ACCESS_DENIED"));
    }
    // Another business's management naming our store, held to its own, to ours or to none, with
    // its own list: our store does not exist for it.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {rival.store(), shop.store(), null}) {
        for (Answer a :
            everyListActAt(rival.tenant(), rival.manager(), role, held, shop.store(), theirList)) {
          assertThat(role + " -> " + a.text(), a.status(), is(404));
          assertThat(a.code(), is("STORE_NOT_FOUND"));
        }
      }
    }
    // Another business's cashier reading our store's list: not found, never 403.
    for (String held : new String[] {rival.store(), shop.store(), null}) {
      Answer read =
          call(
              "GET",
              WORK + "?storeId=" + shop.store(),
              null,
              rival.tenant(),
              rival.cashier(),
              "CASHIER",
              held);
      assertThat(read.text(), read.status(), is(404));
      assertThat(read.code(), is("STORE_NOT_FOUND"));
    }
    assertThat("no list was written", listStatuses(shop).size(), is(lists));
    assertThat("no day was generated or raised", instancesOf(shop.tenant()), is(0));
    assertThat(instancesOf(rival.tenant()), is(0));
    assertThat(listStatuses(rival).size(), is(1));
  }
}
