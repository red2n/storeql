package com.storeql.tenant;

import static com.storeql.test.Envelopes.find;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.okArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The business's exchange rates (03.x): none but the home currency to begin with, a rate set with a
 * reason and read back by staff, refused by name when the currency, rate, day or reason cannot be
 * kept, and a history that keeps every rate.
 */
@HelidonTest
class FxRateIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start().wire("tenant");
  }

  private static final String BASE = "/admin/tenant/fx-rates";
  private static final String OWNER = Ids.newId().toString();

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private String onboard(String country, String currency) {
    return TenantOnboarding.onboard(target, "FX " + Ids.newId(), country, currency);
  }

  private Invocation.Builder as(String path, String tenant, String role) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", OWNER)
        .header("X-Roles", role);
  }

  private Response set(String tenant, String role, String currency, String body) {
    return as(BASE + "/" + currency, tenant, role)
        .put(Entity.entity(body, MediaType.APPLICATION_JSON));
  }

  private static void refused(Response r, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(400));
    assertThat(body, containsString(code));
  }

  @Test
  @DisplayName(
      "A business begins with its home currency and no rates; a rate set with a reason is read back by staff")
  void sheetAndSet() {
    String gb = onboard("GB", "GBP");
    JsonObject empty = ok(as(BASE, gb, "OWNER").get());
    assertThat(empty.getString("home"), is("GBP"));
    assertThat(empty.getJsonArray("rates").size(), is(0));

    JsonObject usd =
        ok(set(gb, "MANAGER", "usd", "{\"rate\":0.79,\"reason\":\"ECB reference, 22 Sep\"}"));
    assertThat(usd.getString("currency"), is("USD"));
    assertThat(usd.getJsonNumber("rate").doubleValue(), closeTo(0.79, 1e-9));
    assertThat(usd.getString("effectiveFrom"), is(LocalDate.now(ZoneOffset.UTC).toString()));
    assertThat(usd.getString("reason"), is("ECB reference, 22 Sep"));
    assertThat(usd.getString("setBy"), is(OWNER));
    ok(
        set(
            gb,
            "OWNER",
            "JPY",
            "{\"rate\":0.0053,\"effectiveFrom\":\"2026-01-01\",\"reason\":\"a\"}"));

    // The sheet, as a storekeeper's service reads it.
    JsonObject sheet = ok(as(BASE, gb, "STOREKEEPER").get());
    assertThat(sheet.getString("home"), is("GBP"));
    JsonArray rates = sheet.getJsonArray("rates");
    assertThat(rates.size(), is(2));
    assertThat(
        find(rates, "currency", "JPY").getJsonNumber("rate").doubleValue(), closeTo(0.0053, 1e-9));

    // A rate dated tomorrow is kept but not yet in force; a rate set twice today, the later wins.
    String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
    ok(
        set(
            gb,
            "OWNER",
            "USD",
            "{\"rate\":0.80,\"effectiveFrom\":\"" + tomorrow + "\",\"reason\":\"b\"}"));
    ok(set(gb, "OWNER", "USD", "{\"rate\":0.78,\"reason\":\"corrected\"}"));
    JsonObject today =
        find(ok(as(BASE, gb, "OWNER").get()).getJsonArray("rates"), "currency", "USD");
    assertThat(today.getJsonNumber("rate").doubleValue(), closeTo(0.78, 1e-9));
    JsonArray history = okArray(as(BASE + "/USD/history", gb, "OWNER").get());
    assertThat(history.size(), is(3));
    assertThat(history.getJsonObject(0).getString("effectiveFrom"), is(tomorrow));

    // Another business's rates are its own.
    String other = onboard("US", "USD");
    JsonObject theirs = ok(as(BASE, other, "OWNER").get());
    assertThat(theirs.getString("home"), is("USD"));
    assertThat(theirs.getJsonArray("rates").size(), is(0));
  }

  @Test
  @DisplayName(
      "Refused by name: the home currency, a code nobody knows, a rate that is not a rate, a day out of reach, no reason; and by role")
  void refusals() {
    String gb = onboard("GB", "GBP");
    refused(set(gb, "OWNER", "GBP", "{\"rate\":1,\"reason\":\"x\"}"), "FX_CURRENCY_INVALID");
    refused(set(gb, "OWNER", "XYZ", "{\"rate\":1,\"reason\":\"x\"}"), "FX_CURRENCY_INVALID");
    refused(set(gb, "OWNER", "USD", "{\"rate\":0,\"reason\":\"x\"}"), "FX_RATE_INVALID");
    refused(set(gb, "OWNER", "USD", "{\"rate\":-0.5,\"reason\":\"x\"}"), "FX_RATE_INVALID");
    refused(
        set(gb, "OWNER", "USD", "{\"rate\":0.12345678901,\"reason\":\"x\"}"), "FX_RATE_INVALID");
    refused(
        set(
            gb,
            "OWNER",
            "USD",
            "{\"rate\":0.79,\"effectiveFrom\":\"1999-12-31\",\"reason\":\"x\"}"),
        "FX_DATE_INVALID");
    refused(
        set(
            gb,
            "OWNER",
            "USD",
            "{\"rate\":0.79,\"effectiveFrom\":\"2030-01-01\",\"reason\":\"x\"}"),
        "FX_DATE_INVALID");
    refused(
        set(gb, "OWNER", "USD", "{\"rate\":0.79,\"effectiveFrom\":\"next week\",\"reason\":\"x\"}"),
        "FX_DATE_INVALID");
    assertThat(set(gb, "OWNER", "USD", "{\"rate\":0.79}").getStatus(), is(400));
    assertThat(set(gb, "OWNER", "USD", "{\"rate\":0.79,\"reason\":\"  \"}").getStatus(), is(400));
    assertThat(
        set(gb, "STOREKEEPER", "USD", "{\"rate\":0.79,\"reason\":\"x\"}").getStatus(), is(403));
    assertThat(set(gb, "CASHIER", "USD", "{\"rate\":0.79,\"reason\":\"x\"}").getStatus(), is(403));
    assertThat(as(BASE + "/USD/history", gb, "STOREKEEPER").get().getStatus(), is(403));
    assertThat(as(BASE, gb, "CUSTOMER").get().getStatus(), is(403));
  }
}
