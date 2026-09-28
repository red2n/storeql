package com.storeql.purchase.client.accounting;

import com.storeql.purchase.domain.Accounting;
import jakarta.json.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * OAuth 2.0 token refresh (RFC 6749 §6), which the three packages share: the refresh token posted
 * as a form to the token endpoint under the client's basic credentials, answered with a new access
 * token, its lifetime and, where the package rotates them, a new refresh token to keep.
 */
final class OAuthRefresh {

  private final String tokenUrl;
  private final PackageHttp http;

  OAuthRefresh(String tokenUrl, PackageHttp http) {
    this.tokenUrl = tokenUrl;
    this.http = http;
  }

  static OAuthRefresh forTest(String tokenUrl) {
    return new OAuthRefresh(tokenUrl, new PackageHttp(Duration.ofSeconds(5)));
  }

  Accounting.Credentials refresh(Accounting.Credentials c, Instant now) {
    if (!c.canRefresh()) {
      throw new AccountingPackage.Refused(
          401,
          "the access token cannot be refreshed: no refresh token, client id or secret",
          false);
    }
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (c.clientId() + ":" + c.clientSecret()).getBytes(StandardCharsets.UTF_8));
    String form =
        "grant_type=refresh_token&refresh_token="
            + URLEncoder.encode(c.refreshToken(), StandardCharsets.UTF_8);
    PackageHttp.Reply reply =
        http.send(
            "POST",
            tokenUrl,
            Map.of("Authorization", "Basic " + basic, "Accept", "application/json"),
            "application/x-www-form-urlencoded",
            form);
    JsonObject json = reply.json();
    if (!reply.ok()) {
      String error = PackageHttp.text(json, "error");
      String description = PackageHttp.text(json, "error_description");
      throw new AccountingPackage.Refused(
          reply.status(),
          "the token endpoint refused the refresh: "
              + (error == null ? "HTTP " + reply.status() : error)
              + (description == null ? "" : " — " + description),
          false);
    }
    String access = PackageHttp.text(json, "access_token");
    if (access == null || access.isBlank()) {
      throw new AccountingPackage.Unreachable(
          "the token endpoint answered no access token", true, null);
    }
    String refresh = PackageHttp.text(json, "refresh_token");
    String expiresIn = PackageHttp.text(json, "expires_in");
    Instant expiresAt = null;
    if (expiresIn != null) {
      try {
        expiresAt = now.plusSeconds(Long.parseLong(expiresIn.trim()));
      } catch (NumberFormatException e) {
        expiresAt = null;
      }
    }
    return new Accounting.Credentials(
        access,
        refresh == null || refresh.isBlank() ? c.refreshToken() : refresh,
        c.clientId(),
        c.clientSecret(),
        expiresAt);
  }
}
