package com.storeql.product.repo;

import com.storeql.ids.Ids;
import com.storeql.product.domain.CategoryTree;
import com.storeql.product.domain.Domain.Category;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Category CRUD. Extracted from {@code ProductRepository}: self-contained, no outbox events, no
 * coupling to any other aggregate, so it only needs the JDBC infra inherited from {@link
 * BaseJdbcRepository}.
 */
@ApplicationScoped
public class CategoryRepository extends BaseJdbcRepository {

  /**
   * Inserts a category.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param parentId the parent to nest under, or {@code null} for a root
   * @param name the name to set
   * @return the category as stored
   */
  public Category createCategory(UUID tenantId, UUID parentId, String name) {
    Instant now = Instant.now();
    var c = new Category(Ids.newId(), tenantId, parentId, name, Category.STATUS_ACTIVE, now, now);
    if (parentId != null && findCategory(tenantId, parentId).isEmpty()) {
      throw ApiException.badRequest("PARENT_NOT_FOUND", "parentId not found in this tenant");
    }
    exec(
        "INSERT INTO categories (id, tenant_id, parent_id, name, status, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, c.id());
          ps.setObject(2, c.tenantId());
          ps.setObject(3, c.parentId());
          ps.setString(4, c.name());
          ps.setString(5, c.status());
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create category");
    return c;
  }

  /**
   * Looks a category up by name within the tenant.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param name the name to match
   * @return the category, or empty when nothing matches
   */
  public Optional<Category> findCategoryByName(UUID tenantId, String name) {
    return query(
            "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
                + " FROM categories WHERE tenant_id = ? AND name = ? AND status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, name);
            },
            CategoryRepository::mapCategory,
            "find category by name")
        .stream()
        .findFirst();
  }

  /**
   * Looks a category up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the category to act on
   * @return the category, or empty when it does not exist in this tenant
   */
  public Optional<Category> findCategory(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
                + " FROM categories WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            CategoryRepository::mapCategory,
            "find category")
        .stream()
        .findFirst();
  }

  /**
   * Lists the tenant's categories.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the matching rows
   */
  public List<Category> listCategories(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
            + " FROM categories WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        CategoryRepository::mapCategory,
        "list categories");
  }

  /**
   * Writes a category back with its new values.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the category to act on
   * @param name the name to set
   * @param parentId the parent to nest under, or {@code null} for a root
   * @return the category as stored
   */
  public Category updateCategory(UUID tenantId, UUID id, String name, UUID parentId) {
    Instant now = Instant.now();
    inTx(
        c -> {
          // Every category of the tenant is locked before the tree is read, so two re-parents
          // that would each be fine alone (A under B, B under A) are taken one after the other
          // and the second sees the first's result. In id order, so two of them cannot deadlock.
          java.util.Map<UUID, UUID> parentOf = new java.util.HashMap<>();
          try (var ps =
              c.prepareStatement(
                  "SELECT id, parent_id FROM categories WHERE tenant_id = ? ORDER BY id FOR UPDATE")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                parentOf.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
              }
            }
          }
          if (parentId != null && !parentOf.containsKey(parentId)) {
            throw ApiException.badRequest("PARENT_NOT_FOUND", "parentId not found in this tenant");
          }
          if (CategoryTree.createsCycle(parentOf, id, parentId)) {
            throw ApiException.conflict(
                "PRODUCT_CATEGORY_CYCLE",
                "A category cannot be placed under itself or one of its own descendants");
          }
          try (var ps =
              c.prepareStatement(
                  "UPDATE categories SET name = ?, parent_id = ?, updated_at = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'")) {
            ps.setString(1, name);
            ps.setObject(2, parentId);
            ps.setObject(3, now.atOffset(ZoneOffset.UTC));
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.executeUpdate();
          }
          return null;
        },
        "update category");
    return findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  /**
   * Soft-deletes a category by marking it inactive.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the category to act on
   * @return the category in its deactivated state
   */
  public Category deactivateCategory(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE categories SET status = 'INACTIVE', updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate category");
    return findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  private static Category mapCategory(ResultSet rs) throws SQLException {
    return new Category(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("parent_id", UUID.class),
        rs.getString("name"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
