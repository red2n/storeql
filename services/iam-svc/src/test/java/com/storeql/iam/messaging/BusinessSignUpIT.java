package com.storeql.iam.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Start a business" (29 Sep 2026): a sign-up of its own for the person who is about to set a
 * business up, beside the shopper's. {@code POST /auth/register/business} makes a STAFF login with
 * no tenant and no role and signs it in — the app reads exactly that as "open the setup wizard" —
 * and the business's {@code TenantCreated} then makes the same login its OWNER. {@code POST
 * /auth/register} still makes a shopper.
 *
 * <p>A shopper's account and a business account are separate identities (29 Sep 2026, as at
 * Shopify, Square and Stripe): one address may hold one of each outside any business, a sign-in
 * says which it means ({@code accountType}), and provisioning staff by email never takes either
 * over — it makes the business a login of its own. That is the only way a login becomes a
 * business's staff: {@code StaffAssigned} binds a role only to a login already in the business and
 * stamps nobody in, and another business's login with the address refuses nothing.
 *
 * <p>In the messaging package so the real {@link TenantCreatedHandler} (package-private) is what
 * binds the owner, and the real {@link StaffAssignedHandler} what takes staff on, not stand-ins for
 * them. Requests carry the identity headers the gateway stamps from a verified token; Kafka and
 * Consul are off.
 */
@HelidonTest
class BusinessSignUpIT {

  private static final PostgresSupport PG;
  private static final String PASSWORD = "a phrase long enough";
  private static final String BUSINESS = "/auth/register/business";
  private static final String SHOPPER = "/auth/register";

  /** A shopper's password, and a member of staff's, apart from the founder's {@link #PASSWORD}. */
  private static final String SHOPPING = "another phrase for the shop";

  private static final String WORKING = "a third phrase for the work";

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.jwt.secret", "integration-test-secret-of-at-least-32-chars");
  }

  @Inject WebTarget target;
  @Inject TenantCreatedHandler tenants;
  @Inject StaffAssignedHandler staff;
  @Inject StaffRemovedHandler letGo;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── the sign-up ────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A business sign-up is a staff login with no business and no role, signed in at once, and"
          + " announced as UserRegistered type STAFF")
  void aBusinessSignUpHoldsNothingYet() {
    String email = "founder-" + suffix() + "@example.com";
    Answer a = signUp(BUSINESS, email, PASSWORD, Caller.NOBODY);
    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(a.data().getString("tokenType"), is("Bearer"));
    assertThat(a.data().getString("refreshToken").isBlank(), is(false));

    DecodedJWT token = token(a);
    UUID id = Ids.parse(token.getSubject());
    assertThat(token.getClaim("type").asString(), is("STAFF"));
    assertThat("no business yet", token.getClaim("tenant").isMissing(), is(true));
    assertThat("no role yet", token.getClaim("roles").asList(String.class), is(empty()));
    assertThat(token.getClaim("email").asString(), is(email));
    assertThat(token.getClaim("amr").asList(String.class), contains("pwd"));

    // The row: staff, no tenant, not a single role — not even CUSTOMER.
    assertThat(userColumn(id, "type"), is("STAFF"));
    assertThat(userColumn(id, "tenant_id"), is(nullValue()));
    assertThat(roleCount(id), is("0"));

    // Announced on the login's own transaction, as a shopper's sign-up is: type STAFF, no tenant.
    assertThat(registeredCount(id), is("1"));
    JsonObject event = registeredEvent(id);
    assertThat(event.getString("type"), is("STAFF"));
    assertThat(event.isNull("tenantId"), is(true));
    assertThat(event.getString("email"), is(email));
    assertThat(event.getString("aggregateId"), is(id.toString()));
    assertThat(auditCount(id, "BUSINESS_SIGNED_UP"), is("1"));

    // Who-am-I, as the gateway forwards it: a staff login of no business and no role.
    Answer me = call("GET", "/auth/me", new Caller(id, null, null), null);
    assertThat(me.body().toString(), me.status(), is(200));
    assertThat(me.data().getString("type"), is("STAFF"));
    assertThat(!me.data().containsKey("tenantId") || me.data().isNull("tenantId"), is(true));
    assertThat(me.data().getJsonArray("roles").isEmpty(), is(true));
  }

  @Test
  @DisplayName("A shopper's sign-up is unchanged: a CUSTOMER with no business")
  void theShoppersSignUpIsUnchanged() {
    String email = "shopper-" + suffix() + "@example.com";
    Answer a = signUp(SHOPPER, email, PASSWORD, Caller.NOBODY);
    assertThat(a.body().toString(), a.status(), is(201));
    DecodedJWT token = token(a);
    UUID id = Ids.parse(token.getSubject());
    assertThat(token.getClaim("type").asString(), is("CUSTOMER"));
    assertThat(token.getClaim("tenant").isMissing(), is(true));
    assertThat(token.getClaim("roles").asList(String.class), contains("CUSTOMER"));
    assertThat(userColumn(id, "type"), is("CUSTOMER"));
    assertThat(roleCount(id), is("1"));
    JsonObject event = registeredEvent(id);
    assertThat(event.getString("type"), is("CUSTOMER"));
    assertThat(event.isNull("tenantId"), is(true));
    assertThat(auditCount(id, "USER_REGISTERED"), is("1"));
  }

  @Test
  @DisplayName(
      "The shopper's rules hold: the password policy by its own codes, and an address already"
          + " signed up for a business is refused a second business sign-up — nothing written")
  void theSameRulesAsAShoppersSignUp() {
    String email = "rules-" + suffix() + "@example.com";

    Answer tooShortToRead = signUp(BUSINESS, email, "short", Caller.NOBODY);
    assertThat(tooShortToRead.status(), is(400));
    assertThat(tooShortToRead.code(), is("VALIDATION_FAILED"));
    Answer tooShort = signUp(BUSINESS, email, "fourteen chars", Caller.NOBODY);
    assertThat(tooShort.body().toString(), tooShort.status(), is(400));
    assertThat(tooShort.code(), is("PASSWORD_TOO_SHORT"));
    String local = email.substring(0, email.indexOf('@'));
    Answer identity = signUp(BUSINESS, email, local + " and some more words", Caller.NOBODY);
    assertThat(identity.status(), is(400));
    assertThat(identity.code(), is("PASSWORD_IS_IDENTITY"));
    Answer notAnEmail = signUp(BUSINESS, "not-an-email", PASSWORD, Caller.NOBODY);
    assertThat(notAnEmail.status(), is(400));
    assertThat(notAnEmail.code(), is("VALIDATION_FAILED"));
    assertThat("a refused sign-up leaves no login behind", emailCount(email), is("0"));

    assertThat(signUp(BUSINESS, email, PASSWORD, Caller.NOBODY).status(), is(201));
    Answer twice = signUp(BUSINESS, email, PASSWORD, Caller.NOBODY);
    assertThat(twice.status(), is(409));
    assertThat(twice.code(), is("USER_ALREADY_EXISTS"));
    String shouted = email.toUpperCase(Locale.ROOT);
    assertThat(signUp(BUSINESS, shouted, PASSWORD, Caller.NOBODY).status(), is(409));
    assertThat("one business sign-up per address", emailCount(email), is("1"));
  }

  // ── signing in again before the business exists ───────────────────────────

  @Test
  @DisplayName(
      "Signed in again, or refreshed, before the business exists: still no business and no role")
  void signingInAgainStillHoldsNothing() {
    String email = "returning-" + suffix() + "@example.com";
    Answer a = signUp(BUSINESS, email, PASSWORD, Caller.NOBODY);
    assertThat(a.status(), is(201));

    Answer login = login(email);
    assertThat(login.body().toString(), login.status(), is(200));
    DecodedJWT signedIn = token(login);
    assertThat(signedIn.getClaim("type").asString(), is("STAFF"));
    assertThat(signedIn.getClaim("tenant").isMissing(), is(true));
    assertThat(signedIn.getClaim("roles").asList(String.class), is(empty()));

    String refresh = "{\"refreshToken\":\"" + a.data().getString("refreshToken") + "\"}";
    Answer refreshed = call("POST", "/auth/refresh", Caller.NOBODY, refresh);
    assertThat(refreshed.body().toString(), refreshed.status(), is(200));
    DecodedJWT renewed = token(refreshed);
    assertThat(renewed.getClaim("tenant").isMissing(), is(true));
    assertThat(renewed.getClaim("roles").asList(String.class), is(empty()));
    assertThat(renewed.getSubject(), is(signedIn.getSubject()));
  }

  // ── the business is created ────────────────────────────────────────────────

  @Test
  @DisplayName(
      "TenantCreated makes the same login the business's OWNER — OWNER alone, business-wide —"
          + " once, however often it is delivered")
  void theBusinessMakesTheLoginItsOwner() {
    String email = "owner-" + suffix() + "@example.com";
    UUID id = signedUp(BUSINESS, email);
    UUID tenant = Ids.newId();

    String event = tenantCreated(Ids.newId(), tenant, id);
    tenants.handle(event);
    tenants.handle(event);

    Answer login = login(email);
    assertThat(login.body().toString(), login.status(), is(200));
    DecodedJWT token = token(login);
    assertThat(token.getSubject(), is(id.toString()));
    assertThat(token.getClaim("tenant").asString(), is(tenant.toString()));
    assertThat(token.getClaim("type").asString(), is("STAFF"));
    // OWNER and nothing else: never a shopper's role picked up on the way.
    assertThat(token.getClaim("roles").asList(String.class), contains("OWNER"));
    assertThat("an owner is held to no store", token.getClaim("storeIds").isMissing(), is(true));
    assertThat("an owner is never narrowed", token.getClaim("perms").isMissing(), is(true));
    assertThat(userColumn(id, "tenant_id"), is(tenant.toString()));
    assertThat("delivered twice, bound once", roleCount(id), is("1"));

    Answer me = call("GET", "/auth/me", new Caller(id, "OWNER", tenant), null);
    assertThat(me.status(), is(200));
    assertThat(me.data().getString("tenantId"), is(tenant.toString()));
    assertThat(me.data().getJsonArray("roles").getString(0), is("OWNER"));
  }

  @Test
  @DisplayName(
      "A login of another business — its cashier — is not made the owner of a business whose"
          + " TenantCreated names it")
  void aLoginOfAnotherBusinessIsNotMadeItsOwner() {
    Rival employer = rival();
    UUID id = employer.cashier();
    String email = userColumn(id, "email");

    String event = tenantCreated(Ids.newId(), Ids.newId(), id);
    tenants.handle(event);
    tenants.handle(event);

    DecodedJWT token = token(login(email));
    assertThat(token.getSubject(), is(id.toString()));
    assertThat(token.getClaim("tenant").asString(), is(employer.tenant().toString()));
    List<String> roles = token.getClaim("roles").asList(String.class);
    assertThat("still the employer's cashier, and nobody's owner", roles, contains("CASHIER"));
    assertThat(
        token.getClaim("storeIds").asList(String.class), contains(employer.store().toString()));
    assertThat(ownerRoleCount(id), is("0"));
    assertThat(auditCount(id, "OWNER_BIND_REFUSED"), is("1"));
  }

  @Test
  @DisplayName(
      "Nor can the business they start take their employed login on: its StaffAssigned — OWNER,"
          + " MANAGER, any tier, a role of its own, even naming the employer's store — leaves the"
          + " login as its employer made it; and a login of no business is not its to take on"
          + " either")
  void aLoginOfAnotherBusinessIsNotTakenOnByIt() {
    // The employer's cashier, provisioned and assigned there...
    Rival employerBusiness = rival();
    UUID employer = employerBusiness.tenant();
    UUID employerStore = employerBusiness.store();
    UUID employed = employerBusiness.cashier();
    String email = userColumn(employed, "email");
    // ...signs up a second login with the business sign-up and starts a business with it.
    UUID founder = signedUp(BUSINESS, "second-login-" + suffix() + "@example.com");
    UUID own = Ids.newId();
    UUID ownStore = Ids.newId();
    tenants.handle(tenantCreated(Ids.newId(), own, founder));
    String rolesBefore = roleCount(employed);

    // As that business's owner they assign the employed login every tier at their own store, and
    // then at the employer's store; each event delivered twice.
    List<String> events = new java.util.ArrayList<>();
    for (String tier : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER")) {
      events.add(staffAssigned(Ids.newId(), own, employed, ownStore, tier, null));
    }
    events.add(staffAssigned(Ids.newId(), own, employed, ownStore, "MANAGER", "BUYER"));
    events.add(staffAssigned(Ids.newId(), own, employed, employerStore, "OWNER", null));
    for (String event : events) {
      staff.handle(event);
      staff.handle(event);
    }

    DecodedJWT token = token(login(email));
    assertThat(token.getClaim("tenant").asString(), is(employer.toString()));
    assertThat(
        "still the employer's cashier, and nothing more",
        token.getClaim("roles").asList(String.class),
        contains("CASHIER"));
    assertThat(token.getClaim("storeIds").asList(String.class), contains(employerStore.toString()));
    assertThat(
        "no role of the business's own came with it",
        token.getClaim("perms").isMissing(),
        is(true));
    assertThat(userColumn(employed, "tenant_id"), is(employer.toString()));
    assertThat(roleCount(employed), is(rolesBefore));
    assertThat(ownerRoleCount(employed), is("0"));
    assertThat(storeRoleCount(employed, ownStore), is("0"));
    assertThat(
        "once per event, however often delivered",
        auditCount(employed, "STAFF_BIND_REFUSED"),
        is("6"));

    // A login of no business is not theirs to take on by id either: a shopper's account stays the
    // shopper's, exactly as it was.
    UUID shopper = signedUp(SHOPPER, "not-a-recruit-" + suffix() + "@example.com");
    String shopperBefore = rowsOf(shopper);
    staff.handle(staffAssigned(Ids.newId(), own, shopper, ownStore, "CASHIER", null));
    assertThat(rowsOf(shopper), is(shopperBefore));
    assertThat(userColumn(shopper, "tenant_id"), is(nullValue()));
    assertThat(userColumn(shopper, "type"), is("CUSTOMER"));
    assertThat(auditCount(shopper, "STAFF_BIND_REFUSED"), is("1"));
    // Their own staff are the logins they provision, and the founder is their own already.
    Caller boss = new Caller(founder, "OWNER", own);
    UUID recruit = provisioned(boss, "recruit-" + suffix() + "@example.com", WORKING);
    staff.handle(staffAssigned(Ids.newId(), own, recruit, ownStore, "CASHIER", null));
    assertThat(userColumn(recruit, "tenant_id"), is(own.toString()));
    assertThat(storeRoleCount(recruit, ownStore), is("1"));
    staff.handle(staffAssigned(Ids.newId(), own, founder, ownStore, "CASHIER", null));
    assertThat(storeRoleCount(founder, ownStore), is("1"));
    // And the employer can still give its own login a second store.
    UUID employerSecond = Ids.newId();
    staff.handle(
        staffAssigned(Ids.newId(), employer, employed, employerSecond, "STOREKEEPER", null));
    assertThat(storeRoleCount(employed, employerSecond), is("1"));
    assertThat(auditCount(employed, "STAFF_BIND_REFUSED"), is("6"));
  }

  // ── nobody else's business ─────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Before its business exists the login reaches nobody's: every management read and write is"
          + " refused, even naming another business, and nothing moves")
  void theNewLoginReachesNoBusiness() {
    Rival rival = rival();
    UUID id = signedUp(BUSINESS, "no-business-yet-" + suffix() + "@example.com");
    String poached = "poached-" + suffix() + "@example.com";
    String provision = "{\"email\":\"" + poached + "\",\"password\":\"" + PASSWORD + "\"}";
    String key = "{\"name\":\"mine\",\"role\":\"MANAGER\"}";
    String ids = rival.owner() + "," + rival.cashier();
    String rivalStaffBefore = rivalStaffCount(rival);

    // As the gateway forwards its token (no tenant, no role), and as if a tenant header naming the
    // rival had got through: there is still no role to act with.
    for (Caller who : List.of(new Caller(id, null, null), new Caller(id, null, rival.tenant()))) {
      assertThat(call("GET", "/auth/admin/staff-users?ids=" + ids, who, null).status(), is(403));
      assertThat(call("POST", "/auth/admin/staff-users", who, provision).status(), is(403));
      assertThat(call("GET", "/auth/admin/api-keys", who, null).status(), is(403));
      assertThat(call("POST", "/auth/admin/api-keys", who, key).status(), is(403));
      assertThat(call("GET", "/auth/admin/mfa-policy", who, null).status(), is(403));
      assertThat(call("GET", "/auth/admin/sso", who, null).status(), is(403));
    }
    assertThat("nobody was provisioned", emailCount(poached), is("0"));
    assertThat(rivalStaffCount(rival), is(rivalStaffBefore));
    assertThat(userColumn(id, "tenant_id"), is(nullValue()));
    assertThat(roleCount(id), is("0"));
  }

  @Test
  @DisplayName(
      "Signed up by anyone — another business's staff of every role, or a shopper — and whatever"
          + " the body says, the new login holds no business and no role; the callers are"
          + " unchanged")
  void noCallerAndNoBodyNamesTheBusiness() {
    Rival rival = rival();
    UUID shopper = signedUp(SHOPPER, "caller-shopper-" + suffix() + "@example.com");
    List<Caller> callers =
        List.of(
            new Caller(rival.owner(), "OWNER", rival.tenant()),
            new Caller(rival.manager(), "MANAGER", rival.tenant()),
            new Caller(rival.storekeeper(), "STOREKEEPER", rival.tenant()),
            new Caller(rival.cashier(), "CASHIER", rival.tenant()),
            new Caller(shopper, "CUSTOMER", null));
    String rivalStaffBefore = rivalStaffCount(rival);

    for (Caller who : callers) {
      String email = "signed-up-by-" + suffix() + "@example.com";
      // Every way a body might try to name a business or a role; none of them is read.
      String body =
          "{\"email\":\""
              + email
              + "\",\"password\":\""
              + PASSWORD
              + "\",\"tenantId\":\""
              + rival.tenant()
              + "\",\"storeId\":\""
              + rival.store()
              + "\",\"roles\":[\"OWNER\"],\"role\":\"OWNER\",\"type\":\"CUSTOMER\"}";
      Answer a = call("POST", BUSINESS, who, body);
      assertThat(a.body().toString(), a.status(), is(201));
      DecodedJWT token = token(a);
      UUID made = Ids.parse(token.getSubject());
      assertThat("a new login, never the caller's", made.equals(who.userId()), is(false));
      assertThat(token.getClaim("tenant").isMissing(), is(true));
      assertThat(token.getClaim("roles").asList(String.class), is(empty()));
      assertThat(token.getClaim("type").asString(), is("STAFF"));
      assertThat(userColumn(made, "tenant_id"), is(nullValue()));
      assertThat(roleCount(made), is("0"));
    }

    // The callers are who they were.
    assertThat(rivalStaffCount(rival), is(rivalStaffBefore));
    assertThat(userColumn(rival.owner(), "tenant_id"), is(rival.tenant().toString()));
    assertThat(roleCount(rival.owner()), is("1"));
    assertThat(userColumn(shopper, "tenant_id"), is(nullValue()));
    assertThat(userColumn(shopper, "type"), is("CUSTOMER"));
  }

  // ── one address: a shopper's account and a business account ────────────────

  @Test
  @DisplayName(
      "One address signs up as a shopper and as a business: two accounts, each signed in from its"
          + " own place, neither sign-up taken twice, and one's password never opening the other")
  void oneAddressShopsAndRunsABusinessAsTwoAccounts() {
    String email = "both-" + suffix() + "@example.com";
    UUID shopper = signedUp(SHOPPER, email, SHOPPING);
    UUID founder = signedUp(BUSINESS, email, PASSWORD);
    assertThat("two logins, not one", founder.equals(shopper), is(false));

    // Each sign-up once, however the address is written.
    String shouted = email.toUpperCase(Locale.ROOT);
    for (String path : List.of(SHOPPER, BUSINESS)) {
      for (String address : List.of(email, shouted)) {
        Answer again = signUp(path, address, PASSWORD, Caller.NOBODY);
        assertThat(path + " " + address, again.status(), is(409));
        assertThat(again.code(), is("USER_ALREADY_EXISTS"));
      }
    }
    assertThat(emailCount(email), is("2"));

    // From a storefront the shopper's account; to run a business — said, or not said — the other.
    DecodedJWT atTheShop = token(signIn(email, SHOPPING, "CUSTOMER"));
    assertThat(atTheShop.getSubject(), is(shopper.toString()));
    assertThat(atTheShop.getClaim("type").asString(), is("CUSTOMER"));
    assertThat(atTheShop.getClaim("roles").asList(String.class), contains("CUSTOMER"));
    assertThat(atTheShop.getClaim("tenant").isMissing(), is(true));
    for (String kind : Arrays.asList("STAFF", null)) {
      DecodedJWT atWork = token(signIn(email, PASSWORD, kind));
      assertThat(String.valueOf(kind), atWork.getSubject(), is(founder.toString()));
      assertThat(atWork.getClaim("type").asString(), is("STAFF"));
      assertThat(atWork.getClaim("roles").asList(String.class), is(empty()));
    }

    // A password that opens the other kind is not enough while this kind exists.
    assertRefused(signIn(email, PASSWORD, "CUSTOMER"));
    assertRefused(signIn(email, SHOPPING, "STAFF"));
    assertRefused(signIn(email, SHOPPING, null));
    Answer unknownKind = signIn(email, PASSWORD, "ADMIN");
    assertThat(unknownKind.status(), is(400));
    assertThat(unknownKind.code(), is("VALIDATION_FAILED"));

    // The business is created: the business account becomes its owner, the shopper's stays as it
    // was, and each still signs in from its own place.
    UUID tenant = Ids.newId();
    tenants.handle(tenantCreated(Ids.newId(), tenant, founder));
    DecodedJWT owner = token(signIn(email, PASSWORD, null));
    assertThat(owner.getSubject(), is(founder.toString()));
    assertThat(owner.getClaim("tenant").asString(), is(tenant.toString()));
    assertThat(owner.getClaim("roles").asList(String.class), contains("OWNER"));
    DecodedJWT stillShopping = token(signIn(email, SHOPPING, "CUSTOMER"));
    assertThat(stillShopping.getSubject(), is(shopper.toString()));
    assertThat(stillShopping.getClaim("tenant").isMissing(), is(true));
    assertThat(stillShopping.getClaim("roles").asList(String.class), contains("CUSTOMER"));
    assertThat(userColumn(shopper, "type"), is("CUSTOMER"));
    assertThat(userColumn(shopper, "tenant_id"), is(nullValue()));
    assertThat(roleCount(shopper), is("1"));
  }

  @Test
  @DisplayName(
      "A person with one account signs in with it when naming its kind or none, and never through"
          + " the other kind")
  void oneAccountSignsInOnlyAsItsKindOrUnnamed() {
    String shopperOnly = "only-shopping-" + suffix() + "@example.com";
    UUID shopper = signedUp(SHOPPER, shopperOnly);
    String founderOnly = "only-founding-" + suffix() + "@example.com";
    UUID founder = signedUp(BUSINESS, founderOnly);
    // Naming no kind is unchanged: the address's one account opens from anywhere.
    assertThat(token(signIn(shopperOnly, PASSWORD, null)).getSubject(), is(shopper.toString()));
    assertThat(token(signIn(founderOnly, PASSWORD, null)).getSubject(), is(founder.toString()));
    // Naming its own kind opens it.
    assertThat(
        token(signIn(shopperOnly, PASSWORD, "CUSTOMER")).getSubject(), is(shopper.toString()));
    assertThat(token(signIn(founderOnly, PASSWORD, "STAFF")).getSubject(), is(founder.toString()));
    // Naming the other kind is answered as a wrong password (storefront-trust slice 1).
    assertRefused(signIn(shopperOnly, PASSWORD, "STAFF"));
    assertRefused(signIn(founderOnly, PASSWORD, "CUSTOMER"));
  }

  @Test
  @DisplayName(
      "Provisioning staff with a shopper's email makes a separate staff login in the business,"
          + " bound by StaffAssigned; the shopper's account is untouched; the same email again is"
          + " the same login")
  void provisioningAShoppersEmailMakesASeparateLogin() {
    Rival ours = rival();
    String email = "shopper-hired-" + suffix() + "@example.com";
    UUID shopper = signedUp(SHOPPER, email, SHOPPING);
    String shopperHash = userColumn(shopper, "password_hash");

    Answer made = provision(new Caller(ours.owner(), "OWNER", ours.tenant()), email, WORKING);
    assertThat(made.body().toString(), made.status(), is(200));
    assertThat(made.data().getBoolean("created"), is(true));
    UUID hired = Ids.parse(made.data().getString("userId"));
    assertThat("never the shopper's login", hired.equals(shopper), is(false));
    // Made in the business, holding nothing until it is assigned; announced naming the business.
    assertThat(userColumn(hired, "tenant_id"), is(ours.tenant().toString()));
    assertThat(userColumn(hired, "type"), is("STAFF"));
    assertThat(roleCount(hired), is("0"));
    JsonObject event = registeredEvent(hired);
    assertThat(event.getString("type"), is("STAFF"));
    assertThat(event.getString("tenantId"), is(ours.tenant().toString()));
    assertThat(auditCount(hired, "STAFF_PROVISIONED"), is("1"));

    // StaffAssigned binds a login that was never of no business, once however often delivered.
    String assigned =
        staffAssigned(Ids.newId(), ours.tenant(), hired, ours.store(), "CASHIER", null);
    staff.handle(assigned);
    staff.handle(assigned);
    assertThat(storeRoleCount(hired, ours.store()), is("1"));
    assertThat(auditCount(hired, "STAFF_BOUND"), is("1"));
    assertThat(auditCount(hired, "STAFF_BIND_REFUSED"), is("0"));
    DecodedJWT atTheTill = token(signIn(email, WORKING, "STAFF"));
    assertThat(atTheTill.getSubject(), is(hired.toString()));
    assertThat(atTheTill.getClaim("tenant").asString(), is(ours.tenant().toString()));
    assertThat(atTheTill.getClaim("roles").asList(String.class), contains("CASHIER"));

    // The shopper's account: as it was, and still the storefront's.
    assertThat(userColumn(shopper, "type"), is("CUSTOMER"));
    assertThat(userColumn(shopper, "tenant_id"), is(nullValue()));
    assertThat(roleCount(shopper), is("1"));
    assertThat(userColumn(shopper, "password_hash"), is(shopperHash));
    DecodedJWT atTheShop = token(signIn(email, SHOPPING, "CUSTOMER"));
    assertThat(atTheShop.getSubject(), is(shopper.toString()));
    assertThat(atTheShop.getClaim("roles").asList(String.class), contains("CUSTOMER"));
    assertThat(atTheShop.getClaim("tenant").isMissing(), is(true));

    // The same business provisioning the address again: the same login, nothing new.
    Answer again = provision(new Caller(ours.manager(), "MANAGER", ours.tenant()), email, WORKING);
    assertThat(again.body().toString(), again.status(), is(200));
    assertThat(again.data().getBoolean("created"), is(false));
    assertThat(again.data().getString("userId"), is(hired.toString()));
    assertThat(auditCount(hired, "STAFF_PROVISIONED_REUSE"), is("1"));
    assertThat(emailCount(email), is("2"));
  }

  @Test
  @DisplayName(
      "Provisioning staff with a founder's email before their business exists makes a separate"
          + " login: the founder still becomes the owner of their own business, and the business"
          + " that provisioned never gains the founder's login")
  void provisioningAFoundersEmailNeverCapturesTheirBusiness() {
    Rival employer = rival();
    String email = "founder-hired-" + suffix() + "@example.com";
    UUID founder = signedUp(BUSINESS, email, PASSWORD);

    Answer made =
        provision(new Caller(employer.owner(), "OWNER", employer.tenant()), email, WORKING);
    assertThat(made.body().toString(), made.status(), is(200));
    assertThat(made.data().getBoolean("created"), is(true));
    UUID hired = Ids.parse(made.data().getString("userId"));
    assertThat(hired.equals(founder), is(false));
    staff.handle(
        staffAssigned(Ids.newId(), employer.tenant(), hired, employer.store(), "CASHIER", null));
    assertThat(
        "the sign-up is still no business's", userColumn(founder, "tenant_id"), is(nullValue()));
    assertThat(roleCount(founder), is("0"));

    // Having the address added as staff first did not take the founder's business from them.
    UUID own = Ids.newId();
    String created = tenantCreated(Ids.newId(), own, founder);
    tenants.handle(created);
    tenants.handle(created);
    assertThat(userColumn(founder, "tenant_id"), is(own.toString()));
    assertThat(ownerRoleCount(founder), is("1"));
    assertThat(auditCount(founder, "OWNER_BOUND"), is("1"));
    assertThat(auditCount(founder, "OWNER_BIND_REFUSED"), is("0"));
    DecodedJWT owning = token(signIn(email, PASSWORD, null));
    assertThat(owning.getSubject(), is(founder.toString()));
    assertThat(owning.getClaim("tenant").asString(), is(own.toString()));
    assertThat(owning.getClaim("roles").asList(String.class), contains("OWNER"));

    // Their job is their other login, in the employer's business alone.
    DecodedJWT working = token(signIn(email, WORKING, null));
    assertThat(working.getSubject(), is(hired.toString()));
    assertThat(working.getClaim("tenant").asString(), is(employer.tenant().toString()));
    assertThat(working.getClaim("roles").asList(String.class), contains("CASHIER"));
    assertThat(storeRoleCount(founder, employer.store()), is("0"));
    assertThat(
        "the employer holds one login for the address, its own",
        loginsInBusiness(employer.tenant(), email),
        is(hired.toString()));
  }

  @Test
  @DisplayName(
      "Another business — its owner, manager, storekeeper and cashier, and a shopper — changes"
          + " none of our logins: its management adding our member of staff's address makes a"
          + " login of its own, its StaffAssigned naming ours is refused even at our store, and our"
          + " rows stay exactly as they were")
  void anotherBusinessChangesNoneOfOurLogins() {
    Rival ours = rival();
    Rival other = rival();
    String email = "ours-" + suffix() + "@example.com";
    UUID shopper = signedUp(SHOPPER, email, SHOPPING);
    UUID mine = provisioned(new Caller(ours.owner(), "OWNER", ours.tenant()), email, WORKING);
    staff.handle(staffAssigned(Ids.newId(), ours.tenant(), mine, ours.store(), "CASHIER", null));
    UUID stranger = signedUp(SHOPPER, "stranger-" + suffix() + "@example.com");
    String before = rowsOf(mine, shopper);

    // Their management may take the person on too, with a login of their own in their business —
    // never ours or the shopper's — and gets the same one back when it asks again.
    String theirPassword = "their own phrase for the job";
    Caller theirOwner = new Caller(other.owner(), "OWNER", other.tenant());
    Answer made = provision(theirOwner, email, theirPassword);
    assertThat(made.body().toString(), made.status(), is(200));
    assertThat(made.data().getBoolean("created"), is(true));
    UUID theirLogin = Ids.parse(made.data().getString("userId"));
    assertThat(List.of(mine, shopper).contains(theirLogin), is(false));
    assertThat(userColumn(theirLogin, "tenant_id"), is(other.tenant().toString()));
    Caller theirManager = new Caller(other.manager(), "MANAGER", other.tenant());
    Answer again = provision(theirManager, email, WORKING);
    assertThat(again.body().toString(), again.status(), is(200));
    assertThat(again.data().getBoolean("created"), is(false));
    assertThat(again.data().getString("userId"), is(theirLogin.toString()));

    // Their till and warehouse staff, and a shopper, provision nobody.
    List<Caller> noManagers =
        List.of(
            new Caller(other.storekeeper(), "STOREKEEPER", other.tenant()),
            new Caller(other.cashier(), "CASHIER", other.tenant()),
            new Caller(stranger, "CUSTOMER", null));
    for (Caller who : noManagers) {
      assertThat(who.roles(), provision(who, email, WORKING).status(), is(403));
    }
    // Their StaffAssigned naming our login, at their store or at ours: refused, once per event.
    for (UUID store : List.of(other.store(), ours.store())) {
      for (String tier : List.of("OWNER", "CASHIER")) {
        String event = staffAssigned(Ids.newId(), other.tenant(), mine, store, tier, null);
        staff.handle(event);
        staff.handle(event);
      }
    }
    assertThat(rowsOf(mine, shopper), is(before));
    assertThat(auditCount(mine, "STAFF_BIND_REFUSED"), is("4"));
    assertThat(loginsInBusiness(ours.tenant(), email), is(mine.toString()));

    // Each password opens its own login: ours at our till, theirs at theirs, the shopper's at the
    // storefront.
    DecodedJWT stillOurs = token(signIn(email, WORKING, null));
    assertThat(stillOurs.getSubject(), is(mine.toString()));
    assertThat(stillOurs.getClaim("tenant").asString(), is(ours.tenant().toString()));
    assertThat(stillOurs.getClaim("roles").asList(String.class), contains("CASHIER"));
    assertThat(
        stillOurs.getClaim("storeIds").asList(String.class), contains(ours.store().toString()));
    assertThat(token(signIn(email, theirPassword, null)).getSubject(), is(theirLogin.toString()));
    assertThat(token(signIn(email, SHOPPING, "CUSTOMER")).getSubject(), is(shopper.toString()));
  }

  @Test
  @DisplayName(
      "A StaffAssigned takes on no login of no business, whatever id it names — a shopper's read"
          + " off an order, a founder's before their business exists, an id nobody holds — in any"
          + " tier or role of the business's own: refused and audited once per event, every row as"
          + " it was, and each still signs in to its own")
  void aStaffAssignedTakesOnNobody() {
    Rival ours = rival();
    String shopperEmail = "read-off-an-order-" + suffix() + "@example.com";
    UUID shopper = signedUp(SHOPPER, shopperEmail, SHOPPING);
    String founderEmail = "not-yet-founded-" + suffix() + "@example.com";
    UUID founder = signedUp(BUSINESS, founderEmail, PASSWORD);
    UUID nobody = Ids.newId();
    String before = rowsOf(shopper, founder);
    String staffBefore = rivalStaffCount(ours);

    for (UUID id : List.of(shopper, founder, nobody)) {
      List<String> events = new java.util.ArrayList<>();
      for (String tier : List.of("OWNER", "MANAGER", "CASHIER")) {
        events.add(staffAssigned(Ids.newId(), ours.tenant(), id, ours.store(), tier, null));
      }
      events.add(staffAssigned(Ids.newId(), ours.tenant(), id, ours.store(), "MANAGER", "BUYER"));
      for (String event : events) {
        staff.handle(event);
        staff.handle(event);
      }
    }

    assertThat(rowsOf(shopper, founder), is(before));
    assertThat(rivalStaffCount(ours), is(staffBefore));
    for (UUID id : List.of(shopper, founder, nobody)) {
      // Once per event, however often delivered — and never a failing event.
      assertThat(auditCount(id, "STAFF_BIND_REFUSED"), is("4"));
    }
    // Nothing is made for an id nobody holds.
    assertThat(userColumn(nobody, "id"), is(nullValue()));
    assertThat(roleCount(nobody), is("0"));

    // The shopper still shops everywhere: the storefront's sign-in finds their own account.
    DecodedJWT atTheShop = token(signIn(shopperEmail, SHOPPING, "CUSTOMER"));
    assertThat(atTheShop.getSubject(), is(shopper.toString()));
    assertThat(atTheShop.getClaim("type").asString(), is("CUSTOMER"));
    assertThat(atTheShop.getClaim("tenant").isMissing(), is(true));
    assertThat(atTheShop.getClaim("roles").asList(String.class), contains("CUSTOMER"));

    // The founder still sets their own business up and owns it.
    UUID own = Ids.newId();
    tenants.handle(tenantCreated(Ids.newId(), own, founder));
    DecodedJWT owning = token(signIn(founderEmail, PASSWORD, null));
    assertThat(owning.getSubject(), is(founder.toString()));
    assertThat(owning.getClaim("tenant").asString(), is(own.toString()));
    assertThat(owning.getClaim("roles").asList(String.class), contains("OWNER"));
  }

  @Test
  @DisplayName(
      "A business that already has a login for an address cannot stamp the same address's shopper"
          + " account in beside it by id: refused and audited, never a failing event")
  void aSecondLoginForOneAddressIsNeverStampedIn() {
    Rival ours = rival();
    String email = "twice-over-" + suffix() + "@example.com";
    UUID shopper = signedUp(SHOPPER, email, SHOPPING);
    provisioned(new Caller(ours.owner(), "OWNER", ours.tenant()), email, WORKING);

    String event =
        staffAssigned(Ids.newId(), ours.tenant(), shopper, ours.store(), "CASHIER", null);
    staff.handle(event);
    staff.handle(event);
    assertThat(userColumn(shopper, "tenant_id"), is(nullValue()));
    assertThat(userColumn(shopper, "type"), is("CUSTOMER"));
    assertThat(roleCount(shopper), is("1"));
    assertThat(auditCount(shopper, "STAFF_BIND_REFUSED"), is("1"));
  }

  @Test
  @DisplayName(
      "A login another business provisioned and never assigned blocks nobody: our business makes a"
          + " login of its own for the address, theirs stays exactly as it was, each business gets"
          + " its own back on asking again, and the person may work for both")
  void aLoginElsewhereNeverAssignedBlocksNobody() {
    Rival a = rival();
    Rival b = rival();
    Caller aOwner = new Caller(a.owner(), "OWNER", a.tenant());
    Caller bOwner = new Caller(b.owner(), "OWNER", b.tenant());
    String email = "sought-after-" + suffix() + "@example.com";
    String bPassword = "the other shop's own phrase";

    // B adds the address and never assigns it — its plan's staff limit refused the assignment, say.
    UUID stranded = provisioned(bOwner, email, bPassword);
    String strandedBefore = rowsOf(stranded);

    // A takes the person on: a login of its own, made now, and bound.
    Answer made = provision(aOwner, email, WORKING);
    assertThat(made.body().toString(), made.status(), is(200));
    assertThat(made.data().getBoolean("created"), is(true));
    UUID hired = Ids.parse(made.data().getString("userId"));
    assertThat(hired.equals(stranded), is(false));
    assertThat(userColumn(hired, "tenant_id"), is(a.tenant().toString()));
    staff.handle(staffAssigned(Ids.newId(), a.tenant(), hired, a.store(), "CASHIER", null));
    assertThat(rowsOf(stranded), is(strandedBefore));
    DecodedJWT atA = token(signIn(email, WORKING, null));
    assertThat(atA.getSubject(), is(hired.toString()));
    assertThat(atA.getClaim("tenant").asString(), is(a.tenant().toString()));
    assertThat(atA.getClaim("roles").asList(String.class), contains("CASHIER"));

    // Each business, asking again, gets its own login back and nothing new.
    Answer bAgain = provision(bOwner, email, "any phrase at all");
    assertThat(bAgain.data().getBoolean("created"), is(false));
    assertThat(bAgain.data().getString("userId"), is(stranded.toString()));
    Answer aAgain = provision(aOwner, email, "any phrase at all");
    assertThat(aAgain.data().getBoolean("created"), is(false));
    assertThat(aAgain.data().getString("userId"), is(hired.toString()));
    assertThat(emailCount(email), is("2"));

    // B assigns its login after all: the person works for both, each business's password opening
    // its own login, and neither assignment reaching the other's.
    staff.handle(staffAssigned(Ids.newId(), b.tenant(), stranded, b.store(), "STOREKEEPER", null));
    DecodedJWT atB = token(signIn(email, bPassword, null));
    assertThat(atB.getSubject(), is(stranded.toString()));
    assertThat(atB.getClaim("tenant").asString(), is(b.tenant().toString()));
    assertThat(atB.getClaim("roles").asList(String.class), contains("STOREKEEPER"));
    assertThat(token(signIn(email, WORKING, null)).getSubject(), is(hired.toString()));
    assertThat(storeRoleCount(hired, b.store()), is("0"));
    assertThat(storeRoleCount(stranded, a.store()), is("0"));
  }

  @Test
  @DisplayName(
      "Let go of their last store, a member of staff made in the business stays in it holding no"
          + " role — never a login of no business in the way of a sign-up of their own — and"
          + " provisioning the address again gives the business the same login back")
  void lettingGoKeepsTheLoginInItsBusiness() {
    Rival ours = rival();
    Caller owner = new Caller(ours.owner(), "OWNER", ours.tenant());
    String email = "leaving-" + suffix() + "@example.com";
    UUID left = provisioned(owner, email, WORKING);
    staff.handle(staffAssigned(Ids.newId(), ours.tenant(), left, ours.store(), "CASHIER", null));
    String removed = staffRemoved(Ids.newId(), ours.tenant(), left, ours.store(), "CASHIER");
    letGo.handle(removed);
    letGo.handle(removed);
    assertThat(userColumn(left, "tenant_id"), is(ours.tenant().toString()));
    assertThat(userColumn(left, "type"), is("STAFF"));
    assertThat(roleCount(left), is("0"));
    assertThat(auditCount(left, "STAFF_UNBOUND"), is("1"));

    // It opens nothing — the business's, holding no role, never the setup wizard's login of no
    // business — and the business's management no longer names it.
    DecodedJWT gone = token(signIn(email, WORKING, null));
    assertThat(gone.getSubject(), is(left.toString()));
    assertThat(gone.getClaim("tenant").asString(), is(ours.tenant().toString()));
    assertThat(gone.getClaim("roles").asList(String.class), is(empty()));
    Answer named = call("GET", "/auth/admin/staff-users?ids=" + left, owner, null);
    assertThat(named.body().toString(), named.status(), is(200));
    assertThat(named.body().getJsonArray("data").isEmpty(), is(true));

    // The address is free for a business sign-up of the person's own, which stays theirs.
    UUID founder = signedUp(BUSINESS, email, PASSWORD);
    assertThat(founder.equals(left), is(false));
    assertThat(token(signIn(email, PASSWORD, null)).getSubject(), is(founder.toString()));

    // Taken on again: the same login back, bound again; the founder's sign-up untouched.
    Answer back = provision(owner, email, "a phrase nobody is told");
    assertThat(back.body().toString(), back.status(), is(200));
    assertThat(back.data().getBoolean("created"), is(false));
    assertThat(back.data().getString("userId"), is(left.toString()));
    staff.handle(
        staffAssigned(Ids.newId(), ours.tenant(), left, ours.store(), "STOREKEEPER", null));
    DecodedJWT rehired = token(signIn(email, WORKING, null));
    assertThat(rehired.getSubject(), is(left.toString()));
    assertThat(rehired.getClaim("roles").asList(String.class), contains("STOREKEEPER"));
    assertThat(userColumn(founder, "tenant_id"), is(nullValue()));
    assertThat(roleCount(founder), is("0"));
  }

  @Test
  @DisplayName(
      "A forgotten password on an address holding a shopper's account and a business account: one"
          + " link per login, each resetting only its own")
  void aResetLinkResetsOnlyItsOwnLogin() {
    String email = "forgetful-" + suffix() + "@example.com";
    UUID shopper = signedUp(SHOPPER, email, SHOPPING);
    UUID founder = signedUp(BUSINESS, email, PASSWORD);

    Answer asked =
        call("POST", "/auth/password/forgot", Caller.NOBODY, "{\"email\":\"" + email + "\"}");
    assertThat(asked.status(), is(202));
    JsonArray entries = resetEvent(email).getJsonArray("entries");
    assertThat(entries.size(), is(2));
    JsonObject shopperEntry = entryFor(entries, shopper);
    assertThat(shopperEntry.getString("kind"), is("SHOPPER"));
    JsonObject founderEntry = entryFor(entries, founder);
    assertThat(
        "a business account, before it has a business",
        founderEntry.getString("kind"),
        is("STAFF"));
    assertThat(founderEntry.isNull("businessName"), is(true));

    String newShopping = "the shop's brand new phrase";
    String founderHash = userColumn(founder, "password_hash");
    Answer shopperReset = reset(shopperEntry, newShopping);
    assertThat(shopperReset.body().toString(), shopperReset.status(), is(200));
    assertThat(
        "the business account untouched", userColumn(founder, "password_hash"), is(founderHash));
    assertThat(token(signIn(email, newShopping, "CUSTOMER")).getSubject(), is(shopper.toString()));
    assertThat(signIn(email, SHOPPING, "CUSTOMER").status(), is(401));
    assertThat(token(signIn(email, PASSWORD, null)).getSubject(), is(founder.toString()));

    String newWorking = "the business's brand new phrase";
    String shopperHash = userColumn(shopper, "password_hash");
    Answer founderReset = reset(founderEntry, newWorking);
    assertThat(founderReset.body().toString(), founderReset.status(), is(200));
    assertThat(
        "the shopper's account untouched", userColumn(shopper, "password_hash"), is(shopperHash));
    assertThat(token(signIn(email, newWorking, null)).getSubject(), is(founder.toString()));
    assertThat(signIn(email, PASSWORD, null).status(), is(401));
    assertThat(token(signIn(email, newShopping, "CUSTOMER")).getSubject(), is(shopper.toString()));
    // Each link was the whole capability for its own login, and is spent.
    assertThat(reset(shopperEntry, "yet another phrase entirely").status(), is(400));
  }

  // ── a phone ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A business sign-up with a phone keeps it on the login, and the owner keeps it once the"
          + " business exists; without one, none")
  void aBusinessSignUpKeepsItsPhone() {
    String phone = phone("+81 3 ");
    UUID founder = signedUpWithPhone(BUSINESS, "phoning-" + suffix() + "@example.com", phone);
    assertThat(userColumn(founder, "phone"), is(phone));
    assertThat(userColumn(founder, "type"), is("STAFF"));
    assertThat(userColumn(founder, "tenant_id"), is(nullValue()));
    Answer me = call("GET", "/auth/me", new Caller(founder, null, null), null);
    assertThat(me.body().toString(), me.status(), is(200));
    assertThat(me.data().getString("phone"), is(phone));

    UUID tenant = Ids.newId();
    tenants.handle(tenantCreated(Ids.newId(), tenant, founder));
    assertThat(userColumn(founder, "tenant_id"), is(tenant.toString()));
    assertThat(ownerRoleCount(founder), is("1"));
    assertThat(userColumn(founder, "phone"), is(phone));

    UUID without = signedUp(BUSINESS, "no-phone-" + suffix() + "@example.com");
    assertThat(userColumn(without, "phone"), is(nullValue()));
  }

  @Test
  @DisplayName(
      "One phone signs up as a shopper and as a business, in either order: two accounts, each"
          + " signed in from its own place")
  void aShopperAndABusinessMayShareAPhone() {
    // Shopper first, as when the platform console onboards someone who already shops.
    String shopsFirst = phone("+91 98");
    UUID shopper =
        signedUpWithPhone(SHOPPER, "phone-shops-" + suffix() + "@example.com", shopsFirst);
    String founderEmail = "phone-founds-" + suffix() + "@example.com";
    UUID founder = signedUpWithPhone(BUSINESS, founderEmail, shopsFirst);
    assertThat("two logins, not one", founder.equals(shopper), is(false));
    assertThat(phoneCount(shopsFirst), is("2"));
    assertThat(userColumn(shopper, "type"), is("CUSTOMER"));
    assertThat(userColumn(founder, "type"), is("STAFF"));
    DecodedJWT atWork = token(signIn(founderEmail, PASSWORD, "STAFF"));
    assertThat(atWork.getSubject(), is(founder.toString()));
    assertThat(atWork.getClaim("roles").asList(String.class), is(empty()));

    // Business first, then the same person starts shopping with the number.
    String foundsFirst = phone("+55 11 9");
    UUID second = signedUpWithPhone(BUSINESS, "phone-b-" + suffix() + "@example.com", foundsFirst);
    UUID buyer = signedUpWithPhone(SHOPPER, "phone-s-" + suffix() + "@example.com", foundsFirst);
    assertThat(second.equals(buyer), is(false));
    assertThat(phoneCount(foundsFirst), is("2"));
    assertThat(userColumn(buyer, "phone"), is(foundsFirst));
    assertThat(userColumn(second, "phone"), is(foundsFirst));
  }

  @Test
  @DisplayName(
      "Two business sign-ups cannot share a phone, nor two shoppers: 409 USER_ALREADY_EXISTS and"
          + " nothing written")
  void twoBusinessSignUpsCannotShareAPhone() {
    String phone = phone("+44 20 7946 ");
    signedUpWithPhone(BUSINESS, "phone-first-" + suffix() + "@example.com", phone);
    String late = "phone-late-" + suffix() + "@example.com";
    Answer again = signUpWithPhone(BUSINESS, late, PASSWORD, Json.createValue(phone));
    assertThat(again.body().toString(), again.status(), is(409));
    assertThat(again.code(), is("USER_ALREADY_EXISTS"));
    assertThat("a refused sign-up leaves no login behind", emailCount(late), is("0"));
    assertThat(phoneCount(phone), is("1"));

    // One shopper may still take it, and only one.
    signedUpWithPhone(SHOPPER, "phone-shopper-" + suffix() + "@example.com", phone);
    Answer secondShopper = signUpWithPhone(SHOPPER, late, PASSWORD, Json.createValue(phone));
    assertThat(secondShopper.status(), is(409));
    assertThat(secondShopper.code(), is("USER_ALREADY_EXISTS"));
    assertThat(emailCount(late), is("0"));
    assertThat(phoneCount(phone), is("2"));
  }

  @Test
  @DisplayName(
      "A phone is read as the shopper's sign-up reads one: one that is not text is refused by the"
          + " same code with nothing written, and one that is text is kept exactly as the shopper's"
          + " would be")
  void aPhoneIsReadAsTheShoppersSignUpReadsIt() {
    List<JsonValue> notText =
        List.of(
            Json.createObjectBuilder().add("country", "JP").add("number", "3-1234-5678").build(),
            Json.createArrayBuilder().add("+81").add("312345678").build());
    List<JsonValue> text =
        List.of(
            Json.createValue(phone("+33 6 ")),
            Json.createValue(phone("0")),
            Json.createValue("not a phone " + suffix()));
    for (JsonValue typed : notText) {
      String shopperEmail = "unread-shopper-" + suffix() + "@example.com";
      String founderEmail = "unread-founder-" + suffix() + "@example.com";
      Answer asShopper = signUpWithPhone(SHOPPER, shopperEmail, PASSWORD, typed);
      Answer asFounder = signUpWithPhone(BUSINESS, founderEmail, PASSWORD, typed);
      assertThat(typed.toString(), asShopper.status(), is(400));
      assertThat(typed.toString(), asShopper.code(), is("REQUEST_BODY_INVALID"));
      assertThat(typed.toString(), asFounder.status(), is(asShopper.status()));
      assertThat(typed.toString(), asFounder.code(), is(asShopper.code()));
      assertThat(emailCount(founderEmail), is("0"));
    }
    // Whatever the shopper's sign-up does with text, the business sign-up does the same: today
    // both keep it as typed, and if the shopper's ever refuses some, this refuses it too.
    for (JsonValue typed : text) {
      String shopperEmail = "read-shopper-" + suffix() + "@example.com";
      String founderEmail = "read-founder-" + suffix() + "@example.com";
      Answer asShopper = signUpWithPhone(SHOPPER, shopperEmail, PASSWORD, typed);
      Answer asFounder = signUpWithPhone(BUSINESS, founderEmail, PASSWORD, typed);
      assertThat(typed.toString(), asFounder.status(), is(asShopper.status()));
      assertThat(typed.toString(), asFounder.code(), is(asShopper.code()));
      if (asShopper.status() == 201) {
        UUID shopper = Ids.parse(token(asShopper).getSubject());
        UUID founder = Ids.parse(token(asFounder).getSubject());
        assertThat(userColumn(founder, "phone"), is(userColumn(shopper, "phone")));
      } else {
        assertThat(emailCount(founderEmail), is("0"));
      }
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private record Answer(int status, JsonObject body) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  /** Who the gateway says is calling: the identity headers it stamps from a verified token. */
  private record Caller(UUID userId, String roles, UUID tenantId) {
    static final Caller NOBODY = new Caller(null, null, null);
  }

  /** Another business, with an owner and a member of staff of every other tier at one store. */
  private record Rival(
      UUID tenant, UUID store, UUID owner, UUID manager, UUID storekeeper, UUID cashier) {}

  private Rival rival() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID owner = signedUp(BUSINESS, "rival-owner-" + suffix() + "@example.com");
    tenants.handle(tenantCreated(Ids.newId(), tenant, owner));
    Caller boss = new Caller(owner, "OWNER", tenant);
    UUID manager = staff(boss, store, "MANAGER");
    UUID storekeeper = staff(boss, store, "STOREKEEPER");
    UUID cashier = staff(boss, store, "CASHIER");
    return new Rival(tenant, store, owner, manager, storekeeper, cashier);
  }

  /**
   * A member of staff made the one way there is: provisioned in the owner's business with {@link
   * #PASSWORD}, then assigned at the store in the tier, as StaffAssigned binds one.
   */
  private UUID staff(Caller boss, UUID store, String tier) {
    String email = "rival-" + tier.toLowerCase(Locale.ROOT) + "-" + suffix() + "@example.com";
    UUID id = provisioned(boss, email, PASSWORD);
    staff.handle(staffAssigned(Ids.newId(), boss.tenantId(), id, store, tier, null));
    return id;
  }

  private UUID signedUp(String path, String email) {
    return signedUp(path, email, PASSWORD);
  }

  private UUID signedUp(String path, String email, String password) {
    Answer a = signUp(path, email, password, Caller.NOBODY);
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(token(a).getSubject());
  }

  private Answer signUp(String path, String email, String password, Caller who) {
    String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    return call("POST", path, who, body);
  }

  /** Signed up with {@link #PASSWORD} and a phone, which the login must then hold as given. */
  private UUID signedUpWithPhone(String path, String email, String phone) {
    Answer a = signUpWithPhone(path, email, PASSWORD, Json.createValue(phone));
    assertThat(a.body().toString(), a.status(), is(201));
    UUID id = Ids.parse(token(a).getSubject());
    assertThat(userColumn(id, "phone"), is(phone));
    return id;
  }

  /** A sign-up whose phone is any JSON value: text as someone typed it, or something that isn't. */
  private Answer signUpWithPhone(String path, String email, String password, JsonValue phone) {
    String body =
        Json.createObjectBuilder()
            .add("email", email)
            .add("password", password)
            .add("phone", phone)
            .build()
            .toString();
    return call("POST", path, Caller.NOBODY, body);
  }

  private Answer login(String email) {
    String body = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    return call("POST", "/auth/login", Caller.NOBODY, body);
  }

  /**
   * A sign-in from a place: {@code CUSTOMER} as the storefront sends it, {@code STAFF} as the admin
   * console and the till do, or null for a caller that says nothing.
   */
  private Answer signIn(String email, String password, String accountType) {
    String kind = accountType == null ? "" : ",\"accountType\":\"" + accountType + "\"";
    String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + kind + "}";
    return call("POST", "/auth/login", Caller.NOBODY, body);
  }

  /** {@code POST /auth/admin/staff-users}, as the gateway forwards the caller. */
  private Answer provision(Caller who, String email, String password) {
    return call("POST", "/auth/admin/staff-users", who, signUpBody(email, password));
  }

  /** A login provisioned for the caller's business, made there now. */
  private UUID provisioned(Caller who, String email, String password) {
    Answer a = provision(who, email, password);
    assertThat(a.body().toString(), a.status(), is(200));
    assertThat(a.data().getBoolean("created"), is(true));
    return Ids.parse(a.data().getString("userId"));
  }

  /** Refused as any wrong password is: nothing said about which logins the address holds. */
  private static void assertRefused(Answer a) {
    assertThat(a.body().toString(), a.status(), is(401));
    assertThat(a.code(), is("INVALID_CREDENTIALS"));
  }

  private static String signUpBody(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
  }

  /** Spends a forgotten-password entry's link with a new password. */
  private Answer reset(JsonObject entry, String newPassword) {
    String link = entry.getString("link");
    String token = link.substring(link.lastIndexOf('/') + 1);
    return call(
        "POST",
        "/auth/password/reset",
        Caller.NOBODY,
        "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
  }

  private Answer call(String method, String path, Caller who, String json) {
    WebTarget t = target;
    int query = path.indexOf('?');
    if (query < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, query));
      for (String pair : path.substring(query + 1).split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv[1]);
      }
    }
    Invocation.Builder b = t.request();
    if (who.userId() != null) b = b.header("X-User-Id", who.userId());
    if (who.roles() != null) b = b.header("X-Roles", who.roles());
    if (who.tenantId() != null) b = b.header("X-Tenant-Id", who.tenantId());
    Response r =
        "GET".equals(method)
            ? b.get()
            : b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
    String text = r.readEntity(String.class);
    JsonObject body =
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject();
    return new Answer(r.getStatus(), body);
  }

  private static DecodedJWT token(Answer a) {
    return JWT.decode(a.data().getString("accessToken"));
  }

  /** One column of the login's row, as text; null for a null. */
  private static String userColumn(UUID id, String column) {
    return scalar("SELECT " + column + " FROM iam.users WHERE id = '" + id + "'");
  }

  private static String emailCount(String email) {
    return scalar("SELECT count(*) FROM iam.users WHERE lower(email) = lower('" + email + "')");
  }

  private static String phoneCount(String phone) {
    return scalar("SELECT count(*) FROM iam.users WHERE phone = '" + phone + "'");
  }

  private static String roleCount(UUID userId) {
    return scalar("SELECT count(*) FROM iam.user_roles WHERE user_id = '" + userId + "'");
  }

  private static String ownerRoleCount(UUID userId) {
    return scalar(
        "SELECT count(*) FROM iam.user_roles ur JOIN iam.roles r ON r.id = ur.role_id"
            + " WHERE ur.user_id = '"
            + userId
            + "' AND r.name = 'OWNER'");
  }

  /** The login's roles at one store. */
  private static String storeRoleCount(UUID userId, UUID storeId) {
    return scalar(
        "SELECT count(*) FROM iam.user_roles WHERE user_id = '"
            + userId
            + "' AND store_id = '"
            + storeId
            + "'");
  }

  private static String auditCount(UUID userId, String action) {
    return scalar(
        "SELECT count(*) FROM iam.audit_log WHERE user_id = '"
            + userId
            + "' AND action = '"
            + action
            + "'");
  }

  private static String registeredCount(UUID userId) {
    return scalar(
        "SELECT count(*) FROM iam.outbox WHERE event_type = 'UserRegistered'"
            + " AND aggregate_id = '"
            + userId
            + "'");
  }

  /** The UserRegistered the login was announced with. */
  private static JsonObject registeredEvent(UUID userId) {
    return Envelopes.parse(
        scalar(
            "SELECT payload FROM iam.outbox WHERE event_type = 'UserRegistered'"
                + " AND aggregate_id = '"
                + userId
                + "'"));
  }

  /**
   * The logins' rows and every role each holds, as one line of text: what a refusal must leave
   * exactly as it was.
   */
  private static String rowsOf(UUID... ids) {
    String logins = String.join(",", Arrays.stream(ids).map(id -> "'" + id + "'").toList());
    String rows =
        scalar(
            "SELECT string_agg(id || ':' || coalesce(tenant_id::text, '-') || ':' || type || ':'"
                + " || status || ':' || password_hash, ',' ORDER BY id) FROM iam.users"
                + " WHERE id IN ("
                + logins
                + ")");
    String roles =
        scalar(
            "SELECT string_agg(user_id || ':' || role_id || ':' || coalesce(store_id::text, '-')"
                + " || ':' || coalesce(role_code, '-') || ':' || coalesce(permissions, '-'), ','"
                + " ORDER BY id) FROM iam.user_roles WHERE user_id IN ("
                + logins
                + ")");
    return rows + " / " + roles;
  }

  /** The ids of the business's logins with the address, comma-separated. */
  private static String loginsInBusiness(UUID tenantId, String email) {
    return scalar(
        "SELECT string_agg(id::text, ',' ORDER BY id) FROM iam.users WHERE tenant_id = '"
            + tenantId
            + "' AND lower(email) = lower('"
            + email
            + "')");
  }

  /** The latest PasswordResetRequested for the address: it belongs to no business. */
  private static JsonObject resetEvent(String email) {
    return Envelopes.parse(
        scalar(
            "SELECT payload FROM iam.outbox WHERE event_type = 'PasswordResetRequested'"
                + " AND payload LIKE '%\"email\":\""
                + email
                + "\"%' ORDER BY created_at DESC LIMIT 1"));
  }

  /** The entry a forgotten-password email carries for one login. */
  private static JsonObject entryFor(JsonArray entries, UUID userId) {
    for (JsonObject entry : entries.getValuesAs(JsonObject.class)) {
      if (userId.toString().equals(entry.getString("userId", null))) {
        return entry;
      }
    }
    throw new AssertionError("no entry for " + userId + " in " + entries);
  }

  private static String rivalStaffCount(Rival rival) {
    return scalar(
        "SELECT count(*) FROM iam.users u JOIN iam.user_roles ur ON ur.user_id = u.id"
            + " WHERE u.tenant_id = '"
            + rival.tenant()
            + "'");
  }

  private static String scalar(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  /** Six hex characters from a fresh id: enough to keep this class's addresses apart. */
  private static String suffix() {
    return Ids.newId().toString().substring(30);
  }

  /**
   * A number nobody else in the run holds: the prefix, then eight digits from a fresh id's random
   * bits. Kept as typed, so the prefix is only there to span countries, never read.
   */
  private static String phone(String prefix) {
    long digits = Math.floorMod(Ids.newId().getLeastSignificantBits(), 100_000_000L);
    return prefix + String.format(Locale.ROOT, "%08d", digits);
  }

  /**
   * A StaffAssigned, as tenant-svc announces it: a tier at a store, or, with {@code roleCode}, one
   * of the business's own roles on that tier with the permissions it holds.
   */
  private static String staffAssigned(
      UUID eventId, UUID tenantId, UUID userId, UUID storeId, String tier, String roleCode) {
    String custom =
        roleCode == null
            ? ""
            : ",\"roleCode\":\""
                + roleCode
                + "\",\"permissions\":[\"purchasing.approve\"],\"roleUpdatedAt\":\""
                + Instant.now()
                + "\"";
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"StaffAssigned\",\"tenantId\":\""
        + tenantId
        + "\",\"aggregateId\":\""
        + userId
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\",\"userId\":\""
        + userId
        + "\",\"storeId\":\""
        + storeId
        + "\",\"role\":\""
        + tier
        + "\""
        + custom
        + "}";
  }

  /** A StaffRemoved, as tenant-svc announces it: the tier taken away at one store. */
  private static String staffRemoved(
      UUID eventId, UUID tenantId, UUID userId, UUID storeId, String tier) {
    return staffAssigned(eventId, tenantId, userId, storeId, tier, null)
        .replace("\"eventType\":\"StaffAssigned\"", "\"eventType\":\"StaffRemoved\"");
  }

  /** A live business's TenantCreated, as tenant-svc announces it. */
  private static String tenantCreated(UUID eventId, UUID tenantId, UUID ownerId) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"TenantCreated\",\"tenantId\":\""
        + tenantId
        + "\",\"aggregateId\":\""
        + tenantId
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\",\"ownerUserId\":\""
        + ownerId
        + "\",\"name\":\"Founders Ltd\",\"country\":\"GB\",\"currency\":\"GBP\",\"mode\":\"LIVE\"}";
  }
}
