package com.storeql.tenant;

import static com.storeql.tenant.AdminRig.assign;
import static com.storeql.tenant.AdminRig.audit;
import static com.storeql.tenant.AdminRig.call;
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
import jakarta.ws.rs.client.WebTarget;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A manager held to stores acts at those stores only. Every per-store write (store, status,
 * till-phone, zones, weighing instruments, delivery areas, staff) is allowed for an owner, for a
 * manager held to none, and for a manager held to that store; a manager held to another store is
 * refused 403 STORE_ACCESS_DENIED with nothing changed and nothing announced; another business is a
 * 404. What belongs to the whole business is for a caller held to no store.
 */
@HelidonTest
class StoreHeldWritesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private long announced(String tenant) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement("SELECT count(*) FROM tenant.outbox WHERE tenant_id = ?::uuid")) {
      ps.setString(1, tenant);
      var rs = ps.executeQuery();
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void denied(Answer a) {
    assertThat(a.text(), a.status(), is(403));
    assertThat(a.code(), is("STORE_ACCESS_DENIED"));
  }

  private static String scale(String serial) {
    return "{\"identifier\":\"S-"
        + serial
        + "\",\"serialNumber\":\""
        + serial
        + "\",\"make\":\"Avery\",\"model\":\"X\",\"kind\":\"COUNTER\",\"maxCapacity\":15,"
        + "\"capacityUom\":\"KG\",\"scaleInterval\":0.005}";
  }

  /** Every per-store write at {@code store}; returns the answers in order. */
  private Answer[] perStoreWrites(Who who, String store, String zone, String tag) {
    String s = "/admin/stores/" + store;
    return new Answer[] {
      call(
          target,
          "PUT",
          s,
          "{\"name\":\"Renamed " + tag + "\",\"timezone\":\"Europe/Paris\",\"tillPhone\":\"OFF\"}",
          who),
      call(target, "PATCH", s + "/status", "{\"status\":\"SUSPENDED\"}", who),
      call(
          target,
          "POST",
          s + "/zones",
          "{\"name\":\"Z " + tag + "\",\"code\":\"" + tag + "\"}",
          who),
      call(
          target,
          "PUT",
          s + "/zones/" + zone,
          "{\"name\":\"Zone " + tag + "\",\"code\":\"DEFAULT\"}",
          who),
      call(target, "PATCH", s + "/zones/" + zone + "/status", "{\"status\":\"RETIRED\"}", who),
      call(target, "POST", s + "/delivery-areas", "{\"pincode\":\"P" + tag + "\"}", who),
      call(target, "POST", s + "/weighing-instruments", scale("SN" + tag), who),
    };
  }

  private String defaultZone(Biz b, String store) {
    Answer z = call(target, "GET", "/admin/stores/" + store + "/zones", null, b.owner());
    return z.list().getJsonObject(0).getString("id");
  }

  @Test
  @DisplayName(
      "Owner, an unrestricted manager and a manager held to the store may write there; a manager held to another store may not, and nothing moves")
  void perStoreWrites() throws Exception {
    Biz b = AdminRig.biz(target, "Held Writes", "FR", "EUR");
    String a = b.store();
    String other = AdminRig.addStore(target, b, "Store B", "FR");
    String zoneA = defaultZone(b, a);
    String zoneB = defaultZone(b, other);

    // Allowed: an owner, a manager held to none, a manager held to A (at A).
    int n = 0;
    for (Who who : new Who[] {b.owner(), b.manager(), b.manager(a)}) {
      String tag = "OK" + n++;
      // Restore the store first so each writer starts from the same place.
      call(target, "PATCH", "/admin/stores/" + a + "/status", "{\"status\":\"ACTIVE\"}", b.owner());
      Answer[] answers = perStoreWrites(who, a, zoneA, tag);
      for (Answer ans : answers) {
        assertThat(who.roles() + "/" + who.stores() + ": " + ans.text(), ans.status() / 100, is(2));
      }
    }

    // Refused: held to A, acting at B, or held to B acting at A. Nothing changes or is announced.
    String beforeB =
        call(target, "GET", "/admin/stores/" + other, null, b.owner()).data().toString();
    String zonesBefore =
        call(target, "GET", "/admin/stores/" + other + "/zones", null, b.owner()).list().toString();
    long events = announced(b.tenant());
    int logged = audit(target, b.owner(), null).size();
    int n2 = 0;
    for (Who who : new Who[] {b.manager(a), b.manager(a, "01a09000-0000-7000-8000-00000000abcd")}) {
      for (Answer ans : perStoreWrites(who, other, zoneB, "NO" + n2++)) denied(ans);
    }
    // A zone of B named through A's path: judged by the zone's own store.
    denied(
        call(
            target,
            "PUT",
            "/admin/stores/" + a + "/zones/" + zoneB,
            "{\"name\":\"Sneaky\",\"code\":\"DEFAULT\"}",
            b.manager(a)));
    denied(
        call(
            target,
            "PATCH",
            "/admin/stores/" + a + "/zones/" + zoneB + "/status",
            "{\"status\":\"RETIRED\"}",
            b.manager(a)));
    assertThat(
        call(target, "GET", "/admin/stores/" + other, null, b.owner()).data().toString(),
        is(beforeB));
    assertThat(
        call(target, "GET", "/admin/stores/" + other + "/zones", null, b.owner()).list().toString(),
        is(zonesBefore));
    assertThat(
        call(target, "GET", "/admin/stores/" + other + "/delivery-areas", null, b.owner()).list(),
        hasSize(0));
    assertThat(announced(b.tenant()), is(events));
    assertThat(audit(target, b.owner(), null), hasSize(logged));
  }

  @Test
  @DisplayName(
      "Another business, even a manager held to its own store, gets 404 on every per-store write and moves nothing")
  void otherBusiness() throws Exception {
    Biz ours = AdminRig.biz(target, "Ours Held", "DE", "EUR");
    Biz rival = AdminRig.biz(target, "Rival Held", "DE", "EUR");
    String zone = defaultZone(ours, ours.store());
    String before =
        call(target, "GET", "/admin/stores/" + ours.store(), null, ours.owner()).data().toString();
    long events = announced(ours.tenant());
    for (Who who :
        new Who[] {
          rival.owner(), rival.manager(), rival.manager(rival.store()), rival.manager(ours.store())
        }) {
      for (Answer ans : perStoreWrites(who, ours.store(), zone, "X")) {
        assertThat(who.roles() + "/" + who.stores() + ": " + ans.text(), ans.status(), is(404));
      }
      assertThat(
          assign(target, who, Ids.newId().toString(), ours.store(), "CASHIER").status(), is(404));
      assertThat(
          call(
                  target,
                  "DELETE",
                  "/admin/staff/" + Ids.newId() + "?store=" + ours.store(),
                  null,
                  who)
              .status(),
          is(404));
    }
    assertThat(
        call(target, "GET", "/admin/stores/" + ours.store(), null, ours.owner()).data().toString(),
        is(before));
    assertThat(announced(ours.tenant()), is(events));
    assertThat(audit(target, ours.owner(), null), hasSize(1));
  }

  @Test
  @DisplayName(
      "Staff: a store-held manager assigns and removes at their own stores only, and never touches the same person's assignment elsewhere")
  void staffWrites() throws Exception {
    Biz b = AdminRig.biz(target, "Held Staff", "IE", "EUR");
    String a = b.store();
    String other = AdminRig.addStore(target, b, "Other", "IE");
    Who heldA = b.manager(a);
    String person = Ids.newId().toString();

    // Owner and unrestricted manager: as before, anywhere.
    assertThat(assign(target, b.owner(), person, other, "CASHIER").status(), is(201));
    assertThat(
        assign(target, b.manager(), Ids.newId().toString(), other, "CASHIER").status(), is(201));
    // Held to A, at A.
    assertThat(assign(target, heldA, person, a, "CASHIER").status(), is(201));
    assertThat(assign(target, heldA, Ids.newId().toString(), a, "STOREKEEPER").status(), is(201));

    // Held to A, at B: refused, and nothing is stored, logged or announced.
    long events = announced(b.tenant());
    int logged = audit(target, b.owner(), null).size();
    String staffBefore =
        call(target, "GET", "/admin/staff?limit=100", null, b.owner()).list().toString();
    denied(assign(target, heldA, Ids.newId().toString(), other, "CASHIER"));
    denied(call(target, "DELETE", "/admin/staff/" + person + "?store=" + other, null, heldA));
    assertThat(announced(b.tenant()), is(events));
    assertThat(audit(target, b.owner(), null), hasSize(logged));
    assertThat(
        call(target, "GET", "/admin/staff?limit=100", null, b.owner()).list().toString(),
        is(staffBefore));

    // Removing at A takes the assignment at A only; the same person at B stays.
    assertThat(
        call(target, "DELETE", "/admin/staff/" + person + "?store=" + a, null, heldA).status(),
        is(200));
    String after = call(target, "GET", "/admin/staff?limit=100", null, b.owner()).body();
    int stillThere = 0;
    for (var v : call(target, "GET", "/admin/staff?limit=100", null, b.owner()).list()) {
      var o = v.asJsonObject();
      if (o.getString("userId").equals(person)) {
        stillThere++;
        assertThat(o.getString("storeId"), is(other));
      }
    }
    assertThat(after, stillThere, is(1));
    // A business-wide (no-store) assignment cannot be made at all: a store is required.
    assertThat(
        call(
                target,
                "POST",
                "/admin/staff",
                "{\"userId\":\"" + person + "\",\"role\":\"CASHIER\"}",
                heldA)
            .status(),
        is(400));
    // The RoleGrants rules still bind on top: a manager held to A cannot make an owner at A.
    assertThat(assign(target, heldA, person, a, "OWNER").code(), is("STAFF_OWNER_TIER_OWNER_ONLY"));
  }

  @Test
  @DisplayName("What belongs to the whole business is for a caller held to no store")
  void businessWide() throws Exception {
    Biz b = AdminRig.biz(target, "Wide Ltd", "GB", "GBP");
    Who held = b.manager(b.store());
    long events = announced(b.tenant());
    int logged = audit(target, b.owner(), null).size();

    for (Answer ans :
        new Answer[] {
          call(target, "PUT", "/admin/tenant", "{\"businessName\":\"Held Renamed\"}", held),
          call(
              target,
              "POST",
              "/admin/stores",
              "{\"name\":\"New\",\"code\":\"N-"
                  + AdminRig.tail()
                  + "\",\"timezone\":\"Europe/London\"}",
              held),
          call(
              target,
              "POST",
              "/admin/roles",
              "{\"code\":\"HELD\",\"name\":\"h\",\"baseTier\":\"CASHIER\",\"permissions\":[]}",
              held),
          call(
              target,
              "PUT",
              "/admin/tenant/fx-rates/USD",
              "{\"rate\":0.79,\"reason\":\"held\"}",
              held),
          call(target, "PUT", "/admin/inventory-config", "{\"lotControlEnabled\":true}", held),
          call(target, "PUT", "/admin/tenant/retention/ORDERS", "{\"periodDays\":4000}", held),
          call(target, "POST", "/admin/tenant/billing/cancel", "{}", held),
          call(
              target,
              "POST",
              "/admin/tenant/billing/plan",
              "{\"planId\":\"" + Ids.newId() + "\",\"when\":\"NOW\"}",
              held),
        }) {
      assertThat(ans.text(), ans.status(), is(403));
      assertThat(ans.text(), ans.code(), is("BUSINESS_WIDE_ONLY"));
    }
    assertThat(
        call(target, "GET", "/admin/tenant", null, b.owner()).data().getString("name"),
        not(is("Held Renamed")));
    assertThat(call(target, "GET", "/admin/stores", null, b.owner()).list(), hasSize(1));
    assertThat(call(target, "GET", "/admin/roles/HELD", null, b.owner()).status(), is(404));
    assertThat(announced(b.tenant()), is(events));
    assertThat(audit(target, b.owner(), null), hasSize(logged));

    // Owner and an unrestricted manager: as before.
    assertThat(
        call(target, "PUT", "/admin/tenant", "{\"businessName\":\"Owner Renamed\"}", b.owner())
            .status(),
        is(200));
    assertThat(
        call(target, "PUT", "/admin/tenant", "{\"businessName\":\"Mgr Renamed\"}", b.manager())
            .status(),
        is(200));
    assertThat(
        call(
                target,
                "POST",
                "/admin/stores",
                "{\"name\":\"New\",\"code\":\"W-"
                    + AdminRig.tail()
                    + "\",\"timezone\":\"Europe/London\"}",
                b.manager())
            .status(),
        is(201));
    assertThat(
        call(
                target,
                "POST",
                "/admin/roles",
                "{\"code\":\"WIDE\",\"name\":\"w\",\"baseTier\":\"CASHIER\",\"permissions\":[]}",
                b.manager())
            .status(),
        is(201));
    // The rest are gated on other things (a plan, a rate's validity); they are not this refusal.
    for (Answer ans :
        new Answer[] {
          call(
              target,
              "PUT",
              "/admin/tenant/fx-rates/USD",
              "{\"rate\":0.79,\"reason\":\"ok\"}",
              b.manager()),
          call(
              target,
              "PUT",
              "/admin/inventory-config",
              "{\"lotControlEnabled\":true}",
              b.manager()),
          call(
              target,
              "PUT",
              "/admin/tenant/fx-rates/USD",
              "{\"rate\":0.79,\"reason\":\"ok\"}",
              b.owner()),
        }) {
      assertThat(ans.text(), ans.body(), not(containsString("BUSINESS_WIDE_ONLY")));
    }
  }

  @Test
  @DisplayName(
      "Reads stay scoped: a store-held manager reads staff only at their stores plus business-wide, and the store list is unchanged")
  void readsStayScoped() {
    Biz b = AdminRig.biz(target, "Held Reads", "NL", "EUR");
    String other = AdminRig.addStore(target, b, "Other", "NL");
    String atA = Ids.newId().toString();
    String atB = Ids.newId().toString();
    assertThat(assign(target, b.owner(), atA, b.store(), "CASHIER").status(), is(201));
    assertThat(assign(target, b.owner(), atB, other, "CASHIER").status(), is(201));
    String seen = call(target, "GET", "/admin/staff?limit=100", null, b.manager(b.store())).body();
    assertThat(seen, containsString(atA));
    assertThat(seen, not(containsString(atB)));
    String all = call(target, "GET", "/admin/staff?limit=100", null, b.manager()).body();
    assertThat(all, containsString(atA));
    assertThat(all, containsString(atB));
    Answer rival =
        call(
            target,
            "GET",
            "/admin/staff?limit=100",
            null,
            AdminRig.biz(target, "Rival Reads", "NL", "EUR").owner());
    assertThat(rival.body(), not(containsString(atA)));
  }
}
