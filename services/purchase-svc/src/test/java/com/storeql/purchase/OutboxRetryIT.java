package com.storeql.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxStore;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * What a row that will not publish does to the rest of the outbox: it is backed off, it holds back
 * only its own aggregate, it becomes a dead letter after its attempts, and the drain never holds a
 * transaction open while it publishes. Runs on the schema purchase-svc migrates.
 */
class OutboxRetryIT {

  private static final String SCHEMA = "purchase";
  private static final PostgresSupport PG = PostgresSupport.start();

  static {
    Flyway.configure()
        .dataSource(PG.jdbcUrl(), PG.username(), PG.password())
        .locations("classpath:db/migration")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .load()
        .migrate();
  }

  private static final class Replica extends BaseOutboxRepository {
    Replica(DataSource ds) {
      this.dataSource = ds;
    }
  }

  @AfterAll
  static void stop() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (Connection c = connection();
        Statement st = c.createStatement()) {
      st.execute("TRUNCATE TABLE outbox");
    }
  }

  /** The schema's drain, as OutboxPublisher runs it: loop while claims come back full. */
  private static void drainAll(
      Replica r,
      int batch,
      Function<List<OutboxStore.PendingOutbox>, OutboxStore.PublishOutcome> publish) {
    try (OutboxStore.DrainLease lease = r.tryDrainLock().orElseThrow()) {
      int claimed;
      do {
        claimed = r.drainOnce(lease, batch, publish).claimed();
      } while (claimed >= batch);
    }
  }

  /**
   * Fails {@code poison} and delivers the rest, as the real publisher does: once a row fails, the
   * later rows of its aggregate in the batch are neither sent nor reported, so they are not
   * attempted.
   */
  private static OutboxStore.PublishOutcome failing(
      List<OutboxStore.PendingOutbox> rows, UUID poison) {
    List<UUID> ok = new ArrayList<>();
    Map<UUID, String> failed = new java.util.HashMap<>();
    UUID heldAggregate = null;
    for (OutboxStore.PendingOutbox row : rows) {
      if (row.id().equals(poison)) {
        failed.put(row.id(), "broker said no");
        heldAggregate = row.aggregateId();
      } else if (heldAggregate != null && heldAggregate.equals(row.aggregateId())) {
        // held back behind the failed row: not sent
      } else {
        ok.add(row.id());
      }
    }
    return new OutboxStore.PublishOutcome(ok, failed);
  }

  @Test
  void aPoisonRowHoldsBackItsOwnAggregateAndNotTheSchema() throws Exception {
    Instant t = Instant.now().minusSeconds(3600);
    UUID aggregateA = Ids.newId();
    UUID aggregateB = Ids.newId();
    UUID poison = insertRow(aggregateA, t); // the oldest row in the schema, and it will not publish
    UUID behindPoison = insertRow(aggregateA, t.plusSeconds(1));
    for (int i = 0; i < 100; i++) insertRow(aggregateB, t.plusSeconds(2 + i));

    Replica r = new Replica(dataSource());
    drainAll(r, 100, rows -> failing(rows, poison));

    assertEquals(
        "100",
        scalar(
            "SELECT count(*) FROM purchase.outbox WHERE aggregate_id = '"
                + aggregateB
                + "' AND published_at IS NOT NULL"),
        "every row of the other aggregate is published, though the poison row is older");
    assertEquals("1", scalar("SELECT attempts FROM purchase.outbox WHERE id = '" + poison + "'"));
    assertEquals(
        "broker said no",
        scalar("SELECT last_error FROM purchase.outbox WHERE id = '" + poison + "'"));
    assertEquals(
        "0",
        scalar("SELECT attempts FROM purchase.outbox WHERE id = '" + behindPoison + "'"),
        "the row behind it was never sent, so it is not attempted");
    assertEquals(
        "true",
        scalar("SELECT next_attempt_at > now() FROM purchase.outbox WHERE id = '" + poison + "'"),
        "the poison row waits for its backoff");
  }

  @Test
  void aBackedOffRowIsNotClaimedAgainUntilItsTime() throws Exception {
    Instant t = Instant.now().minusSeconds(3600);
    UUID aggregateA = Ids.newId();
    UUID poison = insertRow(aggregateA, t);
    insertRow(aggregateA, t.plusSeconds(1));

    Replica r = new Replica(dataSource());
    AtomicInteger published = new AtomicInteger();
    drainAll(r, 100, rows -> failing(rows, poison));
    try (OutboxStore.DrainLease lease = r.tryDrainLock().orElseThrow()) {
      OutboxStore.DrainResult again =
          r.drainOnce(
              lease,
              100,
              rows -> {
                published.incrementAndGet();
                return failing(rows, poison);
              });
      assertEquals(
          0, again.claimed(), "the poison row is in its backoff and its successor waits behind it");
    }
    assertEquals(0, published.get());
  }

  @Test
  void aRowThatKeepsFailingBecomesADeadLetterAndHoldsBackOnlyItsAggregate() throws Exception {
    System.setProperty("storeql.outbox.max-attempts", "2");
    System.setProperty("storeql.outbox.backoff-base-seconds", "1");
    try {
      Instant t = Instant.now().minusSeconds(3600);
      UUID aggregateA = Ids.newId();
      UUID poison = insertRow(aggregateA, t);
      UUID behind = insertRow(aggregateA, t.plusSeconds(1));
      UUID other = insertRow(Ids.newId(), t.plusSeconds(2));
      Replica r = new Replica(dataSource());

      drainAll(r, 100, rows -> failing(rows, poison));
      // time passes: the backoff is over, the row is eligible again
      execute(
          "UPDATE purchase.outbox SET next_attempt_at = now() - interval '1 second' WHERE id = '"
              + poison
              + "'");
      drainAll(r, 100, rows -> failing(rows, poison));

      assertEquals(
          "false",
          scalar("SELECT dead_at IS NULL FROM purchase.outbox WHERE id = '" + poison + "'"),
          "after two attempts the row is a dead letter");
      assertEquals(
          "1",
          scalar(
              "SELECT count(*) FROM purchase.outbox WHERE id = '"
                  + other
                  + "' AND published_at IS NOT NULL"),
          "another aggregate is untouched by the dead letter");
      assertEquals(
          "0",
          scalar(
              "SELECT count(*) FROM purchase.outbox WHERE id = '"
                  + behind
                  + "' AND published_at IS NOT NULL"),
          "the dead letter holds back its own aggregate, in order");
      AtomicInteger seen = new AtomicInteger();
      drainAll(
          r,
          100,
          rows -> {
            seen.addAndGet(rows.size());
            return new OutboxStore.PublishOutcome(List.of(), Map.of());
          });
      assertEquals(0, seen.get(), "a dead letter is never claimed again");
    } finally {
      System.clearProperty("storeql.outbox.max-attempts");
      System.clearProperty("storeql.outbox.backoff-base-seconds");
    }
  }

  @Test
  void noTransactionIsOpenWhileRowsArePublished() throws Exception {
    insertRow(Ids.newId(), Instant.now().minusSeconds(60));
    Replica r = new Replica(dataSource());
    AtomicInteger idleInTransaction = new AtomicInteger(-1);
    drainAll(
        r,
        100,
        rows -> {
          idleInTransaction.set(
              Integer.parseInt(
                  scalar(
                      "SELECT count(*) FROM pg_stat_activity a WHERE a.datname = current_database()"
                          + " AND a.state LIKE 'idle in transaction%'"
                          // the drain's own transaction holds the advisory drain lock and nothing
                          // else; any other lock (a row, a table, a transaction id) is the fault
                          + " AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = a.pid"
                          + " AND l.locktype NOT IN ('advisory', 'virtualxid'))")));
          return new OutboxStore.PublishOutcome(
              rows.stream().map(OutboxStore.PendingOutbox::id).toList(), Map.of());
        });
    assertEquals(
        0,
        idleInTransaction.get(),
        "a broker outage must not hold row or table locks open (the drain lock is an advisory one)");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static UUID insertRow(UUID aggregate, Instant createdAt) throws Exception {
    UUID id = Ids.newId();
    try (Connection c = connection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload, created_at)"
                    + " VALUES (?, 'Probe', 'storeql.test', ?, ?, '{}', ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, Ids.newId());
      ps.setObject(3, aggregate);
      ps.setObject(4, createdAt.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
    return id;
  }

  private static void execute(String sql) throws Exception {
    try (Connection c = connection();
        Statement st = c.createStatement()) {
      st.executeUpdate(sql);
    }
  }

  private static String scalar(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  private static Connection connection() throws Exception {
    return dataSource().getConnection();
  }

  private static DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setURL(PG.jdbcUrl());
    ds.setUser(PG.username());
    ds.setPassword(PG.password());
    ds.setCurrentSchema(SCHEMA);
    return ds;
  }
}
