package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Statutory reporting by jurisdiction (07.14), over HTTP and a real database.
 *
 * <p>Two assertions need a database and cannot be had from a unit test.
 *
 * <p><b>Which returns reach a business is answered by the membership table, of the period.</b> A
 * Portuguese business owes the EU's recapitulative statement; a British one owes none of it for any
 * period in this decade, because the United Kingdom stopped being a member on 31 January 2020 and
 * {@code jurisdiction_members} records exactly that. Asking "is it a member now?" would be a
 * different and wrong question — it would also drop the quarters a leaving member still owed.
 *
 * <p><b>Exactly one filing stands per period.</b> That is a partial unique index keyed on {@code
 * superseded_by IS NULL}, and a correction marking its predecessor and inserting itself is one
 * transaction. Both halves are database facts: in memory the second insert simply succeeds.
 */
@HelidonTest
class StatutoryReturnsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  private static final String SR = "/admin/tenant/statutory-returns";

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

  /** A query string is split off: {@link WebTarget#path(String)} percent-encodes a {@code ?}. */
  private Answer call(String method, String path, String json, String tenant, String roles) {
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
    Invocation.Builder b = t.request(MediaType.APPLICATION_JSON).header("X-User-Id", Ids.newId());
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
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

  private Answer owner(String method, String path, String json, String tenantId) {
    return call(method, path, json, tenantId, "OWNER");
  }

  private String business(String country, String currency) {
    return TenantOnboarding.onboard(target, "statutory", country, currency);
  }

  /** The first day of the month {@code n} months before this one. */
  private static LocalDate monthsBack(int n) {
    return LocalDate.now().withDayOfMonth(1).minusMonths(n);
  }

  private static List<String> codes(Answer calendar) {
    return calendar.data().getJsonArray("obligations").getValuesAs(JsonObject.class).stream()
        .map(o -> o.getString("returnCode"))
        .distinct()
        .sorted()
        .toList();
  }

  private static JsonObject period(Answer calendar, String code, LocalDate periodStart) {
    return calendar.data().getJsonArray("obligations").getValuesAs(JsonObject.class).stream()
        .filter(o -> code.equals(o.getString("returnCode")))
        .filter(o -> periodStart.toString().equals(o.getString("periodStart")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + code + " period beginning " + periodStart));
  }

  // ── which returns reach whom, asked of the membership table ────────────────

  @Test
  @DisplayName(
      "A Portuguese business owes its country's return and the EU's; a British one does not")
  void membershipDecidesTheCalendar() {
    Answer pt = owner("GET", SR, null, business("PT", "EUR"));
    assertThat(pt.text(), pt.status(), is(200));
    assertThat(codes(pt), hasItem("SAFT_PT"));
    assertThat(
        "the recapitulative statement reaches a member", codes(pt), hasItem("EC_SALES_LIST"));
    assertThat("and no other country's return", codes(pt), not(hasItem("VAT_RETURN_UK")));

    Answer gb = owner("GET", SR, null, business("GB", "GBP"));
    assertThat(codes(gb), hasItem("VAT_RETURN_UK"));
    // The point of the test. The United Kingdom's membership row ends 31 January 2020, so for every
    // period on this calendar the answer is no — and it is the period's own dates that are asked.
    assertThat(
        "a former member owes no recapitulative statement for periods after it left",
        codes(gb),
        not(hasItem("EC_SALES_LIST")));
  }

  @Test
  @DisplayName("Each return carries its instrument and where its export lives, and never the bytes")
  void eachReturnSaysWhereItsExportLives() {
    Answer pt = owner("GET", SR, null, business("PT", "EUR"));
    JsonObject saft = period(pt, "SAFT_PT", monthsBack(1));

    assertThat(saft.getString("citation"), is(not("")));
    // A link, not a proxy: the SAF-T is order-svc's to serve, and serving it from here would be one
    // service reading another's data.
    assertThat(saft.getString("exportService"), is("order-svc"));
    assertThat(saft.getString("exportPath"), is(not("")));

    JsonObject statement = period(pt, "EC_SALES_LIST", monthsBack(1));
    assertThat(
        "and a return the platform cannot produce says so rather than offering an empty export",
        statement.get("exportService"),
        is(nullValue()));
  }

  @Test
  @DisplayName("Every seeded export names a route its own service serves, spelled as it serves it")
  void everySeededExportIsARealRoute() throws SQLException {
    // The link is service + path, and the path is the one the owning service itself serves, not a
    // gateway path and not a guess: a route that is not there 404s quietly. Each is read off the
    // resource that serves it:
    //   order-svc   FiscalReceiptResource  @Path("/admin") + GET /fiscal-receipts/export?format=
    //                                       dsfinvk | saft-pt
    //   order-svc   EReportingResource      @Path("/admin/ereporting") + GET and POST /submissions
    //   pricing-svc VatReturnResource       @Path("/vat-return"), GET, and not under /admin/
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("DSFINVK_DE", "order-svc /admin/fiscal-receipts/export?format=dsfinvk");
    expected.put("EC_SALES_LIST", "none");
    expected.put("EREPORTING_PAY_FR", "order-svc /admin/ereporting/submissions");
    expected.put("EREPORTING_TX_FR", "order-svc /admin/ereporting/submissions");
    expected.put("SAFT_PT", "order-svc /admin/fiscal-receipts/export?format=saft-pt");
    expected.put("VAT_RETURN_UK", "pricing-svc /vat-return");

    Map<String, String> seeded = new LinkedHashMap<>();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT code, export_service, export_path FROM tenant.statutory_returns"
                    + " ORDER BY code")) {
      while (rs.next()) {
        String service = rs.getString("export_service");
        seeded.put(
            rs.getString("code"),
            service == null ? "none" : service + " " + rs.getString("export_path"));
      }
    }
    assertThat(seeded, is(expected));
  }

  @Test
  @DisplayName(
      "The due date is the day the instrument names, not the day the arithmetic is easiest")
  void theDueDateIsTheInstrumentsDate() {
    Answer pt = owner("GET", SR, null, business("PT", "EUR"));
    LocalDate last = monthsBack(1);
    JsonObject saft = period(pt, "SAFT_PT", last);

    // Portugal: by the 5th of the following month. The seeded offset is P4D from the exclusive end,
    // which is the same day — and a reading of it as "five days after the last day" would be the
    // 4th.
    assertThat(saft.getString("periodEnd"), is(last.plusMonths(1).toString()));
    assertThat(saft.getString("dueOn"), is(last.plusMonths(1).plusDays(4).toString()));
    assertThat(LocalDate.parse(saft.getString("dueOn")).getDayOfMonth(), is(5));

    // The EU statement, by the 20th, for the same month.
    assertThat(
        LocalDate.parse(period(pt, "EC_SALES_LIST", last).getString("dueOn")).getDayOfMonth(),
        is(20));
  }

  @Test
  @DisplayName("Asked as of different days, the same period moves through its states on its own")
  void theStateIsDerivedOnEveryRead() {
    String tenantId = business("PT", "EUR");
    LocalDate last = monthsBack(1);
    LocalDate end = last.plusMonths(1);
    LocalDate due = end.plusDays(4);

    assertThat(
        "inside the period, nothing is owed for it",
        period(owner("GET", SR + "?asOf=" + last.plusDays(10), null, tenantId), "SAFT_PT", last)
            .getString("state"),
        is("NOT_DUE"));
    assertThat(
        "the day the period ends, it is owed",
        period(owner("GET", SR + "?asOf=" + end, null, tenantId), "SAFT_PT", last)
            .getString("state"),
        is("DUE"));
    assertThat(
        "on the day it falls due it is still due — filing on the day is filing on time",
        period(owner("GET", SR + "?asOf=" + due, null, tenantId), "SAFT_PT", last)
            .getString("state"),
        is("DUE"));
    assertThat(
        period(owner("GET", SR + "?asOf=" + due.plusDays(1), null, tenantId), "SAFT_PT", last)
            .getString("state"),
        is("OVERDUE"));
    assertThat(
        "and a date that is not a date is refused rather than read as today",
        owner("GET", SR + "?asOf=last-tuesday", null, tenantId).code(),
        is("STATUTORY_DATE_INVALID"));
  }

  @Test
  @DisplayName("Outstanding is the same calendar, oldest first, and only what needs acting on")
  void outstandingIsWhatToActOn() {
    Answer pt = owner("GET", SR, null, business("PT", "EUR"));
    List<JsonObject> outstanding =
        pt.data().getJsonArray("outstanding").getValuesAs(JsonObject.class);

    assertThat(outstanding.size(), is(not(0)));
    String previous = null;
    for (JsonObject o : outstanding) {
      assertThat(
          "only what needs acting on",
          List.of("DUE", "OVERDUE").contains(o.getString("state")),
          is(true));
      if (previous != null) {
        assertThat(
            "oldest first, which is the order to act in",
            o.getString("dueOn").compareTo(previous) >= 0,
            is(true));
      }
      previous = o.getString("dueOn");
    }
  }

  // ── filing, and the one filing that stands ─────────────────────────────────

  @Test
  @DisplayName("A filing is recorded, and filing the same period again needs a correction")
  void aPeriodIsFiledOnce() {
    String tenantId = business("PT", "EUR");
    LocalDate last = monthsBack(1);
    String body =
        "{\"periodStart\":\"" + last + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-1\"}";

    Answer filed = owner("POST", SR + "/SAFT_PT/filings", body, tenantId);
    assertThat(filed.text(), filed.status(), is(200));
    assertThat(filed.data().getString("state"), is("FILED"));
    assertThat(filed.data().getJsonObject("filing").getString("reference"), is("AT-1"));
    assertThat(filed.data().getJsonObject("filing").getBoolean("stands"), is(true));

    // The unique index, through the service's own check: one standing filing per period.
    Answer again = owner("POST", SR + "/SAFT_PT/filings", body, tenantId);
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("STATUTORY_FILING_EXISTS"));

    assertThat(
        "and the calendar reads it as filed whichever day it is asked about",
        period(
                owner("GET", SR + "?asOf=" + LocalDate.now().plusYears(1), null, tenantId),
                "SAFT_PT",
                last)
            .getString("state"),
        is("FILED"));
  }

  @Test
  @DisplayName("A correction names what it replaces; both stay on the record and one stands")
  void aCorrectionSupersedesItsPredecessor() {
    String tenantId = business("PT", "EUR");
    LocalDate last = monthsBack(1);
    Answer first =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\"" + last + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-1\"}",
            tenantId);
    String standing = first.data().getJsonObject("filing").getString("id");

    Answer wrongOne =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\""
                + last
                + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-x\","
                + "\"supersedes\":\""
                + Ids.newId()
                + "\"}",
            tenantId);
    assertThat(wrongOne.status(), is(409));
    assertThat(wrongOne.code(), is("STATUTORY_FILING_NOT_STANDING"));

    Answer corrected =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\""
                + last
                + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-2\","
                + "\"supersedes\":\""
                + standing
                + "\",\"note\":\"figures restated\"}",
            tenantId);
    assertThat(corrected.text(), corrected.status(), is(200));
    assertThat(corrected.data().getString("state"), is("FILED"));
    assertThat(
        "the correction is what stands now",
        corrected.data().getJsonObject("filing").getString("reference"),
        is("AT-2"));

    List<JsonObject> history =
        owner("GET", SR + "/filings", null, tenantId).list().stream()
            .filter(f -> last.toString().equals(f.getString("periodStart")))
            .toList();
    assertThat("both are on the record", history, hasSize(2));
    assertThat(
        "and exactly one of them stands",
        history.stream()
            .filter(f -> f.getBoolean("stands"))
            .map(f -> f.getString("reference"))
            .toList(),
        contains("AT-2"));
    assertThat(
        "the correction says what it replaced",
        history.stream()
            .filter(f -> "AT-2".equals(f.getString("reference")))
            .findFirst()
            .orElseThrow()
            .getString("supersedes"),
        is(standing));

    // And the one it replaced cannot be corrected a second time, which is what keeps the chain a
    // chain rather than a fan.
    Answer twice =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\""
                + last
                + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-3\","
                + "\"supersedes\":\""
                + standing
                + "\"}",
            tenantId);
    assertThat(twice.status(), is(409));
    assertThat(twice.code(), is("STATUTORY_FILING_NOT_STANDING"));
  }

  @Test
  @DisplayName("Ten corrections of one standing filing at once: one stands, the rest are refused")
  void twoCorrectionsAtOnceStandOnce() throws Exception {
    String tenantId = business("PT", "EUR");
    LocalDate last = monthsBack(1);
    Answer first =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\"" + last + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-1\"}",
            tenantId);
    assertThat(first.text(), first.status(), is(200));
    String standing = first.data().getJsonObject("filing").getString("id");

    int n = 10;
    var pool = Executors.newFixedThreadPool(n);
    var go = new CountDownLatch(1);
    List<Future<Answer>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String reference = "AT-C" + i;
      results.add(
          pool.submit(
              () -> {
                go.await();
                return owner(
                    "POST",
                    SR + "/SAFT_PT/filings",
                    "{\"periodStart\":\""
                        + last
                        + "\",\"provider\":\"MANUAL\",\"reference\":\""
                        + reference
                        + "\",\"supersedes\":\""
                        + standing
                        + "\"}",
                    tenantId);
              }));
    }
    go.countDown();
    int accepted = 0;
    for (Future<Answer> f : results) {
      Answer a = f.get();
      if (a.status() == 200) {
        accepted++;
      } else {
        assertThat(a.text(), a.status(), is(409));
        assertThat(
            a.text(),
            a.code(),
            org.hamcrest.Matchers.either(is("STATUTORY_FILING_ALREADY_CORRECTED"))
                .or(is("STATUTORY_FILING_NOT_STANDING")));
      }
    }
    pool.shutdown();
    assertThat("one correction wins", accepted, is(1));

    List<JsonObject> history =
        owner("GET", SR + "/filings", null, tenantId).list().stream()
            .filter(f -> last.toString().equals(f.getString("periodStart")))
            .toList();
    assertThat("the first and the one correction are on the record", history, hasSize(2));
    assertThat(
        "and exactly one of them stands",
        history.stream().filter(f -> f.getBoolean("stands")).count(),
        is(1L));
  }

  // ── what filing refuses ────────────────────────────────────────────────────

  @Test
  @DisplayName("Nothing is filed for a period that has not ended, or for a period that is not one")
  void filingRefusesWhatCannotBeTrue() {
    String tenantId = business("PT", "EUR");

    Answer running =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\"" + monthsBack(0) + "\",\"provider\":\"MANUAL\"}",
            tenantId);
    assertThat(running.status(), is(409));
    assertThat(running.code(), is("STATUTORY_PERIOD_NOT_ENDED"));

    Answer midMonth =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\"" + monthsBack(1).plusDays(13) + "\",\"provider\":\"MANUAL\"}",
            tenantId);
    assertThat(midMonth.status(), is(400));
    assertThat(midMonth.code(), is("STATUTORY_PERIOD_NOT_A_PERIOD"));

    Answer unknownProvider =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\"" + monthsBack(1) + "\",\"provider\":\"PIGEON\"}",
            tenantId);
    assertThat(unknownProvider.status(), is(400));
    assertThat(unknownProvider.code(), is("STATUTORY_PROVIDER_UNKNOWN"));

    Answer unknownReturn =
        owner(
            "POST",
            SR + "/NOT_A_RETURN/filings",
            "{\"periodStart\":\"" + monthsBack(1) + "\",\"provider\":\"MANUAL\"}",
            tenantId);
    assertThat(unknownReturn.status(), is(404));
    assertThat(unknownReturn.code(), is("STATUTORY_RETURN_UNKNOWN"));

    Answer noPeriod = owner("POST", SR + "/SAFT_PT/filings", "{\"provider\":\"MANUAL\"}", tenantId);
    assertThat("and a filing with no period at all is a bad request", noPeriod.status(), is(400));
  }

  // ── whose returns these are ────────────────────────────────────────────────

  @Test
  @DisplayName("A statutory filing is management's, and one business never sees another's")
  void whoseReturnsTheseAre() {
    String mine = business("PT", "EUR");
    LocalDate last = monthsBack(1);
    Answer filed =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\"" + last + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-mine\"}",
            mine);
    String standing = filed.data().getJsonObject("filing").getString("id");

    assertThat(
        "a cashier does not read what the business owes an authority",
        call("GET", SR, null, mine, "CASHIER").status(),
        is(403));
    assertThat(
        "nor state to one on its behalf",
        call(
                "POST",
                SR + "/SAFT_PT/filings",
                "{\"periodStart\":\"" + last + "\"," + "\"provider\":\"MANUAL\"}",
                mine,
                "CASHIER")
            .status(),
        is(403));
    // 403 and not 401: there is no gateway in front of an IT, so the request arrives with no roles
    // rather than with no token, and the filter refuses it for what it is. Through the gateway the
    // same call is a 401, which is what the k6 suite asserts.
    assertThat(
        "nor does anybody who is nobody", call("GET", SR, null, null, null).status(), is(403));

    String other = business("PT", "EUR");
    assertThat(
        "another business reads its own filings, which are none",
        owner("GET", SR + "/filings", null, other).list(),
        hasSize(0));
    // And it cannot reach into this one's by naming a filing id it has somehow learned: the
    // standing
    // filing is looked up under the caller's own tenant, so there is nothing there to correct.
    Answer reachIn =
        owner(
            "POST",
            SR + "/SAFT_PT/filings",
            "{\"periodStart\":\""
                + last
                + "\",\"provider\":\"MANUAL\",\"reference\":\"AT-theirs\","
                + "\"supersedes\":\""
                + standing
                + "\"}",
            other);
    assertThat(reachIn.status(), is(409));
    assertThat(reachIn.code(), is("STATUTORY_FILING_NOT_STANDING"));
    assertThat(
        "and this business's filing still stands, untouched",
        owner("GET", SR + "/filings", null, mine).list().stream()
            .filter(f -> f.getBoolean("stands"))
            .map(f -> f.getString("reference"))
            .toList(),
        contains("AT-mine"));
  }
}
