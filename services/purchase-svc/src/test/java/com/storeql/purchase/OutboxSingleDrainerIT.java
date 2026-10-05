package com.storeql.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxStore;
import com.storeql.test.PostgresSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Two replicas drain one outbox. While the first is still publishing an aggregate's earlier row,
 * the second must not claim that aggregate's later row: Kafka would otherwise see the later event
 * first. Runs against the schema purchase-svc migrates.
 */
class OutboxSingleDrainerIT {

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

  /** The shared drain, over this test's database, as one replica of the service. */
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

  @Test
  void aSecondReplicaDoesNotClaimALaterRowOfAnAggregateWhileTheFirstPublishesIt() throws Exception {
    DataSource ds = dataSource();
    UUID aggregate = Ids.newId();
    insertRow(aggregate, Instant.now().minusSeconds(2)); // r1, written first
    insertRow(aggregate, Instant.now().minusSeconds(1)); // r2, written after r1

    Replica first = new Replica(ds);
    Replica second = new Replica(ds);
    CountDownLatch publishing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<OutboxStore.DrainResult> held =
          pool.submit(
              () -> {
                try (OutboxStore.DrainLease lease = first.tryDrainLock().orElseThrow()) {
                  return first.drainOnce(
                      lease,
                      1,
                      rows -> {
                        publishing.countDown();
                        awaitQuietly(release);
                        return new OutboxStore.PublishOutcome(ids(rows), Map.of());
                      });
                }
              });
      assertEquals(true, publishing.await(10, TimeUnit.SECONDS), "the first replica is publishing");

      // the second replica cannot take the schema's drain right, so it claims nothing at all
      assertTrue(second.tryDrainLock().isEmpty(), "the drain right is held by the first replica");

      release.countDown();
      assertEquals(1, held.get(10, TimeUnit.SECONDS).published().size());
      try (OutboxStore.DrainLease lease = second.tryDrainLock().orElseThrow()) {
        // once free, the second replica takes r2, which is now eligible
        assertEquals(
            1,
            second
                .drainOnce(lease, 1, rows -> new OutboxStore.PublishOutcome(ids(rows), Map.of()))
                .claimed());
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private static List<UUID> ids(List<OutboxStore.PendingOutbox> rows) {
    return rows.stream().map(OutboxStore.PendingOutbox::id).toList();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void insertRow(UUID aggregate, Instant createdAt) throws Exception {
    try (Connection c = connection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
                    + " created_at) VALUES (?, 'Probe', 'storeql.test', ?, ?, '{}', ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.newId());
      ps.setObject(3, aggregate);
      ps.setObject(4, createdAt.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
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
