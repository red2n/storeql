package com.storeql.product.service;

import com.storeql.product.domain.Domain.Variant;
import com.storeql.product.domain.imports.ImportItem;
import com.storeql.product.dto.Dtos.CreateProductRequest;
import com.storeql.product.dto.Dtos.CreateVariantRequest;
import com.storeql.product.dto.Dtos.UpdateProductRequest;
import com.storeql.product.dto.Dtos.UpdateVariantRequest;
import com.storeql.product.dto.Dtos.VariantComplianceRequest;
import com.storeql.product.repo.BrandRepository;
import com.storeql.product.repo.CategoryRepository;
import com.storeql.product.repo.ComplianceRepository;
import com.storeql.product.repo.ImportRepository;
import com.storeql.product.repo.ImportRepository.VariantRef;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The catalogue half of an apply (intent/catalogue-import.md): each row becomes a product and a
 * variant, or updates the ones its SKU names, through the same service methods a person's own entry
 * goes through (events, plan limit, barcode rules) — never a raw insert.
 *
 * <p>Idempotent by SKU: a row is judged against what the catalogue holds now, not against the dry
 * run, so a chunk run again after a crash creates nothing twice and changes only what still
 * differs. A blank cell never erases. A new product is made for the till ({@code sellablePos}), not
 * the shop window ({@code sellableOnline} off): putting a line online is a decision with its own
 * legal conditions (product safety information) and is the owner's, line by line.
 */
@ApplicationScoped
public class ImportApplier {

  @Inject ProductService products;
  @Inject ImportRepository repo;
  @Inject BrandRepository brands;
  @Inject CategoryRepository categories;
  @Inject ComplianceRepository compliance;

  /** What one chunk of rows did. */
  public record Done(int created, int updated, int unchanged, int aliases, List<String> errors) {
    public Done {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Writes the catalogue half of a chunk. A row that fails is recorded with its line and the rest
   * go on.
   *
   * @param items the rows of the chunk, as read from the file
   */
  public Done apply(UUID tenantId, List<ImportItem> items) {
    TenantContext ctx = new TenantContext();
    ctx.assume(tenantId, "OWNER");
    Map<String, UUID> brandIds = new HashMap<>();
    Map<String, UUID> categoryIds = new HashMap<>();
    int created = 0;
    int updated = 0;
    int unchanged = 0;
    int aliases = 0;
    List<String> errors = new ArrayList<>();
    for (ImportItem item : items) {
      try {
        var held = repo.variantBySku(tenantId, item.sku());
        UUID variantId;
        if (held.isEmpty()) {
          variantId = create(ctx, tenantId, item, brandIds, categoryIds);
          created++;
        } else if (update(ctx, tenantId, item, held.get(), brandIds, categoryIds)) {
          variantId = held.get().variantId();
          updated++;
        } else {
          variantId = held.get().variantId();
          unchanged++;
        }
        for (var a : item.aliases()) {
          if (repo.addAlias(tenantId, variantId, a.gtin14(), a.kind(), a.packQty())) {
            aliases++;
          } else {
            errors.add(
                "line " + item.line() + ": " + a.gtin14() + " is already another product's code");
          }
        }
      } catch (ApiException e) {
        errors.add(
            "line " + item.line() + " (" + item.sku() + "): " + e.code() + " " + e.getMessage());
      } catch (RuntimeException e) {
        errors.add("line " + item.line() + " (" + item.sku() + "): the row could not be written");
      }
    }
    return new Done(created, updated, unchanged, aliases, errors);
  }

  // ── a new product ────────────────────────────────────────────────────────────

  private UUID create(
      TenantContext ctx,
      UUID tenantId,
      ImportItem item,
      Map<String, UUID> brandIds,
      Map<String, UUID> categoryIds) {
    UUID brandId = brandOf(tenantId, item.brand(), brandIds);
    UUID categoryId = categoryOf(tenantId, item.categoryPath(), categoryIds);
    var product =
        products.createProduct(
            ctx,
            new CreateProductRequest(
                item.name(),
                null,
                brandId == null ? null : brandId.toString(),
                categoryId == null ? null : categoryId.toString(),
                false,
                true,
                null,
                null,
                null,
                List.of()),
            List.of());
    Variant variant =
        products.createVariant(
            ctx,
            product.id(),
            new CreateVariantRequest(item.sku(), item.barcode(), null, null, item.unit()));
    if (item.soldBy() != null && !"EACH".equals(item.soldBy())) {
      setSoldBy(tenantId, variant.id(), item);
    }
    return variant.id();
  }

  // ── an existing one ──────────────────────────────────────────────────────────

  /** Changes what differs and only that; returns whether anything did. */
  private boolean update(
      TenantContext ctx,
      UUID tenantId,
      ImportItem item,
      VariantRef held,
      Map<String, UUID> brandIds,
      Map<String, UUID> categoryIds) {
    boolean changed = false;
    UUID brandId =
        item.brand() != null ? brandOf(tenantId, item.brand(), brandIds) : held.brandId();
    UUID categoryId =
        item.categoryPath().isEmpty()
            ? held.categoryId()
            : categoryOf(tenantId, item.categoryPath(), categoryIds);
    boolean productDiffers =
        !item.name().equals(held.name())
            || !java.util.Objects.equals(brandId, held.brandId())
            || !java.util.Objects.equals(categoryId, held.categoryId());
    if (productDiffers) {
      products.updateProduct(
          ctx,
          held.productId(),
          new UpdateProductRequest(
              item.name(),
              held.description(),
              brandId == null ? null : brandId.toString(),
              categoryId == null ? null : categoryId.toString(),
              held.sellableOnline(),
              held.sellablePos()));
      changed = true;
    }
    boolean barcodeDiffers = item.gtin14() != null && !item.gtin14().equals(held.gtin14());
    boolean unitDiffers = item.unit() != null && !item.unit().equals(held.unit());
    if (barcodeDiffers || unitDiffers) {
      products.updateVariant(
          ctx,
          held.productId(),
          held.variantId(),
          new UpdateVariantRequest(
              item.sku(),
              barcodeDiffers ? item.barcode() : held.barcode(),
              held.manufacturerPn(),
              held.attributes(),
              unitDiffers ? item.unit() : held.unit()));
      changed = true;
    }
    if (item.soldBy() != null
        && (!item.soldBy().equals(held.soldBy())
            || (!"EACH".equals(item.soldBy())
                && item.unit() != null
                && !item.unit().equals(held.netContentUom())))) {
      setSoldBy(tenantId, held.variantId(), item);
      changed = true;
    }
    return changed;
  }

  /**
   * Says how the variant is sold, keeping everything else the variant's compliance holds (its
   * origin, ingredients, tax code, deposit) as it is.
   */
  private void setSoldBy(UUID tenantId, UUID variantId, ImportItem item) {
    var current = compliance.findCompliance(tenantId, variantId);
    boolean measured = !"EACH".equals(item.soldBy());
    products.updateCompliance(
        tenantId,
        variantId,
        new VariantComplianceRequest(
            current == null ? null : current.countryOfOrigin(),
            current == null ? null : current.originDetail(),
            current == null ? null : current.restrictionCategory(),
            current == null ? null : current.ingredients(),
            current == null ? null : current.hsnCode(),
            item.soldBy(),
            measured ? null : (current == null ? null : current.netContent()),
            measured ? item.unit() : (current == null ? null : current.netContentUom()),
            current == null ? null : current.tareWeight(),
            current != null && current.catchWeight(),
            current == null ? null : current.depositMaterial(),
            current == null ? null : current.depositVolumeMl(),
            null));
  }

  // ── brand and category ───────────────────────────────────────────────────────

  private UUID brandOf(UUID tenantId, String name, Map<String, UUID> cache) {
    if (name == null || name.isBlank()) return null;
    String key = name.trim().toLowerCase(Locale.ROOT);
    UUID known = cache.get(key);
    if (known != null) return known;
    UUID id =
        brands
            .findBrandByName(tenantId, name.trim())
            .map(b -> b.id())
            .orElseGet(() -> brands.createBrand(tenantId, name.trim()).id());
    cache.put(key, id);
    return id;
  }

  private UUID categoryOf(UUID tenantId, List<String> path, Map<String, UUID> cache) {
    if (path.isEmpty()) return null;
    UUID parent = null;
    StringBuilder key = new StringBuilder();
    for (String level : path) {
      key.append('>').append(level.toLowerCase(Locale.ROOT));
      UUID known = cache.get(key.toString());
      if (known == null) {
        UUID under = parent;
        known =
            categories
                .findCategoryByNameUnder(tenantId, under, level)
                .map(c -> c.id())
                .orElseGet(() -> categories.createCategory(tenantId, under, level).id());
        cache.put(key.toString(), known);
      }
      parent = known;
    }
    return parent;
  }
}
