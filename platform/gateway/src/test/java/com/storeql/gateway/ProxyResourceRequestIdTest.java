package com.storeql.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.http.WritableHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proxy sends the service the id the gateway minted at the door, not one of its own, and relays
 * the code a service names for an error.
 */
class ProxyResourceRequestIdTest {

  @Test
  @DisplayName("The id forwarded upstream is the one the id filter put on the request")
  void theMintedIdIsUsed() {
    var inbound = mock(jakarta.ws.rs.core.HttpHeaders.class);
    String minted = Ids.newId().toString();
    when(inbound.getHeaderString(HttpHeaders.REQUEST_ID)).thenReturn(minted);
    assertEquals(minted, ProxyResource.requestIdOf(inbound));
    assertEquals(
        minted, ProxyResource.requestIdOf(inbound), "the same id however often it is asked");
  }

  @Test
  @DisplayName("A request that never passed the id filter still gets a UUIDv7, each its own")
  void aMissingIdIsMinted() {
    var inbound = mock(jakarta.ws.rs.core.HttpHeaders.class);
    when(inbound.getHeaderString(HttpHeaders.REQUEST_ID)).thenReturn(null, "", "  ");
    String first = ProxyResource.requestIdOf(inbound);
    String second = ProxyResource.requestIdOf(inbound);
    String third = ProxyResource.requestIdOf(inbound);
    for (String id : new String[] {first, second, third}) {
      assertTrue(Ids.isV7(Ids.parse(id)), id);
    }
    assertNotEquals(first, second);
    assertNotEquals(second, third);
  }

  @Test
  @DisplayName("The answer relayed to the client carries the id it was given")
  void theRelayedAnswerCarriesTheId() {
    // relay() builds the answer around the id it is handed; a connectivity failure is the case that
    // needs no upstream.
    GatewayConfig config = new GatewayConfig();
    config.circuitBreakerFailureThreshold = 5;
    config.circuitBreakerOpenSeconds = 10;
    UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker();
    breaker.config = config;
    ProxyResource resource = new ProxyResource();
    resource.breaker = breaker;
    String id = Ids.newId().toString();
    Response r =
        resource.relay(
            () -> {
              throw new IllegalStateException("connection refused");
            },
            "svc",
            id);
    assertEquals(502, r.getStatus());
    assertEquals(id, r.getHeaderString(HttpHeaders.REQUEST_ID));
  }

  @Test
  @DisplayName(
      "A service's X-Error-Code crosses the edge, so the health screen can name the failure")
  void theErrorCodeIsRelayed() {
    var upstream = WritableHeaders.create();
    upstream.set(HeaderNames.create("X-Error-Code"), "ORDER_CLOSED");
    upstream.set(HeaderNames.create("X-Internal-Node"), "order-svc-7");
    var relayed = ProxyResource.relayedResponseHeaders(upstream);
    assertEquals("ORDER_CLOSED", relayed.get("X-Error-Code"));
    assertEquals(1, relayed.size(), "nothing else a service sets crosses with it");
  }
}
