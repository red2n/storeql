package com.storeql.product.repo;

import com.storeql.product.domain.Domain.UomClass;
import com.storeql.product.domain.Domain.UomDefinition;
import com.storeql.product.domain.Domain.UomItemConversion;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Units of measure: classes/definitions (reference data) and per-variant conversion factors.
 * Extracted from {@code ProductRepository}: self-contained, no outbox events, no coupling to any
 * other aggregate, so it only needs the JDBC infra inherited from {@link BaseJdbcRepository}.
 */
@ApplicationScoped
public class UomRepository extends BaseJdbcRepository {

  /**
   * Lists the tenant's uom classes.
   *
   * @return the matching rows
   */
  public List<UomClass> listUomClasses() {
    return query(
        "SELECT id, code, name FROM uom_classes ORDER BY name",
        ps -> {},
        rs ->
            new UomClass(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")),
        "list uom classes");
  }

  /**
   * Whether a unit code exists at all.
   *
   * <p>A targeted lookup rather than scanning {@link #listUomDefinitions}: this is called whenever
   * a variant's net content is set, and reading the whole reference table to answer one membership
   * question is the kind of thing that is invisible until the catalogue import runs.
   */
  public boolean definitionExists(String code) {
    return !query(
            "SELECT 1 FROM uom_definitions WHERE code = ?",
            ps -> ps.setString(1, code),
            rs -> rs.getInt(1),
            "uom definition exists")
        .isEmpty();
  }

  /**
   * Lists the tenant's uom definitions.
   *
   * @param classCode the UoM class to restrict to
   * @return the matching rows
   */
  public List<UomDefinition> listUomDefinitions(String classCode) {
    if (classCode != null) {
      return query(
          "SELECT id, class_code, code, name FROM uom_definitions WHERE class_code = ? ORDER BY name",
          ps -> ps.setString(1, classCode),
          UomRepository::mapUomDef,
          "list uom definitions by class");
    }
    return query(
        "SELECT id, class_code, code, name FROM uom_definitions ORDER BY class_code, name",
        ps -> {},
        UomRepository::mapUomDef,
        "list all uom definitions");
  }

  /**
   * Looks a standard conversion factor up by id.
   *
   * @param fromUom the unit converting from
   * @param toUom the unit converting to
   * @return the standard conversion factor, or empty when it does not exist in this tenant
   */
  public Optional<BigDecimal> findStandardConversionFactor(String fromUom, String toUom) {
    var list =
        query(
            "SELECT factor FROM uom_standard_conversions WHERE from_uom = ? AND to_uom = ?",
            ps -> {
              ps.setString(1, fromUom);
              ps.setString(2, toUom);
            },
            rs -> rs.getBigDecimal("factor"),
            "find std conversion");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * Creates or replaces an item conversion.
   *
   * @param c the item to persist
   * @return the item conversion as stored
   */
  public UomItemConversion upsertItemConversion(UomItemConversion c) {
    return inTx(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO uom_item_conversions"
                      + " (id, tenant_id, variant_id, from_uom, to_uom, factor)"
                      + " VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, variant_id, from_uom, to_uom)"
                      + " DO UPDATE SET factor = EXCLUDED.factor"
                      + " RETURNING id, tenant_id, variant_id, from_uom, to_uom, factor")) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setObject(3, c.variantId());
            ps.setString(4, c.fromUom());
            ps.setString(5, c.toUom());
            ps.setBigDecimal(6, c.factor());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapItemConversion(rs);
            }
          }
        },
        "upsert item conversion");
  }

  /**
   * Lists the tenant's item conversions.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @return the matching rows
   */
  public List<UomItemConversion> listItemConversions(UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, from_uom, to_uom, factor"
            + " FROM uom_item_conversions WHERE tenant_id = ? AND variant_id = ?"
            + " ORDER BY from_uom, to_uom",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        UomRepository::mapItemConversion,
        "list item conversions");
  }

  /**
   * The variant an item conversion belongs to.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the item conversion
   * @return its variant, or empty when it is not the tenant's
   */
  public java.util.Optional<UUID> variantOfItemConversion(UUID tenantId, UUID id) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT variant_id FROM uom_item_conversions WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (var rs = ps.executeQuery()) {
        return rs.next()
            ? java.util.Optional.of(rs.getObject("variant_id", UUID.class))
            : java.util.Optional.empty();
      }
    } catch (SQLException e) {
      throw dbError("find item conversion", e);
    }
  }

  /**
   * Deletes an item conversion.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the conversion
   * @return whether a row was removed
   */
  public boolean deleteItemConversion(UUID tenantId, UUID id) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement("DELETE FROM uom_item_conversions WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("delete item conversion", e);
    }
  }

  /**
   * Looks an item conversion factor up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @param fromUom the unit converting from
   * @param toUom the unit converting to
   * @return the item conversion factor, or empty when it does not exist in this tenant
   */
  public Optional<BigDecimal> findItemConversionFactor(
      UUID tenantId, UUID variantId, String fromUom, String toUom) {
    var list =
        query(
            "SELECT factor FROM uom_item_conversions"
                + " WHERE tenant_id = ? AND variant_id = ? AND from_uom = ? AND to_uom = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setString(3, fromUom);
              ps.setString(4, toUom);
            },
            rs -> rs.getBigDecimal("factor"),
            "find item conversion factor");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private static UomDefinition mapUomDef(ResultSet rs) throws SQLException {
    return new UomDefinition(
        rs.getObject("id", UUID.class),
        rs.getString("class_code"),
        rs.getString("code"),
        rs.getString("name"));
  }

  private static UomItemConversion mapItemConversion(ResultSet rs) throws SQLException {
    return new UomItemConversion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("from_uom"),
        rs.getString("to_uom"),
        rs.getBigDecimal("factor"));
  }

  /**
   * The class a unit code belongs to (WEIGHT, VOLUME, LENGTH, AREA, EACH, TIME).
   *
   * @param code a unit code such as G or ML
   * @return the class, or empty for a code nobody defined
   */
  public java.util.Optional<String> classOf(String code) {
    return query(
            "SELECT class_code FROM uom_definitions WHERE code = ?",
            ps -> ps.setString(1, code),
            rs -> rs.getString(1),
            "uom class of a code")
        .stream()
        .findFirst();
  }
}
