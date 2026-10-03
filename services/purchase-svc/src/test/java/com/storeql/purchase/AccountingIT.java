package com.storeql.purchase;

import static com.storeql.test.Envelopes.parse;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Accounting connectors (17.9): a business connects the accounting package it keeps its books in —
 * Xero, QuickBooks Online, Sage Business Cloud, or the platform's own stand-in — maps its nominal
 * codes to the package's accounts, and every journal the ledger posts from then on is pushed to the
 * package once, as that package's journal, with what came back kept on a log. A journal the package
 * refuses waits with the reason and is tried again, by the clock or by hand; the connection is read
 * by management, written by the owner alone, and unseen by another business.
 *
 * <p>Driven here against the SIMULATED package, which delivers in-process and refuses one account
 * it is told not to know; the three real packages are proved against stubs of their APIs in {@code
 * client.accounting}.
 */
@HelidonTest
@AddConfig(key = "storeql.accounting.enabled", value = "false")
@AddConfig(
    key = "storeql.accounting.secrets-key",
    value = "vyZCf8bWkchYrFCuJiHWqwh5mTSEykU+wRPHbL7nkok=")
class AccountingIT {
  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    TenantSvcStub.start().with(AccountingIT.T, "GBP", "GB").with(AccountingIT.T2, "GBP", "GB");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a090ae-611e-7037-a4b7-c854f0266ace";
  private static final String OWNER = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String MANAGER = "01a090ae-611e-700b-bde4-50df0324c37d";
  private static final String CASHIER = "01a090ae-611e-700b-bde4-50df0324c37e";
  private static final String ACCOUNTING = "/accounting";
  private static final String CONNECTION = ACCOUNTING + "/connection";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE purchase.accounting_sync_attempts, purchase.accounting_syncs,"
              + " purchase.accounting_account_mappings, purchase.accounting_connections,"
              + " purchase.nominal_ledger_entries, purchase.outbox CASCADE");
    }
  }

  // ── the catalogue and a connection ──────────────────────────────────────────

  @Test
  @DisplayName(
      "Management reads what can be connected; the owner connects the package, and what is kept"
          + " never includes a token")
  void theOwnerConnectsAPackage() {
    JsonArray providers =
        array(call("GET", ACCOUNTING + "/providers", null, T, "MANAGER", MANAGER), 200);
    assertThat(providers.size(), is(4));
    JsonObject xero = com.storeql.test.Envelopes.find(providers, "code", "XERO");
    assertThat(xero.getJsonArray("settings").getString(0), is("tenantId"));
    assertThat(xero.getString("name"), is("Xero"));
    assertThat(
        call("GET", ACCOUNTING + "/providers", null, T, "CASHIER", CASHIER).getStatus(), is(403));

    assertError(call("GET", CONNECTION, null, T, "OWNER", OWNER), 404, "ACCOUNTING_NOT_CONNECTED");
    assertThat(
        call("PUT", CONNECTION, simulated(null), T, "MANAGER", MANAGER).getStatus(), is(403));
    assertError(
        call(
            "PUT",
            CONNECTION,
            "{\"provider\":\"NETSUITE\",\"syncFrom\":\"2026-01-01\"}",
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_PROVIDER_UNKNOWN");
    assertError(
        call(
            "PUT",
            CONNECTION,
            "{\"provider\":\"XERO\",\"settings\":{},\"credentials\":{\"accessToken\":\"t\"},\"syncFrom\":\"2026-01-01\"}",
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_SETTINGS_INVALID");
    // A QuickBooks realm id is the company's digits: one no address can be made from is refused
    // here, never claimed by the clock and left there.
    assertError(
        call(
            "PUT",
            CONNECTION,
            "{\"provider\":\"QUICKBOOKS\",\"settings\":{\"realmId\":\"91 30%\"},\"credentials\":{\"accessToken\":\"t\"},\"syncFrom\":\"2026-01-01\"}",
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_SETTINGS_INVALID");
    assertError(call("GET", CONNECTION, null, T, "OWNER", OWNER), 404, "ACCOUNTING_NOT_CONNECTED");
    assertError(
        call(
            "PUT",
            CONNECTION,
            "{\"provider\":\"XERO\",\"settings\":{\"tenantId\":\"x\"},\"syncFrom\":\"2026-01-01\"}",
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_CREDENTIALS_MISSING");
    // A token expiry that is not an instant is refused by name, and nothing is kept.
    assertError(
        call(
            "PUT",
            CONNECTION,
            xeroJson().replace("2026-09-23T10:00:00Z", "tomorrow"),
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_CREDENTIALS_INVALID");
    assertError(call("GET", CONNECTION, null, T, "OWNER", OWNER), 404, "ACCOUNTING_NOT_CONNECTED");
    assertError(
        call(
            "PUT",
            CONNECTION,
            "{\"provider\":\"SIMULATED\",\"settings\":{},\"syncFrom\":\"not-a-day\"}",
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_SYNC_FROM_INVALID");

    Response made = call("PUT", CONNECTION, xeroJson(), T, "OWNER", OWNER);
    JsonObject c = object(made, 200);
    assertThat(c.getString("provider"), is("XERO"));
    assertThat(c.getString("status"), is("ACTIVE"));
    assertThat(c.getJsonObject("settings").getString("tenantId"), is("xero-org-1"));
    assertThat(c.getString("syncFrom"), is("2026-01-01"));
    assertThat(c.containsKey("credentials"), is(false));
    assertThat(c.getBoolean("hasRefreshToken"), is(true));
    JsonObject read = object(call("GET", CONNECTION, null, T, "MANAGER", MANAGER), 200);
    assertThat(read.getString("id"), is(c.getString("id")));
    assertThat(read.getJsonObject("counts").getInt("pending"), is(0));
    // Sealed, and never the token itself.
    String stored =
        com.storeql.test.Envelopes.scalar(
            PG,
            "SELECT credentials_sealed FROM purchase.accounting_connections WHERE id = '"
                + c.getString("id")
                + "'");
    assertThat(stored, not(containsString("xero-access")));
    assertThat(stored, not(nullValue()));
    // Another business sees nothing.
    assertError(call("GET", CONNECTION, null, T2, "OWNER", OWNER), 404, "ACCOUNTING_NOT_CONNECTED");

    // Replaced by the owner: one package per business.
    JsonObject replaced = object(call("PUT", CONNECTION, simulated(null), T, "OWNER", OWNER), 200);
    assertThat(replaced.getString("provider"), is("SIMULATED"));
    assertThat(replaced.getString("id"), not(is(c.getString("id"))));
    // Removed by the owner alone.
    assertThat(call("DELETE", CONNECTION, null, T, "MANAGER", MANAGER).getStatus(), is(403));
    assertThat(call("DELETE", CONNECTION, null, T, "OWNER", OWNER).getStatus(), is(200));
    assertError(call("GET", CONNECTION, null, T, "OWNER", OWNER), 404, "ACCOUNTING_NOT_CONNECTED");
  }

  // ── the package's chart, and the mapping onto it ────────────────────────────

  @Test
  @DisplayName("The package's chart of accounts is read, and the business's codes mapped onto it")
  void theChartAndTheMapping() {
    object(call("PUT", CONNECTION, simulated(null), T, "OWNER", OWNER), 200);
    JsonArray chart =
        array(call("GET", CONNECTION + "/accounts", null, T, "MANAGER", MANAGER), 200);
    assertThat(chart.size(), greaterThan(5));
    JsonObject bank = com.storeql.test.Envelopes.find(chart, "code", "1200");
    assertThat(bank.getString("name"), is("Bank"));
    assertThat(bank.getString("id"), is("SIM-1200"));

    assertThat(
        array(call("GET", CONNECTION + "/mappings", null, T, "MANAGER", MANAGER), 200).size(),
        is(0));
    assertError(
        call(
            "PUT",
            CONNECTION + "/mappings",
            "{\"mappings\":[{\"nominalCode\":\"not a code!\",\"externalAccount\":\"x\"}]}",
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_MAPPING_INVALID");
    assertError(
        call(
            "PUT",
            CONNECTION + "/mappings",
            "{\"mappings\":[{\"nominalCode\":\"1001\",\"externalAccount\":\" \"}]}",
            T,
            "OWNER",
            OWNER),
        400,
        "ACCOUNTING_MAPPING_INVALID");
    JsonArray saved =
        array(
            call(
                "PUT",
                CONNECTION + "/mappings",
                "{\"mappings\":[{\"nominalCode\":\"1001\",\"externalAccount\":\"SIM-1001\",\"externalName\":\"Stock on hand\"},"
                    + "{\"nominalCode\":\"2109\",\"externalAccount\":\"SIM-2109\"}]}",
                T,
                "MANAGER",
                MANAGER),
            200);
    assertThat(saved.size(), is(2));
    JsonArray read = array(call("GET", CONNECTION + "/mappings", null, T, "OWNER", OWNER), 200);
    assertThat(
        com.storeql.test.Envelopes.find(read, "nominalCode", "1001").getString("externalName"),
        is("Stock on hand"));
    // Replaced whole: what is left out is gone.
    array(
        call(
            "PUT",
            CONNECTION + "/mappings",
            "{\"mappings\":[{\"nominalCode\":\"1001\",\"externalAccount\":\"SIM-1001\"}]}",
            T,
            "OWNER",
            OWNER),
        200);
    assertThat(
        array(call("GET", CONNECTION + "/mappings", null, T, "OWNER", OWNER), 200).size(), is(1));
    // No connection, no chart and no mapping.
    assertError(
        call("GET", CONNECTION + "/accounts", null, T2, "OWNER", OWNER),
        404,
        "ACCOUNTING_NOT_CONNECTED");
  }

  // ── journals pushed once ────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Every journal dated from the day chosen is pushed to the package once, with what came back on"
          + " the log; older ones and a second pass push nothing")
  void journalsArePushedOnce() {
    String today = LocalDate.now().toString();
    String old = LocalDate.now().minusDays(40).toString();
    String before = postJournal(old, "Opening balances");
    object(
        call(
            "PUT",
            CONNECTION,
            simulated(LocalDate.now().minusDays(7).toString()),
            T,
            "OWNER",
            OWNER),
        200);
    String first = postJournal(today, "Rent");
    String second = postJournal(today, "Insurance");

    JsonObject run = object(call("POST", CONNECTION + "/sync", null, T, "MANAGER", MANAGER), 200);
    assertThat(run.getInt("queued"), is(2));
    assertThat(run.getInt("delivered"), is(2));
    assertThat(run.getInt("failed"), is(0));

    JsonObject page = object(call("GET", ACCOUNTING + "/syncs", null, T, "MANAGER", MANAGER), 200);
    JsonArray items = page.getJsonArray("items");
    assertThat(items.size(), is(2));
    JsonObject one = com.storeql.test.Envelopes.find(items, "journalId", first);
    assertThat(one.getString("status"), is("DELIVERED"));
    assertThat(one.getString("externalId"), is("sim-" + first));
    assertThat(one.getInt("attempts"), is(1));
    assertThat(one.getString("description"), is("Rent"));
    assertThat(one.getString("entryDate"), is(today));
    JsonObject detail =
        object(
            call("GET", ACCOUNTING + "/syncs/" + one.getString("id"), null, T, "OWNER", OWNER),
            200);
    assertThat(detail.getJsonArray("attemptLog").size(), is(1));
    assertThat(detail.getJsonArray("attemptLog").getJsonObject(0).getInt("statusCode"), is(200));
    assertThat(detail.getJsonArray("lines").size(), is(2));
    com.storeql.test.Envelopes.find(items, "journalId", second);
    // The one dated before the day chosen was not pushed.
    for (JsonObject s : items.getValuesAs(JsonObject.class)) {
      assertThat(s.getString("journalId"), not(is(before)));
    }
    // A second pass finds nothing new.
    JsonObject again = object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    assertThat(again.getInt("queued"), is(0));
    assertThat(again.getInt("delivered"), is(0));
    assertThat(
        object(call("GET", CONNECTION, null, T, "OWNER", OWNER), 200)
            .getJsonObject("counts")
            .getInt("delivered"),
        is(2));
    assertThat(
        object(call("GET", CONNECTION, null, T, "OWNER", OWNER), 200).getString("lastSyncAt"),
        not(nullValue()));
    // Another business reads none of it.
    assertError(
        call("GET", ACCOUNTING + "/syncs/" + one.getString("id"), null, T2, "OWNER", OWNER),
        404,
        "ACCOUNTING_SYNC_NOT_FOUND");
    assertThat(
        call("GET", ACCOUNTING + "/syncs", null, T, "CASHIER", CASHIER).getStatus(), is(403));
  }

  // ── a refusal waits with the reason, and is tried again ─────────────────────

  @Test
  @DisplayName(
      "A journal the package refuses waits with the reason and a next try; mapped and retried by"
          + " hand it lands; one nobody wants is skipped")
  void aRefusalWaitsAndIsRetried() {
    object(call("PUT", CONNECTION, simulated(null, "9999"), T, "OWNER", OWNER), 200);
    String refused = postJournal(LocalDate.now().toString(), "Suspense", "9999", "1200");
    String fine = postJournal(LocalDate.now().toString(), "Fine", "1001", "1200");

    JsonObject run = object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    assertThat(run.getInt("queued"), is(2));
    assertThat(run.getInt("delivered"), is(1));
    assertThat(run.getInt("failed"), is(1));
    JsonArray items =
        object(call("GET", ACCOUNTING + "/syncs?status=PENDING", null, T, "OWNER", OWNER), 200)
            .getJsonArray("items");
    assertThat(items.size(), is(1));
    JsonObject waiting = items.getJsonObject(0);
    assertThat(waiting.getString("journalId"), is(refused));
    assertThat(waiting.getInt("attempts"), is(1));
    assertThat(waiting.getString("lastError"), containsString("9999"));
    assertThat(
        Instant.parse(waiting.getString("nextAttemptAt")).isAfter(Instant.now().plusSeconds(30)),
        is(true));
    assertThat(
        object(call("GET", CONNECTION, null, T, "OWNER", OWNER), 200)
            .getJsonObject("counts")
            .getInt("pending"),
        is(1));
    assertThat(
        com.storeql.test.Envelopes.find(
                object(call("GET", ACCOUNTING + "/syncs", null, T, "OWNER", OWNER), 200)
                    .getJsonArray("items"),
                "journalId",
                fine)
            .getString("status"),
        is("DELIVERED"));

    // Not due yet: another pass leaves it.
    assertThat(
        object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200).getInt("failed"),
        is(0));
    // Mapped onto an account the package knows, and tried again by hand: it lands.
    array(
        call(
            "PUT",
            CONNECTION + "/mappings",
            "{\"mappings\":[{\"nominalCode\":\"9999\",\"externalAccount\":\"SIM-1100\"}]}",
            T,
            "OWNER",
            OWNER),
        200);
    assertThat(
        call(
                "POST",
                ACCOUNTING + "/syncs/" + waiting.getString("id") + "/retry",
                null,
                T,
                "MANAGER",
                MANAGER)
            .getStatus(),
        is(200));
    JsonObject retried = object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    assertThat(retried.getInt("delivered"), is(1));
    JsonObject landed =
        object(
            call("GET", ACCOUNTING + "/syncs/" + waiting.getString("id"), null, T, "OWNER", OWNER),
            200);
    assertThat(landed.getString("status"), is("DELIVERED"));
    assertThat(landed.getInt("attempts"), is(2));
    assertThat(landed.getJsonArray("attemptLog").size(), is(2));
    assertThat(
        landed.getJsonArray("attemptLog").getJsonObject(0).getString("error"),
        containsString("9999"));

    // A journal nobody wants in the package is skipped, with a reason, and stays skipped.
    String unwanted = postJournal(LocalDate.now().toString(), "Correction", "9999", "1200");
    array(call("PUT", CONNECTION + "/mappings", "{\"mappings\":[]}", T, "OWNER", OWNER), 200);
    object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    JsonObject stuck =
        com.storeql.test.Envelopes.find(
            object(call("GET", ACCOUNTING + "/syncs?status=PENDING", null, T, "OWNER", OWNER), 200)
                .getJsonArray("items"),
            "journalId",
            unwanted);
    assertThat(
        call(
                "POST",
                ACCOUNTING + "/syncs/" + stuck.getString("id") + "/skip",
                "{\"reason\":\"entered in the package by hand\"}",
                T,
                "CASHIER",
                CASHIER)
            .getStatus(),
        is(403));
    JsonObject skipped =
        object(
            call(
                "POST",
                ACCOUNTING + "/syncs/" + stuck.getString("id") + "/skip",
                "{\"reason\":\"entered in the package by hand\"}",
                T,
                "OWNER",
                OWNER),
            200);
    assertThat(skipped.getString("status"), is("SKIPPED"));
    assertThat(skipped.getString("lastError"), is("entered in the package by hand"));
    assertThat(
        object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200).getInt("failed"),
        is(0));
    assertError(
        call(
            "POST",
            ACCOUNTING + "/syncs/" + stuck.getString("id") + "/retry",
            null,
            T2,
            "OWNER",
            OWNER),
        404,
        "ACCOUNTING_SYNC_NOT_FOUND");
  }

  // ── an uncertain push is settled by a person, on the record ─────────────────

  private String syncIdOf(String journalId, String status) {
    return com.storeql.test.Envelopes.find(
            object(
                    call("GET", ACCOUNTING + "/syncs?status=" + status, null, T, "OWNER", OWNER),
                    200)
                .getJsonArray("items"),
            "journalId",
            journalId)
        .getString("id");
  }

  private JsonObject syncOf(String id) {
    return object(call("GET", ACCOUNTING + "/syncs/" + id, null, T, "OWNER", OWNER), 200);
  }

  @Test
  @DisplayName(
      "A person says whether an uncertain push landed: delivered under the package's own reference"
          + " and never pushed again, or queued to try again; once, and kept on the row")
  void anUncertainPushIsSettledByAPersonOnTheRecord() throws Exception {
    object(call("PUT", CONNECTION, simulated(null, "9999"), T, "OWNER", OWNER), 200);
    String landed = postJournal(LocalDate.now().toString(), "Maybe landed", "9999", "1200");
    String missing = postJournal(LocalDate.now().toString(), "Maybe not", "9999", "1200");
    String retried = postJournal(LocalDate.now().toString(), "Retried outright", "9999", "1200");
    object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    String landedId = syncIdOf(landed, "PENDING");
    String missingId = syncIdOf(missing, "PENDING");
    String retriedId = syncIdOf(retried, "PENDING");
    // No answer came back from a package with no idempotency key: the state the clock never leaves.
    for (String id : new String[] {landedId, missingId, retriedId}) {
      com.storeql.test.Envelopes.exec(
          PG, "UPDATE purchase.accounting_syncs SET status = 'UNCERTAIN' WHERE id = '" + id + "'");
    }
    String resolve = ACCOUNTING + "/syncs/" + landedId + "/resolve";
    String landedBody =
        "{\"outcome\":\"LANDED\",\"externalId\":\"SIM-4711\",\"note\":\"found in the package\"}";

    // Refused by role and by tenant, and nothing moves.
    assertThat(call("POST", resolve, landedBody, T, "CASHIER", CASHIER).getStatus(), is(403));
    assertThat(call("POST", resolve, landedBody, T, "STOREKEEPER", CASHIER).getStatus(), is(403));
    assertError(
        call("POST", resolve, landedBody, T2, "OWNER", OWNER), 404, "ACCOUNTING_SYNC_NOT_FOUND");
    assertError(
        call("POST", resolve, landedBody, T2, "MANAGER", MANAGER),
        404,
        "ACCOUNTING_SYNC_NOT_FOUND");
    assertError(
        call("POST", resolve, "{\"outcome\":\"MAYBE\"}", T, "MANAGER", MANAGER),
        400,
        "ACCOUNTING_OUTCOME_INVALID");
    assertError(
        call("POST", resolve, "{\"outcome\":\"LANDED\"}", T, "MANAGER", MANAGER),
        400,
        "ACCOUNTING_EXTERNAL_ID_REQUIRED");
    assertError(
        call(
            "POST",
            resolve,
            "{\"outcome\":\"LANDED\",\"externalId\":\"  \"}",
            T,
            "MANAGER",
            MANAGER),
        400,
        "ACCOUNTING_EXTERNAL_ID_REQUIRED");
    // A note longer than the package would take is refused, and the push stays uncertain.
    assertError(
        call(
            "POST",
            resolve,
            "{\"outcome\":\"LANDED\",\"externalId\":\"SIM-1\",\"note\":\""
                + "x".repeat(501)
                + "\"}",
            T,
            "MANAGER",
            MANAGER),
        400,
        "ACCOUNTING_NOTE_TOO_LONG");
    assertThat(syncOf(landedId).getString("status"), is("UNCERTAIN"));

    // It did land: delivered under the package's reference, who and when on the row.
    JsonObject done = object(call("POST", resolve, landedBody, T, "MANAGER", MANAGER), 200);
    assertThat(done.getString("status"), is("DELIVERED"));
    assertThat(done.getString("externalId"), is("SIM-4711"));
    assertThat(done.containsKey("deliveredAt"), is(true));
    JsonObject decision = done.getJsonObject("resolution");
    assertThat(decision.getString("outcome"), is("LANDED"));
    assertThat(decision.getString("resolvedBy"), is(MANAGER));
    assertThat(decision.getString("note"), is("found in the package"));
    assertThat(decision.containsKey("resolvedAt"), is(true));
    // Never pushed again: a pass finds nothing to send for it, and its attempts stay as they were.
    JsonObject pass = object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    assertThat(pass.getInt("delivered"), is(0));
    JsonObject after = syncOf(landedId);
    assertThat(after.getString("status"), is("DELIVERED"));
    assertThat(after.getInt("attempts"), is(1));
    // Decided once, either way.
    assertError(
        call("POST", resolve, landedBody, T, "OWNER", OWNER), 409, "ACCOUNTING_SYNC_NOT_UNCERTAIN");
    assertError(
        call("POST", resolve, "{\"outcome\":\"NOT_LANDED\"}", T, "OWNER", OWNER),
        409,
        "ACCOUNTING_SYNC_NOT_UNCERTAIN");
    // A pending one is not uncertain.
    String pending = postJournal(LocalDate.now().toString(), "Still pending", "9999", "1200");
    object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    assertError(
        call(
            "POST",
            ACCOUNTING + "/syncs/" + syncIdOf(pending, "PENDING") + "/resolve",
            "{\"outcome\":\"NOT_LANDED\"}",
            T,
            "OWNER",
            OWNER),
        409,
        "ACCOUNTING_SYNC_NOT_UNCERTAIN");

    // It never landed: queued to go now, the decision kept, and when the cause is mended it lands.
    JsonObject notLanded =
        object(
            call(
                "POST",
                ACCOUNTING + "/syncs/" + missingId + "/resolve",
                "{\"outcome\":\"NOT_LANDED\",\"note\":\"checked the package\"}",
                T,
                "OWNER",
                OWNER),
            200);
    assertThat(notLanded.getString("status"), is("PENDING"));
    assertThat(notLanded.getJsonObject("resolution").getString("outcome"), is("NOT_LANDED"));
    assertThat(notLanded.getJsonObject("resolution").getString("resolvedBy"), is(OWNER));
    array(
        call(
            "PUT",
            CONNECTION + "/mappings",
            "{\"mappings\":[{\"nominalCode\":\"9999\",\"externalAccount\":\"SIM-1100\"}]}",
            T,
            "OWNER",
            OWNER),
        200);
    object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    JsonObject nowLanded = syncOf(missingId);
    assertThat(nowLanded.getString("status"), is("DELIVERED"));
    assertThat(nowLanded.getJsonObject("resolution").getString("outcome"), is("NOT_LANDED"));

    // Trying an uncertain one again by hand is the same word, and is recorded as such.
    JsonObject retry =
        object(
            call(
                "POST", ACCOUNTING + "/syncs/" + retriedId + "/retry", null, T, "MANAGER", MANAGER),
            200);
    JsonObject retryDecision = retry.getJsonObject("resolution");
    assertThat(retryDecision.getString("outcome"), is("NOT_LANDED"));
    assertThat(retryDecision.getString("resolvedBy"), is(MANAGER));
    // A push nobody was unsure about carries no decision.
    assertThat(syncOf(syncIdOf(pending, "PENDING")).containsKey("resolution"), is(false));
  }

  // ── switched off, switched on, removed ──────────────────────────────────────

  @Test
  @DisplayName(
      "Switched off, nothing is pushed; switched on, what waited goes; removed, the log goes with it")
  void switchedOffAndOn() {
    object(call("PUT", CONNECTION, simulated(null), T, "OWNER", OWNER), 200);
    assertThat(
        call("POST", CONNECTION + "/disable", null, T, "MANAGER", MANAGER).getStatus(), is(403));
    JsonObject off = object(call("POST", CONNECTION + "/disable", null, T, "OWNER", OWNER), 200);
    assertThat(off.getString("status"), is("DISABLED"));
    postJournal(LocalDate.now().toString(), "While off");
    assertError(
        call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 409, "ACCOUNTING_DISABLED");
    assertThat(
        object(call("GET", ACCOUNTING + "/syncs", null, T, "OWNER", OWNER), 200)
            .getJsonArray("items")
            .size(),
        is(0));
    object(call("POST", CONNECTION + "/enable", null, T, "OWNER", OWNER), 200);
    JsonObject run = object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    assertThat(run.getInt("delivered"), is(1));
    assertThat(call("DELETE", CONNECTION, null, T, "OWNER", OWNER).getStatus(), is(200));
    assertError(
        call("GET", ACCOUNTING + "/syncs", null, T, "OWNER", OWNER),
        404,
        "ACCOUNTING_NOT_CONNECTED");
    assertThat(
        com.storeql.test.Envelopes.scalar(
            PG, "SELECT count(*) FROM purchase.accounting_syncs WHERE tenant_id = '" + T + "'"),
        is("0"));
  }

  @Test
  @DisplayName("A sync status nobody defined is refused by name, for management only")
  void aSyncStatusNobodyKnowsIsRefused() {
    object(call("PUT", CONNECTION, simulated(null), T, "OWNER", OWNER), 200);
    assertError(
        call("GET", ACCOUNTING + "/syncs?status=LOST", null, T, "MANAGER", MANAGER),
        400,
        "ACCOUNTING_STATUS_INVALID");
    // Lower case is read as the status it names, so a known one still answers.
    assertThat(
        call("GET", ACCOUNTING + "/syncs?status=pending", null, T, "MANAGER", MANAGER).getStatus(),
        is(200));
    assertThat(
        call("GET", ACCOUNTING + "/syncs?status=PENDING", null, T, "CASHIER", CASHIER).getStatus(),
        is(403));
    // Another business has no connection to list, whatever it asks for.
    assertError(
        call("GET", ACCOUNTING + "/syncs?status=PENDING", null, T2, "OWNER", OWNER),
        404,
        "ACCOUNTING_NOT_CONNECTED");
  }

  @Test
  @DisplayName(
      "A journal is left out of the package only with a reason, by management, never once it is in"
          + " the package, and never another business's; a decision with no outcome is refused too")
  void aSkipNeedsAReasonAndAJournalOfOurOwn() {
    object(call("PUT", CONNECTION, simulated(null, "9999"), T, "OWNER", OWNER), 200);
    String stuck =
        postJournal(LocalDate.now().toString(), "Refused by the package", "9999", "1200");
    String taken = postJournal(LocalDate.now().toString(), "Taken by the package", "1001", "1200");
    object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    String stuckId = syncIdOf(stuck, "PENDING");
    String takenId = syncIdOf(taken, "DELIVERED");
    String skip = ACCOUNTING + "/syncs/" + stuckId + "/skip";

    // The reason is the point of it: none, a blank one, a null one, or one longer than the log
    // keeps is refused by name, and the journal waits as it was. (Both requests are read by the
    // service, which names each refusal; the request records carry no constraint of their own.)
    for (String body :
        new String[] {
          "{}",
          "{\"reason\":null}",
          "{\"reason\":\"   \"}",
          "{\"reason\":\"" + "x".repeat(501) + "\"}"
        }) {
      assertError(call("POST", skip, body, T, "OWNER", OWNER), 400, "ACCOUNTING_REASON_REQUIRED");
    }
    for (String body : new String[] {"{}", "{\"outcome\":null}", "{\"outcome\":\"\"}"}) {
      assertError(
          call("POST", ACCOUNTING + "/syncs/" + stuckId + "/resolve", body, T, "OWNER", OWNER),
          400,
          "ACCOUNTING_OUTCOME_INVALID");
    }
    assertThat(syncOf(stuckId).getString("status"), is("PENDING"));

    // Another business's management finds no such push; a shelf, a till or a shopper is turned
    // away.
    for (String roles : new String[] {"OWNER", "MANAGER"}) {
      assertError(
          call("POST", skip, "{\"reason\":\"not theirs\"}", T2, roles, OWNER),
          404,
          "ACCOUNTING_SYNC_NOT_FOUND");
    }
    for (String roles : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertThat(
          roles,
          call("POST", skip, "{\"reason\":\"not mine\"}", T, roles, CASHIER).getStatus(),
          is(403));
    }
    assertThat("nothing moved", syncOf(stuckId).getString("status"), is("PENDING"));

    // What is already in the package is not left out of it.
    assertError(
        call(
            "POST",
            ACCOUNTING + "/syncs/" + takenId + "/skip",
            "{\"reason\":\"already there\"}",
            T,
            "OWNER",
            OWNER),
        409,
        "ACCOUNTING_SYNC_DELIVERED");
    assertThat(syncOf(takenId).getString("status"), is("DELIVERED"));

    // With a reason, by management, it is left out.
    assertThat(
        object(call("POST", skip, "{\"reason\":\"entered by hand\"}", T, "MANAGER", MANAGER), 200)
            .getString("status"),
        is("SKIPPED"));
  }

  @Test
  @DisplayName(
      "QuickBooks Online's environment is PRODUCTION or SANDBOX: anything else is refused at"
          + " connect, and a connection kept with one before the check is refused unsent at push"
          + " time, with its reason, and its chart is not asked for")
  void aQuickBooksEnvironmentIsOneTheDriverKnows() {
    String quickBooks =
        "{\"provider\":\"QUICKBOOKS\",\"settings\":{\"realmId\":\"9130\",\"environment\":\"%s\"},"
            + "\"credentials\":{\"accessToken\":\"t\"},\"syncFrom\":\"2026-01-01\"}";
    for (String environment : new String[] {"PROD", "Sandbx", "live"}) {
      Response r =
          call("PUT", CONNECTION, String.format(quickBooks, environment), T, "OWNER", OWNER);
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(400));
      assertThat(body, parse(body).getString("code"), is("ACCOUNTING_SETTINGS_INVALID"));
      assertThat(body, containsString("environment"));
    }
    assertError(call("GET", CONNECTION, null, T, "OWNER", OWNER), 404, "ACCOUNTING_NOT_CONNECTED");
    object(call("PUT", CONNECTION, String.format(quickBooks, "sandbox"), T, "OWNER", OWNER), 200);

    // A connection kept before the check, with an environment its driver never knew: it would have
    // been sent to production. It is refused before anything leaves, for a person to put right.
    com.storeql.test.Envelopes.exec(
        PG,
        "UPDATE purchase.accounting_connections"
            + " SET settings = '{\"realmId\":\"9130\",\"environment\":\"PROD\"}'"
            + " WHERE tenant_id = '"
            + T
            + "'");
    String journal = postJournal(LocalDate.now().toString(), "Kept before the check");
    JsonObject run = object(call("POST", CONNECTION + "/sync", null, T, "OWNER", OWNER), 200);
    assertThat(run.getInt("failed"), is(1));
    assertThat(run.getInt("delivered"), is(0));
    JsonObject sync = syncOf(syncIdOf(journal, "FAILED"));
    assertThat(sync.getString("lastError"), containsString("environment"));
    JsonObject attempt = sync.getJsonArray("attemptLog").getJsonObject(0);
    // JSON-B leaves a null out: absent and null both mean nothing answered.
    assertThat(
        "nothing was sent, so nothing answered",
        !attempt.containsKey("statusCode") || attempt.isNull("statusCode"),
        is(true));
    assertError(
        call("GET", CONNECTION + "/accounts", null, T, "OWNER", OWNER),
        409,
        "ACCOUNTING_SETTINGS_INVALID");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static String simulated(String syncFrom) {
    return simulated(syncFrom, null);
  }

  private static String simulated(String syncFrom, String refuse) {
    return "{\"provider\":\"SIMULATED\",\"settings\":{"
        + (refuse == null ? "" : "\"refuse\":\"" + refuse + "\"")
        + "},\"syncFrom\":\""
        + (syncFrom == null ? "2026-01-01" : syncFrom)
        + "\"}";
  }

  private static String xeroJson() {
    return "{\"provider\":\"XERO\",\"settings\":{\"tenantId\":\"xero-org-1\"},"
        + "\"credentials\":{\"accessToken\":\"xero-access\",\"refreshToken\":\"xero-refresh\","
        + "\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"expiresAt\":\"2026-09-23T10:00:00Z\"},"
        + "\"syncFrom\":\"2026-01-01\"}";
  }

  private String postJournal(String date, String description) {
    return postJournal(date, description, "1001", "1200");
  }

  private String postJournal(String date, String description, String debitCode, String creditCode) {
    Response r =
        call(
            "POST",
            "/nominal-ledger/journals",
            "{\"entryDate\":\""
                + date
                + "\",\"description\":\""
                + description
                + "\",\"lines\":[{\"nominalCode\":\""
                + debitCode
                + "\",\"nominalName\":\"Debit side\",\"debit\":\"120.00\"},{\"nominalCode\":\""
                + creditCode
                + "\",\"nominalName\":\"Credit side\",\"credit\":\"120.00\"}]}",
            T,
            "OWNER",
            OWNER);
    return object(r, 201).getString("journalId");
  }

  private Response call(
      String method, String path, String json, String tenant, String roles, String user) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv.length == 2 ? kv[1] : "");
      }
    }
    var b =
        t.request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", user)
            .header("X-Roles", roles);
    return switch (method) {
      case "GET" -> b.get();
      case "DELETE" -> b.delete();
      case "PUT" -> b.put(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
    };
  }

  private static String bodyOf(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private static JsonObject object(Response r, int status) {
    return parse(bodyOf(r, status)).getJsonObject("data");
  }

  private static JsonArray array(Response r, int status) {
    return parse(bodyOf(r, status)).getJsonArray("data");
  }

  private static void assertError(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, parse(body).getString("code", null), is(code));
  }
}
