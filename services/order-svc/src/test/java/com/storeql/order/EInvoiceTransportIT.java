package com.storeql.order;

import static com.storeql.order.support.InvoicingStubs.V_GST;
import static com.storeql.order.support.InvoicingStubs.V_PL;
import static com.storeql.order.support.InvoicingStubs.V_STD;
import static com.storeql.order.support.InvoicingStubs.basket;
import static com.storeql.order.support.InvoicingStubs.business;
import static com.storeql.order.support.InvoicingStubs.code;
import static com.storeql.order.support.InvoicingStubs.data;
import static com.storeql.order.support.InvoicingStubs.dataArray;
import static com.storeql.order.support.InvoicingStubs.envelope;
import static com.storeql.order.support.InvoicingStubs.eventually;
import static com.storeql.order.support.InvoicingStubs.only;
import static com.storeql.order.support.InvoicingStubs.services;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.storeql.ids.Ids;
import com.storeql.order.support.IrpPortalStub;
import com.storeql.order.support.KsefStub;
import com.storeql.order.support.Till;
import com.storeql.test.Concurrency;
import com.storeql.test.JsonStub;
import com.storeql.test.JsonStub.Answer;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The e-invoicing transport seam (07.13, 18.9): where a business's documents leave, and what the
 * network says. The simulated network answers at once; a stub stands where a Peppol access point's
 * facade would, and is told to deliver, take and answer later, refuse, or be down. The interesting
 * tests are the ones a network makes hard: a document sent exactly once however many ask, tried
 * again only while the network is unreachable, refused for good when it says no, and nothing sent
 * where nobody can receive it.
 */
@HelidonTest
class EInvoiceTransportIT {

  private static final String T = Ids.newId().toString();
  private static final String T_NOADDR = Ids.newId().toString();
  private static final String T_OTHER = Ids.newId().toString();
  private static final String S = Ids.newId().toString();
  private static final String S_NOADDR = Ids.newId().toString();
  private static final String S_OTHER = Ids.newId().toString();
  private static final String C_PEPPOL = Ids.newId().toString();
  private static final String C_REJECT = Ids.newId().toString();
  private static final String C_LATER = Ids.newId().toString();
  private static final String C_NOADDR = Ids.newId().toString();
  private static final String T_IN = Ids.newId().toString();
  private static final String S_IN = Ids.newId().toString();
  private static final String C_IN = Ids.newId().toString();
  private static final String T_FR = Ids.newId().toString();
  private static final String S_FR = Ids.newId().toString();
  private static final String C_FR = Ids.newId().toString();
  private static final String C_FR_REFUSE = Ids.newId().toString();
  private static final String T_RDY = Ids.newId().toString();
  private static final String S_RDY = Ids.newId().toString();
  private static final String T_PL = Ids.newId().toString();
  private static final String S_PL = Ids.newId().toString();
  private static final String C_PL = Ids.newId().toString();

  private static final String[] LEEDS = {"2 Mill Lane", "Leeds", "LS1 4AB"};

  /** How the access point answers a readiness check's participant lookup. */
  private static final AtomicReference<String> PROBE = new AtomicReference<>("known");

  /** What the access point stub does with the next document: deliver, accept, reject or down. */
  private static final AtomicReference<String> MODE = new AtomicReference<>("deliver");

  /** How the platform's own inbox answers a simulated delivery: taken, nobody, down, refused. */
  private static final AtomicReference<String> INBOX = new AtomicReference<>("taken");

  private static final String DELIVERY_KEY = "it-delivery-key";

  private static final AtomicInteger AP_POSTS = new AtomicInteger();
  private static final AtomicReference<String> LAST_POST = new AtomicReference<>();

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub SERVICES;
  private static final IrpPortalStub PORTAL;
  private static final KsefStub KSEF;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .withIdentity(T, "GB123456789", "0088", "5790000435975")
            .withLegalName(T, "Harbour Provisions Ltd")
            .withStore(T, S, "GB", "1 High Street", "London", "E1 6AN")
            .with(T_NOADDR, "GBP", "GB")
            .withIdentity(T_NOADDR, "GB987654321", null, null)
            .withLegalName(T_NOADDR, "Unaddressed Ltd")
            .withStore(T_NOADDR, S_NOADDR, "GB", "2 Low Street", "Bath", "BA1 1AA")
            .with(T_OTHER, "GBP", "GB")
            .withIdentity(T_OTHER, "GB111111111", "9932", "GB111111111")
            .withLegalName(T_OTHER, "Someone Else Ltd")
            .withStore(T_OTHER, S_OTHER, "GB", "1 Other Street", "Hull", "HU1 1AA")
            .with(T_IN, "INR", "IN")
            .withIdentity(T_IN, "27AAPFU0939F1ZV", null, null)
            .withLegalName(T_IN, "Kiran Traders Pvt Ltd")
            .withStore(T_IN, S_IN, "IN", "12 Marine Drive", "Mumbai", "400002")
            .with(T_FR, "EUR", "FR")
            .withIdentity(T_FR, "FR32123456789", null, null)
            .withLegalName(T_FR, "Épicerie du Port SARL")
            .withStore(T_FR, S_FR, "FR", "3 rue du Port", "Paris", "75001")
            .with(T_RDY, "GBP", "GB")
            .withIdentity(T_RDY, "GB444444444", "9932", "GB444444444")
            .withLegalName(T_RDY, "Readiness Ltd")
            .withStore(T_RDY, S_RDY, "GB", "9 Dock Road", "Hull", "HU1 2AA")
            .with(T_PL, "PLN", "PL")
            .withIdentity(T_PL, "PL5260250274", null, null)
            .withLegalName(T_PL, "Sklep Portowy sp. z o.o.")
            .withStore(T_PL, S_PL, "PL", "ul. Portowa 1", "Gdańsk", "80-001")
            // As if the mandate were in force: what the settings suggest.
            .withObligation("GB", "E_INVOICING_B2B", "COUNTRY", "2020-01-01", null);
    SERVICES = services();
    business(
        SERVICES, C_PEPPOL, "Cafe Leeds Ltd", "GB555555555", "GB", "9932", "GB555555555", LEEDS);
    business(
        SERVICES, C_REJECT, "Nobody Ltd", "GB555555555", "GB", "9932", "GB555555555REJECT", LEEDS);
    business(SERVICES, C_LATER, "Slow Ltd", "GB555555555", "GB", "9932", "GB555555555LATER", LEEDS);
    business(SERVICES, C_NOADDR, "Offline Ltd", "GB222222222", "GB", null, null, LEEDS);
    business(
        SERVICES,
        C_IN,
        "Bengaluru Stores Pvt Ltd",
        "29AAGCB7383J1Z4",
        "IN",
        null,
        null,
        new String[] {"4 Residency Road", "Bengaluru", "560025"});
    String[] paris = {"8 rue de Rivoli", "Paris", "75004"};
    business(
        SERVICES,
        C_FR,
        "Boulangerie Martin SAS",
        "FR03552081317",
        "FR",
        "0009",
        "55208131700013",
        paris);
    business(
        SERVICES,
        C_FR_REFUSE,
        "Grincheux SAS",
        "FR03552081317",
        "FR",
        "0009",
        "55208131700013REFUSE",
        paris);
    business(
        SERVICES,
        C_PL,
        "Kawiarnia Molo sp. z o.o.",
        "PL7740001454",
        "PL",
        null,
        null,
        new String[] {"ul. Długa 2", "Gdańsk", "80-002"});
    // India's portal, doing its own cryptography; France's platform, answering by the buyer;
    // Poland's system, opening what the client sealed.
    PORTAL = IrpPortalStub.on(SERVICES, "user1", "pass1");
    KSEF = KsefStub.on(SERVICES, "5260250274", "ksef-token-1");
    System.setProperty("storeql.einvoice.ksef.base-url", SERVICES.baseUrl() + KsefStub.PREFIX);
    SERVICES.on("POST", "/pdp/invoices", EInvoiceTransportIT::platformDeposit);
    SERVICES.on(
        "GET",
        "/pdp/invoices/F-1/lifecycle",
        200,
        "{\"status\":{\"code\":209,\"label\":\"Reçue par la plateforme\"}}");
    System.setProperty("storeql.einvoice.irp.base-url", SERVICES.baseUrl());
    System.setProperty("storeql.einvoice.irp.auth-path", IrpPortalStub.AUTH_PATH);
    System.setProperty("storeql.einvoice.irp.invoice-path", IrpPortalStub.INVOICE_PATH);
    System.setProperty("storeql.einvoice.irp.client-id", "cid");
    System.setProperty("storeql.einvoice.irp.client-secret", "csec");
    System.setProperty("storeql.einvoice.irp.public-key", PORTAL.publicKeyBase64());
    System.setProperty("storeql.einvoice.fr-pdp.base-url", SERVICES.baseUrl() + "/pdp");
    System.setProperty("storeql.einvoice.fr-pdp.api-key", "pdp-key");
    System.setProperty(
        "storeql.einvoice.secrets-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
    // The access point's facade: one document id, answered as MODE says.
    SERVICES.on("POST", "/documents", EInvoiceTransportIT::accessPointSend);
    SERVICES.on("GET", "/documents/AP-1", EInvoiceTransportIT::accessPointStatus);
    SERVICES.on(
        "GET",
        "/participants/9932:GB444444444",
        call ->
            switch (PROBE.get()) {
              case "nokey" -> new Answer(401, "{\"message\":\"bad key\"}");
              case "down" -> new Answer(503, "{\"message\":\"maintenance\"}");
              default -> new Answer(200, "{\"participant\":\"known\"}");
            });
    System.setProperty("storeql.einvoice.inbound.key", DELIVERY_KEY);
    SERVICES.on(
        "POST",
        "/e-invoices/inbound/simulated",
        call ->
            switch (INBOX.get()) {
              case "down" -> new Answer(503, "");
              case "nobody" ->
                  new Answer(
                      404,
                      "{\"error\":{\"code\":\"PURCHASE_EINVOICE_RECEIVER_UNKNOWN\",\"message\":\"no"
                          + " business on this platform holds it\"}}");
              case "refused" ->
                  new Answer(
                      422,
                      "{\"error\":{\"code\":\"PURCHASE_EINVOICE_RECEIVER_UNNAMED\",\"message\":\"the"
                          + " document names no buyer\"}}");
              default ->
                  new Answer(
                      201,
                      "{\"data\":{\"id\":\""
                          + Ids.newId()
                          + "\",\"network\":\"SIMULATED\",\"alreadyReceived\":false}}");
            });
    System.setProperty("storeql.einvoice.peppol.base-url", SERVICES.baseUrl());
    System.setProperty("storeql.einvoice.peppol.api-key", "test-key");
    System.setProperty("storeql.order.einvoice-transport.interval-seconds", "1");
    System.setProperty("storeql.order.einvoice-transport.retry-base-seconds", "1");
    System.setProperty("storeql.order.einvoice-transport.poll-seconds", "1");
    System.setProperty("storeql.order.einvoice-transport.max-attempts", "4");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    for (String p :
        List.of(
            "storeql.order.pricing.enforce",
            "storeql.einvoice.peppol.base-url",
            "storeql.einvoice.peppol.api-key",
            "storeql.order.einvoice-transport.interval-seconds",
            "storeql.order.einvoice-transport.retry-base-seconds",
            "storeql.order.einvoice-transport.poll-seconds",
            "storeql.order.einvoice-transport.max-attempts",
            "storeql.einvoice.irp.base-url",
            "storeql.einvoice.irp.auth-path",
            "storeql.einvoice.irp.invoice-path",
            "storeql.einvoice.irp.client-id",
            "storeql.einvoice.irp.client-secret",
            "storeql.einvoice.irp.public-key",
            "storeql.einvoice.fr-pdp.base-url",
            "storeql.einvoice.fr-pdp.api-key",
            "storeql.einvoice.ksef.base-url",
            "storeql.einvoice.secrets-key")) {
      System.clearProperty(p);
    }
    SERVICES.close();
    TENANTS.close();
    PG.stop();
  }

  private static JsonStub.Answer accessPointSend(JsonStub.Call call) {
    AP_POSTS.incrementAndGet();
    LAST_POST.set(call.body());
    return switch (MODE.get()) {
      case "accept" -> new JsonStub.Answer(202, "{\"id\":\"AP-1\",\"status\":\"PENDING\"}");
      case "reject" ->
          new JsonStub.Answer(422, "{\"message\":\"receiver not registered in the SMP\"}");
      case "down" -> new JsonStub.Answer(503, "{\"message\":\"maintenance\"}");
      default -> new JsonStub.Answer(201, "{\"id\":\"AP-1\",\"status\":\"DELIVERED\"}");
    };
  }

  /** The platform deposits every document but the one addressed to a buyer who refuses. */
  private static JsonStub.Answer platformDeposit(JsonStub.Call call) {
    if (call.body().contains("REFUSE")) {
      return new JsonStub.Answer(
          201,
          "{\"id\":\"F-2\",\"status\":{\"code\":210,\"label\":\"Refusée\",\"reason\":\"bon de"
              + " commande inconnu\"}}");
    }
    return new JsonStub.Answer(
        201, "{\"id\":\"F-1\",\"status\":{\"code\":200,\"label\":\"Déposée\"}}");
  }

  private static JsonStub.Answer accessPointStatus(JsonStub.Call call) {
    return new JsonStub.Answer(200, "{\"id\":\"AP-1\",\"status\":\"DELIVERED\"}");
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Till till() {
    return new Till(target);
  }

  private Response setTransport(String tenant, String json) {
    return till().put("/admin/einvoicing/transport", json, tenant, "OWNER");
  }

  private static String transport(String network, String provider) {
    return "{\"network\":\""
        + network
        + "\""
        + (provider == null ? "" : ",\"provider\":\"" + provider + "\"")
        + "}";
  }

  /** Sells to the customer and waits for the invoice; the document with its newest transmission. */
  private JsonObject invoiced(String customer) {
    return invoiced(T, S, "GBP", customer);
  }

  private JsonObject invoiced(String tenant, String store, String currency, String customer) {
    return invoiced(tenant, store, currency, customer, V_STD);
  }

  /** An Indian sale needs an item with an HSN code, or the portal's document is never written. */
  private JsonObject invoiced(
      String tenant, String store, String currency, String customer, String variant) {
    String order = till().sell(basket(store, customer, currency, variant, "1"), tenant);
    return only(till().documentsOf(order, tenant, 1), "INVOICE");
  }

  private JsonObject document(String id) {
    return document(T, id);
  }

  private JsonObject document(String tenant, String id) {
    return data(till().get("/admin/sales-invoices/" + id, tenant));
  }

  /** The document's newest transmission once it is in one of the states. */
  private JsonObject transmissionOf(String id, String... states) {
    return transmissionIn(T, id, states);
  }

  private JsonObject transmissionIn(String tenant, String id, String... states) {
    List<String> wanted = List.of(states);
    JsonObject t =
        eventually(
            () -> {
              JsonObject d = document(tenant, id);
              if (!d.containsKey("transmission") || d.isNull("transmission")) return null;
              JsonObject tr = d.getJsonObject("transmission");
              return wanted.contains(tr.getString("status")) ? tr : null;
            });
    assertThat("transmission of " + id + " in " + wanted, t, notNullValue());
    return t;
  }

  private Response send(String id, String tenant) {
    return till().post("/admin/sales-invoices/" + id + "/transmissions", "{}", tenant);
  }

  private JsonArray transmissions(String id) {
    return dataArray(till().get("/admin/sales-invoices/" + id + "/transmissions", T));
  }

  // ── readiness ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Readiness names what is missing, and asks the network rather than assuming it")
  void readiness() {
    // A business that never chose: told what to do, and nothing is asked of any network.
    JsonObject none = data(till().get("/admin/einvoicing/readiness", T_RDY));
    assertThat(none.getBoolean("ready"), is(false));
    assertThat(none.getString("network"), is("NONE"));
    assertThat(check(none, "NETWORK_CHOSEN").getBoolean("satisfied"), is(false));
    assertThat(
        check(none, "NETWORK_CHOSEN").getString("detail"), containsString("no network is chosen"));
    // Identity is a fact about the business, and holds before any network is chosen.
    assertThat(check(none, "SELLER_VAT_ID").getBoolean("satisfied"), is(true));
    assertThat(check(none, "SELLER_VAT_ID").getString("detail"), containsString("GB444444444"));
    assertThat(none.containsKey("networkState") && !none.isNull("networkState"), is(false));

    // A business whose own details cannot be read is told that, not told to record a VAT number it
    // recorded last year: the two look the same at the boundary and send a shop to different
    // places.
    JsonObject unread = data(till().get("/admin/einvoicing/readiness", Ids.newId().toString()));
    assertThat(check(unread, "SELLER_VAT_ID").getBoolean("satisfied"), is(false));
    assertThat(
        check(unread, "SELLER_VAT_ID").getString("detail"), containsString("could not be read"));
    assertThat(
        check(unread, "SELLER_VAT_ID").getString("detail"),
        containsString("nothing is wrong with the settings"));

    // The platform standing in: ready, and saying what ready means here — nothing leaves.
    assertThat(setTransport(T_RDY, transport("PEPPOL", "SIMULATED")).getStatus(), is(200));
    JsonObject simulated = data(till().get("/admin/einvoicing/readiness", T_RDY));
    assertThat(simulated.getBoolean("ready"), is(true));
    assertThat(simulated.getString("networkState"), is("READY"));
    assertThat(simulated.getString("networkDetail"), containsString("nothing leaves it"));
    assertThat(simulated.getString("networkDetail"), containsString("provider contract"));
    assertThat(
        check(simulated, "SENDER_ADDRESS").getString("detail"), containsString("9932:GB444444444"));
    assertThat(check(simulated, "CREDENTIAL_HELD").getBoolean("satisfied"), is(true));

    // A real access point, asked with the key this deployment holds.
    assertThat(setTransport(T_RDY, transport("PEPPOL", "ACCESS_POINT")).getStatus(), is(200));
    int sent = AP_POSTS.get();
    JsonObject ready = data(till().get("/admin/einvoicing/readiness", T_RDY));
    assertThat(ready.getBoolean("ready"), is(true));
    assertThat(ready.getString("networkState"), is("READY"));
    assertThat(check(ready, "PROVIDER_DEPLOYED").getBoolean("satisfied"), is(true));

    // The two answers a shop must never see as one: a key someone has to fix, and a wait.
    PROBE.set("nokey");
    JsonObject refused = data(till().get("/admin/einvoicing/readiness", T_RDY));
    assertThat(refused.getBoolean("ready"), is(false));
    assertThat(refused.getString("networkState"), is("REFUSED"));
    assertThat(refused.getString("networkDetail"), containsString("key"));
    PROBE.set("down");
    JsonObject unreachable = data(till().get("/admin/einvoicing/readiness", T_RDY));
    assertThat(unreachable.getBoolean("ready"), is(false));
    assertThat(unreachable.getString("networkState"), is("UNREACHABLE"));
    PROBE.set("known");
    // Whatever the network answered, no document was handed to it by a check.
    assertThat(AP_POSTS.get(), is(sent));

    // The readiness of a business is management's business, and only its own.
    assertThat(till().getAs("/admin/einvoicing/readiness", T_RDY, "CASHIER").getStatus(), is(403));
    assertThat(
        data(till().get("/admin/einvoicing/readiness", T_OTHER)).getString("network"), is("NONE"));
  }

  /** One item of the checklist, by its code. */
  private static JsonObject check(JsonObject readiness, String code) {
    for (JsonObject c : readiness.getJsonArray("checks").getValuesAs(JsonObject.class)) {
      if (code.equals(c.getString("code"))) return c;
    }
    throw new AssertionError("no check " + code + " in " + readiness);
  }

  // ── settings ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Nothing is sent until a network is chosen; the settings say what is offered")
  void settings() {
    // A business that never chose: the other tests set T's network as they need it.
    Response r = till().get("/admin/einvoicing/transport", T_OTHER);
    assertThat(r.getStatus(), is(200));
    JsonObject s = data(r);
    assertThat(s.getString("network"), is("NONE"));
    assertThat(
        s.getJsonArray("networks").toString(), is("[\"PEPPOL\",\"FR_PDP\",\"KSEF\",\"IRP\"]"));
    assertThat(
        s.getJsonObject("providers").getJsonArray("PEPPOL").toString(),
        is("[\"ACCESS_POINT\",\"SIMULATED\"]"));
    assertThat(
        s.getJsonObject("providers").getJsonArray("KSEF").toString(),
        is("[\"KSEF\",\"SIMULATED\"]"));
    // The stub's credentials are configured, so the access point can be chosen here.
    assertThat(
        s.getJsonObject("available").getJsonArray("PEPPOL").toString(),
        containsString("ACCESS_POINT"));
    assertThat(s.getString("senderAddress"), is("9932:GB111111111"));
    assertThat(s.getString("suggestedNetwork"), is("PEPPOL"));
    assertThat(
        data(till().get("/admin/einvoicing/transport", T)).getString("senderAddress"),
        is("0088:5790000435975"));

    assertThat(till().getAs("/admin/einvoicing/transport", T, "CASHIER").getStatus(), is(403));
    assertThat(
        till()
            .put("/admin/einvoicing/transport", transport("PEPPOL", "SIMULATED"), T, "CASHIER")
            .getStatus(),
        is(403));
    Response unknown = setTransport(T, transport("FAX", "SIMULATED"));
    assertThat(unknown.getStatus(), is(400));
    assertThat(code(unknown), is("EINVOICE_NETWORK_UNKNOWN"));
    Response noProvider = setTransport(T, transport("PEPPOL", null));
    assertThat(noProvider.getStatus(), is(400));
    assertThat(code(noProvider), is("EINVOICE_PROVIDER_REQUIRED"));
    Response wrongProvider = setTransport(T, transport("KSEF", "ACCESS_POINT"));
    assertThat(wrongProvider.getStatus(), is(400));
    assertThat(code(wrongProvider), is("EINVOICE_PROVIDER_UNKNOWN"));
    Response noSender = setTransport(T_NOADDR, transport("PEPPOL", "SIMULATED"));
    assertThat(noSender.getStatus(), is(409));
    assertThat(code(noSender), is("EINVOICE_SENDER_ADDRESS_MISSING"));
    Response longAccount =
        setTransport(
            T,
            "{\"network\":\"PEPPOL\",\"provider\":\"SIMULATED\",\"providerAccount\":\""
                + "x".repeat(121)
                + "\"}");
    assertThat(longAccount.getStatus(), is(400));
    // The request's own limit (120) answers first; the service's check after stripping is its
    // second line and cannot be reached by a longer value.
    assertThat(code(longAccount), is("VALIDATION_FAILED"));

    // KSeF with the simulated provider needs no address: the network takes the sender's own.
    Response ksef = setTransport(T_NOADDR, transport("KSEF", "SIMULATED"));
    assertThat(ksef.readEntity(String.class), ksef.getStatus(), is(200));
    Response none = setTransport(T_NOADDR, transport("NONE", null));
    assertThat(none.getStatus(), is(200));
    assertThat(data(none).getString("network"), is("NONE"));
  }

  // ── the simulated network ──────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Over the simulated network a document is delivered, refused, or taken and answered later")
  void simulated() {
    assertThat(setTransport(T, transport("PEPPOL", "SIMULATED")).getStatus(), is(200));

    JsonObject inv = invoiced(C_PEPPOL);
    JsonObject sent = transmissionOf(inv.getString("id"), "ACCEPTED");
    assertThat(sent.getString("providerRef"), startsWith("SIM-"));
    assertThat(sent.getString("receiver"), is("9932:GB555555555"));
    assertThat(sent.getString("network"), is("PEPPOL"));
    assertThat(sent.getString("provider"), is("SIMULATED"));
    assertThat(sent.getString("sentAt"), notNullValue());
    Response again = send(inv.getString("id"), T);
    assertThat(again.getStatus(), is(409));
    assertThat(code(again), is("EINVOICE_ALREADY_SENT"));

    JsonObject refused = invoiced(C_REJECT);
    JsonObject rejected = transmissionOf(refused.getString("id"), "REJECTED");
    assertThat(rejected.getString("detail"), containsString("knows no participant"));
    // Refused is final until someone sends it again; the answer is the network's again.
    Response resent = send(refused.getString("id"), T);
    assertThat(resent.getStatus(), is(200));
    assertThat(data(resent).getString("status"), is("REJECTED"));
    assertThat(transmissions(refused.getString("id")), hasSize(2));

    JsonObject slow = invoiced(C_LATER);
    JsonObject delivered = transmissionOf(slow.getString("id"), "ACCEPTED");
    assertThat(
        "taken first, then asked after", delivered.getInt("attempts"), greaterThanOrEqualTo(2));

    // A buyer with no electronic address has nowhere to receive: nothing is queued, and a send by
    // hand says why.
    JsonObject offline = invoiced(C_NOADDR);
    Response nowhere = send(offline.getString("id"), T);
    assertThat(nowhere.getStatus(), is(409));
    assertThat(code(nowhere), is("EINVOICE_RECEIVER_ADDRESS_MISSING"));
    assertThat(document(offline.getString("id")).containsKey("transmission"), is(false));
  }

  // ── an access point ────────────────────────────────────────────────────────

  @Test
  @DisplayName("An access point that is down is tried again, later each time, until it answers")
  void accessPointRetried() {
    assertThat(
        setTransport(
                T,
                "{\"network\":\"PEPPOL\",\"provider\":\"ACCESS_POINT\",\"providerAccount\":\"LE-123\"}")
            .getStatus(),
        is(200));
    MODE.set("down");
    try {
      JsonObject inv = invoiced(C_PEPPOL);
      String id = inv.getString("id");
      JsonObject waiting =
          eventually(
              () -> {
                JsonObject d = document(id);
                if (!d.containsKey("transmission")) return null;
                JsonObject t = d.getJsonObject("transmission");
                return t.getInt("attempts") >= 2 ? t : null;
              });
      assertThat(waiting, notNullValue());
      assertThat(waiting.getString("detail"), containsString("trying again"));
      // Caught between attempts, or in one: still trying either way.
      assertThat(waiting.getString("status"), anyOf(is("QUEUED"), is("SENDING")));
      // Back up, but slow to deliver: taken, then asked after until it says delivered.
      MODE.set("accept");
      JsonObject done = transmissionOf(id, "ACCEPTED");
      assertThat(done.getString("providerRef"), is("AP-1"));
      assertThat(done.getInt("attempts"), greaterThanOrEqualTo(3));
      try (JsonReader r = Json.createReader(new StringReader(LAST_POST.get()))) {
        JsonObject posted = r.readObject();
        assertThat(posted.getString("sender"), is("0088:5790000435975"));
        assertThat(posted.getString("receiver"), is("9932:GB555555555"));
        assertThat(posted.getString("documentTypeId"), containsString("Invoice-2::Invoice"));
        assertThat(
            posted.getString("processId"), is("urn:fdc:peppol.eu:2017:poacc:billing:01:1.0"));
        String xml =
            new String(
                Base64.getDecoder().decode(posted.getString("document")), StandardCharsets.UTF_8);
        assertThat(xml, containsString(inv.getString("fullNumber")));
      }
    } finally {
      MODE.set("deliver");
    }
  }

  @Test
  @DisplayName(
      "The access point's refusal is final; sent again by hand, the answer comes back at once")
  void accessPointRefusal() {
    assertThat(setTransport(T, transport("PEPPOL", "ACCESS_POINT")).getStatus(), is(200));
    MODE.set("reject");
    try {
      JsonObject inv = invoiced(C_PEPPOL);
      JsonObject rejected = transmissionOf(inv.getString("id"), "REJECTED");
      assertThat(rejected.getString("detail"), is("receiver not registered in the SMP"));
      MODE.set("deliver");
      Response resent = send(inv.getString("id"), T);
      assertThat(resent.readEntity(String.class), resent.getStatus(), is(200));
      JsonObject t =
          data(till().get("/admin/sales-invoices/" + inv.getString("id"), T))
              .getJsonObject("transmission");
      assertThat(t.getString("status"), is("ACCEPTED"));
      assertThat(t.getString("providerRef"), is("AP-1"));
      assertThat(transmissions(inv.getString("id")), hasSize(2));
    } finally {
      MODE.set("deliver");
    }
  }

  @Test
  @DisplayName(
      "After the last attempt the document is marked failed, and can be sent again by hand")
  void gaveUp() {
    assertThat(setTransport(T, transport("PEPPOL", "ACCESS_POINT")).getStatus(), is(200));
    MODE.set("down");
    try {
      JsonObject inv = invoiced(C_PEPPOL);
      JsonObject failed = transmissionOf(inv.getString("id"), "FAILED");
      assertThat(failed.getString("detail"), containsString("gave up after 4 attempts"));
      MODE.set("deliver");
      Response resent = send(inv.getString("id"), T);
      assertThat(resent.getStatus(), is(200));
      assertThat(data(resent).getString("status"), is("ACCEPTED"));
    } finally {
      MODE.set("deliver");
    }
  }

  @Test
  @DisplayName("Eight sends at once for one document send it once")
  void eightAtOnce() throws Exception {
    assertThat(setTransport(T, transport("PEPPOL", "ACCESS_POINT")).getStatus(), is(200));
    MODE.set("reject");
    JsonObject inv;
    try {
      inv = invoiced(C_PEPPOL);
      transmissionOf(inv.getString("id"), "REJECTED");
    } finally {
      MODE.set("deliver");
    }
    int before = AP_POSTS.get();
    List<Response> all = Concurrency.inParallel(8, () -> send(inv.getString("id"), T));
    long ok = all.stream().filter(r -> r.getStatus() == 200).count();
    long refused = all.stream().filter(r -> r.getStatus() == 409).count();
    assertThat(ok, is(1L));
    assertThat(refused, is(7L));
    assertThat(AP_POSTS.get() - before, is(1));
    assertThat(transmissions(inv.getString("id")), hasSize(2));
  }

  // ── the outbox, and who sees it ────────────────────────────────────────────

  @Test
  @DisplayName("The outbox lists every attempt, filtered and paged; another tenant sees none of it")
  void outbox() {
    assertThat(setTransport(T, transport("PEPPOL", "SIMULATED")).getStatus(), is(200));
    JsonObject inv = invoiced(C_PEPPOL);
    transmissionOf(inv.getString("id"), "ACCEPTED");
    Response accepted =
        till().get("/admin/einvoicing/transmissions", T, "status", "accepted", "limit", "1");
    assertThat(accepted.getStatus(), is(200));
    JsonObject env = envelope(accepted);
    assertThat(env.getJsonArray("data"), hasSize(1));
    assertThat(env.getJsonArray("data").getJsonObject(0).getString("status"), is("ACCEPTED"));
    assertThat(env.getJsonObject("meta").getString("nextCursor", null), notNullValue());
    Response unknown = till().get("/admin/einvoicing/transmissions", T, "status", "LOST");
    assertThat(unknown.getStatus(), is(400));
    assertThat(code(unknown), is("EINVOICE_TRANSMISSION_STATUS_UNKNOWN"));

    assertThat(dataArray(till().get("/admin/einvoicing/transmissions", T_OTHER)), hasSize(0));
    int attempts = transmissions(inv.getString("id")).size();
    Response theirs =
        till().get("/admin/sales-invoices/" + inv.getString("id") + "/transmissions", T_OTHER);
    assertThat(theirs.getStatus(), is(404));
    assertThat(code(theirs), is("ORDER_INVOICE_NOT_FOUND"));
    Response theirSend = send(inv.getString("id"), T_OTHER);
    assertThat(theirSend.getStatus(), is(404));
    assertThat(code(theirSend), is("ORDER_INVOICE_NOT_FOUND"));
    // An invoice nobody issued has no transmissions to read or send, in our own business too.
    Response unissued = till().get("/admin/sales-invoices/" + Ids.newId() + "/transmissions", T);
    assertThat(unissued.getStatus(), is(404));
    assertThat(code(unissued), is("ORDER_INVOICE_NOT_FOUND"));
    Response unissuedSend = send(Ids.newId().toString(), T);
    assertThat(unissuedSend.getStatus(), is(404));
    assertThat(code(unissuedSend), is("ORDER_INVOICE_NOT_FOUND"));
    // Nothing was queued by the refused sends.
    assertThat(transmissions(inv.getString("id")).size(), is(attempts));
  }

  @Test
  @DisplayName("With no network chosen a document is issued and kept, never sent")
  void none() throws Exception {
    assertThat(setTransport(T, transport("NONE", null)).getStatus(), is(200));
    JsonObject inv = invoiced(C_PEPPOL);
    Thread.sleep(2500);
    assertThat(document(inv.getString("id")).containsKey("transmission"), is(false));
    Response nothing = send(inv.getString("id"), T);
    assertThat(nothing.getStatus(), is(409));
    assertThat(code(nothing), is("EINVOICE_TRANSPORT_NOT_SET"));
    JsonObject after = data(till().get("/admin/einvoicing/transport", T));
    assertThat(!after.containsKey("provider") || after.isNull("provider"), is(true));
  }

  // ── India's portal, and France's platform ─────────────────────────────────

  @Test
  @DisplayName(
      "An Indian business signs in to the portal with its own credential, kept sealed, and"
          + " registers its invoices")
  void irp() {
    JsonObject offered = data(till().get("/admin/einvoicing/transport", T_IN));
    assertThat(offered.getString("suggestedNetwork", null), nullValue());
    assertThat(
        offered.getJsonObject("needingSecret").getJsonArray("IRP").toString(), is("[\"NIC\"]"));
    assertThat(
        offered.getJsonObject("available").getJsonArray("IRP").toString(), containsString("NIC"));

    Response noSecret =
        setTransport(
            T_IN, "{\"network\":\"IRP\",\"provider\":\"NIC\",\"providerAccount\":\"user1\"}");
    assertThat(noSecret.getStatus(), is(409));
    assertThat(code(noSecret), is("EINVOICE_PROVIDER_SECRET_REQUIRED"));
    Response chosen =
        setTransport(
            T_IN,
            "{\"network\":\"IRP\",\"provider\":\"NIC\",\"providerAccount\":\"user1\",\"providerSecret\":\"pass1\"}");
    assertThat(chosen.readEntity(String.class), chosen.getStatus(), is(200));
    Response read = till().get("/admin/einvoicing/transport", T_IN);
    String body = read.readEntity(String.class);
    assertThat(body, containsString("\"hasSecret\":true"));
    assertThat(body.contains("pass1"), is(false));

    JsonObject inv = invoiced(T_IN, S_IN, "INR", C_IN, V_GST);
    JsonObject registered =
        transmissionIn(T_IN, inv.getString("id"), "ACCEPTED", "REJECTED", "FAILED");
    assertThat(registered.toString(), registered.getString("status"), is("ACCEPTED"));
    assertThat(registered.getString("providerRef").length(), is(64));
    assertThat(registered.getString("detail"), startsWith("registered: IRN "));
    assertThat(PORTAL.lastInvoice(), containsString("\"Gstin\":\"27AAPFU0939F1ZV\""));

    // Changing the account keeps the credential; removing it means the provider cannot be kept.
    Response kept =
        setTransport(
            T_IN, "{\"network\":\"IRP\",\"provider\":\"NIC\",\"providerAccount\":\"user1\"}");
    assertThat(kept.getStatus(), is(200));
    assertThat(kept.readEntity(String.class), containsString("\"hasSecret\":true"));
    Response removed =
        setTransport(
            T_IN,
            "{\"network\":\"IRP\",\"provider\":\"NIC\",\"providerAccount\":\"user1\",\"providerSecret\":\"\"}");
    assertThat(removed.getStatus(), is(409));
    assertThat(code(removed), is("EINVOICE_PROVIDER_SECRET_REQUIRED"));

    // A wrong password is the portal's refusal, not something to try again.
    assertThat(
        setTransport(
                T_IN,
                "{\"network\":\"IRP\",\"provider\":\"NIC\",\"providerAccount\":\"user1\",\"providerSecret\":\"wrong\"}")
            .getStatus(),
        is(200));
    JsonObject refused = invoiced(T_IN, S_IN, "INR", C_IN, V_GST);
    JsonObject rejected = transmissionIn(T_IN, refused.getString("id"), "REJECTED", "FAILED");
    assertThat(rejected.getString("status"), is("REJECTED"));
    assertThat(rejected.getString("detail"), containsString("refused the business's credentials"));
  }

  @Test
  @DisplayName(
      "Over the simulated network a receiver on this platform gets the document in its inbox")
  void inPlatform() {
    assertThat(setTransport(T, transport("PEPPOL", "SIMULATED")).getStatus(), is(200));
    INBOX.set("taken");
    SERVICES.reset();
    JsonObject inv = invoiced(C_PEPPOL);
    JsonObject sent = transmissionOf(inv.getString("id"), "ACCEPTED");
    assertThat(sent.getString("detail"), containsString("inbox on this platform"));
    JsonStub.Call delivery =
        SERVICES.calls().stream()
            .filter(c -> c.path().equals("/e-invoices/inbound/simulated"))
            .reduce((first, last) -> last)
            .orElseThrow();
    assertThat(
        "the deployment's key, as an access point would present it",
        delivery.header("X-EInvoice-Key"),
        is(DELIVERY_KEY));
    assertThat(delivery.header("X-EInvoice-Reference"), is(sent.getString("providerRef")));
    assertThat(delivery.header("Content-Type"), startsWith("application/xml"));
    assertThat(
        "no identity: the receiver is whoever the document names",
        delivery.tenantId(),
        nullValue());
    assertThat(delivery.header("X-Roles"), nullValue());
    assertThat(
        delivery.body(), containsString("<cbc:ID>" + inv.getString("fullNumber") + "</cbc:ID>"));
    assertThat(delivery.body(), containsString("<cbc:EndpointID schemeID=\"9932\">GB555555555"));

    // An inbox that cannot be reached is a network that is down: tried again, later.
    INBOX.set("down");
    JsonObject waiting = invoiced(C_PEPPOL);
    JsonObject tried =
        eventually(
            () -> {
              JsonObject d = document(waiting.getString("id"));
              if (!d.containsKey("transmission") || d.isNull("transmission")) return null;
              JsonObject tr = d.getJsonObject("transmission");
              return tr.getInt("attempts") >= 1 && tr.containsKey("detail") && !tr.isNull("detail")
                  ? tr
                  : null;
            });
    assertThat(tried.getString("detail"), containsString("inbox"));
    assertThat(tried.getString("status"), anyOf(is("QUEUED"), is("SENDING")));
    INBOX.set("taken");
    assertThat(
        transmissionOf(waiting.getString("id"), "ACCEPTED").getInt("attempts"),
        greaterThanOrEqualTo(2));

    // A receiver nobody here holds is delivered the simulated way: to nowhere, and said so.
    INBOX.set("nobody");
    JsonObject away = invoiced(C_PEPPOL);
    assertThat(
        transmissionOf(away.getString("id"), "ACCEPTED").getString("detail"),
        containsString("no business on this platform holds 9932:GB555555555"));

    // An inbox that refuses the document is a refusal, with the inbox's reason.
    INBOX.set("refused");
    JsonObject bad = invoiced(C_PEPPOL);
    JsonObject rejected = transmissionOf(bad.getString("id"), "REJECTED");
    assertThat(rejected.getString("detail"), containsString("names no buyer"));
    INBOX.set("taken");
  }

  @Test
  @DisplayName(
      "A French business deposits with its platform, and hears back as the reform's statuses")
  void frenchPlatform() {
    Response chosen =
        setTransport(
            T_FR,
            "{\"network\":\"FR_PDP\",\"provider\":\"PDP\",\"providerAccount\":\"123456789\"}");
    assertThat(chosen.readEntity(String.class), chosen.getStatus(), is(200));
    JsonObject inv = invoiced(T_FR, S_FR, "EUR", C_FR);
    JsonObject delivered =
        transmissionIn(T_FR, inv.getString("id"), "ACCEPTED", "REJECTED", "FAILED");
    assertThat(delivered.toString(), delivered.getString("status"), is("ACCEPTED"));
    assertThat(delivered.getString("providerRef"), is("F-1"));
    assertThat(delivered.getString("receiver"), is("0009:55208131700013"));
    assertThat(
        "deposited, then asked after", delivered.getInt("attempts"), greaterThanOrEqualTo(2));
    assertThat(delivered.getString("detail"), containsString("with the buyer's platform"));

    JsonObject unwanted = invoiced(T_FR, S_FR, "EUR", C_FR_REFUSE);
    JsonObject refused = transmissionIn(T_FR, unwanted.getString("id"), "REJECTED", "FAILED");
    assertThat(refused.getString("status"), is("REJECTED"));
    assertThat(refused.getString("detail"), is("refused by the buyer: bon de commande inconnu"));
  }

  @Test
  @DisplayName(
      "A Polish business signs in to KSeF with its token, sends FA(3), and is given a KSeF number")
  void ksef() {
    KSEF.mode("accept");
    Response noToken = setTransport(T_PL, "{\"network\":\"KSEF\",\"provider\":\"KSEF\"}");
    assertThat(noToken.getStatus(), is(409));
    assertThat(code(noToken), is("EINVOICE_PROVIDER_SECRET_REQUIRED"));
    Response chosen =
        setTransport(
            T_PL,
            "{\"network\":\"KSEF\",\"provider\":\"KSEF\",\"providerSecret\":\"ksef-token-1\"}");
    assertThat(chosen.readEntity(String.class), chosen.getStatus(), is(200));
    JsonObject inv = invoiced(T_PL, S_PL, "PLN", C_PL, V_PL);
    JsonObject numbered =
        transmissionIn(T_PL, inv.getString("id"), "ACCEPTED", "REJECTED", "FAILED");
    assertThat(numbered.toString(), numbered.getString("status"), is("ACCEPTED"));
    assertThat(numbered.getString("providerRef"), is("5260250274-20260916-010203ABCDEF-01"));
    assertThat(numbered.getString("detail"), startsWith("KSeF number "));
    assertThat("taken, then asked after", numbered.getInt("attempts"), greaterThanOrEqualTo(2));
    assertThat(KSEF.lastInvoice(), containsString("<NIP>5260250274</NIP>"));
    assertThat(KSEF.lastInvoice(), containsString("<NIP>7740001454</NIP>"));
    assertThat(
        KSEF.lastInvoice(), containsString("<P_2>" + inv.getString("fullNumber") + "</P_2>"));

    // The system's refusal of the document is final.
    KSEF.mode("reject");
    try {
      JsonObject bad = invoiced(T_PL, S_PL, "PLN", C_PL, V_PL);
      JsonObject rejected = transmissionIn(T_PL, bad.getString("id"), "REJECTED", "FAILED");
      assertThat(rejected.getString("status"), is("REJECTED"));
      assertThat(rejected.getString("detail"), containsString("KSeF refused (450)"));
    } finally {
      KSEF.mode("accept");
    }
  }
}
