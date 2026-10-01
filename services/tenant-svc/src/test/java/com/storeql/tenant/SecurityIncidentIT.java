package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The security incident register (21.15): the statutory clocks, the order of reports, notices to
 * the businesses affected and only them, and everything the wrong caller or the wrong input must be
 * refused — including two people recording the same report at the same moment.
 */
@HelidonTest
class SecurityIncidentIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "tenant");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String BASE = "/platform/security-incidents";
  private static final String ADMIN = Ids.newId().toString();

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String path, String roles, String tenant, String user) {
    var b = target.path(path).request(MediaType.APPLICATION_JSON);
    if (roles != null) b = b.header("X-Roles", roles);
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (user != null) b = b.header("X-User-Id", user);
    return b;
  }

  private Invocation.Builder platform(String path) {
    return as(path, "PLATFORM_ADMIN", null, ADMIN);
  }

  private Response listed(String status) {
    return target
        .path(BASE)
        .queryParam("status", status)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Roles", "PLATFORM_ADMIN")
        .header("X-User-Id", ADMIN)
        .get();
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static JsonObject stage(JsonObject incident, String name) {
    for (var v : incident.getJsonArray("stages")) {
      if (v.asJsonObject().getString("stage").equals(name)) return v.asJsonObject();
    }
    throw new AssertionError(name + " not in " + incident);
  }

  private record Business(String id, String owner) {}

  private Business onboard(String name) {
    return onboardIn(name, "gb", "gbp");
  }

  private Business onboardIn(String name, String country, String currency) {
    String owner = Ids.newId().toString();
    Response r =
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", owner)
            .post(
                Entity.json(
                    "{\"businessName\":\""
                        + name
                        + " "
                        + Ids.newId()
                        + "\",\"country\":\""
                        + country
                        + "\",\"currency\":\""
                        + currency
                        + "\"}"));
    assertThat(r.getStatus(), is(201));
    return new Business(data(r).getString("id"), owner);
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static Instant ago(Duration d) {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(d);
  }

  private Response open(String kind, Instant aware, String tenantsJson) {
    return platform(BASE)
        .post(
            Entity.json(
                "{\"kind\":\""
                    + kind
                    + "\",\"title\":\"Checkout token leak\",\"summary\":\"Session tokens logged by a proxy\","
                    + "\"awareAt\":\""
                    + aware
                    + "\""
                    + (tenantsJson == null ? "" : ",\"tenantIds\":" + tenantsJson)
                    + "}"));
  }

  private JsonObject opened(String kind, Instant aware, String tenantsJson) {
    Response r = open(kind, aware, tenantsJson);
    assertThat(r.getStatus(), is(201));
    return data(r);
  }

  private Response record(String id, String json) {
    return platform(BASE + "/" + id + "/events").post(Entity.json(json));
  }

  private static String event(String kind, Instant at, String reference) {
    return "{\"kind\":\""
        + kind
        + "\""
        + (at == null ? "" : ",\"occurredAt\":\"" + at + "\"")
        + (reference == null ? "" : ",\"reference\":\"" + reference + "\"")
        + "}";
  }

  // ── the clocks ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "An exploited vulnerability runs 24 h, 72 h and 14 days after a measure, and closes when all are done")
  void anExploitedVulnerabilityRunsItsClocks() {
    Business shop = onboard("Clocks");
    Instant aware = ago(Duration.ofHours(2));
    JsonObject i = opened("EXPLOITED_VULNERABILITY", aware, "[\"" + shop.id() + "\"]");
    String id = i.getString("id");
    assertThat(i.getString("status"), is("OPEN"));
    assertThat(
        stage(i, "EARLY_WARNING").getString("dueAt"),
        is(aware.plus(Duration.ofHours(24)).toString()));
    assertThat(stage(i, "EARLY_WARNING").getString("state"), is("DUE"));
    assertThat(
        stage(i, "NOTIFICATION").getString("dueAt"),
        is(aware.plus(Duration.ofHours(72)).toString()));
    assertThat(stage(i, "FINAL_REPORT").getString("state"), is("WAITING"));
    assertThat(stage(i, "TENANT_NOTICE").getString("state"), is("NO_DEADLINE"));
    assertThat(stage(i, "EARLY_WARNING").getString("citation"), containsString("2024/2847"));

    Response warned =
        record(id, event("EARLY_WARNING_SENT", ago(Duration.ofHours(1)), "SRP-2026-0042"));
    assertThat(warned.getStatus(), is(201));
    JsonObject w = data(warned);
    assertThat(stage(w, "EARLY_WARNING").getString("state"), is("DONE"));
    assertThat(
        w.getJsonArray("events").getJsonObject(0).getString("reference"), is("SRP-2026-0042"));

    Response early = record(id, event("FINAL_REPORT_SENT", null, null));
    assertThat(early.getStatus(), is(409));
    assertThat(early.readEntity(String.class), containsString("INCIDENT_FINAL_REPORT_TOO_EARLY"));

    assertThat(
        record(id, event("NOTIFICATION_SENT", ago(Duration.ofMinutes(50)), "SRP-2026-0042"))
            .getStatus(),
        is(201));
    Instant measure = ago(Duration.ofMinutes(40));
    JsonObject m = data(record(id, event("MITIGATION_AVAILABLE", measure, null)));
    assertThat(
        stage(m, "FINAL_REPORT").getString("dueAt"),
        is(measure.plus(Duration.ofDays(14)).toString()));
    assertThat(stage(m, "FINAL_REPORT").getString("state"), is("DUE"));

    Response tooSoon = record(id, event("CLOSED", null, null));
    assertThat(tooSoon.getStatus(), is(409));
    assertThat(tooSoon.readEntity(String.class), containsString("INCIDENT_STAGES_OUTSTANDING"));

    assertThat(
        record(id, event("FINAL_REPORT_SENT", null, "SRP-2026-0042-F")).getStatus(), is(201));
    Response told =
        platform(BASE + "/" + id + "/notices")
            .post(Entity.json("{\"message\":\"Rotate your staff passwords.\"}"));
    assertThat(told.getStatus(), is(200));
    assertThat(data(told).getInt("issued"), is(1));

    Response closed = record(id, event("CLOSED", null, null));
    assertThat(closed.getStatus(), is(201));
    assertThat(data(closed).getString("status"), is("CLOSED"));
    Response after = record(id, "{\"kind\":\"NOTE\",\"note\":\"one more thing\"}");
    assertThat(after.getStatus(), is(409));
    assertThat(after.readEntity(String.class), containsString("INCIDENT_CLOSED"));
    assertThat(
        platform(BASE + "/" + id + "/notices")
            .post(Entity.json("{\"message\":\"again\"}"))
            .getStatus(),
        is(409));
  }

  @Test
  @DisplayName(
      "A missed 24 hours is overdue on the register, and the list says which stage is next")
  void overdueIsFlagged() {
    String id = opened("SEVERE_INCIDENT", ago(Duration.ofHours(30)), null).getString("id");
    JsonObject sheet = data(platform(BASE + "/" + id).get());
    assertThat(stage(sheet, "EARLY_WARNING").getString("state"), is("OVERDUE"));
    assertThat(stage(sheet, "NOTIFICATION").getString("state"), is("DUE"));
    assertThat(sheet.getBoolean("affectsAllTenants"), is(true));

    JsonArray list =
        Json.createReader(new StringReader(listed("open").readEntity(String.class)))
            .readObject()
            .getJsonArray("data");
    JsonObject row = null;
    for (var v : list) if (v.asJsonObject().getString("id").equals(id)) row = v.asJsonObject();
    assertThat("listed as open", row != null, is(true));
    assertThat(row.getBoolean("overdue"), is(true));
    assertThat(row.getString("nextStage"), is("EARLY_WARNING"));
    assertThat(listed("closed").readEntity(String.class), not(containsString(id)));
  }

  @Test
  @DisplayName("A severe incident's final report runs a calendar month from its notification")
  void severeIncidentFinalReportRunsFromTheNotification() {
    String id = opened("SEVERE_INCIDENT", ago(Duration.ofHours(3)), null).getString("id");
    Instant notified = ago(Duration.ofHours(1));
    JsonObject n = data(record(id, event("NOTIFICATION_SENT", notified, null)));
    assertThat(
        stage(n, "FINAL_REPORT").getString("dueAt"),
        is(notified.atZone(ZoneOffset.UTC).plusMonths(1).toInstant().toString()));
    Response measure = record(id, event("MITIGATION_AVAILABLE", null, null));
    assertThat(measure.getStatus(), is(400));
    assertThat(measure.readEntity(String.class), containsString("INCIDENT_EVENT_NOT_APPLICABLE"));
  }

  // ── notices ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A breach notice carries the business's own duties, by regime, recorded once each (13.12)")
  void aBreachNoticeCarriesTheBusinessesDuties() {
    Business british = onboard("Kent Grocers");
    Business indian = onboardIn("Chennai Provisions", "IN", "INR");
    Business quiet = onboard("Untouched");
    Instant aware = ago(Duration.ofHours(1));
    String id =
        opened("PERSONAL_DATA_BREACH", aware, "[\"" + british.id() + "\",\"" + indian.id() + "\"]")
            .getString("id");
    data(
        platform(BASE + "/" + id + "/notices")
            .post(Entity.json("{\"message\":\"Names and emails were read.\"}")));

    JsonObject gb = notice(british);
    assertThat(gb.getString("regime"), is("GDPR"));
    assertThat(gb.getBoolean("binding"), is(true));
    JsonArray gbDuties = gb.getJsonArray("duties");
    assertThat(gbDuties.size(), is(2));
    JsonObject authority = duty(gb, "AUTHORITY_NOTIFIED");
    assertThat(authority.getString("state"), is("DUE"));
    assertThat(
        "72 hours from the notice, not from the platform's awareness",
        authority.getString("dueAt"),
        is(Instant.parse(gb.getString("issuedAt")).plus(Duration.ofHours(72)).toString()));
    assertThat(duty(gb, "SUBJECTS_TOLD").getString("state"), is("WAITING"));
    assertThat(absent(duty(gb, "SUBJECTS_TOLD"), "dueAt"), is(true));

    JsonObject in = notice(indian);
    assertThat(in.getString("regime"), is("DPDP"));
    assertThat("the Act's duties bind from 13 May 2027", in.getBoolean("binding"), is(false));
    assertThat(in.getString("bindsFrom"), is("2027-05-13"));
    assertThat(in.getJsonArray("duties").size(), is(3));
    assertThat(duty(in, "PRINCIPALS_TOLD").getString("citation"), containsString("r.7(1)"));
    assertThat(duty(in, "BOARD_REPORTED").getString("state"), is("DUE"));

    String noticeId = in.getString("id");
    JsonObject recorded =
        data(
            as(
                    "/admin/tenant/security-notices/" + noticeId + "/reports",
                    "MANAGER",
                    indian.id(),
                    indian.owner())
                .post(
                    Entity.json(
                        "{\"duty\":\"board_intimated\",\"reference\":\"DPB-2026-0042\",\"note\":\"By the portal.\"}")),
            201);
    JsonObject done = duty(recorded, "BOARD_INTIMATED");
    assertThat(done.getString("state"), is("DONE"));
    assertThat(done.getString("reference"), is("DPB-2026-0042"));
    assertThat(done.getString("recordedBy"), is(indian.owner()));
    assertThat(duty(recorded, "BOARD_REPORTED").getString("state"), is("DUE"));
    assertThat(
        "the list shows it too",
        duty(notice(indian), "BOARD_INTIMATED").getString("state"),
        is("DONE"));

    Response twice =
        as(
                "/admin/tenant/security-notices/" + noticeId + "/reports",
                "OWNER",
                indian.id(),
                indian.owner())
            .post(Entity.json("{\"duty\":\"BOARD_INTIMATED\"}"));
    assertThat(twice.getStatus(), is(409));
    assertThat(twice.readEntity(String.class), containsString("SECURITY_NOTICE_DUTY_DONE"));
    Response wrongRegime =
        as(
                "/admin/tenant/security-notices/" + noticeId + "/reports",
                "OWNER",
                indian.id(),
                indian.owner())
            .post(Entity.json("{\"duty\":\"AUTHORITY_NOTIFIED\"}"));
    assertThat(wrongRegime.getStatus(), is(400));
    assertThat(
        wrongRegime.readEntity(String.class), containsString("SECURITY_NOTICE_DUTY_UNKNOWN"));
    Response future =
        as(
                "/admin/tenant/security-notices/" + noticeId + "/reports",
                "OWNER",
                indian.id(),
                indian.owner())
            .post(
                Entity.json("{\"duty\":\"PRINCIPALS_TOLD\",\"doneAt\":\"2099-01-01T00:00:00Z\"}"));
    assertThat(future.getStatus(), is(400));
    assertThat(future.readEntity(String.class), containsString("SECURITY_NOTICE_DONE_AT_FUTURE"));
    assertThat(
        as(
                "/admin/tenant/security-notices/" + noticeId + "/reports",
                "CASHIER",
                indian.id(),
                Ids.newId().toString())
            .post(Entity.json("{\"duty\":\"PRINCIPALS_TOLD\"}"))
            .getStatus(),
        is(403));
    Response theirs =
        as(
                "/admin/tenant/security-notices/" + noticeId + "/reports",
                "OWNER",
                british.id(),
                british.owner())
            .post(Entity.json("{\"duty\":\"SUBJECTS_TOLD\"}"));
    assertThat(theirs.getStatus(), is(404));
    assertThat(
        as("/admin/tenant/security-notices", "OWNER", quiet.id(), quiet.owner()).get(String.class),
        containsString("\"data\":[]"));

    // A notice of anything but a breach carries no duties.
    String vuln =
        opened("EXPLOITED_VULNERABILITY", aware, "[\"" + british.id() + "\"]").getString("id");
    data(
        platform(BASE + "/" + vuln + "/notices").post(Entity.json("{\"message\":\"Patch now.\"}")));
    JsonObject plain =
        notices(british).stream()
            .map(JsonObject.class::cast)
            .filter(n -> n.getString("incidentId").equals(vuln))
            .findFirst()
            .orElseThrow();
    assertThat(absent(plain, "regime"), is(true));
    assertThat(plain.getJsonArray("duties").size(), is(0));
    Response noDuties =
        as(
                "/admin/tenant/security-notices/" + plain.getString("id") + "/reports",
                "OWNER",
                british.id(),
                british.owner())
            .post(Entity.json("{\"duty\":\"SUBJECTS_TOLD\"}"));
    assertThat(noDuties.readEntity(String.class), containsString("SECURITY_NOTICE_NO_DUTIES"));
  }

  private JsonArray notices(Business b) {
    Response r = as("/admin/tenant/security-notices", "OWNER", b.id(), b.owner()).get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Json.createReader(new StringReader(body)).readObject().getJsonArray("data");
  }

  private JsonObject notice(Business b) {
    return notices(b).getJsonObject(0);
  }

  /** JSON-B leaves a null field out, so absent and null are the same answer. */
  private static boolean absent(JsonObject o, String field) {
    return !o.containsKey(field) || o.isNull(field);
  }

  private static JsonObject duty(JsonObject notice, String duty) {
    return notice.getJsonArray("duties").stream()
        .map(JsonObject.class::cast)
        .filter(d -> d.getString("duty").equals(duty))
        .findFirst()
        .orElseThrow(() -> new AssertionError(duty + " not in " + notice));
  }

  @Test
  @DisplayName("A breach reaches only the business it affects, which acknowledges it once")
  void aBreachNoticeReachesOnlyTheBusinessAffected() {
    Business affected = onboard("Affected");
    Business other = onboard("Other");
    JsonObject i =
        opened("PERSONAL_DATA_BREACH", ago(Duration.ofMinutes(30)), "[\"" + affected.id() + "\"]");
    String id = i.getString("id");
    assertThat(i.getJsonArray("stages").size(), is(1));
    Response warning = record(id, event("EARLY_WARNING_SENT", null, null));
    assertThat(warning.getStatus(), is(400));
    assertThat(warning.readEntity(String.class), containsString("INCIDENT_EVENT_NOT_APPLICABLE"));

    JsonObject issued =
        data(
            platform(BASE + "/" + id + "/notices")
                .post(
                    Entity.json(
                        "{\"message\":\"Customer emails were exposed; tell your supervisory authority within 72 hours.\"}")));
    assertThat(issued.getInt("issued"), is(1));
    assertThat(issued.getInt("total"), is(1));
    JsonObject again =
        data(platform(BASE + "/" + id + "/notices").post(Entity.json("{\"message\":\"again\"}")));
    assertThat("a notice goes once per business", again.getInt("issued"), is(0));

    String mine =
        as("/admin/tenant/security-notices", "OWNER", affected.id(), affected.owner())
            .get(String.class);
    assertThat(mine, containsString("Security notice: Checkout token leak"));
    assertThat(mine, containsString("\"acknowledged\":false"));
    String noticeId =
        Json.createReader(new StringReader(mine))
            .readObject()
            .getJsonArray("data")
            .getJsonObject(0)
            .getString("id");
    assertThat(
        as("/admin/tenant/security-notices", "OWNER", other.id(), other.owner()).get(String.class),
        containsString("\"data\":[]"));
    assertThat(
        as("/admin/tenant/security-notices", "CASHIER", affected.id(), Ids.newId().toString())
            .get()
            .getStatus(),
        is(403));

    Response theirs =
        as(
                "/admin/tenant/security-notices/" + noticeId + "/acknowledge",
                "OWNER",
                other.id(),
                other.owner())
            .post(Entity.json(""));
    assertThat(theirs.getStatus(), is(404));
    assertThat(theirs.readEntity(String.class), containsString("SECURITY_NOTICE_NOT_FOUND"));
    JsonObject ack =
        data(
            as(
                    "/admin/tenant/security-notices/" + noticeId + "/acknowledge",
                    "MANAGER",
                    affected.id(),
                    affected.owner())
                .post(Entity.json("")));
    assertThat(ack.getBoolean("acknowledged"), is(true));
    JsonObject ack2 =
        data(
            as(
                    "/admin/tenant/security-notices/" + noticeId + "/acknowledge",
                    "OWNER",
                    affected.id(),
                    affected.owner())
                .post(Entity.json("")));
    assertThat(
        "the first acknowledgement stands",
        ack2.getString("acknowledgedAt"),
        is(ack.getString("acknowledgedAt")));
    JsonObject sheet = data(platform(BASE + "/" + id).get());
    assertThat(sheet.getInt("noticesIssued"), is(1));
    assertThat(sheet.getInt("noticesAcknowledged"), is(1));
    assertThat(stage(sheet, "TENANT_NOTICE").getString("state"), is("DONE"));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Only the platform administrator opens, reads or records an incident")
  void onlyThePlatformAdministrator() {
    String id = opened("SEVERE_INCIDENT", ago(Duration.ofHours(1)), null).getString("id");
    Business shop = onboard("Nosy");
    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      assertThat(role, as(BASE, role, shop.id(), shop.owner()).get().getStatus(), is(403));
      assertThat(
          role, as(BASE + "/" + id, role, shop.id(), shop.owner()).get().getStatus(), is(403));
      assertThat(
          role,
          as(BASE + "/" + id + "/events", role, shop.id(), shop.owner())
              .post(Entity.json(event("NOTE", null, null)))
              .getStatus(),
          is(403));
    }
    assertThat(as(BASE, null, null, null).get().getStatus(), is(403));
  }

  @Test
  @DisplayName("Bad input is refused by name, and nothing is updated or deleted")
  void badInputIsRefused() {
    String[][] openCases = {
      {"PANIC", ago(Duration.ofHours(1)).toString(), null, "INCIDENT_KIND_UNKNOWN"},
      {
        "SEVERE_INCIDENT",
        Instant.now().plus(Duration.ofHours(2)).toString(),
        null,
        "INCIDENT_AWARE_IN_FUTURE"
      },
      {"SEVERE_INCIDENT", "yesterday", null, "INVALID_DATE"},
      {
        "SEVERE_INCIDENT",
        ago(Duration.ofHours(1)).toString(),
        "[\"" + Ids.newId() + "\"]",
        "INCIDENT_TENANT_UNKNOWN"
      },
      {"SEVERE_INCIDENT", ago(Duration.ofHours(1)).toString(), "[\"not-a-uuid\"]", "INVALID_UUID"},
    };
    for (String[] c : openCases) {
      Response r =
          platform(BASE)
              .post(
                  Entity.json(
                      "{\"kind\":\""
                          + c[0]
                          + "\",\"title\":\"t\",\"summary\":\"s\",\"awareAt\":\""
                          + c[1]
                          + "\""
                          + (c[2] == null ? "" : ",\"tenantIds\":" + c[2])
                          + "}"));
      assertThat(String.join(" ", c), r.getStatus(), is(400));
      assertThat(String.join(" ", c), r.readEntity(String.class), containsString(c[3]));
    }
    Response longTitle =
        platform(BASE)
            .post(
                Entity.json(
                    "{\"kind\":\"SEVERE_INCIDENT\",\"title\":\""
                        + "x".repeat(201)
                        + "\",\"summary\":\"s\",\"awareAt\":\""
                        + ago(Duration.ofHours(1))
                        + "\"}"));
    assertThat(longTitle.readEntity(String.class), containsString("INCIDENT_TITLE_INVALID"));
    assertThat(
        platform(BASE).post(Entity.json("{\"kind\":\"SEVERE_INCIDENT\"}")).getStatus(), is(400));
    Response badStatus = listed("SOMETIMES");
    assertThat(badStatus.getStatus(), is(400));
    assertThat(badStatus.readEntity(String.class), containsString("INCIDENT_STATUS_UNKNOWN"));

    Instant aware = ago(Duration.ofHours(1));
    String id = opened("EXPLOITED_VULNERABILITY", aware, null).getString("id");
    String[][] eventCases = {
      {event("PANICKED", null, null), "INCIDENT_EVENT_UNKNOWN"},
      {event("EARLY_WARNING_SENT", aware.minusSeconds(60), null), "INCIDENT_EVENT_BEFORE_AWARE"},
      {
        event("EARLY_WARNING_SENT", Instant.now().plus(Duration.ofHours(1)), null),
        "INCIDENT_EVENT_IN_FUTURE"
      },
      {event("TENANTS_NOTIFIED", null, null), "INCIDENT_EVENT_NOT_APPLICABLE"},
      {event("EARLY_WARNING_SENT", null, "R".repeat(121)), "INCIDENT_REFERENCE_INVALID"},
      {"{\"kind\":\"NOTE\"}", "INCIDENT_NOTE_REQUIRED"},
    };
    for (String[] c : eventCases) {
      Response r = record(id, c[0]);
      assertThat(c[1], r.getStatus(), is(400));
      assertThat(c[1], r.readEntity(String.class), containsString(c[1]));
    }
    assertThat(record(id, event("EARLY_WARNING_SENT", null, null)).getStatus(), is(201));
    Response twice = record(id, event("EARLY_WARNING_SENT", null, null));
    assertThat(twice.getStatus(), is(409));
    assertThat(twice.readEntity(String.class), containsString("INCIDENT_STAGE_ALREADY_RECORDED"));
    Response ghost = record(Ids.newId().toString(), "{\"kind\":\"NOTE\",\"note\":\"who?\"}");
    assertThat(ghost.getStatus(), is(404));
    assertThat(ghost.readEntity(String.class), containsString("INCIDENT_NOT_FOUND"));
    Response emptyMessage =
        platform(BASE + "/" + id + "/notices").post(Entity.json("{\"message\":\"   \"}"));
    assertThat(emptyMessage.getStatus(), is(400));

    // Append-only: no route edits or removes an incident or its timeline.
    assertThat(
        platform(BASE + "/" + id).put(Entity.json("{}")).getStatus(), anyOf(is(404), is(405)));
    assertThat(platform(BASE + "/" + id).delete().getStatus(), anyOf(is(404), is(405)));
    assertThat(platform(BASE + "/" + id + "/events").delete().getStatus(), anyOf(is(404), is(405)));
  }

  // ── abuse ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Twenty people recording the same report at once record it once; twenty acknowledgements agree")
  void concurrentRecordsAndAcknowledgements() throws Exception {
    Business shop = onboard("Rush");
    String id =
        opened("EXPLOITED_VULNERABILITY", ago(Duration.ofHours(1)), "[\"" + shop.id() + "\"]")
            .getString("id");
    var pool = Executors.newFixedThreadPool(20);
    try {
      List<Future<Integer>> tries = new ArrayList<>();
      for (int k = 0; k < 20; k++) {
        tries.add(
            pool.submit(
                () -> record(id, event("EARLY_WARNING_SENT", null, "SRP-RACE")).getStatus()));
      }
      int created = 0;
      int refused = 0;
      for (var f : tries) {
        int s = f.get();
        if (s == 201) created++;
        else if (s == 409) refused++;
      }
      assertThat(created, is(1));
      assertThat(refused, is(19));
      JsonObject sheet = data(platform(BASE + "/" + id).get());
      long warnings =
          sheet.getJsonArray("events").stream()
              .filter(v -> v.asJsonObject().getString("kind").equals("EARLY_WARNING_SENT"))
              .count();
      assertThat(warnings, is(1L));

      platform(BASE + "/" + id + "/notices")
          .post(Entity.json("{\"message\":\"Change passwords\"}"))
          .close();
      String noticeId =
          Json.createReader(
                  new StringReader(
                      as("/admin/tenant/security-notices", "OWNER", shop.id(), shop.owner())
                          .get(String.class)))
              .readObject()
              .getJsonArray("data")
              .getJsonObject(0)
              .getString("id");
      List<Future<String>> acks = new ArrayList<>();
      for (int k = 0; k < 20; k++) {
        acks.add(
            pool.submit(
                () ->
                    data(as(
                                "/admin/tenant/security-notices/" + noticeId + "/acknowledge",
                                "OWNER",
                                shop.id(),
                                shop.owner())
                            .post(Entity.json("")))
                        .getString("acknowledgedAt")));
      }
      java.util.Set<String> stamps = new java.util.HashSet<>();
      for (var f : acks) stamps.add(f.get());
      assertThat(stamps.size(), is(1));
    } finally {
      pool.shutdownNow();
    }
  }
}
