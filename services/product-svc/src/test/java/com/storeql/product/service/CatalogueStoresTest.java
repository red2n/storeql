package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Brand;
import com.storeql.product.domain.Domain.Category;
import com.storeql.product.domain.Domain.Product;
import com.storeql.product.domain.Domain.ProductSafety;
import com.storeql.product.domain.Domain.Variant;
import com.storeql.product.dto.Dtos.BulkImportRequest;
import com.storeql.product.dto.Dtos.BulkImportResult;
import com.storeql.product.dto.Dtos.CreateProductRequest;
import com.storeql.product.dto.Dtos.ImportProductRequest;
import com.storeql.product.dto.Dtos.ImportVariantRequest;
import com.storeql.product.repo.BrandRepository;
import com.storeql.product.repo.CategoryRepository;
import com.storeql.product.repo.ProductRepository;
import com.storeql.service.Entitlements;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Every store a catalogue write names is one of the business's own, and, for a manager held to
 * stores, one of theirs — checked before anything is written.
 *
 * <p>Stores are tenant-svc's. A product's store assortment ({@code PUT
 * /admin/products/{id}/stores}) and a bulk import's {@code storeIds} took any well-formed id:
 * another business's store, or one nobody made, was kept in {@code product_stores} as though it
 * were real, and a manager held to one branch ranged a product into, or out of, every other. Here
 * tenant-svc is a stand-in that knows each business's stores, and the repositories note every
 * write, which is the claim: for a refusal, none.
 */
class CatalogueStoresTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();
  private static final UUID STORE_C = Ids.newId();
  private static final UUID RIVAL_STORE = Ids.newId();
  private static final UUID PRODUCT = Ids.newId();

  /** Ours has three stores, the rival one; a store id nobody made is in neither. */
  private static final Map<UUID, Set<UUID>> STORES =
      Map.of(TENANT, Set.of(STORE_A, STORE_B, STORE_C), RIVAL, Set.of(RIVAL_STORE));

  private final List<String> writes = new ArrayList<>();

  /**
   * What each product of ours is ranged to, as the transaction that changes it reads it with the
   * product row locked; a product with no entry is sold everywhere.
   */
  private final Map<UUID, List<UUID>> ranged = new HashMap<>();

  /**
   * What a read made before that transaction would see, when it differs: another change has landed
   * in between. Null when nothing has.
   */
  private List<UUID> staleRead;

  /** Whether "Tea", uncategorised, is already in the catalogue, for an import in REPLACE. */
  private boolean teaHeld;

  private int storeReads;

  private static Product tea() {
    Instant now = Instant.now();
    return new Product(
        PRODUCT, TENANT, "Tea", null, null, null, "ACTIVE", false, true, now, now, null, null);
  }

  private final class Products extends ProductRepository {
    @Override
    public Optional<Product> findProduct(UUID tenantId, UUID id) {
      if (!TENANT.equals(tenantId) || !PRODUCT.equals(id)) return Optional.empty();
      return Optional.of(tea());
    }

    @Override
    public Optional<Product> findProductByNameAndCategory(
        UUID tenantId, String name, UUID categoryId) {
      return teaHeld && TENANT.equals(tenantId) && "Tea".equals(name) && categoryId == null
          ? Optional.of(tea())
          : Optional.empty();
    }

    @Override
    public List<UUID> storesForProduct(UUID tenantId, UUID productId) {
      return staleRead != null ? staleRead : ranged.getOrDefault(productId, List.of());
    }

    @Override
    public void setStoresForProduct(
        UUID tenantId, UUID productId, List<UUID> storeIds, Consumer<List<UUID>> guard) {
      // As the repository does: the guard sees the range read inside the transaction.
      guard.accept(ranged.getOrDefault(productId, List.of()));
      writes.add("set:" + storeIds);
    }

    @Override
    public Product createProductWithOutbox(
        Product p, List<OutboxRow> events, ProductSafety safety, List<UUID> storeIds) {
      writes.add("product:" + p.name());
      if (!storeIds.isEmpty()) writes.add("stores:" + storeIds);
      return p;
    }

    @Override
    public Variant createVariantWithOutbox(Variant v, OutboxRow event) {
      writes.add("variant:" + v.sku());
      return v;
    }

    @Override
    public void deleteVariantBySku(UUID tenantId, String sku) {
      // Replacing a variant is no change to where the product is sold.
    }

    @Override
    public Set<String> skusHeld(UUID tenantId, java.util.Collection<String> skus) {
      // The SKUs these sheets name are new; a held manager's row that names one already there is
      // VariantDelistBusinessWideTest's.
      return Set.of();
    }

    @Override
    public void addStoreAssignments(
        UUID tenantId, UUID productId, List<UUID> storeIds, Consumer<List<UUID>> guard) {
      guard.accept(ranged.getOrDefault(productId, List.of()));
      writes.add("stores:" + storeIds);
    }
  }

  /** A plan with room for every product. */
  private static final class Unlimited extends Entitlements {
    @Override
    public void requireRoom(UUID tenantId, String key, String what, LongSupplier used) {}
  }

  private final class Categories extends CategoryRepository {
    @Override
    public Optional<Category> findCategoryByName(UUID tenantId, String name) {
      return Optional.empty();
    }

    @Override
    public Category createCategory(UUID tenantId, UUID parentId, String name) {
      writes.add("category:" + name);
      Instant now = Instant.now();
      return new Category(Ids.newId(), tenantId, parentId, name, "ACTIVE", now, now);
    }
  }

  private final class Brands extends BrandRepository {
    @Override
    public Optional<Brand> findBrandByName(UUID tenantId, String name) {
      return Optional.empty();
    }

    @Override
    public Brand createBrand(UUID tenantId, String name) {
      writes.add("brand:" + name);
      Instant now = Instant.now();
      return new Brand(Ids.newId(), tenantId, name, "ACTIVE", now, now);
    }
  }

  /** tenant-svc as it answers: each business's own stores, read when asked. */
  private final class Tenants extends TenantProfiles {
    @Override
    public Stores stores(UUID tenantId, UUID including) {
      storeReads++;
      return new Stores(STORES.getOrDefault(tenantId, Set.of()), Map.of());
    }

    @Override
    public Optional<Profile> find(UUID tenantId) {
      return Optional.empty();
    }
  }

  private final ProductService svc = new ProductService();

  CatalogueStoresTest() {
    svc.repo = new Products();
    svc.categoryRepo = new Categories();
    svc.brandRepo = new Brands();
    svc.profiles = new Tenants();
    svc.entitlements = new Unlimited();
  }

  /**
   * A caller as the gateway describes one: the shared filter that reads the headers is the only
   * writer of a {@code TenantContext}, so a test reaches the same setter it uses.
   */
  static TenantContext caller(UUID tenant, String role, Set<UUID> heldTo) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, tenant, Ids.newId(), Set.of(role), heldTo, null, "req-1");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static final TenantContext OWNER = caller(TENANT, "OWNER", Set.of());

  /** A manager held to store A alone. */
  private static final TenantContext HELD_TO_A = caller(TENANT, "MANAGER", Set.of(STORE_A));

  private static void assertRefused(Executable call, int status, String code) {
    ApiException e = assertThrows(ApiException.class, call);
    assertThat(e.getMessage(), e.status(), is(status));
    assertThat(e.getMessage(), e.code(), is(code));
  }

  private void setStores(TenantContext ctx, UUID... stores) {
    svc.setProductStores(ctx, PRODUCT, List.of(stores));
  }

  // ── a product's store assortment ─────────────────────────────────────────────

  @Test
  @DisplayName("A store that is not the business's is not found, and the range is not touched")
  void aStoreThatIsNotTheBusinessesIsNotFound() {
    UUID neverMade = Ids.newId();

    assertRefused(() -> setStores(OWNER, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");
    assertRefused(() -> setStores(OWNER, neverMade), 404, "PRODUCT_STORE_NOT_FOUND");
    // One bad store in a list refuses the list: none of it is written.
    assertRefused(() -> setStores(OWNER, STORE_A, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");
    // A manager held to stores is told the same as anybody: another business's store is not found.
    assertRefused(() -> setStores(HELD_TO_A, STORE_A, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");

    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("When tenant-svc cannot say which stores are the business's, nothing is ranged")
  void anUnreadableStoreListRangesNothing() {
    svc.profiles = TenantProfiles.forTest(tenant -> Optional.empty(), Clock.systemUTC());

    assertRefused(() -> setStores(OWNER, STORE_A), 503, "TENANT_STORES_UNAVAILABLE");
    assertThat(writes, is(empty()));

    // Selling everywhere names no store, so there is nothing to ask tenant-svc.
    setStores(OWNER);
    assertThat(writes, contains("set:[]"));
  }

  @Test
  @DisplayName("Another business's product is not found, before its stores are asked about")
  void anotherBusinesssProductIsNotFound() {
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      TenantContext theirs = caller(RIVAL, role, Set.of());
      assertRefused(
          () -> svc.setProductStores(theirs, PRODUCT, List.of(RIVAL_STORE)),
          404,
          "PRODUCT_NOT_FOUND");
      assertRefused(
          () -> svc.setProductStores(theirs, PRODUCT, List.of(STORE_A)), 404, "PRODUCT_NOT_FOUND");
    }
    assertThat("tenant-svc was not asked", storeReads, is(0));
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("The business's owner ranges any of its stores, or every store")
  void anOwnerRangesAnyOfTheBusinesssStores() {
    ranged.put(PRODUCT, List.of(STORE_B));

    setStores(OWNER, STORE_A, STORE_C);
    setStores(OWNER);

    assertThat(writes, contains("set:[" + STORE_A + ", " + STORE_C + "]", "set:[]"));
  }

  @Test
  @DisplayName("A manager held to a store ranges a product into theirs, never into another")
  void aHeldManagerAddsOnlyTheirOwnStore() {
    ranged.put(PRODUCT, List.of(STORE_B));

    // Adding a store of the business they do not keep.
    assertRefused(() -> setStores(HELD_TO_A, STORE_B, STORE_C), 403, "STORE_ACCESS_DENIED");
    assertThat(writes, is(empty()));

    // Their own, with store B, which is not theirs, left as it was: no write at B.
    setStores(HELD_TO_A, STORE_B, STORE_A);
    assertThat(writes, contains("set:[" + STORE_B + ", " + STORE_A + "]"));
  }

  @Test
  @DisplayName("A manager held to a store cannot take a product out of a store that is not theirs")
  void aHeldManagerRemovesOnlyFromTheirOwnStore() {
    ranged.put(PRODUCT, List.of(STORE_A, STORE_B));

    // Dropping B, which they do not keep, by leaving it out of the list.
    assertRefused(() -> setStores(HELD_TO_A, STORE_A), 403, "STORE_ACCESS_DENIED");
    assertThat(writes, is(empty()));

    // Dropping A, which they do keep, is theirs to do.
    setStores(HELD_TO_A, STORE_B);
    assertThat(writes, contains("set:[" + STORE_B + "]"));
  }

  @Test
  @DisplayName("Selling everywhere, or no longer everywhere, is not a held manager's to decide")
  void aHeldManagerDoesNotDecideEverywhere() {
    // Ranged to their store alone: "every store" would put it on every other shelf. Refused as
    // the business's to decide (BUSINESS_WIDE_ONLY), not as a store that is not theirs: the store
    // is theirs, and the words a person is shown must say what would let it through.
    ranged.put(PRODUCT, List.of(STORE_A));
    assertRefused(() -> setStores(HELD_TO_A), 403, "BUSINESS_WIDE_ONLY");

    // Sold everywhere: ranging it to their store would take it off every other shelf.
    ranged.remove(PRODUCT);
    assertRefused(() -> setStores(HELD_TO_A, STORE_A), 403, "BUSINESS_WIDE_ONLY");
    assertThat(writes, is(empty()));

    // Sending back what is there changes nothing at any store, and is not refused.
    setStores(HELD_TO_A);
    assertThat(writes, contains("set:[]"));
  }

  // ── a bulk import's storeIds ─────────────────────────────────────────────────

  private static ImportProductRequest row(String name, String sku, String... stores) {
    return new ImportProductRequest(
        name,
        null,
        null,
        null,
        false,
        true,
        stores.length == 0 ? null : List.of(stores),
        List.of(new ImportVariantRequest(sku, null, null, "EA", null)));
  }

  private static BulkImportRequest sheet(ImportProductRequest... rows) {
    return new BulkImportRequest(null, List.of(rows), null);
  }

  @Test
  @DisplayName("An import naming a store that is not the business's imports nothing")
  void anImportNamingAStoreThatIsNotTheBusinesssImportsNothing() {
    BulkImportRequest rival =
        sheet(row("Tea", "TEA-1"), row("Coffee", "COF-1", RIVAL_STORE.toString()));
    BulkImportRequest neverMade = sheet(row("Tea", "TEA-1", Ids.newId().toString()));

    assertRefused(() -> svc.bulkImport(OWNER, rival), 404, "PRODUCT_STORE_NOT_FOUND");
    assertRefused(() -> svc.bulkImport(OWNER, neverMade), 404, "PRODUCT_STORE_NOT_FOUND");
    // Not even the row before it: the stores are checked before the first row is written.
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("A held manager's import names only their stores, or imports nothing")
  void aHeldManagersImportNamesOnlyTheirStores() {
    BulkImportRequest other =
        sheet(row("Tea", "TEA-1"), row("Coffee", "COF-1", STORE_B.toString()));

    assertRefused(() -> svc.bulkImport(HELD_TO_A, other), 403, "STORE_ACCESS_DENIED");
    assertThat(writes, is(empty()));

    BulkImportResult own =
        svc.bulkImport(HELD_TO_A, sheet(row("Tea", "TEA-1", STORE_A.toString())));
    assertThat(own.productsCreated(), is(1));
    assertThat(writes, hasItem("stores:[" + STORE_A + "]"));
  }

  @Test
  @DisplayName("An import naming stores is refused whole when tenant-svc cannot say whose they are")
  void anImportNamingStoresIsRefusedWhenTheyCannotBeChecked() {
    svc.profiles = TenantProfiles.forTest(tenant -> Optional.empty(), Clock.systemUTC());

    assertRefused(
        () -> svc.bulkImport(OWNER, sheet(row("Tea", "TEA-1"), row("Cof", "C-1", STORE_A + ""))),
        503,
        "TENANT_STORES_UNAVAILABLE");
    assertThat(writes, is(empty()));

    // A sheet that names no store does not need tenant-svc, and goes through without it.
    BulkImportResult plain = svc.bulkImport(OWNER, sheet(row("Tea", "TEA-1")));
    assertThat(plain.productsCreated(), is(1));
  }

  @Test
  @DisplayName("A store that is not an id stays that row's error, and is not asked about")
  void aStoreThatIsNotAnIdStaysTheRowsError() {
    BulkImportResult result =
        svc.bulkImport(OWNER, sheet(row("Bad", "BAD-1", "not-an-id"), row("Tea", "TEA-1")));

    assertThat(result.productsCreated(), is(1));
    assertThat(result.errors().size(), is(1));
    assertThat(result.errors().get(0).reason(), is("storeIds must be a UUIDv7"));
    assertThat("nothing to check, so tenant-svc was not asked", storeReads, is(0));
  }

  @Test
  @DisplayName("Another business naming our store in its import is told it is not found")
  void anotherBusinessNamingOurStoreIsNotFound() {
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      TenantContext theirs = caller(RIVAL, role, Set.of());
      assertRefused(
          () -> svc.bulkImport(theirs, sheet(row("Tea", "TEA-1", STORE_A.toString()))),
          404,
          "PRODUCT_STORE_NOT_FOUND");
    }
    TenantContext theirHeld = caller(RIVAL, "MANAGER", Set.of(RIVAL_STORE));
    assertRefused(
        () -> svc.bulkImport(theirHeld, sheet(row("Tea", "TEA-1", STORE_A.toString()))),
        404,
        "PRODUCT_STORE_NOT_FOUND");
    assertThat(writes, is(empty()));
  }

  // ── the range is judged inside the transaction that changes it (2 Oct 2026) ────

  @Test
  @DisplayName("The range is judged on what the transaction reads, not on a read made before it")
  void theRangeIsJudgedOnWhatTheTransactionReads() {
    // Read before the transaction: ranged to B. By the time it runs, another change has made it
    // every store. Adding A to "B" would be theirs to do; narrowing "every store" to B and A is
    // not.
    staleRead = List.of(STORE_B);
    assertRefused(() -> setStores(HELD_TO_A, STORE_B, STORE_A), 403, "BUSINESS_WIDE_ONLY");
    assertThat(writes, is(empty()));

    // The other way about: the early read says every store, the transaction finds B. Adding their
    // own store beside it is theirs to do, and is not refused for a range that is gone.
    staleRead = List.of();
    ranged.put(PRODUCT, List.of(STORE_B));
    setStores(HELD_TO_A, STORE_B, STORE_A);
    assertThat(writes, contains("set:[" + STORE_B + ", " + STORE_A + "]"));
  }

  // ── a new product of a manager held to stores (2 Oct 2026) ─────────────────────

  private Product create(TenantContext ctx, UUID... stores) {
    return svc.createProduct(
        ctx,
        new CreateProductRequest("Tea", null, null, null, false, true, null, null, null, null),
        List.of(stores));
  }

  @Test
  @DisplayName("A held manager's new product is sold at stores of theirs, never at every store")
  void aHeldManagersNewProductIsSoldAtTheirStores() {
    // A store of the business they do not keep, alone or beside their own.
    assertRefused(() -> create(HELD_TO_A, STORE_B), 403, "STORE_ACCESS_DENIED");
    assertRefused(() -> create(HELD_TO_A, STORE_A, STORE_B), 403, "STORE_ACCESS_DENIED");
    // Another business's store is not found, held or not.
    assertRefused(() -> create(HELD_TO_A, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");
    assertThat(writes, is(empty()));

    // Their own store, named twice, kept once: the product is sold there and nowhere else.
    create(HELD_TO_A, STORE_A, STORE_A);
    assertThat(writes, contains("product:Tea", "stores:[" + STORE_A + "]"));

    // None named — the admin app's product form names none: sold at the stores they keep, all of
    // them, and not at every store. Nothing is asked of tenant-svc for stores the caller is
    // already held to by their sign-in.
    writes.clear();
    int reads = storeReads;
    Product made = create(HELD_TO_A);
    assertThat(made.name(), is("Tea"));
    assertThat(writes, contains("product:Tea", "stores:[" + STORE_A + "]"));
    assertThat(storeReads, is(reads));

    // Held to two: sold at both, in a stable order.
    writes.clear();
    TenantContext heldToTwo = caller(TENANT, "MANAGER", Set.of(STORE_C, STORE_B));
    create(heldToTwo);
    List<UUID> both = new ArrayList<>(List.of(STORE_B, STORE_C));
    both.sort(null);
    assertThat(writes, contains("product:Tea", "stores:" + both));
  }

  @Test
  @DisplayName("A wrong request is told what is wrong (400) before its stores are asked about")
  void aWrongRequestIsJudgedBeforeItsStores() {
    record Case(String brandId, String categoryId, String status, String launchOn, String code) {}
    // A well-formed UUID of another version (4), which names nothing StoreQL made.
    String v4 = "123e4567-e89b-42d3-a456-426614174000";
    int reads = storeReads;
    for (Case c :
        List.of(
            new Case("not-an-id", null, null, null, "INVALID_UUID"),
            new Case(null, v4, null, null, "INVALID_UUID"),
            new Case(null, null, "ON_SALE", null, "PRODUCT_STATUS_INVALID"),
            new Case(null, null, null, "2026-11-01", "PRODUCT_LAUNCH_ON_NEEDS_NEW_LINE"),
            new Case(null, null, "NEW_LINE", "next week", "PRODUCT_LAUNCH_ON_INVALID"))) {
      CreateProductRequest req =
          new CreateProductRequest(
              "Tea",
              null,
              c.brandId(),
              c.categoryId(),
              false,
              true,
              null,
              c.status(),
              c.launchOn(),
              null);
      // Naming a store the caller does not keep (403), another business's (404), or one of each:
      // the request's own fault is what they are told.
      for (List<UUID> stores :
          List.of(List.of(STORE_B), List.of(RIVAL_STORE), List.of(STORE_B, RIVAL_STORE))) {
        assertRefused(() -> svc.createProduct(HELD_TO_A, req, stores), 400, c.code());
      }
    }
    assertThat("tenant-svc was never asked about the stores", storeReads, is(reads));
    assertThat("nothing was written", writes, is(empty()));

    // A request that is right is then judged on its stores: the business's first, then theirs.
    assertRefused(() -> create(HELD_TO_A, STORE_B, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");
    assertRefused(() -> create(HELD_TO_A, STORE_B), 403, "STORE_ACCESS_DENIED");
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("An owner or a manager of the whole business may create a product sold everywhere")
  void aCallerHeldToNoStoreMayCreateOneSoldEverywhere() {
    TenantContext wholeBusiness = caller(TENANT, "MANAGER", Set.of());

    create(OWNER);
    create(wholeBusiness);
    create(OWNER, STORE_B, STORE_C);

    assertThat(
        writes,
        contains(
            "product:Tea",
            "product:Tea",
            "product:Tea",
            "stores:[" + STORE_B + ", " + STORE_C + "]"));
    // Still only the business's own stores.
    assertRefused(() -> create(OWNER, RIVAL_STORE), 404, "PRODUCT_STORE_NOT_FOUND");
  }

  @Test
  @DisplayName("Another business naming our store in a new product is told it is not found")
  void anotherBusinessNamingOurStoreInANewProductIsNotFound() {
    for (TenantContext theirs :
        List.of(
            caller(RIVAL, "OWNER", Set.of()),
            caller(RIVAL, "MANAGER", Set.of()),
            // Their held manager, naming our store as though it were theirs.
            caller(RIVAL, "MANAGER", Set.of(STORE_A)),
            caller(RIVAL, "MANAGER", Set.of(RIVAL_STORE)))) {
      assertRefused(() -> create(theirs, STORE_A), 404, "PRODUCT_STORE_NOT_FOUND");
    }
    assertThat(writes, is(empty()));
  }

  // ── a held manager's import, row by row (2 Oct 2026) ───────────────────────────

  private static BulkImportRequest sheet(String mode, ImportProductRequest... rows) {
    return new BulkImportRequest(null, List.of(rows), mode);
  }

  private static final String ROW_REFUSED_PREFIX = "BUSINESS_WIDE_ONLY: ";

  @Test
  @DisplayName("A held manager's imported new product naming no store is sold at their stores")
  void aHeldManagersImportedNewProductIsSoldAtTheirStores() {
    for (String mode : new String[] {"ADD", "REPLACE"}) {
      writes.clear();
      BulkImportResult result =
          svc.bulkImport(
              HELD_TO_A,
              sheet(mode, row("Coffee", "COF-1"), row("Cocoa", "COC-1", STORE_A.toString())));

      assertThat(mode, result.productsCreated(), is(2));
      assertThat(result.errors(), is(empty()));
      // The row naming no store is ranged to the store they keep, as the one that names it is.
      assertThat(
          writes,
          contains(
              "product:Coffee",
              "stores:[" + STORE_A + "]",
              "variant:COF-1",
              "product:Cocoa",
              "stores:[" + STORE_A + "]",
              "variant:COC-1"));
    }

    // An owner's and a manager of the whole business's rows name none and are sold everywhere.
    writes.clear();
    svc.bulkImport(OWNER, sheet("ADD", row("Coffee", "COF-1")));
    svc.bulkImport(caller(TENANT, "MANAGER", Set.of()), sheet("ADD", row("Cocoa", "COC-1")));
    assertThat(
        writes, contains("product:Coffee", "variant:COF-1", "product:Cocoa", "variant:COC-1"));
  }

  @Test
  @DisplayName("REPLACE never narrows a product sold everywhere to a held manager's store")
  void replaceNeverNarrowsEverywhereToAHeldManagersStore() {
    teaHeld = true; // and sold everywhere: no entry in ranged

    BulkImportResult result =
        svc.bulkImport(HELD_TO_A, sheet("REPLACE", row("Tea", "TEA-2", STORE_A.toString())));

    assertThat(result.errors().size(), is(1));
    assertThat(result.errors().get(0).item(), is("product:Tea"));
    assertThat(result.errors().get(0).reason().startsWith(ROW_REFUSED_PREFIX), is(true));
    assertThat("no store and no variant of the row was written", writes, is(empty()));
  }

  @Test
  @DisplayName("REPLACE writes to a line only when it is ranged solely to a held manager's stores")
  void replaceWritesOnlyToALineWhollyTheirs() {
    teaHeld = true;

    // 3 Oct 2026: a row naming an existing line adds a variant to it, so the line must be theirs
    // alone. Sold at another store (here B), or at every store, it is the row's error and nothing
    // is written, whether the row names a store or not.
    ranged.put(PRODUCT, List.of(STORE_B));
    BulkImportResult beyond =
        svc.bulkImport(HELD_TO_A, sheet("REPLACE", row("Tea", "TEA-2", STORE_A.toString())));
    assertThat(beyond.errors().size(), is(1));
    assertThat(beyond.errors().get(0).reason().startsWith("BUSINESS_WIDE_ONLY"), is(true));
    ranged.remove(PRODUCT);
    BulkImportResult everywhere = svc.bulkImport(HELD_TO_A, sheet("REPLACE", row("Tea", "TEA-3")));
    assertThat(everywhere.errors().size(), is(1));
    assertThat(everywhere.errors().get(0).reason().startsWith("BUSINESS_WIDE_ONLY"), is(true));
    assertThat("nothing was written", writes, is(empty()));

    // A line ranged to their store alone is theirs: its variant is replaced, and a store they
    // hold may be named.
    ranged.put(PRODUCT, List.of(STORE_A));
    BulkImportResult details = svc.bulkImport(HELD_TO_A, sheet("REPLACE", row("Tea", "TEA-4")));
    assertThat(details.errors(), is(empty()));
    assertThat(writes, contains("variant:TEA-4"));
  }

  @Test
  @DisplayName("The owner's REPLACE may range a product sold everywhere to the stores it names")
  void theOwnersReplaceMayRangeAProductSoldEverywhere() {
    teaHeld = true;

    BulkImportResult result =
        svc.bulkImport(OWNER, sheet("REPLACE", row("Tea", "TEA-2", STORE_B.toString())));

    assertThat(result.errors(), is(empty()));
    assertThat(writes, contains("stores:[" + STORE_B + "]", "variant:TEA-2"));
  }

  // ── one store check, each door's refusal (2 Oct 2026) ─────────────────────────

  @Test
  @DisplayName("One check of the business's stores, answering with the refusal each door names")
  void oneStoreCheckAnswersWithEachDoorsRefusal() {
    assertRefused(
        () ->
            CatalogueStores.requireOwn(
                svc.profiles, TENANT, List.of(STORE_A, RIVAL_STORE), CatalogueStores.NOT_FOUND),
        404,
        "PRODUCT_STORE_NOT_FOUND");
    assertRefused(
        () ->
            CatalogueStores.requireOwn(
                svc.profiles, TENANT, List.of(RIVAL_STORE), CatalogueStores.ASSORTMENT_NOT_FOUND),
        404,
        "ASSORTMENT_STORE_NOT_FOUND");
    // A store named twice is asked about once; the business's own stores pass.
    int before = storeReads;
    CatalogueStores.requireOwn(
        svc.profiles, TENANT, List.of(STORE_A, STORE_A, STORE_B), CatalogueStores.NOT_FOUND);
    assertThat(storeReads - before, is(2));
  }
}
