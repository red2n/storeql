package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment methods a store has switched off (catalogue POS-64), with tenant-svc a stub that
 * answers each store's {@code enabledPaymentMethods}: a method the store excludes is refused with
 * 422 {@code PAYMENT_METHOD_DISABLED} and nothing is recorded or announced; a method it includes is
 * taken; and a store setting that cannot be read (an error, or no such store) never stops a sale —
 * it fails open.
 */
@HelidonTest
class StoreMethodSettingIT {

  private static final PostgresSupport PG;
  private static final JsonStub TENANTS;

  /** What the stub answers for each store, by id: a status and the methods it enables. */
  private static final ConcurrentMap<String, Object[]> SETTINGS = new ConcurrentHashMap<>();

  static {
    PG = PostgresSupport.start().wire("payment");
    TENANTS = JsonStub.start("tenant-svc");
    TENANTS.on(
        "GET",
        "/storefront/config",
        call -> {
          Object[] setting = SETTINGS.get(JsonStub.query(call.query()).get("store"));
          if (setting == null) return new JsonStub.Answer(404, "{}");
          int status = (Integer) setting[0];
          if (status != 200) return new JsonStub.Answer(status, "{}");
          return JsonStub.Answer.ok("{\"enabledPaymentMethods\":[" + setting[1] + "]}");
        });
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    TENANTS.close();
    PG.stop();
  }

  private static UUID storeEnabling(String... methods) {
    UUID store = Ids.newId();
    StringBuilder json = new StringBuilder();
    for (String m : methods)
      json.append(json.length() == 0 ? "" : ",").append('"').append(m).append('"');
    SETTINGS.put(store.toString(), new Object[] {200, json.toString()});
    return store;
  }

  private static UUID storeAnswering(int status) {
    UUID store = Ids.newId();
    SETTINGS.put(store.toString(), new Object[] {status, ""});
    return store;
  }

  private Answer tender(UUID tenant, UUID order, UUID store, String method) {
    return ItCalls.call(
        target,
        "POST",
        "/payments",
        new Caller(tenant, Ids.newId(), "CASHIER"),
        "{\"orderId\":\""
            + order
            + "\",\"amount\":10.00,\"method\":\""
            + method
            + "\",\"storeId\":\""
            + store
            + "\""
            // a card typed at a till carries the machine's receipt reference
            + ("CARD".equals(method) ? ",\"reference\":\"AUTH 1\"" : "")
            + "}",
        Ids.newId().toString());
  }

  private String rows(UUID tenant, UUID order) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM payment.payment_tenders WHERE tenant_id = '"
            + tenant
            + "' AND order_id = '"
            + order
            + "'");
  }

  private String announced(UUID tenant, UUID order) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
            + tenant
            + "' AND event_type = 'PaymentCaptured' AND payload LIKE '%\"orderId\":\""
            + order
            + "\"%'");
  }

  @Test
  @DisplayName("POS-64: a method the store has switched off is 422, recorded and announced nowhere")
  void aMethodTheStoreSwitchedOffIsRefused() {
    UUID tenant = Ids.newId();
    UUID store = storeEnabling("CASH", "CARD");
    UUID order = Ids.newId();

    Answer refused = tender(tenant, order, store, "UPI");
    assertThat(refused.body().toString(), refused.status(), is(422));
    assertThat(refused.code(), is("PAYMENT_METHOD_DISABLED"));
    assertThat("no tender", rows(tenant, order), is("0"));
    assertThat("no PaymentCaptured", announced(tenant, order), is("0"));

    // A wallet is switched off too, and the name is read case-insensitively.
    assertThat(tender(tenant, order, store, "wallet").code(), is("PAYMENT_METHOD_DISABLED"));

    // The same store still takes what it has enabled.
    Answer taken = tender(tenant, order, store, "CARD");
    assertThat(taken.body().toString(), taken.status(), is(201));
    assertThat(rows(tenant, order), is("1"));
    assertThat(announced(tenant, order), is("1"));
  }

  @Test
  @DisplayName("POS-64: switching a method off is per store, not per business")
  void theSettingIsPerStore() {
    UUID tenant = Ids.newId();
    UUID noUpi = storeEnabling("CARD");
    UUID withUpi = storeEnabling("CARD", "UPI");

    assertThat(tender(tenant, Ids.newId(), noUpi, "UPI").status(), is(422));
    UUID order = Ids.newId();
    Answer taken = tender(tenant, order, withUpi, "UPI");
    assertThat(taken.body().toString(), taken.status(), is(201));
    assertThat(announced(tenant, order), is("1"));
  }

  @Test
  @DisplayName("POS-64: an unreadable store setting fails open: the sale is taken")
  void anUnreadableSettingFailsOpen() {
    UUID tenant = Ids.newId();
    for (UUID store : new UUID[] {storeAnswering(500), storeAnswering(503), Ids.newId()}) {
      // 500 and 503 are tenant-svc failing; the last store is one it has no record of (404).
      UUID order = Ids.newId();
      Answer taken = tender(tenant, order, store, "UPI");
      assertThat(taken.body().toString(), taken.status(), is(201));
      assertThat(rows(tenant, order), is("1"));
      assertThat(announced(tenant, order), is("1"));
    }
  }

  @Test
  @DisplayName("POS-64: the setting is asked for as the caller's own business")
  void theSettingIsReadForTheCallersBusiness() {
    UUID tenant = Ids.newId();
    UUID store = storeEnabling("CARD");
    TENANTS.reset();

    assertThat(tender(tenant, Ids.newId(), store, "UPI").status(), is(422));
    var asked =
        TENANTS.calls().stream()
            .filter(c -> store.toString().equals(JsonStub.query(c.query()).get("store")))
            .toList();
    assertThat(asked.size(), is(1));
    assertThat(asked.get(0).tenantId(), is(tenant.toString()));
  }
}
