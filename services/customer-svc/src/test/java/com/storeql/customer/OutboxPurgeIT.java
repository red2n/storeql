package com.storeql.customer;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.customer.repo.CustomerRepository;
import com.storeql.ids.Ids;
import com.storeql.service.OutboxStore.DrainLease;
import com.storeql.service.OutboxStore.PendingOutbox;
import com.storeql.service.OutboxStore.PublishOutcome;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled purge of this service's operational tables, against its real schema: published
 * outbox rows and consumer dedupe rows older than their retention go, in bounded batches, and
 * nothing else does; and the statements the purge runs are served by an index, not by a scan and
 * sort of the whole table on every batch (V1__init.sql and V2__processed_events.sql). The relay's
 * own claim is planned here too, as common-service's BaseOutboxRepository sends it, over a table
 * with waiting, dead, backing-off and held rows: its ordered scan must be served by the outbox's
 * claim index and its check for an earlier waiting row of the same aggregate by the per-aggregate
 * index; the outcome of a drain goes by primary key and the purge reads its own index, and the
 * outbox keeps no index of every unpublished row by created_at (idx_outbox_unpublished).
 */
@HelidonTest
class OutboxPurgeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  static {
    System.setProperty("storeql.customer.loyalty.sweep-seconds", "0");
  }

  /** The statements as common-service's BaseOutboxRepository sends them, with literals bound. */
  private static final String PURGE_OUTBOX =
      "DELETE FROM customer.outbox WHERE id IN (SELECT id FROM customer.outbox"
          + " WHERE published_at IS NOT NULL AND published_at < now() - interval '7 days'"
          + " ORDER BY published_at ASC LIMIT 1000 FOR UPDATE SKIP LOCKED)";

  private static final String PURGE_PROCESSED =
      "DELETE FROM customer.processed_events WHERE (event_id, consumer) IN"
          + " (SELECT event_id, consumer FROM customer.processed_events"
          + " WHERE processed_at < now() - interval '30 days'"
          + " ORDER BY processed_at ASC LIMIT 1000 FOR UPDATE SKIP LOCKED)";

  /**
   * The relay's claim, as common-service's BaseOutboxRepository.claim sends it (its dead-letter and
   * backoff conditions, the per-aggregate hold and its order), with the limit bound to 100.
   */
  private static final String CLAIM =
      "SELECT o.id, o.aggregate_id, o.topic, o.payload FROM customer.outbox o"
          + " WHERE o.published_at IS NULL AND o.dead_at IS NULL"
          + " AND o.next_attempt_at <= now()"
          + " AND NOT EXISTS (SELECT 1 FROM customer.outbox p"
          + "   WHERE p.aggregate_id = o.aggregate_id AND p.published_at IS NULL"
          + "   AND (p.dead_at IS NOT NULL OR p.next_attempt_at > now())"
          + "   AND (p.created_at, p.id) < (o.created_at, o.id))"
          + " ORDER BY o.created_at, o.id LIMIT 100";

  private static final String CLAIM_TOPIC = "storeql.test.claim-probe";

  @Inject CustomerRepository repo;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /** An outbox row this test made: created long ago, published at the given time or never. */
  private static UUID outbox(String publishedAt) {
    UUID id = Ids.newId();
    exec(
        PG,
        "INSERT INTO customer.outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
            + " created_at, published_at) VALUES ('"
            + id
            + "','PurgeProbe','storeql.test.purge-probe','"
            + Ids.newId()
            + "','"
            + id
            + "','{}', now() - interval '12 days', "
            + publishedAt
            + ")");
    return id;
  }

  private static UUID processed(int daysAgo) {
    UUID id = Ids.newId();
    exec(
        PG,
        "INSERT INTO customer.processed_events (event_id, consumer, processed_at) VALUES ('"
            + id
            + "','purge-probe', now() - interval '"
            + daysAgo
            + " days')");
    return id;
  }

  private static String probeRows() {
    return scalar(PG, "SELECT count(*) FROM customer.outbox WHERE event_type = 'PurgeProbe'");
  }

  private static String dedupeRows() {
    return scalar(
        PG, "SELECT count(*) FROM customer.processed_events WHERE consumer = 'purge-probe'");
  }

  private static boolean exists(String table, String idColumn, UUID id) {
    return "1"
        .equals(
            scalar(
                PG,
                "SELECT count(*) FROM customer."
                    + table
                    + " WHERE "
                    + idColumn
                    + " = '"
                    + id
                    + "'"));
  }

  /** What Postgres would do with a statement if it could not simply read the whole table. */
  private static String plan(String sql) throws SQLException {
    return plan(sql, false);
  }

  /**
   * The same, and with bitmap scans off too when asked: only an ordered index scan is left to serve
   * an ORDER BY ... LIMIT, if an index can, and a Sort in the plan shows that none could.
   */
  private static String plan(String sql, boolean noBitmap) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      if (noBitmap) st.execute("SET enable_bitmapscan = off");
      StringBuilder out = new StringBuilder();
      try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
        while (rs.next()) out.append(rs.getString(1)).append('\n');
      }
      return out.toString();
    }
  }

  @Test
  @DisplayName(
      "Published outbox rows past retention are purged in bounded batches; unpublished and recent stay")
  void publishedRowsPastRetentionGoInBatches() {
    UUID a = outbox("now() - interval '10 days'");
    UUID b = outbox("now() - interval '9 days'");
    UUID c = outbox("now() - interval '8 days'");
    UUID recent = outbox("now() - interval '1 hour'");
    UUID waiting = outbox("NULL"); // twelve days old and never relayed: a backlog, not history
    Instant cutoff = Instant.now().minus(Duration.ofDays(7));

    assertThat("a batch of two", repo.purgePublished(cutoff, 2), is(2));
    assertThat("the rest", repo.purgePublished(cutoff, 2), is(1));
    assertThat("nothing left to purge", repo.purgePublished(cutoff, 2), is(0));

    for (UUID gone : new UUID[] {a, b, c}) {
      assertThat("purged " + gone, exists("outbox", "id", gone), is(false));
    }
    assertThat("a recently published row is kept", exists("outbox", "id", recent), is(true));
    assertThat("an unpublished row is never purged", exists("outbox", "id", waiting), is(true));
    assertThat(probeRows(), is("2"));
  }

  @Test
  @DisplayName(
      "Consumer dedupe rows past retention are purged in bounded batches; recent ones stay")
  void dedupeRowsPastRetentionGoInBatches() {
    UUID a = processed(40);
    UUID b = processed(35);
    UUID c = processed(31);
    UUID recent = processed(1);
    Instant cutoff = Instant.now().minus(Duration.ofDays(30));

    assertThat("a batch of two", repo.purgeProcessedEvents(cutoff, 2), is(2));
    assertThat("the rest", repo.purgeProcessedEvents(cutoff, 2), is(1));
    assertThat("nothing left to purge", repo.purgeProcessedEvents(cutoff, 2), is(0));

    for (UUID gone : new UUID[] {a, b, c}) {
      assertThat("purged " + gone, exists("processed_events", "event_id", gone), is(false));
    }
    assertThat(
        "a recent mark must outlive redelivery",
        exists("processed_events", "event_id", recent),
        is(true));
    assertThat(dedupeRows(), is("1"));
  }

  @Test
  @DisplayName("The purge statements are served by an index, not by a scan of the whole table")
  void thePurgeStatementsUseTheirIndexes() throws SQLException {
    String outboxPlan = plan(PURGE_OUTBOX);
    assertThat(outboxPlan, outboxPlan, containsString("idx_outbox_published"));
    String processedPlan = plan(PURGE_PROCESSED);
    assertThat(processedPlan, processedPlan, containsString("idx_processed_events_processed_at"));
  }

  /** One row for the claim tests, as it is written now; {@code ageMinutes} is how old it is. */
  private static UUID claimRow(
      PreparedStatement ps,
      UUID aggregate,
      int ageMinutes,
      boolean published,
      boolean dead,
      Instant nextAttempt)
      throws SQLException {
    Instant now = Instant.now();
    UUID id = Ids.newId();
    ps.setObject(1, id);
    ps.setObject(2, Ids.newId());
    ps.setObject(3, aggregate);
    ps.setObject(4, now.minus(Duration.ofMinutes(ageMinutes)).atOffset(ZoneOffset.UTC));
    if (published) {
      // Recently, so no purge test of this class (cutoff seven days) ever takes it.
      ps.setObject(5, now.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC));
    } else {
      ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
    }
    ps.setInt(6, dead ? 10 : 0);
    ps.setObject(7, nextAttempt.atOffset(ZoneOffset.UTC));
    if (dead) {
      ps.setObject(8, now.atOffset(ZoneOffset.UTC));
    } else {
      ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
    }
    ps.addBatch();
    return id;
  }

  /**
   * Writes an outbox with a backlog, the case its indexes are for: four hundred rows published, two
   * thousand waiting, and in front of them (the oldest rows) five dead letters, five rows backing
   * off and, for three of each, a later healthy row of the same aggregate, which the claim must
   * hold back. Everything unpublished is older than any other test's rows of this class, so the
   * claim's first hundred are these. Returns the waiting ids, oldest first, and fills {@code
   * notClaimable} with the sixteen rows the claim must never return.
   */
  private static List<UUID> claimFixture(Set<UUID> notClaimable) throws SQLException {
    final int base = 40_000; // minutes: nearly twenty-eight days, older than the purge tests' rows
    List<UUID> waiting = new ArrayList<>();
    Instant now = Instant.now();
    Instant due = now.minusSeconds(60);
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO customer.outbox (id, event_type, topic, tenant_id, aggregate_id,"
                    + " payload, created_at, published_at, attempts, next_attempt_at, dead_at)"
                    + " VALUES (?,'ClaimProbe','"
                    + CLAIM_TOPIC
                    + "',?,?,'{}',?,?,?,?,?)")) {
      for (int i = 0; i < 400; i++) {
        claimRow(ps, Ids.newId(), 600 + i, true, false, now);
      }
      for (int i = 0; i < 5; i++) {
        UUID dead = Ids.newId();
        UUID backingOff = Ids.newId();
        notClaimable.add(claimRow(ps, dead, base - i, false, true, now));
        notClaimable.add(
            claimRow(ps, backingOff, base - 10 - i, false, false, now.plusSeconds(600)));
        if (i < 3) {
          notClaimable.add(claimRow(ps, dead, base - 50 - i, false, false, due));
          notClaimable.add(claimRow(ps, backingOff, base - 60 - i, false, false, due));
        }
      }
      for (int i = 0; i < 2000; i++) {
        waiting.add(claimRow(ps, Ids.newId(), base - 100 - i, false, false, due));
      }
      ps.executeBatch();
    }
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("ANALYZE customer.outbox");
    }
    return waiting;
  }

  @Test
  @DisplayName(
      "The outbox keeps the indexes its relay and purge read and no other: no index of every"
          + " unpublished row by created_at, and none led by a business")
  void theOutboxHasOnlyTheIndexesTheRelayReads() throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexname || ' ' || indexdef FROM pg_indexes"
                    + " WHERE schemaname = 'customer' AND tablename = 'outbox' ORDER BY indexname")) {
      while (rs.next()) found.add(rs.getString(1));
    }
    String all = String.join("\n", found);
    assertThat(all, found.size(), is(4));
    assertThat(
        "the claim's own index, in the claim's order, over rows that may publish",
        all.contains("(created_at, id) WHERE ((published_at IS NULL) AND (dead_at IS NULL))"),
        is(true));
    assertThat(
        "the per-aggregate check's index, over rows still waiting",
        all.contains("(aggregate_id, created_at, id) WHERE (published_at IS NULL)"),
        is(true));
    assertThat(
        "the purge's index, over delivered rows only",
        all.contains("(published_at) WHERE (published_at IS NOT NULL)"),
        is(true));
    assertThat(all, all, not(containsString("idx_outbox_unpublished")));
    assertThat(
        "the outbox is cross-tenant on purpose: " + all, all, not(containsString("(tenant_id")));
  }

  @Test
  @DisplayName(
      "The relay's real claim is served by the claim index and the per-aggregate index, sorts"
          + " nothing, and returns exactly what the shared repository claims")
  void theClaimIsServedByItsIndexes() throws SQLException {
    Set<UUID> notClaimable = new HashSet<>();
    List<UUID> waiting = claimFixture(notClaimable);
    assertThat("two thousand rows are waiting", waiting.size(), is(2000));
    assertThat("ten dead or backing off, six held behind them", notClaimable.size(), is(16));

    String claimPlan = plan(CLAIM, true);
    assertThat(claimPlan, claimPlan, containsString("idx_outbox_claim"));
    assertThat(
        "the per-aggregate hold reads its own index: " + claimPlan,
        claimPlan,
        containsString("idx_outbox_aggregate_pending"));
    assertThat(
        "the claim index is in the claim's order: " + claimPlan,
        claimPlan,
        not(containsString("Sort")));

    List<UUID> copied = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(CLAIM)) {
      while (rs.next()) {
        if (CLAIM_TOPIC.equals(rs.getString("topic"))) copied.add(rs.getObject("id", UUID.class));
      }
    }
    assertThat(
        "the hundred oldest that may publish, in order, none held back or dead",
        copied,
        is(waiting.subList(0, 100)));

    // The shared repository's own claim, on the same rows: the copy above is that statement.
    List<UUID> real = new ArrayList<>();
    try (DrainLease lease = repo.tryDrainLock().orElseThrow()) {
      repo.drainOnce(
          lease,
          100,
          rows -> {
            for (PendingOutbox r : rows) {
              if (CLAIM_TOPIC.equals(r.topic())) real.add(r.id());
            }
            return new PublishOutcome(List.of(), Map.of());
          });
    }
    assertThat("the relay claims what the planned statement returns", real, is(copied));
    for (UUID held : notClaimable) {
      assertThat("never claimed: " + held, real.contains(held), is(false));
    }
  }
}
