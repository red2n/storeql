package com.storeql.tenant;

import static com.storeql.tenant.AdminRig.assign;
import static com.storeql.tenant.AdminRig.audit;
import static com.storeql.tenant.AdminRig.call;
import static com.storeql.tenant.AdminRig.q;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who changed what (flow catalogue onb-stores-zones-hours gap 1, stf-staff-assignment-and-roles gap
 * 2): a store's status, its till-phone setting, who was given or lost a role and what a role holds
 * leave an entry on the transaction of the change, and management reads them newest first — a
 * manager held to stores reading only theirs and the business-wide ones.
 */
@HelidonTest
class AdminAuditIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static List<String> types(List<JsonObject> entries) {
    List<String> out = new ArrayList<>();
    for (JsonObject e : entries) out.add(e.getString("type"));
    return out;
  }

  private static String str(JsonObject o, String key) {
    return !o.containsKey(key) || o.isNull(key) ? null : o.getString(key);
  }

  @Test
  @DisplayName(
      "A store's status and till-phone changes are logged with who, when and from -> to; an unchanged till setting is not")
  void storeChangesAreLogged() {
    Biz b = AdminRig.biz(target, "Audit Stores", "FR", "EUR");
    Who manager = b.manager();
    String shop = b.store();

    Answer status =
        call(
            target,
            "PATCH",
            "/admin/stores/" + shop + "/status",
            "{\"status\":\"SUSPENDED\"}",
            manager);
    assertThat(status.text(), status.status(), is(200));
    Answer phone =
        call(
            target,
            "PUT",
            "/admin/stores/" + shop,
            "{\"name\":\"Main\",\"timezone\":\"Europe/Paris\",\"tillPhone\":\"REQUIRED\"}",
            manager);
    assertThat(phone.text(), phone.status(), is(200));
    // The same setting again, and one omitted: nothing moved, nothing is logged.
    assertThat(
        call(
                target,
                "PUT",
                "/admin/stores/" + shop,
                "{\"name\":\"Main again\",\"timezone\":\"Europe/Paris\",\"tillPhone\":\"REQUIRED\"}",
                manager)
            .status(),
        is(200));
    assertThat(
        call(
                target,
                "PUT",
                "/admin/stores/" + shop,
                "{\"name\":\"Main\",\"timezone\":\"Europe/Paris\"}",
                manager)
            .status(),
        is(200));
    // A refused change leaves no entry.
    assertThat(
        call(
                target,
                "PATCH",
                "/admin/stores/" + shop + "/status",
                "{\"status\":\"BOGUS\"}",
                manager)
            .status(),
        is(400));
    assertThat(
        call(
                target,
                "PUT",
                "/admin/stores/" + shop,
                "{\"name\":\"Main\",\"timezone\":\"Europe/Paris\",\"tillPhone\":\"SOMETIMES\"}",
                manager)
            .status(),
        is(400));
    Answer back =
        call(
            target,
            "PATCH",
            "/admin/stores/" + shop + "/status",
            "{\"status\":\"ACTIVE\"}",
            manager);
    assertThat(back.status(), is(200));

    List<JsonObject> log = audit(target, b.owner(), null);
    assertThat(
        types(log),
        contains(
            "STORE_STATUS_CHANGED",
            "STORE_TILL_PHONE_CHANGED",
            "STORE_STATUS_CHANGED",
            "STORE_CREATED"));
    JsonObject reopened = log.get(0);
    assertThat(str(reopened, "from"), is("SUSPENDED"));
    assertThat(str(reopened, "to"), is("ACTIVE"));
    assertThat(str(reopened, "storeId"), is(shop));
    assertThat(str(reopened, "actorId"), is(manager.user()));
    Instant at = Instant.parse(reopened.getString("occurredAt"));
    assertThat(at.isAfter(Instant.now().minus(5, ChronoUnit.MINUTES)), is(true));
    JsonObject till = log.get(1);
    assertThat(str(till, "from"), is("OPTIONAL"));
    assertThat(str(till, "to"), is("REQUIRED"));
    assertThat(str(till, "actorId"), is(manager.user()));
    JsonObject suspended = log.get(2);
    assertThat(str(suspended, "from"), is("ACTIVE"));
    assertThat(str(suspended, "to"), is("SUSPENDED"));
    JsonObject created = log.get(3);
    assertThat(str(created, "to"), is("STORE"));
    assertThat(str(created, "actorId"), is(b.ownerId()));
    assertThat(created.getString("id"), not(is(reopened.getString("id"))));
  }

  @Test
  @DisplayName(
      "Staff assigned and unassigned, and a custom role defined, changed and deleted, are logged; a business-wide role has no store")
  void staffAndRoleChangesAreLogged() {
    Biz b = AdminRig.biz(target, "Audit Staff", "GB", "GBP");
    String user = Ids.newId().toString();
    String trainee = Ids.newId().toString();
    assertThat(assign(target, b.owner(), user, b.store(), "CASHIER").status(), is(201));
    Answer defined =
        call(
            target,
            "POST",
            "/admin/roles",
            "{\"code\":\"TRAINEE\",\"name\":\"Trainee\",\"baseTier\":\"CASHIER\",\"permissions\":[\"till.no_sale\"]}",
            b.owner());
    assertThat(defined.text(), defined.status(), is(201));
    Answer changed =
        call(
            target,
            "PUT",
            "/admin/roles/TRAINEE",
            "{\"name\":\"Trainee\",\"permissions\":[],\"description\":\"\"}",
            b.owner());
    assertThat(changed.text(), changed.status(), is(200));
    assertThat(assign(target, b.owner(), trainee, b.store(), "TRAINEE").status(), is(201));
    // The same role twice is refused, and leaves no second entry.
    assertThat(assign(target, b.owner(), trainee, b.store(), "TRAINEE").status(), is(409));
    assertThat(
        call(target, "DELETE", "/admin/staff/" + trainee + "?store=" + b.store(), null, b.owner())
            .status(),
        is(200));
    // Removing again removes nothing, so nothing is logged for it.
    assertThat(
        call(target, "DELETE", "/admin/staff/" + trainee + "?store=" + b.store(), null, b.owner())
            .status(),
        is(200));
    assertThat(call(target, "DELETE", "/admin/roles/TRAINEE", null, b.owner()).status(), is(204));
    // A role that does not exist: refused, and not logged.
    assertThat(call(target, "DELETE", "/admin/roles/TRAINEE", null, b.owner()).status(), is(404));

    List<JsonObject> log = audit(target, b.owner(), null);
    assertThat(
        types(log),
        contains(
            "ROLE_DELETED",
            "STAFF_UNASSIGNED",
            "STAFF_ASSIGNED",
            "ROLE_CHANGED",
            "ROLE_DEFINED",
            "STAFF_ASSIGNED",
            "STORE_CREATED"));
    JsonObject deleted = log.get(0);
    assertThat(deleted.getString("subjectCode"), is("TRAINEE"));
    assertThat(str(deleted, "storeId"), is(nullValue()));
    assertThat(str(deleted, "from"), is("Trainee (CASHIER): "));
    JsonObject unassigned = log.get(1);
    assertThat(str(unassigned, "subjectId"), is(trainee));
    assertThat(str(unassigned, "storeId"), is(b.store()));
    assertThat(str(unassigned, "from"), is("TRAINEE"));
    assertThat(str(unassigned, "to"), is(nullValue()));
    assertThat(str(unassigned, "actorId"), is(b.ownerId()));
    JsonObject assignedTrainee = log.get(2);
    assertThat(str(assignedTrainee, "to"), is("TRAINEE"));
    assertThat(str(assignedTrainee, "subjectId"), is(trainee));
    JsonObject roleChanged = log.get(3);
    assertThat(str(roleChanged, "from"), is("Trainee (CASHIER): till.no_sale"));
    assertThat(str(roleChanged, "to"), is("Trainee (CASHIER): "));
    JsonObject roleDefined = log.get(4);
    assertThat(str(roleDefined, "from"), is(nullValue()));
    assertThat(str(roleDefined, "to"), is("Trainee (CASHIER): till.no_sale"));
    JsonObject cashier = log.get(5);
    assertThat(str(cashier, "subjectId"), is(user));
    assertThat(str(cashier, "to"), is("CASHIER"));
  }

  @Test
  @DisplayName(
      "The log is filtered by type, actor, store and period, newest first, and walked by cursor")
  void filtersAndPaging() {
    Biz b = AdminRig.biz(target, "Audit Filters", "DE", "EUR");
    String second = AdminRig.addStore(target, b, "Second", "DE");
    Who manager = b.manager();
    String u1 = Ids.newId().toString();
    String u2 = Ids.newId().toString();
    assertThat(assign(target, b.owner(), u1, b.store(), "CASHIER").status(), is(201));
    assertThat(assign(target, manager, u2, second, "STOREKEEPER").status(), is(201));
    assertThat(
        call(
                target,
                "PATCH",
                "/admin/stores/" + second + "/status",
                "{\"status\":\"CLOSED\"}",
                manager)
            .status(),
        is(200));

    List<JsonObject> all = audit(target, b.owner(), null);
    // Two STORE_CREATED, two STAFF_ASSIGNED, one STORE_STATUS_CHANGED.
    assertThat(all, hasSize(5));
    List<String> stamps = new ArrayList<>();
    for (JsonObject e : all) stamps.add(e.getString("occurredAt"));
    List<String> newestFirst = new ArrayList<>(stamps);
    newestFirst.sort(java.util.Comparator.reverseOrder());
    assertThat(stamps, is(newestFirst));

    assertThat(
        types(audit(target, b.owner(), "type=staff_assigned")),
        contains("STAFF_ASSIGNED", "STAFF_ASSIGNED"));
    assertThat(audit(target, b.owner(), "actor=" + manager.user()), hasSize(2));
    assertThat(audit(target, b.owner(), "actor=" + b.ownerId()), hasSize(3));
    assertThat(audit(target, b.owner(), "store=" + second), hasSize(3));
    assertThat(audit(target, b.owner(), "store=" + b.store() + "&type=STAFF_ASSIGNED"), hasSize(1));

    Instant now = Instant.now();
    assertThat(
        audit(target, b.owner(), "from=" + q(now.plus(1, ChronoUnit.HOURS).toString())),
        hasSize(0));
    assertThat(
        audit(target, b.owner(), "to=" + q(now.minus(1, ChronoUnit.HOURS).toString())), hasSize(0));
    assertThat(
        audit(
            target,
            b.owner(),
            "from="
                + q(now.minus(1, ChronoUnit.HOURS).toString())
                + "&to="
                + q(now.plus(1, ChronoUnit.HOURS).toString())),
        hasSize(5));

    // Cursor: two at a time, every entry once, none repeated.
    List<String> ids = new ArrayList<>();
    String after = null;
    int pages = 0;
    do {
      Answer page =
          call(
              target,
              "GET",
              "/admin/tenant/audit?limit=2" + (after == null ? "" : "&after=" + q(after)),
              null,
              b.owner());
      assertThat(page.text(), page.status(), is(200));
      page.list().forEach(v -> ids.add(v.asJsonObject().getString("id")));
      after = page.next();
      pages++;
    } while (after != null);
    assertThat(pages, is(3));
    assertThat(ids, hasSize(5));
    assertThat(new java.util.HashSet<>(ids), hasSize(5));
    List<String> full = new ArrayList<>();
    for (JsonObject e : all) full.add(e.getString("id"));
    assertThat(ids, is(full));

    // Every way of asking wrongly is refused by name.
    assertThat(
        call(target, "GET", "/admin/tenant/audit?type=NOPE", null, b.owner()).code(),
        is("AUDIT_TYPE_INVALID"));
    Answer range =
        call(
            target,
            "GET",
            "/admin/tenant/audit?from="
                + q("2026-02-01T00:00:00Z")
                + "&to="
                + q("2026-01-01T00:00:00Z"),
            null,
            b.owner());
    assertThat(range.text(), range.status(), is(400));
    assertThat(range.code(), is("AUDIT_RANGE_INVALID"));
    assertThat(
        call(target, "GET", "/admin/tenant/audit?actor=not-a-uuid", null, b.owner()).status(),
        is(400));
    assertThat(
        call(target, "GET", "/admin/tenant/audit?store=" + UUID_V4, null, b.owner()).status(),
        is(400));
    assertThat(
        call(target, "GET", "/admin/tenant/audit?from=yesterday", null, b.owner()).status(),
        is(400));
  }

  private static final String UUID_V4 = "3f2b8c1e-9d4a-4b7e-8c21-5a6d7e8f9012";

  @Test
  @DisplayName(
      "A manager held to a store reads that store's entries and the business-wide ones, never another store's, and cannot name one")
  void storeHeldManagerReadsOnlyTheirs() {
    Biz b = AdminRig.biz(target, "Audit Held", "IE", "EUR");
    String other = AdminRig.addStore(target, b, "Other", "IE");
    String atMain = Ids.newId().toString();
    String atOther = Ids.newId().toString();
    assertThat(assign(target, b.owner(), atMain, b.store(), "CASHIER").status(), is(201));
    assertThat(assign(target, b.owner(), atOther, other, "CASHIER").status(), is(201));
    assertThat(
        call(
                target,
                "PATCH",
                "/admin/stores/" + other + "/status",
                "{\"status\":\"CLOSED\"}",
                b.owner())
            .status(),
        is(200));
    assertThat(
        call(
                target,
                "POST",
                "/admin/roles",
                "{\"code\":\"SHIFT\",\"name\":\"Shift\",\"baseTier\":\"CASHIER\",\"permissions\":[]}",
                b.owner())
            .status(),
        is(201));

    Who held = b.manager(b.store());
    List<JsonObject> mine = audit(target, held, null);
    for (JsonObject e : mine) {
      assertThat(
          "an entry at the other store is never shown: " + e, str(e, "storeId"), not(is(other)));
    }
    assertThat(types(mine), contains("ROLE_DEFINED", "STAFF_ASSIGNED", "STORE_CREATED"));
    assertThat(mine.get(1).getString("subjectId"), is(atMain));
    // Naming the other store is refused, whatever else is asked.
    Answer named = call(target, "GET", "/admin/tenant/audit?store=" + other, null, held);
    assertThat(named.text(), named.status(), is(403));
    assertThat(named.code(), is("STORE_ACCESS_DENIED"));
    // Naming their own works.
    assertThat(audit(target, held, "store=" + b.store()), hasSize(2));
    // Held to both, reads both.
    assertThat(audit(target, b.manager(b.store(), other), null), hasSize(6));
    // The owner and a business-wide manager read everything.
    assertThat(audit(target, b.owner(), null), hasSize(6));
    assertThat(audit(target, b.manager(), null), hasSize(6));
  }

  @Test
  @DisplayName("Management reads the log; a storekeeper, a cashier and a shopper are refused")
  void onlyManagementReads() {
    Biz b = AdminRig.biz(target, "Audit Gate", "NL", "EUR");
    for (String role : List.of("OWNER", "MANAGER")) {
      assertThat(call(target, "GET", "/admin/tenant/audit", null, b.as(role)).status(), is(200));
    }
    for (String role : List.of("STOREKEEPER", "CASHIER", "CUSTOMER")) {
      Answer a = call(target, "GET", "/admin/tenant/audit", null, b.as(role));
      assertThat(role + ": " + a.text(), a.status(), is(403));
    }
    assertThat(
        call(
                target,
                "GET",
                "/admin/tenant/audit",
                null,
                new Who(b.tenant(), null, null, null, null))
            .status(),
        is(403));
  }

  @Test
  @DisplayName(
      "Another business's owner and manager, even naming our store and our people, read nothing of ours and change nothing")
  void otherBusinessesSeeNothing() {
    Biz ours = AdminRig.biz(target, "Audit Ours", "SE", "SEK");
    Biz rival = AdminRig.biz(target, "Audit Rival", "NO", "NOK");
    String user = Ids.newId().toString();
    assertThat(assign(target, ours.owner(), user, ours.store(), "CASHIER").status(), is(201));
    int before = audit(target, ours.owner(), null).size();
    assertThat(before, is(2));

    // The rival reads its own log only: its one STORE_CREATED.
    for (Who rivalCaller :
        List.of(
            rival.owner(),
            rival.manager(),
            rival.manager(ours.store()),
            rival.manager(rival.store(), ours.store()))) {
      List<JsonObject> read = audit(target, rivalCaller, null);
      // A manager held to our store (naming none of theirs) reads none of the rival's own entries.
      if (rivalCaller.stores() != null && !rivalCaller.stores().contains(rival.store())) {
        assertThat(read, hasSize(0));
      } else {
        assertThat(types(read), contains("STORE_CREATED"));
      }
      // Naming our store, our person or our actor finds nothing.
      assertThat(audit(target, rivalCaller.withStores(null), "store=" + ours.store()), hasSize(0));
      assertThat(
          audit(target, rivalCaller.withStores(null), "actor=" + ours.ownerId()), hasSize(0));
    }
    // The rival cannot act on our store, and nothing of ours moved or was logged.
    Answer status =
        call(
            target,
            "PATCH",
            "/admin/stores/" + ours.store() + "/status",
            "{\"status\":\"CLOSED\"}",
            rival.owner());
    assertThat(status.text(), status.status(), is(404));
    Answer till =
        call(
            target,
            "PUT",
            "/admin/stores/" + ours.store(),
            "{\"name\":\"Hijacked\",\"timezone\":\"Europe/Oslo\",\"tillPhone\":\"OFF\"}",
            rival.owner());
    assertThat(till.text(), till.status(), is(404));
    Answer assigned = assign(target, rival.owner(), user, ours.store(), "MANAGER");
    assertThat(assigned.text(), assigned.status(), is(404));
    Answer roles =
        call(
            target,
            "DELETE",
            "/admin/staff/" + user + "?store=" + ours.store(),
            null,
            rival.owner());
    assertThat(roles.status(), is(404));

    assertThat(audit(target, ours.owner(), null), hasSize(before));
    List<JsonObject> rivalLog = audit(target, rival.owner(), null);
    assertThat(types(rivalLog), not(hasItem("STAFF_UNASSIGNED")));
    assertThat(rivalLog, hasSize(1));
    assertThat(
        call(target, "GET", "/admin/stores/" + ours.store(), null, ours.owner())
            .data()
            .getString("status"),
        is("ACTIVE"));
    assertThat(
        call(target, "GET", "/admin/stores/" + ours.store(), null, ours.owner())
            .data()
            .getString("tillPhone"),
        is("OPTIONAL"));
    assertThat(
        call(target, "GET", "/admin/staff", null, ours.owner()).body(),
        org.hamcrest.Matchers.containsString(user));
  }
}
