package com.storeql.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.StoreQlArchRules;
import com.storeql.test.TenantSvcStub;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for customer-svc: register, lookup, loyalty earn/redeem/tier-up, store credit
 * issue/redeem, insufficient-balance 422, GDPR anonymise. Tests run against real Postgres
 * (Testcontainers); Kafka and Consul disabled.
 */
@HelidonTest
class CustomerIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    TenantSvcStub.start().with(CustomerIT.TENANT, "JPY", "JP");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "customer");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String TENANT = "01a090ae-611e-703c-a378-a4972ea461c8";

  @Inject WebTarget target;

  // Kafka is disabled in-test, so drive the loyalty accrual path directly (as the consumer would).
  @Inject com.storeql.customer.service.CustomerService loyalty;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @Test
  void registerAndGetCustomer() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"alice@example.com\",\"firstName\":\"Alice\",\"lastName\":\"Smith\","
                + "\"gdprConsent\":true}");
    assertThat(r.getStatus(), is(201));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("alice@example.com"));
    String id = field(body, "id");

    String getBody =
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(getBody, containsString("alice@example.com"));
  }

  @Test
  void duplicateEmailReturns409() {
    String json = "{\"email\":\"bob@example.com\",\"firstName\":\"Bob\",\"lastName\":\"Jones\"}";
    assertThat(post("/customers", json).getStatus(), is(201));
    Response again = post("/customers", json);
    String body = again.readEntity(String.class);
    assertThat(body, again.getStatus(), is(409));
    assertThat(body, containsString("CUSTOMER_ALREADY_EXISTS"));

    // The same address in capitals is the same address.
    Response shouted =
        post(
            "/customers",
            "{\"email\":\"BOB@example.com\",\"firstName\":\"Bob\",\"lastName\":\"Jones\"}");
    String shoutedBody = shouted.readEntity(String.class);
    assertThat(shoutedBody, shouted.getStatus(), is(409));
    assertThat(shoutedBody, containsString("CUSTOMER_ALREADY_EXISTS"));
  }

  @Test
  void lookupByEmail() {
    post(
        "/customers",
        "{\"email\":\"carol@example.com\",\"firstName\":\"Carol\",\"lastName\":\"White\"}");
    String result =
        target
            .path("/customers/lookup")
            .queryParam("email", "carol@example.com")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(result, containsString("carol@example.com"));
  }

  @Test
  void loyaltyEarnTierUpAndRedeem() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"dave@example.com\",\"firstName\":\"Dave\",\"lastName\":\"Brown\"}");
    String id = field(r.readEntity(String.class), "id");

    // earn 500 → BRONZE
    String earn1 =
        post("/customers/" + id + "/loyalty/earn", "{\"points\":500,\"reason\":\"purchase\"}")
            .readEntity(String.class);
    assertThat(earn1, containsString("BRONZE"));

    // earn 600 more → 1100 lifetime → SILVER
    String earn2 =
        post("/customers/" + id + "/loyalty/earn", "{\"points\":600,\"reason\":\"purchase\"}")
            .readEntity(String.class);
    assertThat(earn2, containsString("SILVER"));

    // redeem 100
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "{\"points\":100,\"reason\":\"discount\"}")
            .getStatus(),
        is(200));

    // ledger has 3 entries
    String ledger =
        target
            .path("/customers/" + id + "/loyalty/ledger")
            .queryParam("limit", 10)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(ledger, containsString("EARN"));
    assertThat(ledger, containsString("REDEEM"));
  }

  @Test
  void redeemMoreThanBalanceReturns422() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"eve@example.com\",\"firstName\":\"Eve\",\"lastName\":\"Green\"}");
    String id = field(r.readEntity(String.class), "id");
    assertThat(
        post("/customers/" + id + "/loyalty/redeem", "{\"points\":9999,\"reason\":\"test\"}")
            .getStatus(),
        is(422));
  }

  @Test
  void storeCreditIssueAndRedeem() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"frank@example.com\",\"firstName\":\"Frank\",\"lastName\":\"Black\"}");
    String id = field(r.readEntity(String.class), "id");

    assertThat(
        post(
                "/customers/" + id + "/store-credit/issue",
                "{\"amount\":50.00,\"reason\":\"return refund\"}")
            .getStatus(),
        is(200));

    assertThat(
        post(
                "/customers/" + id + "/store-credit/redeem",
                "{\"amount\":20.00,\"reason\":\"purchase\"}")
            .getStatus(),
        is(200));

    Response over =
        post(
            "/customers/" + id + "/store-credit/redeem",
            "{\"amount\":999.00,\"reason\":\"over-limit\"}");
    String overBody = over.readEntity(String.class);
    assertThat(overBody, over.getStatus(), is(422));
    assertThat(overBody, containsString("STORE_CREDIT_INSUFFICIENT"));
    assertThat(
        "the refused redeem took nothing",
        creditBalance(id).compareTo(new java.math.BigDecimal("30")),
        is(0));
  }

  @Test
  void storeCreditConcurrentRedeemNeverDoubleSpends() throws Exception {
    Response r =
        post(
            "/customers",
            "{\"email\":\"grace@example.com\",\"firstName\":\"Grace\",\"lastName\":\"Hopper\"}");
    String id = field(r.readEntity(String.class), "id");

    // Fund exactly 100; then fire N concurrent redeems each draining the whole balance.
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "{\"amount\":100.00,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));

    int threads = 8;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var ready = new java.util.concurrent.CountDownLatch(threads);
    var go = new java.util.concurrent.CountDownLatch(1);
    var ok = new java.util.concurrent.atomic.AtomicInteger();
    var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                int status =
                    post(
                            "/customers/" + id + "/store-credit/redeem",
                            "{\"amount\":100.00,\"reason\":\"race\"}")
                        .getStatus();
                if (status == 200) ok.incrementAndGet();
                return status;
              }));
    }
    ready.await();
    go.countDown(); // release all at once
    for (var f : futures) f.get();
    pool.shutdown();

    // With FOR UPDATE row locking, exactly one redeem of the full balance can win; the rest see a
    // zero balance and get 422. Without the lock this would allow multiple winners (double-spend).
    assertThat("only one full-balance redeem may succeed", ok.get(), is(1));

    String credit =
        target
            .path("/customers/" + id + "/store-credit")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(credit, containsString("\"balance\":0"));
  }

  @Test
  void registerWithJsonBreakingEmailDoesNotCorruptEvent() {
    // A quoted-local-part email contains a double-quote that would break a string-concatenated JSON
    // payload (and used to be able to inject into the outbox event). The event is now built with a
    // JSON writer, so the only valid outcomes are: 201 (accepted and serialised safely) or 400
    // (bean-validation rejected the address up front) — never a 500 from a corrupted payload.
    int status =
        post(
                "/customers",
                "{\"email\":\"\\\"weird\\\"@example.com\",\"firstName\":\"Q\",\"lastName\":\"Q\"}")
            .getStatus();
    assertThat(status, org.hamcrest.Matchers.anyOf(is(201), is(400)));
  }

  @Test
  void tenantIsolation() {
    post(
        "/customers",
        "{\"email\":\"shared@example.com\",\"firstName\":\"Shared\",\"lastName\":\"User\"}");
    String listOtherTenant =
        target
            .path("/customers")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", "01a090ae-611e-7056-8f30-ecdbb48160eb")
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(listOtherTenant, not(containsString("shared@example.com")));
  }

  @Test
  void updateAfterAnonymizeIsRejectedNotResurrected() {
    Response created =
        post(
            "/customers",
            "{\"email\":\"helen@example.com\",\"firstName\":\"Helen\",\"lastName\":\"Lee\"}");
    assertThat(created.getStatus(), is(201));
    String id = field(created.readEntity(String.class), "id");

    Response anonymized = delete("/customers/" + id);
    assertThat(anonymized.getStatus(), is(204));

    // A profile update after anonymize must be rejected, not silently resurrect the erased PII.
    Response updated =
        put("/customers/" + id, "{\"firstName\":\"Resurrected\",\"lastName\":\"Person\"}");
    assertThat(updated.getStatus(), is(409));
    assertThat(updated.readEntity(String.class), containsString("CUSTOMER_ANONYMIZED"));

    String getBody =
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(getBody, not(containsString("Resurrected")));
    assertThat(getBody, containsString("ANONYMIZED"));
  }

  @Test
  void anonymizeRequiresManagementRole() {
    Response created =
        post(
            "/customers",
            "{\"email\":\"ivy@example.com\",\"firstName\":\"Ivy\",\"lastName\":\"Nguyen\"}");
    assertThat(created.getStatus(), is(201));
    String id = field(created.readEntity(String.class), "id");

    // GDPR erasure is destructive — unlike loyalty/store-credit redemption, a CASHIER must not be
    // able to perform it just by virtue of holding any staff role.
    Response asCashier =
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "CASHIER")
            .delete();
    assertThat(asCashier.getStatus(), is(403));
  }

  @Test
  void loyaltyAccruesFromOrderOnceAndDedupesOnEventId() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"jill@example.com\",\"firstName\":\"Jill\",\"lastName\":\"Reed\"}");
    String id = field(r.readEntity(String.class), "id");
    java.util.UUID tenant = Ids.parse(TENANT);
    java.util.UUID customerId = Ids.parse(id);
    java.util.UUID eventA = com.storeql.ids.Ids.newId();

    // Order A: £40 spent → 40 points at the default 1-point-per-unit rate.
    loyalty.accrueLoyaltyFromOrder(
        eventA,
        tenant,
        customerId,
        com.storeql.ids.Ids.newId(),
        new java.math.BigDecimal("40.00"),
        java.math.BigDecimal.ZERO);
    // Redelivery of the SAME event must not accrue again (dedupe on eventId).
    loyalty.accrueLoyaltyFromOrder(
        eventA,
        tenant,
        customerId,
        com.storeql.ids.Ids.newId(),
        new java.math.BigDecimal("40.00"),
        java.math.BigDecimal.ZERO);
    // A genuinely different order (new eventId) accrues normally → 50.
    loyalty.accrueLoyaltyFromOrder(
        com.storeql.ids.Ids.newId(),
        tenant,
        customerId,
        com.storeql.ids.Ids.newId(),
        new java.math.BigDecimal("10.00"),
        java.math.BigDecimal.ZERO);

    String acct =
        target
            .path("/customers/" + id + "/loyalty")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    // 40 (once, not twice) + 10 = 50. Double-accrual would show 90.
    assertThat(acct, containsString("\"pointsBalance\":50.00"));
    assertThat(acct, not(containsString("\"pointsBalance\":90")));
  }

  @Test
  void storeCreditRedeemIsIdempotentPerOrder() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"kate@example.com\",\"firstName\":\"Kate\",\"lastName\":\"Ng\"}");
    String id = field(r.readEntity(String.class), "id");
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "{\"amount\":100.00,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));

    // payment-svc may retry the same store-credit tender for an order; keyed on orderId, the second
    // redeem must be a no-op (not a second deduction).
    String order = com.storeql.ids.Ids.newId().toString();
    String body = "{\"amount\":30.00,\"orderId\":\"" + order + "\",\"reason\":\"tender\"}";
    assertThat(post("/customers/" + id + "/store-credit/redeem", body).getStatus(), is(200));
    assertThat(post("/customers/" + id + "/store-credit/redeem", body).getStatus(), is(200));

    String credit =
        target
            .path("/customers/" + id + "/store-credit")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    // 100 − 30 (once, not twice) = 70. A double redeem would show 40.
    assertThat(credit, containsString("\"balance\":70.00"));
    assertThat(credit, not(containsString("\"balance\":40")));
  }

  /**
   * Customer records are staff-only over HTTP since SJ-D10 made reads default-deny.
   *
   * <p>{@code CustomerService.requireReadAccess} is still correct and still enforced — staff read
   * anyone in their tenant, and it 404s rather than 403s so ids cannot be probed for existence.
   * What changed is that its other two branches are no longer reachable from outside the mesh:
   *
   * <ul>
   *   <li>Its <b>customer-reads-their-own-record</b> branch has no caller. The storefront has no
   *       account self-service screen, so allowing {@code /customers/&#123;id&#125;} through the
   *       filter would widen the surface for nobody. When that screen is built, the fix is to
   *       allowlist the shape in the filter — not to loosen this check, which is already right.
   *   <li>Its <b>no-principal means service-to-service</b> branch rested on the gateway never
   *       forwarding an anonymous request here. That was an assumption, and it was wrong: an
   *       anonymous caller reached these reads. It is now enforced rather than assumed, which is
   *       why {@link com.storeql.notification.client.CustomerClient} and payment-svc's {@code
   *       CustomerClient} stamp a staff role on their internal lookups.
   * </ul>
   */
  @Test
  void customerReadsAreStaffOnlyAndStillObjectLevelAuthorizedForStaff() {
    Response created =
        post(
            "/customers",
            "{\"email\":\"liam@example.com\",\"firstName\":\"Liam\",\"lastName\":\"Ortiz\"}");
    assertThat(created.getStatus(), is(201));
    String id = field(created.readEntity(String.class), "id");

    assertThat(
        post("/customers/" + id + "/loyalty/earn", "{\"points\":10,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));
    assertThat(
        post("/customers/" + id + "/store-credit/issue", "{\"amount\":5.00,\"reason\":\"seed\"}")
            .getStatus(),
        is(200));

    // A CUSTOMER token is refused at the filter, before the record is looked up at all — including
    // for the caller's own record, which no client currently asks for.
    for (String path :
        new String[] {
          "", "/addresses", "/loyalty", "/loyalty/ledger", "/store-credit",
        }) {
      assertThat(
          "own record, customer token: " + path,
          getAs("/customers/" + id + path, id, "CUSTOMER").getStatus(),
          is(403));
      assertThat(
          "another customer's record: " + path,
          getAs("/customers/" + id + path, Ids.newId().toString(), "CUSTOMER").getStatus(),
          is(403));
    }

    // The branch that actually leaked: no principal at all. This used to be served, on the
    // assumption that only the service mesh could produce the shape.
    assertThat(
        target.path("/customers/" + id).request().header("X-Tenant-Id", TENANT).get().getStatus(),
        is(403));

    // Staff read any customer in their tenant — the object-level check's staff branch, still live.
    assertThat(
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "CASHIER")
            .get()
            .getStatus(),
        is(200));

    // Which is exactly how the internal clients now reach it: a stamped staff role, not anonymity.
    assertThat(
        target
            .path("/customers/" + id)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "CASHIER")
            .get()
            .readEntity(String.class),
        containsString("liam@example.com"));
  }

  @Test
  void archRules() {
    var classes = new ClassFileImporter().importPackages("com.storeql.customer");
    StoreQlArchRules.API_DOES_NOT_CALL_REPO.check(classes);
    StoreQlArchRules.DTOS_DO_NOT_EXPOSE_DOMAIN.check(classes);
  }

  @Test
  @org.junit.jupiter.api.DisplayName("A lookup naming neither an email nor a phone is refused")
  void aLookupNamingNeitherEmailNorPhoneIsRefused() {
    for (String[] query : new String[][] {{}, {"email", ""}, {"phone", ""}, {"email", "  "}}) {
      WebTarget t = target.path("/customers/lookup");
      for (int i = 0; i < query.length; i += 2) t = t.queryParam(query[i], query[i + 1]);
      Response r =
          t.request(MediaType.APPLICATION_JSON)
              .header("X-Tenant-Id", TENANT)
              .header("X-Roles", "OWNER")
              .get();
      assertRefusal("lookup " + String.join("=", query), r, 400, "LOOKUP_PARAM_REQUIRED");
    }
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "Another business's staff and a shopper cannot replace a customer's address")
  void anAddressIsReplacedOnlyByThisBusinessesStaffForThisCustomer() {
    String ana = newCustomer("addr-put-a");
    String ben = newCustomer("addr-put-b");
    String home = newAddress(ana, "1 Original Road");
    newAddress(ben, "9 Ben Street");
    String replacement = "{\"type\":\"WORK\",\"line1\":\"2 Changed Road\",\"country\":\"JP\"}";
    String path = "/customers/" + ana + "/addresses/" + home;
    String stranger = Ids.newId().toString();

    for (String role : STAFF) {
      assertRefusal(
          "another business's " + role,
          as("PUT", path, stranger, role, replacement),
          404,
          "ADDRESS_NOT_FOUND");
    }
    assertRefusal(
        "an address of another customer of this business",
        put("/customers/" + ben + "/addresses/" + home, replacement),
        404,
        "ADDRESS_NOT_FOUND");
    assertRefusal(
        "an address that is nobody's",
        put("/customers/" + ana + "/addresses/" + Ids.newId(), replacement),
        404,
        "ADDRESS_NOT_FOUND");
    assertRefusal("a shopper", as("PUT", path, TENANT, "CUSTOMER", replacement), 403, null);
    assertRefusal(
        "no line1", put(path, "{\"type\":\"WORK\",\"country\":\"JP\"}"), 400, "VALIDATION_FAILED");

    String book = addressBook(ana);
    assertThat(book, containsString("1 Original Road"));
    assertThat(book, not(containsString("2 Changed Road")));
    assertThat(addressBook(ben), containsString("9 Ben Street"));
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "Another business's staff and a shopper cannot remove a customer's address")
  void anAddressIsRemovedOnlyByThisBusinessesStaffForThisCustomer() {
    String ana = newCustomer("addr-del-a");
    String ben = newCustomer("addr-del-b");
    String home = newAddress(ana, "1 Keep Road");
    String path = "/customers/" + ana + "/addresses/" + home;
    String stranger = Ids.newId().toString();

    for (String role : STAFF) {
      assertRefusal(
          "another business's " + role,
          as("DELETE", path, stranger, role, null),
          404,
          "ADDRESS_NOT_FOUND");
    }
    assertRefusal(
        "an address of another customer of this business",
        delete("/customers/" + ben + "/addresses/" + home),
        404,
        "ADDRESS_NOT_FOUND");
    assertRefusal("a shopper", as("DELETE", path, TENANT, "CUSTOMER", null), 403, null);
    assertThat("nothing was removed", addressBook(ana), containsString("1 Keep Road"));

    assertThat(delete(path).getStatus(), is(204));
    assertRefusal("deleting twice", delete(path), 404, "ADDRESS_NOT_FOUND");
    assertThat(addressBook(ana), not(containsString("1 Keep Road")));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Response post(String path, String json) {
    // A manual award or adjustment needs the key of its attempt; every call here is a new attempt.
    boolean needsKey =
        path.endsWith("/loyalty/earn")
            || path.endsWith("/loyalty/adjust")
            || path.endsWith("/store-credit/issue");
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", needsKey ? Ids.newId().toString() : null)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response delete(String path) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-Roles", "OWNER")
        .delete();
  }

  private Response getAs(String path, String userId, String roles) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-User-Id", userId)
        .header("X-Roles", roles)
        .get();
  }

  private java.math.BigDecimal creditBalance(String customerId) {
    String body =
        target
            .path("/customers/" + customerId + "/store-credit")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "OWNER")
            .get(String.class);
    return jakarta.json.Json.createReader(new java.io.StringReader(body))
        .readObject()
        .getJsonObject("data")
        .getJsonNumber("balance")
        .bigDecimalValue();
  }

  /** A request as any business's person of any role; the tenant is whatever the token says. */
  private Response as(String method, String path, String tenant, String role, String json) {
    var b =
        target
            .path(path)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role);
    return switch (method) {
      case "GET" -> b.get();
      case "DELETE" -> b.delete();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  private String newCustomer(String label) {
    Response r =
        post(
            "/customers",
            "{\"email\":\""
                + label
                + "-"
                + Ids.newId()
                + "@example.com\",\"firstName\":\"A\",\"lastName\":\"B\"}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return field(body, "id");
  }

  private String newAddress(String customerId, String line1) {
    Response r =
        post(
            "/customers/" + customerId + "/addresses",
            "{\"type\":\"HOME\",\"line1\":\"" + line1 + "\",\"country\":\"JP\"}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return field(body, "id");
  }

  private String addressBook(String customerId) {
    return target
        .path("/customers/" + customerId + "/addresses")
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", TENANT)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  private static void assertRefusal(String what, Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(what + ": " + body, r.getStatus(), is(status));
    if (code != null) assertThat(what + ": " + body, body, containsString(code));
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in: " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  // ── SJ-D53: store credit in the tenant's own currency ────────────────────────

  @Test
  @org.junit.jupiter.api.DisplayName(
      "Store credit without a currency is the tenant's yen; an undescribed tenant is refused")
  void storeCreditIsInTheTenantsOwnCurrency() {
    Response r =
        post(
            "/customers",
            "{\"email\":\"yuki@example.com\",\"firstName\":\"Yuki\",\"lastName\":\"Sato\"}");
    String id = field(r.readEntity(String.class), "id");
    Response issued =
        post("/customers/" + id + "/store-credit/issue", "{\"amount\":500,\"reason\":\"refund\"}");
    String body = issued.readEntity(String.class);
    assertThat(body, issued.getStatus(), is(200));
    assertThat(body, containsString("\"currency\":\"JPY\""));
    assertThat(body, not(containsString("GBP")));
    String read =
        target
            .path("/customers/" + id + "/store-credit")
            .request()
            .header("X-Tenant-Id", TENANT)
            .header("X-Roles", "MANAGER")
            .get()
            .readEntity(String.class);
    assertThat(read, containsString("\"currency\":\"JPY\""));

    String nobody = "01a090ae-611e-70f0-8a00-0000000000b9";
    Response created =
        target
            .path("/customers")
            .request()
            .header("X-Tenant-Id", nobody)
            .header("X-Roles", "MANAGER")
            .post(
                jakarta.ws.rs.client.Entity.json(
                    "{\"email\":\"ghost@example.com\",\"firstName\":\"G\",\"lastName\":\"H\"}"));
    String ghost = field(created.readEntity(String.class), "id");
    Response refused =
        target
            .path("/customers/" + ghost + "/store-credit/issue")
            .request()
            .header("Idempotency-Key", Ids.newId().toString())
            .header("X-Tenant-Id", nobody)
            .header("X-Roles", "MANAGER")
            .post(jakarta.ws.rs.client.Entity.json("{\"amount\":5,\"reason\":\"x\"}"));
    String refusedBody = refused.readEntity(String.class);
    assertThat(refusedBody, refused.getStatus(), is(503));
    assertThat(refusedBody, containsString("TENANT_PROFILE_UNAVAILABLE"));
  }
}
