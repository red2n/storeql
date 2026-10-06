package com.storeql.notification;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.notification.repo.RetentionRunRepository;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled purge of this service's operational tables, against its real schema: published
 * outbox rows and consumer dedupe rows older than their retention go, in bounded batches, and
 * nothing else does; and the statements the purge runs are served by an index, not by a scan and
 * sort of the whole table on every batch (indexes idx_outbox_published in V4__outbox.sql and
 * idx_processed_events_processed_at in V1__init.sql).
 *
 * <p>The relay's own claim is planned here too, as common-service sends it: its ordered scan reads
 * idx_outbox_claim, which leaves the dead letters out, and its check for an earlier waiting row of
 * the same aggregate reads idx_outbox_aggregate_pending. The outbox keeps no index of every
 * unpublished row by created_at (idx_outbox_unpublished): it would also hold the dead letters,
 * which the claim's ordered scan never reads, and the outcome of a drain goes by primary key.
 */
@HelidonTest
class OutboxPurgeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("notification");

  static {
    System.setProperty("storeql.retention-sweeper.enabled", "false");
  }

  /** The statements as common-service's BaseOutboxRepository sends them, with literals bound. */
  private static final String PURGE_OUTBOX =
      "DELETE FROM notification.outbox WHERE id IN (SELECT id FROM notification.outbox"
          + " WHERE published_at IS NOT NULL AND published_at < now() - interval '7 days'"
          + " ORDER BY published_at ASC LIMIT 1000 FOR UPDATE SKIP LOCKED)";

  private static final String PURGE_PROCESSED =
      "DELETE FROM notification.processed_events WHERE (event_id, consumer) IN"
          + " (SELECT event_id, consumer FROM notification.processed_events"
          + " WHERE processed_at < now() - interval '30 days'"
          + " ORDER BY processed_at ASC LIMIT 1000 FOR UPDATE SKIP LOCKED)";

  /**
   * The relay's claim, as common-service's BaseOutboxRepository.claim sends it (its text, with the
   * limit bound to the default batch size and the schema left to the search path).
   */
  private static final String CLAIM =
      "SELECT o.id, o.aggregate_id, o.topic, o.payload FROM outbox o"
          + " WHERE o.published_at IS NULL AND o.dead_at IS NULL"
          + " AND o.next_attempt_at <= now()"
          + " AND NOT EXISTS (SELECT 1 FROM outbox p"
          + "   WHERE p.aggregate_id = o.aggregate_id AND p.published_at IS NULL"
          + "   AND (p.dead_at IS NOT NULL OR p.next_attempt_at > now())"
          + "   AND (p.created_at, p.id) < (o.created_at, o.id))"
          + " ORDER BY o.created_at, o.id LIMIT 100";

  private static final String TWO_IDS =
      "0190f000-2222-7000-8000-000000000001,0190f000-2222-7000-8000-000000000002";

  /** The outcome statements of the same class: published rows, and a failure's retry or burial. */
  private static final String MARK_PUBLISHED =
      "UPDATE outbox SET published_at = now() WHERE id = ANY('{" + TWO_IDS + "}'::uuid[])";

  private static final String RECORD_FAILURE =
      "UPDATE outbox o SET attempts = o.attempts + 1, last_error = left(f.err, 500),"
          + " next_attempt_at = now() + make_interval(secs => LEAST(900::double precision,"
          + "   1::double precision * power(2, o.attempts))),"
          + " dead_at = CASE WHEN o.attempts + 1 >= 10 THEN now() END"
          + " FROM unnest('{"
          + TWO_IDS
          + "}'::uuid[], '{a,b}'::text[]) AS f(id, err)"
          + " WHERE o.id = f.id AND o.published_at IS NULL"
          + " RETURNING o.id, o.event_type, o.aggregate_id, o.tenant_id, o.attempts,"
          + " o.dead_at IS NOT NULL AS dead, o.last_error";

  @Inject RetentionRunRepository repo;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /** An outbox row this test made: created long ago, published at the given time or never. */
  private static UUID outbox(String publishedAt) {
    UUID id = Ids.newId();
    exec(
        PG,
        "INSERT INTO notification.outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
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

  /**
   * A relay's backlog, in the proportions of a bad hour: 3,000 rows waiting, one aggregate each, in
   * the order they were written; 300 dead letters; 300 backing off for an hour; 2,000 published
   * recently (inside the retention, so the purge leaves them). The ids are made in SQL, shaped as
   * UUIDv7. A probe row is of event type ClaimProbe, which {@link #clearBacklog} removes.
   */
  private static void seedBacklog() {
    exec(
        PG,
        "INSERT INTO notification.outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
            + " created_at, published_at, next_attempt_at, dead_at)"
            + " SELECT ('0190f000-2222-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " 'ClaimProbe', 'storeql.test.claim-probe', '0190f000-3333-7000-8000-000000000001'::uuid,"
            + " ('0190f000-4444-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, '{}',"
            + " now() - (g || ' seconds')::interval,"
            + " CASE WHEN g > 3600 THEN now() - interval '1 hour' END,"
            + " CASE WHEN g BETWEEN 3301 AND 3600 THEN now() + interval '1 hour' ELSE now() END,"
            + " CASE WHEN g BETWEEN 3001 AND 3300 THEN now() END"
            + " FROM generate_series(1, 5600) g");
    // An aggregate whose earlier row is dead: its later row waits behind it, so the claim's
    // anti-join has something to find.
    exec(
        PG,
        "INSERT INTO notification.outbox (id, event_type, topic, tenant_id, aggregate_id, payload,"
            + " created_at) VALUES ('0190f000-2222-7000-8000-00000000ffff', 'ClaimProbe',"
            + " 'storeql.test.claim-probe', '0190f000-3333-7000-8000-000000000001',"
            + " '0190f000-4444-7000-8000-000000000bb9', '{}', now())");
    exec(PG, "ANALYZE notification.outbox");
  }

  private static void clearBacklog() {
    exec(PG, "DELETE FROM notification.outbox WHERE event_type = 'ClaimProbe'");
  }

  private static UUID processed(int daysAgo) {
    UUID id = Ids.newId();
    exec(
        PG,
        "INSERT INTO notification.processed_events (event_id, consumer, processed_at) VALUES ('"
            + id
            + "','purge-probe', now() - interval '"
            + daysAgo
            + " days')");
    return id;
  }

  private static String probeRows() {
    return scalar(PG, "SELECT count(*) FROM notification.outbox WHERE event_type = 'PurgeProbe'");
  }

  private static String dedupeRows() {
    return scalar(
        PG, "SELECT count(*) FROM notification.processed_events WHERE consumer = 'purge-probe'");
  }

  private static boolean exists(String table, String idColumn, UUID id) {
    return "1"
        .equals(
            scalar(
                PG,
                "SELECT count(*) FROM notification."
                    + table
                    + " WHERE "
                    + idColumn
                    + " = '"
                    + id
                    + "'"));
  }

  /** What Postgres would do with a statement if it could not simply read the whole table. */
  private static String plan(String sql) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      // the service's own schema, as its connections have it: the statements are sent unqualified
      st.execute("SET search_path TO notification");
      st.execute("SET enable_seqscan = off");
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

  @Test
  @DisplayName(
      "The relay's claim, dead letters and backoff filters and all, scans idx_outbox_claim and"
          + " checks each aggregate through idx_outbox_aggregate_pending")
  void theClaimIsServedByItsOwnIndex() throws SQLException {
    seedBacklog();
    try {
      String claim = plan(CLAIM);
      assertThat(claim, claim, containsString("Index Scan using idx_outbox_claim"));
      assertThat(
          "the partial index holds them in the claim's order, so nothing is sorted: " + claim,
          claim,
          not(containsString("Sort")));
      assertThat(
          "an aggregate's earlier rows are found through the aggregate's own index: " + claim,
          claim,
          containsString("idx_outbox_aggregate_pending"));
      assertThat(
          "no index of every unpublished row by created_at is read: " + claim,
          claim,
          not(containsString("idx_outbox_unpublished")));
    } finally {
      clearBacklog();
    }
  }

  @Test
  @DisplayName(
      "The outcome statements go by primary key, and no index of every unpublished row by"
          + " created_at is kept")
  void noIndexIsKeptForWhatNoStatementReads() throws SQLException {
    seedBacklog();
    try {
      String published = plan(MARK_PUBLISHED);
      assertThat(published, published, containsString("outbox_pkey"));
      String failed = plan(RECORD_FAILURE);
      assertThat(failed, failed, containsString("outbox_pkey"));
    } finally {
      clearBacklog();
    }

    String indexes =
        scalar(
            PG,
            "SELECT string_agg(indexname, ' ' ORDER BY indexname) FROM pg_indexes"
                + " WHERE schemaname = 'notification' AND tablename = 'outbox'");
    assertThat(
        "the claim, the aggregate check and the purge have an index each; the rows still waiting"
            + " (dead letters included) have none by created_at, because the claim's ordered scan"
            + " never reads a dead letter and no other statement reads them in that order",
        indexes,
        is("idx_outbox_aggregate_pending idx_outbox_claim idx_outbox_published outbox_pkey"));
  }
}
