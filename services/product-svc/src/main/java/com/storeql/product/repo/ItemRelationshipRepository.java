package com.storeql.product.repo;

import com.storeql.product.domain.Domain.ItemRelationship;
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
 * Item relationships (Gap #32): e.g. substitute/accessory links between variants. A link is
 * inserted and deleted, never updated; removing the row is how it is severed. Extracted from {@code
 * ProductRepository}: self-contained, no outbox events, no coupling to any other aggregate, so it
 * only needs the JDBC infra inherited from {@link BaseJdbcRepository}.
 */
@ApplicationScoped
public class ItemRelationshipRepository extends BaseJdbcRepository {

  /**
   * Inserts a relationship.
   *
   * @param r the relationship to persist
   * @return the relationship as stored
   */
  public ItemRelationship createRelationship(ItemRelationship r) {
    exec(
        "INSERT INTO item_relationships"
            + " (id, tenant_id, variant_id, related_variant_id, relationship_type, created_at)"
            + " VALUES (?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, r.id());
          ps.setObject(2, r.tenantId());
          ps.setObject(3, r.variantId());
          ps.setObject(4, r.relatedVariantId());
          ps.setString(5, r.relationshipType());
          ps.setObject(6, r.createdAt().atOffset(ZoneOffset.UTC));
        },
        "create item relationship");
    return r;
  }

  /**
   * Lists the tenant's relationships.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<ItemRelationship> listRelationships(UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, related_variant_id, relationship_type, created_at"
            + " FROM item_relationships WHERE tenant_id = ? AND variant_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ItemRelationshipRepository::mapRelationship,
        "list item relationships");
  }

  /**
   * Deletes a relationship.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the variant it must belong to; another variant's is not found
   * @param id the relationship to act on
   * @return {@code true} when a row was removed, {@code false} when nothing matched
   */
  public boolean deleteRelationship(UUID tenantId, UUID variantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM item_relationships WHERE tenant_id = ? AND variant_id = ? AND id = ?"
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
        "delete item relationship");
    return found[0] != null;
  }

  private static ItemRelationship mapRelationship(ResultSet rs) throws SQLException {
    return new ItemRelationship(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("related_variant_id", UUID.class),
        rs.getString("relationship_type"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
