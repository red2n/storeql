package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
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
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Delisting a line and reinstating one are the whole business's to decide, as discontinuing is (2
 * Oct 2026). A delist hides the line from the shop and the till at every store and takes it out of
 * the low-stock report and the planning run everywhere; a reinstate undoes a discontinue, which is
 * itself the whole business's. Both took a tenant id alone, so a manager held to one branch who was
 * refused the discontinue could delist the line at every branch, or bring back a line an owner had
 * run down.
 *
 * <p>A manager held to stores is refused {@code 403 BUSINESS_WIDE_ONLY} before the line is read,
 * whatever the line's range: a line they made themselves, ranged to their stores alone, is refused
 * them too, so its range is never asked. The repository here notes every read and write, which is
 * the claim: for a refusal, none.
 */
class DelistReinstateBusinessWideTest {

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
    public List<UUID> storesForProduct(UUID tenantId, UUID productId) {
      reads.add("stores");
      return List.of(STORE);
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

  DelistReinstateBusinessWideTest() {
    svc.repo = new Products();
  }

  /** The two moves, each as the resource calls it. */
  private final Map<String, BiFunction<TenantContext, UUID, Product>> moves =
      Map.of("delist", svc::delistProduct, "reinstate", svc::reinstateProduct);

  private UUID line(UUID tenantId, String status) {
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
            status,
            true,
            true,
            now,
            now,
            null,
            Product.STATUS_DISCONTINUED.equals(status) ? now : null));
    return id;
  }

  private static ApiException refused(Runnable call) {
    return assertThrows(ApiException.class, call::run);
  }

  @Test
  @DisplayName(
      "A manager held to stores is refused a delist or a reinstate before the line is read, even"
          + " their own local line, and nothing changes")
  void aManagerHeldToStoresIsRefusedBeforeTheLineIsRead() {
    // An active line (a delist would take it everywhere) and a discontinued one (a reinstate would
    // undo the whole business's run-down). The active one stands for a line the manager made
    // themselves and ranged to their store alone: the range is not what decides.
    UUID active = line(TENANT, Product.STATUS_ACTIVE);
    UUID discontinued = line(TENANT, Product.STATUS_DISCONTINUED);
    Map<String, UUID> target = Map.of("delist", active, "reinstate", discontinued);

    for (var move : moves.entrySet()) {
      for (Set<UUID> heldTo : List.of(Set.of(STORE), Set.of(STORE, OTHER_STORE))) {
        TenantContext held = CatalogueStoresTest.caller(TENANT, "MANAGER", heldTo);
        ApiException e = refused(() -> move.getValue().apply(held, target.get(move.getKey())));
        assertThat(move.getKey(), e.status(), is(403));
        assertThat(move.getKey(), e.code(), is("BUSINESS_WIDE_ONLY"));
        // Who can is named, in the words every held-manager refusal of the catalogue uses.
        assertThat(
            move.getKey(),
            e.getMessage(),
            containsString("an owner or a manager of the whole business"));
      }
      // A line that does not exist gets the same answer: who may is asked before what it names.
      TenantContext held = CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of(STORE));
      ApiException never = refused(() -> move.getValue().apply(held, Ids.newId()));
      assertThat(move.getKey(), never.code(), is("BUSINESS_WIDE_ONLY"));
    }

    assertThat("the line, its variants and its range were never read", reads, is(empty()));
    assertThat("nothing was written", writes, is(empty()));
    assertThat("nothing was announced", events, is(empty()));
    assertThat(lines.get(active).status(), is(Product.STATUS_ACTIVE));
    assertThat(lines.get(discontinued).status(), is(Product.STATUS_DISCONTINUED));
  }

  @Test
  @DisplayName("An owner and a manager of the whole business delist and reinstate, with the event")
  void theWholeBusinessDelistsAndReinstates() {
    for (TenantContext whole :
        List.of(
            CatalogueStoresTest.caller(TENANT, "OWNER", Set.of()),
            CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of()))) {
      UUID discontinued = line(TENANT, Product.STATUS_DISCONTINUED);
      Product back = svc.reinstateProduct(whole, discontinued);
      assertThat(back.status(), is(Product.STATUS_ACTIVE));
      assertThat("the run-down is spent", back.discontinuedAt() == null, is(true));

      Product gone = svc.delistProduct(whole, discontinued);
      assertThat(gone.status(), is(Product.STATUS_DELISTED));
      assertThat(lines.get(discontinued).updatedAt(), is(notNullValue()));
    }
    assertThat(
        writes,
        is(
            List.of(
                Product.STATUS_ACTIVE,
                Product.STATUS_DELISTED,
                Product.STATUS_ACTIVE,
                Product.STATUS_DELISTED)));
    assertThat(
        events.stream().map(OutboxRow::eventType).toList(),
        is(
            List.of(
                "ProductReinstated", "ProductDelisted", "ProductReinstated", "ProductDelisted")));
    assertThat(
        "the range is not what decides, so it is never read", reads.contains("stores"), is(false));
  }

  @Test
  @DisplayName("The lifecycle still refuses what a line cannot do from where it is")
  void theLifecycleStillHolds() {
    TenantContext owner = CatalogueStoresTest.caller(TENANT, "OWNER", Set.of());
    UUID active = line(TENANT, Product.STATUS_ACTIVE);
    ApiException e = refused(() -> svc.reinstateProduct(owner, active));
    assertThat(e.status(), is(409));
    assertThat(e.code(), is("PRODUCT_LIFECYCLE_INVALID"));
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("Another business's staff, held or not, naming our line move nothing of ours")
  void anotherBusinessMovesNothingOfOurs() {
    UUID active = line(TENANT, Product.STATUS_ACTIVE);
    UUID discontinued = line(TENANT, Product.STATUS_DISCONTINUED);
    Map<String, UUID> target = Map.of("delist", active, "reinstate", discontinued);

    for (var move : moves.entrySet()) {
      // Held to stores (theirs, or naming ours): refused as held, before anything is read.
      for (Set<UUID> heldTo : List.of(Set.of(RIVAL_STORE), Set.of(STORE))) {
        TenantContext rival = CatalogueStoresTest.caller(RIVAL, "MANAGER", heldTo);
        ApiException e = refused(() -> move.getValue().apply(rival, target.get(move.getKey())));
        assertThat(move.getKey(), e.status(), is(403));
        assertThat(move.getKey(), e.code(), is("BUSINESS_WIDE_ONLY"));
      }
    }
    assertThat(reads, is(empty()));

    for (var move : moves.entrySet()) {
      // Held to none: our line is not theirs to find.
      for (String role : new String[] {"OWNER", "MANAGER"}) {
        TenantContext rival = CatalogueStoresTest.caller(RIVAL, role, Set.of());
        ApiException e = refused(() -> move.getValue().apply(rival, target.get(move.getKey())));
        assertThat(move.getKey() + " " + role, e.status(), is(404));
        assertThat(move.getKey() + " " + role, e.code(), is("PRODUCT_NOT_FOUND"));
      }
    }

    assertThat("nothing was written", writes, is(empty()));
    assertThat(lines.get(active).status(), is(Product.STATUS_ACTIVE));
    assertThat(lines.get(discontinued).status(), is(Product.STATUS_DISCONTINUED));
  }
}
