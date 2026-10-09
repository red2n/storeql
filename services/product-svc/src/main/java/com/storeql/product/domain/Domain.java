package com.storeql.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Domain records for the product catalog. */
public final class Domain {

  private Domain() {}

  public record Brand(
      UUID id, UUID tenantId, String name, String status, Instant createdAt, Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
  }

  public record Category(
      UUID id,
      UUID tenantId,
      UUID parentId,
      String name,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
  }

  public record Product(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      UUID brandId,
      UUID categoryId,
      String status,
      boolean sellableOnline,
      boolean sellablePos,
      Instant createdAt,
      Instant updatedAt,
      /** For a NEW_LINE: the day it is meant to go on sale, or null (item lifecycle). */
      java.time.LocalDate launchOn,
      /** When the line was marked for run-down; null unless DISCONTINUED. */
      Instant discontinuedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELISTED = "DELISTED";

    /** Listed, not yet on sale: hidden from the shop, refused at the till until launched. */
    public static final String STATUS_NEW_LINE = "NEW_LINE";

    /** Being run down: sold while stock lasts, never reordered. */
    public static final String STATUS_DISCONTINUED = "DISCONTINUED";

    /** Whether the shop lists it and the till sells it: on sale or being run down. */
    public boolean onSale() {
      return STATUS_ACTIVE.equals(status) || STATUS_DISCONTINUED.equals(status);
    }
  }

  /** The product's primary image (owner-uploaded, one per product, always under 256 KB). */
  public record ProductImage(UUID productId, String contentType, byte[] bytes) {}

  /** UOM class (system reference — no tenant_id). */
  public record UomClass(UUID id, String code, String name) {}

  /** UOM definition within a class (system reference). */
  public record UomDefinition(UUID id, String classCode, String code, String name) {}

  /** Item-level UOM conversion override (tenant + variant scoped). */
  public record UomItemConversion(
      UUID id, UUID tenantId, UUID variantId, String fromUom, String toUom, BigDecimal factor) {}

  public record Variant(
      UUID id,
      UUID tenantId,
      UUID productId,
      String sku,
      String barcode,
      String manufacturerPn,
      String attributes,
      String unit,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
  }

  /** Carrier for a variant + its parent product, used by the POS barcode-scan lookup. */
  public record VariantWithProduct(Variant variant, Product product) {}

  /** A scanned code that is not the variant's own barcode: which kind, and how many units it is. */
  public record AliasHit(VariantWithProduct found, String kind, int packQty) {}

  // ── Gap #33: Supplier / Customer Cross-References ────────────────────────

  /**
   * One of the fourteen allergens Regulation (EU) 1169/2011 Annex II names. Reference data: the
   * list is set by regulation, so it is seeded and read, never written by a tenant.
   */
  public record Allergen(String code, String name, String detail, String regulation) {}

  /**
   * A declaration that one variant contains, or may contain, one allergen.
   *
   * <p>{@link #MAY_CONTAIN} is a cross-contamination warning and is legally a different statement
   * from {@link #CONTAINS}. Collapsing them into a boolean either invents a declaration nobody made
   * or discards one they did.
   */
  public record VariantAllergen(
      UUID tenantId,
      UUID variantId,
      String allergenCode,
      String presence,
      UUID declaredBy,
      Instant declaredAt) {
    public static final String CONTAINS = "CONTAINS";
    public static final String MAY_CONTAIN = "MAY_CONTAIN";
  }

  /**
   * The minimum age for one restricted category in one country.
   *
   * <p>{@code tenantId} is null for the statutory default and set for a tenant's own override — a
   * business may sell above the legal minimum, and some jurisdictions set the age below national
   * level (India varies by state).
   */
  public record AgeRestrictionRule(
      UUID tenantId,
      String country,
      String category,
      int minimumAge,
      String note,
      java.time.LocalDate bornBefore,
      java.time.LocalDate bornBeforeFrom) {

    /** A rule with no birth-date cut-off, as every rule was before the generational tobacco ban. */
    public AgeRestrictionRule(
        UUID tenantId, String country, String category, int minimumAge, String note) {
      this(tenantId, country, category, minimumAge, note, null, null);
    }
  }

  /**
   * A birth-date cut-off in force: refuse anyone born on or after {@code bornBefore}.
   *
   * @param tenantPolicy true when the tenant adopted it, false when it is the law
   */
  public record BirthCutoff(java.time.LocalDate bornBefore, boolean tenantPolicy) {}

  /**
   * What the till must ask before selling an item in a country (10.8).
   *
   * @param ageFromTenant true when the minimum age is the tenant's stricter policy
   * @param bornBefore the cut-off in force today, or null when there is none
   * @param bornBeforeFromTenant true when that cut-off is the tenant's policy rather than the law
   */
  public record AgeCheck(
      String country,
      String category,
      int minimumAge,
      boolean ageFromTenant,
      java.time.LocalDate bornBefore,
      boolean bornBeforeFromTenant) {}

  /** A variant's drinks container, for the deposit a scheme puts on it (09.16). */
  public record DepositContainer(UUID variantId, String material, int volumeMl) {}

  /** How a variant is sold, and the declarations attached to it. */
  public record VariantCompliance(
      UUID variantId,
      String countryOfOrigin,
      String originDetail,
      String restrictionCategory,
      String allergenStatus,
      String ingredients,
      String hsnCode,
      String soldBy,
      java.math.BigDecimal netContent,
      String netContentUom,
      java.math.BigDecimal tareWeight,
      boolean catchWeight,
      /**
       * PET, ALUMINIUM, STEEL or GLASS when sold in a drinks container a deposit scheme may reach
       * (09.16).
       */
      String depositMaterial,
      /** The container's volume in millilitres, with {@code depositMaterial}. */
      Integer depositVolumeMl) {
    public static final String UNDECLARED = "UNDECLARED";
    public static final java.util.List<String> DEPOSIT_MATERIALS =
        java.util.List.of("PET", "ALUMINIUM", "STEEL", "GLASS");
    public static final String DECLARED = "DECLARED";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String EACH = "EACH";
    public static final String WEIGHT = "WEIGHT";
  }

  public record ItemCrossReference(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String partyType,
      UUID partyId,
      String partyName,
      String crossRefNumber,
      Instant createdAt) {
    public static final String SUPPLIER = "SUPPLIER";
    public static final String CUSTOMER = "CUSTOMER";
  }

  // ── Gap #32: Item Relationships ─────────────────────────────────────────

  public record ItemRelationship(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID relatedVariantId,
      String relationshipType,
      Instant createdAt) {
    public static final String SUBSTITUTE = "SUBSTITUTE";
    public static final String COMPLEMENTARY = "COMPLEMENTARY";
  }

  // ── Gap #13: Item Templates ──────────────────────────────────────────────

  public record ItemTemplate(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      String attributes,
      String status,
      Instant createdAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
  }

  public record ItemTemplateApplication(
      UUID id, UUID tenantId, UUID variantId, UUID templateId, Instant appliedAt) {}

  // ── Gap #12: Item Revisions ───────────────────────────────────────────────

  public record ItemRevision(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String revision,
      String description,
      LocalDate effectiveDate,
      String status,
      Instant createdAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
  }

  // ── Gap #37: Container Types / Cartonization ────────────────────────────

  public record ContainerType(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      String description,
      java.math.BigDecimal lengthMm,
      java.math.BigDecimal widthMm,
      java.math.BigDecimal heightMm,
      java.math.BigDecimal maxWeightKg,
      java.math.BigDecimal tareWeightKg,
      Integer maxUnits,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
  }

  public record VariantContainerLink(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID containerTypeId,
      int qtyPerContainer,
      boolean isPrimary,
      Instant createdAt) {}

  // ── Gap #36: 18 Oracle Item Attribute Groups ─────────────────────────────

  public record ItemAttributeGroup(String groupCode, String name, String description) {}

  public record ItemAttributeGroupField(
      String groupCode,
      String fieldCode,
      String label,
      String dataType,
      boolean required,
      int sortOrder) {}

  public record VariantAttributeGroupValues(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String groupCode,
      String values,
      Instant createdAt,
      Instant updatedAt) {}

  // ── Gap #35: Item Catalog Groups & Descriptive Elements ──────────────────

  public record CatalogGroup(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
  }

  public record CatalogGroupElement(
      UUID id,
      UUID tenantId,
      UUID groupId,
      String elementName,
      String dataType,
      boolean required,
      String defaultVal,
      int sortOrder,
      Instant createdAt) {
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_NUMBER = "NUMBER";
    public static final String TYPE_BOOLEAN = "BOOLEAN";
    public static final String TYPE_DATE = "DATE";
  }

  public record VariantCatalogAssignment(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID groupId,
      String elementVals,
      Instant createdAt,
      Instant updatedAt) {}

  // ── Gap #39: Category sets (multi-set / flexfield model) ──────────────────
  public record CategorySet(
      UUID id,
      UUID tenantId,
      String name,
      String description,
      String purpose,
      UUID defaultCatId,
      boolean controlled,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    public static final String PURPOSE_GENERAL = "GENERAL";
    public static final String PURPOSE_INVENTORY = "INVENTORY";
    public static final String PURPOSE_PURCHASING = "PURCHASING";
    public static final String PURPOSE_COSTING = "COSTING";
    public static final String PURPOSE_SALES = "SALES";
  }

  public record CategorySetMember(
      UUID id, UUID tenantId, UUID setId, UUID categoryId, Instant createdAt) {}

  public record VariantCategorySetAssignment(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID setId,
      UUID categoryId,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * What an online offer must show about a product under GPSR art.19 (01.12): its manufacturer, the
   * person responsible for it in the EU when the manufacturer is outside, and its warnings — or the
   * business's statement that none apply. Absent fields are null.
   */
  public record ProductSafety(
      UUID tenantId,
      UUID productId,
      String manufacturerName,
      String manufacturerAddress,
      String manufacturerContact,
      String manufacturerCountry,
      String responsiblePersonName,
      String responsiblePersonAddress,
      String responsiblePersonContact,
      String warnings,
      boolean noWarnings,
      Instant updatedAt,
      UUID updatedBy) {}

  /** An active online product with its safety statement, or null when none was made. */
  public record ListedProductSafety(UUID productId, String name, ProductSafety safety) {}

  /**
   * A product's safety statement as it stands against the law: whether its market requires one for
   * an online offer today, and what an offer would still lack.
   */
  public record SafetySheet(
      UUID productId, ProductSafety safety, boolean required, java.util.List<String> missing) {}

  /** An online product that its market requires safety information for, and what it lacks. */
  public record MissingSafety(UUID productId, String name, java.util.List<String> missing) {}

  /**
   * The quantity one price buys, in the unit a unit price is shown per (03.13): KG, L, M, SQM, or
   * EA for goods sold by number.
   */
  public record UnitMeasure(String unit, java.math.BigDecimal quantity) {}

  /** What a variant says about how it is sold, as the catalogue re-announces it. */
  public record VariantMeasureSource(
      UUID variantId,
      UUID productId,
      String soldBy,
      java.math.BigDecimal netContent,
      String netContentUom,
      boolean catchWeight,
      long version) {}
}
