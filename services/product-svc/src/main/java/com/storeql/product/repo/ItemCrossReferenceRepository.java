package com.storeql.product.repo;

import com.storeql.product.domain.Domain.ItemCrossReference;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Supplier / customer cross-references (Gap #33). Extracted from {@code ProductRepository}:
 * self-contained, no outbox events, no coupling to any other aggregate, so it only needs the JDBC
 * infra inherited from {@link BaseJdbcRepository}.
 */
@ApplicationScoped
public class ItemCrossReferenceRepository extends BaseJdbcRepository {

  /**
   * Inserts a cross reference.
   *
   * @param x the cross to persist
   * @return the cross reference as stored
   */
  public ItemCrossReference createCrossReference(ItemCrossReference x) {
    exec(
        "INSERT INTO item_cross_references"
            + " (id, tenant_id, variant_id, party_type, party_id, party_name,"
            + " cross_ref_number, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, x.id());
          ps.setObject(2, x.tenantId());
          ps.setObject(3, x.variantId());
          ps.setString(4, x.partyType());
          ps.setObject(5, x.partyId());
          ps.setString(6, x.partyName());
          ps.setString(7, x.crossRefNumber());
          ps.setObject(8, x.createdAt().atOffset(ZoneOffset.UTC));
        },
        "create cross reference");
    return x;
  }

  /**
   * Lists the tenant's cross references.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @param partyType the party type
   * @return the matching rows
   */
  public List<ItemCrossReference> listCrossReferences(
      UUID tenantId, UUID variantId, String partyType) {
    if (partyType != null) {
      return query(
          "SELECT id, tenant_id, variant_id, party_type, party_id, party_name,"
              + " cross_ref_number, created_at"
              + " FROM item_cross_references"
              + " WHERE tenant_id = ? AND variant_id = ? AND party_type = ?"
              + " ORDER BY created_at",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, variantId);
            ps.setString(3, partyType);
          },
          ItemCrossReferenceRepository::mapCrossReference,
          "list cross references by type");
    }
    return query(
        "SELECT id, tenant_id, variant_id, party_type, party_id, party_name,"
            + " cross_ref_number, created_at"
            + " FROM item_cross_references"
            + " WHERE tenant_id = ? AND variant_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ItemCrossReferenceRepository::mapCrossReference,
        "list cross references");
  }

  /**
   * Deletes a cross reference.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the variant it must belong to; another variant's is not found
   * @param id the cross reference to act on
   * @return {@code true} when a row was removed, {@code false} when nothing matched
   */
  public boolean deleteCrossReference(UUID tenantId, UUID variantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM item_cross_references WHERE tenant_id = ? AND variant_id = ? AND id = ?"
            + " RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
          ps.setObject(3, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete cross reference");
    return found[0] != null;
  }

  private static ItemCrossReference mapCrossReference(ResultSet rs) throws SQLException {
    return new ItemCrossReference(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("party_type"),
        rs.getObject("party_id", UUID.class),
        rs.getString("party_name"),
        rs.getString("cross_ref_number"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
