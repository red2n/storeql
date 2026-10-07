package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may import a supplier sheet that receives stock and sets prices, and as whom inventory-svc
 * and pricing-svc are asked (2 Oct 2026).
 *
 * <p>The import asked nothing of the caller past the management tier, and handed its follow-ups
 * only the tenant and the tier: a manager held to one branch received stock into any, and a manager
 * whose role took {@code pricing.write} away set prices through the sheet. Here both peers are
 * stand-ins in discovery that note every request, so what was asked of them, and as whom, is the
 * evidence; the database is counted for what was written.
 */
@HelidonTest
class SupplierCsvAuthorityIT {

  /** A British business: ours. */
  private static final String T = Ids.newId().toString();

  /** A Japanese business: another one, which must move nothing of ours. */
  private static final String OTHER = Ids.newId().toString();

  private static final String STORE = Ids.newId().toString();
  private static final String SECOND_STORE = Ids.newId().toString();
  private static final String THEIR_STORE = Ids.newId().toString();
  private static final String LIST_ID = Ids.newId().toString();
  private static final String PATH = "/admin/import/supplier-csv";
  private static final String RECEIVE = "/admin/inventory/receive/batch";
  private static final String PRICES = "/admin/price-lists/" + LIST_ID + "/items/batch";

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final JsonStub INVENTORY;
  private static final JsonStub PRICING;
  private static final JsonStub CONSUL;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "product");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
    // Two businesses in different countries, neither where GPSR binds an online offer. Stores are
    // tenant-svc's, and a sheet names only the business's own: ours has two, theirs one.
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .with(OTHER, "JPY", "JP")
        .withStore(T, STORE, "GB")
        .withStore(T, SECOND_STORE, "GB")
        .withStore(OTHER, THEIR_STORE, "JP");

    // inventory-svc as it validates a receipt: the whole call, before any line is received
    // (BatchReceiveItem.qty is @Positive @Fits(integer = 15, fraction = 3)), so one quantity it
    // refuses refuses every line sent with it.
    INVENTORY = JsonStub.start();
    INVENTORY.on(
        "POST",
        RECEIVE,
        call ->
            receivable(call.body())
                ? JsonStub.Answer.ok(
                    "{\"received\":" + count(call.body(), "\"variantId\"") + ",\"errors\":[]}")
                : new JsonStub.Answer(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\"}}"));
    PRICING = JsonStub.start();
    PRICING.on(
        "GET",
        "/price-lists",
        200,
        "{\"data\":[{\"id\":\"" + LIST_ID + "\",\"channel\":\"ALL\",\"active\":true}]}");
    PRICING.on(
        "POST",
        PRICES,
        call ->
            JsonStub.Answer.ok(
                "{\"upserted\":" + count(call.body(), "\"variantId\"") + ",\"errors\":[]}"));
    CONSUL = JsonStub.start();
    listed("inventory-svc", INVENTORY);
    listed("pricing-svc", PRICING);
    URI consul = URI.create(CONSUL.baseUrl());
    System.setProperty("storeql.consul.host", consul.getHost());
    System.setProperty("storeql.consul.port", String.valueOf(consul.getPort()));
  }

  /** Whether inventory-svc would take every quantity of a receipt, as its validation judges. */
  private static boolean receivable(String body) {
    var items =
        jakarta.json.Json.createReader(new java.io.StringReader(body))
            .readObject()
            .getJsonArray("items");
    for (int k = 0; k < items.size(); k++) {
      java.math.BigDecimal qty = items.getJsonObject(k).getJsonNumber("qty").bigDecimalValue();
      if (qty.signum() <= 0
          || qty.precision() - qty.scale() > 15
          || qty.stripTrailingZeros().scale() > 3) {
        return false;
      }
    }
    return true;
  }

  private static void listed(String service, JsonStub stub) {
    URI at = URI.create(stub.baseUrl());
    CONSUL.on(
        "GET",
        "/v1/health/service/" + service,
        200,
        "[{\"Node\":{\"Address\":\""
            + at.getHost()
            + "\"},\"Service\":{\"Address\":\""
            + at.getHost()
            + "\",\"Port\":"
            + at.getPort()
            + "}}]");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    try {
      INVENTORY.close();
      PRICING.close();
      CONSUL.close();
      System.clearProperty("storeql.consul.host");
      System.clearProperty("storeql.consul.port");
    } finally {
      try {
        PG.stop();
      } finally {
        REDIS.stop();
      }
    }
  }

  @BeforeEach
  void forgetCalls() {
    INVENTORY.reset();
    PRICING.reset();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** A member of staff as the gateway describes them; null for a header the token does not set. */
  private record Staff(
      String tenant, String user, String roles, String storeIds, String permissions) {}

  private static Staff staff(String tenant, String roles, String storeIds, String permissions) {
    return new Staff(tenant, Ids.newId().toString(), roles, storeIds, permissions);
  }

  /** A sheet of one product with a SKU of its own, carrying a quantity and a price. */
  private static String sheet(String name, String sku) {
    return sheet(name, sku, "3.50");
  }

  /** As above, at a price written in the business's own currency. */
  private static String sheet(String name, String sku, String price) {
    return "Product ID,Product Description,Category,Quantity,Price\\n"
        + sku
        + ","
        + name
        + ",Tea,12,"
        + price;
  }

  /** As {@link #sheet}, sold at the shop its Store column calls "North". */
  private static String sheetAtNorth(String name, String sku) {
    return "Product ID,Product Description,Category,Quantity,Price,Store\\n"
        + sku
        + ","
        + name
        + ",Tea,12,3.50,North";
  }

  private static String pricesOnly(String name, String sku) {
    return "Product ID,Product Description,Price\\n" + sku + "," + name + ",3.50";
  }

  private static String quantitiesOnly(String name, String sku) {
    return "Product ID,Product Description,Quantity\\n" + sku + "," + name + ",12";
  }

  /** A sheet of one product sold at the shop its Store column calls "North". */
  private static String storeColumn(String name, String sku) {
    return "Product ID,Product Description,Store\\n" + sku + "," + name + ",North";
  }

  private Response importAs(Staff who, String csv, String storeId) {
    return importAs(who, csv, storeId, null);
  }

  /** As above, with the sheet's Store column value "North" mapped to {@code north}. */
  private Response importAs(Staff who, String csv, String storeId, String north) {
    String json =
        "{\"csv\":\""
            + csv
            + "\""
            // No currency: each business's own is read from tenant-svc, as for a real sheet.
            + (storeId == null ? "" : ",\"storeId\":\"" + storeId + "\"")
            + (north == null ? "" : ",\"storeNameToId\":{\"North\":\"" + north + "\"}")
            + "}";
    Invocation.Builder req =
        target
            .path(PATH)
            .request()
            .header("X-Tenant-Id", who.tenant())
            .header("X-User-Id", who.user())
            .header("X-Roles", who.roles());
    if (who.storeIds() != null) req = req.header("X-Store-Ids", who.storeIds());
    if (who.permissions() != null) req = req.header("X-Permissions", who.permissions());
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String body(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private static int count(String text, String needle) {
    return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }

  private static int rows(String sql, String... params) {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
  }

  private static final String PRODUCTS =
      "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid";
  private static final String CATEGORIES =
      "SELECT count(*) FROM product.categories WHERE tenant_id = ?::uuid";
  private static final String VARIANTS =
      "SELECT count(*) FROM product.product_variants WHERE tenant_id = ?::uuid";
  private static final String OUTBOX =
      "SELECT count(*) FROM product.outbox WHERE tenant_id = ?::uuid";
  private static final String NAMED =
      "SELECT count(*) FROM product.products WHERE tenant_id = ?::uuid AND name = ?";

  /** Everything of a business's catalogue an import could write, counted. */
  private static List<Integer> written(String tenant) {
    return List.of(
        rows(PRODUCTS, tenant),
        rows(CATEGORIES, tenant),
        rows(VARIANTS, tenant),
        rows(OUTBOX, tenant));
  }

  private static String unique(String prefix) {
    return prefix + " " + Ids.newId();
  }

  private static String sku() {
    return "AUTH-" + Ids.newId();
  }

  // ── a manager held to stores ─────────────────────────────────────────────────

  @Test
  @DisplayName("A manager held to one store naming another is refused, and nothing is written")
  void aManagerHeldToOneStoreIsRefusedAnother() {
    Staff heldToOne = staff(T, "MANAGER", STORE, null);
    List<Integer> before = written(T);

    for (String csv :
        new String[] {
          sheet(unique("Tea"), sku()), quantitiesOnly(unique("Tea"), sku()),
        }) {
      String answer = body(importAs(heldToOne, csv, SECOND_STORE), 403);
      assertThat(answer, containsString("STORE_ACCESS_DENIED"));
    }

    assertThat("nothing of the catalogue was written", written(T), is(before));
    assertThat("no stock was received", INVENTORY.calls().size(), is(0));
    assertThat("no price was set", PRICING.calls().size(), is(0));
  }

  @Test
  @DisplayName("A manager held to stores imports into theirs, and the peers are asked as them")
  void aManagerHeldToStoresIsAskedAsThemselves() {
    Staff held = staff(T, "MANAGER", STORE + "," + SECOND_STORE, "pricing.write,stock.adjust");
    String name = unique("Tea");

    // A new product of a manager held to stores is sold at stores they name: here the shop the
    // Store column calls North, which is the store the stock goes into.
    String answer = body(importAs(held, sheetAtNorth(name, sku()), STORE, STORE), 200);

    assertThat(answer, containsString("\"stockReceived\":1"));
    assertThat(answer, containsString("\"pricesSet\":1"));
    assertThat(rows(NAMED, T, name), is(1));
    var receipt = INVENTORY.calls().get(0);
    assertThat(receipt.path(), is(RECEIVE));
    assertThat(receipt.body(), containsString("\"storeId\":\"" + STORE + "\""));
    var priced = PRICING.calls().stream().filter(c -> c.path().equals(PRICES)).toList();
    assertThat(priced.size(), is(1));
    for (var call : List.of(receipt, PRICING.calls().get(0), priced.get(0))) {
      assertThat(call.header("X-Tenant-Id"), is(T));
      assertThat(call.header("X-User-Id"), is(held.user()));
      assertThat(call.header("X-Roles"), is("MANAGER"));
      assertThat(Set.of(call.header("X-Store-Ids").split(",")), is(Set.of(STORE, SECOND_STORE)));
      assertThat(
          Set.of(call.header("X-Permissions").split(",")),
          is(Set.of("pricing.write", "stock.adjust")));
    }
  }

  // ── the pricing permission ───────────────────────────────────────────────────

  @Test
  @DisplayName("A sheet with prices needs pricing.write: refused whole without it")
  void pricesNeedThePricingPermission() {
    Staff noPricing = staff(T, "MANAGER", null, "stock.adjust,stock.transfer");
    Staff narrowedToNothing = staff(T, "MANAGER", null, "-");
    List<Integer> before = written(T);

    for (Staff who : List.of(noPricing, narrowedToNothing)) {
      assertThat(
          body(importAs(who, pricesOnly(unique("Tea"), sku()), null), 403),
          containsString("PERMISSION_DENIED"));
      assertThat(
          body(importAs(who, sheet(unique("Tea"), sku()), STORE), 403),
          containsString("pricing.write"));
    }

    assertThat("nothing of the catalogue was written", written(T), is(before));
    assertThat(INVENTORY.calls().size(), is(0));
    assertThat(PRICING.calls().size(), is(0));

    // A sheet with no prices does not ask for it; the stock is received as this caller.
    String name = unique("Tea");
    String answer = body(importAs(noPricing, quantitiesOnly(name, sku()), STORE), 200);
    assertThat(answer, containsString("\"stockReceived\":1"));
    assertThat(rows(NAMED, T, name), is(1));
    assertThat(INVENTORY.calls().get(0).header("X-Permissions"), containsString("stock.adjust"));
    assertThat(PRICING.calls().size(), is(0));
  }

  @Test
  @DisplayName("An owner narrowed on paper is not narrowed; a token with no claim keeps its tier")
  void anOwnerIsNotNarrowedAndATokenWithNoClaimKeepsItsTier() {
    Staff owner = staff(T, "OWNER", null, "-");
    Staff noClaim = staff(T, "MANAGER", null, null);

    for (Staff who : List.of(owner, noClaim)) {
      String name = unique("Tea");
      String answer = body(importAs(who, sheet(name, sku()), STORE), 200);
      assertThat(answer, containsString("\"pricesSet\":1"));
      assertThat(rows(NAMED, T, name), is(1));
    }
    // Held to no store, the peers are told none, and judge the caller unrestricted as before.
    for (var call : INVENTORY.calls()) {
      assertThat(call.header("X-Store-Ids"), is((String) null));
    }
  }

  // ── tiers below management, and a shopper ───────────────────────────────────

  @Test
  @DisplayName("Storekeeper, cashier and shopper are refused at the door, and nothing is written")
  void tiersBelowManagementAndAShopperAreRefused() {
    List<Integer> before = written(T);

    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      // Even naming a store they are assigned to, and holding pricing.write on paper.
      Staff who = staff(T, role, "CUSTOMER".equals(role) ? null : STORE, "pricing.write");
      Response r = importAs(who, sheet(unique("Tea"), sku()), STORE);
      assertThat(role, r.getStatus(), is(403));
    }

    assertThat(written(T), is(before));
    assertThat(INVENTORY.calls().size(), is(0));
    assertThat(PRICING.calls().size(), is(0));
  }

  // ── another business ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("Another business naming our store moves nothing of ours, whatever its role")
  void anotherBusinessNamingOurStoreMovesNothingOfOurs() {
    List<Integer> ours = written(T);
    List<Integer> theirs = written(OTHER);

    // Their owner and their manager, held to no store or to theirs, naming our store as the
    // destination or in the Store column: not a store of their business, so not found.
    for (Staff who :
        List.of(
            staff(OTHER, "OWNER", null, null),
            staff(OTHER, "MANAGER", null, null),
            staff(OTHER, "MANAGER", THEIR_STORE, null))) {
      assertThat(
          body(importAs(who, sheet(unique("Tea"), sku()), STORE), 404),
          containsString("PRODUCT_STORE_NOT_FOUND"));
      assertThat(
          body(importAs(who, storeColumn(unique("Tea"), sku()), null, STORE), 404),
          containsString("PRODUCT_STORE_NOT_FOUND"));
    }
    // Their storekeeper, cashier and a shopper of theirs are refused at the door.
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      Staff who = staff(OTHER, role, "CUSTOMER".equals(role) ? null : THEIR_STORE, null);
      Response r = importAs(who, sheet(unique("Tea"), sku()), STORE);
      assertThat(role, r.getStatus(), is(403));
    }
    assertThat("nothing of theirs was written", written(OTHER), is(theirs));
    assertThat("nothing of ours was written", written(T), is(ours));
    assertThat("no stock was received", INVENTORY.calls().size(), is(0));
    assertThat("no price was set", PRICING.calls().size(), is(0));

    // Their owner, into their own store: what is asked of the peers is asked in their business.
    Staff theirOwner = staff(OTHER, "OWNER", null, null);
    String name = unique("Sencha");
    // Priced in whole yen, their currency's own minor units.
    String inYen = body(importAs(theirOwner, sheet(name, sku(), "350"), THEIR_STORE), 200);
    assertThat(inYen, containsString("\"pricesSet\":1"));

    assertThat(rows(NAMED, OTHER, name), is(1));
    assertThat("nothing of ours was written", written(T), is(ours));
    assertThat(rows(NAMED, T, name), is(0));
    for (var call : INVENTORY.calls()) {
      assertThat(call.header("X-Tenant-Id"), is(OTHER));
      assertThat(call.body(), containsString("\"storeId\":\"" + THEIR_STORE + "\""));
    }
    for (var call : PRICING.calls()) {
      assertThat(call.header("X-Tenant-Id"), is(OTHER));
    }
  }

  // ── the stores a sheet names ─────────────────────────────────────────────────

  @Test
  @DisplayName("Our owner naming a store that is not ours is not found, and nothing is written")
  void aStoreThatIsNotTheBusinesssIsNotFound() {
    Staff owner = staff(T, "OWNER", null, null);
    List<Integer> before = written(T);

    for (String store : new String[] {THEIR_STORE, Ids.newId().toString()}) {
      assertThat(
          body(importAs(owner, sheet(unique("Tea"), sku()), store), 404),
          containsString("PRODUCT_STORE_NOT_FOUND"));
      assertThat(
          body(importAs(owner, storeColumn(unique("Tea"), sku()), null, store), 404),
          containsString("PRODUCT_STORE_NOT_FOUND"));
    }

    assertThat("nothing of the catalogue was written", written(T), is(before));
    assertThat(INVENTORY.calls().size(), is(0));
    assertThat(PRICING.calls().size(), is(0));
  }

  @Test
  @DisplayName("The Store column names only the held manager's stores, else nothing is written")
  void theStoreColumnNamesOnlyTheHeldManagersStores() {
    Staff heldToOne = staff(T, "MANAGER", STORE, null);
    List<Integer> before = written(T);

    assertThat(
        body(importAs(heldToOne, storeColumn(unique("Tea"), sku()), null, SECOND_STORE), 403),
        containsString("STORE_ACCESS_DENIED"));
    assertThat("nothing of the catalogue was written", written(T), is(before));

    // Their own store: the product is ranged there.
    String name = unique("Tea");
    body(importAs(heldToOne, storeColumn(name, sku()), null, STORE), 200);
    assertThat(
        rows(
            "SELECT count(*) FROM product.product_stores s JOIN product.products p"
                + " ON p.id = s.product_id AND p.tenant_id = s.tenant_id"
                + " WHERE s.tenant_id = ?::uuid AND p.name = ? AND s.store_id = ?::uuid",
            T,
            name,
            STORE),
        is(1));
  }

  // ── a held manager's new products, and prices in the business's currency (2 Oct 2026) ──

  @Test
  @DisplayName("A held manager's sheet naming no store sells each new product at their stores")
  void aHeldManagersSheetNamingNoStoreSellsAtTheirStores() {
    Staff held = staff(T, "MANAGER", STORE, "pricing.write");
    String name = unique("Tea");

    // No Store column: a new product of theirs is sold at the stores they keep, never at every
    // store (on shelves they do not keep), and never refused for it — the sheet cannot say more.
    String answer = body(importAs(held, sheet(name, sku()), STORE), 200);

    assertThat(answer, containsString("\"productsCreated\":1"));
    assertThat(answer, not(containsString("STORE_ACCESS_DENIED")));
    assertThat(answer, not(containsString("BUSINESS_WIDE_ONLY")));
    String ranged =
        "SELECT count(*) FROM product.product_stores s JOIN product.products p"
            + " ON p.id = s.product_id AND p.tenant_id = s.tenant_id"
            + " WHERE s.tenant_id = ?::uuid AND p.name = ?";
    assertThat("sold at one store", rows(ranged, T, name), is(1));
    assertThat("theirs", rows(ranged + " AND s.store_id = ?::uuid", T, name, STORE), is(1));
    assertThat("its stock was received", answer, containsString("\"stockReceived\":1"));
    assertThat("and its price set", answer, containsString("\"pricesSet\":1"));
  }

  @Test
  @DisplayName("A price with more decimal places than the business's currency has is not set")
  void aPriceIsHeldToTheBusinesssCurrency() {
    Staff owner = staff(T, "OWNER", null, null);
    String name = unique("Tea");

    // Pounds have pence: a third decimal is not a price in GBP. The product is imported; its price
    // is reported by SKU, never rounded.
    String sku = sku();
    String answer = body(importAs(owner, sheet(name, sku, "3.505"), STORE), 200);

    assertThat(answer, containsString("\"productsCreated\":1"));
    assertThat(answer, containsString("\"pricesSet\":0"));
    assertThat(
        answer, containsString(sku + ": price 3.505 has more decimal places than GBP has (2)"));
    assertThat(
        "no price was sent",
        PRICING.calls().stream().filter(c -> c.path().equals(PRICES)).count(),
        is(0L));

    // Yen have none: 350.5 is refused for the Japanese business, 350 is its price.
    Staff theirOwner = staff(OTHER, "OWNER", null, null);
    String yen =
        body(importAs(theirOwner, sheet(unique("Sencha"), sku(), "350.5"), THEIR_STORE), 200);
    assertThat(yen, containsString("has more decimal places than JPY has (0)"));
    assertThat(yen, containsString("\"pricesSet\":0"));
  }

  // ── a quantity stock is not kept in (2 Oct 2026) ─────────────────────────────

  /** A sheet of one product per quantity, each with a SKU of its own. */
  private static String counted(List<String> skus, String... quantities) {
    StringBuilder csv = new StringBuilder("Product ID,Product Description,Category,Quantity");
    for (int k = 0; k < quantities.length; k++) {
      csv.append("\\n")
          .append(skus.get(k))
          .append(',')
          .append(unique("Tea"))
          .append(",Tea,")
          .append(quantities[k]);
    }
    return csv.toString();
  }

  @Test
  @DisplayName(
      "A quantity stock is not kept in is its row's error; the sheet's others are received")
  void aQuantityStockIsNotKeptInIsItsRowsError() {
    for (Staff who :
        List.of(staff(T, "OWNER", null, null), staff(T, "MANAGER", STORE, "stock.adjust"))) {
      INVENTORY.reset();
      List<String> skus = List.of(sku(), sku(), sku(), sku());

      // inventory-svc refuses a receipt whole for one of these: before, the whole call was "4
      // lines not received: inventory-svc answered HTTP 400", and three good rows went with it.
      String answer = body(importAs(who, counted(skus, "2", "1.2345", "2.5000", "0"), STORE), 200);

      assertThat(answer, containsString("\"productsCreated\":4"));
      assertThat(answer, containsString("\"stockReceived\":2"));
      assertThat(
          answer,
          containsString(
              skus.get(1)
                  + ": quantity 1.2345 has more decimal places than stock is kept to (3); not"
                  + " received"));
      assertThat(answer, containsString(skus.get(3) + ": quantity 0 is not above zero"));
      assertThat(answer, not(containsString("answered HTTP 400")));
      assertThat("one receipt", INVENTORY.calls().size(), is(1));
      String receipt = INVENTORY.calls().get(0).body();
      assertThat(count(receipt, "\"variantId\""), is(2));
      assertThat(receipt, not(containsString("1.2345")));
      assertThat(receipt, containsString("\"storeId\":\"" + STORE + "\""));
    }
  }

  @Test
  @DisplayName("Another business's sheet of such quantities moves nothing of ours")
  void anotherBusinessesQuantitiesMoveNothingOfOurs() {
    List<Integer> ours = written(T);
    Staff theirOwner = staff(OTHER, "OWNER", null, null);
    List<String> skus = List.of(sku(), sku());

    // Into their own store: their rows are judged as ours are, and nothing of ours is touched.
    String answer = body(importAs(theirOwner, counted(skus, "3", "3.0001"), THEIR_STORE), 200);
    assertThat(answer, containsString("\"stockReceived\":1"));
    assertThat(
        INVENTORY.calls().get(0).body(), containsString("\"storeId\":\"" + THEIR_STORE + "\""));
    assertThat(INVENTORY.calls().get(0).header("X-Tenant-Id"), is(OTHER));

    // Naming our store: not found, and nothing is received or written anywhere.
    INVENTORY.reset();
    List<Integer> theirs = written(OTHER);
    String refused = body(importAs(theirOwner, counted(List.of(sku()), "3.0001"), STORE), 404);
    assertThat(refused, containsString("PRODUCT_STORE_NOT_FOUND"));
    assertThat(INVENTORY.calls().size(), is(0));
    assertThat(written(OTHER), is(theirs));
    assertThat(written(T), is(ours));
  }
}
