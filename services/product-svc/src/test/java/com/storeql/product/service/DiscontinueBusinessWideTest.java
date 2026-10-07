package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Product;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Discontinuing a line is the whole business's to decide (2 Oct 2026): the line stops being
 * reordered at every store, so a manager held to stores is refused {@code 403 BUSINESS_WIDE_ONLY}
 * before the line is read and nothing changes. It had no check of its own, and a manager of one
 * branch could take a line out of replenishment at every branch.
 *
 * <p>The repository here notes every read and write, which is the claim: for a refusal, none.
 */
class DiscontinueBusinessWideTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID OTHER_STORE = Ids.newId();

  /** Another business, which must move nothing of ours. */
  private static final UUID RIVAL = Ids.newId();

  private static final UUID RIVAL_STORE = Ids.newId();

  private final List<String> reads = new ArrayList<>();
  private final List<String> writes = new ArrayList<>();
  private final List<OutboxRow> events = new ArrayList<>();

  /** Each business's lines, by id. */
  private final Map<UUID, Product> lines = new HashMap<>();

  private final class Products extends ProductRepository {
    @Override
    public Optional<Product> findProduct(UUID tenantId, UUID id) {
      reads.add("product");
      Product p = lines.get(id);
      return p != null && p.tenantId().equals(tenantId) ? Optional.of(p) : Optional.empty();
    }

    @Override
    public List<UUID> variantIdsOf(UUID tenantId, UUID productId) {
      reads.add("variants");
      return List.of();
    }

    @Override
    public Product updateProductWithOutbox(Product p, OutboxRow event) {
      writes.add(p.status());
      events.add(event);
      lines.put(p.id(), p);
      return p;
    }
  }

  private final ProductService svc = new ProductService();

  DiscontinueBusinessWideTest() {
    svc.repo = new Products();
  }

  private UUID line(UUID tenantId) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    lines.put(
        id,
        new Product(
            id,
            tenantId,
            "Old cola",
            null,
            null,
            null,
            Product.STATUS_ACTIVE,
            true,
            true,
            now,
            now,
            null,
            null));
    return id;
  }

  private ApiException refused(TenantContext ctx, UUID productId) {
    return assertThrows(ApiException.class, () -> svc.discontinueProduct(ctx, productId));
  }

  @Test
  @DisplayName("A manager held to stores is refused before the line is read, and nothing changes")
  void aManagerHeldToStoresIsRefusedBeforeTheLineIsRead() {
    UUID ours = line(TENANT);
    for (Set<UUID> heldTo : List.of(Set.of(STORE), Set.of(STORE, OTHER_STORE))) {
      ApiException e = refused(CatalogueStoresTest.caller(TENANT, "MANAGER", heldTo), ours);
      assertThat(e.status(), is(403));
      assertThat(e.code(), is("BUSINESS_WIDE_ONLY"));
      // Who can is named, in the words every held-manager refusal of the catalogue uses.
      assertThat(e.getMessage(), containsString("an owner or a manager of the whole business"));
    }
    // A line that does not exist gets the same answer: who may is asked before what it names.
    ApiException never =
        refused(CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of(STORE)), Ids.newId());
    assertThat(never.code(), is("BUSINESS_WIDE_ONLY"));

    assertThat("the line was never read", reads, is(empty()));
    assertThat("nothing was written", writes, is(empty()));
    assertThat(lines.get(ours).status(), is(Product.STATUS_ACTIVE));
  }

  @Test
  @DisplayName("An owner and a manager of the whole business discontinue it, with its event")
  void theWholeBusinessDiscontinuesIt() {
    for (TenantContext whole :
        List.of(
            CatalogueStoresTest.caller(TENANT, "OWNER", Set.of()),
            CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of()))) {
      UUID ours = line(TENANT);
      Product moved = svc.discontinueProduct(whole, ours);
      assertThat(moved.status(), is(Product.STATUS_DISCONTINUED));
      assertThat(lines.get(ours).discontinuedAt() != null, is(true));
    }
    assertThat(writes, is(List.of(Product.STATUS_DISCONTINUED, Product.STATUS_DISCONTINUED)));
    assertThat(
        events.stream().map(OutboxRow::eventType).toList(),
        is(List.of("ProductDiscontinued", "ProductDiscontinued")));
  }

  @Test
  @DisplayName("Another business's staff, held or not, naming our line move nothing of ours")
  void anotherBusinessMovesNothingOfOurs() {
    UUID ours = line(TENANT);

    // Held to stores (theirs, or naming ours): refused as held, before anything is read.
    for (Set<UUID> heldTo : List.of(Set.of(RIVAL_STORE), Set.of(STORE))) {
      ApiException e = refused(CatalogueStoresTest.caller(RIVAL, "MANAGER", heldTo), ours);
      assertThat(e.status(), is(403));
      assertThat(e.code(), is("BUSINESS_WIDE_ONLY"));
    }
    assertThat(reads, is(empty()));

    // Held to none: our line is not theirs to find.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      ApiException e = refused(CatalogueStoresTest.caller(RIVAL, role, Set.of()), ours);
      assertThat(e.status(), is(404));
      assertThat(e.code(), is("PRODUCT_NOT_FOUND"));
    }

    assertThat("nothing was written", writes, is(empty()));
    assertThat(lines.get(ours).status(), is(Product.STATUS_ACTIVE));
  }
}
