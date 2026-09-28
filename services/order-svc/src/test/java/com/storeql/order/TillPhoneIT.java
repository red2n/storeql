package com.storeql.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A phone at the till (intent/phone-at-the-till.md), against real Postgres: a Required shop refuses
 * a till sale with neither a number nor a customer; Optional and Don't-ask shops take one with
 * none; a number is read in the shop's own country, then the business's, and kept in international
 * form beside the typed one; one that is no phone where the business trades is refused at the till
 * and kept as typed online; a store or a business tenant-svc cannot answer about refuses nothing;
 * and another business's store id is refused as it always was, its choice never applied here.
 *
 * <p>tenant-svc is a hand-built {@link JsonStub}: each store here needs its own country and till
 * choice, which the shared {@code TenantSvcStub} cannot say.
 */
@HelidonTest
class TillPhoneIT {

  // An Indian business with a shop in Britain; a French one with a shop in the Netherlands; and one
  // tenant-svc cannot answer about.
  private static final String T = "01a0f1a0-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a0f1a0-611e-702c-a97b-d1b8025478e2";
  private static final String T3 = "01a0f1a0-611e-702c-a97b-d1b8025478e3";
  private static final String REQUIRED_SHOP = "01a0f1a0-611e-703c-a378-a4972ea461e1";
  private static final String OPTIONAL_SHOP = "01a0f1a0-611e-703c-a378-a4972ea461e2";
  private static final String OFF_SHOP = "01a0f1a0-611e-703c-a378-a4972ea461e3";
  private static final String GB_SHOP = "01a0f1a0-611e-703c-a378-a4972ea461e4";
  private static final String FR_SHOP = "01a0f1a0-611e-703c-a378-a4972ea461e5";
  private static final String NL_SHOP = "01a0f1a0-611e-703c-a378-a4972ea461e6";
  private static final String UNREAD_SHOP = "01a0f1a0-611e-703c-a378-a4972ea461e7";
  private static final String VARIANT = "01a0f1a0-611e-7037-a4b7-c854f0266ae1";
  private static final String CASHIER = "01a0f1a0-611e-7000-8000-0000000000a1";
  private static final String OWNER = "01a0f1a0-611e-7000-8000-0000000000a2";
  private static final String SHOPPER = "01a0f1a0-611e-700b-bde4-50df0324c3e1";

  private static final PostgresSupport PG;
  private static final JsonStub TENANTS;

  static {
    PG = PostgresSupport.start();
    TENANTS = JsonStub.start("tenant-svc");
    TENANTS.on(
        "GET",
        "/admin/tenant",
        call -> {
          if (T.equals(call.tenantId())) return profile(call.tenantId(), "INR", "IN");
          if (T2.equals(call.tenantId())) return profile(call.tenantId(), "EUR", "FR");
          return new JsonStub.Answer(500, "{\"error\":{\"code\":\"DOWN\"}}");
        });
    TENANTS.on(
        "GET",
        "/admin/stores",
        call -> {
          String stores;
          if (T.equals(call.tenantId())) {
            stores =
                String.join(
                    ",",
                    store(REQUIRED_SHOP, "IN", "REQUIRED"),
                    store(OPTIONAL_SHOP, "IN", "OPTIONAL"),
                    store(OFF_SHOP, "IN", "OFF"),
                    store(GB_SHOP, "GB", "OPTIONAL"));
          } else if (T2.equals(call.tenantId())) {
            stores =
                String.join(
                    ",", store(FR_SHOP, "FR", "REQUIRED"), store(NL_SHOP, "NL", "OPTIONAL"));
          } else {
            return new JsonStub.Answer(500, "{\"error\":{\"code\":\"DOWN\"}}");
          }
          return new JsonStub.Answer(
              200, "{\"data\":[" + stores + "],\"meta\":{\"nextCursor\":null}}");
        });
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "false");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  private static JsonStub.Answer profile(String tenant, String currency, String country) {
    return JsonStub.Answer.ok(
        "{\"id\":\""
            + tenant
            + "\",\"currency\":\""
            + currency
            + "\",\"country\":\""
            + country
            + "\"}");
  }

  private static String store(String id, String country, String tillPhone) {
    return "{\"id\":\""
        + id
        + "\",\"type\":\"STORE\",\"country\":\""
        + country
        + "\",\"timezone\":\"UTC\",\"tillPhone\":\""
        + tillPhone
        + "\"}";
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    TENANTS.close();
    PG.stop();
  }

  // ── harness ──────────────────────────────────────────────────────────────────

  private Invocation.Builder as(String tenant, String user, String roles, String storeIds) {
    Invocation.Builder b =
        WebTargets.at(target, "/orders")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", user)
            .header("X-Roles", roles)
            .header("Idempotency-Key", Ids.newId().toString());
    return storeIds == null ? b : b.header("X-Store-Ids", storeIds);
  }

  private static String sale(String storeId, String channel, String extra) {
    return "{\"storeId\":\""
        + storeId
        + "\",\"channel\":\""
        + channel
        + "\",\"fulfilmentType\":\""
        + ("POS".equals(channel) ? "INSTORE" : "PICKUP")
        + "\",\"items\":[{\"variantId\":\""
        + VARIANT
        + "\",\"qty\":1,\"unitPrice\":5.00}]"
        + extra
        + "}";
  }

  private static String phone(String number) {
    return ",\"contactPhone\":\"" + number + "\"";
  }

  /** A till sale rung up by a cashier at {@code storeId}. */
  private Response tillSale(String tenant, String storeId, String extra) {
    return as(tenant, CASHIER, "CASHIER", storeId)
        .post(Entity.entity(sale(storeId, "POS", extra), MediaType.APPLICATION_JSON));
  }

  /** An online collection order placed by a signed-in shopper. */
  private Response online(String tenant, String storeId, String extra) {
    return as(tenant, SHOPPER, "CUSTOMER", null)
        .post(Entity.entity(sale(storeId, "ONLINE", extra), MediaType.APPLICATION_JSON));
  }

  private static JsonObject placed(Response r) {
    return Envelopes.created(r);
  }

  private static String refused(Response r, int status) {
    return Envelopes.parse(Envelopes.bodyOf(r, status)).getString("code");
  }

  private static String ordersAt(String tenant, String storeId) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM \"order\".orders WHERE tenant_id = '"
            + tenant
            + "' AND store_id = '"
            + storeId
            + "'");
  }

  /** order-svc's store-status projection, as tenant-svc's StoreStatusChanged would leave it. */
  private static void storeBelongsTo(String storeId, String tenant) {
    Envelopes.exec(
        PG,
        "INSERT INTO \"order\".store_status (store_id, tenant_id, status) VALUES ('"
            + storeId
            + "', '"
            + tenant
            + "', 'ACTIVE') ON CONFLICT (store_id) DO NOTHING");
  }

  /**
   * order-svc's own record of a business's currency, as TenantCreated leaves it: what a sale is
   * priced in when tenant-svc cannot be asked.
   */
  private static void currencyKnownFor(String tenant, String currency) {
    Envelopes.exec(
        PG,
        "INSERT INTO \"order\".tenant_status (tenant_id, status, currency) VALUES ('"
            + tenant
            + "', 'ACTIVE', '"
            + currency
            + "') ON CONFLICT (tenant_id) DO NOTHING");
  }

  // ── the store's choice ──────────────────────────────────────────────────────

  @Test
  @DisplayName("A Required shop refuses a till sale with neither a number nor a customer")
  void aRequiredShopRefusesATillSaleWithNeither() {
    String before = ordersAt(T, REQUIRED_SHOP);
    assertThat(refused(tillSale(T, REQUIRED_SHOP, ""), 409), is("ORDER_CONTACT_PHONE_REQUIRED"));
    assertThat(
        refused(tillSale(T, REQUIRED_SHOP, phone("  ")), 409), is("ORDER_CONTACT_PHONE_REQUIRED"));
    assertThat("nothing was placed", ordersAt(T, REQUIRED_SHOP), is(before));
  }

  @Test
  @DisplayName("A Required shop takes a number, kept as typed and in international form")
  void aRequiredShopTakesANumber() {
    JsonObject order = placed(tillSale(T, REQUIRED_SHOP, phone("98860 21001")));
    assertThat(order.getString("contactPhone"), is("98860 21001"));
    assertThat(order.getString("contactPhoneE164"), is("+919886021001"));
  }

  @Test
  @DisplayName("A Required shop takes a customer instead of a number")
  void aRequiredShopTakesACustomerInstead() {
    JsonObject order =
        placed(tillSale(T, REQUIRED_SHOP, ",\"customerId\":\"" + Ids.newId() + "\""));
    assertThat(order.containsKey("contactPhone"), is(false));
  }

  @Test
  @DisplayName("Optional and Don't-ask shops take a till sale with no number")
  void optionalAndOffShopsTakeNoNumber() {
    assertThat(placed(tillSale(T, OPTIONAL_SHOP, "")).containsKey("contactPhoneE164"), is(false));
    assertThat(placed(tillSale(T, OFF_SHOP, "")).containsKey("contactPhone"), is(false));
  }

  // ── reading the number ──────────────────────────────────────────────────────

  @Test
  @DisplayName("A number that is no phone where the business trades: refused at the till only")
  void aNumberThatIsNoPhoneIsRefusedAtTheTillOnly() {
    String before = ordersAt(T, OPTIONAL_SHOP);
    assertThat(
        refused(tillSale(T, OPTIONAL_SHOP, phone("12345")), 400),
        is("ORDER_CONTACT_PHONE_INVALID"));
    assertThat("nothing was placed", ordersAt(T, OPTIONAL_SHOP), is(before));
    JsonObject kept = placed(online(T, OPTIONAL_SHOP, phone("12345")));
    assertThat("online it is kept as typed", kept.getString("contactPhone"), is("12345"));
    assertThat(kept.containsKey("contactPhoneE164"), is(false));
  }

  @Test
  @DisplayName("A shopper's number is kept in international form too")
  void aShoppersNumberIsKeptInInternationalForm() {
    assertThat(
        placed(online(T, OPTIONAL_SHOP, phone("98450 12345"))).getString("contactPhoneE164"),
        is("+919845012345"));
  }

  @Test
  @DisplayName("A number is read in the country of the shop it is given at")
  void aNumberIsReadInTheShopsOwnCountry() {
    assertThat(
        "an Indian business's British shop",
        placed(tillSale(T, GB_SHOP, phone("07400 123456"))).getString("contactPhoneE164"),
        is("+447400123456"));
    assertThat(
        "a French business's Dutch shop: the same digits are Dutch there",
        placed(tillSale(T2, NL_SHOP, phone("06 12 34 56 78"))).getString("contactPhoneE164"),
        is("+31612345678"));
    assertThat(
        "and French at its French shop",
        placed(tillSale(T2, FR_SHOP, phone("06 12 34 56 78"))).getString("contactPhoneE164"),
        is("+33612345678"));
  }

  // ── what cannot be read, and who is never reached ───────────────────────────

  @Test
  @DisplayName("A business tenant-svc cannot answer about is refused nothing, its number kept")
  void whatCannotBeReadRefusesNothing() {
    currencyKnownFor(T3, "INR");
    assertThat(placed(tillSale(T3, UNREAD_SHOP, "")).containsKey("contactPhone"), is(false));
    JsonObject typed = placed(tillSale(T3, UNREAD_SHOP, phone("98860 21001")));
    assertThat(typed.getString("contactPhone"), is("98860 21001"));
    assertThat(
        "not read, never refused for want of a country",
        typed.containsKey("contactPhoneE164"),
        is(false));
  }

  @Test
  @DisplayName("Another business naming this one's Required shop is refused as always")
  void anotherBusinessNeverReachesThisOnesShop() {
    storeBelongsTo(REQUIRED_SHOP, T);
    String before = ordersAt(T2, REQUIRED_SHOP);
    for (String roles : new String[] {"OWNER", "MANAGER", "CASHIER"}) {
      Response r =
          as(T2, OWNER, roles, REQUIRED_SHOP)
              .post(
                  Entity.entity(
                      sale(REQUIRED_SHOP, "POS", phone("06 12 34 56 78")),
                      MediaType.APPLICATION_JSON));
      assertThat(roles, refused(r, 409), is("STORE_NOT_OPERATIONAL"));
    }
    assertThat(
        "nor online",
        refused(online(T2, REQUIRED_SHOP, phone("06 12 34 56 78")), 409),
        is("STORE_NOT_OPERATIONAL"));
    assertThat("nothing moved", ordersAt(T2, REQUIRED_SHOP), is(before));
  }
}
