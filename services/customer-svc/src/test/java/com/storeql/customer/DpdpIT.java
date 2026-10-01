package com.storeql.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.JsonStub.Answer;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * India's DPDP Act as a person meets it (13.12): the notice in their language, consent by purpose
 * withdrawn in one step, a child's guardian, the requests they make and the business's answer, a
 * breach told to them. An Indian business the Act binds, and a British one it does not.
 */
@HelidonTest
class DpdpIT {

  private static final PostgresSupport PG;
  private static final JsonStub NOTIFY;

  /** The Indian business the Act binds, and the British one it does not. */
  private static final String T = Ids.newId().toString();

  private static final String T2 = Ids.newId().toString();

  /** A second Indian business, untouched by the other tests: the notice test starts clean. */
  private static final String T3 = Ids.newId().toString();

  /** A third Indian business, where the race for a first notice is run. */
  private static final String T4 = Ids.newId().toString();

  private static final String OWNER = Ids.newId().toString();
  private static final AtomicBoolean NOTIFY_DOWN = new AtomicBoolean(false);

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start()
        .with(T, "INR", "IN")
        .with(T3, "INR", "IN")
        .with(T4, "INR", "IN")
        .withObligation("IN", "DPDP", "COUNTRY", "2020-01-01", null)
        .with(T2, "GBP", "GB");
    NOTIFY =
        JsonStub.start("notification-svc")
            .on(
                "POST",
                "/notifications/send",
                call ->
                    NOTIFY_DOWN.get()
                        ? new Answer(503, "")
                        : new Answer(202, "{\"data\":{\"status\":\"QUEUED\"}}"));
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "customer");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the notice ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The notice is published per language, versioned, and read before signing up")
  void theNotice() {
    JsonObject none = data(publicGet(T3, "/customers/privacy/notice?language=hi"), 200);
    assertThat(none.toString(), absent(none, "notice"), is(true));
    assertThat(none.getBoolean("dpdp"), is(true));
    assertThat(none.getJsonArray("languages"), hasSize(23));
    assertThat(none.getJsonArray("purposes"), hasSize(4));
    assertThat(none.getString("requested"), is("hi"));

    JsonObject en =
        data(
            staff(T3, "OWNER", "/customers/privacy/notices")
                .post(
                    json(
                        "{\"language\":\"en\",\"title\":\"How we use your data\",\"body\":\"We keep your orders…\"}")),
            201);
    assertThat(en.getInt("version"), is(1));
    JsonObject hi =
        data(
            staff(T3, "MANAGER", "/customers/privacy/notices")
                .post(
                    json(
                        "{\"language\":\"HI\",\"title\":\"हम आपके डेटा का उपयोग कैसे करते हैं\",\"body\":\"हम आपके ऑर्डर रखते हैं…\"}")),
            201);
    assertThat(hi.getString("language"), is("hi"));
    assertThat(hi.getString("languageName"), is("Hindi"));

    JsonObject served = data(publicGet(T3, "/customers/privacy/notice?language=hi"), 200);
    assertThat(served.getString("served"), is("hi"));
    assertThat(served.getJsonObject("notice").getString("title"), containsString("डेटा"));
    JsonObject fallback = data(publicGet(T3, "/customers/privacy/notice?language=ta"), 200);
    assertThat("Tamil not published: English is served", fallback.getString("served"), is("en"));
    assertThat(fallback.getString("requested"), is("ta"));
    assertThat(
        fallback.getJsonArray("languages").stream()
            .map(JsonObject.class::cast)
            .filter(l -> l.getBoolean("published"))
            .map(l -> l.getString("code"))
            .toList(),
        is(List.of("en", "hi")));

    JsonObject again =
        data(
            staff(T3, "OWNER", "/customers/privacy/notices")
                .post(
                    json(
                        "{\"language\":\"en\",\"title\":\"How we use your data\",\"body\":\"Second wording\"}")),
            201);
    assertThat("a new version every time", again.getInt("version"), is(2));
    assertThat(
        dataArray(staff(T3, "CASHIER", "/customers/privacy/notices").get(), 200), hasSize(2));

    assertCode(
        publicGet(T3, "/customers/privacy/notice?language=fr"), 400, "PRIVACY_LANGUAGE_UNKNOWN");
    assertCode(
        staff(T3, "OWNER", "/customers/privacy/notices")
            .post(json("{\"language\":\"fr\",\"title\":\"t\",\"body\":\"b\"}")),
        400,
        "PRIVACY_LANGUAGE_UNKNOWN");
    assertCode(
        staff(T3, "OWNER", "/customers/privacy/notices")
            .post(json("{\"language\":\"en\",\"title\":\"\",\"body\":\"b\"}")),
        400,
        "PRIVACY_NOTICE_TEXT_INVALID");
    assertThat(
        staff(T3, "CASHIER", "/customers/privacy/notices")
            .post(json("{\"language\":\"en\",\"title\":\"t\",\"body\":\"b\"}"))
            .getStatus(),
        is(403));
    assertThat(
        "another business has no notice",
        absent(data(publicGet(T2, "/customers/privacy/notice"), 200), "notice"),
        is(true));
  }

  // ── consent per purpose ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Consent is per purpose, against the notice read, and withdrawn in one step")
  void consentPerPurpose() {
    publish(T, "en");
    publish(T, "hi");
    String login = Ids.newId().toString();
    claim(T, login, "asha@example.in");

    JsonObject mine = data(shopper(T, login, "/customers/me/privacy").get(), 200);
    assertThat(mine.getJsonArray("consents"), hasSize(4));
    assertThat(mine.getBoolean("child"), is(false));
    assertThat(mine.getBoolean("canTrack"), is(true));
    assertThat(granted(mine, "MARKETING"), is(false));

    JsonObject chosen =
        data(
            shopper(T, login, "/customers/me/privacy/consents")
                .put(
                    json(
                        "{\"choices\":[{\"purpose\":\"marketing\",\"granted\":true},{\"purpose\":\"LOYALTY\",\"granted\":true}],\"language\":\"hi\"}")),
            200);
    assertThat(granted(chosen, "MARKETING"), is(true));
    assertThat(granted(chosen, "LOYALTY"), is(true));
    assertThat(granted(chosen, "ANALYTICS"), is(false));
    JsonObject marketing = consent(chosen, "MARKETING");
    assertThat("against the Hindi notice read", marketing.getString("noticeLanguage"), is("hi"));
    assertThat(marketing.getInt("noticeVersion"), is(1));

    JsonObject withdrawn = data(shopper(T, login, "/customers/me/privacy/consents").delete(), 200);
    assertThat(granted(withdrawn, "MARKETING"), is(false));
    assertThat(granted(withdrawn, "LOYALTY"), is(false));

    String customerId = data(shopper(T, login, "/customers/me").get(), 200).getString("id");
    JsonArray log =
        dataArray(staff(T, "OWNER", "/customers/" + customerId + "/privacy/log").get(), 200);
    assertThat(log, hasSize(4));
    assertThat(log.getJsonObject(0).getString("source"), is("WITHDRAW_ALL"));
    assertThat(log.getJsonObject(3).getString("source"), is("PREFERENCE_CENTRE"));
    assertThat(log.getJsonObject(3).getBoolean("granted"), is(true));

    assertCode(
        shopper(T, login, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"SURVEILLANCE\",\"granted\":true}]}")),
        400,
        "PRIVACY_PURPOSE_UNKNOWN");
    assertThat(
        shopper(T, login, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[]}"))
            .getStatus(),
        is(400));
    // Over the counter, a staff member's act is recorded as theirs.
    JsonObject counter =
        data(
            staff(T, "CASHIER", "/customers/" + customerId + "/privacy/consents")
                .put(json("{\"choices\":[{\"purpose\":\"LOYALTY\",\"granted\":true}]}")),
            200);
    assertThat(granted(counter, "LOYALTY"), is(true));
    JsonArray after =
        dataArray(staff(T, "OWNER", "/customers/" + customerId + "/privacy/log").get(), 200);
    assertThat(after.getJsonObject(0).getString("source"), is("STAFF"));
    assertThat(after.getJsonObject(0).getString("actorId"), is(OWNER));
    assertThat(
        staff(T, "CASHIER", "/customers/" + customerId + "/privacy/log").get().getStatus(),
        is(403));
  }

  @Test
  @DisplayName("Where the Act binds, consent needs a published notice; elsewhere it does not")
  void noticeRequiredWhereTheActBinds() {
    String login = Ids.newId().toString();
    claim(T, login, "no-notice@example.in");
    Response r =
        shopper(T, login, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"ANALYTICS\",\"granted\":true}]}"));
    // The notice test may have published one already: then the grant stands; else it is refused.
    if (r.getStatus() == 409) {
      assertThat(code(r), is("PRIVACY_NOTICE_REQUIRED"));
    } else {
      assertThat(r.getStatus(), is(200));
    }
    // A withdrawal never needs a notice.
    assertThat(
        shopper(T, login, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"ANALYTICS\",\"granted\":false}]}"))
            .getStatus(),
        is(200));
    String british = Ids.newId().toString();
    claim(T2, british, "tom@example.co.uk");
    JsonObject gb =
        data(
            shopper(T2, british, "/customers/me/privacy/consents")
                .put(json("{\"choices\":[{\"purpose\":\"ANALYTICS\",\"granted\":true}]}")),
            200);
    assertThat(granted(gb, "ANALYTICS"), is(true));
    assertThat(absent(consent(gb, "ANALYTICS"), "noticeVersion"), is(true));
    assertThat(data(publicGet(T2, "/customers/privacy/notice"), 200).getBoolean("dpdp"), is(false));
  }

  // ── a child ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A child is not tracked without a guardian's consent, which can be withdrawn")
  void aChild() {
    publish(T, "en");
    String login = Ids.newId().toString();
    claim(T, login, "kid@example.in");
    String dob = LocalDate.now(ZoneOffset.UTC).minusYears(14).toString();
    data(
        shopper(T, login, "/customers/me")
            .put(json("{\"firstName\":\"Kavi\",\"lastName\":\"R\",\"dob\":\"" + dob + "\"}")),
        200);
    String customerId = data(shopper(T, login, "/customers/me").get(), 200).getString("id");

    JsonObject mine = data(shopper(T, login, "/customers/me/privacy").get(), 200);
    assertThat(mine.getBoolean("child"), is(true));
    assertThat(mine.getBoolean("canTrack"), is(false));
    assertCode(
        shopper(T, login, "/customers/me/privacy/consents")
            .put(json("{\"choices\":[{\"purpose\":\"MARKETING\",\"granted\":true}]}")),
        409,
        "PRIVACY_GUARDIAN_CONSENT_REQUIRED");
    assertThat(
        "loyalty tracks nobody",
        granted(
            data(
                shopper(T, login, "/customers/me/privacy/consents")
                    .put(json("{\"choices\":[{\"purpose\":\"LOYALTY\",\"granted\":true}]}")),
                200),
            "LOYALTY"),
        is(true));

    assertCode(
        staff(T, "MANAGER", "/customers/" + customerId + "/privacy/guardian")
            .post(
                json(
                    "{\"guardianName\":\"R. Kumar\",\"verification\":\"DOCUMENT_SEEN\",\"reference\":\"passport 12345678\"}")),
        400,
        "PRIVACY_REFERENCE_IS_A_NUMBER");
    assertCode(
        staff(T, "MANAGER", "/customers/" + customerId + "/privacy/guardian")
            .post(json("{\"guardianName\":\"R. Kumar\",\"verification\":\"HEARSAY\"}")),
        400,
        "PRIVACY_VERIFICATION_UNKNOWN");
    assertThat(
        staff(T, "CASHIER", "/customers/" + customerId + "/privacy/guardian")
            .post(json("{\"guardianName\":\"R. Kumar\",\"verification\":\"DOCUMENT_SEEN\"}"))
            .getStatus(),
        is(403));
    JsonObject guardian =
        data(
            staff(T, "MANAGER", "/customers/" + customerId + "/privacy/guardian")
                .post(
                    json(
                        "{\"guardianName\":\"R. Kumar\",\"verification\":\"DOCUMENT_SEEN\",\"reference\":\"passport seen over the counter\"}")),
            200);
    assertThat(guardian.getBoolean("standing"), is(true));
    JsonObject now = data(shopper(T, login, "/customers/me/privacy").get(), 200);
    assertThat(now.getBoolean("canTrack"), is(true));
    assertThat(now.getJsonObject("guardian").getString("guardianName"), is("R. Kumar"));
    assertThat(
        granted(
            data(
                shopper(T, login, "/customers/me/privacy/consents")
                    .put(json("{\"choices\":[{\"purpose\":\"MARKETING\",\"granted\":true}]}")),
                200),
            "MARKETING"),
        is(true));

    JsonObject withdrawn =
        data(staff(T, "OWNER", "/customers/" + customerId + "/privacy/guardian").delete(), 200);
    assertThat(withdrawn.getBoolean("standing"), is(false));
    JsonObject fallen = data(shopper(T, login, "/customers/me/privacy").get(), 200);
    assertThat("marketing fell with the guardian", granted(fallen, "MARKETING"), is(false));
    assertThat("loyalty stands", granted(fallen, "LOYALTY"), is(true));
    assertThat(fallen.getBoolean("canTrack"), is(false));
    JsonArray log =
        dataArray(staff(T, "OWNER", "/customers/" + customerId + "/privacy/log").get(), 200);
    assertThat(log.getJsonObject(0).getString("source"), is("GUARDIAN"));
    assertCode(
        staff(T, "OWNER", "/customers/" + customerId + "/privacy/guardian").delete(),
        404,
        "PRIVACY_GUARDIAN_CONSENT_NOT_FOUND");

    String adult = Ids.newId().toString();
    claim(T, adult, "grown@example.in");
    data(
        shopper(T, adult, "/customers/me")
            .put(json("{\"firstName\":\"Dev\",\"lastName\":\"S\",\"dob\":\"1990-01-01\"}")),
        200);
    String adultId = data(shopper(T, adult, "/customers/me").get(), 200).getString("id");
    assertCode(
        staff(T, "MANAGER", "/customers/" + adultId + "/privacy/guardian")
            .post(json("{\"guardianName\":\"Someone\",\"verification\":\"DETAILS_HELD\"}")),
        409,
        "PRIVACY_NOT_A_CHILD");
  }

  // ── requests ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Requests queue up, due within the published period, and staff answer them")
  void requests() {
    JsonObject settings =
        data(
            staff(T, "OWNER", "/customers/privacy/settings")
                .put(
                    json(
                        "{\"grievanceName\":\"Grievance Officer\",\"grievanceEmail\":\"privacy@example.in\",\"responseDays\":10}")),
            200);
    assertThat(settings.getInt("responseDays"), is(10));
    assertThat(settings.getBoolean("hasGrievanceContact"), is(true));
    assertCode(
        staff(T, "OWNER", "/customers/privacy/settings").put(json("{\"responseDays\":91}")),
        400,
        "PRIVACY_RESPONSE_DAYS_INVALID");
    assertCode(
        staff(T, "OWNER", "/customers/privacy/settings")
            .put(json("{\"grievanceEmail\":\"not an address\"}")),
        400,
        "PRIVACY_GRIEVANCE_CONTACT_INVALID");
    assertThat(
        staff(T, "CASHIER", "/customers/privacy/settings")
            .put(json("{\"responseDays\":5}"))
            .getStatus(),
        is(403));
    assertThat(
        data(publicGet(T, "/customers/privacy/notice"), 200)
            .getJsonObject("settings")
            .getString("grievanceEmail"),
        is("privacy@example.in"));

    String login = Ids.newId().toString();
    claim(T, login, "priya@example.in");
    JsonObject opened =
        data(
            shopper(T, login, "/customers/me/privacy/requests")
                .post(
                    json(
                        "{\"kind\":\"grievance\",\"detail\":\"You kept emailing after I withdrew.\"}")),
            201);
    assertThat(opened.getString("status"), is("OPEN"));
    assertThat(
        opened.getString("dueOn"), is(LocalDate.now(ZoneOffset.UTC).plusDays(10).toString()));
    assertThat(opened.getBoolean("overdue"), is(false));
    assertCode(
        shopper(T, login, "/customers/me/privacy/requests").post(json("{\"kind\":\"NOMINATION\"}")),
        400,
        "PRIVACY_NOMINEE_REQUIRED");
    assertCode(
        shopper(T, login, "/customers/me/privacy/requests").post(json("{\"kind\":\"REFUND\"}")),
        400,
        "PRIVACY_REQUEST_KIND_UNKNOWN");
    JsonObject nomination =
        data(
            shopper(T, login, "/customers/me/privacy/requests")
                .post(
                    json(
                        "{\"kind\":\"NOMINATION\",\"nomineeName\":\"Arun\",\"nomineeContact\":\"arun@example.in\"}")),
            201);
    assertThat(nomination.getString("nomineeName"), is("Arun"));
    assertThat(
        dataArray(shopper(T, login, "/customers/me/privacy/requests").get(), 200), hasSize(2));

    JsonArray queue =
        dataArray(staff(T, "CASHIER", "/customers/privacy/requests?status=open").get(), 200);
    assertThat(
        queue.stream().map(JsonObject.class::cast).map(r -> r.getString("id")).toList(),
        org.hamcrest.Matchers.hasItem(opened.getString("id")));
    assertCode(
        staff(T, "OWNER", "/customers/privacy/requests?status=LOST").get(),
        400,
        "PRIVACY_REQUEST_STATUS_UNKNOWN");
    String id = opened.getString("id");
    assertThat(
        staff(T, "CASHIER", "/customers/privacy/requests/" + id + "/resolve")
            .post(json("{\"status\":\"RESOLVED\",\"resolution\":\"Stopped.\"}"))
            .getStatus(),
        is(403));
    assertCode(
        staff(T, "OWNER", "/customers/privacy/requests/" + id + "/resolve")
            .post(json("{\"status\":\"PENDING\",\"resolution\":\"x\"}")),
        400,
        "PRIVACY_RESOLUTION_INVALID");
    JsonObject resolved =
        data(
            staff(T, "OWNER", "/customers/privacy/requests/" + id + "/resolve")
                .post(
                    json(
                        "{\"status\":\"RESOLVED\",\"resolution\":\"Stopped, and the consent log corrected.\"}")),
            200);
    assertThat(resolved.getString("status"), is("RESOLVED"));
    assertThat(resolved.getString("resolvedAt"), notNullValue());
    assertCode(
        staff(T, "OWNER", "/customers/privacy/requests/" + id + "/resolve")
            .post(json("{\"status\":\"REFUSED\",\"resolution\":\"again\"}")),
        409,
        "PRIVACY_REQUEST_SETTLED");
    assertCode(
        staff(T, "OWNER", "/customers/privacy/requests/" + Ids.newId() + "/resolve")
            .post(json("{\"status\":\"REFUSED\",\"resolution\":\"x\"}")),
        404,
        "PRIVACY_REQUEST_NOT_FOUND");
    assertCode(
        staff(T2, "OWNER", "/customers/privacy/requests/" + nomination.getString("id") + "/resolve")
            .post(json("{\"status\":\"REFUSED\",\"resolution\":\"x\"}")),
        404,
        "PRIVACY_REQUEST_NOT_FOUND");
    assertThat(
        "the shopper sees the answer",
        dataArray(shopper(T, login, "/customers/me/privacy/requests").get(), 200).stream()
            .map(JsonObject.class::cast)
            .filter(r -> r.getString("id").equals(id))
            .findFirst()
            .orElseThrow()
            .getString("resolution"),
        containsString("Stopped"));

    // Abuse: a shopper cannot flood the queue.
    for (int i = 0; i < 19; i++) {
      shopper(T, login, "/customers/me/privacy/requests").post(json("{\"kind\":\"ACCESS\"}"));
    }
    assertCode(
        shopper(T, login, "/customers/me/privacy/requests").post(json("{\"kind\":\"ACCESS\"}")),
        409,
        "PRIVACY_REQUESTS_OPEN_LIMIT");
  }

  // ── a breach ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A breach is told to every reachable customer, and what was sent is kept")
  void aBreach() {
    String a = create(T, "a-breach@example.in", "Ananya");
    String b = create(T, "b-breach@example.in", "Bala");
    NOTIFY.reset();
    NOTIFY_DOWN.set(false);
    JsonObject sent =
        data(
            staff(T, "OWNER", "/customers/privacy/breach-intimations")
                .post(
                    json(
                        "{\"subject\":\"Your data\",\"body\":\"On 15 September an attacker read names and emails. We have closed the hole; change any password you reused. Write to privacy@example.in.\",\"customerIds\":[\""
                            + a
                            + "\",\""
                            + b
                            + "\"]}")),
            201);
    assertThat(sent.getInt("recipients"), is(2));
    assertThat(sent.getInt("failures"), is(0));
    List<JsonStub.Call> calls =
        NOTIFY.calls().stream().filter(c -> c.path().equals("/notifications/send")).toList();
    assertThat(calls, hasSize(2));
    assertThat(calls.get(0).body(), containsString("\"channel\":\"EMAIL\""));
    assertThat(calls.get(0).body(), containsString("DATA_BREACH_INTIMATION"));
    assertThat(calls.get(0).tenantId(), is(T));
    assertThat(
        dataArray(staff(T, "MANAGER", "/customers/privacy/breach-intimations").get(), 200).size(),
        greaterThanOrEqualTo(1));

    NOTIFY_DOWN.set(true);
    JsonObject failed =
        data(
            staff(T, "OWNER", "/customers/privacy/breach-intimations")
                .post(
                    json(
                        "{\"subject\":\"Your data\",\"body\":\"Again.\",\"customerIds\":[\""
                            + a
                            + "\"]}")),
            201);
    assertThat("a failure is counted, never hidden", failed.getInt("failures"), is(1));
    NOTIFY_DOWN.set(false);

    assertCode(
        staff(T, "OWNER", "/customers/privacy/breach-intimations")
            .post(json("{\"subject\":\"\",\"body\":\"x\"}")),
        400,
        "PRIVACY_INTIMATION_TEXT_INVALID");
    assertCode(
        staff(T, "OWNER", "/customers/privacy/breach-intimations")
            .post(
                json(
                    "{\"subject\":\"s\",\"body\":\"b\",\"customerIds\":[\""
                        + Ids.newId()
                        + "\"]}")),
        409,
        "PRIVACY_NOBODY_TO_TELL");
    assertThat(
        staff(T, "CASHIER", "/customers/privacy/breach-intimations")
            .post(json("{\"subject\":\"s\",\"body\":\"b\"}"))
            .getStatus(),
        is(403));
    assertCode(
        staff(T2, "OWNER", "/customers/privacy/breach-intimations")
            .post(json("{\"subject\":\"s\",\"body\":\"b\",\"customerIds\":[\"" + a + "\"]}")),
        409,
        "PRIVACY_NOBODY_TO_TELL");
  }

  // ── what is refused ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("A guardian with no name is refused and none is recorded")
  void aGuardianWithNoNameIsRefused() {
    publish(T, "en");
    String login = Ids.newId().toString();
    claim(T, login, "nameless-guardian@example.in");
    String dob = LocalDate.now(ZoneOffset.UTC).minusYears(12).toString();
    data(
        shopper(T, login, "/customers/me")
            .put(json("{\"firstName\":\"Mani\",\"lastName\":\"R\",\"dob\":\"" + dob + "\"}")),
        200);
    String customerId = data(shopper(T, login, "/customers/me").get(), 200).getString("id");

    for (String blank : new String[] {"   ", "\\t", ""}) {
      assertCode(
          staff(T, "MANAGER", "/customers/" + customerId + "/privacy/guardian")
              .post(
                  json("{\"guardianName\":\"" + blank + "\",\"verification\":\"DOCUMENT_SEEN\"}")),
          400,
          "PRIVACY_GUARDIAN_NAME_INVALID");
    }
    JsonObject view = data(shopper(T, login, "/customers/me/privacy").get(), 200);
    assertThat("no guardian was recorded", absent(view, "guardian"), is(true));
    assertThat("the child is still not tracked", view.getBoolean("canTrack"), is(false));
  }

  @Test
  @DisplayName("A breach naming more than five hundred customers is refused and nothing is sent")
  void aBreachNamingMoreThanFiveHundredIsRefused() {
    String reachable = create(T, "reachable-breach@example.in", "Rani");
    int before =
        dataArray(staff(T, "OWNER", "/customers/privacy/breach-intimations").get(), 200).size();
    NOTIFY.reset();
    NOTIFY_DOWN.set(false);

    StringBuilder ids = new StringBuilder("\"" + reachable + "\"");
    for (int i = 0; i < 500; i++) ids.append(",\"").append(Ids.newId()).append('"');
    assertCode(
        staff(T, "OWNER", "/customers/privacy/breach-intimations")
            .post(json("{\"subject\":\"s\",\"body\":\"b\",\"customerIds\":[" + ids + "]}")),
        400,
        "PRIVACY_INTIMATION_TOO_MANY_NAMED");

    assertThat(
        "nothing was sent",
        NOTIFY.calls().stream().filter(c -> c.path().equals("/notifications/send")).count(),
        is(0L));
    assertThat(
        "no intimation was written",
        dataArray(staff(T, "OWNER", "/customers/privacy/breach-intimations").get(), 200).size(),
        is(before));
  }

  @Test
  @DisplayName("Eight first notices at once are each published or told to retry, never lost")
  void twoFirstNoticesAtOnceAreOneVersionOrBusy() throws Exception {
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(callers);
    List<Integer> statuses = new ArrayList<>();
    List<String> bodies = new ArrayList<>();
    try {
      List<Future<String[]>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  Response r =
                      staff(T4, "OWNER", "/customers/privacy/notices")
                          .post(json("{\"language\":\"ta\",\"title\":\"t\",\"body\":\"b\"}"));
                  String body = r.readEntity(String.class);
                  return new String[] {String.valueOf(r.getStatus()), body};
                }));
      }
      start.countDown();
      for (Future<String[]> f : results) {
        String[] answer = f.get(60, TimeUnit.SECONDS);
        statuses.add(Integer.parseInt(answer[0]));
        bodies.add(answer[1]);
      }
    } finally {
      pool.shutdownNow();
    }
    long published = 0;
    for (int i = 0; i < callers; i++) {
      int status = statuses.get(i);
      if (status == 201) {
        published++;
      } else {
        assertThat(bodies.get(i), status, is(409));
        assertThat(bodies.get(i), containsString("PRIVACY_NOTICE_BUSY"));
      }
    }
    assertThat(statuses.toString(), published >= 1, is(true));

    // The list answers only the current version of a language, so the rows are read back.
    List<Integer> versions = noticeVersions(T4, "ta");
    List<Integer> expected = new ArrayList<>();
    for (int v = 1; v <= published; v++) expected.add(v);
    assertThat("one row per answer of 201, contiguous, no duplicate", versions, is(expected));
  }

  @Test
  @DisplayName("Withdrawing every consent needs a record here and a login, and writes nothing")
  void withdrawingEveryConsentWithoutARecordIsRefused() throws Exception {
    String login = Ids.newId().toString();
    claim(T, login, "withdraw-owner@example.in");
    long loggedT = consentLogRows(T);
    long loggedT2 = consentLogRows(T2);

    assertCode(
        shopper(T, Ids.newId().toString(), "/customers/me/privacy/consents").delete(),
        404,
        "CUSTOMER_NOT_FOUND");
    assertCode(
        shopper(T2, login, "/customers/me/privacy/consents").delete(), 404, "CUSTOMER_NOT_FOUND");
    assertCode(
        WebTargets.at(target, "/customers/me/privacy/consents")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .delete(),
        401,
        "NO_USER");

    assertThat("nothing was logged in this shop", consentLogRows(T), is(loggedT));
    assertThat("nothing was logged in the rival shop", consentLogRows(T2), is(loggedT2));
  }

  private List<Integer> noticeVersions(String tenant, String language) throws Exception {
    List<Integer> versions = new ArrayList<>();
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "SELECT version FROM customer.privacy_notices"
                    + " WHERE tenant_id = ? AND language = ? ORDER BY version")) {
      ps.setObject(1, Ids.parse(tenant));
      ps.setString(2, language);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) versions.add(rs.getInt(1));
      }
    }
    return versions;
  }

  private long consentLogRows(String tenant) throws Exception {
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM customer.purpose_consent_log WHERE tenant_id = ?")) {
      ps.setObject(1, Ids.parse(tenant));
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private void publish(String tenant, String language) {
    Response r =
        staff(tenant, "OWNER", "/customers/privacy/notices")
            .post(
                json(
                    "{\"language\":\""
                        + language
                        + "\",\"title\":\"Notice\",\"body\":\"What we do with your data.\"}"));
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));
  }

  private void claim(String tenant, String login, String email) {
    Response r = shopper(tenant, login, email, "/customers/me").post(json("{}"));
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
  }

  private String create(String tenant, String email, String name) {
    return data(
            staff(tenant, "OWNER", "/customers")
                .post(
                    json(
                        "{\"email\":\""
                            + email
                            + "\",\"firstName\":\""
                            + name
                            + "\",\"lastName\":\"K\"}")),
            201)
        .getString("id");
  }

  private Invocation.Builder shopper(String tenant, String login, String path) {
    return shopper(tenant, login, login.substring(0, 8) + "@example.in", path);
  }

  private Invocation.Builder shopper(String tenant, String login, String email, String path) {
    return WebTargets.at(target, path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "CUSTOMER")
        .header("X-User-Id", login)
        .header("X-User-Email", email);
  }

  private Invocation.Builder staff(String tenant, String role, String path) {
    return WebTargets.at(target, path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", role)
        .header("X-User-Id", OWNER);
  }

  private Response publicGet(String tenant, String path) {
    return WebTargets.at(target, path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .get();
  }

  private static Entity<String> json(String body) {
    return Entity.entity(body, MediaType.APPLICATION_JSON);
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private static JsonArray dataArray(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonArray("data");
    }
  }

  private static String code(Response r) {
    String body = r.readEntity(String.class);
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("error").getString("code");
    }
  }

  private static void assertCode(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, body, containsString("\"code\":\"" + code + "\""));
  }

  /** JSON-B leaves a null field out, so absent and null are the same answer. */
  private static boolean absent(JsonObject o, String field) {
    return !o.containsKey(field) || o.isNull(field);
  }

  private static JsonObject consent(JsonObject view, String purpose) {
    return view.getJsonArray("consents").stream()
        .map(JsonObject.class::cast)
        .filter(c -> c.getString("purpose").equals(purpose))
        .findFirst()
        .orElseThrow();
  }

  private static boolean granted(JsonObject view, String purpose) {
    return consent(view, purpose).getBoolean("granted");
  }
}
