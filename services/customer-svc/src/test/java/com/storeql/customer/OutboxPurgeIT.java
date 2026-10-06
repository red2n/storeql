package com.storeql.customer;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.customer.repo.CustomerRepository;
import com.storeql.ids.Ids;
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
 * sort of the whole table on every batch (V1__init.sql and V2__processed_events.sql).
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
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
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
}
