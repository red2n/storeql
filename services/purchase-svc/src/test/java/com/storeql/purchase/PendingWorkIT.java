package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.purchase.repo.PendingWorkRepository;
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
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /admin/pending-work} (system-health dashboard): how many things wait for a person in
 * this service, for the caller's business as a whole. The counts come from the real status columns
 * and nothing else; another business's rows are never counted; the route is management's by path,
 * needs the {@code system.health} permission, and a caller held to stores is turned away.
 *
 * <p>Rows are written straight into the tables: what is being proved is the count, not the flows
 * that put a purchase order, a run, an invoice or a push into a waiting state (they have their own
 * suites).
 *
 * <p>A count stops at {@link PendingWorkCount#CAP}: the screen polls these every few seconds, so a
 * queue that grows must not make each poll dearer. The cap is proved at the boundary (one under,
 * exactly at, well over), per business, and in how the database runs each statement.
 *
 * <p>How the database runs each statement is shown for tables as autovacuum keeps them: the plan
 * tests vacuum and analyse first, so the early stop (an index-only scan under a limit, no row read
 * and thrown away) is the plan of a table that has been written to for a while, not of one just
 * bulk-loaded.
 */
@HelidonTest
class PendingWorkIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    // No spend authority configured: approvals are not routed, and the answer says so.
    System.setProperty("storeql.purchase.approval.limits", "");
  }

  private static final String PATH = "/admin/pending-work";

  private static final int CAP = PendingWorkCount.CAP;

  /** The waiting counts and the queue each one reads, for the assertions that cover all four. */
  private static final List<String> FIELDS =
      List.of(
          "purchaseOrdersPendingApproval",
          "paymentRunsProposed",
          "supplierInvoicesFlagged",
          "accountingSyncsUncertain");

  /**
   * The id of row {@code g} of a bulk insert: a version-7 prefix minted here ({@link #idPrefix()},
   * the first 24 characters of a v7 id) with the last twelve hex digits counted out by the
   * statement. SQL cannot mint a v7 id, and this keeps a thousand rows to one statement.
   */
  private static final String ID = "(?::text || lpad(to_hex(g), 12, '0'))::uuid";

  /** The rows a step of an analysed plan reports having handled. */
  private static final Pattern ACTUAL_ROWS = Pattern.compile("actual rows=(\\d+)");

  /** The rows a step read and threw away (a filter, a join filter, an index recheck). */
  private static final Pattern ROWS_REMOVED = Pattern.compile("Rows Removed by [A-Za-z ]+: (\\d+)");

  /**
   * A filter a step applies to rows it has already read, as opposed to a condition of the index.
   */
  private static final Pattern FILTER_LINE = Pattern.compile("(?m)^\\s*(Join )?Filter:");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the counts ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Each count is its queue's status and no other, over every status the column allows")
  void countsEqualTheRealQueues() throws SQLException {
    UUID t = Ids.newId();
    UUID supplier = supplier(t);
    for (String status :
        List.of(
            "DRAFT",
            "PENDING_APPROVAL",
            "PENDING_APPROVAL",
            "PENDING_APPROVAL",
            "SUBMITTED",
            "PARTIALLY_RECEIVED",
            "RECEIVED",
            "CLOSED",
            "CANCELLED")) {
      order(t, supplier, status);
    }
    UUID po = order(t, supplier, "SUBMITTED");
    for (String status : List.of("MATCHED", "FLAGGED", "FLAGGED", "APPROVED", "REJECTED")) {
      invoice(t, po, supplier, status);
    }
    for (String status :
        List.of("PROPOSED", "PROPOSED", "PROPOSED", "PROPOSED", "APPROVED", "PAID", "CANCELLED")) {
      run(t, status);
    }
    UUID connection = connection(t);
    for (String status :
        List.of(
            "PENDING", "DELIVERED", "FAILED", "UNCERTAIN", "UNCERTAIN", "UNCERTAIN", "SKIPPED")) {
      sync(t, connection, status);
    }

    JsonObject data = Envelopes.ok(get(t, "OWNER", null, null));

    assertThat(count(data, "purchaseOrdersPendingApproval"), is(3L));
    assertThat(count(data, "supplierInvoicesFlagged"), is(2L));
    assertThat(count(data, "paymentRunsProposed"), is(4L));
    assertThat(count(data, "accountingSyncsUncertain"), is(3L));
    assertThat(data.getBoolean("approvalsRouted"), is(false));
    // The same queues the screens that settle them list.
    assertThat(
        Envelopes.okArray(get(t, "OWNER", null, null, "/payment-runs?status=PROPOSED")).size(),
        is(4));
  }

  @Test
  @DisplayName("A business with nothing waiting is told zero, not an error")
  void anEmptyBusinessIsAllZero() {
    JsonObject data = Envelopes.ok(get(Ids.newId(), "MANAGER", null, null));
    assertThat(count(data, "purchaseOrdersPendingApproval"), is(0L));
    assertThat(count(data, "paymentRunsProposed"), is(0L));
    assertThat(count(data, "supplierInvoicesFlagged"), is(0L));
    assertThat(count(data, "accountingSyncsUncertain"), is(0L));
  }

  // ── another business ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Another business's rows are never counted, and its owner is told only its own")
  void anotherBusinessIsNeverCounted() throws SQLException {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    seedOneOfEach(ours, 2);
    seedOneOfEach(theirs, 5);

    JsonObject mine = Envelopes.ok(get(ours, "OWNER", null, null));
    assertThat(count(mine, "purchaseOrdersPendingApproval"), is(2L));
    assertThat(count(mine, "paymentRunsProposed"), is(2L));
    assertThat(count(mine, "supplierInvoicesFlagged"), is(2L));
    assertThat(count(mine, "accountingSyncsUncertain"), is(2L));

    // The other business's management, naming our tenant id as a parameter, is read as itself: the
    // business comes from the token and from nothing the request says.
    for (String role : List.of("OWNER", "MANAGER")) {
      JsonObject other = Envelopes.ok(get(theirs, role, null, null, PATH + "?tenantId=" + ours));
      assertThat(count(other, "purchaseOrdersPendingApproval"), is(5L));
      assertThat(count(other, "paymentRunsProposed"), is(5L));
      assertThat(count(other, "supplierInvoicesFlagged"), is(5L));
      assertThat(count(other, "accountingSyncsUncertain"), is(5L));
    }
  }

  @Test
  @DisplayName(
      "A storekeeper, a cashier, a shopper and a caller with no role are refused by the tier, in"
          + " their own business and naming ours in another's; nothing is counted for them")
  void belowManagementIsTurnedAwayWhoeverTheyAre() throws SQLException {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    seedOneOfEach(ours, 3);
    seedOneOfEach(theirs, 4);

    for (UUID tenant : List.of(ours, theirs)) {
      for (String role : List.of("STOREKEEPER", "CASHIER", "CUSTOMER")) {
        // Naming the other business changes nothing: it is the tier that refuses.
        try (Response r = get(tenant, role, null, null, PATH + "?tenantId=" + ours)) {
          String body = r.readEntity(String.class);
          assertThat(role + " in " + tenant, r.getStatus(), is(403));
          assertThat(body, containsString("FORBIDDEN"));
          assertThat(body, not(containsString("PendingApproval")));
        }
      }
      try (Response r = get(tenant, null, null, null)) {
        assertThat("no role at all", r.getStatus(), is(403));
      }
    }
  }

  @Test
  @DisplayName("A request carrying no business is not answered with anyone's counts")
  void noBusinessIsRefused() {
    try (Response r =
        target.path("admin").path("pending-work").request().header("X-Roles", "OWNER").get()) {
      assertThat(r.getStatus(), is(401));
    }
  }

  // ── the cap ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A queue longer than the cap is answered as the cap, in every one of the four queues")
  void aQueueOverTheCapAnswersTheCap() throws SQLException {
    UUID t = Ids.newId();
    seedWaiting(t, CAP + 25);

    JsonObject data = Envelopes.ok(get(t, "OWNER", null, null));

    for (String field : FIELDS) {
      assertThat(field, count(data, field), is((long) CAP));
    }
  }

  @Test
  @DisplayName("A queue of exactly the cap is answered as the cap")
  void aQueueExactlyTheCapAnswersTheCap() throws SQLException {
    UUID t = Ids.newId();
    seedWaiting(t, CAP);

    JsonObject data = Envelopes.ok(get(t, "OWNER", null, null));

    for (String field : FIELDS) {
      assertThat(field, count(data, field), is((long) CAP));
    }
  }

  @Test
  @DisplayName("A queue one short of the cap is answered exactly, not rounded up to it")
  void aQueueOneUnderTheCapIsExact() throws SQLException {
    UUID t = Ids.newId();
    // Five rows of another status sit beside each queue: a count that read them too would reach the
    // cap here, and the figure would be wrong by exactly the amount that matters.
    seedWaiting(t, CAP - 1);

    JsonObject data = Envelopes.ok(get(t, "OWNER", null, null));

    for (String field : FIELDS) {
      assertThat(field, count(data, field), is((long) CAP - 1));
    }
  }

  @Test
  @DisplayName(
      "Another business's rows never count towards ours, whether ours is over the cap, just under"
          + " it or small, and a business over the cap does not lift anyone else's figure")
  void anotherBusinessNeverCountsTowardsTheCap() throws SQLException {
    UUID over = Ids.newId();
    UUID under = Ids.newId();
    UUID small = Ids.newId();
    seedWaiting(over, CAP + 40);
    // Three short of the cap beside a business with ten: were theirs counted too, ours would read
    // as the cap.
    seedWaiting(under, CAP - 3);
    seedWaiting(small, 10);

    for (String role : List.of("OWNER", "MANAGER")) {
      JsonObject mineOver = Envelopes.ok(get(over, role, null, null));
      JsonObject mineUnder = Envelopes.ok(get(under, role, null, null, PATH + "?tenantId=" + over));
      JsonObject mineSmall = Envelopes.ok(get(small, role, null, null, PATH + "?tenantId=" + over));
      for (String field : FIELDS) {
        assertThat(role + " over " + field, count(mineOver, field), is((long) CAP));
        assertThat(role + " under " + field, count(mineUnder, field), is((long) CAP - 3));
        assertThat(role + " small " + field, count(mineSmall, field), is(10L));
      }
    }
  }

  // ── who may ask ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A manager held to stores is refused BUSINESS_WIDE_ONLY: the view is the whole business's")
  void aStoreHeldManagerIsRefused() throws SQLException {
    UUID t = Ids.newId();
    seedOneOfEach(t, 1);
    try (Response r = get(t, "MANAGER", null, Ids.newId().toString())) {
      String body = r.readEntity(String.class);
      assertThat(r.getStatus(), is(403));
      assertThat(body, containsString("BUSINESS_WIDE_ONLY"));
      assertThat(body, not(containsString("PendingApproval")));
    }
    // Two stores are the same refusal.
    try (Response r = get(t, "MANAGER", null, Ids.newId() + "," + Ids.newId())) {
      assertThat(r.getStatus(), is(403));
    }
  }

  @Test
  @DisplayName(
      "The permission is checked first: a manager without system.health is refused by name")
  void aManagerWithoutThePermissionIsRefused() throws SQLException {
    UUID t = Ids.newId();
    seedOneOfEach(t, 1);
    for (String claim : List.of("-", "purchasing.approve,finance.payments")) {
      try (Response r = get(t, "MANAGER", claim, null)) {
        String body = r.readEntity(String.class);
        assertThat("claim " + claim, r.getStatus(), is(403));
        assertThat(body, containsString("SYSTEM_HEALTH_NOT_PERMITTED"));
        assertThat(body, not(containsString("PendingApproval")));
      }
    }
    // Held to a store and without the permission: told about the permission first.
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
    seedOneOfEach(t, 1);
    assertThat(get(t, "MANAGER", null, null).getStatus(), is(200));
    assertThat(get(t, "MANAGER", "system.health", null).getStatus(), is(200));
    assertThat(get(t, "OWNER", "-", null).getStatus(), is(200));
    assertThat(get(t, "PLATFORM_ADMIN", null, null).getStatus(), is(200));
  }

  // ── how the database answers ────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Counting the pushes waiting for a person reads the partial index, not every push the"
          + " business has made")
  void uncertainPushesAreCountedFromThePartialIndex() throws SQLException {
    startFromEmptyQueues();
    UUID t = Ids.newId();
    UUID connection = connection(t);
    bulkSyncs(t, connection, "DELIVERED", 3000);
    bulkSyncs(t, connection, "UNCERTAIN", 3);

    String plan = planOf(PendingWorkRepository.ACCOUNTING_SYNCS_UNCERTAIN, t);

    assertThat(plan, containsString("idx_accounting_syncs_tenant_status"));
    assertThat(plan, containsString("Limit"));
  }

  @Test
  @DisplayName(
      "Counting the orders held for approval reads the partial index, not every order the business"
          + " has raised")
  void pendingOrdersAreCountedFromThePartialIndex() throws SQLException {
    startFromEmptyQueues();
    UUID t = Ids.newId();
    UUID supplier = supplier(t);
    bulkOrders(t, supplier, "DRAFT", 3000);
    bulkOrders(t, supplier, "PENDING_APPROVAL", 3);

    String plan = planOf(PendingWorkRepository.PURCHASE_ORDERS_PENDING_APPROVAL, t);

    assertThat(plan, containsString("idx_po_pending"));
    assertThat(plan, containsString("Limit"));
  }

  @Test
  @DisplayName(
      "Counting the flagged invoices reads the tenant-and-status index, not every invoice the"
          + " business has captured")
  void flaggedInvoicesAreCountedFromTheStatusIndex() throws SQLException {
    startFromEmptyQueues();
    UUID t = Ids.newId();
    UUID supplier = supplier(t);
    UUID po = order(t, supplier, "SUBMITTED");
    bulkInvoices(t, po, supplier, "APPROVED", 3000);
    bulkInvoices(t, po, supplier, "FLAGGED", 3);

    String plan = planOf(PendingWorkRepository.SUPPLIER_INVOICES_FLAGGED, t);

    assertThat(plan, containsString("idx_supplier_invoices_status"));
    assertThat(plan, containsString("Limit"));
  }

  @Test
  @DisplayName(
      "Counting the proposed payment runs reads the partial index, not every run the business has"
          + " ever made")
  void proposedRunsAreCountedFromThePartialIndex() throws SQLException {
    startFromEmptyQueues();
    UUID t = Ids.newId();
    // Runs are paid and kept: a business's history is long and few of its runs are proposed. With
    // nothing indexed by status the limit never fires and every run is walked to find the few.
    bulkRuns(t, "PAID", 3000);
    bulkRuns(t, "PROPOSED", 3);

    String plan = planOf(PendingWorkRepository.PAYMENT_RUNS_PROPOSED, t);

    assertThat(plan, containsString("idx_payment_runs_tenant_proposed"));
    assertThat(plan, containsString("Limit"));
  }

  @Test
  @DisplayName(
      "Each count stops reading at the cap: with three times the cap waiting, no step of the plan"
          + " handles more than the cap and none reads a row to throw it away, so a queue that"
          + " grows costs no more per poll")
  void everyCountStopsReadingAtTheCap() throws SQLException {
    startFromEmptyQueues();
    UUID t = Ids.newId();
    seedWaiting(t, 3 * CAP);

    for (String sql :
        List.of(
            PendingWorkRepository.PURCHASE_ORDERS_PENDING_APPROVAL,
            PendingWorkRepository.PAYMENT_RUNS_PROPOSED,
            PendingWorkRepository.SUPPLIER_INVOICES_FLAGGED,
            PendingWorkRepository.ACCOUNTING_SYNCS_UNCERTAIN)) {
      String plan = analyzedPlanOf(sql, t);
      String shown = sql + "\n" + plan;
      assertThat(shown, mostRowsAnyStepHandled(plan), is(CAP));
      // The step that reads the most may still be a scan that filters and discards: it reports only
      // the rows it kept. The rows it threw away, and the filter that threw them, are the signs.
      assertThat(shown, rowsDiscarded(plan), is(0));
      assertThat(shown, FILTER_LINE.matcher(plan).find(), is(false));
    }
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

  private static long count(JsonObject data, String field) {
    return data.getJsonNumber(field).longValue();
  }

  // ── rows ────────────────────────────────────────────────────────────────────

  /** {@code n} of each waiting kind, and one of the kinds that are not waiting beside each. */
  private void seedOneOfEach(UUID t, int n) throws SQLException {
    UUID supplier = supplier(t);
    UUID po = order(t, supplier, "SUBMITTED");
    UUID connection = connection(t);
    order(t, supplier, "DRAFT");
    invoice(t, po, supplier, "APPROVED");
    run(t, "PAID");
    sync(t, connection, "DELIVERED");
    for (int i = 0; i < n; i++) {
      order(t, supplier, "PENDING_APPROVAL");
      invoice(t, po, supplier, "FLAGGED");
      run(t, "PROPOSED");
      sync(t, connection, "UNCERTAIN");
    }
  }

  private static UUID supplier(UUID t) throws SQLException {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO purchase.suppliers (id, tenant_id, name, country_code, currency)"
            + " VALUES (?, ?, ?, 'GB', 'GBP')",
        id,
        t,
        "pending-work-" + id);
    return id;
  }

  private static UUID order(UUID t, UUID supplier, String status) throws SQLException {
    UUID id = Ids.newId();
    if ("CANCELLED".equals(status)) {
      exec(
          "INSERT INTO purchase.purchase_orders (id, tenant_id, supplier_id, store_id, status,"
              + " currency, cancelled_at, cancelled_reason) VALUES (?, ?, ?, ?, ?, 'GBP', now(),"
              + " 'test')",
          id,
          t,
          supplier,
          Ids.newId(),
          status);
    } else {
      exec(
          "INSERT INTO purchase.purchase_orders (id, tenant_id, supplier_id, store_id, status,"
              + " currency) VALUES (?, ?, ?, ?, ?, 'GBP')",
          id,
          t,
          supplier,
          Ids.newId(),
          status);
    }
    return id;
  }

  private static void invoice(UUID t, UUID po, UUID supplier, String status) throws SQLException {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO purchase.supplier_invoices (id, tenant_id, po_id, supplier_id, invoice_number,"
            + " invoice_date, currency, net_amount, gross_amount, status)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'GBP', 10, 10, ?)",
        id,
        t,
        po,
        supplier,
        "INV-" + id,
        LocalDate.of(2026, 9, 1),
        status);
  }

  private static void run(UUID t, String status) throws SQLException {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO purchase.payment_runs (id, tenant_id, reference, status, pay_up_to,"
            + " payment_date, currency, total) VALUES (?, ?, ?, ?, ?, ?, 'GBP', 10)",
        id,
        t,
        "PAY-" + id,
        status,
        LocalDate.of(2026, 9, 30),
        LocalDate.of(2026, 10, 1));
  }

  private static UUID connection(UUID t) throws SQLException {
    UUID id = Ids.newId();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    exec(
        "INSERT INTO purchase.accounting_connections (id, tenant_id, provider, status, settings,"
            + " sync_from, created_by, created_at, updated_at)"
            + " VALUES (?, ?, 'SIMULATED', 'ACTIVE', '{}', ?, ?, ?, ?)",
        id,
        t,
        LocalDate.of(2026, 1, 1),
        Ids.newId(),
        now,
        now);
    return id;
  }

  private static void sync(UUID t, UUID connection, String status) throws SQLException {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    exec(
        "INSERT INTO purchase.accounting_syncs (id, tenant_id, connection_id, journal_id, status,"
            + " next_attempt_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        Ids.newId(),
        t,
        connection,
        Ids.newId(),
        status,
        now,
        now);
  }

  /**
   * Empties the four tables the counts read, so the planner's statistics are this test's and not
   * those of the cap tests that ran before it (businesses of thousands of waiting rows make a
   * waiting status look common table-wide, which it is not in service). Every test here makes its
   * own business and rows, so nothing else depends on what was there.
   */
  private static void startFromEmptyQueues() throws SQLException {
    exec(
        "TRUNCATE purchase.purchase_orders, purchase.supplier_invoices, purchase.payment_runs,"
            + " purchase.accounting_syncs CASCADE");
  }

  // ── many rows at once ───────────────────────────────────────────────────────

  private static String idPrefix() {
    return Ids.newId().toString().substring(0, 24);
  }

  /** {@code n} rows from one {@code INSERT ... SELECT}; {@code g} counts them in {@code values}. */
  private static void bulk(String table, String columns, String values, int n, Object... args)
      throws SQLException {
    Object[] all = Arrays.copyOf(args, args.length + 1);
    all[args.length] = n;
    exec(
        "INSERT INTO purchase."
            + table
            + " ("
            + columns
            + ") SELECT "
            + values
            + " FROM generate_series(1, ?) AS g",
        all);
  }

  private static void bulkOrders(UUID t, UUID supplier, String status, int n) throws SQLException {
    bulk(
        "purchase_orders",
        "id, tenant_id, supplier_id, store_id, status, currency",
        ID + ", ?, ?, ?, ?, 'GBP'",
        n,
        idPrefix(),
        t,
        supplier,
        Ids.newId(),
        status);
  }

  private static void bulkInvoices(UUID t, UUID po, UUID supplier, String status, int n)
      throws SQLException {
    bulk(
        "supplier_invoices",
        "id, tenant_id, po_id, supplier_id, invoice_number, invoice_date, currency, net_amount,"
            + " gross_amount, status",
        ID + ", ?, ?, ?, 'INV-' || ? || g::text, ?, 'GBP', 10, 10, ?",
        n,
        idPrefix(),
        t,
        po,
        supplier,
        idPrefix(),
        LocalDate.of(2026, 9, 1),
        status);
  }

  private static void bulkRuns(UUID t, String status, int n) throws SQLException {
    bulk(
        "payment_runs",
        "id, tenant_id, reference, status, pay_up_to, payment_date, currency, total",
        ID + ", ?, 'PAY-' || ? || g::text, ?, ?, ?, 'GBP', 10",
        n,
        idPrefix(),
        t,
        idPrefix(),
        status,
        LocalDate.of(2026, 9, 30),
        LocalDate.of(2026, 10, 1));
  }

  private static void bulkSyncs(UUID t, UUID connection, String status, int n) throws SQLException {
    bulk(
        "accounting_syncs",
        "id, tenant_id, connection_id, journal_id, status, next_attempt_at, created_at",
        ID + ", ?, ?, " + ID + ", ?, now(), now()",
        n,
        idPrefix(),
        t,
        connection,
        idPrefix(),
        status);
  }

  /**
   * {@code n} of each waiting kind for the business, and five of another status beside each, so a
   * count that ignored the status would show.
   */
  private void seedWaiting(UUID t, int n) throws SQLException {
    UUID supplier = supplier(t);
    UUID po = order(t, supplier, "SUBMITTED");
    UUID connection = connection(t);
    bulkOrders(t, supplier, "PENDING_APPROVAL", n);
    bulkOrders(t, supplier, "DRAFT", 5);
    bulkInvoices(t, po, supplier, "FLAGGED", n);
    bulkInvoices(t, po, supplier, "APPROVED", 5);
    bulkRuns(t, "PROPOSED", n);
    bulkRuns(t, "PAID", 5);
    bulkSyncs(t, connection, "UNCERTAIN", n);
    bulkSyncs(t, connection, "DELIVERED", 5);
  }

  /** What the database says it will do for the statement, after it has looked at the table. */
  private static String planOf(String sql, UUID tenant) throws SQLException {
    return explain("EXPLAIN " + sql, sql, tenant);
  }

  /** What the database did: the plan with the rows each step handled when it ran. */
  private static String analyzedPlanOf(String sql, UUID tenant) throws SQLException {
    return explain("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF) " + sql, sql, tenant);
  }

  private static String explain(String explain, String sql, UUID tenant) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      try (var st = c.createStatement()) {
        st.execute("SET search_path TO purchase");
        // As autovacuum leaves a table that has been written to for a while: statistics taken and
        // the visibility map set, so an index-only scan is as cheap as it is in service. A fresh
        // bulk load nobody has vacuumed is planned as a bitmap scan, which reads every matching
        // index entry before the limit applies.
        st.execute("VACUUM (ANALYZE)");
      }
      try (PreparedStatement ps = c.prepareStatement(explain)) {
        ps.setObject(1, tenant);
        ps.setInt(2, CAP);
        StringBuilder plan = new StringBuilder();
        try (var rs = ps.executeQuery()) {
          while (rs.next()) plan.append(rs.getString(1)).append('\n');
        }
        return plan.toString();
      }
    }
  }

  /** The most rows any one step of an analysed plan handled. */
  private static int mostRowsAnyStepHandled(String plan) {
    int most = 0;
    Matcher m = ACTUAL_ROWS.matcher(plan);
    while (m.find()) most = Math.max(most, Integer.parseInt(m.group(1)));
    return most;
  }

  /** The rows every step of an analysed plan read and threw away, added together. */
  private static int rowsDiscarded(String plan) {
    int total = 0;
    Matcher m = ROWS_REMOVED.matcher(plan);
    while (m.find()) total += Integer.parseInt(m.group(1));
    return total;
  }

  private static void exec(String sql, Object... args) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    }
  }
}
