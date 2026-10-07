package com.storeql.product.repo;

import com.storeql.product.domain.Domain.ItemRevision;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Item revisions (Gap #12): design/spec versions of a product variant over time. Not append-only:
 * recording a revision marks the variant's {@code ACTIVE} ones effective on or before it {@code
 * SUPERSEDED} (the one update this class makes). The service's own code never deletes a revision;
 * only a departed business's erasure does. Extracted from {@code ProductRepository}: has its own
 * outbox event on create, no coupling to any other aggregate. Keeps the same {@link
 * #handleTxSqlException} override the monolith had — a duplicate {@code (tenant_id, variant_id,
 * revision)} insert must still surface as {@code 409 DUPLICATE}, not the generic 500 a plain {@code
 * BaseJdbcRepository} would give.
 */
@ApplicationScoped
public class ItemRevisionRepository extends BaseOutboxRepository {

  /**
   * Inserts a revision and its event in one transaction.
   *
   * @param rev the revision to persist
   * @param event the outbox row to commit alongside the write
   * @return the revision as stored
   */
  public ItemRevision createRevisionWithOutbox(ItemRevision rev, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE item_revisions SET status='SUPERSEDED'"
                      + " WHERE tenant_id=? AND variant_id=? AND status='ACTIVE'"
                      + " AND effective_date <= ?")) {
            ps.setObject(1, rev.tenantId());
            ps.setObject(2, rev.variantId());
            ps.setObject(3, Date.valueOf(rev.effectiveDate()));
            ps.executeUpdate();
          }
          String sql =
              "INSERT INTO item_revisions"
                  + " (id, tenant_id, variant_id, revision, description, effective_date, status)"
                  + " VALUES (?,?,?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, rev.id());
            ps.setObject(2, rev.tenantId());
            ps.setObject(3, rev.variantId());
            ps.setString(4, rev.revision());
            ps.setString(5, rev.description());
            ps.setObject(6, Date.valueOf(rev.effectiveDate()));
            ps.setString(7, rev.status());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409, "REVISION_EXISTS", "Revision already exists", List.of(), null);
              ItemRevision saved = mapRevision(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create item revision");
  }

  /**
   * Lists the tenant's revisions.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<ItemRevision> listRevisions(UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, revision, description, effective_date, status, created_at"
            + " FROM item_revisions WHERE tenant_id=? AND variant_id=?"
            + " ORDER BY effective_date DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ItemRevisionRepository::mapRevision,
        "list item revisions");
  }

  /**
   * The variant's active revision.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the variant whose current revision to read
   * @return the active revision, or empty when the variant has none
   */
  public Optional<ItemRevision> currentRevision(UUID tenantId, UUID variantId) {
    var rows =
        query(
            "SELECT id, tenant_id, variant_id, revision, description, effective_date, status, created_at"
                + " FROM item_revisions WHERE tenant_id=? AND variant_id=?"
                + " AND effective_date <= CURRENT_DATE"
                + " ORDER BY effective_date DESC LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            ItemRevisionRepository::mapRevision,
            "current item revision");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Looks a revision up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param revisionId the revision id
   * @return the revision, or empty when it does not exist in this tenant
   */
  public Optional<ItemRevision> findRevision(UUID tenantId, UUID revisionId) {
    var rows =
        query(
            "SELECT id, tenant_id, variant_id, revision, description, effective_date, status, created_at"
                + " FROM item_revisions WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, revisionId);
            },
            ItemRevisionRepository::mapRevision,
            "find item revision");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "DUPLICATE", "A record with that unique value already exists", List.of(), e);
    return dbError(what, e);
  }

  private static ItemRevision mapRevision(ResultSet rs) throws SQLException {
    return new ItemRevision(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("revision"),
        rs.getString("description"),
        rs.getDate("effective_date").toLocalDate(),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
