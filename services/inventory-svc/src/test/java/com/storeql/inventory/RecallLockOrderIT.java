package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Recall.Hazard;
import com.storeql.inventory.domain.Recall.Header;
import com.storeql.inventory.domain.Recall.Kind;
import com.storeql.inventory.domain.Recall.Scope;
import com.storeql.inventory.domain.Recall.Source;
import com.storeql.inventory.domain.Recall.Status;
import com.storeql.inventory.repo.InventoryRepository;
import com.storeql.inventory.repo.RecallRepository;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A recall's cancel, release and store action lock the recall and then its batches; every other
 * transaction that touches a recalled lot must take its locks in that order, or two requests that
 * land together deadlock, or leave a batch held by a recall that is no longer open. Each test holds
 * a recall's row from a second connection the way a cancel does, runs the other side through the
 * API, waits until it is queued behind that lock, and lets the cancel finish. Real Postgres; Kafka
 * and Consul disabled; tenant-svc is a stub.
 */
@HelidonTest
class RecallLockOrderIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;

  static {
    TENANTS = TenantSvcStub.start();
    PG = PostgresSupport.start().wire("inventory");
  }

  @Inject WebTarget target;
  @Inject RecallRepository recalls;
  @Inject InventoryRepository inventory;

  @AfterAll
  static void stopDb() {
    TENANTS.close();
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  /** A business, a person in it, and the store and variant its batches are of. */
  private record Stock(String tenant, String staff, String store, String variant) {}

  private Stock stock() {
    String tenant = Ids.newId().toString();
    TENANTS.with(tenant, "GBP", "GB");
    return new Stock(
        tenant, Ids.newId().toString(), Ids.newId().toString(), Ids.newId().toString());
  }

  private Response post(String path, String json, Stock s) {
    Invocation.Builder b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", s.tenant())
            .header("X-Roles", "OWNER")
            .header("X-User-Id", s.staff());
    return b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** Receives a batch of the lot; returns its id. */
  private String receive(Stock s, int qty, String lot) {
    return receiveDated(s, qty, lot, "2098-01-01");
  }

  private String receiveDated(Stock s, int qty, String lot, String expiry) {
    return Envelopes.created(
            post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + s.store()
                    + "\",\"variantId\":\""
                    + s.variant()
                    + "\",\"qty\":"
                    + qty
                    + ",\"batchNo\":\""
                    + lot
                    + "\",\"costPrice\":1.0000,\"expiryDate\":\""
                    + expiry
                    + "\"}",
                s))
        .getString("id");
  }

  private JsonObject recall(Stock s, String reference, String lot) {
    return Envelopes.created(
        post(
            "/admin/recalls",
            "{\"reference\":\""
                + reference
                + "\",\"kind\":\"WITHDRAWAL\",\"hazard\":\"ALLERGEN\","
                + "\"reason\":\"Undeclared peanut\",\"source\":\"FSA\",\"items\":[{\"variantId\":\""
                + s.variant()
                + "\",\"batchNo\":\""
                + lot
                + "\"}]}",
            s));
  }

  private static String sql(String query) {
    return Envelopes.scalar(PG, query);
  }

  private static BigDecimal number(String query) {
    return new BigDecimal(sql(query));
  }

  private static String materialStatus(String batch) {
    return sql(
        "SELECT material_status FROM inventory.inventory_batches WHERE id = '" + batch + "'");
  }

  private static BigDecimal holds(String batch) {
    return number("SELECT count(*) FROM inventory.recall_batches WHERE batch_id = '" + batch + "'");
  }

  /** Waits until some statement is queued behind a lock, which is where the test lets go. */
  private static void awaitALockWait() throws InterruptedException {
    long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!"1".equals(sql("SELECT least(count(*), 1) FROM pg_locks WHERE NOT granted"))) {
      assertThat("the request reached the lock", System.nanoTime() < give, is(true));
      Thread.sleep(20);
    }
  }

  /** What a cancel does first, not yet committed: lock the recall and end it. */
  private static void beginCancel(Statement st, String recall) throws Exception {
    st.execute("SELECT id FROM inventory.recalls WHERE id = '" + recall + "' FOR UPDATE");
    st.executeUpdate(
        "UPDATE inventory.recalls SET status = 'CANCELLED', ended_by = '"
            + Ids.newId()
            + "', ended_at = now() WHERE id = '"
            + recall
            + "'");
  }

  // ── a delivery against a recall that is ending ─────────────────────────────

  @Test
  @DisplayName("A delivery landing while its recall is cancelled is not held by that recall")
  void aDeliveryLandingWhileItsRecallIsCancelledIsNotHeldByIt() throws Exception {
    Stock s = stock();
    receive(s, 10, "LOT-A");
    String recall = recall(s, "STUCK-A", "LOT-A").getString("id");

    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      beginCancel(st, recall);
      CompletableFuture<String> delivery =
          CompletableFuture.supplyAsync(() -> receive(s, 5, "LOT-A"));
      awaitALockWait();
      c.commit();

      String batch = delivery.get(30, TimeUnit.SECONDS);
      assertThat("the delivery is for sale", materialStatus(batch), is("AVAILABLE"));
      assertThat("no cancelled recall holds it", holds(batch), comparesEqualTo(BigDecimal.ZERO));
    }
  }

  // ── a cancel against a recall that is opening ──────────────────────────────

  /**
   * Opens a recall of the lot on a thread of its own, and keeps its transaction open, with every
   * batch it took locked and held, until {@code finish} is counted down.
   */
  private CompletableFuture<Void> openPaused(
      Stock s, String reference, String lot, CountDownLatch paused, CountDownLatch finish) {
    UUID tenant = Ids.parse(s.tenant());
    UUID id = Ids.newId();
    Header header =
        new Header(
            id,
            tenant,
            reference,
            Kind.WITHDRAWAL,
            Hazard.ALLERGEN,
            "Undeclared peanut",
            null,
            Source.FSA,
            null,
            Status.OPEN,
            Ids.parse(s.staff()),
            Instant.now(),
            null,
            null,
            null,
            Set.of(),
            null,
            null,
            null,
            null);
    Scope scope = new Scope(Ids.newId(), Ids.parse(s.variant()), lot, null, null);
    return CompletableFuture.runAsync(
        () ->
            recalls.open(
                header,
                List.of(scope),
                stores -> {
                  paused.countDown();
                  try {
                    finish.await(30, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return new OutboxRow(
                      "RecallOpened",
                      "storeql.inventory.recall-opened",
                      tenant,
                      id,
                      "{\"eventId\":\"" + Ids.newId() + "\"}");
                },
                order -> null));
  }

  @Test
  @DisplayName("A cancel leaves a batch off sale when a recall that is opening has taken it too")
  void aCancelLeavesABatchOffSaleThatARecallBeingOpenedHolds() throws Exception {
    Stock s = stock();
    String batch = receive(s, 10, "LOT-A");
    String first = recall(s, "FIRST-A", "LOT-A").getString("id");
    assertThat(materialStatus(batch), is("RECALLED"));

    CountDownLatch paused = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    CompletableFuture<Void> opening = openPaused(s, "SECOND-A", "LOT-A", paused, finish);
    assertThat("the second recall holds the batch", paused.await(20, TimeUnit.SECONDS), is(true));

    CompletableFuture<Integer> cancel =
        CompletableFuture.supplyAsync(
            () -> {
              Response r =
                  post(
                      "/admin/recalls/" + first + "/cancel", "{\"reason\":\"Opened in error\"}", s);
              r.readEntity(String.class);
              return r.getStatus();
            });
    awaitALockWait();
    finish.countDown();

    opening.get(30, TimeUnit.SECONDS);
    assertThat(cancel.get(30, TimeUnit.SECONDS), is(200));
    assertThat(
        "the second recall is open and holds it: it stays off sale",
        materialStatus(batch),
        is("RECALLED"));
  }

  // ── a sale against a recall that is opening ────────────────────────────────

  @Test
  @DisplayName("A sale locks the batches it may draw in id order, as a recall opening does")
  void aSaleLocksBatchesInIdOrderNotInPickOrder() throws Exception {
    Stock s = stock();
    // The first received has the lower id and the later date; the sale picks the second first.
    String low = receiveDated(s, 10, "LOT-LOW", "2099-01-01");
    String high = receiveDated(s, 3, "LOT-HIGH", "2098-01-01");

    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      // What a recall opening does first: lock the lower id.
      st.execute("SELECT id FROM inventory.inventory_batches WHERE id = '" + low + "' FOR UPDATE");
      CompletableFuture<Void> sale =
          CompletableFuture.runAsync(
              () ->
                  inventory.deductSale(
                      Ids.parse(s.tenant()),
                      Ids.parse(s.store()),
                      Ids.parse(s.variant()),
                      new BigDecimal("5"),
                      Ids.newId(),
                      new OutboxRow(
                          "StockDeducted",
                          "storeql.inventory.stock-deducted",
                          Ids.parse(s.tenant()),
                          Ids.newId(),
                          "{\"eventId\":\"" + Ids.newId() + "\"}")));
      awaitALockWait();
      // Queued on the lower id, the sale holds nothing on the higher one: what the recall takes
      // next.
      try {
        st.execute(
            "SELECT id FROM inventory.inventory_batches WHERE id = '"
                + high
                + "' FOR UPDATE NOWAIT");
      } catch (java.sql.SQLException e) {
        assertThat(
            "the sale already holds the higher id (" + e.getMessage() + ")", false, is(true));
      }
      c.commit();
      sale.get(30, TimeUnit.SECONDS);
    }
  }

  // ── a putaway placed while its batch is split or merged ────────────────────

  @Test
  @DisplayName("Placing a putaway task locks the batch before the task, as a split or merge does")
  void placingAPutawayTaskLocksTheBatchBeforeTheTask() throws Exception {
    Stock s = stock();
    String batch = receive(s, 5, "LOT-P");
    String task = sql("SELECT id FROM inventory.putaway_tasks WHERE batch_id = '" + batch + "'");

    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      // What a split or merge does first: lock the batch.
      st.execute(
          "SELECT id FROM inventory.inventory_batches WHERE id = '" + batch + "' FOR UPDATE");
      CompletableFuture<Integer> place =
          CompletableFuture.supplyAsync(
              () -> {
                Response r =
                    post(
                        "/admin/inventory/putaway/tasks/" + task + "/place",
                        "{\"zoneId\":\"" + Ids.newId() + "\"}",
                        s);
                r.readEntity(String.class);
                return r.getStatus();
              });
      awaitALockWait();
      // Queued on the batch, the placing holds nothing on the task: what a split takes next.
      try {
        st.execute(
            "SELECT id FROM inventory.putaway_tasks WHERE id = '" + task + "' FOR UPDATE NOWAIT");
      } catch (java.sql.SQLException e) {
        assertThat("the placing already holds the task (" + e.getMessage() + ")", false, is(true));
      }
      c.commit();
      assertThat(place.get(30, TimeUnit.SECONDS), is(200));
    }
  }

  // ── a cycle count adjusted twice ───────────────────────────────────────────

  private int adjustCount(Stock s, String count) {
    Response r = post("/admin/inventory/cycle-counts/" + count + "/adjust", "{}", s);
    r.readEntity(String.class);
    return r.getStatus();
  }

  @Test
  @DisplayName("Two requests to adjust one cycle count adjust the stock once")
  void twoRequestsToAdjustOneCycleCountAdjustTheStockOnce() throws Exception {
    Stock s = stock();
    String count = Ids.newId().toString();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO inventory.cycle_count_headers (id, tenant_id, store_id, name, status)"
              + " VALUES ('%s', '%s', '%s', 'Aisle 4', 'PENDING_APPROVAL')"
                  .formatted(count, s.tenant(), s.store()));
      st.executeUpdate(
          ("INSERT INTO inventory.cycle_count_lines (id, tenant_id, header_id, store_id,"
                  + " variant_id, system_qty, counted_qty, variance, status, counted_at)"
                  + " VALUES ('%s', '%s', '%s', '%s', '%s', 4, 6, 2, 'APPROVED', now())")
              .formatted(Ids.newId(), s.tenant(), count, s.store(), s.variant()));
    }

    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      // Both requests pass the service's look at the status before either has written anything.
      st.execute(
          "SELECT id FROM inventory.cycle_count_headers WHERE id = '" + count + "' FOR UPDATE");
      CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> adjustCount(s, count));
      CompletableFuture<Integer> second =
          CompletableFuture.supplyAsync(() -> adjustCount(s, count));
      long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (number("SELECT count(*) FROM pg_locks WHERE NOT granted").intValue() < 2) {
        assertThat("both requests queued", System.nanoTime() < give, is(true));
        Thread.sleep(20);
      }
      c.commit();

      List<Integer> statuses =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertThat(
          "one adjusts, one finds it done",
          statuses.stream().sorted().toList(),
          is(List.of(200, 422)));
    }
    assertThat(
        "the variance was added once",
        number(
            "SELECT coalesce(sum(remaining_qty), 0) FROM inventory.inventory_batches"
                + " WHERE variant_id = '"
                + s.variant()
                + "'"),
        comparesEqualTo(new BigDecimal("2")));
  }

  // ── a delivery against a recall that is opening ────────────────────────────

  @Test
  @DisplayName("A delivery landing while its recall is being opened is held by that recall")
  void aDeliveryLandingWhileItsRecallIsBeingOpenedIsHeldByIt() throws Exception {
    Stock s = stock();
    receive(s, 10, "LOT-A");

    CountDownLatch paused = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    CompletableFuture<Void> opening = openPaused(s, "OPENING-A", "LOT-A", paused, finish);
    assertThat("the recall has taken its batches", paused.await(20, TimeUnit.SECONDS), is(true));

    // Neither side can see the other's uncommitted row: the delivery cannot see the recall, and the
    // recall's scan of batches cannot see the delivery. The delivery must wait for the recall.
    CompletableFuture<String> delivery =
        CompletableFuture.supplyAsync(() -> receive(s, 5, "LOT-A"));
    long give = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!delivery.isDone()
        && !"1".equals(sql("SELECT least(count(*), 1) FROM pg_locks WHERE NOT granted"))) {
      assertThat("the delivery settled or queued", System.nanoTime() < give, is(true));
      Thread.sleep(20);
    }
    finish.countDown();

    opening.get(30, TimeUnit.SECONDS);
    String batch = delivery.get(30, TimeUnit.SECONDS);
    assertThat("the recall covers its lot: off sale", materialStatus(batch), is("RECALLED"));
  }
}
