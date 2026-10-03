package com.storeql.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.gateway.ProxyResource.Route;
import org.junit.jupiter.api.Test;

class ProxyRouteTest {

  @Test
  void unversionedAliasRoutesUnchanged() {
    Route r = Route.of("order-svc", "orders/123");
    assertEquals("order-svc", r.service());
    assertEquals("orders/123", r.path());
  }

  @Test
  void versionSegmentIsPeeledOffToTheRealService() {
    Route r = Route.of("v1", "order-svc/orders/123");
    assertEquals("order-svc", r.service());
    assertEquals("orders/123", r.path());
  }

  @Test
  void versionedServiceRootHasEmptyPath() {
    Route r = Route.of("v1", "order-svc");
    assertEquals("order-svc", r.service());
    assertEquals("", r.path());
  }

  @Test
  void anyNumericVersionIsAccepted() {
    Route r = Route.of("v2", "product-svc/catalog/products");
    assertEquals("product-svc", r.service());
    assertEquals("catalog/products", r.path());
  }

  @Test
  void serviceNamesAreNotMistakenForVersions() {
    // A real service name that merely contains a digit must not be treated as a version token.
    Route r = Route.of("iam-svc", "auth/login");
    assertEquals("iam-svc", r.service());
    assertEquals("auth/login", r.path());
  }

  @Test
  void onlyVAndDigitsIsAVersionSegment() {
    assertTrue(ApiVersions.isVersionSegment("v1"));
    assertTrue(ApiVersions.isVersionSegment("v12"));
    assertFalse(ApiVersions.isVersionSegment("v"));
    assertFalse(ApiVersions.isVersionSegment("v1x"));
    assertFalse(ApiVersions.isVersionSegment("x1"));
    assertFalse(ApiVersions.isVersionSegment(null));
    assertEquals("v1x", Route.of("v1x", "a/b").service());
  }
}
