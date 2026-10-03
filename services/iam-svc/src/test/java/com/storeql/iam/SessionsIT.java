package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.auth0.jwt.JWT;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Sign-in protection slice 3, the iam half: a person lists their own sessions (which one is this
 * one) and signs one out; never another login's.
 */
@HelidonTest
class SessionsIT {

  private static final PostgresSupport PG;
  // Made for this run: no password-shaped constant is kept in the repository.
  private static final String PASSWORD =
      "sessions " + Ids.newId().toString().substring(24) + " passphrase";

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

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private JsonObject post(String path, String json, String userAgent) {
    var b = target.path(path).request();
    if (userAgent != null) b = b.header("User-Agent", userAgent);
    try (Response r = b.post(Entity.entity(json, MediaType.APPLICATION_JSON))) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus() < 300, is(true));
      return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
    }
  }

  private static String email() {
    String t = Ids.newId().toString().replace("-", "");
    return "sess-" + t.substring(t.length() - 12) + "@example.com";
  }

  private JsonObject signIn(String email, String agent) {
    return post(
        "/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", agent);
  }

  private JsonObject register(String email) {
    return post(
        "/auth/register",
        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
        "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0 Safari/537.36");
  }

  private jakarta.ws.rs.client.Invocation.Builder as(String path, String userId, String sid) {
    var b =
        target
            .path(path)
            .request()
            .header("X-Tenant-Id", Ids.newId().toString())
            .header("X-User-Id", userId)
            .header("X-Roles", "CASHIER");
    return sid == null ? b : b.header("X-Session-Id", sid);
  }

  private JsonArray list(String userId, String sid) {
    try (Response r = as("/auth/sessions", userId, sid).get()) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(200));
      return Json.createReader(new StringReader(body)).readObject().getJsonArray("data");
    }
  }

  private int end(String userId, String sessionId) {
    try (Response r = as("/auth/sessions/" + sessionId, userId, null).delete()) {
      return r.getStatus();
    }
  }

  private int refresh(String token) {
    try (Response r =
        target
            .path("/auth/refresh")
            .request()
            .post(
                Entity.entity(
                    "{\"refreshToken\":\"" + token + "\"}", MediaType.APPLICATION_JSON))) {
      return r.getStatus();
    }
  }

  @Test
  void ownListAndEndOne() {
    String email = email();
    JsonObject first = register(email);
    JsonObject second = signIn(email, "Dart/3.5 (dart:io); Android 14");
    String userId = JWT.decode(first.getString("accessToken")).getSubject();
    String sidFirst = JWT.decode(first.getString("accessToken")).getClaim("sid").asString();
    String sidSecond = JWT.decode(second.getString("accessToken")).getClaim("sid").asString();
    assertThat(sidFirst.equals(sidSecond), is(false));

    // A renewal stays in its own session: the same id, and still one row in the list.
    JsonObject renewed =
        post(
            "/auth/refresh",
            "{\"refreshToken\":\"" + second.getString("refreshToken") + "\"}",
            null);
    assertThat(
        JWT.decode(renewed.getString("accessToken")).getClaim("sid").asString(), is(sidSecond));

    JsonArray sessions = list(userId, sidSecond);
    assertThat(sessions.size(), is(2));
    for (var v : sessions) {
      JsonObject s = v.asJsonObject();
      boolean isSecond = s.getString("id").equals(sidSecond);
      assertThat(s.getBoolean("current"), is(isSecond));
      assertThat(
          s.getString("deviceLabel"),
          is(isSecond ? "StoreQL app on Android" : "Chrome on Windows"));
      assertThat(s.containsKey("startedAt") && s.containsKey("lastUsedAt"), is(true));
    }
    // Naming a session that is not the caller's marks nothing.
    for (var v : list(userId, Ids.newId().toString())) {
      assertThat(v.asJsonObject().getBoolean("current"), is(false));
    }

    // Ending the first revokes that chain and no other; ending it twice is 404.
    assertThat(end(userId, sidFirst), is(204));
    assertThat(end(userId, sidFirst), is(404));
    assertThat(list(userId, null).size(), is(1));
    // The other session still renews; the ended one's token is dead (its reuse is theft-shaped,
    // so it is tried last: it revokes the login's sessions).
    assertThat(refresh(renewed.getString("refreshToken")), is(200));
    assertThat(refresh(first.getString("refreshToken")), is(401));
  }

  @Test
  void otherLoginsSessionsAreNeverVisibleOrEndable() {
    JsonObject mine = register(email());
    JsonObject theirs = register(email());
    String me = JWT.decode(mine.getString("accessToken")).getSubject();
    String them = JWT.decode(theirs.getString("accessToken")).getSubject();
    String theirSid = JWT.decode(theirs.getString("accessToken")).getClaim("sid").asString();

    for (var v : list(me, null)) {
      assertThat(v.asJsonObject().getString("id").equals(theirSid), is(false));
    }
    // Naming their session id as mine: 404, and theirs stands.
    try (Response r = as("/auth/sessions/" + theirSid, me, null).delete()) {
      assertThat(r.readEntity(String.class), containsString("SESSION_NOT_FOUND"));
    }
    assertThat(end(me, theirSid), is(404));
    assertThat(end(me, Ids.newId().toString()), is(404));
    assertThat(refresh(theirs.getString("refreshToken")), is(200));
    assertThat(list(them, null).size(), is(1));
  }
}
