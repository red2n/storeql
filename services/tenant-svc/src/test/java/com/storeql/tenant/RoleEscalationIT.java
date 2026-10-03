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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nobody raises anybody past themselves (flow catalogue stf-staff-assignment-and-roles gap 1): only
 * an owner makes an owner, and only an owner gives a role the power to manage staff; a role handed
 * out or defined never holds what its giver does not.
 */
@HelidonTest
class RoleEscalationIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static String role(String code, String tier, String perms) {
    return "{\"code\":\""
        + code
        + "\",\"name\":\""
        + code
        + "\",\"baseTier\":\""
        + tier
        + "\",\"permissions\":"
        + perms
        + "}";
  }

  private Answer define(Who who, String code, String tier, String perms) {
    return call(target, "POST", "/admin/roles", role(code, tier, perms), who);
  }

  private String staff(Biz b) {
    return call(target, "GET", "/admin/staff?limit=100", null, b.owner()).body();
  }

  @Test
  @DisplayName(
      "Only an owner makes an owner: a manager, even assigning themselves, is refused and nothing is stored")
  void onlyAnOwnerMakesAnOwner() {
    Biz b = AdminRig.biz(target, "Escalation Owners", "GB", "GBP");
    Who manager = b.manager();
    String friend = Ids.newId().toString();

    Answer forFriend = assign(target, manager, friend, b.store(), "OWNER");
    assertThat(forFriend.text(), forFriend.status(), is(403));
    assertThat(forFriend.code(), is("STAFF_OWNER_TIER_OWNER_ONLY"));
    Answer forSelf = assign(target, manager, manager.user(), b.store(), "owner");
    assertThat(forSelf.text(), forSelf.status(), is(403));
    assertThat(forSelf.code(), is("STAFF_OWNER_TIER_OWNER_ONLY"));
    assertThat(staff(b), not(containsString(friend)));
    assertThat(staff(b), not(containsString(manager.user())));
    assertThat(audit(target, b.owner(), "type=STAFF_ASSIGNED"), hasSize(0));

    // A platform administrator is not an owner of the business either.
    Answer platform =
        assign(
            target,
            new Who(b.tenant(), Ids.newId().toString(), "PLATFORM_ADMIN", null, null),
            friend,
            b.store(),
            "OWNER");
    assertThat(platform.text(), platform.code(), is("STAFF_OWNER_TIER_OWNER_ONLY"));

    // The owner may, and it is logged.
    Answer byOwner = assign(target, b.owner(), friend, b.store(), "OWNER");
    assertThat(byOwner.text(), byOwner.status(), is(201));
    assertThat(staff(b), containsString(friend));
    assertThat(audit(target, b.owner(), "type=STAFF_ASSIGNED"), hasSize(1));

    // What a manager may still do: the tiers below and beside them.
    for (String tier : new String[] {"MANAGER", "STOREKEEPER", "CASHIER"}) {
      Answer ok = assign(target, manager, Ids.newId().toString(), b.store(), tier);
      assertThat(tier + ": " + ok.text(), ok.status(), is(201));
    }
  }

  @Test
  @DisplayName(
      "A role holding staff.manage is an owner's to give; a manager cannot copy the power downward")
  void staffManageIsTheOwnersToGive() {
    Biz b = AdminRig.biz(target, "Escalation Power", "GB", "GBP");
    Who manager = b.manager();

    Answer refused =
        define(manager, "LEAD", "MANAGER", "[\"staff.manage\",\"purchasing.approve\"]");
    assertThat(refused.text(), refused.status(), is(403));
    assertThat(refused.code(), is("ROLE_STAFF_MANAGE_OWNER_ONLY"));
    assertThat(call(target, "GET", "/admin/roles/LEAD", null, b.owner()).status(), is(404));

    // The owner may.
    Answer made = define(b.owner(), "LEAD", "MANAGER", "[\"staff.manage\",\"purchasing.approve\"]");
    assertThat(made.text(), made.status(), is(201));

    // A manager may still define a role that leaves staff.manage out, holding what they hold.
    assertThat(
        define(manager, "VOIDER", "MANAGER", "[\"sales.void\",\"finance.journal\"]").status(),
        is(201));

    // ...and cannot widen a role to include it, but may narrow or rename one that has it.
    Answer widen =
        call(
            target,
            "PUT",
            "/admin/roles/VOIDER",
            "{\"name\":\"Voider\",\"permissions\":[\"sales.void\",\"staff.manage\"],\"description\":\"\"}",
            manager);
    assertThat(widen.text(), widen.status(), is(403));
    assertThat(widen.code(), is("ROLE_STAFF_MANAGE_OWNER_ONLY"));
    assertThat(
        call(
                target,
                "PUT",
                "/admin/roles/LEAD",
                "{\"name\":\"Lead renamed\",\"permissions\":[\"staff.manage\"],\"description\":\"\"}",
                manager)
            .status(),
        is(200));
    assertThat(
        call(
                target,
                "PUT",
                "/admin/roles/VOIDER",
                "{\"name\":\"Voider\",\"permissions\":[\"sales.void\",\"staff.manage\"],\"description\":\"\"}",
                b.owner())
            .status(),
        is(200));
    assertThat(
        call(target, "GET", "/admin/roles/VOIDER", null, b.owner()).body(),
        containsString("staff.manage"));
  }

  @Test
  @DisplayName(
      "Nobody hands out or defines more than they hold: a narrowed manager is held to their own set")
  void nobodyGrantsMoreThanTheyHold() {
    Biz b = AdminRig.biz(target, "Escalation Held", "GB", "GBP");
    assertThat(
        define(b.owner(), "LEAD", "MANAGER", "[\"staff.manage\",\"purchasing.approve\"]").status(),
        is(201));
    // The token of someone assigned LEAD: the tier's MANAGER role, carrying exactly these.
    Who lead = b.manager().withPermissions("staff.manage,purchasing.approve");
    String person = Ids.newId().toString();

    Answer manager = assign(target, lead, person, b.store(), "MANAGER");
    assertThat(manager.text(), manager.status(), is(403));
    assertThat(manager.code(), is("ROLE_EXCEEDS_CALLER"));
    assertThat(manager.body(), containsString("finance.journal"));
    Answer owner = assign(target, lead, person, b.store(), "OWNER");
    assertThat(owner.code(), is("STAFF_OWNER_TIER_OWNER_ONLY"));
    Answer cashier = assign(target, lead, person, b.store(), "CASHIER");
    assertThat(cashier.text(), cashier.code(), is("ROLE_EXCEEDS_CALLER"));
    assertThat(staff(b), not(containsString(person)));

    // A role no wider than theirs is theirs to give.
    assertThat(assign(target, lead, person, b.store(), "LEAD").status(), is(201));

    // Defining: within their set (and without staff.manage) only.
    Answer beyond = define(lead, "WIDE", "MANAGER", "[\"sales.void\"]");
    assertThat(beyond.text(), beyond.status(), is(403));
    assertThat(beyond.code(), is("ROLE_EXCEEDS_CALLER"));
    assertThat(define(lead, "APPROVER", "CASHIER", "[\"purchasing.approve\"]").status(), is(201));
    assertThat(define(lead, "EMPTY", "CASHIER", "[]").status(), is(201));
    assertThat(call(target, "GET", "/admin/roles/WIDE", null, b.owner()).status(), is(404));

    // Nothing but the refusals' absence changed: the owner-given and lead-given roles exist.
    assertThat(
        call(target, "GET", "/admin/roles", null, b.owner()).body(), containsString("APPROVER"));
  }

  @Test
  @DisplayName("A shopper, a cashier and a storekeeper cannot assign or define at all")
  void lowerTiersCannotAssignOrDefine() {
    Biz b = AdminRig.biz(target, "Escalation Low", "GB", "GBP");
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      Who who = b.as(role);
      Answer a = assign(target, who, Ids.newId().toString(), b.store(), "CASHIER");
      assertThat(role + ": " + a.text(), a.status(), is(403));
      assertThat(role, assign(target, who, who.user(), b.store(), "OWNER").status(), is(403));
      assertThat(role, define(who, "NOPE", "CASHIER", "[]").status(), is(403));
    }
    assertThat(call(target, "GET", "/admin/roles/NOPE", null, b.owner()).status(), is(404));
  }

  @Test
  @DisplayName(
      "Another business's owner or manager cannot make an owner on ours, nor touch our roles, naming our real ids")
  void otherBusinessesCannotEscalateHere() {
    Biz ours = AdminRig.biz(target, "Escalation Ours", "GB", "GBP");
    Biz rival = AdminRig.biz(target, "Escalation Rival", "GB", "GBP");
    assertThat(define(ours.owner(), "OURS", "MANAGER", "[\"sales.void\"]").status(), is(201));
    String friend = Ids.newId().toString();

    for (Who caller : new Who[] {rival.owner(), rival.manager(), rival.manager(ours.store())}) {
      Answer owner = assign(target, caller, friend, ours.store(), "OWNER");
      assertThat(owner.text(), owner.status(), is(404));
      Answer cashier = assign(target, caller, friend, ours.store(), "CASHIER");
      assertThat(cashier.text(), cashier.status(), is(404));
      assertThat(call(target, "GET", "/admin/roles/OURS", null, caller).status(), is(404));
      // Roles are business-wide: a manager held to stores is refused (403) before anything of ours
      // is looked at; the others find no such role (404).
      int expected = caller.stores() == null ? 404 : 403;
      assertThat(
          call(
                  target,
                  "PUT",
                  "/admin/roles/OURS",
                  "{\"name\":\"x\",\"permissions\":[],\"description\":\"\"}",
                  caller)
              .status(),
          is(expected));
      assertThat(call(target, "DELETE", "/admin/roles/OURS", null, caller).status(), is(expected));
    }
    assertThat(staff(ours), not(containsString(friend)));
    assertThat(
        call(target, "GET", "/admin/roles/OURS", null, ours.owner()).data().getString("name"),
        is("OURS"));
    assertThat(audit(target, ours.owner(), "type=STAFF_ASSIGNED"), hasSize(0));
    assertThat(audit(target, ours.owner(), "type=ROLE_DEFINED"), hasSize(1));
    assertThat(audit(target, rival.owner(), "type=ROLE_DEFINED"), hasSize(0));
  }
}
