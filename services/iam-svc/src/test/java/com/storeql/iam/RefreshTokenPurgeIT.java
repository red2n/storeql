package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.auth0.jwt.JWT;
import com.storeql.iam.service.RefreshTokenPurge;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Refresh tokens expired past the retention are deleted; live tokens, recently expired ones and
 * other logins' tokens are not.
 */
@HelidonTest
class RefreshTokenPurgeIT {

  private static final PostgresSupport PG;
  private static final String PASSWORD =
      "purge " + Ids.newId().toString().substring(24) + " passphrase";

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
  @Inject RefreshTokenPurge purge;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private UUID register() {
    String t = Ids.newId().toString().replace("-", "");
    String email = "purge-" + t.substring(t.length() - 12) + "@example.com";
    try (Response r =
        target
            .path("/auth/register")
            .request()
            .post(
                Entity.entity(
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                    MediaType.APPLICATION_JSON))) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus() < 300, is(true));
      String access =
          Json.createReader(new StringReader(body))
              .readObject()
              .getJsonObject("data")
              .getString("accessToken");
      return Ids.parse(JWT.decode(access).getSubject());
    }
  }

  /** A connection in iam-svc's own schema, where the service migrates and writes. */
  private static java.sql.Connection iam() throws SQLException {
    var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
    c.setSchema("iam");
    return c;
  }

  private int rows(UUID user) throws SQLException {
    try (var c = iam();
        var ps = c.prepareStatement("SELECT count(*) FROM refresh_tokens WHERE user_id = ?")) {
      ps.setObject(1, user);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private void expire(UUID user, String interval, boolean revoked) throws SQLException {
    try (var c = iam();
        var ps =
            c.prepareStatement(
                "UPDATE refresh_tokens SET expires_at = now() - ?::interval, revoked = ?"
                    + " WHERE user_id = ?")) {
      ps.setString(1, interval);
      ps.setBoolean(2, revoked);
      ps.setObject(3, user);
      ps.executeUpdate();
    }
  }

  @Test
  void onlyTokensExpiredPastTheRetentionGo() throws SQLException {
    UUID old = register();
    UUID recent = register();
    UUID live = register();
    expire(old, "30 days", true);
    expire(recent, "1 day", true);
    assertThat(rows(old), is(1));

    int removed = purge.runQuietly();

    assertThat(removed >= 1, is(true));
    assertThat("expired long ago: deleted", rows(old), is(0));
    assertThat("revoked but only just expired: kept for reuse detection", rows(recent), is(1));
    assertThat("a live token is never touched", rows(live), is(1));
  }
}
