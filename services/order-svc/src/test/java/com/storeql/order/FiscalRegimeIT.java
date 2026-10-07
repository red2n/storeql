package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.storeql.ids.Ids;
import com.storeql.order.fiscal.PtSignature;
import com.storeql.order.fiscal.SimulatedTseProvider;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fiscal regime a store trades under (18.5), driven through the resources: a German store whose
 * every sale a security module signs, a Portuguese store whose every document is RSA-signed and
 * chained, and the settings that put them there — with the wrong caller and the wrong input at
 * every step, and the stamps verified with the public keys beside them rather than trusted.
 */
@HelidonTest
class FiscalRegimeIT {

  private static final PostgresSupport PG;
  private static final java.security.KeyPair PT_KEY;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start()
        .with(FiscalRegimeIT.T, "EUR", "PT")
        .with(FiscalRegimeIT.OTHER_T, "EUR", "DE");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(1024);
      PT_KEY = g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    // The Portuguese signing key is configuration; here it is a key this test minted, so the
    // signatures can be checked with the public half.
    System.setProperty(
        "storeql.fiscal.pt.private-key",
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(PT_KEY.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----");
  }

  private static final String T = "01a090ae-611e-702a-9bdf-bcc7032115c4";
  private static final String OTHER_T = "01a090ae-611e-702a-9bdf-bcc7032115c5";
  private static final String V = "01a090ae-611e-7055-9838-5de027ce9e0a";

  @Inject WebTarget target;
  @Inject com.storeql.order.service.OrderService orderService;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static String activeStore(String tenant) {
    String id = Ids.newId().toString();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO \"order\".store_status (store_id, tenant_id, status, status_changed_at)"
                    + " VALUES (?, ?, 'ACTIVE', now())")) {
      ps.setObject(1, Ids.parse(id));
      ps.setObject(2, Ids.parse(tenant));
      ps.executeUpdate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  private Response putAs(String path, String json, String tenant, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .header("X-User-Id", "01a090ae-611e-7055-9838-5de027ce9e0c")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response getAs(String path, String tenant, String roles, String... params) {
    WebTarget t = target.path(path);
    for (int i = 0; i < params.length; i += 2) {
      t = t.queryParam(params[i], params[i + 1]);
    }
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", roles).get();
  }

  private Response post(String path, String json, String tenant) {
    var req = target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER");
    // A void is retryable, so it carries a key: a fresh one for each attempt here.
    if (path.endsWith("/void")) req = req.header("Idempotency-Key", Ids.newId().toString());
    return req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject json(String body) {
    try (JsonReader r = Json.createReader(new StringReader(body))) {
      return r.readObject();
    }
  }

  private static JsonObject data(Response r) {
    return json(r.readEntity(String.class)).getJsonObject("data");
  }

  /** JSON-B leaves a null member out, so "no stamp" is absent or null. */
  private static boolean absent(JsonObject o, String key) {
    return !o.containsKey(key) || o.isNull(key);
  }

  private Response setRegime(String store, String body, String tenant, String roles) {
    return putAs(
        "/admin/fiscal-receipts/settings",
        "{\"storeId\":\"" + store + "\"," + body + "}",
        tenant,
        roles);
  }

  private JsonObject settings(String store) {
    Response r = getAs("/admin/fiscal-receipts/settings", T, "OWNER", "storeId", store);
    assertThat(r.getStatus(), is(200));
    return data(r);
  }

  /** Places a till sale of one unit at {@code price}, with {@code vat} on the line, and pays it. */
  private String sell(String store, String price, String vat, String method) {
    Response r =
        target
            .path("/orders")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"storeId\":\""
                        + store
                        + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                        + "\"items\":[{\"variantId\":\""
                        + V
                        + "\",\"qty\":1,\"unitPrice\":"
                        + price
                        + "}],\"currency\":\"EUR\"}",
                    MediaType.APPLICATION_JSON));
    String placed = r.readEntity(String.class);
    assertThat(placed, r.getStatus(), is(201));
    String id = json(placed).getJsonObject("data").getString("id");
    BigDecimal total = new BigDecimal(price);
    if (vat != null) {
      // What an enforced quote would have written: VAT on the line and on the order.
      try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
          var line =
              c.prepareStatement(
                  "UPDATE \"order\".order_items SET vat_amount = ? WHERE order_id = ?");
          var order =
              c.prepareStatement(
                  "UPDATE \"order\".orders SET tax_amount = ?, total = total + ? WHERE id = ?")) {
        line.setBigDecimal(1, new BigDecimal(vat));
        line.setObject(2, Ids.parse(id));
        line.executeUpdate();
        order.setBigDecimal(1, new BigDecimal(vat));
        order.setBigDecimal(2, new BigDecimal(vat));
        order.setObject(3, Ids.parse(id));
        order.executeUpdate();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      total = total.add(new BigDecimal(vat));
    }
    orderService.handlePaymentCaptured(Ids.parse(T), Ids.parse(id), Ids.newId(), total, method);
    return id;
  }

  private JsonObject receipt(String orderId) {
    Response r = getAs("/admin/orders/" + orderId + "/fiscal-receipt", T, "OWNER");
    assertThat(r.getStatus(), is(200));
    return data(r);
  }

  private JsonObject audit(String store) {
    return data(
        getAs(
            "/admin/fiscal-receipts/audit",
            T,
            "OWNER",
            "storeId",
            store,
            "series",
            "MAIN",
            "period",
            String.valueOf(Instant.now().atZone(ZoneOffset.UTC).getYear())));
  }

  // ── tests ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A store starts under NONE, and its receipts carry no stamp")
  void aStoreStartsUnderNone() {
    String store = activeStore(T);
    JsonObject s = settings(store);
    assertThat(s.getString("regime"), is("NONE"));
    assertThat(absent(s, "tse"), is(true));
    assertThat(s.getJsonArray("regimes").toString(), containsString("DE_KASSENSICHV"));
    assertThat(s.getJsonArray("regimes").toString(), containsString("PT_SAFT"));
    assertThat(s.getJsonArray("tseProviders").toString(), containsString("SIMULATED"));
    // Cloud credentials are not configured here, so the cloud provider is not on offer.
    assertThat(s.getJsonArray("tseProviders").toString(), not(containsString("CLOUD")));
    assertThat(s.getBoolean("ptKeyConfigured"), is(true));

    JsonObject r = receipt(sell(store, "5.00", null, "CASH"));
    assertThat(r.getString("regime"), is("NONE"));
    assertThat(absent(r, "tse"), is(true));
    assertThat(absent(r, "pt"), is(true));
  }

  private static org.hamcrest.Matcher<String> not(org.hamcrest.Matcher<String> m) {
    return org.hamcrest.Matchers.not(m);
  }

  @Test
  @DisplayName("Under Germany, a simulated module is registered and every sale is signed by it")
  void germanyRegistersAModuleAndSignsEverySale() {
    String store = activeStore(T);
    Response r =
        setRegime(
            store,
            "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE123456789\","
                + "\"tseProvider\":\"SIMULATED\",\"tseClientId\":\"till-1\"",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(200));
    JsonObject s = data(r);
    assertThat(s.getString("regime"), is("DE_KASSENSICHV"));
    JsonObject device = s.getJsonObject("tse");
    assertThat(device.getString("provider"), is("SIMULATED"));
    assertThat(device.getString("clientId"), is("till-1"));
    assertThat(device.getString("serialNumber").length(), is(64));
    String publicKey = device.getString("publicKey");
    assertThat(publicKey, notNullValue());

    // 10.00 net at 19% paid in cash.
    JsonObject one = receipt(sell(store, "10.00", "1.90", "CASH"));
    assertThat(one.getString("regime"), is("DE_KASSENSICHV"));
    JsonObject tse = one.getJsonObject("tse");
    assertThat(tse.getString("serialNumber"), is(device.getString("serialNumber")));
    assertThat(tse.getJsonNumber("transactionNumber").longValue(), is(1L));
    assertThat(tse.getJsonNumber("signatureCounter").longValue(), is(1L));
    assertThat(tse.getString("processType"), is("Kassenbeleg-V1"));
    assertThat(tse.getString("processData"), is("Beleg^11.90_0.00_0.00_0.00_0.00^11.90:Bar"));
    assertThat(tse.getString("algorithm"), is("ecdsa-plain-SHA256"));
    assertThat(absent(tse, "error"), is(true));
    String[] qr = tse.getString("qr").split(";");
    assertThat(qr.length, is(12));
    assertThat(qr[1], is("till-1"));
    assertThat(qr[10], is(tse.getString("signature")));

    // The signature is the device's, over the fields the receipt prints — checked with the public
    // key rather than trusted.
    String payload =
        SimulatedTseProvider.signedPayload(
            tse.getString("clientId"),
            tse.getString("processType"),
            tse.getString("processData"),
            tse.getJsonNumber("transactionNumber").longValue(),
            tse.getJsonNumber("signatureCounter").longValue(),
            Instant.parse(tse.getString("startedAt")),
            Instant.parse(tse.getString("finishedAt")));
    assertThat(
        SimulatedTseProvider.verify(publicKey, payload, tse.getString("signature")), is(true));
    assertThat(
        SimulatedTseProvider.verify(
            publicKey, payload.replace("11.90:Bar", "11.90:Unbar"), tse.getString("signature")),
        is(false));

    // The next sale, paid by card: both counters move by one, and the chain holds.
    JsonObject two = receipt(sell(store, "2.00", "0.14", "CARD")).getJsonObject("tse");
    assertThat(two.getJsonNumber("transactionNumber").longValue(), is(2L));
    assertThat(two.getJsonNumber("signatureCounter").longValue(), is(2L));
    assertThat(two.getString("processData"), is("Beleg^0.00_2.14_0.00_0.00_0.00^2.14:Unbar"));
    JsonObject a = audit(store);
    assertThat(a.getBoolean("intact"), is(true));
    assertThat(a.getBoolean("chainIntact"), is(true));
    assertThat(
        settings(store).getJsonObject("tse").getJsonNumber("signatureCounter").longValue(), is(2L));
  }

  @Test
  @DisplayName("A swapped device signature breaks the hash chain at that document")
  void aTamperedDeviceSignatureBreaksTheChain() throws Exception {
    String store = activeStore(T);
    assertThat(
        setRegime(
                store,
                "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"SIMULATED\"",
                T,
                "OWNER")
            .getStatus(),
        is(200));
    sell(store, "1.00", null, "CASH");
    String second = sell(store, "2.00", null, "CASH");
    sell(store, "3.00", null, "CASH");
    assertThat(audit(store).getBoolean("chainIntact"), is(true));
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "UPDATE \"order\".fiscal_receipts SET tse_signature = 'forged' WHERE order_id = ?")) {
      ps.setObject(1, Ids.parse(second));
      assertThat(ps.executeUpdate(), is(1));
    }
    JsonObject a = audit(store);
    assertThat(a.getBoolean("intact"), is(true));
    assertThat(a.getBoolean("chainIntact"), is(false));
    assertThat(a.getJsonNumber("chainBrokenAt").longValue(), is(2L));
  }

  @Test
  @DisplayName(
      "Under Portugal, every document is RSA-signed over its figures and chained to the last")
  void portugalSignsAndChainsEveryDocument() {
    String store = activeStore(T);
    Response r =
        setRegime(
            store,
            "\"regime\":\"PT_SAFT\",\"taxRegistrationNumber\":\"500000000\","
                + "\"certificateNumber\":\"1234\",\"seriesValidationCode\":\"ABCD1234\"",
            T,
            "OWNER");
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    JsonObject s = settings(store);
    assertThat(s.getString("regime"), is("PT_SAFT"));
    assertThat(s.getBoolean("ptKeyConfigured"), is(true));
    var publicKey = PtSignature.loadPublicKey(s.getString("ptPublicKey"));

    JsonObject one = receipt(sell(store, "10.00", "2.30", "CASH"));
    JsonObject two = receipt(sell(store, "4.00", "0.92", "CARD"));
    assertThat(one.getString("regime"), is("PT_SAFT"));
    JsonObject p1 = one.getJsonObject("pt");
    JsonObject p2 = two.getJsonObject("pt");
    assertThat(p1.getString("invoiceNo"), is("FS " + one.getString("period") + "/1"));
    assertThat(p1.getString("atcud"), is("ABCD1234-1"));
    assertThat(p2.getString("atcud"), is("ABCD1234-2"));
    assertThat(p1.getString("hashControl"), is("1"));
    assertThat(p1.getString("certificateNumber"), is("1234"));
    assertThat(p1.getString("printedExcerpt").length(), is(4));

    // Verified with the public half: the first chains on nothing, the second on the first.
    String c1 =
        PtSignature.canonical(
            Instant.parse(one.getString("issuedAt")).atZone(ZoneOffset.UTC).toLocalDate(),
            Instant.parse(one.getString("issuedAt")),
            p1.getString("invoiceNo"),
            one.getJsonNumber("grossTotal").bigDecimalValue(),
            "");
    assertThat(PtSignature.verify(publicKey, c1, p1.getString("hash")), is(true));
    String c2 =
        PtSignature.canonical(
            Instant.parse(two.getString("issuedAt")).atZone(ZoneOffset.UTC).toLocalDate(),
            Instant.parse(two.getString("issuedAt")),
            p2.getString("invoiceNo"),
            two.getJsonNumber("grossTotal").bigDecimalValue(),
            p1.getString("hash"));
    assertThat(PtSignature.verify(publicKey, c2, p2.getString("hash")), is(true));
    assertThat(
        PtSignature.verify(publicKey, c2.replace("4.92", "4.93"), p2.getString("hash")), is(false));
    assertThat(audit(store).getBoolean("chainIntact"), is(true));
  }

  @Test
  @DisplayName("What the regimes refuse, each by name")
  void whatTheRegimesRefuse() {
    String store = activeStore(T);
    Response r = setRegime(store, "\"regime\":\"FR_NF525\"", T, "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_REGIME_UNKNOWN"));

    r = setRegime(store, "\"regime\":\"DE_KASSENSICHV\",\"tseProvider\":\"SIMULATED\"", T, "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_TAX_NUMBER_REQUIRED"));
    // A refused placement registers no module — the live run found the first version doing so,
    // and the store then keeping a device with the wrong client id through the accepted placement.
    assertThat(absent(settings(store), "tse"), is(true));
    assertThat(settings(store).getString("regime"), is("NONE"));

    String fresh = activeStore(T);
    r =
        setRegime(
            fresh, "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\"", T, "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_TSE_REQUIRED"));

    r =
        setRegime(
            fresh,
            "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"CLOUD\",\"tseTssId\":\"x\"",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("FISCAL_TSE_CLOUD_NOT_CONFIGURED"));

    r =
        setRegime(
            fresh,
            "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"USB_STICK\"",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_TSE_PROVIDER_UNKNOWN"));

    r =
        setRegime(
            fresh,
            "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"SIMULATED\",\"tseClientId\":\"till one!\"",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_TSE_CLIENT_ID_INVALID"));

    r =
        setRegime(
            fresh, "\"regime\":\"PT_SAFT\",\"taxRegistrationNumber\":\"123456780\"", T, "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_NIF_INVALID"));

    r =
        setRegime(
            fresh,
            "\"regime\":\"PT_SAFT\",\"taxRegistrationNumber\":\"500000000\",\"seriesValidationCode\":\"has space\"",
            T,
            "OWNER");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_SERIES_CODE_INVALID"));

    r = setRegime(fresh, "\"regime\":\"\"", T, "OWNER");
    assertThat(r.getStatus(), is(400));

    // Nothing above changed the store: a refusal stores nothing.
    assertThat(settings(fresh).getString("regime"), is("NONE"));
    assertThat(absent(settings(fresh), "tse"), is(true));
  }

  @Test
  @DisplayName("Settings are management-only and tenant-scoped")
  void settingsAreManagementOnlyAndTenantScoped() {
    String store = activeStore(T);
    String body =
        "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"SIMULATED\"";
    assertThat(setRegime(store, body, T, "CASHIER").getStatus(), is(403));
    assertThat(setRegime(store, body, T, "STOREKEEPER").getStatus(), is(403));
    assertThat(
        getAs("/admin/fiscal-receipts/settings", T, "CASHIER", "storeId", store).getStatus(),
        is(403));

    // A rival tenant's owner cannot place our store under a regime: it is not one of theirs.
    Response r = setRegime(store, body, OTHER_T, "OWNER");
    assertThat(r.getStatus(), is(409));
    assertThat(r.readEntity(String.class), containsString("STORE_NOT_OPERATIONAL"));
    // And reads nothing of ours: their view of our store id is an unset store of their own.
    assertThat(setRegime(store, body, T, "MANAGER").getStatus(), is(200));
    Response theirs = getAs("/admin/fiscal-receipts/settings", OTHER_T, "OWNER", "storeId", store);
    assertThat(theirs.getStatus(), is(200));
    JsonObject theirView = data(theirs);
    assertThat(theirView.getString("regime"), is("NONE"));
    assertThat(absent(theirView, "tse"), is(true));
  }

  @Test
  @DisplayName("Changing the regime leaves every issued document as it was")
  void changingTheRegimeLeavesIssuedDocumentsAlone() {
    String store = activeStore(T);
    String first = sell(store, "1.00", null, "CASH");
    assertThat(
        setRegime(
                store,
                "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"SIMULATED\"",
                T,
                "OWNER")
            .getStatus(),
        is(200));
    String second = sell(store, "2.00", null, "CASH");
    assertThat(setRegime(store, "\"regime\":\"NONE\"", T, "OWNER").getStatus(), is(200));
    String third = sell(store, "3.00", null, "CASH");

    assertThat(receipt(first).getString("regime"), is("NONE"));
    assertThat(absent(receipt(first), "tse"), is(true));
    assertThat(receipt(second).getString("regime"), is("DE_KASSENSICHV"));
    assertThat(receipt(second).getJsonObject("tse").getString("signature"), notNullValue());
    assertThat(receipt(third).getString("regime"), is("NONE"));
    JsonObject a = audit(store);
    assertThat(a.getBoolean("intact"), is(true));
    assertThat(a.getBoolean("chainIntact"), is(true));
    // The device stays registered through the change, so going back does not mint a new serial.
    assertThat(settings(store).getJsonObject("tse").getString("provider"), is("SIMULATED"));
  }

  @Test
  @DisplayName("A voided sale keeps its stamp")
  void aVoidedSaleKeepsItsStamp() {
    String store = activeStore(T);
    assertThat(
        setRegime(
                store,
                "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"SIMULATED\"",
                T,
                "OWNER")
            .getStatus(),
        is(200));
    String id = sell(store, "9.00", null, "CASH");
    assertThat(
        post("/orders/" + id + "/void", "{\"reason\":\"wrong item scanned\"}", T).getStatus(),
        is(200));
    JsonObject r = receipt(id);
    assertThat(absent(r, "voidedAt"), is(false));
    assertThat(r.getJsonObject("tse").getString("signature"), notNullValue());
    assertThat(audit(store).getBoolean("chainIntact"), is(true));
  }

  @Test
  @DisplayName("The inspector's files refuse to name nobody, and refuse the wrong caller")
  void theInspectorFilesRefuseToNameNobody() {
    String store = activeStore(T);
    String year = String.valueOf(Instant.now().atZone(ZoneOffset.UTC).getYear());
    // tenant-svc is not reachable here (discovery is off), so the store's identity cannot be read
    // and no file is written — a file naming no business is worse than no file.
    Response r =
        getAs(
            "/admin/fiscal-receipts/export",
            T,
            "OWNER",
            "storeId",
            store,
            "period",
            year,
            "format",
            "dsfinvk");
    assertThat(r.getStatus(), is(503));
    assertThat(r.readEntity(String.class), containsString("FISCAL_EXPORT_DEPENDENCY_UNAVAILABLE"));
    r =
        getAs(
            "/admin/fiscal-receipts/export",
            T,
            "OWNER",
            "storeId",
            store,
            "period",
            year,
            "format",
            "saft-pt");
    assertThat(r.getStatus(), is(503));
    r =
        getAs(
            "/admin/fiscal-receipts/export",
            T,
            "OWNER",
            "storeId",
            store,
            "period",
            year,
            "format",
            "pdf");
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("FISCAL_EXPORT_FORMAT_UNKNOWN"));
    assertThat(
        getAs(
                "/admin/fiscal-receipts/export",
                T,
                "CASHIER",
                "storeId",
                store,
                "period",
                year,
                "format",
                "dsfinvk")
            .getStatus(),
        is(403));
    // The register itself still exports.
    assertThat(
        getAs(
                "/admin/fiscal-receipts/export",
                T,
                "OWNER",
                "storeId",
                store,
                "period",
                year,
                "format",
                "csv")
            .getStatus(),
        is(200));
  }

  @Test
  @DisplayName("Eight tills signing at once get eight distinct counters")
  void theSimulatedModuleCountsOncePerSaleUnderConcurrency() throws Exception {
    String store = activeStore(T);
    assertThat(
        setRegime(
                store,
                "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"SIMULATED\"",
                T,
                "OWNER")
            .getStatus(),
        is(200));
    List<Callable<String>> tills = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      tills.add(() -> sell(store, "1.00", null, "CASH"));
    }
    Set<Long> txNumbers = new HashSet<>();
    Set<Long> counters = new HashSet<>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      for (var f : pool.invokeAll(tills)) {
        JsonObject tse = receipt(f.get()).getJsonObject("tse");
        txNumbers.add(tse.getJsonNumber("transactionNumber").longValue());
        counters.add(tse.getJsonNumber("signatureCounter").longValue());
      }
    }
    assertThat(txNumbers.size(), is(8));
    assertThat(counters.size(), is(8));
    JsonObject a = audit(store);
    assertThat(a.getBoolean("intact"), is(true));
    assertThat(a.getBoolean("chainIntact"), is(true));
    assertThat(a.getJsonNumber("issued").longValue(), is(8L));
  }

  @Test
  @DisplayName("The issued document is what the till reads, stamp included")
  void theTillReadsTheStamp() {
    String store = activeStore(T);
    assertThat(
        setRegime(
                store,
                "\"regime\":\"DE_KASSENSICHV\",\"taxRegistrationNumber\":\"DE1\",\"tseProvider\":\"SIMULATED\"",
                T,
                "OWNER")
            .getStatus(),
        is(200));
    String id = sell(store, "7.00", null, "CASH");
    Response r = getAs("/orders/" + id + "/fiscal-receipt", T, "CASHIER");
    assertThat(r.getStatus(), is(200));
    JsonObject d = data(r);
    assertThat(d.getString("regime"), is("DE_KASSENSICHV"));
    assertThat(d.getJsonObject("tse").getString("qr"), containsString("Kassenbeleg-V1"));
    assertThat(absent(d, "pt"), is(true));
    assertThat(d.getString("hash"), is(notNullValue()));
  }
}
