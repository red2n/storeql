package com.storeql.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.web.HttpHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proxy forwards by allowlist, so a header the filter stamps but the list omits vanishes
 * silently — which is how X-Store-Ids never reached a service (SJ-D46). This holds the list to
 * every identity header the filter is responsible for.
 */
class ProxyResourceForwardedHeadersTest {

  @Test
  @DisplayName("Every identity header the filter stamps is forwarded upstream")
  void everyStampedIdentityHeaderIsForwarded() {
    for (String header :
        new String[] {
          HttpHeaders.TENANT_ID,
          HttpHeaders.USER_ID,
          HttpHeaders.USER_EMAIL,
          HttpHeaders.ROLES,
          HttpHeaders.STORE_IDS,
          HttpHeaders.PERMISSIONS,
          HttpHeaders.AUTH_SCOPE,
          HttpHeaders.AUTH_METHODS,
          HttpHeaders.SESSION_ID
        }) {
      assertTrue(
          ProxyResource.FORWARDED_HEADERS.contains(header),
          header + " is stamped by JwtAuthFilter but not forwarded by ProxyResource");
    }
  }

  @Test
  @DisplayName("The retry key the client owns is forwarded too")
  void idempotencyKeyIsForwarded() {
    assertTrue(ProxyResource.FORWARDED_HEADERS.contains(HttpHeaders.IDEMPOTENCY_KEY));
  }
}
