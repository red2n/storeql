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
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A business-wide staff assignment (head office): a MANAGER-tier role with no store, granted and
 * removed by an owner alone. The person is then held to no store, so they change what belongs to
 * the whole business and act at any store; a manager held to stores still cannot. (The token that
 * makes them so is iam-svc's; here the caller is stamped as the gateway would stamp it.)
 */
@HelidonTest
class BusinessWideStaffIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static String wide(String user, String role) {
    return "{\"userId\":\"" + user + "\",\"businessWide\":true,\"role\":\"" + role + "\"}";
  }

  private Answer grant(Who who, String user, String role) {
    return call(target, "POST", "/admin/staff", wide(user, role), who);
  }

  private Answer revoke(Who who, String user) {
    return call(target, "DELETE", "/admin/staff/" + user + "?businessWide=true", null, who);
  }

  private List<String> events(String tenant, String type) throws Exception {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid AND event_type = ?"
                    + " ORDER BY created_at, id")) {
      ps.setString(1, tenant);
      ps.setString(2, type);
      var rs = ps.executeQuery();
      while (rs.next()) out.add(rs.getString(1));
    }
    return out;
  }

  private JsonObject entryFor(Who who, String user) {
    Answer a = call(target, "GET", "/admin/staff?limit=100", null, who);
    assertThat(a.text(), a.status(), is(200));
    for (var v : a.list()) {
      if (v.asJsonObject().getString("userId").equals(user)) return v.asJsonObject();
    }
    return null;
  }

  @Test
  @DisplayName(
      "An owner grants a business-wide manager: the event says so and names no store; the staff list, the audit and the removal agree")
  void anOwnerGrantsAndRemoves() throws Exception {
    Biz b = AdminRig.biz(target, "Head Office", "GB", "GBP");
    String user = Ids.newId().toString();
    Answer made = grant(b.owner(), user, "MANAGER");
    assertThat(made.text(), made.status(), is(201));

    List<String> assigned = events(b.tenant(), "StaffAssigned");
    assertThat(assigned, hasSize(1));
    assertThat(assigned.get(0), containsString("\"businessWide\":true"));
    assertThat(assigned.get(0), not(containsString("storeId")));
    assertThat(assigned.get(0), containsString("\"role\":\"MANAGER\""));

    // Read as such: no store, to the owner, an unrestricted manager and a store-held manager.
    for (Who reader : new Who[] {b.owner(), b.manager(), b.manager(b.store())}) {
      JsonObject e = entryFor(reader, user);
      assertThat(reader.roles() + "/" + reader.stores(), e != null, is(true));
      assertThat(e.containsKey("storeId"), is(false));
      assertThat(e.getBoolean("businessWide"), is(true));
      assertThat(e.getString("role"), is("MANAGER"));
      assertThat(e.getString("baseTier"), is("MANAGER"));
    }
    // A store assignment is not business-wide.
    String clerk = Ids.newId().toString();
    assertThat(assign(target, b.owner(), clerk, b.store(), "CASHIER").status(), is(201));
    assertThat(entryFor(b.owner(), clerk).getBoolean("businessWide"), is(false));

    // The audit records the grant, with no store, visible to a store-held manager too.
    for (Who reader : new Who[] {b.owner(), b.manager(b.store())}) {
      List<JsonObject> log = audit(target, reader, "type=STAFF_ASSIGNED");
      JsonObject grantEntry = null;
      for (JsonObject e : log) if (user.equals(e.getString("subjectId", null))) grantEntry = e;
      assertThat(grantEntry != null, is(true));
      assertThat(grantEntry.containsKey("storeId"), is(false));
      assertThat(grantEntry.getString("to"), is("MANAGER (business-wide)"));
      assertThat(grantEntry.getString("actorId"), is(b.ownerId()));
    }

    // The same again is refused; nothing more is announced.
    assertThat(grant(b.owner(), user, "MANAGER").status(), is(409));
    assertThat(events(b.tenant(), "StaffAssigned"), hasSize(2));

    // Removal: the owner's, announced with the marker, logged, and gone from the list.
    Answer gone = revoke(b.owner(), user);
    assertThat(gone.text(), gone.status(), is(200));
    List<String> removed = events(b.tenant(), "StaffRemoved");
    assertThat(removed, hasSize(1));
    assertThat(removed.get(0), containsString("\"businessWide\":true"));
    assertThat(removed.get(0), not(containsString("storeId")));
    assertThat(entryFor(b.owner(), user) == null, is(true));
    JsonObject unassigned = audit(target, b.owner(), "type=STAFF_UNASSIGNED").get(0);
    assertThat(unassigned.getString("from"), is("MANAGER (business-wide)"));
    assertThat(unassigned.containsKey("storeId"), is(false));
    // Again: nothing to remove, nothing announced or logged.
    assertThat(revoke(b.owner(), user).status(), is(200));
    assertThat(events(b.tenant(), "StaffRemoved"), hasSize(1));
    assertThat(audit(target, b.owner(), "type=STAFF_UNASSIGNED"), hasSize(1));
  }

  @Test
  @DisplayName(
      "Only an owner grants or removes it: managers (held or not), and every lower role, are refused and nothing changes")
  void onlyAnOwner() throws Exception {
    Biz b = AdminRig.biz(target, "Owner Only Wide", "GB", "GBP");
    String user = Ids.newId().toString();
    String other = Ids.newId().toString();
    for (Who who : new Who[] {b.manager(), b.manager(b.store())}) {
      Answer a = grant(who, user, "MANAGER");
      assertThat(who.stores() + ": " + a.text(), a.status(), is(403));
      assertThat(a.code(), is("STAFF_BUSINESS_WIDE_OWNER_ONLY"));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertThat(role, grant(b.as(role), user, "MANAGER").status(), is(403));
    }
    assertThat(entryFor(b.owner(), user) == null, is(true));
    assertThat(events(b.tenant(), "StaffAssigned"), hasSize(0));

    assertThat(grant(b.owner(), other, "MANAGER").status(), is(201));
    for (Who who : new Who[] {b.manager(), b.manager(b.store())}) {
      Answer a = revoke(who, other);
      assertThat(a.text(), a.status(), is(403));
      assertThat(a.code(), is("STAFF_BUSINESS_WIDE_OWNER_ONLY"));
    }
    assertThat(revoke(b.as("CASHIER"), other).status(), is(403));
    assertThat(entryFor(b.owner(), other) != null, is(true));
    assertThat(events(b.tenant(), "StaffRemoved"), hasSize(0));
  }

  @Test
  @DisplayName(
      "Only the MANAGER tier goes business-wide (built-in, or a custom role standing on it); a store may not be named with it")
  void onlyTheManagerTier() throws Exception {
    Biz b = AdminRig.biz(target, "Wide Tiers", "GB", "GBP");
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "OWNER"}) {
      Answer a = grant(b.owner(), Ids.newId().toString(), role);
      assertThat(role + ": " + a.text(), a.status(), is(400));
      assertThat(a.code(), is("STAFF_BUSINESS_WIDE_TIER"));
    }
    assertThat(
        call(
                target,
                "POST",
                "/admin/roles",
                "{\"code\":\"TILL_LEAD\",\"name\":\"t\",\"baseTier\":\"CASHIER\",\"permissions\":[]}",
                b.owner())
            .status(),
        is(201));
    assertThat(
        grant(b.owner(), Ids.newId().toString(), "TILL_LEAD").code(),
        is("STAFF_BUSINESS_WIDE_TIER"));
    assertThat(
        call(
                target,
                "POST",
                "/admin/roles",
                "{\"code\":\"HEAD_OFFICE\",\"name\":\"h\",\"baseTier\":\"MANAGER\",\"permissions\":[\"finance.journal\"]}",
                b.owner())
            .status(),
        is(201));
    String user = Ids.newId().toString();
    Answer custom = grant(b.owner(), user, "head_office");
    assertThat(custom.text(), custom.status(), is(201));
    assertThat(
        events(b.tenant(), "StaffAssigned").get(0), containsString("\"roleCode\":\"HEAD_OFFICE\""));
    assertThat(events(b.tenant(), "StaffAssigned").get(0), containsString("\"businessWide\":true"));
    assertThat(entryFor(b.owner(), user).getString("role"), is("HEAD_OFFICE"));

    Answer both =
        call(
            target,
            "POST",
            "/admin/staff",
            "{\"userId\":\""
                + Ids.newId()
                + "\",\"storeId\":\""
                + b.store()
                + "\",\"businessWide\":true,\"role\":\"MANAGER\"}",
            b.owner());
    assertThat(both.text(), both.status(), is(400));
    assertThat(both.code(), is("STAFF_STORE_AND_BUSINESS_WIDE"));
    // Neither a store nor the flag is still refused, as ever.
    Answer neither =
        call(
            target,
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + Ids.newId() + "\",\"role\":\"MANAGER\"}",
            b.owner());
    assertThat(neither.code(), is("STAFF_STORE_REQUIRED"));
    Answer bothRemove =
        call(
            target,
            "DELETE",
            "/admin/staff/" + user + "?businessWide=true&store=" + b.store(),
            null,
            b.owner());
    assertThat(bothRemove.code(), is("STAFF_STORE_AND_BUSINESS_WIDE"));
    assertThat(
        call(target, "DELETE", "/admin/staff/" + user, null, b.owner()).code(),
        is("MISSING_STORE"));
  }

  @Test
  @DisplayName(
      "Held to no store, a business-wide manager changes the business's settings and acts at every store; a store-held manager still cannot; per-store assignments are independent")
  void whatItOpens() throws Exception {
    Biz b = AdminRig.biz(target, "Wide Reach", "GB", "GBP");
    String second = AdminRig.addStore(target, b, "Second", "GB");
    String person = Ids.newId().toString();
    assertThat(grant(b.owner(), person, "MANAGER").status(), is(201));
    // A store assignment beside it, then taken away: the business-wide one stays.
    assertThat(assign(target, b.owner(), person, b.store(), "MANAGER").status(), is(201));
    assertThat(
        call(target, "DELETE", "/admin/staff/" + person + "?store=" + b.store(), null, b.owner())
            .status(),
        is(200));
    assertThat(entryFor(b.owner(), person).getBoolean("businessWide"), is(true));

    // The token iam mints for them carries no store ids: an unrestricted manager.
    Who head = new Who(b.tenant(), person, "MANAGER", null, null);
    assertThat(
        call(target, "PUT", "/admin/tenant", "{\"businessName\":\"Head Office Renamed\"}", head)
            .status(),
        is(200));
    assertThat(
        call(
                target,
                "POST",
                "/admin/roles",
                "{\"code\":\"SHIFT\",\"name\":\"s\",\"baseTier\":\"CASHIER\",\"permissions\":[]}",
                head)
            .status(),
        is(201));
    for (String store : new String[] {b.store(), second}) {
      Answer zone =
          call(
              target,
              "POST",
              "/admin/stores/" + store + "/zones",
              "{\"name\":\"Wide\",\"code\":\"W-" + AdminRig.tail() + "\"}",
              head);
      assertThat(zone.text(), zone.status(), is(201));
      assertThat(
          call(
                  target,
                  "PATCH",
                  "/admin/stores/" + store + "/status",
                  "{\"status\":\"ACTIVE\"}",
                  head)
              .status(),
          is(200));
    }
    // A store-held manager still cannot.
    Who held = b.manager(b.store());
    assertThat(
        call(target, "PUT", "/admin/tenant", "{\"businessName\":\"Nope\"}", held).code(),
        is("BUSINESS_WIDE_ONLY"));
    assertThat(
        call(
                target,
                "POST",
                "/admin/stores/" + second + "/zones",
                "{\"name\":\"Nope\",\"code\":\"NOPE\"}",
                held)
            .code(),
        is("STORE_ACCESS_DENIED"));

    // Removal puts them back to nothing: their next token names no role, and the list has no entry.
    assertThat(revoke(b.owner(), person).status(), is(200));
    assertThat(entryFor(b.owner(), person) == null, is(true));
  }

  @Test
  @DisplayName(
      "Another business is untouched: it cannot see, grant into or remove ours, and the same person can be head office of both")
  void otherBusinessesAreUntouched() throws Exception {
    Biz ours = AdminRig.biz(target, "Wide Ours", "GB", "GBP");
    Biz rival = AdminRig.biz(target, "Wide Rival", "GB", "GBP");
    String person = Ids.newId().toString();
    assertThat(grant(ours.owner(), person, "MANAGER").status(), is(201));

    // The rival's staff list and audit hold nothing of ours.
    assertThat(entryFor(rival.owner(), person) == null, is(true));
    assertThat(audit(target, rival.owner(), "type=STAFF_ASSIGNED"), hasSize(0));
    // The rival's removal of the same person removes nothing of ours and logs nothing of ours.
    assertThat(revoke(rival.owner(), person).status(), is(200));
    assertThat(entryFor(ours.owner(), person) != null, is(true));
    assertThat(events(ours.tenant(), "StaffRemoved"), hasSize(0));
    assertThat(audit(target, rival.owner(), "type=STAFF_UNASSIGNED"), hasSize(0));
    // Their manager and a manager held to our store, naming ours, are refused or find nothing.
    assertThat(revoke(rival.manager(), person).code(), is("STAFF_BUSINESS_WIDE_OWNER_ONLY"));
    assertThat(
        revoke(rival.manager(ours.store()), person).code(), is("STAFF_BUSINESS_WIDE_OWNER_ONLY"));
    assertThat(entryFor(ours.owner(), person) != null, is(true));

    // The rival's own grant of the same person is its own.
    assertThat(grant(rival.owner(), person, "MANAGER").status(), is(201));
    assertThat(revoke(rival.owner(), person).status(), is(200));
    assertThat(entryFor(ours.owner(), person) != null, is(true));
    assertThat(events(ours.tenant(), "StaffAssigned"), hasSize(1));
  }
}
