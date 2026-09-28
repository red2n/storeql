package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.auth0.jwt.JWT;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * A business's own API keys (22.7): an owner mints one for the systems it runs — an ERP, an
 * accounting package, an integrator — in a staff role and, if it likes, for some stores and until a
 * day; the key is shown once and never again; the platform's gateway asks this service what a key
 * may do and is told, until the key is revoked, has expired, or the business is switched off. Never
 * an owner's key: a leaked key must not be able to close the business, export it or bill it.
 *
 * <p>Requests carry the identity headers the gateway would have stamped from a verified token.
 */
@HelidonTest
class ApiKeyIT {

  private static final PostgresSupport PG;
  private static final String PASSWORD = "a phrase long enough";
  private static final String CONSUMER = "api-key-it";

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

  @AfterAll
  static void stop() {
    PG.stop();
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
    static final Caller NOBODY = new Caller(null, null, null);
    static final Caller PLATFORM = new Caller(Ids.newId(), "PLATFORM_ADMIN", null);
  }

  private Answer call(String method, String path, Caller who, String json) {
    WebTarget t = target;
    String[] parts = path.split("\\?", 2);
    t = t.path(parts[0]);
    if (parts.length == 2) {
      for (String pair : parts[1].split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv.length == 2 ? kv[1] : "");
      }
    }
    Invocation.Builder b = t.request();
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
            Caller.NOBODY,
            "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
  }

  /** A business with an owner and one store. */
  private record Business(UUID tenant, UUID store, UUID ownerId, Caller owner) {}

  private Business business(String label) {
    UUID tenant = Ids.newId();
    UUID ownerId = register(label + "-owner@example.com");
    users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenant, "OWNER");
    return new Business(tenant, Ids.newId(), ownerId, new Caller(ownerId, "OWNER", tenant));
  }

  private Caller staff(Business b, String email, String tier) {
    UUID id = register(email);
    users.bindStaffOnce(Ids.newId(), CONSUMER, id, b.tenant(), tier, b.store());
    return new Caller(id, tier, b.tenant());
  }

  private static String keyBody(String name, String role, UUID store, String expiresAt) {
    return "{\"name\":"
        + (name == null ? "null" : "\"" + name + "\"")
        + ",\"role\":"
        + (role == null ? "null" : "\"" + role + "\"")
        + (store == null ? "" : ",\"storeIds\":[\"" + store + "\"]")
        + (expiresAt == null ? "" : ",\"expiresAt\":\"" + expiresAt + "\"")
        + "}";
  }

  private Answer mint(Caller who, String json) {
    return call("POST", "/auth/admin/api-keys", who, json);
  }

  private Answer introspect(Caller who, String key) {
    return call("POST", "/platform/api-keys/introspect", who, "{\"key\":\"" + key + "\"}");
  }

  private Answer list(Caller who, String query) {
    return call("GET", "/auth/admin/api-keys" + (query == null ? "" : query), who, null);
  }

  /** Absent or null: JSON-B leaves a null out, and a reader must not care which. */
  private static boolean absent(JsonObject obj, String name) {
    return !obj.containsKey(name) || obj.isNull(name);
  }

  /** A connection in iam-svc's own schema, which the service migrates and writes. */
  private static java.sql.Connection iam() throws SQLException {
    var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  private static int audit(UUID tenant, String action) {
    try (var c = iam();
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?")) {
      ps.setObject(1, tenant);
      ps.setString(2, action);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void sql(String statement, Object... args) {
    try (var c = iam();
        var ps = c.prepareStatement(statement)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── minting ────────────────────────────────────────────────────────────────

  @Test
  void anOwnerMintsAKeyShownOnceAndListedWithoutIt() {
    Business b = business("mint");
    Answer made = mint(b.owner(), keyBody("Warehouse ERP", "STOREKEEPER", b.store(), null));
    assertThat(made.body().toString(), made.status(), is(201));
    JsonObject key = made.data();
    String secret = key.getString("key");
    assertThat(secret, startsWith("sqk_"));
    assertThat(secret.length(), is(44));
    assertThat(
        "the prefix is what the owner tells keys apart by",
        key.getString("prefix"),
        is(secret.substring(0, 12)));
    assertThat(key.getString("name"), is("Warehouse ERP"));
    assertThat(key.getString("role"), is("STOREKEEPER"));
    assertThat(key.getJsonArray("storeIds").getString(0), is(b.store().toString()));
    assertThat(key.getString("createdBy"), is(b.ownerId().toString()));
    assertThat(absent(key, "expiresAt"), is(true));
    assertThat(absent(key, "lastUsedAt"), is(true));
    assertThat(absent(key, "revokedAt"), is(true));

    // Listed once, without the secret; a manager may read the list, and only this business's.
    Caller manager = staff(b, "mint-manager@example.com", "MANAGER");
    Answer listed = list(manager, null);
    assertThat(listed.body().toString(), listed.status(), is(200));
    JsonArray items = listed.data().getJsonArray("items");
    assertThat(items, hasSize(1));
    JsonObject row = items.getJsonObject(0);
    assertThat(row.getString("id"), is(key.getString("id")));
    assertThat("the key is shown once and never again", row.containsKey("key"), is(false));
    assertThat(row.getString("prefix"), is(secret.substring(0, 12)));
    assertThat(absent(listed.data(), "nextCursor"), is(true));

    Business other = business("mint-other");
    assertThat(list(other.owner(), null).data().getJsonArray("items"), hasSize(0));
    assertThat(audit(b.tenant(), "API_KEY_CREATED"), is(1));
  }

  @Test
  void whoMayMintAndInWhichRoles() {
    Business b = business("who");
    Caller manager = staff(b, "who-manager@example.com", "MANAGER");
    Caller keeper = staff(b, "who-keeper@example.com", "STOREKEEPER");
    String fine = keyBody("Till feed", "CASHIER", null, null);
    assertThat("a manager may read keys, not mint them", mint(manager, fine).status(), is(403));
    assertThat(mint(keeper, fine).status(), is(403));
    assertThat(mint(Caller.NOBODY, fine).status(), anyOf(is(401), is(403)));

    // Never an owner's key, nor the platform's; a role nobody has is refused too.
    for (String role : List.of("OWNER", "PLATFORM_ADMIN", "GOD", "manager")) {
      Answer refused = mint(b.owner(), keyBody("x", role, null, null));
      assertThat(role, refused.status(), is(400));
      assertThat(role, refused.code(), is("API_KEY_ROLE_INVALID"));
    }
    assertThat(mint(b.owner(), keyBody("x", null, null, null)).status(), is(400));
    assertThat(mint(b.owner(), keyBody("x", "", null, null)).status(), is(400));

    // A name it can be told apart by, and a day in the future if any.
    assertThat(mint(b.owner(), keyBody("", "MANAGER", null, null)).status(), is(400));
    assertThat(mint(b.owner(), keyBody(null, "MANAGER", null, null)).status(), is(400));
    assertThat(mint(b.owner(), keyBody("n".repeat(81), "MANAGER", null, null)).status(), is(400));
    Answer past =
        mint(
            b.owner(),
            keyBody("x", "MANAGER", null, Instant.now().minus(1, ChronoUnit.DAYS).toString()));
    assertThat(past.status(), is(400));
    assertThat(past.code(), is("API_KEY_EXPIRY_PAST"));
    assertThat(mint(b.owner(), keyBody("x", "MANAGER", null, "soon")).status(), is(400));

    // Store ids are ids.
    Answer badStore =
        mint(b.owner(), "{\"name\":\"x\",\"role\":\"STOREKEEPER\",\"storeIds\":[\"not-an-id\"]}");
    assertThat(badStore.status(), is(400));
    assertThat(badStore.code(), is("INVALID_UUID"));
    Answer v4 =
        mint(
            b.owner(),
            "{\"name\":\"x\",\"role\":\"STOREKEEPER\",\"storeIds\":[\"123e4567-e89b-42d3-a456-426614174000\"]}");
    assertThat(v4.status(), is(400));
    assertThat(v4.code(), is("INVALID_UUID"));

    String tomorrow =
        Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    Answer dated = mint(b.owner(), keyBody("Accounts", "MANAGER", null, tomorrow));
    assertThat(dated.body().toString(), dated.status(), is(201));
    assertThat(Instant.parse(dated.data().getString("expiresAt")), is(Instant.parse(tomorrow)));
  }

  // ── what a key may do, and when it stops ───────────────────────────────────

  @Test
  void introspectionSaysWhatAKeyMayDoAndStopsWhenItIsRevokedExpiredOrSuspended() {
    Business b = business("look");
    Answer made = mint(b.owner(), keyBody("Accounts", "MANAGER", null, null));
    assertThat(made.body().toString(), made.status(), is(201));
    String id = made.data().getString("id");
    String secret = made.data().getString("key");

    Answer who = introspect(Caller.PLATFORM, secret);
    assertThat(who.body().toString(), who.status(), is(200));
    assertThat(who.data().getBoolean("active"), is(true));
    assertThat(who.data().getString("keyId"), is(id));
    assertThat(who.data().getString("tenantId"), is(b.tenant().toString()));
    assertThat(who.data().getJsonArray("roles").getString(0), is("MANAGER"));
    assertThat(who.data().getJsonArray("storeIds"), hasSize(0));
    assertThat(who.data().getString("name"), is("Accounts"));
    assertThat(
        "a use is remembered",
        absent(list(b.owner(), null).data().getJsonArray("items").getJsonObject(0), "lastUsedAt"),
        is(false));

    // Only the platform's own door asks; a business cannot probe keys.
    assertThat(introspect(b.owner(), secret).status(), is(403));
    assertThat(introspect(Caller.NOBODY, secret).status(), anyOf(is(401), is(403)));

    // A key that is not one: a character off, or nothing like a key.
    String forged = secret.substring(0, 43) + (secret.endsWith("A") ? "B" : "A");
    Answer unknown = introspect(Caller.PLATFORM, forged);
    assertThat(unknown.status(), is(200));
    assertThat(unknown.data().getBoolean("active"), is(false));
    assertThat(unknown.data().getString("reason"), is("unknown"));
    assertThat(absent(unknown.data(), "tenantId"), is(true));
    assertThat(introspect(Caller.PLATFORM, "sqk_short").data().getBoolean("active"), is(false));
    assertThat(introspect(Caller.PLATFORM, "").status(), is(400));

    // Revoking: only this business's owner, once.
    Business other = business("look-other");
    Caller manager = staff(b, "look-manager@example.com", "MANAGER");
    assertThat(call("DELETE", "/auth/admin/api-keys/" + id, other.owner(), null).status(), is(404));
    assertThat(call("DELETE", "/auth/admin/api-keys/" + id, manager, null).status(), is(403));
    assertThat(
        call("DELETE", "/auth/admin/api-keys/" + Ids.newId(), b.owner(), null).code(),
        is("API_KEY_NOT_FOUND"));
    Answer revoked = call("DELETE", "/auth/admin/api-keys/" + id, b.owner(), null);
    assertThat(revoked.body().toString(), revoked.status(), is(200));
    assertThat(revoked.data().getString("revokedAt"), not(nullValue()));
    Answer again = call("DELETE", "/auth/admin/api-keys/" + id, b.owner(), null);
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("API_KEY_REVOKED"));
    Answer stopped = introspect(Caller.PLATFORM, secret);
    assertThat(stopped.data().getBoolean("active"), is(false));
    assertThat(stopped.data().getString("reason"), is("revoked"));
    assertThat(
        "still listed, marked revoked",
        absent(list(b.owner(), null).data().getJsonArray("items").getJsonObject(0), "revokedAt"),
        is(false));
    assertThat(audit(b.tenant(), "API_KEY_REVOKED"), is(1));

    // Expired: the day passes.
    Answer dated =
        mint(
            b.owner(),
            keyBody(
                "Nightly",
                "STOREKEEPER",
                b.store(),
                Instant.now().plus(1, ChronoUnit.HOURS).toString()));
    assertThat(dated.status(), is(201));
    assertThat(
        introspect(Caller.PLATFORM, dated.data().getString("key")).data().getBoolean("active"),
        is(true));
    sql(
        "UPDATE api_keys SET expires_at = now() - interval '1 minute' WHERE id = ?",
        Ids.parse(dated.data().getString("id")));
    Answer expired = introspect(Caller.PLATFORM, dated.data().getString("key"));
    assertThat(expired.data().getBoolean("active"), is(false));
    assertThat(expired.data().getString("reason"), is("expired"));

    // A business switched off takes its keys with it, and back on brings them back.
    Business off = business("look-off");
    Answer offKey = mint(off.owner(), keyBody("ERP", "MANAGER", null, null));
    assertThat(offKey.status(), is(201));
    sql("INSERT INTO tenant_status (tenant_id, status) VALUES (?, 'INACTIVE')", off.tenant());
    Answer suspended = introspect(Caller.PLATFORM, offKey.data().getString("key"));
    assertThat(suspended.data().getBoolean("active"), is(false));
    assertThat(suspended.data().getString("reason"), is("tenant suspended"));
    sql("UPDATE tenant_status SET status = 'ACTIVE' WHERE tenant_id = ?", off.tenant());
    assertThat(
        introspect(Caller.PLATFORM, offKey.data().getString("key")).data().getBoolean("active"),
        is(true));
  }

  @Test
  void theListIsPagedInTheOrderTheKeysWereMade() {
    Business b = business("page");
    List<String> ids = new java.util.ArrayList<>();
    for (String name : List.of("first", "second", "third")) {
      Answer made = mint(b.owner(), keyBody(name, "CASHIER", null, null));
      assertThat(made.status(), is(201));
      ids.add(made.data().getString("id"));
    }
    Answer page = list(b.owner(), "?limit=2");
    assertThat(page.body().toString(), page.status(), is(200));
    JsonArray items = page.data().getJsonArray("items");
    assertThat(items, hasSize(2));
    assertThat(items.getJsonObject(0).getString("name"), is("first"));
    assertThat(items.getJsonObject(1).getString("name"), is("second"));
    String cursor = page.data().getString("nextCursor");
    Answer rest = list(b.owner(), "?limit=2&after=" + cursor);
    assertThat(rest.data().getJsonArray("items"), hasSize(1));
    assertThat(rest.data().getJsonArray("items").getJsonObject(0).getString("id"), is(ids.get(2)));
    assertThat(absent(rest.data(), "nextCursor"), is(true));
    assertThat(list(b.owner(), "?limit=0").status(), anyOf(is(200), is(400)));
    assertThat(list(b.owner(), "?after=not-an-id").status(), is(400));
  }
}
