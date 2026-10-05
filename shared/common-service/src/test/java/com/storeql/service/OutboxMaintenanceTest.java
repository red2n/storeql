package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** The outbox relay's drain loop and ordering, and the bounded purge of operational rows. */
class OutboxMaintenanceTest {

  // ── drain loop ────────────────────────────────────────────────────────────

  private static OutboxPublisher publisherWith(OutboxStore store) throws Exception {
    OutboxPublisher p = new OutboxPublisher();
    Field f = OutboxPublisher.class.getDeclaredField("store");
    f.setAccessible(true);
    f.set(p, store);
    return p;
  }

  @Test
  void aFullBatchIsFollowedAtOnceByTheNextNotByTheNextTick() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<UUID> full = new ArrayList<>();
    for (int i = 0; i < 100; i++) full.add(Ids.newId());
    OutboxStore store =
        (lease, limit, publish) -> {
          int n = calls.incrementAndGet();
          // three full claims, then a short one: the backlog is cleared within one tick
          return new OutboxStore.DrainResult(n <= 3 ? full.size() : 1, List.of());
        };
    publisherWith(store).drainQuietly();
    assertEquals(4, calls.get());
  }

  @Test
  void anEmptyOutboxIsOneQueryPerTick() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    OutboxStore store =
        (lease, limit, publish) -> {
          calls.incrementAndGet();
          return new OutboxStore.DrainResult(0, List.of());
        };
    publisherWith(store).drainQuietly();
    assertEquals(1, calls.get());
  }

  // ── per-aggregate order after a failed send ───────────────────────────────

  @Test
  void aFailedSendHoldsBackLaterRowsOfTheSameAggregateOnly() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    MockProducer<String, String> producer =
        new MockProducer<>(true, new StringSerializer(), new StringSerializer()) {
          @Override
          public synchronized java.util.concurrent.Future<
                  org.apache.kafka.clients.producer.RecordMetadata>
              send(org.apache.kafka.clients.producer.ProducerRecord<String, String> record) {
            // the first send (a1) fails, every other one is acknowledged
            if (sends.incrementAndGet() == 1) {
              return java.util.concurrent.CompletableFuture.failedFuture(
                  new RuntimeException("broker said no"));
            }
            return super.send(record);
          }
        };
    OutboxPublisher p = publisherWith(null);
    Field f = OutboxPublisher.class.getDeclaredField("producer");
    f.setAccessible(true);
    f.set(p, producer);
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    var a1 = new OutboxStore.PendingOutbox(Ids.newId(), a, "t", "{}");
    var a2 = new OutboxStore.PendingOutbox(Ids.newId(), a, "t", "{}");
    var b1 = new OutboxStore.PendingOutbox(Ids.newId(), b, "t", "{}");
    List<UUID> published = p.publishBatch(List.of(a1, a2, b1)).published();
    assertEquals(List.of(b1.id()), published);
    assertFalse(published.contains(a2.id()), "a2 must not overtake a1");
    // Not marked published is not enough: a2 must never reach Kafka ahead of a1's retry.
    assertEquals(2, sends.get(), "a1 and b1 are sent; a2 is held back and not sent");
  }

  /**
   * A batch with the same aggregate twice in a row and a failure on the first: the second row must
   * be sent only after the first is acknowledged, so the retry is what Kafka sees first.
   */
  @Test
  void aRetriedRowIsSentBeforeTheRowsWrittenAfterIt() throws Exception {
    List<String> sent = new ArrayList<>();
    java.util.Set<String> failOnce = new java.util.HashSet<>(List.of("a1"));
    MockProducer<String, String> producer =
        new MockProducer<>(true, new StringSerializer(), new StringSerializer()) {
          @Override
          public synchronized java.util.concurrent.Future<
                  org.apache.kafka.clients.producer.RecordMetadata>
              send(org.apache.kafka.clients.producer.ProducerRecord<String, String> record) {
            sent.add(record.value());
            if (failOnce.remove(record.value())) {
              return java.util.concurrent.CompletableFuture.failedFuture(
                  new RuntimeException("broker said no"));
            }
            return super.send(record);
          }
        };
    OutboxPublisher p = publisherWith(null);
    Field f = OutboxPublisher.class.getDeclaredField("producer");
    f.setAccessible(true);
    f.set(p, producer);
    UUID a = Ids.newId();
    var a1 = new OutboxStore.PendingOutbox(Ids.newId(), a, "t", "a1");
    var a2 = new OutboxStore.PendingOutbox(Ids.newId(), a, "t", "a2");
    p.publishBatch(List.of(a1, a2));
    assertEquals(List.of("a1"), sent, "the failed row goes out alone; its successor waits");
    // the next tick: the retry goes first, then the row after it
    sent.clear();
    List<UUID> again = p.publishBatch(List.of(a1, a2)).published();
    assertEquals(List.of(a1.id(), a2.id()), again);
    assertEquals(List.of("a1", "a2"), sent);
  }

  // ── purge ─────────────────────────────────────────────────────────────────

  /** Records every statement with its bound parameters; may fail statements by predicate. */
  private static final class Recorder {
    final List<String> sql = new ArrayList<>();
    final List<List<Object>> params = new ArrayList<>();
    java.util.function.Predicate<String> failWith42703;
    int updateResult;

    Connection connection() {
      return (Connection)
          Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) return statement((String) args[0]);
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                return null;
              });
    }

    private PreparedStatement statement(String statementSql) {
      List<Object> bound = new ArrayList<>();
      return (PreparedStatement)
          Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) -> {
                if ("setObject".equals(method.getName()) || "setInt".equals(method.getName())) {
                  bound.add(args[1]);
                  return null;
                }
                if ("executeUpdate".equals(method.getName())) {
                  if (failWith42703 != null && failWith42703.test(statementSql)) {
                    throw new SQLException("column does not exist", "42703");
                  }
                  sql.add(statementSql);
                  params.add(bound);
                  return updateResult;
                }
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                return null;
              });
    }
  }

  private static final class Repo extends BaseOutboxRepository {
    Repo(Recorder rec) throws Exception {
      Field f = BaseJdbcRepository.class.getDeclaredField("dataSource");
      f.setAccessible(true);
      f.set(
          this,
          Proxy.newProxyInstance(
              Thread.currentThread().getContextClassLoader(),
              new Class<?>[] {DataSource.class},
              (p, m, a) -> "getConnection".equals(m.getName()) ? rec.connection() : null));
    }
  }

  @Test
  void purgeDeletesOnlyPublishedRowsOlderThanTheCutoffInABoundedBatch() throws Exception {
    Recorder rec = new Recorder();
    rec.updateResult = 7;
    Instant cutoff = Instant.parse("2026-09-01T00:00:00Z");
    int n = new Repo(rec).purgePublished(cutoff, 250);
    assertEquals(7, n);
    String sql = rec.sql.get(0);
    assertTrue(sql.contains("published_at IS NOT NULL"), sql);
    assertTrue(sql.contains("published_at < ?"), sql);
    assertTrue(sql.contains("LIMIT ?"), sql);
    assertTrue(sql.contains("SKIP LOCKED"), sql);
    assertEquals(250, rec.params.get(0).get(1));
  }

  @Test
  void processedEventsPurgeFallsBackToCreatedAtWhereThereIsNoProcessedAt() throws Exception {
    Recorder rec = new Recorder();
    rec.failWith42703 = s -> s.contains("processed_at");
    rec.updateResult = 3;
    Repo repo = new Repo(rec);
    assertEquals(3, repo.purgeProcessedEvents(Instant.now(), 100));
    assertTrue(rec.sql.get(0).contains("created_at < ?"));
    // remembered: the next call goes straight to created_at
    rec.sql.clear();
    repo.purgeProcessedEvents(Instant.now(), 100);
    assertEquals(1, rec.sql.size());
  }

  @Test
  void theSweepKeepsPurgingWhileBatchesAreFullAndStopsAtTheCap() throws Exception {
    AtomicInteger purged = new AtomicInteger();
    OutboxStore store =
        new OutboxStore() {
          @Override
          public DrainResult drainOnce(
              DrainLease lease, int l, Function<List<PendingOutbox>, PublishOutcome> p) {
            return new DrainResult(0, List.of());
          }

          @Override
          public int purgePublished(Instant cutoff, int batch) {
            assertTrue(cutoff.isBefore(Instant.now().minusSeconds(6L * 86400)), "7 days back");
            purged.incrementAndGet();
            return batch; // always full: only the cap stops it
          }

          @Override
          public int purgeProcessedEvents(Instant cutoff, int batch) {
            assertTrue(cutoff.isBefore(Instant.now().minusSeconds(29L * 86400)), "30 days back");
            return 0;
          }
        };
    publisherWith(store).purgeQuietly();
    assertEquals(50, purged.get(), "storeql.outbox.purge.max-batches default");
  }
}
