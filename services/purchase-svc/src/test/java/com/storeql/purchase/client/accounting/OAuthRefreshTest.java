package com.storeql.purchase.client.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.purchase.domain.Accounting;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three packages issue OAuth 2.0 tokens and take them back the same way (RFC 6749 §6): a
 * refresh token posted to the token endpoint under the client's basic credentials answers a new
 * access token, its lifetime and, often, a new refresh token to keep instead of the old.
 */
class OAuthRefreshTest {

  private static PackageStub stub;

  @BeforeAll
  static void start() {
    stub = PackageStub.start(r -> new PackageStub.Answer(200, "{}"));
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  @Test
  @DisplayName(
      "A refresh posts the token under the client's basic credentials and keeps what comes back")
  void refreshes() {
    stub.answerWith(
        r ->
            new PackageStub.Answer(
                200,
                "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":1800,\"token_type\":\"Bearer\"}"));
    Accounting.Credentials before =
        new Accounting.Credentials(
            "old-access",
            "old-refresh",
            "client-1",
            "secret-1",
            Instant.parse("2026-09-23T09:00:00Z"));
    Instant now = Instant.parse("2026-09-23T08:59:30Z");
    Accounting.Credentials after = OAuthRefresh.forTest(stub.url() + "/token").refresh(before, now);
    assertEquals("new-access", after.accessToken());
    assertEquals("new-refresh", after.refreshToken(), "a rotated refresh token replaces the old");
    assertEquals("client-1", after.clientId());
    assertEquals("secret-1", after.clientSecret());
    assertEquals(now.plusSeconds(1800), after.expiresAt());

    PackageStub.Request sent = stub.last();
    assertEquals("POST", sent.method());
    assertEquals("/token", sent.path());
    assertEquals(
        "Basic "
            + Base64.getEncoder()
                .encodeToString("client-1:secret-1".getBytes(StandardCharsets.UTF_8)),
        sent.header("Authorization"));
    assertTrue(sent.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
    assertTrue(sent.body().contains("grant_type=refresh_token"));
    assertTrue(sent.body().contains("refresh_token=old-refresh"));
    assertFalse(
        sent.body().contains("client_secret"), "the secret travels in the header, not the body");
  }

  @Test
  @DisplayName(
      "A refresh token that is not rotated is kept; a refusal says why; no refresh token means no refresh")
  void edges() {
    stub.answerWith(
        r -> new PackageStub.Answer(200, "{\"access_token\":\"a2\",\"expires_in\":3600}"));
    Accounting.Credentials kept =
        OAuthRefresh.forTest(stub.url() + "/token")
            .refresh(
                new Accounting.Credentials("a1", "r1", "c", "s", null),
                Instant.parse("2026-09-23T09:00:00Z"));
    assertEquals("r1", kept.refreshToken());
    assertEquals(Instant.parse("2026-09-23T10:00:00Z"), kept.expiresAt());

    stub.answerWith(
        r ->
            new PackageStub.Answer(
                400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"The refresh token has expired\"}"));
    AccountingPackage.Refused refused =
        assertThrows(
            AccountingPackage.Refused.class,
            () ->
                OAuthRefresh.forTest(stub.url() + "/token")
                    .refresh(
                        new Accounting.Credentials("a1", "r1", "c", "s", null), Instant.now()));
    assertTrue(refused.getMessage().contains("invalid_grant"), refused.getMessage());
    assertTrue(refused.getMessage().contains("has expired"), refused.getMessage());
    assertFalse(refused.retryable(), "a refresh token that is gone needs a person, not a retry");

    Accounting.Credentials bearerOnly =
        new Accounting.Credentials("a1", null, null, null, Instant.parse("2026-09-23T09:00:00Z"));
    assertFalse(bearerOnly.canRefresh());
    assertTrue(bearerOnly.expiringBy(Instant.parse("2026-09-23T08:59:30Z"), 60));
    assertFalse(bearerOnly.expiringBy(Instant.parse("2026-09-23T08:00:00Z"), 60));
    assertNotNull(new Accounting.Credentials("a1", null, null, null, null));
    assertFalse(
        new Accounting.Credentials("a1", null, null, null, null).expiringBy(Instant.now(), 60),
        "no expiry known: not expiring");
  }
}
