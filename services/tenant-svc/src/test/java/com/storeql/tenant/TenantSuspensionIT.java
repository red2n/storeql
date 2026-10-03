package com.storeql.tenant;

import static com.storeql.tenant.AdminRig.call;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.tenant.AdminRig.Answer;
import com.storeql.tenant.AdminRig.Biz;
import com.storeql.tenant.AdminRig.Who;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Suspending and reactivating a business (flow catalogue plat-suspend-and-reactivate-business): a
 * suspension carries the administrator's reason, kept with who and when and shown on reading the
 * business; reactivation clears it; only the platform administrator may, only ACTIVE or INACTIVE
 * are accepted, and one business's suspension touches no other.
 */
@HelidonTest
class TenantSuspensionIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static Who admin() {
    return new Who(null, Ids.newId().toString(), "PLATFORM_ADMIN", null, null);
  }

  private Answer status(Who who, String tenant, String json) {
    return call(target, "PATCH", "/platform/tenants/" + tenant + "/status", json, who);
  }

  private JsonObject read(String tenant) {
    Answer a = call(target, "GET", "/platform/tenants/" + tenant, null, admin());
    assertThat(a.text(), a.status(), is(200));
    return a.data();
  }

  private static String str(JsonObject o, String key) {
    return !o.containsKey(key) || o.isNull(key) ? null : o.getString(key);
  }

  /** The payloads of a tenant's TenantStatusChanged events, oldest first. */
  private List<String> statusEvents(String tenant) throws Exception {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid"
                    + " AND event_type = 'TenantStatusChanged' ORDER BY created_at, id")) {
      ps.setString(1, tenant);
      var rs = ps.executeQuery();
      while (rs.next()) out.add(rs.getString(1));
    }
    return out;
  }

  @Test
  @DisplayName(
      "A suspension needs a reason, which is kept with who and when and shown on reading the business")
  void aSuspensionCarriesItsReason() throws Exception {
    Biz b = AdminRig.biz(target, "Suspended For Cause", "GB", "GBP");
    Who admin = admin();

    for (String body :
        new String[] {
          "{\"status\":\"INACTIVE\"}",
          "{\"status\":\"INACTIVE\",\"reason\":\"\"}",
          "{\"status\":\"inactive\",\"reason\":\"   \"}",
          "{\"status\":\"INACTIVE\",\"reason\":null}"
        }) {
      Answer a = status(admin, b.tenant(), body);
      assertThat(body + " -> " + a.text(), a.status(), is(400));
      assertThat(a.code(), is("TENANT_STATUS_REASON_REQUIRED"));
    }
    Answer tooLong =
        status(
            admin, b.tenant(), "{\"status\":\"INACTIVE\",\"reason\":\"" + "x".repeat(501) + "\"}");
    assertThat(tooLong.text(), tooLong.status(), is(400));
    // Nothing happened: still trading, nothing announced.
    assertThat(str(read(b.tenant()), "status"), is("ACTIVE"));
    assertThat(str(read(b.tenant()), "deactivatedNote"), is(nullValue()));
    assertThat(statusEvents(b.tenant()).size(), is(0));

    Who person = admin;
    Answer off =
        status(
            person,
            b.tenant(),
            "{\"status\":\"INACTIVE\",\"reason\":\"  Chargebacks on 14 orders, case 4471  \"}");
    assertThat(off.text(), off.status(), is(200));
    JsonObject d = off.data();
    assertThat(d.getString("status"), is("INACTIVE"));
    assertThat(d.getString("deactivatedReason"), is("ADMINISTRATOR"));
    assertThat(d.getString("deactivatedNote"), is("Chargebacks on 14 orders, case 4471"));
    assertThat(d.getString("deactivatedBy"), is(person.user()));
    assertThat(java.time.Instant.parse(d.getString("deactivatedAt")), is(notNullValue()));

    // The platform console reads it back, on the one and in the list.
    JsonObject one = read(b.tenant());
    assertThat(one.getString("deactivatedNote"), is("Chargebacks on 14 orders, case 4471"));
    assertThat(one.getString("deactivatedBy"), is(person.user()));
    Answer list = call(target, "GET", "/platform/tenants?limit=100", null, admin);
    assertThat(list.text(), list.status(), is(200));
    boolean listed = false;
    for (var v : list.list()) {
      JsonObject t = v.asJsonObject();
      if (t.getString("id").equals(b.tenant())) {
        listed = true;
        assertThat(t.getString("deactivatedNote"), is("Chargebacks on 14 orders, case 4471"));
      }
    }
    assertThat("the suspended business is in the list", listed, is(true));
    List<String> events = statusEvents(b.tenant());
    assertThat(events.size(), is(1));
    assertThat(events.get(0), containsString("INACTIVE"));

    // Reactivation needs no reason, and clears what was said, who and when.
    Answer on = status(admin(), b.tenant(), "{\"status\":\"ACTIVE\"}");
    assertThat(on.text(), on.status(), is(200));
    JsonObject back = on.data();
    assertThat(back.getString("status"), is("ACTIVE"));
    assertThat(str(back, "deactivatedNote"), is(nullValue()));
    assertThat(str(back, "deactivatedBy"), is(nullValue()));
    assertThat(str(back, "deactivatedAt"), is(nullValue()));
    assertThat(str(back, "deactivatedReason"), is(nullValue()));
    assertThat(statusEvents(b.tenant()).size(), is(2));

    // A reason on reactivation is accepted (and not kept against a business that is on); a second
    // suspension carries its own words.
    assertThat(
        status(admin(), b.tenant(), "{\"status\":\"INACTIVE\",\"reason\":\"first\"}").status(),
        is(200));
    Answer on2 =
        status(admin(), b.tenant(), "{\"status\":\"ACTIVE\",\"reason\":\"cleared by fraud team\"}");
    assertThat(on2.text(), on2.status(), is(200));
    assertThat(str(on2.data(), "deactivatedNote"), is(nullValue()));
    Answer again = status(admin(), b.tenant(), "{\"status\":\"INACTIVE\",\"reason\":\"second\"}");
    assertThat(again.data().getString("deactivatedNote"), is("second"));
  }

  @Test
  @DisplayName(
      "Only ACTIVE or INACTIVE are accepted; anything else is refused before a reason is asked for")
  void onlyTwoStatuses() {
    Biz b = AdminRig.biz(target, "Odd Status", "GB", "GBP");
    Answer odd = status(admin(), b.tenant(), "{\"status\":\"SUSPENDED_FOREVER\",\"reason\":\"x\"}");
    assertThat(odd.text(), odd.status(), is(400));
    assertThat(odd.code(), is("INVALID_STATUS"));
    assertThat(status(admin(), b.tenant(), "{\"reason\":\"no status\"}").status(), is(400));
    assertThat(str(read(b.tenant()), "status"), is("ACTIVE"));
    Answer ghost =
        status(admin(), Ids.newId().toString(), "{\"status\":\"INACTIVE\",\"reason\":\"x\"}");
    assertThat(ghost.text(), ghost.status(), is(404));
  }

  @Test
  @DisplayName(
      "Nobody but the platform administrator changes a business's status, or looks one up by e-invoicing address")
  void onlyThePlatformAdministrator() throws Exception {
    Biz b = AdminRig.biz(target, "Not Yours To Suspend", "GB", "GBP");
    Biz rival = AdminRig.biz(target, "Rival Suspender", "GB", "GBP");
    String suspend = "{\"status\":\"INACTIVE\",\"reason\":\"take it down\"}";
    for (Who who :
        new Who[] {
          b.owner(),
          b.manager(),
          b.as("STOREKEEPER"),
          b.as("CASHIER"),
          b.as("CUSTOMER"),
          rival.owner(),
          rival.manager(b.store())
        }) {
      Answer own = status(who, b.tenant(), suspend);
      assertThat(who.roles() + ": " + own.text(), own.status(), is(403));
    }
    assertThat(status(b.owner(), b.tenant(), suspend).status(), is(403));
    assertThat(str(read(b.tenant()), "status"), is("ACTIVE"));
    assertThat(statusEvents(b.tenant()).size(), is(0));
    for (Who who : new Who[] {b.owner(), b.manager(), b.as("CASHIER")}) {
      Answer lookup =
          call(
              target,
              "GET",
              "/platform/tenants/by-einvoice-address?vatNumber=GB123456789",
              null,
              who);
      assertThat(who.roles() + ": " + lookup.text(), lookup.status(), is(403));
    }
    Answer oneRead = call(target, "GET", "/platform/tenants/" + b.tenant(), null, b.owner());
    assertThat(oneRead.status(), is(403));
    assertThat(call(target, "GET", "/platform/tenants", null, rival.owner()).status(), is(403));
  }

  @Test
  @DisplayName(
      "Suspending one business closes its stores and touches no other; reactivating restores it but not its stores")
  void isolationAndReactivation() throws Exception {
    Biz a = AdminRig.biz(target, "Suspended A", "GB", "GBP");
    Biz other = AdminRig.biz(target, "Untouched B", "GB", "GBP");
    Answer off =
        status(admin(), a.tenant(), "{\"status\":\"INACTIVE\",\"reason\":\"fraud review\"}");
    assertThat(off.text(), off.status(), is(200));

    assertThat(str(read(other.tenant()), "status"), is("ACTIVE"));
    assertThat(str(read(other.tenant()), "deactivatedNote"), is(nullValue()));
    assertThat(statusEvents(other.tenant()).size(), is(0));
    assertThat(
        call(target, "GET", "/admin/stores/" + other.store(), null, other.owner())
            .data()
            .getString("status"),
        is("ACTIVE"));
    assertThat(
        call(target, "GET", "/admin/tenant", null, other.owner()).data().getString("status"),
        is("ACTIVE"));
    // A's own store was closed with it, and A's event says so.
    assertThat(
        call(target, "GET", "/admin/stores/" + a.store(), null, a.owner())
            .data()
            .getString("status"),
        is("SUSPENDED"));
    assertThat(statusEvents(a.tenant()).get(0), containsString("INACTIVE"));

    Answer on = status(admin(), a.tenant(), "{\"status\":\"ACTIVE\"}");
    assertThat(on.text(), on.status(), is(200));
    assertThat(
        call(target, "GET", "/admin/tenant", null, a.owner()).data().getString("status"),
        is("ACTIVE"));
    assertThat(statusEvents(a.tenant()).get(1), containsString("ACTIVE"));
    // Stores are reopened by a person, as the platform has always documented.
    assertThat(
        call(target, "GET", "/admin/stores/" + a.store(), null, a.owner())
            .data()
            .getString("status"),
        is("SUSPENDED"));
    assertThat(
        call(
                target,
                "PATCH",
                "/admin/stores/" + a.store() + "/status",
                "{\"status\":\"ACTIVE\"}",
                a.owner())
            .status(),
        is(200));
    List<String> events = new ArrayList<>(statusEvents(other.tenant()));
    assertThat(events, org.hamcrest.Matchers.empty());
    assertThat(List.of("ACTIVE", "INACTIVE"), hasItem(str(read(a.tenant()), "status")));
  }
}
