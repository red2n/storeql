package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.repo.PendingWorkRepository;
import com.storeql.test.PostgresSupport;
import com.storeql.web.PendingWorkCount;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /admin/pending-work} (system-health dashboard): how many card refunds a person has to
 * settle, for the caller's business as a whole. The count comes from the real state column and
 * nothing else; another business's dues are never counted; the route is management's by path, needs
 * the {@code system.health} permission, and a caller held to stores is turned away.
 *
 * <p>The screen polls this, so the count is bounded: it reads at most {@link PendingWorkCount#CAP}
 * rows of the queue and stops, and a figure equal to the cap means "that many or more". The cap
 * tests seed queues past it and the plan tests prove the database really stops early.
 *
 * <p>Rows are written straight into the tables: what is being proved is the count, not the card
 * machine flows that leave a refund waiting (they have their own suites).
 */
@HelidonTest
class PendingWorkIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  private static final String PATH = "/admin/pending-work";

  /** Gives each bulk write its own id prefix, so two never meet. */
  private static final AtomicInteger BULK = new AtomicInteger();

  private static final AtomicBoolean BACKGROUND = new AtomicBoolean();

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the count ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("NEEDS_ATTENTION dues are counted, over every state the column allows, and no other")
  void countsEqualTheRealQueue() throws SQLException {
    UUID t = Ids.newId();
    UUID store = Ids.newId();
    UUID sale = sale(t, store);
    for (String state :
        List.of(
            "OWED",
            "NEEDS_ATTENTION",
            "NEEDS_ATTENTION",
            "NEEDS_ATTENTION",
            "REFUNDED",
            "NOT_REFUNDED",
            "REFUNDED_ANOTHER_WAY")) {
      due(t, store, sale, state);
    }
    // Another store of the same business counts too: the view is the whole business's.
    due(t, Ids.newId(), sale(t, Ids.newId()), "NEEDS_ATTENTION");

    Answer a = ItCalls.call(target, "GET", PATH, Caller.owner(t), null, null);

    assertThat(a.status(), is(200));
    assertThat(a.data().getJsonNumber("cardRefundDuesNeedingAttention").longValue(), is(4L));
    // The same queue the refunds screen lists.
    Answer queue =
        ItCalls.get(
            target, "/payments/terminal/refund-dues?state=NEEDS_ATTENTION", Caller.owner(t));
    assertThat(queue.status(), is(200));
    assertThat(queue.list().size(), is(4));
  }

  @Test
  @DisplayName("A business with nothing waiting is told zero, not an error")
  void anEmptyBusinessIsZero() {
    Answer a = ItCalls.get(target, PATH, Caller.owner(Ids.newId()).as("MANAGER"));
    assertThat(a.status(), is(200));
    assertThat(a.data().getJsonNumber("cardRefundDuesNeedingAttention").longValue(), is(0L));
  }

  // ── the cap ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A queue longer than the cap is told as the cap, not as its real length")
  void aQueueOverTheCapAnswersTheCap() throws SQLException {
    UUID t = Ids.newId();
    UUID store = Ids.newId();
    UUID sale = sale(t, store);
    bulk(t, store, sale, "NEEDS_ATTENTION", PendingWorkCount.CAP + 500);
    bulk(t, store, sale, "OWED", 50);

    Answer a = ItCalls.get(target, PATH, Caller.owner(t));

    assertThat(owing(a), is((long) PendingWorkCount.CAP));
    // Still a plain number, as before: the cap is read from the figure, not from a new member.
    assertThat(a.data().getJsonNumber("cardRefundDuesNeedingAttention").isIntegral(), is(true));
  }

  @Test
  @DisplayName("One short of the cap is told exactly; the cap and one past it are told as the cap")
  void theCapIsReachedAtTheCapAndNotBefore() throws SQLException {
    long cap = PendingWorkCount.CAP;
    for (long waiting : List.of(cap - 1, cap, cap + 1)) {
      UUID t = Ids.newId();
      UUID store = Ids.newId();
      bulk(t, store, sale(t, store), "NEEDS_ATTENTION", (int) waiting);

      assertThat(
          waiting + " waiting",
          owing(ItCalls.get(target, PATH, Caller.owner(t))),
          is(Math.min(waiting, cap)));
    }
  }

  @Test
  @DisplayName("Another business's dues never count towards ours, over the cap or under it")
  void anotherBusinessNeverCountsTowardsTheCap() throws SQLException {
    UUID over = Ids.newId();
    UUID under = Ids.newId();
    UUID small = Ids.newId();
    UUID other = Ids.newId();
    // Ours is past the cap and a small neighbour has its own few: each is told its own.
    UUID overStore = Ids.newId();
    bulk(over, overStore, sale(over, overStore), "NEEDS_ATTENTION", PendingWorkCount.CAP + 10);
    seed(small, 3);
    // Ours is far under the cap and a neighbour's queue is past it: ours is not lifted to the cap.
    UUID underStore = Ids.newId();
    bulk(under, underStore, sale(under, underStore), "NEEDS_ATTENTION", 5);
    UUID otherStore = Ids.newId();
    bulk(other, otherStore, sale(other, otherStore), "NEEDS_ATTENTION", PendingWorkCount.CAP + 200);

    long cap = PendingWorkCount.CAP;
    assertThat(owing(ItCalls.get(target, PATH, Caller.owner(over))), is(cap));
    assertThat(owing(ItCalls.get(target, PATH, Caller.owner(small))), is(3L));
    assertThat(owing(ItCalls.get(target, PATH, Caller.owner(under))), is(5L));
    assertThat(owing(ItCalls.get(target, PATH, Caller.owner(other))), is(cap));
    // Naming a neighbour's tenant id changes nothing: the business is the token's.
    assertThat(
        owing(ItCalls.get(target, PATH + "?tenantId=" + other, Caller.owner(under))), is(5L));
    // And a neighbour's staff of any role below management is refused, whatever the queue.
    for (String role : List.of("STOREKEEPER", "CASHIER", "CUSTOMER")) {
      Answer a =
          ItCalls.get(target, PATH + "?tenantId=" + over, new Caller(small, Ids.newId(), role));
      assertThat(role, a.status(), is(403));
      assertThat(a.body().toString().contains("cardRefundDues"), is(false));
    }
  }

  // ── another business ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Another business's dues are never counted, and its management is told its own")
  void anotherBusinessIsNeverCounted() throws SQLException {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    seed(ours, 2);
    seed(theirs, 6);

    assertThat(owing(ItCalls.get(target, PATH, Caller.owner(ours))), is(2L));
    // The other business's management, naming our tenant id as a parameter, is read as itself.
    for (String role : List.of("OWNER", "MANAGER")) {
      Caller them = new Caller(theirs, Ids.newId(), role);
      assertThat(owing(ItCalls.get(target, PATH + "?tenantId=" + ours, them)), is(6L));
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
        Answer a =
            ItCalls.get(target, PATH + "?tenantId=" + ours, new Caller(tenant, Ids.newId(), role));
        assertThat(role + " in " + tenant, a.status(), is(403));
        assertThat(a.code(), is("FORBIDDEN"));
        assertThat(a.body().toString().contains("cardRefundDues"), is(false));
      }
      assertThat(
          "no role at all",
          ItCalls.get(target, PATH, new Caller(tenant, Ids.newId(), null)).status(),
          is(403));
    }
  }

  // ── who may ask ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A manager held to stores is refused BUSINESS_WIDE_ONLY: the view is the whole business's")
  void aStoreHeldManagerIsRefused() throws SQLException {
    UUID t = Ids.newId();
    seed(t, 1);
    Answer one = ItCalls.get(target, PATH, Caller.heldTo(t, "MANAGER", Ids.newId()));
    assertThat(one.status(), is(403));
    assertThat(one.code(), is("BUSINESS_WIDE_ONLY"));
    assertThat(one.body().toString().contains("cardRefundDues"), is(false));
    // Two stores are the same refusal.
    Answer two = ItCalls.get(target, PATH, Caller.heldTo(t, "MANAGER", Ids.newId(), Ids.newId()));
    assertThat(two.status(), is(403));
    assertThat(two.code(), is("BUSINESS_WIDE_ONLY"));
  }

  @Test
  @DisplayName(
      "The permission is checked first: a manager without system.health is refused by name")
  void aManagerWithoutThePermissionIsRefused() throws SQLException {
    UUID t = Ids.newId();
    seed(t, 1);
    Caller manager = new Caller(t, Ids.newId(), "MANAGER");
    for (String claim : List.of("-", "sales.refund,till.manage")) {
      Answer a = ItCalls.call(target, "GET", PATH, manager, null, null, claim);
      assertThat("claim " + claim, a.status(), is(403));
      assertThat(a.code(), is("SYSTEM_HEALTH_NOT_PERMITTED"));
      assertThat(a.body().toString().contains("cardRefundDues"), is(false));
    }
    // Held to a store and without the permission: told about the permission first.
    Answer both =
        ItCalls.call(
            target, "GET", PATH, Caller.heldTo(t, "MANAGER", Ids.newId()), null, null, "-");
    assertThat(both.code(), is("SYSTEM_HEALTH_NOT_PERMITTED"));
  }

  @Test
  @DisplayName(
      "A manager with no claim (the tier's defaults), one given system.health, an owner with an"
          + " empty claim and the platform administrator are let in")
  void thoseWhoHoldItAreLetIn() throws SQLException {
    UUID t = Ids.newId();
    seed(t, 1);
    Caller manager = new Caller(t, Ids.newId(), "MANAGER");
    assertThat(ItCalls.get(target, PATH, manager).status(), is(200));
    assertThat(
        ItCalls.call(target, "GET", PATH, manager, null, null, "system.health").status(), is(200));
    assertThat(
        ItCalls.call(target, "GET", PATH, Caller.owner(t), null, null, "-").status(), is(200));
    assertThat(
        ItCalls.get(target, PATH, new Caller(t, Ids.newId(), "PLATFORM_ADMIN")).status(), is(200));
  }

  @Test
  @DisplayName("A request carrying no business is not answered with anyone's count")
  void noBusinessIsRefused() {
    try (var r =
        target.path("admin").path("pending-work").request().header("X-Roles", "OWNER").get()) {
      assertThat(r.getStatus(), is(401));
    }
  }

  // ── how the database answers ────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Counting the dues waiting for a manager reads the partial index, not every refund the"
          + " business has made")
  void dueNeedingAttentionAreCountedFromThePartialIndex() throws SQLException {
    liveShapedTable();
    UUID t = Ids.newId();
    UUID store = Ids.newId();
    UUID sale = sale(t, store);
    dues(t, store, sale, "OWED", 3000);
    dues(t, store, sale, "NEEDS_ATTENTION", 3);

    String plan = planOf(PendingWorkRepository.CARD_REFUND_DUES_NEEDING_ATTENTION, false, t);

    assertThat(plan, containsString("idx_card_refund_dues_tenant_state"));
    assertThat(plan, containsString("Limit"));
  }

  @Test
  @DisplayName(
      "Counting a queue three times the cap reads the cap's worth of index entries and stops, not"
          + " the whole queue")
  void theCountStopsAtTheCapInTheIndex() throws SQLException {
    liveShapedTable();
    UUID t = Ids.newId();
    UUID store = Ids.newId();
    UUID sale = sale(t, store);
    bulk(t, store, sale, "NEEDS_ATTENTION", PendingWorkCount.CAP * 3);
    bulk(t, store, sale, "OWED", 500);

    String plan = planOf(PendingWorkRepository.CARD_REFUND_DUES_NEEDING_ATTENTION, true, t);

    Matcher m =
        Pattern.compile("idx_card_refund_dues_tenant_state.*actual time=\\S+ rows=(\\d+)")
            .matcher(plan);
    assertThat("the partial index is walked:\n" + plan, m.find(), is(true));
    assertThat(
        "the index gave up after the cap:\n" + plan,
        Long.parseLong(m.group(1)),
        is((long) PendingWorkCount.CAP));
  }

  @Test
  @DisplayName(
      "The partial index is used and the walk is bounded by the limit also as the generic plan the"
          + " driver moves to after a few uses, when the limit is not yet known")
  void theGenericPlanIsTheSame() throws SQLException {
    liveShapedTable();
    UUID t = Ids.newId();
    UUID store = Ids.newId();
    UUID sale = sale(t, store);
    bulk(t, store, sale, "NEEDS_ATTENTION", PendingWorkCount.CAP * 2);
    bulk(t, store, sale, "OWED", 3000);

    String plan = genericPlanOf(PendingWorkRepository.CARD_REFUND_DUES_NEEDING_ATTENTION, t);

    assertThat(plan, containsString("idx_card_refund_dues_tenant_state"));
    assertThat(plan, containsString("Limit"));
    assertThat(plan.contains("Seq Scan"), is(false));
    assertThat(plan.contains("Bitmap"), is(false));
  }

  private static long owing(Answer a) {
    assertThat(a.status(), is(200));
    assertThat(a.code(), is(nullValue()));
    return a.data().getJsonNumber("cardRefundDuesNeedingAttention").longValue();
  }

  // ── rows ────────────────────────────────────────────────────────────────────

  /** {@code waiting} dues needing attention, and one owed and one refunded beside them. */
  private static void seed(UUID t, int waiting) throws SQLException {
    UUID store = Ids.newId();
    UUID sale = sale(t, store);
    due(t, store, sale, "OWED");
    due(t, store, sale, "REFUNDED");
    for (int i = 0; i < waiting; i++) due(t, store, sale, "NEEDS_ATTENTION");
  }

  /** A card sale on a terminal at the store, the attempt a due refers to. */
  private static UUID sale(UUID t, UUID store) throws SQLException {
    UUID terminal = Ids.newId();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    exec(
        "INSERT INTO payment.card_terminals (id, tenant_id, store_id, label, vendor, status,"
            + " created_at, created_by, updated_at)"
            + " VALUES (?, ?, ?, ?, 'SIMULATED', 'ACTIVE', ?, ?, ?)",
        terminal,
        t,
        store,
        "Till " + terminal,
        now,
        Ids.newId(),
        now);
    UUID sale = Ids.newId();
    exec(
        "INSERT INTO payment.terminal_payments (id, tenant_id, store_id, terminal_id, order_id,"
            + " amount, currency, kind, state, requested_at, requested_by)"
            + " VALUES (?, ?, ?, ?, ?, 10, 'GBP', 'SALE', 'REQUESTED', ?, ?)",
        sale,
        t,
        store,
        terminal,
        Ids.newId(),
        now,
        Ids.newId());
    return sale;
  }

  private static void due(UUID t, UUID store, UUID sale, String state) throws SQLException {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    boolean person = "NOT_REFUNDED".equals(state);
    UUID refundAttempt = "REFUNDED".equals(state) ? refund(sale, now) : null;
    exec(
        "INSERT INTO payment.card_refund_dues (id, tenant_id, store_id, order_id, sale_attempt_id,"
            + " amount, currency, reason, source, idempotency_key, state, refund_attempt_id,"
            + " requested_by, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, 10, 'GBP', 'test', ?, ?, ?, ?, ?, ?, ?)",
        Ids.newId(),
        t,
        store,
        Ids.newId(),
        sale,
        person ? "PERSON" : "ORDER_EVENT",
        Ids.newId().toString(),
        state,
        refundAttempt,
        person ? Ids.newId() : null,
        now,
        now);
  }

  private static void dues(UUID t, UUID store, UUID sale, String state, int n) throws SQLException {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO payment.card_refund_dues (id, tenant_id, store_id, order_id,"
                    + " sale_attempt_id, amount, currency, reason, source, idempotency_key, state,"
                    + " created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, 10, 'GBP', 'test', 'ORDER_EVENT', ?, ?, ?, ?)")) {
      for (int i = 0; i < n; i++) {
        ps.setObject(1, Ids.newId());
        ps.setObject(2, t);
        ps.setObject(3, store);
        ps.setObject(4, Ids.newId());
        ps.setObject(5, sale);
        ps.setString(6, Ids.newId().toString());
        ps.setString(7, state);
        ps.setObject(8, now);
        ps.setObject(9, now);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /**
   * Sixty other businesses' settled refunds, once, so the table is shaped like a live one: a big
   * table of which a business's waiting dues are a rare few. On a table of a few thousand rows the
   * planner is right to read it all, and says nothing about the real one.
   */
  private static void liveShapedTable() throws SQLException {
    if (!BACKGROUND.compareAndSet(false, true)) return;
    UUID store = Ids.newId();
    UUID sale = sale(Ids.newId(), store);
    String prefix = String.format("0190%04x", BULK.incrementAndGet());
    exec(
        "INSERT INTO payment.card_refund_dues (id, tenant_id, store_id, order_id, sale_attempt_id,"
            + " amount, currency, reason, source, idempotency_key, state, created_at, updated_at)"
            + " SELECT ('"
            + prefix
            + "-2222-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " ('"
            + prefix
            + "-0000-7000-8000-' || lpad(to_hex(g % 60), 12, '0'))::uuid, ?, ('"
            + prefix
            + "-3333-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, ?, 10, 'GBP', 'test',"
            + " 'ORDER_EVENT', '"
            + prefix
            + "-4444-7000-8000-' || lpad(to_hex(g), 12, '0'),"
            + " CASE WHEN g % 7 = 0 THEN 'OWED' ELSE 'REFUNDED_ANOTHER_WAY' END, now(), now()"
            + " FROM generate_series(1, 60000) g",
        store,
        sale);
  }

  /**
   * {@code n} dues of one business in one state, written by one statement. The ids are made in SQL,
   * shaped as UUIDv7, from a prefix no other call shares.
   */
  private static void bulk(UUID t, UUID store, UUID sale, String state, int n) throws SQLException {
    String prefix = String.format("0190%04x", BULK.incrementAndGet());
    exec(
        "INSERT INTO payment.card_refund_dues (id, tenant_id, store_id, order_id, sale_attempt_id,"
            + " amount, currency, reason, source, idempotency_key, state, created_at, updated_at)"
            + " SELECT ('"
            + prefix
            + "-2222-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, ?, ?, ('"
            + prefix
            + "-3333-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, ?, 10, 'GBP', 'test',"
            + " 'ORDER_EVENT', '"
            + prefix
            + "-4444-7000-8000-' || lpad(to_hex(g), 12, '0'), ?, now(), now()"
            + " FROM generate_series(1, "
            + n
            + ") g",
        t,
        store,
        sale,
        state);
  }

  /**
   * What the database says it will do for the statement, after it has vacuumed and looked at the
   * table, as autovacuum leaves a live one: the tenant and then the cap are the two parameters.
   * With {@code analyse} the statement is run and the plan says how many rows each step really
   * read.
   */
  private static String planOf(String sql, boolean analyse, UUID tenant) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      try (var st = c.createStatement()) {
        st.execute("SET search_path TO payment");
        st.execute("VACUUM (ANALYZE) card_refund_dues");
      }
      try (PreparedStatement ps =
          c.prepareStatement((analyse ? "EXPLAIN (ANALYZE) " : "EXPLAIN ") + sql)) {
        ps.setObject(1, tenant);
        ps.setInt(2, PendingWorkCount.CAP);
        StringBuilder plan = new StringBuilder();
        try (var rs = ps.executeQuery()) {
          while (rs.next()) plan.append(rs.getString(1)).append('\n');
        }
        return plan.toString();
      }
    }
  }

  /**
   * The plan the statement gets once it is prepared and planned without knowing the parameters:
   * what the driver's server-side statement becomes after a few uses.
   */
  private static String genericPlanOf(String sql, UUID tenant) throws SQLException {
    StringBuilder numbered = new StringBuilder();
    int n = 0;
    for (char ch : sql.toCharArray()) {
      if (ch == '?') numbered.append('$').append(++n);
      else numbered.append(ch);
    }
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement()) {
      st.execute("SET search_path TO payment");
      st.execute("VACUUM (ANALYZE) card_refund_dues");
      st.execute("SET plan_cache_mode = force_generic_plan");
      st.execute("PREPARE pending_count (uuid, int) AS " + numbered);
      StringBuilder plan = new StringBuilder();
      try (var rs =
          st.executeQuery(
              "EXPLAIN EXECUTE pending_count('" + tenant + "', " + PendingWorkCount.CAP + ")")) {
        while (rs.next()) plan.append(rs.getString(1)).append('\n');
      }
      return plan.toString();
    }
  }

  /** The refund attempt a REFUNDED due names, put back through the sale's own terminal. */
  private static UUID refund(UUID sale, OffsetDateTime now) throws SQLException {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO payment.terminal_payments (id, tenant_id, store_id, terminal_id, order_id,"
            + " amount, currency, kind, refund_of, state, scheme, pan_last4, entry_mode,"
            + " requested_at, requested_by, reason, settled_at)"
            + " SELECT ?, tenant_id, store_id, terminal_id, order_id, 10, 'GBP', 'REFUND', id,"
            + " 'APPROVED', 'TESTCARD', '0000', 'CHIP', ?, ?, 'test', ?"
            + "   FROM payment.terminal_payments WHERE id = ?",
        id,
        now,
        Ids.newId(),
        now,
        sale);
    return id;
  }

  private static void exec(String sql, Object... args) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    }
  }
}
