package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.iam.domain.TokenIdentity;
import com.storeql.iam.repo.UserRepository;
import com.storeql.iam.service.AuthService;
import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a token says about a login is one reading of the database (SJ-D63). Sign-in read the user's
 * row, checked the password for a few hundred milliseconds, then read the roles: a staff removal
 * that committed in between left a token with the new roles beside the old tenant — found live,
 * under load, by the role-model suite.
 */
@HelidonTest
class TokenIdentityIT {

  private static final PostgresSupport PG;
  private static final String PASSWORD = "a phrase long enough";
  private static final String CONSUMER = "token-identity-it";

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
  @Inject UserRepository users;
  @Inject AuthService auth;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private DecodedJWT session(String path, String email) {
    Response r =
        target
            .path(path)
            .request()
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus() / 100, is(2));
    return JWT.decode(
        Json.createReader(new StringReader(body))
            .readObject()
            .getJsonObject("data")
            .getString("accessToken"));
  }

  @Test
  @DisplayName("One reading: a cashier at a store, then let go, then nobody at all")
  void theIdentityFollowsTheAssignment() {
    UUID userId = Ids.parse(session("/auth/register", "identity-1@example.com").getSubject());
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();

    TokenIdentity shopper = users.tokenIdentity(userId).orElseThrow();
    assertThat(shopper.tenantId(), is(nullValue()));
    assertThat(shopper.type(), is("CUSTOMER"));
    assertThat(shopper.roles(), contains("CUSTOMER"));
    assertThat(shopper.storeIds(), is(empty()));
    assertThat("no custom role, no permission claim", shopper.permissions(), is(nullValue()));

    // A StaffAssigned stamps nobody in now (29 Sep 2026), so the shopper is taken on as one was
    // before: stamped into the tenant, then bound.
    takenOnBeforeSeparateIdentities(userId, tenant);
    users.bindStaffOnce(Ids.newId(), CONSUMER, userId, tenant, "CASHIER", store);
    TokenIdentity cashier = users.tokenIdentity(userId).orElseThrow();
    assertThat(cashier.tenantId(), is(tenant));
    assertThat(cashier.type(), is("STAFF"));
    assertThat(cashier.roles(), containsInAnyOrder("CUSTOMER", "CASHIER"));
    assertThat(
        "the shopper's own role has no store and narrows nothing",
        cashier.storeIds(),
        contains(store));

    // A custom role at a second store: the permission claim appears, the scope is both stores.
    UUID second = Ids.newId();
    users.bindStaffOnce(
        Ids.newId(),
        CONSUMER,
        userId,
        tenant,
        "MANAGER",
        second,
        "BUYER",
        Set.of("purchasing.approve"),
        java.time.Instant.now());
    TokenIdentity buyer = users.tokenIdentity(userId).orElseThrow();
    assertThat(buyer.storeIds(), containsInAnyOrder(store, second));
    assertThat(buyer.permissions().contains("purchasing.approve"), is(true));

    users.unbindStaffOnce(Ids.newId(), CONSUMER, userId, tenant, "MANAGER", second);
    assertThat(
        "one store left, still the tenant's",
        users.tokenIdentity(userId).orElseThrow().tenantId(),
        is(tenant));
    users.unbindStaffOnce(Ids.newId(), CONSUMER, userId, tenant, "CASHIER", store);
    TokenIdentity gone = users.tokenIdentity(userId).orElseThrow();
    assertThat(
        "the last staff role gone, the tenant goes with it", gone.tenantId(), is(nullValue()));
    assertThat(gone.type(), is("CUSTOMER"));
    assertThat(gone.roles(), contains("CUSTOMER"));

    // A login made in the business by staff provisioning stays in it when let go, holding no role.
    UUID made = Ids.parse(auth.provisionStaff(tenant, "identity-4@example.com", PASSWORD).userId());
    users.bindStaffOnce(Ids.newId(), CONSUMER, made, tenant, "CASHIER", store);
    assertThat(users.tokenIdentity(made).orElseThrow().roles(), contains("CASHIER"));
    users.unbindStaffOnce(Ids.newId(), CONSUMER, made, tenant, "CASHIER", store);
    TokenIdentity letGo = users.tokenIdentity(made).orElseThrow();
    assertThat(letGo.tenantId(), is(tenant));
    assertThat(letGo.type(), is("STAFF"));
    assertThat(letGo.roles(), is(empty()));

    assertThat(users.tokenIdentity(Ids.newId()).isPresent(), is(false));

    // An owner's role has no store: tenant-wide, and not narrowed by a store-scoped role held too.
    UUID ownerId = Ids.parse(session("/auth/register", "identity-3@example.com").getSubject());
    users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenant, "OWNER");
    users.bindStaffOnce(Ids.newId(), CONSUMER, ownerId, tenant, "CASHIER", store);
    TokenIdentity owner = users.tokenIdentity(ownerId).orElseThrow();
    assertThat(owner.roles(), containsInAnyOrder("CUSTOMER", "OWNER", "CASHIER"));
    assertThat("an empty scope means every store", owner.storeIds(), is(empty()));
  }

  @Test
  @DisplayName("Signed in while being let go and taken on again: never a tenant without a role")
  void noTokenMixesTwoMomentsOfTheSameLogin() throws Exception {
    String email = "identity-2@example.com";
    UUID userId = Ids.parse(session("/auth/register", email).getSubject());
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();

    // One thread takes the login on and lets it go, over and over, each a single transaction. The
    // login moves only as a shopper taken on before 29 Sep 2026 does — stamped in with its role,
    // then moved back out when let go — so it is taken on here as one was then...
    AtomicBoolean stop = new AtomicBoolean();
    Thread churn =
        new Thread(
            () -> {
              while (!stop.get()) {
                takenOnAsCashierBeforeSeparateIdentities(userId, tenant, store);
                pause();
                users.unbindStaffOnce(Ids.newId(), CONSUMER, userId, tenant, "CASHIER", store);
                pause();
              }
            });
    churn.start();

    // ...while the login signs in, each sign-in spending its time on the password check. Every
    // token must be one moment or the other, whole: a cashier of the tenant, or a shopper of none.
    List<String> mixed = new ArrayList<>();
    try {
      for (int i = 0; i < 40; i++) {
        DecodedJWT token = session("/auth/login", email);
        List<String> roles = token.getClaim("roles").asList(String.class);
        boolean cashier = roles.contains("CASHIER");
        boolean named = !token.getClaim("tenant").isMissing() && !token.getClaim("tenant").isNull();
        boolean staff = "STAFF".equals(token.getClaim("type").asString());
        if (cashier != named || cashier != staff) {
          mixed.add("roles=" + roles + " tenant=" + named + " type=" + token.getClaim("type"));
        }
      }
    } finally {
      stop.set(true);
      churn.join();
    }
    assertThat("tokens that mixed two moments: " + mixed, mixed, is(empty()));
  }

  /** A shopper's login stamped into the tenant, as a StaffAssigned did before 29 Sep 2026. */
  private static void takenOnBeforeSeparateIdentities(UUID userId, UUID tenant) {
    Envelopes.exec(
        PG,
        "UPDATE iam.users SET tenant_id = '"
            + tenant
            + "', type = 'STAFF' WHERE id = '"
            + userId
            + "' AND tenant_id IS NULL");
  }

  /**
   * As {@link #takenOnBeforeSeparateIdentities}, with a CASHIER role at the store, in one statement
   * and so one transaction — as that StaffAssigned stamped and bound together.
   */
  private static void takenOnAsCashierBeforeSeparateIdentities(
      UUID userId, UUID tenant, UUID store) {
    Envelopes.exec(
        PG,
        "WITH taken AS (UPDATE iam.users SET tenant_id = '"
            + tenant
            + "', type = 'STAFF' WHERE id = '"
            + userId
            + "' AND tenant_id IS NULL RETURNING id)"
            + " INSERT INTO iam.user_roles (id, user_id, role_id, store_id)"
            + " SELECT '"
            + Ids.newId()
            + "', taken.id, r.id, '"
            + store
            + "' FROM taken, iam.roles r WHERE r.name = 'CASHIER'");
  }

  private static void pause() {
    try {
      Thread.sleep(15);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
