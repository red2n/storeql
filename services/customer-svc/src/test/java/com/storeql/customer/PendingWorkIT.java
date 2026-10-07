package com.storeql.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.customer.domain.Privacy;
import com.storeql.customer.repo.PendingWorkRepository;
import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.web.PendingWorkCount;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /admin/pending-work} (system-health dashboard): how many privacy requests are open for
 * the caller's business as a whole. The count comes from the real status column and nothing else;
 * another business's requests are never counted; the route is management's by path, needs the
 * {@code system.health} permission, and a caller held to stores is turned away. The count stops at
 * {@link PendingWorkCount#CAP}: a longer queue answers the cap, whoever else's rows are beside it,
 * and the statement is served by the queue index and reads no further than the cap.
 */
@HelidonTest
class PendingWorkIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  private static final String PATH = "/admin/pending-work";

  private static final AtomicInteger BULK = new AtomicInteger();

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the count ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Open requests are counted, over every kind, and resolved or refused ones are not")
  void countsEqualTheRealQueue() throws SQLException {
    UUID t = Ids.newId();
    UUID customer = customer(t);
    for (String kind : List.of("ACCESS", "CORRECTION", "ERASURE", "GRIEVANCE")) {
      request(t, customer, kind, "OPEN");
    }
    request(t, customer, "NOMINATION", "OPEN");
    request(t, customer, "ACCESS", "RESOLVED");
    request(t, customer, "ERASURE", "REFUSED");
    request(t, customer(t), "ACCESS", "RESOLVED");

    JsonObject data = Envelopes.ok(get(t, "OWNER", null, null));

    assertThat(data.getJsonNumber("privacyRequestsOpen").longValue(), is(5L));
    // The same queue the privacy screen lists.
    assertThat(
        Envelopes.okArray(get(t, "OWNER", null, null, "/customers/privacy/requests?status=OPEN"))
            .size(),
        is(5));
  }

  @Test
  @DisplayName("A business with nothing waiting is told zero, not an error")
  void anEmptyBusinessIsZero() {
    JsonObject data = Envelopes.ok(get(Ids.newId(), "MANAGER", null, null));
    assertThat(data.getJsonNumber("privacyRequestsOpen").longValue(), is(0L));
  }

  // ── the cap ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A queue longer than the cap is counted to the cap and no further")
  void aQueueOverTheCapAnswersTheCap() throws SQLException {
    UUID t = Ids.newId();
    bulk(t, customer(t), "OPEN", PendingWorkCount.CAP + 250);

    assertThat(open(Envelopes.ok(get(t, "OWNER", null, null))), is((long) PendingWorkCount.CAP));
  }

  @Test
  @DisplayName("A queue one short of the cap is counted exactly, and one at the cap answers it")
  void theEdgeOfTheCapIsExact() throws SQLException {
    UUID justUnder = Ids.newId();
    bulk(justUnder, customer(justUnder), "OPEN", PendingWorkCount.CAP - 1);
    UUID exactly = Ids.newId();
    bulk(exactly, customer(exactly), "OPEN", PendingWorkCount.CAP);

    assertThat(
        open(Envelopes.ok(get(justUnder, "MANAGER", null, null))),
        is((long) PendingWorkCount.CAP - 1));
    assertThat(
        open(Envelopes.ok(get(exactly, "MANAGER", null, null))), is((long) PendingWorkCount.CAP));
  }

  @Test
  @DisplayName(
      "Another business's rows never count towards ours, whether ours is under the cap or over it")
  void anotherBusinessNeverFillsOurCap() throws SQLException {
    UUID under = Ids.newId();
    UUID over = Ids.newId();
    UUID theirs = Ids.newId();
    bulk(under, customer(under), "OPEN", PendingWorkCount.CAP - 5);
    bulk(over, customer(over), "OPEN", PendingWorkCount.CAP + 10);
    bulk(theirs, customer(theirs), "OPEN", PendingWorkCount.CAP + 50);
    UUID few = Ids.newId();
    seed(few, 6);

    // Theirs would push ours past the cap if it were counted; the cap is per business.
    assertThat(
        open(Envelopes.ok(get(under, "OWNER", null, null))), is((long) PendingWorkCount.CAP - 5));
    assertThat(open(Envelopes.ok(get(over, "OWNER", null, null))), is((long) PendingWorkCount.CAP));
    assertThat(
        open(Envelopes.ok(get(theirs, "OWNER", null, null, PATH + "?tenantId=" + under))),
        is((long) PendingWorkCount.CAP));
    // A short queue beside long ones is still told exactly.
    assertThat(open(Envelopes.ok(get(few, "OWNER", null, null))), is(6L));
  }

  @Test
  @DisplayName("Answered requests beyond the cap are not counted: only OPEN is")
  void answeredRequestsAreNotCountedWhateverTheirNumber() throws SQLException {
    UUID t = Ids.newId();
    UUID customer = customer(t);
    bulk(t, customer, "RESOLVED", PendingWorkCount.CAP + 100);
    bulk(t, customer, "REFUSED", 50);
    bulk(t, customer, "OPEN", 4);

    assertThat(open(Envelopes.ok(get(t, "OWNER", null, null))), is(4L));
  }

  @Test
  @DisplayName(
      "The bounded count is served by the queue index on the business and the status, and the"
          + " scan stops at the cap")
  void theBoundedCountReadsTheQueueIndexAndStopsAtTheCap() throws SQLException {
    UUID ours = Ids.newId();
    UUID customer = customer(ours);
    UUID theirs = Ids.newId();
    int waiting = PendingWorkCount.CAP * 20;
    bulk(ours, customer, "OPEN", waiting);
    bulk(ours, customer, "RESOLVED", 300);
    bulk(theirs, customer(theirs), "OPEN", 700);

    String sql = PendingWorkRepository.PRIVACY_REQUESTS_OPEN_SQL;
    assertThat(
        "the status is a literal, so the planner can match it to the index: " + sql,
        sql,
        containsString("status = '" + Privacy.STATUS_OPEN + "'"));
    assertThat(sql, containsString("LIMIT ?"));

    String plan = plan(sql, ours);
    assertThat(plan, plan, containsString("idx_privacy_requests_queue"));
    assertThat(plan, plan, not(containsString("Seq Scan")));
    assertThat(plan, plan, containsString("Limit"));
    // A bitmap scan reads every matching entry before the limit applies, and a sort reads them all.
    assertThat(plan, plan, not(containsString("Bitmap")));
    assertThat(plan, plan, not(containsString("Sort")));
    assertThat(
        "the business, then the status, are the index's own condition: " + plan,
        Pattern.compile(
                "Index Cond: \\(\\(tenant_id = (?:'[^']*'::uuid|\\$1)\\)"
                    + " AND \\(status = 'OPEN'::text\\)\\)")
            .matcher(plan)
            .find(),
        is(true));
    // The scan itself read the cap and stopped, with twenty times that waiting in it.
    Matcher scanned =
        Pattern.compile(
                "Index (?:Only )?Scan using idx_privacy_requests_queue[^\\n]*actual time=\\S+"
                    + " rows=(\\d+)")
            .matcher(plan);
    assertThat(plan, scanned.find(), is(true));
    assertThat(
        "the index scan stops at the cap: " + plan,
        Integer.parseInt(scanned.group(1)),
        is(PendingWorkCount.CAP));
  }

  // ── another business ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Another business's requests are never counted, and its management is told its own")
  void anotherBusinessIsNeverCounted() throws SQLException {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    seed(ours, 2);
    seed(theirs, 6);

    assertThat(open(Envelopes.ok(get(ours, "OWNER", null, null))), is(2L));
    // The other business's management, naming our tenant id as a parameter, is read as itself.
    for (String role : List.of("OWNER", "MANAGER")) {
      assertThat(
          open(Envelopes.ok(get(theirs, role, null, null, PATH + "?tenantId=" + ours))), is(6L));
    }
  }

  @Test
  @DisplayName(
      "A storekeeper, a cashier, a shopper and a caller with no role are refused by the tier, in"
          + " their own business and naming ours in another's; nothing is counted for them")
  void belowManagementIsTurnedAwayWhoeverTheyAre() throws SQLException {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    seed(ours, 3);
    seed(theirs, 4);

    for (UUID tenant : List.of(ours, theirs)) {
      for (String role : List.of("STOREKEEPER", "CASHIER", "CUSTOMER")) {
        try (Response r = get(tenant, role, null, null, PATH + "?tenantId=" + ours)) {
          String body = r.readEntity(String.class);
          assertThat(role + " in " + tenant, r.getStatus(), is(403));
          assertThat(body, containsString("FORBIDDEN"));
          assertThat(body, not(containsString("privacyRequestsOpen")));
        }
      }
      try (Response r = get(tenant, null, null, null)) {
        assertThat("no role at all", r.getStatus(), is(403));
      }
    }
  }

  @Test
  @DisplayName("A request carrying no business is not answered with anyone's count")
  void noBusinessIsRefused() {
    try (Response r =
        target.path("admin").path("pending-work").request().header("X-Roles", "OWNER").get()) {
      assertThat(r.getStatus(), is(401));
    }
  }

  // ── who may ask ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A manager held to stores is refused BUSINESS_WIDE_ONLY: the view is the whole business's")
  void aStoreHeldManagerIsRefused() throws SQLException {
    UUID t = Ids.newId();
    seed(t, 1);
    try (Response r = get(t, "MANAGER", null, Ids.newId().toString())) {
      String body = r.readEntity(String.class);
      assertThat(r.getStatus(), is(403));
      assertThat(body, containsString("BUSINESS_WIDE_ONLY"));
      assertThat(body, not(containsString("privacyRequestsOpen")));
    }
    try (Response r = get(t, "MANAGER", null, Ids.newId() + "," + Ids.newId())) {
      assertThat(r.getStatus(), is(403));
    }
  }

  @Test
  @DisplayName(
      "The permission is checked first: a manager without system.health is refused by name")
  void aManagerWithoutThePermissionIsRefused() throws SQLException {
    UUID t = Ids.newId();
    seed(t, 1);
    for (String claim : List.of("-", "customers.privacy,staff.manage")) {
      try (Response r = get(t, "MANAGER", claim, null)) {
        String body = r.readEntity(String.class);
        assertThat("claim " + claim, r.getStatus(), is(403));
        assertThat(body, containsString("SYSTEM_HEALTH_NOT_PERMITTED"));
        assertThat(body, not(containsString("privacyRequestsOpen")));
      }
    }
    try (Response r = get(t, "MANAGER", "-", Ids.newId().toString())) {
      assertThat(r.readEntity(String.class), containsString("SYSTEM_HEALTH_NOT_PERMITTED"));
    }
  }

  @Test
  @DisplayName(
      "A manager with no claim (the tier's defaults), one given system.health, an owner with an"
          + " empty claim and the platform administrator are let in")
  void thoseWhoHoldItAreLetIn() throws SQLException {
    UUID t = Ids.newId();
    seed(t, 1);
    assertThat(get(t, "MANAGER", null, null).getStatus(), is(200));
    assertThat(get(t, "MANAGER", "system.health", null).getStatus(), is(200));
    assertThat(get(t, "OWNER", "-", null).getStatus(), is(200));
    assertThat(get(t, "PLATFORM_ADMIN", null, null).getStatus(), is(200));
  }

  // ── requests ────────────────────────────────────────────────────────────────

  private Response get(UUID tenant, String roles, String permissions, String storeIds) {
    return get(tenant, roles, permissions, storeIds, PATH);
  }

  private Response get(
      UUID tenant, String roles, String permissions, String storeIds, String pathAndQuery) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    Invocation.Builder b =
        t.request().header("X-Tenant-Id", tenant).header("X-User-Id", Ids.newId());
    if (roles != null) b = b.header("X-Roles", roles);
    if (permissions != null) b = b.header("X-Permissions", permissions);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return b.get();
  }

  private static long open(JsonObject data) {
    return data.getJsonNumber("privacyRequestsOpen").longValue();
  }

  // ── rows ────────────────────────────────────────────────────────────────────

  /** {@code open} open requests, and one resolved beside them. */
  private static void seed(UUID t, int open) throws SQLException {
    UUID customer = customer(t);
    request(t, customer, "ACCESS", "RESOLVED");
    for (int i = 0; i < open; i++) request(t, customer, "ACCESS", "OPEN");
  }

  /**
   * {@code count} requests of one status for one business, in one statement. Their ids are made in
   * SQL from a prefix no other call shares, shaped as UUIDv7 (version 7, variant 10), since {@code
   * Ids.newId()} cannot be called there; the rest honours the table's NOT NULL and CHECK rules (an
   * OPEN request has no resolution time, an answered one has).
   */
  private static void bulk(UUID t, UUID customer, String status, int count) throws SQLException {
    String prefix = String.format("0190c%03x", BULK.incrementAndGet());
    exec(
        "INSERT INTO customer.privacy_requests (id, tenant_id, customer_id, kind, opened_at,"
            + " due_on, status, resolved_at) SELECT ('"
            + prefix
            + "-2222-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, ?, ?, 'ACCESS', now(),"
            + " current_date + 30, '"
            + status
            + "', "
            + ("OPEN".equals(status) ? "NULL" : "now()")
            + " FROM generate_series(1, ?) g",
        t,
        customer,
        count);
  }

  /**
   * What Postgres does with the statement for this business, run for real ({@code EXPLAIN ANALYZE})
   * with a sequential scan off: the plan it takes when it can avoid reading the table.
   */
  private static String plan(String sql, UUID tenant) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET search_path TO customer");
      st.execute("ANALYZE privacy_requests");
      st.execute("SET enable_seqscan = off");
      StringBuilder out = new StringBuilder();
      try (PreparedStatement ps = c.prepareStatement("EXPLAIN (ANALYZE) " + sql)) {
        ps.setObject(1, tenant);
        ps.setInt(2, PendingWorkCount.CAP);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.append(rs.getString(1)).append('\n');
        }
      }
      return out.toString();
    }
  }

  private static UUID customer(UUID t) throws SQLException {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO customer.customers (id, tenant_id, email) VALUES (?, ?, ?)",
        id,
        t,
        id + "@example.test");
    return id;
  }

  private static void request(UUID t, UUID customer, String kind, String status)
      throws SQLException {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    exec(
        "INSERT INTO customer.privacy_requests (id, tenant_id, customer_id, kind, nominee_name,"
            + " opened_at, due_on, status, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        Ids.newId(),
        t,
        customer,
        kind,
        "NOMINATION".equals(kind) ? "Someone" : null,
        now,
        LocalDate.now(ZoneOffset.UTC).plusDays(30),
        status,
        "OPEN".equals(status) ? null : now);
  }

  private static void exec(String sql, Object... args) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    }
  }
}
