package com.storeql.tenant.repo;

import com.storeql.ids.Ids;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Domain.BreachDuty;
import com.storeql.tenant.domain.Domain.IncidentEvent;
import com.storeql.tenant.domain.Domain.NoticeIssue;
import com.storeql.tenant.domain.Domain.NoticeReport;
import com.storeql.tenant.domain.Domain.ReportingStage;
import com.storeql.tenant.domain.Domain.SecurityIncident;
import com.storeql.tenant.domain.Domain.SecurityNotice;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The security incident register (V11). Incidents and their timelines are platform data; notices
 * are the one table here that belongs to a business, and every read of them filters by its tenant
 * first.
 */
@ApplicationScoped
public class SecurityIncidentRepository extends BaseJdbcRepository {

  private static final String UNIQUE_VIOLATION = "23505";
  private static final String INCIDENT_COLUMNS =
      "id, kind, title, summary, aware_at, opened_at, opened_by, affects_all_tenants";
  private static final String EVENT_COLUMNS =
      "id, incident_id, kind, occurred_at, recorded_at, recorded_by, reference, note";
  private static final String NOTICE_COLUMNS =
      "id, tenant_id, incident_id, title, body, issued_at, issued_by, acknowledged_at,"
          + " acknowledged_by";
  private static final String REPORT_COLUMNS =
      "id, tenant_id, notice_id, duty, done_at, reference, note, recorded_by, recorded_at";

  /** The statutory stages for a kind of incident, in order. */
  public List<ReportingStage> stages(String kind) {
    return query(
        "SELECT incident_kind, stage, anchor, due_after, position, citation, summary"
            + " FROM incident_reporting_stages WHERE incident_kind = ? ORDER BY position",
        ps -> ps.setString(1, kind),
        rs ->
            new ReportingStage(
                rs.getString("incident_kind"),
                rs.getString("stage"),
                rs.getString("anchor"),
                rs.getString("due_after"),
                rs.getInt("position"),
                rs.getString("citation"),
                rs.getString("summary")),
        "reporting stages");
  }

  /** How many of these ids are businesses on the platform. */
  public int existingTenants(List<UUID> tenantIds) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT count(*) FROM tenants WHERE id = ANY(?)")) {
            ps.setArray(1, uuids(c, tenantIds));
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? rs.getInt(1) : 0;
            }
          }
        },
        "count tenants");
  }

  public SecurityIncident insert(SecurityIncident i) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO security_incidents ("
                      + INCIDENT_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, i.id());
            ps.setString(2, i.kind());
            ps.setString(3, i.title());
            ps.setString(4, i.summary());
            ps.setObject(5, utc(i.awareAt()));
            ps.setObject(6, utc(i.openedAt()));
            ps.setObject(7, i.openedBy());
            ps.setBoolean(8, i.affectsAllTenants());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO security_incident_tenants (incident_id, tenant_id) VALUES (?,?)")) {
            for (UUID tenant : i.tenantIds()) {
              ps.setObject(1, i.id());
              ps.setObject(2, tenant);
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return i;
        },
        "open security incident");
  }

  public Optional<SecurityIncident> find(UUID id) {
    return inTx(c -> Optional.ofNullable(load(c, id, false)), "find security incident");
  }

  /** Newest first; {@code closed} null for every incident. */
  public List<SecurityIncident> list(Boolean closed, int limit) {
    String where =
        closed == null
            ? ""
            : " WHERE "
                + (closed ? "" : "NOT ")
                + "EXISTS (SELECT 1 FROM security_incident_events e"
                + " WHERE e.incident_id = i.id AND e.kind = 'CLOSED')";
    return inTx(
        c -> {
          List<SecurityIncident> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT i.id FROM security_incidents i"
                      + where
                      + " ORDER BY i.aware_at DESC, i.id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(load(c, rs.getObject(1, UUID.class), false));
              }
            }
          }
          return out;
        },
        "list security incidents");
  }

  public List<IncidentEvent> events(UUID incidentId) {
    return inTx(c -> events(c, incidentId), "incident events");
  }

  /** How many notices went out for an incident, and how many were acknowledged. */
  public NoticeIssue noticeCounts(UUID incidentId) {
    return inTx(
        c -> {
          int[] counts = counts(c, incidentId);
          return new NoticeIssue(0, counts[0], counts[1]);
        },
        "notice counts");
  }

  /**
   * Records an event under a lock on its incident, so two people recording the same stage at once
   * cannot both succeed: {@code decide} sees the incident and its timeline as they stand and
   * returns the event to write, or throws the refusal.
   *
   * @throws ApiException 404 {@code INCIDENT_NOT_FOUND}; whatever {@code decide} throws
   */
  public IncidentEvent recordEvent(
      UUID incidentId, BiFunction<SecurityIncident, List<IncidentEvent>, IncidentEvent> decide) {
    return inTx(
        c -> {
          SecurityIncident incident = load(c, incidentId, true);
          if (incident == null) {
            throw ApiException.notFound("INCIDENT_NOT_FOUND", "No such security incident");
          }
          IncidentEvent event = decide.apply(incident, events(c, incidentId));
          insertEvent(c, event);
          return event;
        },
        "record incident event");
  }

  /**
   * Issues a notice to every business the incident affects that does not have one yet, and records
   * that businesses were told the first time, in one transaction under the incident's lock.
   *
   * @param decide returns the TENANTS_NOTIFIED event to record, null when it already is, or throws
   */
  public NoticeIssue issueNotices(
      UUID incidentId,
      BiFunction<SecurityIncident, List<IncidentEvent>, IncidentEvent> decide,
      String title,
      String body,
      UUID actor,
      Instant at) {
    return inTx(
        c -> {
          SecurityIncident incident = load(c, incidentId, true);
          if (incident == null) {
            throw ApiException.notFound("INCIDENT_NOT_FOUND", "No such security incident");
          }
          IncidentEvent told = decide.apply(incident, events(c, incidentId));
          List<UUID> targets = incident.affectsAllTenants() ? allTenants(c) : incident.tenantIds();
          int issued = 0;
          int queued = 0;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO security_notices"
                      + " (id, tenant_id, incident_id, title, body, issued_at, issued_by)"
                      + " VALUES (?,?,?,?,?,?,?)"
                      + " ON CONFLICT ON CONSTRAINT uq_security_notice_per_tenant DO NOTHING")) {
            for (UUID tenant : targets) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenant);
              ps.setObject(3, incidentId);
              ps.setString(4, title);
              ps.setString(5, body);
              ps.setObject(6, utc(at));
              ps.setObject(7, actor);
              ps.addBatch();
              if (++queued == NOTICE_BATCH) {
                issued += inserted(ps.executeBatch());
                queued = 0;
              }
            }
            if (queued > 0) issued += inserted(ps.executeBatch());
          }
          if (told != null) {
            insertEvent(c, told);
          }
          int[] counts = counts(c, incidentId);
          return new NoticeIssue(issued, counts[0], counts[1]);
        },
        "issue security notices");
  }

  private static final int NOTICE_BATCH = 500;

  /** Rows a batch really inserted: a conflict that did nothing counts zero. */
  private static int inserted(int... counts) {
    int n = 0;
    for (int count : counts) n += Math.max(0, count);
    return n;
  }

  /** The duties a regime puts on a business told of a breach, in order (13.12). */
  public List<BreachDuty> breachDuties(String regime) {
    return query(
        "SELECT regime, duty, anchor, due_after, position, citation, summary"
            + " FROM breach_duties WHERE regime = ? ORDER BY position",
        ps -> ps.setString(1, regime),
        rs ->
            new BreachDuty(
                rs.getString("regime"),
                rs.getString("duty"),
                rs.getString("anchor"),
                rs.getString("due_after"),
                rs.getInt("position"),
                rs.getString("citation"),
                rs.getString("summary")),
        "breach duties");
  }

  /** What a business recorded on one notice, by duty. */
  public List<NoticeReport> reportsFor(UUID tenantId, UUID noticeId) {
    return query(
        "SELECT "
            + REPORT_COLUMNS
            + " FROM security_notice_reports"
            + " WHERE tenant_id = ? AND notice_id = ? ORDER BY recorded_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, noticeId);
        },
        SecurityIncidentRepository::report,
        "notice reports");
  }

  /**
   * Records a duty done, once per duty per notice.
   *
   * @return whether it was new; false when that duty was recorded already
   */
  public boolean recordReport(NoticeReport r) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO security_notice_reports ("
                      + REPORT_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, notice_id, duty) DO NOTHING")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.noticeId());
            ps.setString(4, r.duty());
            ps.setObject(5, utc(r.doneAt()));
            ps.setString(6, r.reference());
            ps.setString(7, r.note());
            ps.setObject(8, r.recordedBy());
            ps.setObject(9, utc(r.recordedAt()));
            return ps.executeUpdate() == 1;
          }
        },
        "record notice report");
  }

  /** One of a business's notices, or empty for another business's. */
  public Optional<SecurityNotice> noticeOf(UUID tenantId, UUID noticeId) {
    return query(
            "SELECT " + NOTICE_COLUMNS + " FROM security_notices WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, noticeId);
            },
            SecurityIncidentRepository::notice,
            "one security notice")
        .stream()
        .findFirst();
  }

  /** A business's own notices, newest first. */
  public List<SecurityNotice> noticesFor(UUID tenantId) {
    return query(
        "SELECT "
            + NOTICE_COLUMNS
            + " FROM security_notices WHERE tenant_id = ?"
            + " ORDER BY issued_at DESC, id DESC LIMIT 200",
        ps -> ps.setObject(1, tenantId),
        SecurityIncidentRepository::notice,
        "security notices for a tenant");
  }

  /**
   * Acknowledges a business's notice once; a second acknowledgement changes nothing.
   *
   * @return the notice as it stands, or empty when the business has no such notice
   */
  public Optional<SecurityNotice> acknowledge(
      UUID tenantId, UUID noticeId, UUID actor, Instant at) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE security_notices SET acknowledged_at = ?, acknowledged_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND acknowledged_at IS NULL")) {
            ps.setObject(1, utc(at));
            ps.setObject(2, actor);
            ps.setObject(3, tenantId);
            ps.setObject(4, noticeId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + NOTICE_COLUMNS
                      + " FROM security_notices WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, noticeId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(notice(rs)) : Optional.<SecurityNotice>empty();
            }
          }
        },
        "acknowledge security notice");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return new ApiException(
          409, "INCIDENT_STAGE_ALREADY_RECORDED", "That stage is already recorded", List.of(), e);
    }
    return dbError(what, e);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static SecurityIncident load(Connection c, UUID id, boolean lock) throws SQLException {
    SecurityIncident found = null;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + INCIDENT_COLUMNS
                + " FROM security_incidents WHERE id = ?"
                + (lock ? " FOR UPDATE" : ""))) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          found =
              new SecurityIncident(
                  rs.getObject("id", UUID.class),
                  rs.getString("kind"),
                  rs.getString("title"),
                  rs.getString("summary"),
                  instant(rs, "aware_at"),
                  instant(rs, "opened_at"),
                  rs.getObject("opened_by", UUID.class),
                  rs.getBoolean("affects_all_tenants"),
                  List.of());
        }
      }
    }
    if (found == null) return null;
    List<UUID> tenants = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT tenant_id FROM security_incident_tenants WHERE incident_id = ? ORDER BY tenant_id")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) tenants.add(rs.getObject(1, UUID.class));
      }
    }
    return new SecurityIncident(
        found.id(),
        found.kind(),
        found.title(),
        found.summary(),
        found.awareAt(),
        found.openedAt(),
        found.openedBy(),
        found.affectsAllTenants(),
        List.copyOf(tenants));
  }

  private static List<IncidentEvent> events(Connection c, UUID incidentId) throws SQLException {
    List<IncidentEvent> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + EVENT_COLUMNS
                + " FROM security_incident_events WHERE incident_id = ?"
                + " ORDER BY occurred_at, recorded_at, id")) {
      ps.setObject(1, incidentId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new IncidentEvent(
                  rs.getObject("id", UUID.class),
                  rs.getObject("incident_id", UUID.class),
                  rs.getString("kind"),
                  instant(rs, "occurred_at"),
                  instant(rs, "recorded_at"),
                  rs.getObject("recorded_by", UUID.class),
                  rs.getString("reference"),
                  rs.getString("note")));
        }
      }
    }
    return out;
  }

  private static void insertEvent(Connection c, IncidentEvent e) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO security_incident_events ("
                + EVENT_COLUMNS
                + ") VALUES (?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.incidentId());
      ps.setString(3, e.kind());
      ps.setObject(4, utc(e.occurredAt()));
      ps.setObject(5, utc(e.recordedAt()));
      ps.setObject(6, e.recordedBy());
      ps.setString(7, e.reference());
      ps.setString(8, e.note());
      ps.executeUpdate();
    }
  }

  private static List<UUID> allTenants(Connection c) throws SQLException {
    List<UUID> out = new ArrayList<>();
    try (PreparedStatement ps =
            c.prepareStatement("SELECT id FROM tenants ORDER BY created_at, id");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) out.add(rs.getObject(1, UUID.class));
    }
    return out;
  }

  private static int[] counts(Connection c, UUID incidentId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT count(*), count(acknowledged_at) FROM security_notices WHERE incident_id = ?")) {
      ps.setObject(1, incidentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new int[] {rs.getInt(1), rs.getInt(2)} : new int[] {0, 0};
      }
    }
  }

  private static NoticeReport report(ResultSet rs) throws SQLException {
    return new NoticeReport(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("notice_id", UUID.class),
        rs.getString("duty"),
        instant(rs, "done_at"),
        rs.getString("reference"),
        rs.getString("note"),
        rs.getObject("recorded_by", UUID.class),
        instant(rs, "recorded_at"));
  }

  private static SecurityNotice notice(ResultSet rs) throws SQLException {
    return new SecurityNotice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("incident_id", UUID.class),
        rs.getString("title"),
        rs.getString("body"),
        instant(rs, "issued_at"),
        rs.getObject("issued_by", UUID.class),
        instant(rs, "acknowledged_at"),
        rs.getObject("acknowledged_by", UUID.class));
  }

  private static Array uuids(Connection c, List<UUID> ids) throws SQLException {
    return c.createArrayOf("uuid", ids.toArray());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime utc(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
