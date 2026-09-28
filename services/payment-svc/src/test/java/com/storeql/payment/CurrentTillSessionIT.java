package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The till a cashier is standing at, over HTTP and Postgres: {@code GET
 * /admin/cash/till-sessions/current?storeId=} answers the caller's own open session at that store
 * and nobody else's — not a colleague's at the same store, not a closed one, and nothing at all to
 * another business, whoever it sends and whichever store it names.
 */
@HelidonTest
class CurrentTillSessionIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  private static final String TILLS = "/admin/cash/till-sessions";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Caller staff(UUID tenantId, String role, UUID storeId) {
    return new Caller(tenantId, Ids.newId(), role, storeId);
  }

  private UUID open(Caller who, UUID storeId, String floatAmount) {
    Answer a =
        ItCalls.post(
            target,
            TILLS,
            who,
            "{\"storeId\":\"" + storeId + "\",\"floatAmount\":" + floatAmount + "}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(a.data().getString("id"));
  }

  private Answer current(Caller who, UUID storeId) {
    return ItCalls.get(target, TILLS + "/current?storeId=" + storeId, who);
  }

  private static void notOpen(Answer a) {
    assertThat(a.body().toString(), a.status(), is(404));
    assertThat(a.body().toString(), a.code(), is("TILL_SESSION_NOT_OPEN"));
  }

  // ── the caller's own open till ─────────────────────────────────────────────

  @Test
  @DisplayName("A cashier opens a till and reads it back as their current session")
  void cashierReadsTheirOpenTill() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller cashier = staff(tenant, "CASHIER", store);
    UUID session = open(cashier, store, "150.00");

    Answer a = current(cashier, store);

    assertThat(a.body().toString(), a.status(), is(200));
    JsonObject s = a.data();
    assertThat(s.getString("id"), is(session.toString()));
    assertThat(s.getString("storeId"), is(store.toString()));
    assertThat(s.getString("openedBy"), is(cashier.userId().toString()));
    assertThat(s.getString("status"), is("OPEN"));
    assertThat(
        s.getJsonNumber("floatAmount").bigDecimalValue().compareTo(new BigDecimal("150")), is(0));
    assertThat(s.containsKey("closedAt") && !s.isNull("closedAt"), is(false));
  }

  @Test
  @DisplayName("A manager and an owner each read back the till they opened themselves")
  void managerAndOwnerReadTheirOwn() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller manager = staff(tenant, "MANAGER", store);
    Caller owner = Caller.owner(tenant);
    UUID managers = open(manager, store, "50.00");
    UUID owners = open(owner, store, "75.00");

    assertThat(current(manager, store).data().getString("id"), is(managers.toString()));
    assertThat(current(owner, store).data().getString("id"), is(owners.toString()));
  }

  @Test
  @DisplayName("A colleague's till at the same store is not the caller's: 404, and it is not shown")
  void anotherCashiersTillIsNotTheirs() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID elsewhere = Ids.newId();
    Caller cashier = staff(tenant, "CASHIER", store);
    UUID session = open(cashier, store, "100.00");

    Answer colleague = current(staff(tenant, "CASHIER", store), store);
    notOpen(colleague);
    assertThat(colleague.body().toString(), not(containsString(session.toString())));

    // The owner did not open it either: the current till is whoever opened it, not the business's.
    Answer owner = current(Caller.owner(tenant), store);
    notOpen(owner);
    assertThat(owner.body().toString(), not(containsString(session.toString())));

    // The cashier's own till is at one store; another store of the business has none of theirs.
    notOpen(current(cashier.at(elsewhere), elsewhere));
    // And a store the cashier is not held to is refused outright.
    Answer refused = current(cashier, elsewhere);
    assertThat(refused.body().toString(), refused.status(), is(403));
    assertThat(refused.code(), is("STORE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName("A storekeeper is refused: the till is not their work")
  void storekeeperIsRefused() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Answer a = current(staff(tenant, "STOREKEEPER", store), store);
    assertThat(a.body().toString(), a.status(), is(403));
    assertThat(a.code(), is("FORBIDDEN"));
  }

  // ── another business ───────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Another business's owner, manager, cashier and storekeeper naming our store see nothing")
  void anotherTenantSeesNothing() {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    UUID store = Ids.newId();
    Caller cashier = staff(ours, "CASHIER", store);
    UUID session = open(cashier, store, "120.00");

    for (Caller intruder :
        new Caller[] {
          Caller.owner(theirs),
          new Caller(theirs, Ids.newId(), "MANAGER"),
          staff(theirs, "MANAGER", store),
          staff(theirs, "CASHIER", store),
          // Even the same user id under another tenant: the tenant is the first thing asked.
          new Caller(theirs, cashier.userId(), "CASHIER", store),
          new Caller(theirs, cashier.userId(), "OWNER")
        }) {
      Answer a = current(intruder, store);
      notOpen(a);
      assertThat(a.body().toString(), not(containsString(session.toString())));
      // Nor does the session's own id open it to them.
      Answer byId = ItCalls.get(target, TILLS + "/" + session, intruder);
      assertThat(byId.body().toString(), byId.status(), is(404));
      assertThat(byId.body().toString(), not(containsString(cashier.userId().toString())));
    }

    Answer storekeeper = current(staff(theirs, "STOREKEEPER", store), store);
    assertThat(storekeeper.status(), is(403));
    assertThat(storekeeper.body().toString(), not(containsString(session.toString())));

    // Nothing of ours moved: the cashier's till is still open, with the float it was opened with.
    JsonObject still = current(cashier, store).data();
    assertThat(still.getString("id"), is(session.toString()));
    assertThat(still.getString("status"), is("OPEN"));
    assertThat(
        still.getJsonNumber("floatAmount").bigDecimalValue().compareTo(new BigDecimal("120")),
        is(0));
  }

  // ── closed is not current ──────────────────────────────────────────────────

  @Test
  @DisplayName("A closed till is not current; the next one opened is")
  void closedTillIsNotCurrent() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    Caller cashier = staff(tenant, "CASHIER", store);
    UUID first = open(cashier, store, "80.00");

    Answer closed =
        ItCalls.post(
            target,
            TILLS + "/" + first + "/close",
            Caller.owner(tenant),
            "{\"countedCash\":80.00}");
    assertThat(closed.body().toString(), closed.status(), is(200));

    Answer after = current(cashier, store);
    notOpen(after);
    assertThat(after.body().toString(), not(containsString(first.toString())));

    UUID second = open(cashier, store, "90.00");
    Answer now = current(cashier, store);
    assertThat(now.status(), is(200));
    assertThat(now.data().getString("id"), is(second.toString()));
  }

  // ── the store named ────────────────────────────────────────────────────────

  @Test
  @DisplayName("A missing store, or one that is not a UUIDv7, is 400 INVALID_UUID")
  void storeIdMustBeAUuidV7() {
    UUID tenant = Ids.newId();
    Caller cashier = new Caller(tenant, Ids.newId(), "CASHIER");
    for (String query :
        new String[] {
          "", "?storeId=", "?storeId=not-a-uuid", "?storeId=3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
        }) {
      Answer a = ItCalls.get(target, TILLS + "/current" + query, cashier);
      assertThat(query + " " + a.body(), a.status(), is(400));
      assertThat(query + " " + a.body(), a.code(), is("INVALID_UUID"));
    }
  }
}
