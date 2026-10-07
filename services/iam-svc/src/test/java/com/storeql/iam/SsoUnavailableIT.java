package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import com.auth0.jwt.JWT;
import com.storeql.iam.repo.UserRepository;
import com.storeql.iam.sso.FakeOidcProvider;
import com.storeql.iam.sso.Pkce;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deployment whose operator never set {@code storeql.sso.callback-url} (SsoIT always does).
 * Single sign-on is unavailable there, and says so: a clean 503 with a code of its own, before
 * anything about the business or its provider is looked at, and nothing opened. What a business can
 * do for itself — save its provider, read its readiness — still works, and the readiness names the
 * step only the operator can take.
 */
@HelidonTest
class SsoUnavailableIT {

  private static final PostgresSupport PG;
  private static final FakeOidcProvider PROVIDER;
  private static final String PASSWORD = "a phrase long enough";
  private static final String APP = "http://localhost:8088/";
  private static final String CONSUMER = "sso-unavailable-it";

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    try {
      PROVIDER = new FakeOidcProvider();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.jwt.secret", "integration-test-secret-of-at-least-32-chars");
    // The point of this class: another test in this JVM may have set it, and this deployment has
    // not.
    System.clearProperty("storeql.sso.callback-url");
    System.setProperty("storeql.sso.insecure-hosts", "localhost");
  }

  @Inject WebTarget target;
  @Inject UserRepository users;

  @AfterAll
  static void stop() {
    PROVIDER.close();
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
  }

  private Answer call(String method, String path, Caller who, String json) {
    Invocation.Builder b = target.path(path).request();
    if (who.userId() != null) b = b.header("X-User-Id", who.userId());
    if (who.roles() != null) b = b.header("X-Roles", who.roles());
    if (who.tenantId() != null) b = b.header("X-Tenant-Id", who.tenantId());
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
          default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
        };
    String text = r.readEntity(String.class);
    JsonObject body =
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject();
    return new Answer(r.getStatus(), body);
  }

  private Caller owner(String label) {
    Answer a =
        call(
            "POST",
            "/auth/register",
            Caller.NOBODY,
            "{\"email\":\"" + label + "@example.com\",\"password\":\"" + PASSWORD + "\"}");
    assertThat(a.body().toString(), a.status(), is(201));
    UUID ownerId = Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
    UUID tenant = Ids.newId();
    users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenant, "OWNER");
    return new Caller(ownerId, "OWNER", tenant);
  }

  private static String startBody(String slug) {
    return "{\"slug\":\""
        + slug
        + "\",\"codeChallenge\":\""
        + Pkce.challenge(Pkce.newVerifier())
        + "\",\"returnTo\":\""
        + APP
        + "\"}";
  }

  private static int flows(UUID tenant) throws SQLException {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      c.setSchema("iam");
      try (PreparedStatement ps =
          c.prepareStatement("SELECT count(*) FROM sso_flows WHERE tenant_id = ?")) {
        ps.setObject(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    }
  }

  // ── the tests ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A sign-in is refused where the deployment has no callback address, and opens nothing")
  void aSignInIsRefusedWhereTheDeploymentHasNoCallback() throws Exception {
    Caller owner = owner("sso-off-owner");
    String slug = "sso-off-" + Ids.newId().toString().substring(24);
    // The business has done everything it can: its provider is saved, enabled and answering.
    Answer saved =
        call(
            "PUT",
            "/auth/admin/sso",
            owner,
            "{\"slug\":\""
                + slug
                + "\",\"issuer\":\""
                + PROVIDER.issuer()
                + "\",\"clientId\":\""
                + FakeOidcProvider.CLIENT_ID
                + "\",\"clientSecret\":\""
                + FakeOidcProvider.CLIENT_SECRET
                + "\",\"enabled\":true,\"requiredTiers\":[]}");
    assertThat(saved.body().toString(), saved.status(), is(200));

    Answer refused = call("POST", "/auth/sso/start", Caller.NOBODY, startBody(slug));
    assertThat(refused.body().toString(), refused.status(), is(503));
    assertThat(refused.code(), is("SSO_UNAVAILABLE"));
    assertThat(
        "no authorization address and no data come back",
        refused.body().containsKey("data"),
        is(false));
    assertThat("no sign-in was opened for the business", flows(owner.tenantId()), is(0));
    assertThat("and the provider was never asked for a token", PROVIDER.tokenCalls(), is(0));

    // The deployment is judged before the business: a name nobody signs in with is the same
    // refusal.
    Answer nobody = call("POST", "/auth/sso/start", Caller.NOBODY, startBody("no-such-business"));
    assertThat(nobody.status(), is(503));
    assertThat(nobody.code(), is("SSO_UNAVAILABLE"));

    // A request that is not one is still a 400 first: the refusal is about a sign-in that could
    // start.
    Answer malformed =
        call("POST", "/auth/sso/start", Caller.NOBODY, "{\"slug\":\"" + slug + "\"}");
    assertThat(malformed.status(), is(400));
    assertThat(malformed.code(), is("VALIDATION_FAILED"));
    assertThat(flows(owner.tenantId()), is(0));
  }

  @Test
  @DisplayName(
      "Readiness names the operator's missing step, and a browser coming back is sent home with a reason")
  void readinessNamesTheMissingCallbackAndACallbackGoesNowhere() {
    Caller owner = owner("sso-off-ready");
    Answer ready = call("GET", "/auth/admin/sso/readiness", owner, null);
    assertThat(ready.body().toString(), ready.status(), is(200));
    assertThat(ready.data().getBoolean("ready"), is(false));
    String checks = ready.data().getJsonArray("checks").toString();
    assertThat(checks, containsString("CALLBACK_CONFIGURED"));
    assertThat(
        "the check says what only the operator can do",
        checks,
        containsString("storeql.sso.callback-url"));
    JsonObject callback =
        ready.data().getJsonArray("checks").getValuesAs(JsonObject.class).stream()
            .filter(c -> "CALLBACK_CONFIGURED".equals(c.getString("code")))
            .findFirst()
            .orElseThrow();
    assertThat(callback.getBoolean("satisfied"), is(false));

    // Nothing was waiting for a browser: it is sent back to the app with the reason, not a page.
    Response back =
        target
            .path("/auth/sso/callback")
            .queryParam("code", "x")
            .queryParam("state", "never-issued")
            .request()
            .property("jersey.config.client.followRedirects", false)
            .get();
    assertThat(back.getStatus(), is(303));
    assertThat(back.getHeaderString("Location"), startsWith(APP));
    assertThat(back.getHeaderString("Location"), containsString("sso_error=SSO_STATE_INVALID"));
  }
}
