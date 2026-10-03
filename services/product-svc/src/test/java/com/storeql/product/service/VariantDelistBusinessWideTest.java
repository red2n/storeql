package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Product;
import com.storeql.product.domain.Domain.ProductSafety;
import com.storeql.product.domain.Domain.Variant;
import com.storeql.product.dto.Dtos.BulkImportRequest;
import com.storeql.product.dto.Dtos.BulkImportResult;
import com.storeql.product.dto.Dtos.ImportProductRequest;
import com.storeql.product.dto.Dtos.ImportVariantRequest;
import com.storeql.product.repo.ProductRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Taking a variant off sale is the whole business's to decide, as delisting its line is (3 Oct
 * 2026). A variant is sold at every store its line is ranged at, so {@code DELETE
 * /admin/products/{id}/variants/{variantId}} took a line off sale everywhere one variant at a time,
 * for a manager held to one branch who had just been refused the delist of the line itself. A
 * {@code REPLACE} import did the same by another door: it drops the variant holding a row's SKU
 * before writing the row's own.
 *
 * <p>A manager held to stores is refused {@code 403 BUSINESS_WIDE_ONLY} before the variant is read,
 * whatever its line's range, and their {@code REPLACE} row never drops a variant that is already
 * there. The repository here notes every read and write, which is the claim: for a refusal, none.
 */
class VariantDelistBusinessWideTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID OTHER_STORE = Ids.newId();

  /** Another business, which must move nothing of ours. */
  private static final UUID RIVAL = Ids.newId();

  private static final UUID RIVAL_STORE = Ids.newId();

  private static final String WHO_CAN = "an owner or a manager of the whole business";

  private final List<String> reads = new ArrayList<>();
  private final List<String> writes = new ArrayList<>();

  /** Each business's variants, by id. */
  private final Map<UUID, Variant> variants = new HashMap<>();

  private final class Products extends ProductRepository {
    @Override
    public Optional<Variant> findVariant(UUID tenantId, UUID variantId) {
      reads.add("variant");
      Variant v = variants.get(variantId);
      return v != null && v.tenantId().equals(tenantId) ? Optional.of(v) : Optional.empty();
    }

    @Override
    public Optional<Product> findProduct(UUID tenantId, UUID id) {
      reads.add("product");
      return Optional.empty();
    }

    @Override
    public List<UUID> storesForProduct(UUID tenantId, UUID productId) {
      reads.add("stores");
      return List.of(STORE);
    }

    @Override
    public Variant delistVariant(UUID tenantId, UUID variantId) {
      writes.add("delist");
      Variant v = variants.get(variantId);
      Variant off =
          new Variant(
              v.id(),
              v.tenantId(),
              v.productId(),
              v.sku(),
              v.barcode(),
              v.manufacturerPn(),
              v.attributes(),
              v.unit(),
              Variant.STATUS_INACTIVE,
              v.createdAt(),
              Instant.now());
      variants.put(variantId, off);
      return off;
    }

    // ── what an import reaches ──

    @Override
    public Optional<Product> findProductByNameAndCategory(
        UUID tenantId, String name, UUID categoryId) {
      return Optional.empty();
    }

    @Override
    public Product createProductWithOutbox(
        Product p, List<OutboxRow> events, ProductSafety safety, List<UUID> storeIds) {
      writes.add("product:" + p.name());
      return p;
    }

    @Override
    public Set<String> skusHeld(UUID tenantId, Collection<String> skus) {
      return variants.values().stream()
          .filter(v -> v.tenantId().equals(tenantId) && skus.contains(v.sku()))
          .map(Variant::sku)
          .collect(Collectors.toSet());
    }

    @Override
    public void deleteVariantBySku(UUID tenantId, String sku) {
      writes.add("drop:" + sku);
      variants.values().removeIf(v -> v.tenantId().equals(tenantId) && v.sku().equals(sku));
    }

    @Override
    public Variant createVariantWithOutbox(Variant v, OutboxRow event) {
      writes.add("variant:" + v.sku());
      variants.put(v.id(), v);
      return v;
    }
  }

  private final ProductService svc = new ProductService();

  VariantDelistBusinessWideTest() {
    svc.repo = new Products();
  }

  private UUID variant(UUID tenantId, String sku, String status) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    variants.put(
        id, new Variant(id, tenantId, Ids.newId(), sku, null, null, null, "EA", status, now, now));
    return id;
  }

  private UUID variant(UUID tenantId) {
    return variant(tenantId, "SKU-" + Ids.newId(), Variant.STATUS_ACTIVE);
  }

  private static ApiException refused(Runnable call) {
    return assertThrows(ApiException.class, call::run);
  }

  // ── DELETE /admin/products/{id}/variants/{variantId} ─────────────────────────

  @Test
  @DisplayName(
      "A manager held to stores is refused a variant's delist before it is read, and nothing"
          + " changes")
  void aManagerHeldToStoresIsRefusedBeforeTheVariantIsRead() {
    UUID ours = variant(TENANT);
    UUID product = variants.get(ours).productId();

    for (Set<UUID> heldTo : List.of(Set.of(STORE), Set.of(STORE, OTHER_STORE))) {
      TenantContext held = CatalogueStoresTest.caller(TENANT, "MANAGER", heldTo);
      ApiException e = refused(() -> svc.delistVariant(held, product, ours));
      assertThat(e.status(), is(403));
      assertThat(e.code(), is("BUSINESS_WIDE_ONLY"));
      // Who can is named, in the words every held-manager refusal of the catalogue uses.
      assertThat(e.getMessage(), containsString(WHO_CAN));
    }
    // A variant that does not exist gets the same answer: who may is asked before what it names.
    TenantContext held = CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of(STORE));
    ApiException never = refused(() -> svc.delistVariant(held, Ids.newId(), Ids.newId()));
    assertThat(never.code(), is("BUSINESS_WIDE_ONLY"));

    assertThat("the variant, its line and its range were never read", reads, is(empty()));
    assertThat("nothing was written", writes, is(empty()));
    assertThat(variants.get(ours).status(), is(Variant.STATUS_ACTIVE));
  }

  @Test
  @DisplayName("An owner and a manager of the whole business take a variant off sale")
  void theWholeBusinessDelistsAVariant() {
    for (TenantContext whole :
        List.of(
            CatalogueStoresTest.caller(TENANT, "OWNER", Set.of()),
            CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of()))) {
      UUID ours = variant(TENANT);
      Variant off = svc.delistVariant(whole, variants.get(ours).productId(), ours);
      assertThat(off.status(), is(Variant.STATUS_INACTIVE));
      assertThat(variants.get(ours).status(), is(Variant.STATUS_INACTIVE));
    }
    assertThat(writes, contains("delist", "delist"));
    assertThat(
        "the range is not what decides, so it is never read", reads.contains("stores"), is(false));
  }

  @Test
  @DisplayName("Another business's staff, held or not, naming our variant move nothing of ours")
  void anotherBusinessMovesNothingOfOurs() {
    UUID ours = variant(TENANT);
    UUID product = variants.get(ours).productId();

    // Held to stores (theirs, or naming ours): refused as held, before anything is read.
    for (Set<UUID> heldTo : List.of(Set.of(RIVAL_STORE), Set.of(STORE))) {
      TenantContext rival = CatalogueStoresTest.caller(RIVAL, "MANAGER", heldTo);
      ApiException e = refused(() -> svc.delistVariant(rival, product, ours));
      assertThat(e.status(), is(403));
      assertThat(e.code(), is("BUSINESS_WIDE_ONLY"));
    }
    assertThat(reads, is(empty()));

    // Held to none: our variant is not theirs to find.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      TenantContext rival = CatalogueStoresTest.caller(RIVAL, role, Set.of());
      ApiException e = refused(() -> svc.delistVariant(rival, product, ours));
      assertThat(role, e.status(), is(404));
      assertThat(role, e.code(), is("VARIANT_NOT_FOUND"));
    }

    assertThat("nothing was written", writes, is(empty()));
    assertThat(variants.get(ours).status(), is(Variant.STATUS_ACTIVE));
  }

  // ── the same, through a REPLACE import ───────────────────────────────────────

  private static ImportProductRequest row(String name, String... skus) {
    return new ImportProductRequest(
        name,
        null,
        null,
        null,
        false,
        true,
        null,
        List.of(skus).stream()
            .map(sku -> new ImportVariantRequest(sku, null, null, "EA", null))
            .toList());
  }

  private static BulkImportRequest replace(ImportProductRequest... rows) {
    return new BulkImportRequest(null, List.of(rows), "REPLACE");
  }

  @Test
  @DisplayName(
      "A held manager's REPLACE row never drops a variant already there, on sale or delisted;"
          + " what is new in the sheet is still imported")
  void aHeldManagersReplaceNeverDropsAVariantAlreadyThere() {
    UUID onSale = variant(TENANT, "COLA-1", Variant.STATUS_ACTIVE);
    UUID delisted = variant(TENANT, "OLD-1", Variant.STATUS_INACTIVE);
    UUID lineOfOnSale = variants.get(onSale).productId();
    TenantContext held = CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of(STORE));

    BulkImportResult result =
        svc.bulkImport(
            held,
            replace(
                // Our SKU moved under a line of their own, ranged to their store alone.
                row("My cola", "COLA-1"),
                // A delisted variant brought back by dropping it and writing it anew.
                row("My old line", "OLD-1"),
                // A row with one of each: the new one is written, the one there is left.
                row("My mix", "COLA-1", "NEW-1")));

    assertThat(
        result.errors().stream().map(e -> e.item()).toList(),
        contains(
            "variant:COLA-1 on My cola",
            "variant:OLD-1 on My old line",
            "variant:COLA-1 on My mix"));
    for (var error : result.errors()) {
      assertThat(error.reason(), startsWith("BUSINESS_WIDE_ONLY: "));
      assertThat(error.reason(), containsString(WHO_CAN));
    }
    assertThat(
        "nothing was dropped, and a new line with nothing left to write was not made",
        writes,
        contains("product:My mix", "variant:NEW-1"));
    assertThat(result.productsCreated(), is(1));
    assertThat(result.variantsCreated(), is(1));
    assertThat(result.importedVariants().stream().map(v -> v.sku()).toList(), contains("NEW-1"));
    // The variants that were there are the ones still there: same id, same line, same state.
    assertThat(variants.get(onSale).status(), is(Variant.STATUS_ACTIVE));
    assertThat(variants.get(onSale).productId(), is(lineOfOnSale));
    assertThat(variants.get(delisted).status(), is(Variant.STATUS_INACTIVE));
  }

  @Test
  @DisplayName("An owner's and a whole-business manager's REPLACE still lets the sheet win")
  void theWholeBusinesssReplaceStillLetsTheSheetWin() {
    variant(TENANT, "COLA-1", Variant.STATUS_ACTIVE);

    for (TenantContext whole :
        List.of(
            CatalogueStoresTest.caller(TENANT, "OWNER", Set.of()),
            CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of()))) {
      writes.clear();
      BulkImportResult result = svc.bulkImport(whole, replace(row("Cola", "COLA-1")));
      assertThat(result.errors(), is(empty()));
      assertThat(writes, contains("product:Cola", "drop:COLA-1", "variant:COLA-1"));
    }
  }

  @Test
  @DisplayName(
      "Another business's held manager naming our SKU in a REPLACE touches nothing of ours")
  void anotherBusinesssReplaceNamingOurSkuTouchesNothingOfOurs() {
    UUID ours = variant(TENANT, "COLA-1", Variant.STATUS_ACTIVE);

    for (Set<UUID> heldTo : List.of(Set.<UUID>of(), Set.of(RIVAL_STORE))) {
      writes.clear();
      TenantContext rival = CatalogueStoresTest.caller(RIVAL, "MANAGER", heldTo);
      BulkImportResult result = svc.bulkImport(rival, replace(row("Theirs", "COLA-1")));
      // The SKU is free in their business, so it is theirs to write; ours is not looked at.
      assertThat(result.errors(), is(empty()));
      assertThat(variants.get(ours).tenantId(), is(TENANT));
      assertThat(variants.get(ours).status(), is(Variant.STATUS_ACTIVE));
      variants.values().removeIf(v -> v.tenantId().equals(RIVAL));
    }
  }
}
