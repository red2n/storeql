package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.gs1.Gtin;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Scanning a GS1 2D code at the till (07.15), over HTTP and a real database.
 *
 * <p>The assertion this class exists for is the first one: <b>a shop that typed the EAN-13 off the
 * shelf edge is found by a 2D code carrying the GTIN-14 of the same item.</b> That is what GS1
 * Sunrise 2027 asks a till to do, and it cannot be had from a unit test — it is a generated column
 * and an index in Postgres, computed from whatever barcode the shop actually entered, and the
 * parser alone knows nothing about what is in the catalogue.
 *
 * <p>The generated column is the other reason for a database: it means the match key
 * <em>cannot</em> drift from the barcode. Editing the barcode moves the key in the same statement,
 * so there is no write path that can forget it — which is exactly what a test against a stubbed
 * repository would miss.
 */
@HelidonTest
class Gs1ScanIT {

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;

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
    TenantSvcStub.start().with(T, "GBP", "GB").with(RIVAL, "GBP", "GB");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /**
   * A scan. The code goes in a query parameter: a Digital Link carries slashes and a query itself.
   */
  private Response scan(String code, String tenant) {
    return target
        .path("/catalog/scan")
        .queryParam("code", code)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "CASHIER")
        .get();
  }

  private String scanned(String code, String tenant) {
    Response r = scan(code, tenant);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return body;
  }

  private static String id(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return Json.createReader(new StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  /**
   * A fresh EAN-13 nobody else in this schema holds, check digit and all.
   *
   * <p>These tests share one database, so a barcode reused between them is a DUPLICATE on the
   * unique index — and worse, two <em>different</em> barcodes that normalise to the same GTIN put
   * two variants behind one match key, which is the collision the migration deliberately tolerates
   * and the lookup resolves by taking the oldest. Either way the test would be asserting about the
   * other test's data.
   */
  private static String freshEan13() {
    String body = String.format("7%011d", COUNTER.incrementAndGet());
    return body + Gtin.checkDigit(body);
  }

  private static final java.util.concurrent.atomic.AtomicLong COUNTER =
      new java.util.concurrent.atomic.AtomicLong(1);

  /**
   * A variant carrying exactly the barcode given, so the match is about that barcode and nothing
   * else.
   */
  private String variantWithBarcode(String tenant, String barcode) {
    String product =
        id(post("/admin/products", "{\"name\":\"Tinned " + Ids.newId() + "\"}", tenant));
    return id(
        post(
            "/admin/products/" + product + "/variants",
            "{\"sku\":\"S-" + Ids.newId() + "\",\"barcode\":\"" + barcode + "\"}",
            tenant));
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A 2D code's GTIN-14 finds the variant a shop entered as an EAN-13")
  void theGtinFormIsOneIdentity() {
    String ean13 = freshEan13();
    String gtin14 = "0" + ean13;
    String variant = variantWithBarcode(T, ean13);

    // Every form of the same GTIN, and the element string and Digital Link that carry it. All five
    // name one item; before this row only the first found anything.
    for (String code :
        new String[] {
          ean13, gtin14, "01" + gtin14, "(01)" + gtin14, "https://id.gs1.org/01/" + gtin14
        }) {
      assertThat(code, scanned(code, T), containsString("\"variantId\":\"" + variant + "\""));
    }
  }

  @Test
  @DisplayName(
      "The reverse too: a shop that entered the GTIN-14 is found by the EAN-13 on the shelf")
  void andTheOtherWayAbout() {
    String ean13 = freshEan13();
    String variant = variantWithBarcode(T, "0" + ean13);
    assertThat(scanned(ean13, T), containsString("\"variantId\":\"" + variant + "\""));
  }

  @Test
  @DisplayName("What the code carried comes back beside the item, unconverted")
  void whatTheCodeCarried() {
    String gtin14 = "0" + freshEan13();
    String variant = variantWithBarcode(T, gtin14);
    String body = scanned("01" + gtin14 + "17261231" + "3103001250" + "10" + "LOT-7", T);

    assertThat(body, containsString("\"variantId\":\"" + variant + "\""));
    assertThat(body, containsString("\"format\":\"ELEMENT_STRING\""));
    assertThat(body, containsString("\"gtin\":\"" + gtin14 + "\""));
    assertThat(body, containsString("\"batch\":\"LOT-7\""));
    assertThat("the date, not the six digits", body, containsString("\"expiry\":\"2026-12-31\""));
    // Scaled by the AI's own decimal places, and a string on the wire because a weight prices
    // goods.
    assertThat(body, containsString("\"netWeightKg\":\"1.250\""));
  }

  @Test
  @DisplayName("A Digital Link carries its extras in the query, and reads the same")
  void aDigitalLink() {
    String gtin14 = "0" + freshEan13();
    String variant = variantWithBarcode(T, gtin14);
    // A plain batch, not a percent-encoded one. A lot number carrying a slash is a question about
    // how
    // the transport encodes the URI, not about the reading — Gs1ReaderTest covers the encoded form
    // directly, where no HTTP client can decode it early and make the assertion mean something
    // else.
    String body = scanned("https://example.co.uk/01/" + gtin14 + "/10/AB12?17=270131&3922=0899", T);

    assertThat(body, containsString("\"variantId\":\"" + variant + "\""));
    assertThat(body, containsString("\"format\":\"DIGITAL_LINK\""));
    assertThat(body, containsString("\"batch\":\"AB12\""));
    assertThat(body, containsString("\"expiry\":\"2027-01-31\""));
    assertThat(body, containsString("\"amountPayable\":\"8.99\""));
  }

  @Test
  @DisplayName("A code that is not GS1 still works, and says it carried nothing")
  void anInternalCodeStillWorks() {
    // The regression this guards: a shop's own internal codes, PLUs and shelf labels have always
    // been
    // matched exactly, and understanding GS1 better must not stop them working.
    String code = "SHELF-EDGE-" + COUNTER.incrementAndGet();
    String variant = variantWithBarcode(T, code);
    String body = scanned(code, T);
    assertThat(body, containsString("\"variantId\":\"" + variant + "\""));
    // The envelope omits a null rather than sending it, so the absence of a format is the
    // assertion.
    assertThat(
        "nothing was read, because there was nothing to read", body, not(containsString("format")));
  }

  @Test
  @DisplayName("A shop that typed a whole element string into the barcode field is still found")
  void anElementStringStoredAsTheBarcode() {
    // Entirely possible, and refusing to find it because the platform now parses the format would
    // be a
    // regression for that shop. The raw string is tried after the GTIN form, not instead of it.
    // Its GTIN belongs to no variant, so the GTIN lookup misses and the raw string is what finds
    // it.
    String stored = "01" + "0" + freshEan13() + "10FROM-LABEL";
    String variant = variantWithBarcode(T, stored);
    assertThat(scanned(stored, T), containsString("\"variantId\":\"" + variant + "\""));
  }

  // ── what it refuses ───────────────────────────────────────────────────────

  @Test
  @DisplayName("A GTIN nothing carries is a 404 naming the GTIN, not the element string")
  void nothingCarriesIt() {
    String gtin14 = "0" + freshEan13();
    Response r = scan("01" + gtin14 + "10NONE", T);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(404));
    assertThat("a shopkeeper can act on a GTIN", body, containsString(gtin14));
    assertThat(body, containsString("VARIANT_NOT_FOUND"));
  }

  @Test
  @DisplayName("A misread check digit finds nothing rather than the wrong item")
  void aMisreadCheckDigit() {
    String ean13 = freshEan13();
    variantWithBarcode(T, ean13);
    // One digit out in the check digit. The alternative to refusing it is selling a different item
    // at
    // a different price, and the barcode column does not carry the wrong spelling either.
    char wrong = ean13.charAt(12) == '0' ? '1' : '0';
    String misread = ean13.substring(0, 12) + wrong;
    assertThat(scan(misread, T).getStatus(), is(404));
    assertThat(scan("01" + "0" + misread, T).getStatus(), is(404));
  }

  @Test
  @DisplayName("A blank code is a bad request, and no code at all likewise")
  void aBlankCode() {
    assertThat(scan("", T).getStatus(), is(400));
    Response none =
        target
            .path("/catalog/scan")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .get();
    assertThat(none.getStatus(), is(400));
  }

  @Test
  @DisplayName("One business never scans another's item, however it is encoded")
  void oneBusinessNeverScansAnothers() {
    String ean13 = freshEan13();
    String mine = variantWithBarcode(T, ean13);
    assertThat(scanned(ean13, T), containsString(mine));
    // The rival holds no such item, and the GTIN form must not become a way around the tenant
    // filter.
    assertThat(scan(ean13, RIVAL).getStatus(), is(404));
    assertThat(scan("https://id.gs1.org/01/0" + ean13, RIVAL).getStatus(), is(404));
    assertThat(scanned(ean13, T), not(containsString(RIVAL)));
  }

  @Test
  @DisplayName("A line that is listed but not yet on sale says so, whichever way it is scanned")
  void aLineNotYetOnSale() {
    // The same refusal the old route gives. Two scan paths reaching the same variants must not
    // disagree about whether it may be sold — one of them would be selling before the launch date.
    String product =
        id(
            post(
                "/admin/products",
                "{\"name\":\"Launch "
                    + Ids.newId()
                    + "\",\"status\":\"NEW_LINE\",\"launchOn\":\"2099-01-01\"}",
                T));
    String barcode = freshEan13();
    id(
        post(
            "/admin/products/" + product + "/variants",
            "{\"sku\":\"L-" + Ids.newId() + "\",\"barcode\":\"" + barcode + "\"}",
            T));

    Response viaScan = scan("01" + "0" + barcode, T);
    String body = viaScan.readEntity(String.class);
    assertThat(body, viaScan.getStatus(), is(409));
    assertThat(body, containsString("PRODUCT_NOT_ON_SALE_YET"));
  }

  /** A variant with the SKU given and no barcode at all — what a label a till cannot read has. */
  private String variantWithSku(String tenant, String sku) {
    String product =
        id(post("/admin/products", "{\"name\":\"Loose " + Ids.newId() + "\"}", tenant));
    return id(
        post("/admin/products/" + product + "/variants", "{\"sku\":\"" + sku + "\"}", tenant));
  }

  /**
   * The till's scan box says "or type SKU": when a label will not scan, the cashier types what is
   * printed under it. Only barcodes and GTINs were ever matched, so a typed SKU found nothing.
   */
  @Test
  @DisplayName("A SKU typed at the till finds its item, in whatever case it is typed")
  void aTypedSkuFindsItsItem() {
    String sku = "APL-GALA-" + COUNTER.incrementAndGet();
    String variant = variantWithSku(T, sku);
    assertThat(scanned(sku, T), containsString("\"variantId\":\"" + variant + "\""));
    assertThat(scanned("  " + sku + " ", T), containsString(variant));
    assertThat(scanned(sku.toLowerCase(java.util.Locale.ROOT), T), containsString(variant));
    // Another business's staff typing the same SKU find nothing of ours.
    assertThat(scan(sku, RIVAL).getStatus(), is(404));
  }

  @Test
  @DisplayName("A barcode wins over a SKU that happens to read the same")
  void aBarcodeWinsOverASku() {
    String code = "DUAL-" + COUNTER.incrementAndGet();
    String bySku = variantWithSku(T, code);
    String byBarcode = variantWithBarcode(T, code);
    String body = scanned(code, T);
    assertThat(body, containsString(byBarcode));
    assertThat(body, not(containsString(bySku)));
  }

  @Test
  @DisplayName("Two SKUs that differ only in case are never guessed between")
  void twoSkusDifferingInCaseAreNotGuessed() {
    String upper = "PLU-AB-" + COUNTER.incrementAndGet();
    String lower = upper.toLowerCase(java.util.Locale.ROOT);
    String upperVariant = variantWithSku(T, upper);
    String lowerVariant = variantWithSku(T, lower);
    // Typed exactly, each finds its own.
    assertThat(scanned(upper, T), containsString(upperVariant));
    assertThat(scanned(lower, T), containsString(lowerVariant));
    // Typed in a third spelling, it could be either: nothing is guessed.
    String mixed = "Plu-Ab-" + upper.substring("PLU-AB-".length());
    assertThat(scan(mixed, T).getStatus(), is(404));
  }
}
