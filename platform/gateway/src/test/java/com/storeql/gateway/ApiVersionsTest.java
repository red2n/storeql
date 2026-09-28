package com.storeql.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The API versioning policy (22.8): one current version, the unversioned alias deprecated with a
 * date and a sunset (RFC 9745, RFC 8594), an unknown version refused, and the whole of it described
 * in one public document.
 */
class ApiVersionsTest {

  private static final ApiVersions.Policy POLICY =
      ApiVersions.Policy.of("v1", "v1", "2026-09-23", "2027-09-30");
  private static final Instant BEFORE_SUNSET = Instant.parse("2026-09-23T12:00:00Z");
  private static final Instant AFTER_SUNSET = Instant.parse("2027-09-30T00:00:00Z");

  @Test
  @DisplayName(
      "A versioned path on a version that exists passes; the alias passes until its sunset")
  void whatPasses() {
    assertEquals(
        new ApiVersions.Ok(false),
        ApiVersions.check(POLICY, "api/v1/order-svc/orders", BEFORE_SUNSET));
    assertEquals(
        new ApiVersions.Ok(false), ApiVersions.check(POLICY, "/api/v1/order-svc", BEFORE_SUNSET));
    assertEquals(
        new ApiVersions.Ok(true), ApiVersions.check(POLICY, "api/order-svc/orders", BEFORE_SUNSET));
    assertEquals(
        new ApiVersions.Ok(true),
        ApiVersions.check(POLICY, "/api/tenant-svc/plans", AFTER_SUNSET.minusSeconds(1)));
    // Not the proxy at all: nothing to say.
    assertEquals(
        new ApiVersions.Ok(false), ApiVersions.check(POLICY, "health/ready", BEFORE_SUNSET));
    assertEquals(
        new ApiVersions.Ok(false),
        ApiVersions.check(POLICY, ".well-known/security.txt", AFTER_SUNSET));
    // The description of the policy itself is not a service call and never deprecated or retired.
    assertEquals(
        new ApiVersions.Ok(false), ApiVersions.check(POLICY, "api/versions", AFTER_SUNSET));
  }

  @Test
  @DisplayName("A version nobody published is unknown, whatever follows it")
  void anUnknownVersion() {
    assertEquals(
        new ApiVersions.Unknown("v9"),
        ApiVersions.check(POLICY, "api/v9/order-svc/orders", BEFORE_SUNSET));
    assertEquals(
        new ApiVersions.Unknown("v0"), ApiVersions.check(POLICY, "/api/v0", BEFORE_SUNSET));
    // Two versions published: both pass, the third does not.
    ApiVersions.Policy two = ApiVersions.Policy.of("v2", "v1, v2", "2026-09-23", "2027-09-30");
    assertEquals(new ApiVersions.Ok(false), ApiVersions.check(two, "api/v1/x", BEFORE_SUNSET));
    assertEquals(new ApiVersions.Ok(false), ApiVersions.check(two, "api/v2/x", BEFORE_SUNSET));
    assertEquals(new ApiVersions.Unknown("v3"), ApiVersions.check(two, "api/v3/x", BEFORE_SUNSET));
  }

  @Test
  @DisplayName("From the sunset day the alias is retired")
  void theAliasRetires() {
    assertInstanceOf(
        ApiVersions.Retired.class, ApiVersions.check(POLICY, "api/order-svc/orders", AFTER_SUNSET));
    assertInstanceOf(
        ApiVersions.Retired.class,
        ApiVersions.check(POLICY, "api/iam-svc/auth/login", AFTER_SUNSET.plusSeconds(86400)));
    // A versioned call is untouched by the alias's retirement.
    assertEquals(
        new ApiVersions.Ok(false),
        ApiVersions.check(POLICY, "api/v1/order-svc/orders", AFTER_SUNSET));
  }

  @Test
  @DisplayName(
      "The alias's headers: Deprecation as an RFC 9745 date, Sunset as an RFC 8594 HTTP date, Link to the successor")
  void theHeaders() {
    assertEquals("@1790121600", ApiVersions.deprecationHeader(POLICY));
    assertEquals("Thu, 30 Sep 2027 00:00:00 GMT", ApiVersions.sunsetHeader(POLICY));
    assertEquals("</api/v1>; rel=\"successor-version\"", ApiVersions.successorLink(POLICY));
    assertEquals("</api/v1>; rel=\"latest-version\"", ApiVersions.latestLink(POLICY));
  }

  @Test
  @DisplayName(
      "The document: the current version, every version with its status and dates, the policy in a sentence")
  void theDocument() {
    ApiVersions.Description d = ApiVersions.describe(POLICY, BEFORE_SUNSET);
    assertEquals("v1", d.current());
    assertEquals("/api/v1/{service}/openapi", d.openapi());
    assertTrue(d.policy().contains("twelve months"), d.policy());
    assertEquals(2, d.versions().size());

    ApiVersions.Version v1 = d.versions().get(0);
    assertEquals("v1", v1.version());
    assertEquals("current", v1.status());
    assertEquals("/api/v1/{service}", v1.base());
    assertNull(v1.deprecatedSince());
    assertNull(v1.sunset());
    assertNull(v1.successor());

    ApiVersions.Version alias = d.versions().get(1);
    assertEquals("unversioned", alias.version());
    assertEquals("deprecated", alias.status());
    assertEquals("/api/{service}", alias.base());
    assertEquals("2026-09-23", alias.deprecatedSince());
    assertEquals("2027-09-30", alias.sunset());
    assertEquals("/api/v1", alias.successor());

    assertEquals("retired", ApiVersions.describe(POLICY, AFTER_SUNSET).versions().get(1).status());
  }

  @Test
  @DisplayName("Two published versions: the older one is supported, the current one current")
  void twoVersions() {
    ApiVersions.Policy two = ApiVersions.Policy.of("v2", "v1,v2", "2026-09-23", "2027-09-30");
    List<ApiVersions.Version> versions = ApiVersions.describe(two, BEFORE_SUNSET).versions();
    assertEquals(3, versions.size());
    assertEquals("supported", versions.get(0).status());
    assertEquals("v1", versions.get(0).version());
    assertEquals("current", versions.get(1).status());
    assertEquals("v2", versions.get(1).version());
    assertEquals("/api/v2", versions.get(2).successor());
  }

  @Test
  @DisplayName(
      "A policy that names a current version it does not publish, or a sunset before the deprecation, is refused at start")
  void aPolicyMustBeConsistent() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> ApiVersions.Policy.of("v2", "v1", "2026-09-23", "2027-09-30"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> ApiVersions.Policy.of("v1", "v1", "2027-09-30", "2026-09-23"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> ApiVersions.Policy.of("v1", "v1,latest", "2026-09-23", "2027-09-30"));
    assertEquals(LocalDate.parse("2027-09-30"), POLICY.aliasSunset());
  }
}
