package com.storeql.notification;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.notification.service.RetentionPurgeService;
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
 * The retention purge of the notification log (21.16): messages older than the period go, a held
 * customer's stay, a recent message stays, the run is announced with its counts through this
 * service's new outbox, and no period means no purge.
 */
@HelidonTest
class RetentionPurgeIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  private static final String AT_ONCE = "01a090ae-611e-7071-8516-000000000001";
  private static final String UNSET = "01a090ae-611e-7071-8516-000000000002";
  private static final String YEAR = "01a090ae-611e-7071-8516-000000000003";
  private static final String OWNER = "01a090ae-611e-7071-8516-000000000031";
  private static final UUID HELD_CUSTOMER = Ids.parse("01a090ae-611e-7071-8516-000000000041");

  static {
    PG = PostgresSupport.start().wire("notification");
    TENANTS =
        TenantSvcStub.start()
            .with(AT_ONCE, "GBP", "GB")
            .with(UNSET, "GBP", "GB")
            .with(YEAR, "GBP", "GB")
            .withRetention(
                AT_ONCE,
                Map.of("NOTIFICATION_LOG", 0),
                List.of("{\"subjectKind\":\"CUSTOMER\",\"subjectId\":\"" + HELD_CUSTOMER + "\"}"))
            .withRetention(UNSET, Map.of("CUSTOMER_RECORDS", 365), List.of())
            .withRetention(YEAR, Map.of("NOTIFICATION_LOG", 365), List.of());
    System.setProperty("storeql.retention-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject RetentionPurgeService retentionService;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  @Test
  void oldMessagesGoHeldOnesStayAndTheRunIsAnnounced() {
    String plain = message(AT_ONCE, null, "0 days");
    String held = message(AT_ONCE, HELD_CUSTOMER, "0 days");
    String staff = message(AT_ONCE, null, "0 days");
    JsonObject run = ok(sweep(AT_ONCE, "OWNER"));
    assertThat(run.getString("dataClass"), is("NOTIFICATION_LOG"));
    assertThat(run.getInt("rowsAffected"), is(2));
    assertThat(run.getInt("heldSkipped"), is(1));
    assertThat(exists(plain), is("0"));
    assertThat(exists(staff), is("0"));
    assertThat(exists(held), is("1"));
    String payload =
        scalar(
            PG,
            "SELECT payload FROM notification.outbox WHERE event_type = 'RetentionRunCompleted'"
                + " AND tenant_id = '"
                + AT_ONCE
                + "' ORDER BY created_at DESC LIMIT 1");
    assertThat(payload, containsString("\"service\":\"notification-svc\""));
    assertThat(payload, containsString("\"rowsAffected\":2"));
    assertThat(payload, containsString("\"heldSkipped\":1"));
    assertThat(ok(sweep(AT_ONCE, "OWNER")).getInt("rowsAffected"), is(0));
  }

  @Test
  void aPeriodKeepsRecentMessagesAndNoPeriodPurgesNothing() {
    String old = message(YEAR, null, "366 days");
    String recent = message(YEAR, null, "300 days");
    JsonObject run = ok(sweep(YEAR, "OWNER"));
    assertThat(run.getInt("rowsAffected"), is(1));
    assertThat(exists(old), is("0"));
    assertThat(exists(recent), is("1"));

    String kept = message(UNSET, null, "1000 days");
    Response unset = sweep(UNSET, "OWNER");
    assertThat(unset.getStatus(), is(409));
    assertThat(unset.readEntity(String.class), containsString("RETENTION_PERIOD_NOT_SET"));
    assertThat(exists(kept), is("1"));
    assertThat(sweep(AT_ONCE, "CASHIER").getStatus(), is(403));
    assertThat(sweep(AT_ONCE, "CUSTOMER").getStatus(), is(403));
  }

  // ── harness ────────────────────────────────────────────────────────────────

  /** A sent message in the log, dated `age` ago, about a customer or nobody in particular. */
  private static String message(String tenant, UUID subject, String age) {
    String id = Ids.newId().toString();
    exec(
        PG,
        "INSERT INTO notification.notification_log (id, tenant_id, subject_id, event_id, type,"
            + " channel, recipient, subject, body, status, created_at) VALUES ('"
            + id
            + "','"
            + tenant
            + "',"
            + (subject == null ? "NULL" : "'" + subject + "'")
            + ",'"
            + Ids.newId()
            + "','ORDER_CONFIRMATION','LOG','a@example.com','Your order',"
            + "'Thanks','SENT', now() - interval '"
            + age
            + "')");
    return id;
  }

  private static String exists(String id) {
    return scalar(PG, "SELECT COUNT(*) FROM notification.notification_log WHERE id = '" + id + "'");
  }

  private Response sweep(String tenant, String roles) {
    return WebTargets.at(target, "/admin/notifications/retention/sweep")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .header("X-User-Id", OWNER)
        .post(Entity.entity("{}", MediaType.APPLICATION_JSON));
  }

  // ── The platform's own password-reset purge, no tenant, no schedule ────────────

  /** A password-reset row, dated {@code age} ago — {@code tenant_id} is always null. */
  private static String passwordReset(String age) {
    String id = Ids.newId().toString();
    exec(
        PG,
        "INSERT INTO notification.notification_log (id, tenant_id, event_id, type, channel,"
            + " recipient, subject, body, status, created_at) VALUES ('"
            + id
            + "', NULL, '"
            + Ids.newId()
            + "', 'PASSWORD_RESET', 'EMAIL', 'a@example.com', 'Reset your password',"
            + "'Shopper account: [link removed]', 'SENT', now() - interval '"
            + age
            + "')");
    return id;
  }

  @Test
  void passwordResetRowsOlderThanTheConfiguredPeriodAreDeletedWhateverTheLogin() {
    String old = passwordReset("31 days");
    String recent = passwordReset("1 days");

    int purged = retentionService.purgePasswordResets();

    assertThat(purged, is(1));
    assertThat(exists(old), is("0"));
    assertThat(exists(recent), is("1"));
    // Idempotent: a second sweep the same moment finds nothing left due.
    assertThat(retentionService.purgePasswordResets(), is(0));
  }
}
