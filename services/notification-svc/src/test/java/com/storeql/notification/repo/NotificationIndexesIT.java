package com.storeql.notification.repo;

import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The indexes behind this service's tenant-scoped reads and erasures, against the real schema.
 *
 * <p>The erasure of one customer's messages and of one account's filter the log by who it was
 * about, so the index has to serve that, not the tenant's whole history. {@code
 * NotificationRepository.REDACT_FOR_CUSTOMER} asks {@code tenant_id = ? AND subject_id = ? AND
 * redacted_at IS NULL} and {@code REDACT_FOR_ACCOUNT} asks {@code tenant_id IS NULL AND subject_id
 * = ?}; {@code idx_notification_log_tenant_subject} (V2__notification_log.sql) leads with the
 * tenant for both, and there is no index on the subject alone for either to fall back on. The plan
 * test reads those statements from the repository, never a copy of them, so a change to either is
 * planned here too.
 *
 * <p>A business's webhook attempts are read by the tenant data export and erased with the business
 * (common-service's TenantDataRepository: {@code WHERE tenant_id = ? AND (id) > ... ORDER BY id
 * LIMIT ?} and {@code DELETE ... WHERE tenant_id = ?}), and {@code idx_webhook_attempts_tenant}
 * (V6__webhooks.sql) is the index those two use; no other statement does, so this is the test that
 * stops it being dropped as unused.
 */
@HelidonTest
class NotificationIndexesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("notification");

  static {
    System.setProperty("storeql.retention-sweeper.enabled", "false");
  }

  /** Subjects each tenant has messages about: enough that a scan of the tenant's rows shows. */
  private static final int SUBJECTS = 2_000;

  @Inject NotificationRepository repo;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /**
   * {@code count} log rows of one tenant (null: of no business), one per subject. The ids are made
   * in SQL from the prefix, shaped as UUIDv7 ({@code subject(prefix, n)} names the nth subject), so
   * that two calls with two prefixes never meet.
   */
  private static void log(UUID tenant, String prefix, int count) {
    exec(
        PG,
        "INSERT INTO notification.notification_log (id, tenant_id, subject_id, event_id, type,"
            + " channel, recipient, subject, body, status)"
            + " SELECT ('"
            + prefix
            + "-2222-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " "
            + (tenant == null ? "NULL" : "'" + tenant + "'::uuid")
            + ", ('"
            + prefix
            + "-0000-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " ('"
            + prefix
            + "-1111-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " 'WELCOME', 'SMTP', 'a@example.com', 's', 'b', 'SENT'"
            + " FROM generate_series(1, "
            + count
            + ") g");
  }

  /** The id of the {@code n}th subject a {@link #log} call with this prefix wrote about. */
  private static UUID subject(String prefix, int n) {
    return Ids.parse(prefix + "-0000-7000-8000-" + String.format("%012x", n));
  }

  /**
   * The plan of a statement with literals in place of its {@code ?}s, with a sequential scan off:
   * what Postgres does when it can avoid reading the whole table.
   */
  private static String plan(String sql, Object... params) throws SQLException {
    String text = sql;
    for (Object p : params) {
      text = text.replaceFirst("\\?", "'" + p + "'");
    }
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET search_path TO notification");
      st.execute("SET enable_seqscan = off");
      StringBuilder out = new StringBuilder();
      try (ResultSet rs = st.executeQuery("EXPLAIN " + text)) {
        while (rs.next()) out.append(rs.getString(1)).append('\n');
      }
      return out.toString();
    }
  }

  private static List<String> indexes() throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexname || ' ' || indexdef FROM pg_indexes"
                    + " WHERE schemaname = 'notification' AND tablename = 'notification_log'")) {
      while (rs.next()) found.add(rs.getString(1));
    }
    return found;
  }

  @Test
  @DisplayName("The subject index leads with the tenant and holds only rows that name a subject")
  void theSubjectIndexIsTenantLed() throws SQLException {
    String all = String.join("\n", indexes());
    assertThat(all, all, containsString("idx_notification_log_tenant_subject"));
    assertThat(
        "tenant first, then the subject, over rows that have one: " + all,
        all,
        containsString("(tenant_id, subject_id) WHERE (subject_id IS NOT NULL)"));
    assertThat(
        "no index on the subject alone is left beside it: " + all,
        all,
        not(containsString("btree (subject_id)")));
  }

  @Test
  @DisplayName("Erasing a customer's messages is served by the tenant-and-subject index")
  void erasingACustomerUsesTheTenantAndSubjectIndex() throws SQLException {
    UUID tenant = Ids.newId();
    UUID rival = Ids.newId();
    log(tenant, "0190b000", SUBJECTS);
    log(rival, "0190c000", SUBJECTS);
    exec(PG, "ANALYZE notification.notification_log");

    String plan = plan(NotificationRepository.REDACT_FOR_CUSTOMER, tenant, subject("0190b000", 7));
    assertThat(plan, containsString("Index Scan using idx_notification_log_tenant_subject"));
    assertThat(
        "the tenant and the subject are both the index's condition, neither one a filter on rows"
            + " read for the other: "
            + plan,
        plan,
        containsString("Index Cond: ((tenant_id = '" + tenant + "'::uuid) AND (subject_id = '"));
  }

  @Test
  @DisplayName("Erasing a deleted account's platform messages is served by the same index")
  void erasingAnAccountUsesTheTenantAndSubjectIndex() throws SQLException {
    log(null, "0190d000", 300);
    log(Ids.newId(), "0190e000", SUBJECTS);
    exec(PG, "ANALYZE notification.notification_log");

    String plan = plan(NotificationRepository.REDACT_FOR_ACCOUNT, subject("0190d000", 5));
    assertThat(plan, containsString("Index Scan using idx_notification_log_tenant_subject"));
    assertThat(
        "the null tenant leads the index condition: " + plan,
        plan,
        containsString("Index Cond: ((tenant_id IS NULL) AND (subject_id = '"));
  }

  @Test
  @DisplayName("The erasure still touches only the named tenant's rows about the named subject")
  void theErasureStillTouchesOnlyWhatItNames() {
    UUID tenant = Ids.newId();
    UUID rival = Ids.newId();
    UUID subject = Ids.newId();
    // One subject, messaged by two businesses, plus the platform's own message to that account.
    UUID ours = message(tenant, subject);
    UUID theirs = message(rival, subject);
    UUID platform = message(null, subject);

    assertThat(repo.redactForCustomer(tenant, subject), is(1));
    assertThat(redacted(ours), is("true"));
    assertThat("another business's message about the same id", redacted(theirs), is("false"));
    assertThat("the platform's own message", redacted(platform), is("false"));

    assertThat(repo.redactForAccount(subject), is(1));
    assertThat(redacted(platform), is("true"));
    assertThat("a business's message is its own to erase", redacted(theirs), is("false"));
  }

  /**
   * The page the tenant data export reads for one table, and the erasure, as common-service's
   * TenantDataRepository sends them (the cursor's id taken from a JSON row, as it does).
   */
  private static final String EXPORT_PAGE =
      "SELECT to_jsonb(r)::text AS row FROM (SELECT id, tenant_id, delivery_id, attempt"
          + " FROM webhook_attempts WHERE tenant_id = ? AND (id) > (SELECT a.id FROM"
          + " jsonb_populate_record(NULL::webhook_attempts, ?::jsonb) a)"
          + " ORDER BY id LIMIT 500) r ORDER BY r.id";

  private static final String ERASE = "DELETE FROM webhook_attempts WHERE tenant_id = ?";

  /**
   * Two hundred businesses, each with an endpoint, and a delivery and an attempt per 4000 sends.
   */
  private static void seedWebhookAttempts() {
    exec(
        PG,
        "INSERT INTO notification.webhook_endpoints (id, tenant_id, url, description,"
            + " secret_sealed, events, enabled, consecutive_failures, created_by, created_at,"
            + " updated_at) SELECT ('0190a000-aaaa-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " ('0190a000-bbbb-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, 'u', 'd', 's',"
            + " '{OrderPlaced}', true, 0, '0190a000-cccc-7000-8000-000000000001', now(), now()"
            + " FROM generate_series(0, 199) g");
    exec(
        PG,
        "INSERT INTO notification.webhook_deliveries (id, tenant_id, endpoint_id, event_id,"
            + " event_type, payload, status, attempts, created_at)"
            + " SELECT ('0190a000-dddd-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " ('0190a000-bbbb-7000-8000-' || lpad(to_hex(g % 200), 12, '0'))::uuid,"
            + " ('0190a000-aaaa-7000-8000-' || lpad(to_hex(g % 200), 12, '0'))::uuid,"
            + " ('0190a000-eeee-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " 'OrderPlaced', '{}', 'DELIVERED', 1, now() FROM generate_series(1, 4000) g");
    exec(
        PG,
        "INSERT INTO notification.webhook_attempts (id, tenant_id, delivery_id, attempt,"
            + " attempted_at, duration_ms)"
            + " SELECT ('0190a000-ffff-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid,"
            + " ('0190a000-bbbb-7000-8000-' || lpad(to_hex(g % 200), 12, '0'))::uuid,"
            + " ('0190a000-dddd-7000-8000-' || lpad(to_hex(g), 12, '0'))::uuid, 1, now(), 5"
            + " FROM generate_series(1, 4000) g");
    exec(PG, "ANALYZE notification.webhook_attempts");
  }

  @Test
  @DisplayName("A business's webhook attempts are exported and erased through the tenant-led index")
  void aBusinessesAttemptsAreReadThroughTheTenantIndex() throws SQLException {
    seedWebhookAttempts();
    UUID tenant = Ids.parse("0190a000-bbbb-7000-8000-000000000007");

    String page = plan(EXPORT_PAGE, tenant, "{\"id\":\"0190a000-ffff-7000-8000-000000000010\"}");
    assertThat(page, containsString("idx_webhook_attempts_tenant"));
    assertThat(
        "the tenant is the index's own condition, not a filter on rows read for another: " + page,
        page,
        containsString("Index Cond: ((tenant_id = '" + tenant + "'::uuid) AND (id > "));

    String erase = plan(ERASE, tenant);
    assertThat(erase, containsString("idx_webhook_attempts_tenant"));
  }

  private static UUID message(UUID tenant, UUID subject) {
    UUID id = Ids.newId();
    exec(
        PG,
        "INSERT INTO notification.notification_log (id, tenant_id, subject_id, event_id, type,"
            + " channel, recipient, subject, body, status) VALUES ('"
            + id
            + "', "
            + (tenant == null ? "NULL" : "'" + tenant + "'")
            + ", '"
            + subject
            + "', '"
            + Ids.newId()
            + "', 'WELCOME', 'SMTP', 'a@example.com', 's', 'b', 'SENT')");
    return id;
  }

  private static String redacted(UUID id) {
    return scalar(
        PG,
        "SELECT (redacted_at IS NOT NULL)::text FROM notification.notification_log WHERE id = '"
            + id
            + "'");
  }
}
