package com.storeql.customer;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The retention purge of customer records (21.16): a record nothing has happened on for the period
 * is erased as the customer could have asked — the same erasure, the same event — while a held
 * record, one with a recent loyalty movement or consent change, and a recent record stay; the run
 * is announced with its counts; and no period means no purge.
 */
@HelidonTest
class RetentionPurgeIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String AT_ONCE = "01a090ae-611e-7072-8516-000000000001";
  private static final String UNSET = "01a090ae-611e-7072-8516-000000000002";
  private static final String YEAR = "01a090ae-611e-7072-8516-000000000003";
  private static final String MANY = "01a090ae-611e-7072-8516-000000000004";
  private static final String OWNER = "01a090ae-611e-7072-8516-000000000031";
  private static final UUID HELD_CUSTOMER = Ids.parse("01a090ae-611e-7072-8516-000000000041");

  static {
    PG = PostgresSupport.start().wire("customer");
    TENANTS =
        TenantSvcStub.start()
            .with(AT_ONCE, "GBP", "GB")
            .with(UNSET, "GBP", "GB")
            .with(YEAR, "GBP", "GB")
            .with(MANY, "GBP", "GB")
            .withRetention(
                AT_ONCE,
                Map.of("CUSTOMER_RECORDS", 0),
                List.of("{\"subjectKind\":\"CUSTOMER\",\"subjectId\":\"" + HELD_CUSTOMER + "\"}"))
            .withRetention(UNSET, Map.of("NOTIFICATION_LOG", 30), List.of())
            .withRetention(YEAR, Map.of("CUSTOMER_RECORDS", 365), List.of())
            .withRetention(MANY, Map.of("CUSTOMER_RECORDS", 0), List.of());
    System.setProperty("storeql.retention-sweeper.enabled", "false");
    // A small page, so the purge below reads its candidates over several pages.
    System.setProperty("storeql.customer.retention.batch", "2");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  @Test
  void anInactiveRecordIsErasedAndAHeldOrActiveOneKept() {
    String idle = customer(AT_ONCE, null, "1 day");
    String held = customer(AT_ONCE, HELD_CUSTOMER, "1 day");
    String loyal = customer(AT_ONCE, null, "1 day");
    exec(
        PG,
        "INSERT INTO customer.loyalty_ledger (id, tenant_id, customer_id, type, points,"
            + " balance_after, created_at) VALUES ('"
            + Ids.newId()
            + "','"
            + AT_ONCE
            + "','"
            + loyal
            + "','EARN',5,5, now() + interval '1 minute')");
    JsonObject run = ok(sweep(AT_ONCE, "OWNER"));
    assertThat(run.getString("dataClass"), is("CUSTOMER_RECORDS"));
    assertThat(run.getInt("rowsAffected"), is(1));
    assertThat(run.getInt("heldSkipped"), is(1));
    assertThat(statusOf(idle), is("ANONYMIZED"));
    assertThat(statusOf(held), is("ACTIVE"));
    assertThat(statusOf(loyal), is("ACTIVE"));
    // The erasure is the ordinary one, event and all; then the run is announced.
    assertThat(
        scalar(
            PG,
            "SELECT COUNT(*) FROM customer.outbox WHERE event_type = 'CustomerErased'"
                + " AND aggregate_id = '"
                + idle
                + "'"),
        is("1"));
    String payload =
        scalar(
            PG,
            "SELECT payload FROM customer.outbox WHERE event_type = 'RetentionRunCompleted'"
                + " AND tenant_id = '"
                + AT_ONCE
                + "' ORDER BY created_at DESC LIMIT 1");
    assertThat(payload, containsString("\"service\":\"customer-svc\""));
    assertThat(payload, containsString("\"rowsAffected\":1"));
    // An erased record is not a candidate again.
    assertThat(ok(sweep(AT_ONCE, "OWNER")).getInt("rowsAffected"), is(0));
  }

  @Test
  void moreCandidatesThanOnePageAreAllErasedOnce() {
    java.util.List<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) {
      ids.add(customer(MANY, null, "1 day"));
    }
    JsonObject run = ok(sweep(MANY, "OWNER"));
    assertThat(run.getInt("rowsAffected"), is(7));
    for (String id : ids) {
      assertThat(statusOf(id), is("ANONYMIZED"));
    }
    assertThat(ok(sweep(MANY, "OWNER")).getInt("rowsAffected"), is(0));
  }

  @Test
  void aPeriodCountsFromTheLastActivityAndNoPeriodPurgesNothing() {
    String old = customer(YEAR, null, "400 days");
    String recent = customer(YEAR, null, "10 days");
    String consented = customer(YEAR, null, "400 days");
    exec(
        PG,
        "INSERT INTO customer.marketing_consent_log (id, tenant_id, customer_id, channel, granted,"
            + " basis, source, recorded_at) VALUES ('"
            + Ids.newId()
            + "','"
            + YEAR
            + "','"
            + consented
            + "','EMAIL',true,'CONSENT','PREFERENCE_CENTRE', now() - interval '5 days')");
    JsonObject run = ok(sweep(YEAR, "OWNER"));
    assertThat(run.getInt("rowsAffected"), is(1));
    assertThat(statusOf(old), is("ANONYMIZED"));
    assertThat(statusOf(recent), is("ACTIVE"));
    assertThat(statusOf(consented), is("ACTIVE"));

    String kept = customer(UNSET, null, "1000 days");
    Response unset = sweep(UNSET, "OWNER");
    assertThat(unset.getStatus(), is(409));
    assertThat(unset.readEntity(String.class), containsString("RETENTION_PERIOD_NOT_SET"));
    assertThat(statusOf(kept), is("ACTIVE"));
    assertThat(sweep(AT_ONCE, "CASHIER").getStatus(), is(403));
    assertThat(sweep(AT_ONCE, "CUSTOMER").getStatus(), is(403));
  }

  // ── harness ────────────────────────────────────────────────────────────────

  /** An active customer record last touched `age` ago. */
  private static String customer(String tenant, UUID id, String age) {
    String customerId = id == null ? Ids.newId().toString() : id.toString();
    exec(
        PG,
        "INSERT INTO customer.customers (id, tenant_id, email, first_name, last_name, status,"
            + " created_at, updated_at) VALUES ('"
            + customerId
            + "','"
            + tenant
            + "','"
            + customerId
            + "@example.com','Chris','Carter','ACTIVE', now() - interval '"
            + age
            + "', now() - interval '"
            + age
            + "')");
    return customerId;
  }

  private static String statusOf(String id) {
    return scalar(PG, "SELECT status FROM customer.customers WHERE id = '" + id + "'");
  }

  private Response sweep(String tenant, String roles) {
    return WebTargets.at(target, "/admin/customers/retention/sweep")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .header("X-User-Id", OWNER)
        .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
  }
}
