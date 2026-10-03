package com.storeql.product.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.client.Caller;
import com.storeql.product.client.InventoryClient;
import com.storeql.product.client.PricingClient;
import com.storeql.product.dto.Dtos.BulkImportRequest;
import com.storeql.product.dto.Dtos.BulkImportResult;
import com.storeql.product.dto.Dtos.ImportedVariant;
import com.storeql.product.dto.Dtos.SupplierCsvImportRequest;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order of a supplier CSV import: everything that can be refused is refused <em>before</em> the
 * catalogue is committed, and what fails after is reported with the result, not thrown.
 *
 * <p>The catalogue goes in first and is not undone, so a refusal that followed it answered an error
 * for an import that had happened, and sending it again met its own duplicates. Here the catalogue
 * step is a stand-in that counts how often it ran, which is the whole claim: for a refusal, never.
 */
class SupplierCsvImportTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  /** A store of the same business the callers below are not all held to. */
  private static final UUID OTHER_STORE = Ids.newId();

  /** Another business, and its one store. */
  private static final UUID RIVAL = Ids.newId();

  private static final UUID RIVAL_STORE = Ids.newId();

  /** tenant-svc's answer: each business's own stores. */
  private static final Map<UUID, Set<UUID>> STORES =
      Map.of(TENANT, Set.of(STORE, OTHER_STORE), RIVAL, Set.of(RIVAL_STORE));

  /** The business's owner, held to no store and narrowed by nothing. */
  private static final TenantContext OWNER = caller(TENANT, "OWNER", Set.of(), null);

  /** Quantities and prices for one SKU: a sheet that asks inventory-svc and pricing-svc both. */
  private static final String SHEET =
      "Product ID,Product Description,Category,Quantity,Price\nTEA-1,Green tea,Drinks,12,3.50\n";

  private static final String SHEET_PRICES_ONLY =
      "Product ID,Product Description,Price\nTEA-1,Green tea,3.50\n";

  private static final String SHEET_QUANTITIES_ONLY =
      "Product ID,Product Description,Quantity\nTEA-1,Green tea,12\n";

  private static final String SHEET_PLAIN = "Product ID,Product Description\nTEA-1,Green tea\n";

  /** A sheet whose Store column names a shop, which the request maps to a store id. */
  private static final String SHEET_STORE_COLUMN =
      "Product ID,Product Description,Store\nTEA-1,Green tea,North\n";

  /**
   * tenant-svc as it answers when it can be read: each business's own stores. The business's
   * profile (its currency) still cannot be read, so a sheet with prices has to name a currency.
   */
  private static final class Tenants extends TenantProfiles {
    int storeReads;

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

  private final Tenants tenants = new Tenants();

  /** The catalogue step: records that it ran, and what it was asked to import. */
  private static final class Importing extends ProductService {

    int imports;
    BulkImportRequest sent;
    UUID importedInto;

    /** When set, every SKU P-0 … P-(n-1) of a priced sheet is answered as imported. */
    int everySku;

    @Override
    BulkImportResult importCatalogue(TenantContext ctx, BulkImportRequest req) {
      imports++;
      sent = req;
      importedInto = ctx.requireTenantId();
      List<ImportedVariant> variants =
          everySku == 0
              ? List.of(
                  new ImportedVariant("TEA-1", Ids.newId().toString(), Ids.newId().toString()))
              : java.util.stream.IntStream.range(0, everySku)
                  .mapToObj(
                      i ->
                          new ImportedVariant(
                              "P-" + i, Ids.newId().toString(), Ids.newId().toString()))
                  .toList();
      return new BulkImportResult(
          1, 0, variants.size(), variants.size(), List.of(), variants, null, null, null, null);
    }
  }

  private static final class Inventory extends InventoryClient {

    RuntimeException unavailable;
    RuntimeException receiveFails;
    int asked;
    UUID receivedAt;
    Caller askedBy;
    List<InventoryClient.ReceiveItem> received = List.of();

    @Override
    public void requireAvailable() {
      asked++;
      if (unavailable != null) throw unavailable;
    }

    @Override
    public BatchResult batchReceive(Caller caller, UUID storeId, List<ReceiveItem> items) {
      if (receiveFails != null) throw receiveFails;
      askedBy = caller;
      receivedAt = storeId;
      received = items;
      return new BatchResult(items.size(), List.of());
    }
  }

  private static final class Pricing extends PricingClient {

    RuntimeException unavailable;
    RuntimeException pricingFails;
    int asked;
    String currency;
    Caller askedBy;
    List<PricingClient.PriceItem> priced = List.of();

    @Override
    public void requireAvailable() {
      asked++;
      if (unavailable != null) throw unavailable;
    }

    @Override
    public BatchResult batchSetPrices(Caller caller, String currency, List<PriceItem> items) {
      if (pricingFails != null) throw pricingFails;
      askedBy = caller;
      priced = items;
      this.currency = currency;
      return new BatchResult(items.size(), List.of());
    }
  }

  private final Importing svc = new Importing();
  private final Inventory inventory = new Inventory();
  private final Pricing pricing = new Pricing();

  SupplierCsvImportTest() {
    svc.inventoryClient = inventory;
    svc.pricingClient = pricing;
    // tenant-svc knows the stores but not the profile: a currency has to be named, or the import
    // is refused for it.
    svc.profiles = tenants;
  }

  private static SupplierCsvImportRequest request(
      String csv, String mode, String storeId, String currency) {
    return new SupplierCsvImportRequest(csv, mode, null, storeId, currency);
  }

  /** A sheet whose Store column says "North", mapped to {@code north}. */
  private static SupplierCsvImportRequest storeColumn(String north) {
    return new SupplierCsvImportRequest(
        SHEET_STORE_COLUMN, null, Map.of("North", north), null, null);
  }

  /**
   * A caller as the gateway describes one: the shared filter that reads the headers is the only
   * writer of a {@code TenantContext}, so a test reaches the same setter it uses.
   *
   * @param permissions the token's permission claim, or null for a token carrying none
   */
  private static TenantContext caller(
      UUID tenant, String role, Set<UUID> heldTo, Set<String> permissions) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, tenant, Ids.newId(), Set.of(role), heldTo, permissions, "req-1");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private ApiException refused(SupplierCsvImportRequest req) {
    return refused(OWNER, req);
  }

  private ApiException refused(TenantContext ctx, SupplierCsvImportRequest req) {
    return assertThrows(ApiException.class, () -> svc.importSupplierCsv(ctx, req));
  }

  private static void assertRefusal(ApiException e, int status, String code) {
    assertThat(e.status(), is(status));
    assertThat(e.code(), is(code));
  }

  // ── refused before anything is written ─────────────────────────────────────

  @Test
  @DisplayName("Quantities for a store are refused, and nothing imported, if inventory is gone")
  void aStockServiceThatIsGoneRefusesBeforeAnythingIsWritten() {
    inventory.unavailable = new ApiException(503, "INVENTORY_UNAVAILABLE", "gone", List.of());

    assertRefusal(
        refused(request(SHEET, null, STORE.toString(), "EUR")), 503, "INVENTORY_UNAVAILABLE");
    assertRefusal(
        refused(request(SHEET_QUANTITIES_ONLY, null, STORE.toString(), null)),
        503,
        "INVENTORY_UNAVAILABLE");
    assertThat("the catalogue was not imported", svc.imports, is(0));
    assertThat("and no stock was received", inventory.received, is(empty()));
  }

  @Test
  @DisplayName("Prices are refused, and nothing imported, while pricing-svc is gone")
  void aPriceServiceThatIsGoneRefusesBeforeAnythingIsWritten() {
    pricing.unavailable = new ApiException(503, "PRICING_UNAVAILABLE", "gone", List.of());

    assertRefusal(
        refused(request(SHEET_PRICES_ONLY, null, null, "EUR")), 503, "PRICING_UNAVAILABLE");
    // The stock service being well does not make the sheet's prices askable.
    assertRefusal(
        refused(request(SHEET, null, STORE.toString(), "EUR")), 503, "PRICING_UNAVAILABLE");
    assertThat("the catalogue was not imported", svc.imports, is(0));
    assertThat("and no stock was received", inventory.received, is(empty()));
  }

  @Test
  @DisplayName("What is wrong with the request is refused before the catalogue is written")
  void aWrongRequestIsRefusedBeforeAnythingIsWritten() {
    // A typo of REPLACE would otherwise create the duplicates it was meant to overwrite.
    assertRefusal(refused(request(SHEET_PLAIN, "REPLCE", null, null)), 400, "IMPORT_MODE_INVALID");
    // The destination store is read as an id before the catalogue, not after it: not an id at all,
    // and a well-formed UUID of another version (4), which names nothing StoreQL made.
    assertRefusal(refused(request(SHEET, null, "not-an-id", "EUR")), 400, "INVALID_UUID");
    assertRefusal(
        refused(request(SHEET, null, "123e4567-e89b-42d3-a456-426614174000", "EUR")),
        400,
        "INVALID_UUID");
    // A price list needs a currency: one that is not ISO 4217, or none and tenant-svc unreadable.
    assertRefusal(refused(request(SHEET_PRICES_ONLY, null, null, "ZZZ9")), 400, "CURRENCY_INVALID");
    assertRefusal(
        refused(request(SHEET_PRICES_ONLY, null, null, null)), 503, "TENANT_PROFILE_UNAVAILABLE");
    // A sheet that cannot be read at all.
    assertRefusal(
        refused(request("SKU,Quantity\nA1,3\n", null, null, null)), 400, "CSV_MISSING_COLUMNS");
    assertRefusal(refused(request("Product Description\n", null, null, null)), 400, "CSV_EMPTY");

    assertThat("the catalogue was never imported", svc.imports, is(0));
    assertThat("no stock was asked for", inventory.asked, is(0));
    assertThat("no price was asked for", pricing.asked, is(0));
  }

  // ── asked only what the sheet needs ────────────────────────────────────────

  @Test
  @DisplayName("A service the sheet does not need is not asked, so its absence refuses nothing")
  void aServiceTheSheetDoesNotNeedIsNotAsked() {
    inventory.unavailable = new ApiException(503, "INVENTORY_UNAVAILABLE", "gone", List.of());
    pricing.unavailable = new ApiException(503, "PRICING_UNAVAILABLE", "gone", List.of());

    // No quantities and no prices, though a destination store is named.
    BulkImportResult plain =
        svc.importSupplierCsv(OWNER, request(SHEET_PLAIN, null, STORE.toString(), null));
    assertThat(plain.productsCreated(), is(1));
    assertThat(plain.stockReceived(), is(nullValue()));
    assertThat(plain.pricesSet(), is(nullValue()));

    // Quantities but no store to receive them into: nothing is received, so nothing is asked.
    svc.importSupplierCsv(OWNER, request(SHEET_QUANTITIES_ONLY, null, null, null));
    // Prices only: inventory-svc is not asked, and pricing-svc being gone is what refuses it.
    assertRefusal(
        refused(request(SHEET_PRICES_ONLY, null, null, "EUR")), 503, "PRICING_UNAVAILABLE");

    assertThat("two sheets were imported", svc.imports, is(2));
    assertThat("inventory-svc was never asked", inventory.asked, is(0));
    assertThat("pricing-svc was asked only for the sheet with prices", pricing.asked, is(1));
  }

  // ── reported after, never thrown ───────────────────────────────────────────

  @Test
  @DisplayName("A follow-up that fails once the catalogue is written is reported, not thrown")
  void aFollowUpThatFailsAfterTheCommitIsReported() {
    // Both services were there when asked first and have gone since: the catalogue is committed,
    // so the answer is the result with the failures in it, and not an error that invites a second
    // import to meet its own duplicates.
    inventory.receiveFails =
        new ApiException(
            503,
            "INVENTORY_UNAVAILABLE",
            "no healthy inventory-svc instance in discovery",
            List.of());
    pricing.pricingFails = new IllegalStateException("circuit breaker is open");

    BulkImportResult result =
        svc.importSupplierCsv(OWNER, request(SHEET, null, STORE.toString(), "EUR"));

    assertThat("the catalogue was imported once", svc.imports, is(1));
    assertThat(result.productsCreated(), is(1));
    assertThat(result.stockReceived(), is(0));
    assertThat(
        result.stockErrors(),
        is(List.of("INVENTORY_UNAVAILABLE: no healthy inventory-svc instance in discovery")));
    assertThat(result.pricesSet(), is(0));
    assertThat(
        "the exception's own words are not the caller's",
        result.priceErrors(),
        is(List.of("pricing-svc could not be asked")));
  }

  @Test
  @DisplayName("A sheet that goes through receives into the store named and prices in its currency")
  void aSheetThatGoesThroughIsReceivedAndPriced() {
    BulkImportResult result =
        svc.importSupplierCsv(OWNER, request(SHEET, "replace", " " + STORE + " ", "eur"));

    assertThat("the mode reaches the catalogue in its own words", svc.sent.mode(), is("REPLACE"));
    assertThat(inventory.receivedAt, is(STORE));
    assertThat(inventory.received.size(), is(1));
    assertThat(inventory.received.get(0).qty(), comparesEqualTo(new BigDecimal("12")));
    assertThat(pricing.currency, is("EUR"));
    assertThat(result.stockReceived(), is(1));
    assertThat(result.stockErrors(), is(nullValue()));
    assertThat(result.pricesSet(), is(1));
    assertThat(result.priceErrors(), is(nullValue()));
  }

  // ── who may import what, and as whom the follow-ups are asked ──────────────

  @Test
  @DisplayName("A manager held to one store is refused another as the destination, before a write")
  void aManagerHeldToOneStoreIsRefusedAnotherBeforeAnythingIsWritten() {
    TenantContext heldToOne = caller(TENANT, "MANAGER", Set.of(STORE), null);

    // Quantities and prices for a store they do not keep, and a store named with nothing to
    // receive: a store the caller names is a store they must keep, as at inventory-svc's own door.
    for (String sheet : new String[] {SHEET, SHEET_QUANTITIES_ONLY, SHEET_PLAIN}) {
      assertRefusal(
          refused(heldToOne, request(sheet, null, OTHER_STORE.toString(), "EUR")),
          403,
          "STORE_ACCESS_DENIED");
    }

    assertThat("the catalogue was never imported", svc.imports, is(0));
    assertThat("no stock was asked for", inventory.asked, is(0));
    assertThat("no price was asked for", pricing.asked, is(0));
    assertThat(inventory.received, is(empty()));
  }

  @Test
  @DisplayName("A sheet with prices needs pricing.write; one with none does not ask for it")
  void aSheetWithPricesNeedsThePricingPermission() {
    // A manager on a custom role that took pricing away.
    TenantContext noPricing = caller(TENANT, "MANAGER", Set.of(), Set.of("stock.adjust"));

    assertRefusal(
        refused(noPricing, request(SHEET_PRICES_ONLY, null, null, "EUR")),
        403,
        "PERMISSION_DENIED");
    assertRefusal(
        refused(noPricing, request(SHEET, null, STORE.toString(), "EUR")),
        403,
        "PERMISSION_DENIED");
    assertThat("nothing imported for a refused sheet", svc.imports, is(0));
    assertThat("and no service asked", inventory.asked + pricing.asked, is(0));

    // Without prices the permission is not asked: receiving stock is what inventory-svc lets any
    // member of staff at the store do, and it is asked as this caller below.
    BulkImportResult quantities =
        svc.importSupplierCsv(
            noPricing, request(SHEET_QUANTITIES_ONLY, null, STORE.toString(), null));
    assertThat(quantities.stockReceived(), is(1));
    BulkImportResult plain =
        svc.importSupplierCsv(noPricing, request(SHEET_PLAIN, null, null, null));
    assertThat(plain.productsCreated(), is(1));
    assertThat(svc.imports, is(2));
    assertThat("pricing-svc was never asked", pricing.asked, is(0));
  }

  @Test
  @DisplayName("An owner narrowed on paper is not narrowed: the sheet goes through")
  void anOwnerNarrowedOnPaperIsNotNarrowed() {
    TenantContext owner = caller(TENANT, "OWNER", Set.of(), Set.of());

    BulkImportResult result =
        svc.importSupplierCsv(owner, request(SHEET, null, OTHER_STORE.toString(), "EUR"));

    assertThat(result.stockReceived(), is(1));
    assertThat(result.pricesSet(), is(1));
  }

  @Test
  @DisplayName("Stock and prices are asked as the caller: their stores, permissions and login")
  void theFollowUpsAreAskedAsTheCaller() {
    TenantContext held = caller(TENANT, "MANAGER", Set.of(STORE), Set.of("pricing.write"));

    BulkImportResult result =
        svc.importSupplierCsv(held, request(SHEET, null, STORE.toString(), "EUR"));

    assertThat(result.stockReceived(), is(1));
    assertThat(result.pricesSet(), is(1));
    Caller expected = Caller.of(held);
    assertThat("inventory-svc judges the caller, not a bare tier", inventory.askedBy, is(expected));
    assertThat("and so does pricing-svc", pricing.askedBy, is(expected));
    assertThat(expected.tenantId(), is(TENANT));
    assertThat(expected.userId(), is(held.userId()));
    assertThat(expected.roles(), is(Set.of("MANAGER")));
    assertThat(expected.storeIds(), is(Set.of(STORE)));
    assertThat(expected.permissions(), is(Set.of("pricing.write")));
  }

  @Test
  @DisplayName("Another business naming our store is told it is not found, and imports nothing")
  void anotherBusinessNamingOurStoreIsNotFound() {
    // Their owner and their manager, held to no store or to theirs, naming our store as the
    // destination or in the sheet's Store column: not one of their business's stores.
    for (TenantContext theirs :
        List.of(
            caller(RIVAL, "OWNER", Set.of(), null),
            caller(RIVAL, "MANAGER", Set.of(), null),
            caller(RIVAL, "MANAGER", Set.of(RIVAL_STORE), null))) {
      assertRefusal(
          refused(theirs, request(SHEET, null, STORE.toString(), "EUR")),
          404,
          "PRODUCT_STORE_NOT_FOUND");
      assertRefusal(refused(theirs, storeColumn(STORE.toString())), 404, "PRODUCT_STORE_NOT_FOUND");
    }
    // Their storekeeper and cashier, and a shopper, hold no pricing.write by default; a priced
    // sheet is refused for it even if one reached here past the management gate at the door.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      assertRefusal(
          refused(
              caller(RIVAL, role, Set.of(), null), request(SHEET_PRICES_ONLY, null, null, "EUR")),
          403,
          "PERMISSION_DENIED");
    }
    assertThat("nothing was imported", svc.imports, is(0));
    assertThat("no service was asked", inventory.asked + pricing.asked, is(0));
  }

  @Test
  @DisplayName("Another business's import into its own store is its own, asked as itself")
  void anotherBusinessImportsIntoItsOwnStoreAsItself() {
    TenantContext theirOwner = caller(RIVAL, "OWNER", Set.of(), null);

    svc.importSupplierCsv(theirOwner, request(SHEET, null, RIVAL_STORE.toString(), "EUR"));

    assertThat(svc.importedInto, is(RIVAL));
    assertThat(inventory.receivedAt, is(RIVAL_STORE));
    assertThat(inventory.askedBy.tenantId(), is(RIVAL));
    assertThat(pricing.askedBy.tenantId(), is(RIVAL));
  }

  // ── the stores a sheet names are the business's, and the caller's ──────────

  @Test
  @DisplayName("A destination store that is not the business's is not found, before a write")
  void aDestinationStoreThatIsNotTheBusinesssIsNotFound() {
    for (UUID store : List.of(RIVAL_STORE, Ids.newId())) {
      for (String sheet : new String[] {SHEET, SHEET_QUANTITIES_ONLY, SHEET_PLAIN}) {
        assertRefusal(
            refused(request(sheet, null, store.toString(), "EUR")), 404, "PRODUCT_STORE_NOT_FOUND");
      }
    }
    assertThat("the catalogue was never imported", svc.imports, is(0));
    assertThat("no service was asked", inventory.asked + pricing.asked, is(0));
  }

  @Test
  @DisplayName("The sheet's Store column names only the business's stores, and the caller's")
  void theStoreColumnNamesOnlyTheBusinesssStoresAndTheCallers() {
    // Another business's store, and one nobody made.
    assertRefusal(refused(storeColumn(RIVAL_STORE.toString())), 404, "PRODUCT_STORE_NOT_FOUND");
    assertRefusal(refused(storeColumn(Ids.newId().toString())), 404, "PRODUCT_STORE_NOT_FOUND");
    // A store of the business the manager is not held to.
    TenantContext heldToOne = caller(TENANT, "MANAGER", Set.of(STORE), null);
    assertRefusal(
        refused(heldToOne, storeColumn(OTHER_STORE.toString())), 403, "STORE_ACCESS_DENIED");
    assertThat("the catalogue was never imported", svc.imports, is(0));

    // Their own store goes through, and reaches the row it was named on.
    svc.importSupplierCsv(heldToOne, storeColumn(STORE.toString()));
    assertThat(svc.imports, is(1));
    assertThat(svc.sent.products().get(0).storeIds(), is(List.of(STORE.toString())));
  }

  @Test
  @DisplayName("A store mapping the sheet does not use is not checked, and refuses nothing")
  void aMappingTheSheetDoesNotUseIsNotChecked() {
    TenantContext heldToOne = caller(TENANT, "MANAGER", Set.of(STORE), null);
    // The app sends every shop it knows by name; the sheet names none of them.
    SupplierCsvImportRequest req =
        new SupplierCsvImportRequest(
            SHEET_PLAIN,
            null,
            Map.of("North", OTHER_STORE.toString(), "Elsewhere", RIVAL_STORE.toString()),
            null,
            null);

    svc.importSupplierCsv(heldToOne, req);

    assertThat(svc.imports, is(1));
    assertThat("no store was named, so tenant-svc was not asked", tenants.storeReads, is(0));
  }

  @Test
  @DisplayName("Stores that cannot be checked refuse the sheet, and nothing is imported")
  void storesThatCannotBeCheckedRefuseTheSheet() {
    svc.profiles = TenantProfiles.forTest(tenant -> Optional.empty(), Clock.systemUTC());

    assertRefusal(
        refused(request(SHEET_PLAIN, null, STORE.toString(), null)),
        503,
        "TENANT_STORES_UNAVAILABLE");
    assertRefusal(refused(storeColumn(STORE.toString())), 503, "TENANT_STORES_UNAVAILABLE");
    assertThat(svc.imports, is(0));

    // A sheet that names no store does not need them.
    svc.importSupplierCsv(OWNER, request(SHEET_PLAIN, null, null, null));
    assertThat(svc.imports, is(1));
  }

  // ── an inventory-svc that stops answering, and the breaker (2 Oct 2026) ─────

  @Test
  @DisplayName("Stock that stopped part of the way is reported as far as it went, not as nothing")
  void stockThatStoppedPartOfTheWayIsReportedAsFarAsItWent() {
    inventory.receiveFails =
        new InventoryClient.Unreachable(
            new InventoryClient.BatchResult(
                500,
                List.of(
                    "500 lines may not have been received: inventory-svc gave no answer; check the"
                        + " store's stock before receiving them again",
                    "201 lines not received: not sent, because inventory-svc had stopped"
                        + " answering")),
            new IllegalStateException("connection reset"));

    BulkImportResult result =
        svc.importSupplierCsv(OWNER, request(SHEET, null, STORE.toString(), "EUR"));

    assertThat("the catalogue was imported once", svc.imports, is(1));
    assertThat(result.stockReceived(), is(500));
    assertThat(result.stockErrors().size(), is(2));
    assertThat(
        result.stockErrors().get(1),
        is("201 lines not received: not sent, because inventory-svc had stopped answering"));
    assertThat("prices are still set", result.pricesSet(), is(1));
  }

  @Test
  @DisplayName("While the breaker is open, every line is reported as not received, and why")
  void anOpenBreakerReportsEveryLine() {
    inventory.receiveFails =
        new org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException("open");

    BulkImportResult result =
        svc.importSupplierCsv(OWNER, request(SHEET, null, STORE.toString(), "EUR"));

    assertThat(result.stockReceived(), is(0));
    assertThat(
        result.stockErrors(),
        is(
            List.of(
                "1 lines not received: inventory-svc is not being asked for now, after too many"
                    + " recent failures; receive them again later")));
  }

  // ── prices are money in the sheet's currency (2 Oct 2026) ──────────────────

  /** One SKU per price, so each is judged on its own. */
  private static String priced(String... prices) {
    StringBuilder csv = new StringBuilder("Product ID,Product Description,Price\n");
    for (int i = 0; i < prices.length; i++) {
      csv.append("P-").append(i).append(",Item ").append(i).append(',').append(prices[i]);
      csv.append('\n');
    }
    return csv.toString();
  }

  /** The catalogue step answering with every SKU of a {@link #priced} sheet as imported. */
  private void importsEverySku(int n) {
    svc.everySku = n;
  }

  @Test
  @DisplayName("A price is held to its currency's minor units: refused by SKU, never rounded")
  void aPriceIsHeldToItsCurrencysMinorUnits() {
    importsEverySku(4);
    // Euro: cents. A third decimal is not a euro price; below zero is no price at all.
    BulkImportResult euro =
        svc.importSupplierCsv(
            OWNER, request(priced("3.5", "3.505", "-1.00", "4"), null, null, "EUR"));
    assertThat(
        pricing.priced.stream().map(p -> p.price().toPlainString()).toList(),
        is(List.of("3.50", "4.00")));
    assertThat(euro.pricesSet(), is(2));
    assertThat(
        euro.priceErrors(),
        is(
            List.of(
                "P-1: price 3.505 has more decimal places than EUR has (2); not set",
                "P-2: price -1.00 is below zero; not set")));

    // Yen: no minor unit at all.
    BulkImportResult yen =
        svc.importSupplierCsv(
            OWNER, request(priced("350", "350.5", "350.00", "0"), null, null, "JPY"));
    assertThat(
        pricing.priced.stream().map(p -> p.price().toPlainString()).toList(),
        is(List.of("350", "350", "0")));
    assertThat(
        yen.priceErrors(),
        is(List.of("P-1: price 350.5 has more decimal places than JPY has (0); not set")));

    // Kuwaiti dinar: fils, three decimals, so the price a euro sheet could not carry is a price.
    BulkImportResult dinar =
        svc.importSupplierCsv(
            OWNER, request(priced("3.505", "3.5", "3.5055", "1"), null, null, "KWD"));
    assertThat(
        pricing.priced.stream().map(p -> p.price().toPlainString()).toList(),
        is(List.of("3.505", "3.500", "1.000")));
    assertThat(
        dinar.priceErrors(),
        is(List.of("P-2: price 3.5055 has more decimal places than KWD has (3); not set")));
  }

  @Test
  @DisplayName("A sheet whose every price is refused asks pricing-svc nothing, and says so")
  void aSheetWhoseEveryPriceIsRefusedSetsNone() {
    importsEverySku(1);

    BulkImportResult result =
        svc.importSupplierCsv(OWNER, request(priced("0.001"), null, null, "GBP"));

    assertThat(result.pricesSet(), is(0));
    assertThat(
        result.priceErrors(),
        is(List.of("P-0: price 0.001 has more decimal places than GBP has (2); not set")));
    assertThat("nothing was sent to be priced", pricing.priced, is(empty()));
  }

  // ── a figure the sheet writes that is not a plain number (2 Oct 2026) ──────

  @Test
  @DisplayName("A price that is not a plain number is reported by SKU, never dropped or guessed")
  void aPriceThatIsNotAPlainNumberIsReported() {
    importsEverySku(5);
    // A comma decimal (or a thousands separator: nobody can say which), a currency symbol, a
    // currency code, a spreadsheet's exponent: each is reported where it was, and the price
    // that reads is set. None of them is read as some other number.
    BulkImportResult result =
        svc.importSupplierCsv(
            OWNER,
            request(
                priced("\"12,345\"", "€12.50", "12.50", "1.2E+3", "12.50 EUR"), null, null, "EUR"));

    assertThat(
        pricing.priced.stream().map(p -> p.price().toPlainString()).toList(), is(List.of("12.50")));
    assertThat(result.pricesSet(), is(1));
    assertThat(
        result.priceErrors(),
        is(
            List.of(
                "P-0: price '12,345' is not a plain number (digits and a decimal point only); not"
                    + " set",
                "P-1: price '€12.50' is not a plain number (digits and a decimal point only); not"
                    + " set",
                "P-3: price '1.2E+3' is not a plain number (digits and a decimal point only); not"
                    + " set",
                "P-4: price '12.50 EUR' is not a plain number (digits and a decimal point only);"
                    + " not set")));
  }

  @Test
  @DisplayName("A sheet whose only price cannot be read sets none, and asks pricing-svc nothing")
  void aSheetWhoseOnlyPriceCannotBeReadAsksNothing() {
    importsEverySku(1);
    // Nothing is set, so pricing.write is not asked for and pricing-svc is not asked.
    TenantContext noPricing = caller(TENANT, "MANAGER", Set.of(), Set.of("stock.adjust"));

    BulkImportResult result =
        svc.importSupplierCsv(noPricing, request(priced("abc"), null, null, null));

    assertThat(result.pricesSet(), is(0));
    assertThat(
        result.priceErrors(),
        is(
            List.of(
                "P-0: price 'abc' is not a plain number (digits and a decimal point only); not"
                    + " set")));
    assertThat(pricing.asked, is(0));
    assertThat(pricing.priced, is(empty()));
  }

  @Test
  @DisplayName("A quantity that is not a plain number is reported by SKU, and not received")
  void aQuantityThatIsNotAPlainNumberIsReported() {
    BulkImportResult result =
        svc.importSupplierCsv(
            OWNER,
            request(
                "Product ID,Product Description,Quantity\nTEA-1,Green tea,12 x 6\n",
                null,
                STORE.toString(),
                null));

    assertThat(result.stockReceived(), is(0));
    assertThat(
        result.stockErrors(),
        is(
            List.of(
                "TEA-1: quantity '12 x 6' is not a plain number (digits and a decimal point"
                    + " only); not received")));
    assertThat("nothing was received", inventory.received, is(empty()));
    // Never 126: the digits are not picked out of what the supplier wrote.
    assertThat(
        svc.sent.products().get(0).variants().get(0).attributes(), is("{\"caseSize\":\"12 x 6\"}"));
  }

  // ── a quantity inventory-svc would refuse is that row's error (2 Oct 2026) ────

  /** One SKU per quantity, so each is judged on its own. */
  private static String counted(String... quantities) {
    StringBuilder csv = new StringBuilder("Product ID,Product Description,Quantity\n");
    for (int i = 0; i < quantities.length; i++) {
      csv.append("P-").append(i).append(",Item ").append(i).append(',').append(quantities[i]);
      csv.append('\n');
    }
    return csv.toString();
  }

  @Test
  @DisplayName(
      "A quantity stock is not kept in is that row's error, and never sent to refuse others")
  void aQuantityStockIsNotKeptInIsThatRowsError() {
    importsEverySku(7);
    // inventory-svc validates a receipt whole: one of these sent would refuse every line with it.
    // Each is named by SKU instead, before anything is sent, and the rows that fit are received.
    BulkImportResult result =
        svc.importSupplierCsv(
            OWNER,
            request(
                counted(
                    "1.5",
                    "1.2345",
                    "2.5000",
                    "0",
                    "-3",
                    "1234567890123456",
                    "999999999999999.999"),
                null,
                STORE.toString(),
                null));

    assertThat(
        inventory.received.stream().map(i -> i.qty().toPlainString()).toList(),
        is(List.of("1.5", "2.5000", "999999999999999.999")));
    assertThat(result.stockReceived(), is(3));
    assertThat(
        result.stockErrors(),
        is(
            List.of(
                "P-1: quantity 1.2345 has more decimal places than stock is kept to (3); not"
                    + " received",
                "P-3: quantity 0 is not above zero; not received",
                "P-4: quantity -3 is not above zero; not received",
                "P-5: quantity 1234567890123456 has more whole digits than stock is kept to (15);"
                    + " not received")));
    assertThat("the catalogue keeps every row", svc.sent.products().size(), is(7));
  }

  @Test
  @DisplayName("A sheet whose every quantity would be refused asks inventory-svc nothing")
  void aSheetWhoseEveryQuantityIsRefusedAsksInventoryNothing() {
    importsEverySku(2);
    // Nothing would be sent, so inventory-svc being gone refuses nothing: the sheet is imported
    // and its quantities are named.
    inventory.unavailable = new ApiException(503, "INVENTORY_UNAVAILABLE", "gone", List.of());

    BulkImportResult result =
        svc.importSupplierCsv(
            OWNER, request(counted("0.0001", "12 x 6"), null, STORE.toString(), null));

    assertThat(svc.imports, is(1));
    assertThat(inventory.asked, is(0));
    assertThat(inventory.received, is(empty()));
    assertThat(result.stockReceived(), is(0));
    assertThat(
        result.stockErrors(),
        is(
            List.of(
                "P-0: quantity 0.0001 has more decimal places than stock is kept to (3); not"
                    + " received",
                "P-1: quantity '12 x 6' is not a plain number (digits and a decimal point only);"
                    + " not received")));
  }

  @Test
  @DisplayName("A manager held to the store gets the same row errors, received as themselves")
  void aHeldManagersSheetIsJudgedTheSame() {
    importsEverySku(2);
    TenantContext held = caller(TENANT, "MANAGER", Set.of(STORE), null);

    BulkImportResult result =
        svc.importSupplierCsv(held, request(counted("4", "4.0005"), null, STORE.toString(), null));

    assertThat(inventory.received.size(), is(1));
    assertThat(inventory.received.get(0).qty().toPlainString(), is("4"));
    assertThat(inventory.receivedAt, is(STORE));
    assertThat(inventory.askedBy.storeIds(), is(Set.of(STORE)));
    assertThat(
        result.stockErrors(),
        is(
            List.of(
                "P-1: quantity 4.0005 has more decimal places than stock is kept to (3); not"
                    + " received")));
  }

  @Test
  @DisplayName("The attributes keep the supplier's case size and trade price as written")
  void theAttributesKeepWhatTheSupplierWrote() {
    String sheet =
        "Product ID,Product Description,Quantity,Price\n"
            + "A-1,Tea,12,3.50\n"
            + "A-2,Coffee,-5,\"3.50 \"\"net\"\"\"\n"
            + "A-3,Cocoa,abc,C:\\list\n"
            + "A-4,Mint,,\n";

    svc.importSupplierCsv(OWNER, request(sheet, null, null, "EUR"));

    var attributes =
        svc.sent.products().stream()
            .map(p -> p.variants().get(0).attributes())
            .collect(java.util.stream.Collectors.toList());
    // A number stays a number; anything else is kept as the text it was, quotes and backslashes
    // included, in JSON that reads — never stripped to digits, and never broken into JSON that
    // does not.
    assertThat(
        attributes,
        is(
            java.util.Arrays.asList(
                "{\"caseSize\":12,\"tradePrice\":\"3.50\"}",
                "{\"caseSize\":-5,\"tradePrice\":\"3.50 \\\"net\\\"\"}",
                "{\"caseSize\":\"abc\",\"tradePrice\":\"C:\\\\list\"}",
                null)));
  }
}
