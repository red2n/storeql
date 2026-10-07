package com.storeql.product.repo;

import com.storeql.product.domain.Domain.ListedProductSafety;
import com.storeql.product.domain.Domain.ProductSafety;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What an online offer shows about a product under GPSR art.19 (01.12). One row per product, kept
 * beside it: the catalogue's hot reads never need it, and a statement has its own writer.
 */
@ApplicationScoped
public class ProductSafetyRepository extends BaseJdbcRepository {

  static final String COLUMNS =
      "s.tenant_id, s.product_id, s.manufacturer_name, s.manufacturer_address,"
          + " s.manufacturer_contact, s.manufacturer_country, s.responsible_person_name,"
          + " s.responsible_person_address, s.responsible_person_contact, s.warnings,"
          + " s.no_warnings, s.updated_at, s.updated_by";

  /**
   * The statement for a product, if one was made.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public Optional<ProductSafety> find(UUID tenantId, UUID productId) {
    return query(
            "SELECT "
                + COLUMNS
                + " FROM product_safety_information s"
                + " WHERE s.tenant_id = ? AND s.product_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, productId);
            },
            ProductSafetyRepository::map,
            "find product safety information")
        .stream()
        .findFirst();
  }

  /**
   * Replaces a product's statement under a lock on the product, so a product cannot be put online
   * between the check and the write: {@code whenListed} learns whether the product is offered
   * online as it stands, and throws when the statement would not do for that.
   *
   * @throws ApiException 404 {@code PRODUCT_NOT_FOUND}; whatever {@code whenListed} throws
   */
  public ProductSafety save(ProductSafety s, Consumer<Boolean> whenListed) {
    return inTx(
        c -> {
          // FOR NO KEY UPDATE, as a product update locks the row: the two still go one after the
          // other, but a variant being added (whose foreign-key check takes FOR KEY SHARE) is not
          // held up behind a statement being saved.
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT status, sellable_online FROM products"
                      + " WHERE tenant_id = ? AND id = ? FOR NO KEY UPDATE")) {
            ps.setObject(1, s.tenantId());
            ps.setObject(2, s.productId());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound("PRODUCT_NOT_FOUND", "No such product in this tenant");
              }
              whenListed.accept("ACTIVE".equals(rs.getString(1)) && rs.getBoolean(2));
            }
          }
          upsert(c, s);
          return s;
        },
        "save product safety information");
  }

  /**
   * Every active product offered online, with its statement or null, for the list of what an online
   * catalogue still lacks.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param limit at most this many, by name
   */
  public List<ListedProductSafety> listedOnline(UUID tenantId, int limit) {
    return query(
        "SELECT p.id, p.name, "
            + COLUMNS
            + " FROM products p LEFT JOIN product_safety_information s"
            + " ON s.tenant_id = p.tenant_id AND s.product_id = p.id"
            + " WHERE p.tenant_id = ? AND p.status = 'ACTIVE' AND p.sellable_online"
            + " ORDER BY p.name, p.id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        rs ->
            new ListedProductSafety(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject("product_id", UUID.class) == null ? null : map(rs)),
        "list online products with their safety information");
  }

  /** The statement for a product inside an open transaction. */
  static Optional<ProductSafety> find(Connection c, UUID tenantId, UUID productId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM product_safety_information s"
                + " WHERE s.tenant_id = ? AND s.product_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, productId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  /** Writes a statement inside an open transaction, replacing any before it. */
  static void upsert(Connection c, ProductSafety s) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO product_safety_information"
                + " (tenant_id, product_id, manufacturer_name, manufacturer_address,"
                + " manufacturer_contact, manufacturer_country, responsible_person_name,"
                + " responsible_person_address, responsible_person_contact, warnings, no_warnings,"
                + " updated_at, updated_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (tenant_id, product_id) DO UPDATE SET"
                + " manufacturer_name = EXCLUDED.manufacturer_name,"
                + " manufacturer_address = EXCLUDED.manufacturer_address,"
                + " manufacturer_contact = EXCLUDED.manufacturer_contact,"
                + " manufacturer_country = EXCLUDED.manufacturer_country,"
                + " responsible_person_name = EXCLUDED.responsible_person_name,"
                + " responsible_person_address = EXCLUDED.responsible_person_address,"
                + " responsible_person_contact = EXCLUDED.responsible_person_contact,"
                + " warnings = EXCLUDED.warnings, no_warnings = EXCLUDED.no_warnings,"
                + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by")) {
      ps.setObject(1, s.tenantId());
      ps.setObject(2, s.productId());
      ps.setString(3, s.manufacturerName());
      ps.setString(4, s.manufacturerAddress());
      ps.setString(5, s.manufacturerContact());
      ps.setString(6, s.manufacturerCountry());
      ps.setString(7, s.responsiblePersonName());
      ps.setString(8, s.responsiblePersonAddress());
      ps.setString(9, s.responsiblePersonContact());
      ps.setString(10, s.warnings());
      ps.setBoolean(11, s.noWarnings());
      ps.setObject(12, s.updatedAt().atOffset(ZoneOffset.UTC));
      ps.setObject(13, s.updatedBy());
      ps.executeUpdate();
    }
  }

  private static ProductSafety map(ResultSet rs) throws SQLException {
    return new ProductSafety(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("manufacturer_name"),
        rs.getString("manufacturer_address"),
        rs.getString("manufacturer_contact"),
        rs.getString("manufacturer_country"),
        rs.getString("responsible_person_name"),
        rs.getString("responsible_person_address"),
        rs.getString("responsible_person_contact"),
        rs.getString("warnings"),
        rs.getBoolean("no_warnings"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_by", UUID.class));
  }
}
