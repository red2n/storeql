package com.storeql.purchase.repo;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Accounting.Attempt;
import com.storeql.purchase.domain.Accounting.Connection;
import com.storeql.purchase.domain.Accounting.Counts;
import com.storeql.purchase.domain.Accounting.Mapping;
import com.storeql.purchase.domain.Accounting.Sync;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The accounting connection a business holds, its account mapping, and every journal's journey to
 * the package (17.9). A connection is one per business; a sync is one per connection and journal at
 * the unique pair; attempts are append-only.
 */
@ApplicationScoped
public class AccountingRepository extends BaseJdbcRepository {

  private static final String CONNECTION_COLUMNS =
      "id, tenant_id, provider, status, settings, credentials_sealed, sync_from, created_by,"
          + " created_at, updated_at, last_sync_at, last_error, disabled_reason";
  private static final String SYNC_CORE =
      "s.id, s.tenant_id, s.connection_id, s.journal_id, s.status, s.attempts, s.external_id,"
          + " s.last_error, s.next_attempt_at, s.leased_until, s.created_at, s.delivered_at";
  private static final String SYNC_WITH_JOURNAL =
      "SELECT "
          + SYNC_CORE
          + ", j.entry_date, j.description, j.source_type, j.total FROM accounting_syncs s"
          + " JOIN (SELECT journal_id, MIN(entry_date) AS entry_date, MIN(description) AS description,"
          + " MIN(source_type) AS source_type, SUM(debit) AS total FROM nominal_ledger_entries"
          + " WHERE tenant_id = ? GROUP BY journal_id) j ON j.journal_id = s.journal_id";

  // ── connections ─────────────────────────────────────────────────────────────

  public Optional<Connection> connection(UUID tenantId) {
    return query(
            "SELECT " + CONNECTION_COLUMNS + " FROM accounting_connections WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            AccountingRepository::readConnection,
            "accounting connection")
        .stream()
        .findFirst();
  }

  public List<Connection> activeConnections() {
    return query(
        "SELECT "
            + CONNECTION_COLUMNS
            + " FROM accounting_connections WHERE status = ? ORDER BY id",
        ps -> ps.setString(1, Accounting.ACTIVE),
        AccountingRepository::readConnection,
        "active accounting connections");
  }

  /** The business's one connection: whatever it had is removed, with its mapping and its log. */
  public Connection replace(Connection c) {
    inTx(
        connection -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "DELETE FROM accounting_connections WHERE tenant_id = ?")) {
            ps.setObject(1, c.tenantId());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "INSERT INTO accounting_connections ("
                      + CONNECTION_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setString(3, c.provider());
            ps.setString(4, c.status());
            ps.setString(5, settingsJson(c.settings()));
            ps.setString(6, c.credentialsSealed());
            ps.setObject(7, c.syncFrom());
            ps.setObject(8, c.createdBy());
            ps.setObject(9, at(c.createdAt()));
            ps.setObject(10, at(c.updatedAt()));
            ps.setObject(11, c.lastSyncAt() == null ? null : at(c.lastSyncAt()));
            ps.setString(12, c.lastError());
            ps.setString(13, c.disabledReason());
            ps.executeUpdate();
          }
          return null;
        },
        "replace accounting connection");
    return c;
  }

  public boolean delete(UUID tenantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM accounting_connections WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            return ps.executeUpdate() == 1;
          }
        },
        "delete accounting connection");
  }

  public boolean setStatus(UUID tenantId, String status, String reason, Instant now) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE accounting_connections SET status = ?, disabled_reason = ?, updated_at = ?"
                      + " WHERE tenant_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setObject(3, at(now));
            ps.setObject(4, tenantId);
            return ps.executeUpdate() == 1;
          }
        },
        "set accounting connection status");
  }

  public void updateCredentials(UUID tenantId, UUID id, String sealed, Instant now) {
    exec(
        "UPDATE accounting_connections SET credentials_sealed = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, sealed);
          ps.setObject(2, at(now));
          ps.setObject(3, tenantId);
          ps.setObject(4, id);
        },
        "update accounting credentials");
  }

  public void syncFinished(UUID tenantId, UUID id, Instant at, String error) {
    exec(
        "UPDATE accounting_connections SET last_sync_at = ?, last_error = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, at(at));
          ps.setString(2, error);
          ps.setObject(3, tenantId);
          ps.setObject(4, id);
        },
        "record accounting sync");
  }

  // ── mappings ────────────────────────────────────────────────────────────────

  public List<Mapping> mappings(UUID connectionId) {
    return query(
        "SELECT connection_id, nominal_code, external_account, external_name"
            + " FROM accounting_account_mappings WHERE connection_id = ? ORDER BY nominal_code",
        ps -> ps.setObject(1, connectionId),
        rs ->
            new Mapping(
                rs.getObject("connection_id", UUID.class),
                rs.getString("nominal_code"),
                rs.getString("external_account"),
                rs.getString("external_name")),
        "account mappings");
  }

  public void replaceMappings(UUID connectionId, List<Mapping> mappings) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM accounting_account_mappings WHERE connection_id = ?")) {
            ps.setObject(1, connectionId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO accounting_account_mappings (connection_id, nominal_code,"
                      + " external_account, external_name) VALUES (?,?,?,?)")) {
            for (Mapping m : mappings) {
              ps.setObject(1, connectionId);
              ps.setString(2, m.nominalCode());
              ps.setString(3, m.externalAccount());
              ps.setString(4, m.externalName());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "replace account mappings");
  }

  // ── syncs ───────────────────────────────────────────────────────────────────

  /**
   * Queues every journal dated from the connection's day that has no sync yet, oldest first.
   *
   * @return how many were queued
   */
  public int queue(Connection c, Instant now, int limit) {
    return inTx(
        conn -> {
          List<UUID> journals = new ArrayList<>();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT e.journal_id FROM nominal_ledger_entries e WHERE e.tenant_id = ?"
                      + " AND e.journal_id IS NOT NULL AND e.entry_date >= ?"
                      + " AND NOT EXISTS (SELECT 1 FROM accounting_syncs s WHERE s.connection_id = ?"
                      + " AND s.journal_id = e.journal_id)"
                      + " GROUP BY e.journal_id ORDER BY MIN(e.entry_date), e.journal_id LIMIT ?")) {
            ps.setObject(1, c.tenantId());
            ps.setObject(2, c.syncFrom());
            ps.setObject(3, c.id());
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) journals.add(rs.getObject("journal_id", UUID.class));
            }
          }
          if (journals.isEmpty()) return 0;
          int queued = 0;
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO accounting_syncs (id, tenant_id, connection_id, journal_id, status,"
                      + " attempts, next_attempt_at, created_at) VALUES (?,?,?,?,?,0,?,?)"
                      + " ON CONFLICT (connection_id, journal_id) DO NOTHING")) {
            for (UUID journalId : journals) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, c.tenantId());
              ps.setObject(3, c.id());
              ps.setObject(4, journalId);
              ps.setString(5, Accounting.PENDING);
              ps.setObject(6, at(now));
              ps.setObject(7, at(now));
              queued += ps.executeUpdate();
            }
          }
          return queued;
        },
        "queue journals for the accounting package");
  }

  /**
   * Claims what is due, under a lease, so two instances never push the same journal.
   *
   * @param connectionId one connection's, or null for every connection's
   */
  public List<Sync> claimDue(UUID connectionId, int limit, Instant now, Duration lease) {
    String sql =
        "UPDATE accounting_syncs SET leased_until = ? WHERE id IN (SELECT id FROM accounting_syncs"
            + " WHERE status = ? AND next_attempt_at <= ? AND (leased_until IS NULL OR leased_until < ?)"
            + (connectionId == null ? "" : " AND connection_id = ?")
            + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING "
            + SYNC_CORE.replace("s.", "");
    return inTx(
        c -> {
          List<Sync> claimed = new ArrayList<>();
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, at(now.plus(lease)));
            ps.setString(i++, Accounting.PENDING);
            ps.setObject(i++, at(now));
            ps.setObject(i++, at(now));
            if (connectionId != null) ps.setObject(i++, connectionId);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) claimed.add(readSync(rs, false));
            }
          }
          return claimed;
        },
        "claim due accounting syncs");
  }

  /** One try recorded, and the sync as it now stands, in one transaction. */
  public void recordAttempt(Attempt a, Sync s) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO accounting_sync_attempts (id, tenant_id, sync_id, attempt, attempted_at,"
                      + " status_code, error, snippet, duration_ms) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, a.id());
            ps.setObject(2, a.tenantId());
            ps.setObject(3, a.syncId());
            ps.setInt(4, a.attempt());
            ps.setObject(5, at(a.at()));
            if (a.statusCode() == null) ps.setNull(6, java.sql.Types.INTEGER);
            else ps.setInt(6, a.statusCode());
            ps.setString(7, a.error());
            ps.setString(8, a.snippet());
            ps.setInt(9, a.durationMs());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE accounting_syncs SET status = ?, attempts = ?, external_id = ?, last_error = ?,"
                      + " next_attempt_at = ?, leased_until = NULL, delivered_at = ?"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, s.status());
            ps.setInt(2, s.attempts());
            ps.setString(3, s.externalId());
            ps.setString(4, s.lastError());
            ps.setObject(5, at(s.nextAttemptAt()));
            ps.setObject(6, s.deliveredAt() == null ? null : at(s.deliveredAt()));
            ps.setObject(7, s.tenantId());
            ps.setObject(8, s.id());
            ps.executeUpdate();
          }
          return null;
        },
        "record accounting sync attempt");
  }

  /** The business's syncs, newest first, cursor on the id. */
  public List<Sync> syncs(UUID tenantId, UUID connectionId, String status, UUID after, int limit) {
    String sql =
        SYNC_WITH_JOURNAL
            + " WHERE s.tenant_id = ? AND s.connection_id = ?"
            + (status == null ? "" : " AND s.status = ?")
            + (after == null ? "" : " AND s.id < ?")
            + " ORDER BY s.id DESC LIMIT ?";
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, tenantId);
          ps.setObject(i++, connectionId);
          if (status != null) ps.setString(i++, status);
          if (after != null) ps.setObject(i++, after);
          ps.setInt(i, limit);
        },
        rs -> readSync(rs, true),
        "accounting syncs");
  }

  public Optional<Sync> sync(UUID tenantId, UUID id) {
    return query(
            SYNC_WITH_JOURNAL + " WHERE s.tenant_id = ? AND s.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, tenantId);
              ps.setObject(3, id);
            },
            rs -> readSync(rs, true),
            "accounting sync")
        .stream()
        .findFirst();
  }

  public List<Attempt> attempts(UUID tenantId, UUID syncId) {
    return query(
        "SELECT id, tenant_id, sync_id, attempt, attempted_at, status_code, error, snippet, duration_ms"
            + " FROM accounting_sync_attempts WHERE tenant_id = ? AND sync_id = ? ORDER BY attempt",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, syncId);
        },
        rs -> {
          int code = rs.getInt("status_code");
          boolean noCode = rs.wasNull();
          return new Attempt(
              rs.getObject("id", UUID.class),
              rs.getObject("tenant_id", UUID.class),
              rs.getObject("sync_id", UUID.class),
              rs.getInt("attempt"),
              rs.getObject("attempted_at", OffsetDateTime.class).toInstant(),
              noCode ? null : code,
              rs.getString("error"),
              rs.getString("snippet"),
              rs.getInt("duration_ms"));
        },
        "accounting sync attempts");
  }

  /** Queued to go now, whatever it was waiting for; a delivered one is left as it is. */
  public boolean retry(UUID tenantId, UUID id, Instant now) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE accounting_syncs SET status = ?, next_attempt_at = ?, leased_until = NULL"
                      + " WHERE tenant_id = ? AND id = ? AND status IN (?, ?, ?, ?)")) {
            ps.setString(1, Accounting.PENDING);
            ps.setObject(2, at(now));
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            ps.setString(5, Accounting.PENDING);
            ps.setString(6, Accounting.FAILED);
            ps.setString(7, Accounting.UNCERTAIN);
            ps.setString(8, Accounting.SKIPPED);
            return ps.executeUpdate() == 1;
          }
        },
        "retry accounting sync");
  }

  public boolean skip(UUID tenantId, UUID id, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE accounting_syncs SET status = ?, last_error = ?, leased_until = NULL"
                      + " WHERE tenant_id = ? AND id = ? AND status <> ?")) {
            ps.setString(1, Accounting.SKIPPED);
            ps.setString(2, reason);
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            ps.setString(5, Accounting.DELIVERED);
            return ps.executeUpdate() == 1;
          }
        },
        "skip accounting sync");
  }

  public Counts counts(UUID tenantId, UUID connectionId) {
    Map<String, Integer> by = new HashMap<>();
    query(
        "SELECT status, count(*) AS n FROM accounting_syncs WHERE tenant_id = ? AND connection_id = ?"
            + " GROUP BY status",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, connectionId);
        },
        rs -> by.put(rs.getString("status"), rs.getInt("n")),
        "accounting sync counts");
    return new Counts(
        by.getOrDefault(Accounting.PENDING, 0),
        by.getOrDefault(Accounting.DELIVERED, 0),
        by.getOrDefault(Accounting.FAILED, 0),
        by.getOrDefault(Accounting.UNCERTAIN, 0),
        by.getOrDefault(Accounting.SKIPPED, 0));
  }

  // ── rows ────────────────────────────────────────────────────────────────────

  private static Connection readConnection(ResultSet rs) throws SQLException {
    return new Connection(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("provider"),
        rs.getString("status"),
        settingsOf(rs.getString("settings")),
        rs.getString("credentials_sealed"),
        rs.getObject("sync_from", LocalDate.class),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        instant(rs, "last_sync_at"),
        rs.getString("last_error"),
        rs.getString("disabled_reason"));
  }

  private static Sync readSync(ResultSet rs, boolean withJournal) throws SQLException {
    return new Sync(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("connection_id", UUID.class),
        rs.getObject("journal_id", UUID.class),
        rs.getString("status"),
        rs.getInt("attempts"),
        rs.getString("external_id"),
        rs.getString("last_error"),
        rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
        instant(rs, "leased_until"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        instant(rs, "delivered_at"),
        withJournal ? rs.getObject("entry_date", LocalDate.class) : null,
        withJournal ? rs.getString("description") : null,
        withJournal ? rs.getString("source_type") : null,
        withJournal ? rs.getBigDecimal("total") : null);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime at(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  static String settingsJson(Map<String, String> settings) {
    JsonObjectBuilder b = Json.createObjectBuilder();
    for (var e : new java.util.TreeMap<>(settings).entrySet()) {
      if (e.getValue() != null) b.add(e.getKey(), e.getValue());
    }
    return b.build().toString();
  }

  static Map<String, String> settingsOf(String json) {
    Map<String, String> out = new LinkedHashMap<>();
    if (json == null || json.isBlank()) return out;
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      JsonObject o = reader.readObject();
      for (var e : o.entrySet()) {
        if (e.getValue().getValueType() == jakarta.json.JsonValue.ValueType.STRING) {
          out.put(e.getKey(), ((jakarta.json.JsonString) e.getValue()).getString());
        }
      }
    }
    return out;
  }
}
