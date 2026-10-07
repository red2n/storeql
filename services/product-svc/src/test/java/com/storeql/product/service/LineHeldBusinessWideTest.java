package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Product;
import com.storeql.product.domain.Domain.Variant;
import com.storeql.product.dto.Dtos.UpdateProductRequest;
import com.storeql.product.dto.Dtos.UpdateVariantRequest;
import com.storeql.product.repo.ProductRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Item master data is kept centrally; a branch edits only what is local to it (3 Oct 2026). A
 * manager held to stores writes to a line only when it is ranged solely to stores they hold: for a
 * line sold everywhere, or at any store beyond theirs, {@code 403 BUSINESS_WIDE_ONLY}, after the
 * product's 404 and before any write. The repository here notes every write, which is the claim: a
 * refusal writes nothing.
 */
class LineHeldBusinessWideTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();

  private final List<String> writes = new ArrayList<>();
  private final List<String> reads = new ArrayList<>();
  private final Map<UUID, Product> products = new HashMap<>();
  private final Map<UUID, Variant> variants = new HashMap<>();
  private final Map<UUID, List<UUID>> ranges = new HashMap<>();

  /** What the locked read in the writing transaction sees, where it differs from {@code ranges}. */
  private final Map<UUID, List<UUID>> lockedRange = new HashMap<>();

  private final class Products extends ProductRepository {
    @Override
    public Optional<Product> findProduct(UUID tenantId, UUID id) {
      Product p = products.get(id);
      return p != null && p.tenantId().equals(tenantId) ? Optional.of(p) : Optional.empty();
    }

    @Override
    public List<UUID> storesForProduct(UUID tenantId, UUID productId) {
      reads.add("stores");
      return ranges.getOrDefault(productId, List.of());
    }

    @Override
    public Optional<Variant> findVariant(UUID tenantId, UUID variantId) {
      Variant v = variants.get(variantId);
      return v != null && v.tenantId().equals(tenantId) ? Optional.of(v) : Optional.empty();
    }

    @Override
    public List<Variant> listVariants(UUID tenantId, UUID productId) {
      return List.of();
    }

    @Override
    public Product updateProductWithOutbox(
        Product p,
        List<OutboxRow> events,
        Consumer<com.storeql.product.domain.Domain.ProductSafety> guard,
        Consumer<List<UUID>> rangeGuard) {
      rangeGuard.accept(ranges.getOrDefault(p.id(), List.of()));
      writes.add("product");
      return p;
    }

    @Override
    public Variant updateVariant(
        UUID tenantId,
        UUID productId,
        UUID variantId,
        String sku,
        String barcode,
        String manufacturerPn,
        String attributes,
        String unit,
        Consumer<List<UUID>> rangeGuard) {
      if (rangeGuard != null) {
        rangeGuard.accept(lockedRange.getOrDefault(productId, ranges.get(productId)));
      }
      writes.add("variant:" + sku + ":" + barcode);
      return variants.get(variantId);
    }

    @Override
    public Variant createVariantWithOutbox(
        Variant v, OutboxRow event, Consumer<List<UUID>> rangeGuard) {
      // The range as the transaction's lock reads it: it may differ from what was read before.
      if (rangeGuard != null) {
        rangeGuard.accept(lockedRange.getOrDefault(v.productId(), ranges.get(v.productId())));
      }
      writes.add("variant-create:" + v.sku() + ":" + v.barcode());
      return v;
    }

    @Override
    public Variant delistVariant(UUID tenantId, UUID variantId) {
      writes.add("delist");
      return variants.get(variantId);
    }

    @Override
    public Optional<Variant> relistVariant(UUID tenantId, UUID variantId) {
      writes.add("relist");
      Variant v = variants.get(variantId);
      return Optional.of(
          new Variant(
              v.id(),
              v.tenantId(),
              v.productId(),
              v.sku(),
              v.barcode(),
              v.manufacturerPn(),
              v.attributes(),
              v.unit(),
              Variant.STATUS_ACTIVE,
              v.createdAt(),
              Instant.now()));
    }
  }

  private final ProductService svc = new ProductService();

  LineHeldBusinessWideTest() {
    svc.repo = new Products();
  }

  private UUID product(UUID tenant, String status, UUID... range) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    products.put(
        id,
        new Product(id, tenant, "P", null, null, null, status, true, true, now, now, null, null));
    ranges.put(id, List.of(range));
    return id;
  }

  private UUID variant(UUID tenant, UUID product, String status) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    variants.put(
        id,
        new Variant(
            id, tenant, product, "SKU-" + id, "4006381333931", null, null, "EA", status, now, now));
    return id;
  }

  private static TenantContext held(UUID tenant, UUID... stores) {
    return CatalogueStoresTest.caller(tenant, "MANAGER", Set.of(stores));
  }

  private static TenantContext wide(String role) {
    return CatalogueStoresTest.caller(TENANT, role, Set.of());
  }

  private static ApiException refused(Runnable call) {
    return assertThrows(ApiException.class, call::run);
  }

  private static void assertWideOnly(ApiException e) {
    assertThat(e.status(), is(403));
    assertThat(e.code(), is("BUSINESS_WIDE_ONLY"));
  }

  // ── the helper ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("requireLineHeldTo: only a range wholly within the caller's stores is theirs")
  void theHelper() {
    TenantContext a = held(TENANT, A);
    assertDoesNotThrow(() -> CatalogueStores.requireLineHeldTo(a, List.of(A)));
    assertDoesNotThrow(() -> CatalogueStores.requireLineHeldTo(held(TENANT, A, B), List.of(A)));
    assertWideOnly(refused(() -> CatalogueStores.requireLineHeldTo(a, List.of())));
    assertWideOnly(refused(() -> CatalogueStores.requireLineHeldTo(a, List.of(B))));
    assertWideOnly(refused(() -> CatalogueStores.requireLineHeldTo(a, List.of(A, B))));
    for (String role : List.of("OWNER", "MANAGER")) {
      assertDoesNotThrow(() -> CatalogueStores.requireLineHeldTo(wide(role), List.of()));
      assertDoesNotThrow(() -> CatalogueStores.requireLineHeldTo(wide(role), List.of(A, B)));
    }
  }

  // ── a line's own writes ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A held manager cannot write to a line sold everywhere or beyond their stores")
  void aHeldManagerIsRefusedAnOwnersLine() {
    UUID everywhere = product(TENANT, Product.STATUS_ACTIVE);
    UUID beyond = product(TENANT, Product.STATUS_ACTIVE, A, B);
    for (UUID line : List.of(everywhere, beyond)) {
      assertWideOnly(refused(() -> svc.requireLineHeld(held(TENANT, A), line)));
      assertWideOnly(
          refused(
              () ->
                  svc.updateProduct(
                      held(TENANT, A),
                      line,
                      new UpdateProductRequest("X", null, null, null, false, false))));
    }
    assertThat("nothing was written", writes, is(empty()));
  }

  @Test
  @DisplayName("A held manager edits a line local to their stores; an owner edits any line")
  void localLineAndWholeBusinessAreAllowed() {
    UUID local = product(TENANT, Product.STATUS_ACTIVE, A);
    UUID everywhere = product(TENANT, Product.STATUS_ACTIVE);
    var req = new UpdateProductRequest("X", null, null, null, true, true);
    assertDoesNotThrow(() -> svc.requireLineHeld(held(TENANT, A), local));
    assertDoesNotThrow(() -> svc.updateProduct(held(TENANT, A), local, req));
    for (String role : List.of("OWNER", "MANAGER")) {
      assertDoesNotThrow(() -> svc.requireLineHeld(wide(role), everywhere));
      assertDoesNotThrow(() -> svc.updateProduct(wide(role), everywhere, req));
    }
    assertThat(writes.size(), is(3));
  }

  @Test
  @DisplayName("The product's 404 comes first, for every caller, and another business is not found")
  void notFoundBeforeForbidden() {
    UUID theirs = product(RIVAL, Product.STATUS_ACTIVE);
    UUID ours = product(TENANT, Product.STATUS_ACTIVE);
    for (TenantContext ctx : List.of(held(TENANT, A), wide("OWNER"), wide("MANAGER"))) {
      assertThat(
          refused(() -> svc.requireLineHeld(ctx, Ids.newId())).code(), is("PRODUCT_NOT_FOUND"));
      assertThat(refused(() -> svc.requireLineHeld(ctx, theirs)).status(), is(404));
    }
    // another business's manager held to a store of theirs, naming our line: not found, not 403
    TenantContext rival = held(RIVAL, A);
    assertThat(refused(() -> svc.requireLineHeld(rival, ours)).status(), is(404));
    assertThat(
        refused(() -> svc.requireVariantLineHeld(rival, variant(TENANT, ours, "ACTIVE"))).code(),
        is("VARIANT_NOT_FOUND"));
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("A caller held to no store is never asked about the range")
  void noRangeReadForTheWholeBusiness() {
    UUID line = product(TENANT, Product.STATUS_ACTIVE);
    svc.requireLineHeld(wide("OWNER"), line);
    svc.requireVariantLineHeld(wide("MANAGER"), variant(TENANT, line, "ACTIVE"));
    assertThat(reads, is(empty()));
  }

  // ── a variant by its own id ─────────────────────────────────────────────────

  @Test
  @DisplayName("A variant's line is judged by the variant's own product")
  void variantScoped() {
    UUID everywhere = product(TENANT, Product.STATUS_ACTIVE);
    UUID local = product(TENANT, Product.STATUS_ACTIVE, A);
    UUID owners = variant(TENANT, everywhere, Variant.STATUS_ACTIVE);
    UUID mine = variant(TENANT, local, Variant.STATUS_ACTIVE);
    assertWideOnly(refused(() -> svc.requireVariantLineHeld(held(TENANT, A), owners)));
    assertThat(svc.requireVariantLineHeld(held(TENANT, A), mine).id(), is(mine));
    assertThat(
        refused(() -> svc.requireVariantLineHeld(held(TENANT, A), Ids.newId())).code(),
        is("VARIANT_NOT_FOUND"));
  }

  // ── the SKU hijack ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("The two-call SKU hijack is refused at each step and nothing is written")
  void theSkuHijack() {
    UUID ownersLine = product(TENANT, Product.STATUS_ACTIVE);
    UUID myLine = product(TENANT, Product.STATUS_ACTIVE, A);
    UUID ownersVariant = variant(TENANT, ownersLine, Variant.STATUS_ACTIVE);
    TenantContext me = held(TENANT, A);
    var blank = new UpdateVariantRequest("OTHER-SKU", null, null, null, "EA");

    // Step 1, the owner's own path: the line is theirs to keep.
    assertWideOnly(refused(() -> svc.requireLineHeld(me, ownersLine)));
    // Step 1, through a line of their own: the variant is not that product's.
    svc.requireLineHeld(me, myLine);
    ApiException mismatch = refused(() -> svc.updateVariant(me, myLine, ownersVariant, blank));
    assertThat(mismatch.status(), is(404));
    assertThat(mismatch.code(), is("VARIANT_NOT_FOUND"));
    assertThat("no SKU or barcode was freed", writes, is(empty()));
  }

  // ── identifiers are judged under the lock ───────────────────────────────────

  @Test
  @DisplayName(
      "A variant create or update is judged on the range the writing transaction reads, not the"
          + " one read just before")
  void identifierWritesAreJudgedUnderTheLock() {
    // Read before the write the line is local to A; the owner widens it before the lock is taken.
    UUID line = product(TENANT, Product.STATUS_ACTIVE, A);
    UUID v = variant(TENANT, line, Variant.STATUS_ACTIVE);
    TenantContext me = held(TENANT, A);
    var create =
        new com.storeql.product.dto.Dtos.CreateVariantRequest("NEW-SKU", null, null, null, "EA");
    var update = new UpdateVariantRequest("HIJACK", null, null, null, "EA");
    assertDoesNotThrow(() -> svc.requireLineHeld(me, line)); // the early read passes
    lockedRange.put(line, List.of(A, B)); // the range as the locked transaction sees it

    assertWideOnly(refused(() -> svc.createVariant(me, line, create)));
    assertWideOnly(refused(() -> svc.updateVariant(me, line, v, update)));
    assertThat("no SKU or barcode was taken", writes, is(empty()));

    // An owner, or a manager held to none, is never asked about the range.
    assertDoesNotThrow(() -> svc.createVariant(wide("OWNER"), line, create));
    assertDoesNotThrow(() -> svc.updateVariant(wide("MANAGER"), line, v, update));
    assertThat(writes.size(), is(2));
  }

  @Test
  @DisplayName("A held manager creates and updates a variant of a line local to their stores")
  void identifierWritesOnALocalLineAreAllowed() {
    UUID line = product(TENANT, Product.STATUS_ACTIVE, A);
    UUID v = variant(TENANT, line, Variant.STATUS_ACTIVE);
    TenantContext me = held(TENANT, A);
    assertDoesNotThrow(
        () ->
            svc.createVariant(
                me,
                line,
                new com.storeql.product.dto.Dtos.CreateVariantRequest(
                    "LOCAL", null, null, null, "EA")));
    assertDoesNotThrow(
        () ->
            svc.updateVariant(
                me, line, v, new UpdateVariantRequest("LOCAL-2", null, null, null, "EA")));
    assertThat(writes.size(), is(2));
  }

  // ── variant delist and relist ───────────────────────────────────────────────

  @Test
  @DisplayName("A variant that is not the path's product's is not found, on delist and update")
  void pathProductIsChecked() {
    UUID line = product(TENANT, Product.STATUS_ACTIVE);
    UUID other = product(TENANT, Product.STATUS_ACTIVE);
    UUID v = variant(TENANT, line, Variant.STATUS_ACTIVE);
    assertThat(
        refused(() -> svc.delistVariant(wide("OWNER"), other, v)).code(), is("VARIANT_NOT_FOUND"));
    assertThat(refused(() -> svc.getVariantOf(TENANT, other, v)).status(), is(404));
    assertThat(svc.getVariantOf(TENANT, line, v).id(), is(v));
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("Relist: a held manager is refused before the variant is read, whatever the line")
  void relistIsWholeBusiness() {
    UUID local = product(TENANT, Product.STATUS_ACTIVE, A);
    UUID v = variant(TENANT, local, Variant.STATUS_INACTIVE);
    assertWideOnly(refused(() -> svc.relistVariant(held(TENANT, A), local, v)));
    assertWideOnly(refused(() -> svc.relistVariant(held(TENANT, A), Ids.newId(), Ids.newId())));
    assertThat(writes, is(empty()));
    assertThat(reads, is(empty()));
  }

  @Test
  @DisplayName("Relist: 404 for another product's or business's variant, 409 for a wrong state")
  void relistStates() {
    UUID line = product(TENANT, Product.STATUS_ACTIVE);
    UUID other = product(TENANT, Product.STATUS_ACTIVE);
    UUID off = variant(TENANT, line, Variant.STATUS_INACTIVE);
    UUID on = variant(TENANT, line, Variant.STATUS_ACTIVE);
    UUID rivals = variant(RIVAL, product(RIVAL, Product.STATUS_ACTIVE), Variant.STATUS_INACTIVE);
    TenantContext owner = wide("OWNER");
    assertThat(refused(() -> svc.relistVariant(owner, other, off)).code(), is("VARIANT_NOT_FOUND"));
    assertThat(
        refused(() -> svc.relistVariant(owner, line, rivals)).code(), is("VARIANT_NOT_FOUND"));
    assertThat(
        refused(() -> svc.relistVariant(owner, line, on)).code(), is("VARIANT_NOT_DELISTED"));
    UUID dead = product(TENANT, Product.STATUS_DELISTED);
    UUID deadVariant = variant(TENANT, dead, Variant.STATUS_INACTIVE);
    ApiException e = refused(() -> svc.relistVariant(owner, dead, deadVariant));
    assertThat(e.status(), is(409));
    assertThat(e.code(), is("VARIANT_PRODUCT_NOT_ON_SALE"));
    assertThat(writes, is(empty()));
    for (TenantContext whole : List.of(owner, wide("MANAGER"))) {
      assertThat(svc.relistVariant(whole, line, off).status(), is(Variant.STATUS_ACTIVE));
    }
    assertThat(writes, is(List.of("relist", "relist")));
  }

  // ── the whole business's catalogue data and policy ──────────────────────────

  @Test
  @DisplayName("Whole-business catalogue data is refused a manager held to stores, naming who can")
  void wholeBusinessFamily() {
    ApiException e =
        refused(() -> svc.requireBusinessWideCatalogue(held(TENANT, A), "Maintaining categories"));
    assertWideOnly(e);
    assertThat(e.getMessage(), containsString("an owner or a manager of the whole business"));
    assertThat(e.getMessage(), containsString("Maintaining categories"));
    assertDoesNotThrow(() -> svc.requireBusinessWideCatalogue(wide("OWNER"), "x"));
    assertDoesNotThrow(() -> svc.requireBusinessWideCatalogue(wide("MANAGER"), "x"));
  }
}
