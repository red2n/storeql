package com.storeql.purchase.repo;

import com.storeql.purchase.domain.Domain.DutyRelease;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Releases from bond as inventory-svc announced them, each owed to the revenue once. */
@ApplicationScoped
public class DutyRepository extends BaseJdbcRepository {

  /**
   * Records a release and the journal that owes its duty, once: the event is marked processed, the
   * row is keyed by the event, and both commit together.
   *
   * @return whether the release was recorded now; false when it already was
   */
  public boolean recordOnce(
      UUID eventId, String consumer, DutyRelease r, List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO duty_releases (id, tenant_id, event_id, release_id, store_id,"
                      + " variant_id, qty, duty_per_unit, duty_amount, currency, reference,"
                      + " released_on, recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, event_id) DO NOTHING")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.eventId());
            ps.setObject(4, r.releaseId());
            ps.setObject(5, r.storeId());
            ps.setObject(6, r.variantId());
            ps.setBigDecimal(7, r.qty());
            ps.setBigDecimal(8, r.dutyPerUnit());
            ps.setBigDecimal(9, r.dutyAmount());
            ps.setString(10, r.currency());
            ps.setString(11, r.reference());
            ps.setObject(12, r.releasedOn());
            ps.setObject(13, r.recordedAt().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() == 0) return false;
          }
          LedgerWriter.insert(c, posting);
          return true;
        },
        "record duty release");
  }

  public List<DutyRelease> findReleases(UUID tenantId, LocalDate from, LocalDate to) {
    return query(
        "SELECT id, tenant_id, event_id, release_id, store_id, variant_id, qty, duty_per_unit,"
            + " duty_amount, currency, reference, released_on, recorded_at FROM duty_releases"
            + " WHERE tenant_id = ? AND released_on >= ? AND released_on <= ?"
            + " ORDER BY released_on DESC, recorded_at DESC, id DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, from);
          ps.setObject(3, to);
        },
        DutyRepository::map,
        "list duty releases");
  }

  private static DutyRelease map(ResultSet rs) throws SQLException {
    return new DutyRelease(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getObject("release_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getBigDecimal("duty_per_unit"),
        rs.getBigDecimal("duty_amount"),
        rs.getString("currency"),
        rs.getString("reference"),
        rs.getObject("released_on", LocalDate.class),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
  }
}
