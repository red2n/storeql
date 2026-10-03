package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Till sessions slice 4: a cashier ends only their own sign-in session; a manager or owner with
 * access to the store ends a colleague's with a reason, recorded with who did it; the list of open
 * sessions is management's and store scoped; another business never sees or ends any.
 */
@HelidonTest
class PosSessionSupervisorIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.jwt.secret", "integration-test-secret-of-at-least-32-chars");
  }

  private static final UUID TENANT = Ids.newId();
  private static final UUID OTHER_TENANT = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();

  @Inject WebTarget target;
  @Inject com.storeql.service.StoreStatusChangedHandler storeStatus;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private void open(UUID store) {
    storeStatus.handle(
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"StoreStatusChanged\",\"tenantId\":\""
            + TENANT
            + "\",\"aggregateId\":\""
            + store
            + "\",\"occurredAt\":\""
            + Instant.now()
            + "\",\"storeId\":\""
            + store
            + "\",\"status\":\"ACTIVE\"}");
  }

  private jakarta.ws.rs.client.Invocation.Builder as(
      String path, UUID tenant, UUID user, String role, UUID... stores) {
    var b =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", tenant.toString())
            .header("X-User-Id", user.toString())
            .header("X-Roles", role);
    if (stores.length > 0) {
      StringBuilder sb = new StringBuilder();
      for (UUID s : stores) sb.append(sb.length() == 0 ? "" : ",").append(s);
      b = b.header("X-Store-Ids", sb.toString());
    }
    return b;
  }

  private String startFor(UUID user, UUID store) {
    Response r =
        as("/auth/pos/sessions", TENANT, user, "CASHIER")
            .post(Entity.entity("{\"storeId\":\"" + store + "\"}", MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  private int end(String id, UUID tenant, UUID user, String role, String reason, UUID... stores) {
    var t = target.path("/auth/pos/sessions/" + id);
    if (reason != null) t = t.queryParam("reason", reason);
    var b =
        t.request()
            .header("X-Tenant-Id", tenant.toString())
            .header("X-User-Id", user.toString())
            .header("X-Roles", role);
    if (stores.length > 0) b = b.header("X-Store-Ids", stores[0].toString());
    try (Response r = b.delete()) {
      return r.getStatus();
    }
  }

  private static String scalar(String sql) {
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private String status(String id) {
    return scalar("SELECT status FROM iam.pos_sessions WHERE id = '" + id + "'");
  }

  @Test
  void supervisorReachIsManagementAtTheirStoresWithAReason() {
    open(STORE_A);
    open(STORE_B);
    UUID cashier = Ids.newId();
    UUID colleague = Ids.newId();
    String s = startFor(cashier, STORE_A);

    // A cashier naming a colleague's session: refused, nothing moves.
    Response no =
        target
            .path("/auth/pos/sessions/" + s)
            .request()
            .header("X-Tenant-Id", TENANT.toString())
            .header("X-User-Id", colleague.toString())
            .header("X-Roles", "CASHIER")
            .delete();
    assertThat(no.readEntity(String.class), containsString("POS_SESSION_NOT_YOURS"));
    assertThat(status(s), is("ACTIVE"));

    // Another business, every role: 404, nothing moves.
    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER"}) {
      assertThat(role, end(s, OTHER_TENANT, Ids.newId(), role, "x"), is(404));
    }
    assertThat(status(s), is("ACTIVE"));

    // A manager held to another store: refused.
    assertThat(end(s, TENANT, Ids.newId(), "MANAGER", "left open", STORE_B), is(403));
    assertThat(status(s), is("ACTIVE"));

    // A manager at the store without a reason: 400.
    assertThat(end(s, TENANT, Ids.newId(), "MANAGER", null, STORE_A), is(400));
    assertThat(end(s, TENANT, Ids.newId(), "MANAGER", "  ", STORE_A), is(400));
    assertThat(status(s), is("ACTIVE"));

    // A manager at the store with a reason: ended, and who and why are kept.
    UUID manager = Ids.newId();
    assertThat(end(s, TENANT, manager, "MANAGER", "left the till open", STORE_A), is(204));
    assertThat(status(s), is("ENDED"));
    assertThat(
        scalar("SELECT ended_by FROM iam.pos_sessions WHERE id = '" + s + "'"),
        is(manager.toString()));
    assertThat(
        scalar("SELECT end_reason FROM iam.pos_sessions WHERE id = '" + s + "'"),
        is("left the till open"));
    assertThat(
        scalar(
            "SELECT count(*) FROM iam.audit_log WHERE action = 'POS_SESSION_ENDED_BY_SUPERVISOR'"
                + " AND detail LIKE '"
                + s
                + "%'"),
        is("1"));
    // Ending again is safe and keeps the first record.
    assertThat(end(s, TENANT, Ids.newId(), "OWNER", "again"), is(204));
    assertThat(
        scalar("SELECT ended_by FROM iam.pos_sessions WHERE id = '" + s + "'"),
        is(manager.toString()));

    // An owner (held to no store) ends another's; a cashier ends their own with no reason.
    String s2 = startFor(cashier, STORE_B);
    assertThat(end(s2, TENANT, Ids.newId(), "OWNER", "shift over"), is(204));
    String s3 = startFor(cashier, STORE_A);
    assertThat(end(s3, TENANT, cashier, "CASHIER", null), is(204));
    assertThat(
        scalar("SELECT ended_by IS NULL FROM iam.pos_sessions WHERE id = '" + s3 + "'"), is("t"));
  }

  @Test
  void theListIsManagementAndStoreScoped() {
    open(STORE_A);
    open(STORE_B);
    UUID cashier = Ids.newId();
    String a = startFor(cashier, STORE_A);
    String b = startFor(cashier, STORE_B);

    assertThat(as("/auth/pos/sessions", TENANT, cashier, "CASHIER").get().getStatus(), is(403));
    assertThat(as("/auth/pos/sessions", TENANT, cashier, "SHOPPER").get().getStatus(), is(403));

    String all = as("/auth/pos/sessions", TENANT, Ids.newId(), "OWNER").get(String.class);
    assertThat(all, containsString(a));
    assertThat(all, containsString(b));

    String mine =
        as("/auth/pos/sessions", TENANT, Ids.newId(), "MANAGER", STORE_A).get(String.class);
    assertThat(mine, containsString(a));
    assertThat(mine.contains(b), is(false));
    assertThat(
        as("/auth/pos/sessions", TENANT, Ids.newId(), "MANAGER", STORE_A).get().getStatus(),
        is(200));
    assertThat(
        target
            .path("/auth/pos/sessions")
            .queryParam("storeId", STORE_B.toString())
            .request()
            .header("X-Tenant-Id", TENANT.toString())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", "MANAGER")
            .header("X-Store-Ids", STORE_A.toString())
            .get()
            .getStatus(),
        is(403));

    // Another business sees none of ours.
    String theirs = as("/auth/pos/sessions", OTHER_TENANT, Ids.newId(), "OWNER").get(String.class);
    assertThat(theirs.contains(a) || theirs.contains(b), is(false));

    // A cashier reads their own open sessions.
    String own = as("/auth/pos/sessions/mine", TENANT, cashier, "CASHIER").get(String.class);
    assertThat(own, containsString(a));
    String colleaguesOwn =
        as("/auth/pos/sessions/mine", TENANT, Ids.newId(), "CASHIER").get(String.class);
    assertThat(colleaguesOwn.contains(a), is(false));
  }
}
