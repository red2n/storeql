package com.storeql.iam.messaging;

import static com.storeql.test.Envelopes.exec;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Getting into a business's sandbox (22.8). tenant-svc announces a sandbox as a {@code
 * TenantCreated} with {@code mode: SANDBOX} and what it is a sandbox of; iam-svc keeps the mapping
 * and binds no owner (the owner already owns the live business). From then on the live owner trades
 * its token for one that names the sandbox as its tenant — an owner there, marked {@code sandbox}
 * in how it was authenticated, with no refresh token — and mints keys that start {@code sqk_test_}
 * and act in the sandbox alone. A sandbox switched off refuses both.
 *
 * <p>Requests carry the identity headers the gateway stamps from a verified token.
 */
@HelidonTest
class SandboxAccessIT {
  private static final PostgresSupport PG;
  private static final String PASSWORD = "a phrase long enough";
  private static final String KEYS = "/auth/admin/api-keys";

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
  @Inject UserRepository users;
  @Inject com.storeql.service.TenantDataErasureHandler erasure;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── the token ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "The live owner trades its token for one in the sandbox: an owner there, marked sandbox, no"
          + " refresh; nobody else, and never from inside the sandbox")
  void theOwnerEntersTheSandbox() {
    Business live = business("acme");
    UUID sandbox = sandboxOf(live);

    Answer a = call("POST", "/auth/sandbox/token", live.owner(), null);
    assertThat(a.body().toString(), a.status(), is(200));
    JsonObject d = a.data();
    assertThat(d.getString("tenantId"), is(sandbox.toString()));
    assertThat(d.getString("tokenType"), is("Bearer"));
    assertThat(d.getJsonNumber("expiresInSeconds").longValue() > 0, is(true));
    assertThat(d.containsKey("refreshToken") && !d.isNull("refreshToken"), is(false));
    DecodedJWT token = JWT.decode(d.getString("accessToken"));
    assertThat(token.getSubject(), is(live.ownerId().toString()));
    assertThat(token.getClaim("tenant").asString(), is(sandbox.toString()));
    assertThat(token.getClaim("roles").asList(String.class), is(List.of("OWNER")));
    assertThat(token.getClaim("amr").asList(String.class), is(List.of("sandbox")));
    assertThat(token.getClaim("type").asString(), is("STAFF"));
    assertThat(token.getClaim("email").asString(), is(live.email()));
    assertThat(token.getClaim("storeIds").isMissing(), is(true));

    // Not from inside the sandbox: a sandbox of a sandbox is nothing.
    Answer nested =
        call("POST", "/auth/sandbox/token", new Caller(live.ownerId(), "OWNER", sandbox), null);
    assertThat(nested.body().toString(), nested.status(), is(409));
    assertThat(nested.code(), is("SANDBOX_NESTED"));
    // Not a manager.
    Caller manager = staff(live, "acme-manager@example.com", "MANAGER");
    assertThat(call("POST", "/auth/sandbox/token", manager, null).status(), is(403));
    // Not a business with no sandbox.
    Business plain = business("plain");
    Answer none = call("POST", "/auth/sandbox/token", plain.owner(), null);
    assertThat(none.status(), is(404));
    assertThat(none.code(), is("SANDBOX_NOT_FOUND"));
  }

  // ── keys ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A sandbox key starts sqk_test_, acts in the sandbox alone, is listed beside the live keys and"
          + " revoked from the live business; from inside the sandbox every key minted is a sandbox key")
  void sandboxKeys() {
    Business live = business("brix");
    UUID sandbox = sandboxOf(live);

    Answer made =
        call(
            "POST",
            KEYS,
            live.owner(),
            "{\"name\":\"ERP test\",\"role\":\"STOREKEEPER\",\"sandbox\":true}");
    assertThat(made.body().toString(), made.status(), is(201));
    String key = made.data().getString("key");
    assertThat(key.startsWith("sqk_test_"), is(true));
    assertThat(key.length(), is(49));
    assertThat(made.data().getString("prefix"), is(key.substring(0, 12)));
    assertThat(made.data().getBoolean("sandbox"), is(true));
    String sandboxKeyId = made.data().getString("id");

    Answer liveKey =
        call("POST", KEYS, live.owner(), "{\"name\":\"ERP\",\"role\":\"STOREKEEPER\"}");
    assertThat(liveKey.status(), is(201));
    assertThat(liveKey.data().getBoolean("sandbox"), is(false));
    assertThat(liveKey.data().getString("key").startsWith("sqk_test_"), is(false));

    // The gateway's question: the key acts as the sandbox.
    Answer asked =
        call("POST", "/platform/api-keys/introspect", Caller.PLATFORM, "{\"key\":\"" + key + "\"}");
    assertThat(asked.status(), is(200));
    assertThat(asked.data().getBoolean("active"), is(true));
    assertThat(asked.data().getString("tenantId"), is(sandbox.toString()));
    assertThat(asked.data().getBoolean("sandbox"), is(true));

    // Listed beside the live keys, flagged — from the live business and from inside the sandbox.
    List<String> fromLive = ids(call("GET", KEYS, live.owner(), null));
    assertThat(fromLive, containsInAnyOrder(sandboxKeyId, liveKey.data().getString("id")));
    Caller inside = new Caller(live.ownerId(), "OWNER", sandbox);
    assertThat(
        ids(call("GET", KEYS, inside, null)),
        containsInAnyOrder(sandboxKeyId, liveKey.data().getString("id")));

    // Minted from inside, a key is a sandbox key whether or not it says so.
    Answer insideKey =
        call("POST", KEYS, inside, "{\"name\":\"From inside\",\"role\":\"MANAGER\"}");
    assertThat(insideKey.body().toString(), insideKey.status(), is(201));
    assertThat(insideKey.data().getBoolean("sandbox"), is(true));
    assertThat(insideKey.data().getString("key").startsWith("sqk_test_"), is(true));
    Answer insideAsked =
        call(
            "POST",
            "/platform/api-keys/introspect",
            Caller.PLATFORM,
            "{\"key\":\"" + insideKey.data().getString("key") + "\"}");
    assertThat(insideAsked.data().getString("tenantId"), is(sandbox.toString()));

    // Revoked from the live business, where it was made.
    Answer revoked = call("DELETE", KEYS + "/" + sandboxKeyId, live.owner(), null);
    assertThat(revoked.body().toString(), revoked.status(), is(200));
    assertThat(revoked.data().isNull("revokedAt"), is(false));
    assertThat(
        call("POST", "/platform/api-keys/introspect", Caller.PLATFORM, "{\"key\":\"" + key + "\"}")
            .data()
            .getBoolean("active"),
        is(false));

    // A business with no sandbox cannot mint one.
    Business plain = business("plain-keys");
    Answer refused =
        call("POST", KEYS, plain.owner(), "{\"name\":\"x\",\"role\":\"CASHIER\",\"sandbox\":true}");
    assertThat(refused.status(), is(404));
    assertThat(refused.code(), is("SANDBOX_NOT_FOUND"));
    // Another business's sandbox keys are its own.
    assertThat(ids(call("GET", KEYS, plain.owner(), null)), is(List.of()));
  }

  // ── switched off ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A sandbox switched off refuses its keys and is no longer entered; the next one is")
  void aSandboxSwitchedOff() {
    Business live = business("crux");
    UUID first = sandboxOf(live);
    String key =
        call("POST", KEYS, live.owner(), "{\"name\":\"t\",\"role\":\"CASHIER\",\"sandbox\":true}")
            .data()
            .getString("key");
    assertThat(
        call("POST", "/platform/api-keys/introspect", Caller.PLATFORM, "{\"key\":\"" + key + "\"}")
            .data()
            .getBoolean("active"),
        is(true));

    // tenant-svc's TenantStatusChanged INACTIVE lands in the projection this service keeps.
    exec(
        PG,
        "INSERT INTO iam.tenant_status (tenant_id, status) VALUES ('"
            + first
            + "', 'INACTIVE')"
            + " ON CONFLICT (tenant_id) DO UPDATE SET status = 'INACTIVE'");
    Answer asked =
        call("POST", "/platform/api-keys/introspect", Caller.PLATFORM, "{\"key\":\"" + key + "\"}");
    assertThat(asked.data().getBoolean("active"), is(false));
    assertThat(asked.data().getString("reason"), is("tenant suspended"));
    Answer gone = call("POST", "/auth/sandbox/token", live.owner(), null);
    assertThat(gone.status(), is(404));
    assertThat(gone.code(), is("SANDBOX_NOT_FOUND"));

    // A new sandbox announced: that is the one entered now.
    UUID second = sandboxOf(live);
    assertThat(second, not(is(first)));
    Answer again = call("POST", "/auth/sandbox/token", live.owner(), null);
    assertThat(again.body().toString(), again.status(), is(200));
    assertThat(again.data().getString("tenantId"), is(second.toString()));
  }

  /**
   * Deleting a sandbox switches it off and then erases its data in every service. The erasure took
   * the switched-off status with it and left the pair naming the sandbox (the pair's erasure
   * predicate was declared and never used), so the sandbox read as live again and its owner could
   * enter a deleted sandbox (found by k6 sandbox-flow). Erased, the pair goes too.
   */
  @Test
  @DisplayName("A sandbox switched off and then erased is never entered again")
  void anErasedSandboxIsNeverEnteredAgain() {
    Business live = business("erased");
    UUID sandbox = sandboxOf(live);
    assertThat(call("POST", "/auth/sandbox/token", live.owner(), null).status(), is(200));

    exec(
        PG,
        "INSERT INTO iam.tenant_status (tenant_id, status) VALUES ('"
            + sandbox
            + "', 'INACTIVE')"
            + " ON CONFLICT (tenant_id) DO UPDATE SET status = 'INACTIVE'");
    erasure.handle(
        "{\"eventType\":\"TenantDataErasureDue\",\"eventId\":\""
            + Ids.newId()
            + "\",\"tenantId\":\""
            + sandbox
            + "\"}");

    assertThat(
        "the pair naming the erased sandbox is gone",
        com.storeql.test.Envelopes.scalar(
            PG,
            "SELECT count(*) FROM iam.tenant_sandboxes WHERE sandbox_tenant_id = '"
                + sandbox
                + "'"),
        is("0"));
    Answer gone = call("POST", "/auth/sandbox/token", live.owner(), null);
    assertThat(gone.body().toString(), gone.status(), is(404));
    assertThat(gone.code(), is("SANDBOX_NOT_FOUND"));

    // The live business is untouched: its owner still makes and enters a new sandbox.
    UUID next = sandboxOf(live);
    Answer again = call("POST", "/auth/sandbox/token", live.owner(), null);
    assertThat(again.body().toString(), again.status(), is(200));
    assertThat(again.data().getString("tenantId"), is(next.toString()));
  }

  @Test
  @DisplayName(
      "The same announcement twice makes one mapping; the owner is never re-bound to the sandbox")
  void theAnnouncementIsIdempotent() {
    Business live = business("dyne");
    UUID sandbox = Ids.newId();
    String json = tenantCreated(Ids.newId(), sandbox, live.ownerId(), "SANDBOX", live.tenant());
    tenants.handle(json);
    tenants.handle(json);
    assertThat(
        com.storeql.test.Envelopes.scalar(
            PG,
            "SELECT count(*) FROM iam.tenant_sandboxes WHERE sandbox_tenant_id = '"
                + sandbox
                + "'"),
        is("1"));
    assertThat(
        com.storeql.test.Envelopes.scalar(
            PG, "SELECT tenant_id FROM iam.users WHERE id = '" + live.ownerId() + "'"),
        is(live.tenant().toString()));
    // A live announcement still binds an owner, as it always has.
    UUID owner = register("dyne-second-owner@example.com");
    UUID other = Ids.newId();
    tenants.handle(tenantCreated(Ids.newId(), other, owner, "LIVE", null));
    assertThat(
        com.storeql.test.Envelopes.scalar(
            PG, "SELECT tenant_id FROM iam.users WHERE id = '" + owner + "'"),
        is(other.toString()));
    // One without a mode at all — an event from before sandboxes — is live.
    UUID third = Ids.newId();
    UUID thirdOwner = register("dyne-third-owner@example.com");
    tenants.handle(tenantCreated(Ids.newId(), third, thirdOwner, null, null));
    assertThat(
        com.storeql.test.Envelopes.scalar(
            PG, "SELECT tenant_id FROM iam.users WHERE id = '" + thirdOwner + "'"),
        is(third.toString()));
    assertThat(
        com.storeql.test.Envelopes.scalar(
            PG, "SELECT count(*) FROM iam.tenant_sandboxes WHERE live_tenant_id = '" + third + "'"),
        is("0"));
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

  private record Caller(UUID userId, String roles, UUID tenantId) {
    static final Caller PLATFORM = new Caller(Ids.newId(), "PLATFORM_ADMIN", null);
  }

  /** A live business with an owner, announced as tenant-svc would. */
  private record Business(UUID tenant, UUID ownerId, String email, Caller owner) {}

  private Business business(String label) {
    UUID tenant = Ids.newId();
    String email = label + "-owner-" + Ids.newId().toString().substring(30) + "@example.com";
    UUID ownerId = register(email);
    tenants.handle(tenantCreated(Ids.newId(), tenant, ownerId, "LIVE", null));
    return new Business(tenant, ownerId, email, new Caller(ownerId, "OWNER", tenant));
  }

  /** A sandbox of the business, announced as tenant-svc would. */
  private UUID sandboxOf(Business live) {
    UUID sandbox = Ids.newId();
    tenants.handle(tenantCreated(Ids.newId(), sandbox, live.ownerId(), "SANDBOX", live.tenant()));
    return sandbox;
  }

  private Caller staff(Business b, String email, String tier) {
    UUID id = register(email);
    users.bindStaffOnce(Ids.newId(), "sandbox-access-it", id, b.tenant(), tier, Ids.newId());
    return new Caller(id, tier, b.tenant());
  }

  private static String tenantCreated(
      UUID eventId, UUID tenantId, UUID ownerId, String mode, UUID sandboxOf) {
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
        + "\",\"name\":\"Acme\",\"country\":\"GB\",\"currency\":\"GBP\""
        + (mode == null ? "" : ",\"mode\":\"" + mode + "\"")
        + (sandboxOf == null ? "" : ",\"sandboxOf\":\"" + sandboxOf + "\"")
        + "}";
  }

  private static List<String> ids(Answer list) {
    assertThat(list.body().toString(), list.status(), is(200));
    return list.data().getJsonArray("items").getValuesAs(JsonObject.class).stream()
        .map(k -> k.getString("id"))
        .toList();
  }

  private Answer call(String method, String path, Caller who, String json) {
    Invocation.Builder b = target.path(path).request();
    if (who.userId() != null) b = b.header("X-User-Id", who.userId());
    if (who.roles() != null) b = b.header("X-Roles", who.roles());
    if (who.tenantId() != null) b = b.header("X-Tenant-Id", who.tenantId());
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "DELETE" -> b.delete();
          default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
        };
    String text = r.readEntity(String.class);
    JsonObject body =
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject();
    return new Answer(r.getStatus(), body);
  }

  private UUID register(String email) {
    Answer a =
        call(
            "POST",
            "/auth/register",
            new Caller(null, null, null),
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
  }
}
