package com.storeql.tenant;

import static com.storeql.tenant.AdminRig.assign;
import static com.storeql.tenant.AdminRig.call;
import static com.storeql.tenant.AdminRig.q;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
 * Stores, zones and staff refusals from the flow catalogue (onb-stores-zones-hours,
 * stf-staff-assignment-and-roles): tenders kept when omitted and never emptied, a store's status
 * announced, zones added, paged, changed and closed to other businesses, and the staff calls that
 * name a store nobody has or none at all.
 */
@HelidonTest
class StoreZoneRulesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private JsonObject store(Biz b, String id) {
    Answer a = call(target, "GET", "/admin/stores/" + id, null, b.owner());
    assertThat(a.text(), a.status(), is(200));
    return a.data();
  }

  private static List<String> strings(JsonObject store, String key) {
    List<String> out = new ArrayList<>();
    store.getJsonArray(key).forEach(v -> out.add(((jakarta.json.JsonString) v).getString()));
    return out;
  }

  private List<String> statusEvents(Biz b, String storeId) throws Exception {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid"
                    + " AND event_type = 'StoreStatusChanged' AND aggregate_id = ?::uuid"
                    + " ORDER BY created_at, id")) {
      ps.setString(1, b.tenant());
      ps.setString(2, storeId);
      var rs = ps.executeQuery();
      while (rs.next()) out.add(rs.getString(1));
    }
    return out;
  }

  @Test
  @DisplayName(
      "ONB-302, ONB-303: tenders survive an update that omits them, and an empty or unknown list is refused, changing nothing")
  void tendersAreKeptAndNeverEmptied() {
    Biz b = AdminRig.biz(target, "Tenders Ltd", "IN", "INR");
    Who manager = b.manager();
    String url = "/admin/stores/" + b.store();
    Answer set =
        call(
            target,
            "PUT",
            url,
            "{\"name\":\"Main\",\"timezone\":\"Asia/Kolkata\",\"enabledPaymentMethods\":[\"upi\",\"cash\"]}",
            manager);
    assertThat(set.text(), set.status(), is(200));
    assertThat(strings(set.data(), "enabledPaymentMethods"), is(List.of("UPI", "CASH")));

    Answer omitted =
        call(
            target,
            "PUT",
            url,
            "{\"name\":\"Renamed\",\"timezone\":\"Asia/Kolkata\",\"city\":\"Pune\"}",
            manager);
    assertThat(omitted.text(), omitted.status(), is(200));
    assertThat(omitted.data().getString("name"), is("Renamed"));
    assertThat(omitted.data().getString("city"), is("Pune"));
    assertThat(strings(omitted.data(), "enabledPaymentMethods"), is(List.of("UPI", "CASH")));

    Answer empty =
        call(
            target,
            "PUT",
            url,
            "{\"name\":\"Nope\",\"timezone\":\"Asia/Kolkata\",\"enabledPaymentMethods\":[]}",
            manager);
    assertThat(empty.text(), empty.status(), is(400));
    assertThat(empty.code(), is("STORE_PAYMENT_METHODS_EMPTY"));
    Answer blanks =
        call(
            target,
            "PUT",
            url,
            "{\"name\":\"Nope\",\"timezone\":\"Asia/Kolkata\",\"enabledPaymentMethods\":[\"\",\"  \"]}",
            manager);
    assertThat(blanks.code(), is("STORE_PAYMENT_METHODS_EMPTY"));
    Answer unknown =
        call(
            target,
            "PUT",
            url,
            "{\"name\":\"Nope\",\"timezone\":\"Asia/Kolkata\",\"enabledPaymentMethods\":[\"CASH\",\"BARTER\"]}",
            manager);
    assertThat(unknown.text(), unknown.status(), is(400));
    assertThat(unknown.code(), is("STORE_PAYMENT_METHOD_INVALID"));

    JsonObject after = store(b, b.store());
    assertThat(after.getString("name"), is("Renamed"));
    assertThat(strings(after, "enabledPaymentMethods"), is(List.of("UPI", "CASH")));
  }

  @Test
  @DisplayName(
      "ONB-304: a store's status change is announced with the store and the new status; a refused status announces nothing")
  void aStatusChangeIsAnnounced() throws Exception {
    Biz b = AdminRig.biz(target, "Announced Ltd", "IN", "INR");
    List<String> before = statusEvents(b, b.store());
    assertThat("creation announced the first status", before, hasSize(1));

    Answer closed =
        call(
            target,
            "PATCH",
            "/admin/stores/" + b.store() + "/status",
            "{\"status\":\"closed\"}",
            b.manager());
    assertThat(closed.text(), closed.status(), is(200));
    assertThat(closed.data().getString("status"), is("CLOSED"));
    List<String> events = statusEvents(b, b.store());
    assertThat(events, hasSize(2));
    assertThat(events.get(1), containsString("\"storeId\":\"" + b.store() + "\""));
    assertThat(events.get(1), containsString("\"status\":\"CLOSED\""));

    Answer bogus =
        call(
            target,
            "PATCH",
            "/admin/stores/" + b.store() + "/status",
            "{\"status\":\"BOGUS\"}",
            b.manager());
    assertThat(bogus.status(), is(400));
    assertThat(bogus.code(), is("INVALID_STATUS"));
    assertThat(statusEvents(b, b.store()), hasSize(2));
    assertThat(store(b, b.store()).getString("status"), is("CLOSED"));

    // A cashier cannot close the shop.
    assertThat(
        call(
                target,
                "PATCH",
                "/admin/stores/" + b.store() + "/status",
                "{\"status\":\"ACTIVE\"}",
                b.as("CASHIER"))
            .status(),
        is(403));
    assertThat(store(b, b.store()).getString("status"), is("CLOSED"));
  }

  @Test
  @DisplayName(
      "ONB-310, ONB-312: a zone is added (type defaults to AISLE), listed by cursor, changed and given a status; a cashier cannot")
  void zonesAreMaintained() {
    Biz b = AdminRig.biz(target, "Zoned Ltd", "IN", "INR");
    Who manager = b.manager();
    String zones = "/admin/stores/" + b.store() + "/zones";
    Answer made = call(target, "POST", zones, "{\"name\":\"Aisle 1\",\"code\":\"A1\"}", manager);
    assertThat(made.text(), made.status(), is(201));
    assertThat(made.data().getString("type"), is("AISLE"));
    Answer blankType =
        call(
            target,
            "POST",
            zones,
            "{\"name\":\"Aisle 2\",\"code\":\"A2\",\"type\":\"  \"}",
            manager);
    assertThat(blankType.text(), blankType.status(), is(201));
    assertThat(blankType.data().getString("type"), is("AISLE"));
    Answer cold =
        call(
            target,
            "POST",
            zones,
            "{\"name\":\"Cold\",\"code\":\"C1\",\"type\":\"COLD_ROOM\"}",
            manager);
    assertThat(cold.data().getString("type"), is("COLD_ROOM"));
    assertThat(
        call(target, "POST", zones, "{\"name\":\"\",\"code\":\"X\"}", manager).status(), is(400));
    assertThat(
        call(target, "POST", zones, "{\"name\":\"Aisle 1 again\",\"code\":\"A1\"}", manager)
            .status(),
        is(409));

    // The DEFAULT zone the store came with, and the three: four in all, two at a time.
    List<String> codes = new ArrayList<>();
    String after = null;
    int pages = 0;
    do {
      Answer page =
          call(
              target,
              "GET",
              zones + "?limit=2" + (after == null ? "" : "&after=" + q(after)),
              null,
              manager);
      assertThat(page.text(), page.status(), is(200));
      page.list().forEach(v -> codes.add(v.asJsonObject().getString("code")));
      after = page.next();
      pages++;
    } while (after != null);
    assertThat(pages, is(2));
    assertThat(codes, containsInAnyOrderOf("DEFAULT", "A1", "A2", "C1"));

    String id = made.data().getString("id");
    Answer renamed =
        call(
            target,
            "PUT",
            zones + "/" + id,
            "{\"name\":\"Aisle One\",\"code\":\"A1\",\"type\":\"RACK\"}",
            manager);
    assertThat(renamed.text(), renamed.status(), is(200));
    assertThat(renamed.data().getString("name"), is("Aisle One"));
    assertThat(renamed.data().getString("type"), is("RACK"));
    Answer status =
        call(
            target,
            "PATCH",
            zones + "/" + id + "/status",
            "{\"status\":\"OUT_OF_SERVICE\"}",
            manager);
    assertThat(status.text(), status.status(), is(200));
    assertThat(status.data().getString("status"), is("OUT_OF_SERVICE"));
    assertThat(
        call(target, "GET", zones + "/" + id, null, manager).data().getString("status"),
        is("OUT_OF_SERVICE"));

    Who cashier = b.as("CASHIER");
    assertThat(
        call(target, "POST", zones, "{\"name\":\"Sneaky\",\"code\":\"S1\"}", cashier).status(),
        is(403));
    assertThat(
        call(target, "PUT", zones + "/" + id, "{\"name\":\"Sneaky\",\"code\":\"A1\"}", cashier)
            .status(),
        is(403));
    assertThat(
        call(target, "PATCH", zones + "/" + id + "/status", "{\"status\":\"ACTIVE\"}", cashier)
            .status(),
        is(403));
    assertThat(
        call(target, "GET", zones + "/" + id, null, manager).data().getString("name"),
        is("Aisle One"));
  }

  private static org.hamcrest.Matcher<Iterable<? extends String>> containsInAnyOrderOf(
      String... items) {
    return org.hamcrest.Matchers.containsInAnyOrder(items);
  }

  @Test
  @DisplayName(
      "ONB-311: another business, naming our real zone and store ids under its own or ours, finds no zone and changes none")
  void zonesAreShutToOtherBusinesses() {
    Biz ours = AdminRig.biz(target, "Zones Ours", "IN", "INR");
    Biz rival = AdminRig.biz(target, "Zones Rival", "IN", "INR");
    Answer made =
        call(
            target,
            "POST",
            "/admin/stores/" + ours.store() + "/zones",
            "{\"name\":\"Ours\",\"code\":\"Z1\"}",
            ours.owner());
    String zone = made.data().getString("id");

    for (Who caller : new Who[] {rival.owner(), rival.manager(), rival.manager(ours.store())}) {
      for (String storeId : new String[] {ours.store(), rival.store(), Ids.newId().toString()}) {
        String path = "/admin/stores/" + storeId + "/zones/" + zone;
        Answer get = call(target, "GET", path, null, caller);
        assertThat(get.text(), get.status(), is(404));
        assertThat(get.code(), is("ZONE_NOT_FOUND"));
        assertThat(
            call(target, "PUT", path, "{\"name\":\"Hijacked\",\"code\":\"Z1\"}", caller).status(),
            is(404));
        assertThat(
            call(target, "PATCH", path + "/status", "{\"status\":\"RETIRED\"}", caller).status(),
            is(404));
      }
      assertThat(
          call(target, "GET", "/admin/stores/" + ours.store() + "/zones", null, caller).status(),
          is(404));
      assertThat(
          call(
                  target,
                  "POST",
                  "/admin/stores/" + ours.store() + "/zones",
                  "{\"name\":\"Planted\",\"code\":\"P1\"}",
                  caller)
              .status(),
          is(404));
    }
    Answer stillOurs =
        call(target, "GET", "/admin/stores/" + ours.store() + "/zones/" + zone, null, ours.owner());
    assertThat(stillOurs.data().getString("name"), is("Ours"));
    assertThat(stillOurs.data().getString("status"), not(is("RETIRED")));
    Answer list =
        call(target, "GET", "/admin/stores/" + ours.store() + "/zones", null, ours.owner());
    assertThat(list.list(), hasSize(2));
    assertThat(list.body(), not(containsString("Planted")));
  }

  private List<String> zoneEvents(Biz b, String zoneId) throws Exception {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid"
                    + " AND event_type = 'ZoneStatusChanged' AND aggregate_id = ?::uuid"
                    + " ORDER BY created_at, id")) {
      ps.setString(1, b.tenant());
      ps.setString(2, zoneId);
      var rs = ps.executeQuery();
      while (rs.next()) out.add(rs.getString(1));
    }
    return out;
  }

  private int zoneAudits(Biz b, String zoneId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM tenant.tenant_admin_audit WHERE tenant_id = ?::uuid"
                    + " AND type = 'ZONE_STATUS_CHANGED' AND subject_id = ?::uuid")) {
      ps.setString(1, b.tenant());
      ps.setString(2, zoneId);
      var rs = ps.executeQuery();
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  @DisplayName(
      "workforce-rules slice 9: a zone's status is ACTIVE, OUT_OF_SERVICE or RETIRED; a change is announced once with old and new, audited, and nothing else moves")
  void zoneStatusIsAVocabularyAndAnnounced() throws Exception {
    Biz b = AdminRig.biz(target, "Zone Status Ltd", "DE", "EUR");
    Biz rival = AdminRig.biz(target, "Zone Status Rival", "DE", "EUR");
    String zones = "/admin/stores/" + b.store() + "/zones";
    String zone =
        call(target, "POST", zones, "{\"name\":\"Cold\",\"code\":\"C9\"}", b.owner())
            .data()
            .getString("id");
    String path = zones + "/" + zone + "/status";
    assertThat(zoneEvents(b, zone), hasSize(0));

    Answer bogus = call(target, "PATCH", path, "{\"status\":\"INACTIVE\"}", b.manager());
    assertThat(bogus.text(), bogus.status(), is(400));
    assertThat(bogus.code(), is("ZONE_STATUS_INVALID"));
    assertThat(zoneEvents(b, zone), hasSize(0));

    Answer out = call(target, "PATCH", path, "{\"status\":\"out_of_service\"}", b.manager());
    assertThat(out.text(), out.status(), is(200));
    assertThat(out.data().getString("status"), is("OUT_OF_SERVICE"));
    List<String> events = zoneEvents(b, zone);
    assertThat(events, hasSize(1));
    assertThat(events.get(0), containsString("\"eventId\":\""));
    assertThat(events.get(0), containsString("\"storeId\":\"" + b.store() + "\""));
    assertThat(events.get(0), containsString("\"zoneId\":\"" + zone + "\""));
    assertThat(events.get(0), containsString("\"oldStatus\":\"ACTIVE\""));
    assertThat(events.get(0), containsString("\"newStatus\":\"OUT_OF_SERVICE\""));
    assertThat(zoneAudits(b, zone), is(1));

    // The same status again changes nothing and says nothing.
    assertThat(
        call(target, "PATCH", path, "{\"status\":\"OUT_OF_SERVICE\"}", b.manager()).status(),
        is(200));
    assertThat(zoneEvents(b, zone), hasSize(1));
    assertThat(zoneAudits(b, zone), is(1));

    assertThat(
        call(target, "PATCH", path, "{\"status\":\"RETIRED\"}", b.manager()).status(), is(200));
    List<String> after = zoneEvents(b, zone);
    assertThat(after, hasSize(2));
    assertThat(after.get(1), containsString("\"oldStatus\":\"OUT_OF_SERVICE\""));
    assertThat(after.get(1), containsString("\"newStatus\":\"RETIRED\""));

    // Another business, and a manager held to another store, change nothing and announce nothing.
    String path2 = "/admin/stores/" + b.store() + "/zones/" + zone + "/status";
    for (Who caller : new Who[] {rival.owner(), rival.manager(), rival.manager(b.store())}) {
      assertThat(call(target, "PATCH", path2, "{\"status\":\"ACTIVE\"}", caller).status(), is(404));
    }
    assertThat(
        call(target, "PATCH", path2, "{\"status\":\"ACTIVE\"}", b.manager(Ids.newId().toString()))
            .status(),
        is(403));
    assertThat(zoneEvents(b, zone), hasSize(2));
    assertThat(
        call(target, "GET", zones + "/" + zone, null, b.owner()).data().getString("status"),
        is("RETIRED"));
  }

  @Test
  @DisplayName(
      "STF-104, STF-110: a store nobody has is a 404 and none named is a 400, and nothing is assigned or removed")
  void staffCallsNameAStore() {
    Biz b = AdminRig.biz(target, "Staff Store Ltd", "IN", "INR");
    String user = Ids.newId().toString();
    Answer nobody = assign(target, b.owner(), user, Ids.newId().toString(), "CASHIER");
    assertThat(nobody.text(), nobody.status(), is(404));
    assertThat(nobody.code(), is("STORE_NOT_FOUND"));
    assertThat(assign(target, b.owner(), user, "not-a-uuid", "CASHIER").status(), is(400));
    assertThat(assign(target, b.owner(), "not-a-uuid", b.store(), "CASHIER").status(), is(400));
    assertThat(call(target, "GET", "/admin/staff", null, b.owner()).list(), hasSize(0));

    assertThat(assign(target, b.owner(), user, b.store(), "CASHIER").status(), is(201));
    Answer missing = call(target, "DELETE", "/admin/staff/" + user, null, b.owner());
    assertThat(missing.text(), missing.status(), is(400));
    assertThat(missing.code(), is("MISSING_STORE"));
    assertThat(
        call(target, "DELETE", "/admin/staff/" + user + "?store=", null, b.owner()).code(),
        is("MISSING_STORE"));
    assertThat(
        call(target, "DELETE", "/admin/staff/" + user + "?store=nope", null, b.owner()).status(),
        is(400));
    assertThat(call(target, "GET", "/admin/staff", null, b.owner()).list(), hasSize(1));
  }
}
