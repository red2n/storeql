package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Domain.Product;
import com.storeql.product.repo.ProductRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
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
 * Launching a line is the whole business's to decide, as the other three moves of its lifecycle are
 * (3 Oct 2026): the status is the line's, at every store it is sold at, and a manager held to one
 * branch could put an owner's new line on sale everywhere before its day. They are refused {@code
 * 403 BUSINESS_WIDE_ONLY} before the line is read, whatever its range, a new line of their own
 * included. The repository here notes every read and write: for a refusal, none.
 */
class LaunchBusinessWideTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID OTHER_STORE = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID RIVAL_STORE = Ids.newId();

  private final List<String> reads = new ArrayList<>();
  private final List<String> writes = new ArrayList<>();
  private final List<OutboxRow> events = new ArrayList<>();
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

  LaunchBusinessWideTest() {
    svc.repo = new Products();
  }

  private UUID line(UUID tenantId, String status) {
    UUID id = Ids.newId();
    Instant now = Instant.now();
    lines.put(
        id,
        new Product(
            id,
            tenantId,
            "Spring cola",
            null,
            null,
            null,
            status,
            true,
            true,
            now,
            now,
            Product.STATUS_NEW_LINE.equals(status) ? LocalDate.now().plusDays(30) : null,
            null));
    return id;
  }

  private static ApiException refused(Runnable call) {
    return assertThrows(ApiException.class, call::run);
  }

  @Test
  @DisplayName(
      "A manager held to stores is refused a launch before the line is read, even a new line of"
          + " their own, and nothing changes")
  void aManagerHeldToStoresIsRefusedBeforeTheLineIsRead() {
    UUID waiting = line(TENANT, Product.STATUS_NEW_LINE);

    for (Set<UUID> heldTo : List.of(Set.of(STORE), Set.of(STORE, OTHER_STORE))) {
      TenantContext held = CatalogueStoresTest.caller(TENANT, "MANAGER", heldTo);
      ApiException e = refused(() -> svc.launchProduct(held, waiting));
      assertThat(e.status(), is(403));
      assertThat(e.code(), is("BUSINESS_WIDE_ONLY"));
      assertThat(e.getMessage(), containsString("an owner or a manager of the whole business"));
    }
    // A line that does not exist gets the same answer: who may is asked before what it names.
    TenantContext held = CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of(STORE));
    assertThat(
        refused(() -> svc.launchProduct(held, Ids.newId())).code(), is("BUSINESS_WIDE_ONLY"));

    assertThat("the line, its variants and its range were never read", reads, is(empty()));
    assertThat("nothing was written", writes, is(empty()));
    assertThat("nothing was announced", events, is(empty()));
    assertThat(lines.get(waiting).status(), is(Product.STATUS_NEW_LINE));
  }

  @Test
  @DisplayName("An owner and a manager of the whole business launch a new line, with the event")
  void theWholeBusinessLaunches() {
    for (TenantContext whole :
        List.of(
            CatalogueStoresTest.caller(TENANT, "OWNER", Set.of()),
            CatalogueStoresTest.caller(TENANT, "MANAGER", Set.of()))) {
      UUID waiting = line(TENANT, Product.STATUS_NEW_LINE);
      Product launched = svc.launchProduct(whole, waiting);
      assertThat(launched.status(), is(Product.STATUS_ACTIVE));
      assertThat("the launch day is spent", launched.launchOn(), is(nullValue()));
    }
    assertThat(writes, contains(Product.STATUS_ACTIVE, Product.STATUS_ACTIVE));
    assertThat(
        events.stream().map(OutboxRow::eventType).toList(),
        contains("ProductLaunched", "ProductLaunched"));
    assertThat(
        "the range is not what decides, so it is never read", reads.contains("stores"), is(false));
  }

  @Test
  @DisplayName("The lifecycle still refuses a launch of a line that is not a new line")
  void theLifecycleStillHolds() {
    TenantContext owner = CatalogueStoresTest.caller(TENANT, "OWNER", Set.of());
    UUID active = line(TENANT, Product.STATUS_ACTIVE);
    ApiException e = refused(() -> svc.launchProduct(owner, active));
    assertThat(e.status(), is(409));
    assertThat(e.code(), is("PRODUCT_LIFECYCLE_INVALID"));
    assertThat(writes, is(empty()));
  }

  @Test
  @DisplayName("Another business's staff, held or not, naming our line launch nothing of ours")
  void anotherBusinessLaunchesNothingOfOurs() {
    UUID waiting = line(TENANT, Product.STATUS_NEW_LINE);

    // Held to stores (theirs, or naming ours): refused as held, before anything is read.
    for (Set<UUID> heldTo : List.of(Set.of(RIVAL_STORE), Set.of(STORE))) {
      TenantContext rival = CatalogueStoresTest.caller(RIVAL, "MANAGER", heldTo);
      ApiException e = refused(() -> svc.launchProduct(rival, waiting));
      assertThat(e.status(), is(403));
      assertThat(e.code(), is("BUSINESS_WIDE_ONLY"));
    }
    assertThat(reads, is(empty()));

    // Held to none: our line is not theirs to find.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      TenantContext rival = CatalogueStoresTest.caller(RIVAL, role, Set.of());
      ApiException e = refused(() -> svc.launchProduct(rival, waiting));
      assertThat(role, e.status(), is(404));
      assertThat(role, e.code(), is("PRODUCT_NOT_FOUND"));
    }

    assertThat("nothing was written", writes, is(empty()));
    assertThat(lines.get(waiting).status(), is(Product.STATUS_NEW_LINE));
  }
}
