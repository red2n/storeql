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
import com.storeql.product.dto.Dtos.ImportCategoryRequest;
import com.storeql.product.dto.Dtos.ImportProductRequest;
import com.storeql.product.dto.Dtos.ImportVariantRequest;
import com.storeql.product.repo.BrandRepository;
import com.storeql.product.repo.CategoryRepository;
import com.storeql.product.repo.ProductRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A hole in an import's list of rows ({@code "products":[{…}, null]}) is refused whole, with the
 * place named, before a row is written.
 *
 * <p>The endpoint checks its rows one by one so that a row that breaks a rule is that row's error,
 * and so it never ran the platform's refusal of a list with holes in it. A null row reached the row
 * loop, whose error handler read the row's name and failed itself: a {@code 500}, after the
 * categories and the rows before it had been committed, which a retry then met as duplicates. Here
 * the repositories note every write, which is the claim: for a hole, none.
 */
class BulkImportHolesTest {

  private static final UUID TENANT = Ids.newId();

  /** The business's owner, held to no store: where a row's product is sold is not in question. */
  private static final TenantContext OWNER = CatalogueStoresTest.caller(TENANT, "OWNER", Set.of());

  private final List<String> writes = new ArrayList<>();
  private final List<String> announced = new ArrayList<>();

  private final class Categories extends CategoryRepository {
    @Override
    public Optional<Category> findCategoryByName(UUID tenantId, String name) {
      return Optional.empty();
    }

    @Override
    public Optional<Category> findCategory(UUID tenantId, UUID id) {
      return Optional.empty(); // the walk up the tree ends at the category itself
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

  private final class Products extends ProductRepository {
    @Override
    public Product createProductWithOutbox(
        Product p, List<OutboxRow> events, ProductSafety safety, List<UUID> storeIds) {
      writes.add("product:" + p.name());
      for (OutboxRow e : events) announced.add(e.eventType() + ":" + p.name());
      return p;
    }

    @Override
    public Variant createVariantWithOutbox(Variant v, OutboxRow event) {
      writes.add("variant:" + v.sku());
      return v;
    }

    @Override
    public void addStoreAssignments(
        UUID tenantId, UUID productId, List<UUID> storeIds, Consumer<List<UUID>> guard) {
      writes.add("stores");
    }

    @Override
    public void deleteVariantBySku(UUID tenantId, String sku) {
      writes.add("delete:" + sku);
    }
  }

  private final ProductService svc = new ProductService();

  BulkImportHolesTest() {
    svc.categoryRepo = new Categories();
    svc.brandRepo = new Brands();
    svc.repo = new Products();
  }

  /** A row that imports cleanly, offered at the till only, so no listing rule is asked. */
  private static ImportProductRequest product(String name, String sku) {
    return new ImportProductRequest(
        name,
        null,
        "Drinks",
        "Acme",
        false,
        true,
        null,
        List.of(new ImportVariantRequest(sku, null, null, "EA", null)));
  }

  private static List<ImportCategoryRequest> categories(ImportCategoryRequest... rows) {
    return Arrays.asList(rows);
  }

  private static List<ImportProductRequest> products(ImportProductRequest... rows) {
    return Arrays.asList(rows);
  }

  private ApiException refused(BulkImportRequest req) {
    return assertThrows(ApiException.class, () -> svc.bulkImport(OWNER, req));
  }

  private static void assertNamed(ApiException e, String detail) {
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("VALIDATION_FAILED"));
    assertThat(e.details(), hasItem(detail));
  }

  @Test
  @DisplayName("A null product row is refused whole, naming it, and nothing is written")
  void aNullProductRowIsRefusedBeforeAnythingIsWritten() {
    ApiException e =
        refused(
            new BulkImportRequest(
                categories(new ImportCategoryRequest("Drinks", null)),
                products(product("Green tea", "TEA-1"), null),
                null));

    assertNamed(e, "products[1]: must not be null");
    assertThat("not the category, nor the row before the hole", writes, is(empty()));
  }

  @Test
  @DisplayName("A null category row is refused whole, naming it, and nothing is written")
  void aNullCategoryRowIsRefusedBeforeAnythingIsWritten() {
    ApiException e =
        refused(
            new BulkImportRequest(
                categories(new ImportCategoryRequest("Drinks", null), null),
                products(product("Green tea", "TEA-1")),
                "REPLACE"));

    assertNamed(e, "categories[1]: must not be null");
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("A hole inside a row is the platform's refusal too: every hole is named at once")
  void everyHoleIsNamedAtOnce() {
    ImportProductRequest holeInVariants =
        new ImportProductRequest(
            "Coffee",
            null,
            null,
            null,
            false,
            true,
            null,
            Arrays.asList(new ImportVariantRequest("COF-1", null, null, "EA", null), null));

    ApiException e =
        refused(
            new BulkImportRequest(
                categories((ImportCategoryRequest) null), products(null, holeInVariants), null));

    assertThat(e.code(), is("VALIDATION_FAILED"));
    assertThat(
        e.details(),
        contains(
            "categories[0]: must not be null",
            "products[0]: must not be null",
            "products[1].variants[1]: must not be null"));
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("A row that breaks a rule is still that row's error, and the rest is imported")
  void aRowThatBreaksARuleIsStillThatRowsError() {
    // What the up-front check must not do: turn a blank name into a 400 for the whole sheet.
    BulkImportResult result =
        svc.bulkImport(
            OWNER,
            new BulkImportRequest(
                categories(
                    new ImportCategoryRequest("", null), new ImportCategoryRequest("Drinks", null)),
                products(product("", "BAD-1"), product("Green tea", "TEA-1")),
                null));

    assertThat(result.categoriesCreated(), is(1));
    assertThat(result.productsCreated(), is(1));
    assertThat(result.variantsCreated(), is(1));
    assertThat(result.errors().size(), is(2));
    assertThat(
        writes, contains("category:Drinks", "brand:Acme", "product:Green tea", "variant:TEA-1"));
  }

  @Test
  @DisplayName("An imported product with a category announces ProductCategorised; one without, not")
  void anImportedProductAnnouncesItsCategory() {
    ImportProductRequest bare =
        new ImportProductRequest(
            "Loose item",
            null,
            null,
            null,
            false,
            true,
            null,
            List.of(new ImportVariantRequest("LOOSE-1", null, null, "EA", null)));

    svc.bulkImport(
        OWNER,
        new BulkImportRequest(
            categories(new ImportCategoryRequest("Drinks", null)),
            products(product("Green tea", "TEA-1"), bare),
            null));

    assertThat(
        announced,
        contains(
            "ProductCreated:Green tea",
            "ProductCategorised:Green tea",
            "ProductCreated:Loose item"));
  }
}
